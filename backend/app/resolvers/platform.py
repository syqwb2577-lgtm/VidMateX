"""Platform resolvers: YouTube, Facebook, TikTok.

Default behavior (no yt-dlp): fetch metadata ONLY via each platform's official
oEmbed endpoint (title, author, thumbnail). MUST NOT produce download stream
URLs. Returns downloadAvailable=false with a clear message.

Optional opt-in: if the environment variable YTDLP_PATH is set to a non-empty
path of a yt-dlp executable, the platform resolvers will invoke it to obtain
real formats + stream URLs. This is DISABLED BY DEFAULT. The user explicitly
opts in by setting YTDLP_PATH, and it is documented as the user's own
responsibility (compliance with platform ToS, etc.). The input URL is always
validated through ssrf_guard before being passed to yt-dlp.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import shlex
from urllib.parse import quote_plus, urlparse

import httpx

from ..schemas import (
    MediaKind,
    MediaTrack,
    SourceType,
    SubtitleFormat,
    SubtitleKind,
    SubtitleTrack,
    VideoMetadata,
)
from ..ssrf_guard import (
    SSRFBlockedError,
    UpstreamError,
    guarded_get_text,
    guarded_request,
    validate_url_full,
)
from .base import BaseResolver, ExtractResult
from .direct_url import DirectUrlResolver, _subtitle_content_type_allowed

YTDLP_PATH = os.environ.get("YTDLP_PATH", "").strip()
YTDLP_TIMEOUT = int(os.environ.get("YTDLP_TIMEOUT", "60"))

NOT_PERMITTED_MESSAGE = (
    "This platform does not permit downloading via this integration. "
    "To enable downloads, deploy a compliant resolver and set the YTDLP_PATH "
    "environment variable (opt-in, your responsibility). See backend/README.md."
)


def _ytdlp_enabled() -> bool:
    return bool(YTDLP_PATH)


async def _run_ytdlp(url: str) -> dict:
    """Invoke yt-dlp -j on a pre-validated URL. Returns parsed JSON dict.

    The URL passes through the full SSRF validator (scheme/length/DNS) before
    the subprocess is spawned. yt-dlp runs via create_subprocess_exec with an
    argument vector (no shell), so the URL cannot inject shell metacharacters.
    """
    if not YTDLP_PATH:
        raise UpstreamError("yt-dlp wrapper is not enabled")
    validate_url_full(url)
    try:
        proc = await asyncio.create_subprocess_exec(
            YTDLP_PATH,
            "-j",
            "--no-playlist",
            "--no-warnings",
            "--",
            url,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=YTDLP_TIMEOUT)
    except FileNotFoundError as exc:
        raise UpstreamError(f"yt-dlp executable not found at YTDLP_PATH={YTDLP_PATH}") from exc
    except asyncio.TimeoutError as exc:
        raise UpstreamError(f"yt-dlp timed out after {YTDLP_TIMEOUT}s") from exc
    if proc.returncode != 0:
        err = stderr.decode("utf-8", errors="replace").strip()[:500]
        raise UpstreamError(f"yt-dlp exited with code {proc.returncode}: {err}")
    try:
        return json.loads(stdout.decode("utf-8", errors="replace"))
    except json.JSONDecodeError as exc:
        raise UpstreamError("yt-dlp returned unparseable JSON") from exc


def _map_ytdlp_formats(info: dict) -> tuple[list[MediaTrack], dict[str, str], list[SubtitleTrack]]:
    """Map yt-dlp info JSON to MediaTracks + streamUrls + SubtitleTracks."""
    formats: list[MediaTrack] = []
    stream_urls: dict[str, str] = {}
    for f in info.get("formats", []) or []:
        fid = str(f.get("format_id") or f.get("id") or "")
        if not fid or fid in stream_urls:
            continue
        url = f.get("url")
        if not url:
            continue
        vcodec = f.get("vcodec")
        acodec = f.get("acodec")
        is_video = vcodec not in (None, "none")
        is_audio = acodec not in (None, "none")
        # Skip non-media entries (thumbnails, manifests, attachments) so we
        # never fabricate a video/audio track from a metadata-only format.
        if not is_video and not is_audio:
            continue
        kind = MediaKind.VIDEO if is_video else MediaKind.AUDIO
        ext = f.get("ext") or "mp4"
        tbr = f.get("tbr")
        formats.append(
            MediaTrack(
                trackId=fid,
                kind=kind,
                container=ext,
                heightPx=f.get("height"),
                widthPx=f.get("width"),
                fps=f.get("fps"),
                vcodec=vcodec if vcodec not in (None, "none") else None,
                acodec=acodec if acodec not in (None, "none") else None,
                bitrateBps=int(tbr * 1000) if isinstance(tbr, (int, float)) else None,
                estimatedBytes=f.get("filesize") or f.get("filesize_approx"),
                language=f.get("language"),
                resumable=True,
            )
        )
        stream_urls[fid] = url

    subtitles: list[SubtitleTrack] = []
    for lang, tracks in (info.get("subtitles") or {}).items():
        if not tracks:
            continue
        # Prefer vtt/srt; pick first track.
        ext = tracks[0].get("ext", "vtt")
        fmt_enum = SubtitleFormat.VTT
        if ext == "srt":
            fmt_enum = SubtitleFormat.SRT
        elif ext == "ass":
            fmt_enum = SubtitleFormat.ASS
        subtitles.append(
            SubtitleTrack(
                trackId=f"ytdlp-sub:{lang}:{ext}",
                languageCode=lang,
                languageName=lang,
                kind=SubtitleKind.AUTOMATIC,
                isTranslatable=True,
                defaultFormat=fmt_enum,
            )
        )
    return formats, stream_urls, subtitles


class _PlatformBase(BaseResolver):
    """Shared platform logic: oEmbed metadata + optional yt-dlp."""

    source_type: SourceType = SourceType.UNKNOWN
    oembed_endpoint: str | None = None
    domains: tuple[str, ...] = ()

    def supports(self, url: str) -> bool:
        try:
            host = (urlparse(url).hostname or "").lower()
        except Exception:
            return False
        return any(host == d or host.endswith("." + d) for d in self.domains)

    def _source_id(self, url: str) -> str:
        return urlparse(url).path.strip("/")[:120] or "unknown"

    async def _oembed_metadata(self, url: str) -> VideoMetadata:
        title = "Untitled"
        author = None
        thumbnail = None
        if self.oembed_endpoint:
            oembed_url = self.oembed_endpoint + quote_plus(url)
            try:
                text = await guarded_get_text(oembed_url, max_bytes=1 * 1024 * 1024)
                data = json.loads(text)
                title = data.get("title") or title
                author = data.get("author_name")
                thumbnail = data.get("thumbnail_url")
            except (
                UpstreamError,
                SSRFBlockedError,
                json.JSONDecodeError,
                httpx.HTTPError,
            ):
                # oEmbed is optional: any transport/timeout/parse failure
                # falls back to generic metadata (still no downloads).
                pass
        return VideoMetadata(
            sourceType=self.source_type,
            sourceUrl=url,
            sourceId=self._source_id(url),
            title=title,
            author=author,
            thumbnailUrl=thumbnail,
        )

    async def extract(self, url: str) -> ExtractResult:
        validate_url_full(url)

        if _ytdlp_enabled():
            info = await _run_ytdlp(url)
            metadata = VideoMetadata(
                sourceType=self.source_type,
                sourceUrl=url,
                sourceId=str(info.get("id") or self._source_id(url)),
                title=info.get("title") or "Untitled",
                author=info.get("uploader") or info.get("channel"),
                durationSec=info.get("duration"),
                thumbnailUrl=info.get("thumbnail"),
                description=info.get("description"),
            )
            formats, stream_urls, subtitles = _map_ytdlp_formats(info)
            return ExtractResult(
                metadata=metadata,
                formats=formats,
                subtitles=subtitles,
                streamUrls=stream_urls,
                downloadAvailable=bool(formats),
                message=None if formats else NOT_PERMITTED_MESSAGE,
            )

        # Default: metadata only, no downloads.
        metadata = await self._oembed_metadata(url)
        return ExtractResult(
            metadata=metadata,
            formats=[],
            subtitles=[],
            streamUrls={},
            downloadAvailable=False,
            message=NOT_PERMITTED_MESSAGE,
        )

    async def fetch_subtitle(self, url: str, track_id: str, fmt: str) -> tuple[str, str]:
        validate_url_full(url)
        if not _ytdlp_enabled():
            raise UpstreamError(
                "Subtitle fetch requires the opt-in yt-dlp wrapper (set YTDLP_PATH)."
            )
        # track_id format: ytdlp-sub:<lang>:<ext> — re-run yt-dlp to get subtitle URL.
        if not track_id.startswith("ytdlp-sub:"):
            raise UpstreamError(f"Unknown subtitle trackId: {track_id}")
        info = await _run_ytdlp(url)
        parts = track_id.split(":", 2)
        lang = parts[1] if len(parts) > 1 else ""
        ext = parts[2] if len(parts) > 2 else "vtt"
        tracks = (info.get("subtitles") or {}).get(lang) or []
        sub_url = None
        for t in tracks:
            if t.get("ext") == ext:
                sub_url = t.get("url")
                break
        if not sub_url and tracks:
            sub_url = tracks[0].get("url")
        if not sub_url:
            raise UpstreamError(f"No subtitle URL found for language '{lang}'")
        # Validate the subtitle URL (it's external; still guard).
        validate_url_full(sub_url)
        resp = await guarded_request(
            "GET", sub_url, read_body=True, max_bytes=5 * 1024 * 1024
        )
        if not _subtitle_content_type_allowed(resp.headers.get("content-type"), ext):
            raise UpstreamError(
                "Refusing subtitle download with a non-subtitle Content-Type"
            )
        return resp.text, ext


class YouTubeResolver(_PlatformBase):
    name = "youtube"
    source_type = SourceType.YOUTUBE
    domains = ("youtube.com", "youtu.be", "youtube-nocookie.com")
    oembed_endpoint = "https://www.youtube.com/oembed?format=json&url="


class FacebookResolver(_PlatformBase):
    name = "facebook"
    source_type = SourceType.FACEBOOK
    domains = ("facebook.com", "fb.com", "fb.watch")
    oembed_endpoint = "https://www.facebook.com/plugins/video/oembed.json/?url="


class TikTokResolver(_PlatformBase):
    name = "tiktok"
    source_type = SourceType.TIKTOK
    domains = ("tiktok.com", "tiktokv.com")
    oembed_endpoint = "https://www.tiktok.com/oembed?url="


# Ordered list: platform resolvers first (domain match), then direct_url catch-all.
def build_resolver_chain() -> list[BaseResolver]:
    return [YouTubeResolver(), FacebookResolver(), TikTokResolver(), DirectUrlResolver()]


__all__ = [
    "YouTubeResolver",
    "FacebookResolver",
    "TikTokResolver",
    "build_resolver_chain",
    "YTDLP_PATH",
]
