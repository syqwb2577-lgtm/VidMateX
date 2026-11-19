"""DirectUrlResolver — handles direct media URLs (http/https links to .mp4, .webm,
.mp3, .m4a, etc.).

Strategy:
  1. HEAD request (guarded) to read Content-Type / Content-Length / Accept-Ranges.
  2. If HEAD is not allowed (405/403/501), fall back to a ranged GET probe
     (Range: bytes=0-1023) and inspect response headers.
  3. Map Content-Type to (MediaKind, container); fall back to URL extension.
  4. Probe sidecar subtitle files (.srt/.vtt/.ass, same basename) and offer
     them as SubtitleTracks.
  5. fetch_subtitle downloads the sidecar and returns its text, with best-effort
     srt<->vtt conversion when the requested format differs from the sidecar.

All outbound requests go through ssrf_guard.
"""
from __future__ import annotations

import re
from urllib.parse import urlparse, urlunparse

import httpx

from ..schemas import (
    MediaKind,
    MediaTrack,
    SubtitleFormat,
    SubtitleKind,
    SubtitleTrack,
    VideoMetadata,
    SourceType,
)
from ..ssrf_guard import (
    InvalidUrlError,
    UpstreamError,
    guarded_request,
    validate_url_full,
)
from .base import BaseResolver, ExtractResult

# Content-Type -> (kind, container)
CONTENT_TYPE_MAP: dict[str, tuple[MediaKind, str]] = {
    "video/mp4": (MediaKind.VIDEO, "mp4"),
    "video/webm": (MediaKind.VIDEO, "webm"),
    "video/x-matroska": (MediaKind.VIDEO, "mkv"),
    "video/quicktime": (MediaKind.VIDEO, "mov"),
    "video/x-msvideo": (MediaKind.VIDEO, "avi"),
    "video/ogg": (MediaKind.VIDEO, "ogv"),
    "video/3gpp": (MediaKind.VIDEO, "3gp"),
    "audio/mpeg": (MediaKind.AUDIO, "mp3"),
    "audio/mp3": (MediaKind.AUDIO, "mp3"),
    "audio/mp4": (MediaKind.AUDIO, "m4a"),
    "audio/x-m4a": (MediaKind.AUDIO, "m4a"),
    "audio/aac": (MediaKind.AUDIO, "m4a"),
    "audio/webm": (MediaKind.AUDIO, "webm"),
    "audio/ogg": (MediaKind.AUDIO, "ogg"),
    "application/ogg": (MediaKind.AUDIO, "ogg"),
    "audio/wav": (MediaKind.AUDIO, "wav"),
    "audio/x-wav": (MediaKind.AUDIO, "wav"),
    "audio/flac": (MediaKind.AUDIO, "flac"),
    "application/octet-stream": (MediaKind.VIDEO, "mp4"),  # resolved via extension below
}

# URL extension -> (kind, container)
EXTENSION_MAP: dict[str, tuple[MediaKind, str]] = {
    ".mp4": (MediaKind.VIDEO, "mp4"),
    ".m4v": (MediaKind.VIDEO, "mp4"),
    ".webm": (MediaKind.VIDEO, "webm"),
    ".mkv": (MediaKind.VIDEO, "mkv"),
    ".mov": (MediaKind.VIDEO, "mov"),
    ".avi": (MediaKind.VIDEO, "avi"),
    ".ogv": (MediaKind.VIDEO, "ogv"),
    ".3gp": (MediaKind.VIDEO, "3gp"),
    ".mp3": (MediaKind.AUDIO, "mp3"),
    ".m4a": (MediaKind.AUDIO, "m4a"),
    ".aac": (MediaKind.AUDIO, "m4a"),
    ".wav": (MediaKind.AUDIO, "wav"),
    ".flac": (MediaKind.AUDIO, "flac"),
    ".ogg": (MediaKind.AUDIO, "ogg"),
    ".oga": (MediaKind.AUDIO, "ogg"),
}

SIDECAR_EXTS = ("srt", "vtt", "ass")
SUBTITLE_MAX_BYTES = 5 * 1024 * 1024  # 5 MB cap on subtitle downloads

# Subtitle Content-Type allowlist. Binary media types (video/*, audio/*,
# image/*, generic octet-stream for a non-subtitle extension) are rejected so
# a media endpoint cannot be misused as a subtitle proxy.
SUBTITLE_CONTENT_TYPES_ALLOWED = frozenset(
    {
        "text/plain",
        "text/vtt",
        "text/srt",
        "application/x-subrip",
        "text/ass",
        "text/x-ass",
    }
)


def _subtitle_content_type_allowed(content_type: str | None, ext: str) -> bool:
    """True only for explicit subtitle text types; octet-stream requires a
    known subtitle extension (srt/vtt/ass)."""
    if not content_type:
        return False
    main = content_type.split(";", 1)[0].strip().lower()
    if main in SUBTITLE_CONTENT_TYPES_ALLOWED:
        return True
    return main == "application/octet-stream" and ext in SIDECAR_EXTS


def _validate_sidecar_url(sub_url: str) -> None:
    """Reject sidecar URLs whose rebuilt (urlunparse) path contains a '..'
    path-traversal segment before any request is issued."""
    parsed = urlparse(sub_url)
    for segment in (parsed.path or "").split("/"):
        if segment == "..":
            raise InvalidUrlError(
                "Refusing sidecar URL with a '..' path-traversal segment"
            )

# Timestamp patterns for srt<->vtt conversion
_TS_VTT = re.compile(r"(\d{2}:\d{2}:\d{2})\.(\d{3})")
_TS_SRT = re.compile(r"(\d{2}:\d{2}:\d{2}),(\d{3})")


def _sidecar_url(media_url: str, ext: str) -> str:
    """Derive a same-basename sidecar URL: https://x/v.mp4 -> https://x/v.srt"""
    parsed = urlparse(media_url)
    path = parsed.path or "/"
    if "/" in path:
        dir_part, last_seg = path.rsplit("/", 1)
    else:
        dir_part, last_seg = "", path
    base = last_seg.rsplit(".", 1)[0] if "." in last_seg else last_seg
    new_last = f"{base}.{ext}"
    new_path = f"{dir_part}/{new_last}" if dir_part else new_last
    return urlunparse(parsed._replace(path=new_path))


def _title_from_url(url: str) -> str:
    """Derive a human-readable title from the URL's last path segment."""
    parsed = urlparse(url)
    last = (parsed.path or "").rsplit("/", 1)[-1]
    if not last:
        return parsed.hostname or "Direct media"
    # Strip extension, replace %20 and underscores/hyphens with spaces
    base = last.rsplit(".", 1)[0] if "." in last else last
    import urllib.parse

    base = urllib.parse.unquote(base)
    return re.sub(r"[-_]+", " ", base).strip() or "Direct media"


def _detect_kind_container(
    content_type: str | None, url: str
) -> tuple[MediaKind, str]:
    if content_type:
        ct = content_type.split(";")[0].strip().lower()
        if ct in CONTENT_TYPE_MAP and ct != "application/octet-stream":
            return CONTENT_TYPE_MAP[ct]
    # Fall back to extension
    parsed = urlparse(url)
    path = parsed.path or ""
    ext = "." + path.rsplit(".", 1)[-1].lower() if "." in path else ""
    if ext in EXTENSION_MAP:
        return EXTENSION_MAP[ext]
    # octet-stream with unknown extension -> still unknown
    raise UpstreamError(
        "Could not determine media type from Content-Type or URL extension. "
        "Only direct media URLs (mp4/webm/mp3/m4a/...) are supported."
    )


def _vtt_to_srt(vtt: str) -> str:
    """Best-effort WebVTT -> SRT conversion. Strips WEBVTT header and cue settings."""
    lines = vtt.splitlines()
    # Drop leading WEBVTT line and any header metadata until first blank line
    out: list[str] = []
    cue_num = 1
    in_cue = False
    i = 0
    # Skip WEBVTT header block
    while i < len(lines) and lines[i].strip().upper().startswith("WEBVTT"):
        i += 1
    while i < len(lines) and lines[i].strip() != "":
        i += 1  # skip header metadata lines (NOTE/STYLE/REGION)
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if stripped == "":
            if in_cue:
                out.append("")  # blank line separates cues
                in_cue = False
            i += 1
            continue
        if "-->" in stripped:
            # Cue identifier line may precede; drop it. Convert timestamp.
            ts_line = _TS_VTT.sub(lambda m: f"{m.group(1)},{m.group(2)}", stripped)
            # Strip cue settings after timestamp (everything after last timestamp)
            ts_line = re.sub(
                r"(\d{2}:\d{2}:\d{2},\d{3}\s*-->\s*\d{2}:\d{2}:\d{2},\d{3}).*",
                r"\1",
                ts_line,
            )
            out.append(str(cue_num))
            out.append(ts_line)
            cue_num += 1
            in_cue = True
        elif in_cue:
            out.append(stripped)
        i += 1
    result = "\n".join(out).strip()
    return result + "\n" if result else ""


def _srt_to_vtt(srt: str) -> str:
    """Best-effort SRT -> WebVTT conversion. Strips cue numbers."""
    lines = srt.splitlines()
    out = ["WEBVTT", ""]
    for line in lines:
        stripped = line.strip()
        if stripped.isdigit():
            continue  # drop cue number
        if "-->" in stripped:
            out.append(_TS_SRT.sub(lambda m: f"{m.group(1)}.{m.group(2)}", stripped))
        else:
            out.append(stripped)
    return "\n".join(out).strip() + "\n"


class DirectUrlResolver(BaseResolver):
    name = "direct_url"

    def supports(self, url: str) -> bool:
        """Catch-all: any http(s) URL that passes basic validation.

        The real media-type check happens in extract() via probing. Platform
        resolvers (youtube/facebook/tiktok) are checked before this one.
        """
        try:
            validate_url_full(url)
            return True
        except Exception:
            return False

    async def extract(self, url: str) -> ExtractResult:
        validate_url_full(url)

        # --- Probe 1: HEAD ---
        content_type: str | None = None
        content_length: int | None = None
        accept_ranges = False
        probe_status: int | None = None

        try:
            resp = await guarded_request(
                "HEAD", url, read_body=False, max_bytes=1024
            )
            probe_status = resp.status_code
            if resp.status_code < 400:
                content_type = resp.headers.get("content-type")
                cl = resp.headers.get("content-length")
                if cl and cl.isdigit():
                    content_length = int(cl)
                accept_ranges = (
                    resp.headers.get("accept-ranges", "").lower() == "bytes"
                )
            await resp.aclose()
        except UpstreamError:
            probe_status = None

        # --- Probe 2: ranged GET if HEAD failed / gave nothing useful ---
        if probe_status is None or probe_status >= 400 or content_type is None:
            try:
                resp = await guarded_request(
                    "GET",
                    url,
                    headers={"Range": "bytes=0-1023"},
                    read_body=False,
                    max_bytes=65536,
                )
                if resp.status_code < 400:
                    content_type = resp.headers.get("content-type") or content_type
                    cl = resp.headers.get("content-length")
                    if cl and cl.isdigit():
                        content_length = int(cl)
                    # If server honored Range (206), it supports ranges.
                    accept_ranges = accept_ranges or resp.status_code == 206
                    if resp.headers.get("accept-ranges", "").lower() == "bytes":
                        accept_ranges = True
                await resp.aclose()
            except UpstreamError as exc:
                raise UpstreamError(
                    f"Could not probe direct media URL (HEAD and GET both failed): {exc.message}"
                ) from exc

        kind, container = _detect_kind_container(content_type, url)

        track = MediaTrack(
            trackId="direct-0",
            kind=kind,
            container=container,
            estimatedBytes=content_length,
            resumable=accept_ranges,
        )

        metadata = VideoMetadata(
            sourceType=SourceType.DIRECT_URL,
            sourceUrl=url,
            sourceId="direct",
            title=_title_from_url(url),
        )

        # --- Probe sidecar subtitles ---
        subtitles: list[SubtitleTrack] = []
        for ext in SIDECAR_EXTS:
            sub_url = _sidecar_url(url, ext)
            # Pure URL-math safety check (no IO) before any network call.
            _validate_sidecar_url(sub_url)
            try:
                probe = await guarded_request(
                    "HEAD", sub_url, read_body=False, max_bytes=1024
                )
                status = probe.status_code
                ct = probe.headers.get("content-type")
                await probe.aclose()
            except Exception:
                # Sidecar probes are best-effort: any probe failure (404,
                # timeout, blocked host) simply yields no subtitle track.
                continue
            if status != 200:
                continue
            # Strict Content-Type allowlist for subtitle sidecars.
            if not _subtitle_content_type_allowed(ct, ext):
                continue
            fmt_enum = {
                "srt": SubtitleFormat.SRT,
                "vtt": SubtitleFormat.VTT,
                "ass": SubtitleFormat.ASS,
            }[ext]
            subtitles.append(
                SubtitleTrack(
                    trackId=f"sidecar:{ext}",
                    languageCode="und",
                    languageName="Undetermined",
                    kind=SubtitleKind.CREATOR_PROVIDED,
                    isTranslatable=True,
                    defaultFormat=fmt_enum,
                )
            )

        formats = [track]
        return ExtractResult(
            metadata=metadata,
            formats=formats,
            subtitles=subtitles,
            streamUrls={"direct-0": url} if formats else {},
            # True ONLY because a successful probe mapped a genuine media type
            # (_detect_kind_container raises UpstreamError otherwise).
            downloadAvailable=bool(formats),
            message=None,
        )

    async def fetch_subtitle(self, url: str, track_id: str, fmt: str) -> tuple[str, str]:
        validate_url_full(url)
        if not track_id.startswith("sidecar:"):
            raise InvalidUrlError(f"Unknown subtitle trackId: {track_id}")
        ext = track_id.split(":", 1)[1].lower()
        if ext not in SIDECAR_EXTS:
            raise InvalidUrlError(f"Unsupported sidecar extension: {ext}")

        sub_url = _sidecar_url(url, ext)
        _validate_sidecar_url(sub_url)
        # Download through the guarded client and inspect Content-Type before
        # treating the body as subtitle text (binary media types rejected).
        resp = await guarded_request(
            "GET", sub_url, read_body=True, max_bytes=SUBTITLE_MAX_BYTES
        )
        content_type = resp.headers.get("content-type")
        if not _subtitle_content_type_allowed(content_type, ext):
            raise UpstreamError(
                f"Refusing subtitle download with non-subtitle Content-Type "
                f"{content_type!r} from {sub_url}"
            )
        content = resp.text

        actual = ext
        requested = fmt.lower()
        if requested != ext:
            # Best-effort conversion between srt and vtt only.
            if requested == "srt" and ext == "vtt":
                content = _vtt_to_srt(content)
                actual = "srt"
            elif requested == "vtt" and ext == "srt":
                content = _srt_to_vtt(content)
                actual = "vtt"
            # ass <-> anything: not converted; return native and report actual.
        return content, actual
