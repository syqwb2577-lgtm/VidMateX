"""Tests for DirectUrlResolver — probes mocked via monkeypatch, no network."""
import httpx
import pytest

from app.resolvers.direct_url import (
    DirectUrlResolver,
    _sidecar_url,
    _srt_to_vtt,
    _subtitle_content_type_allowed,
    _validate_sidecar_url,
    _vtt_to_srt,
)
from app.schemas import MediaKind, SubtitleFormat
from app.ssrf_guard import InvalidUrlError, UpstreamError


@pytest.fixture
def resolver():
    return DirectUrlResolver()


def _fake_guarded_request_factory(video_headers, sidecar_status=200):
    """Build a fake guarded_request: video URL -> video_headers; sidecar -> sidecar_status."""

    async def fake_guarded_request(method, url, **kwargs):
        if any(url.endswith(f".{ext}") for ext in ("srt", "vtt", "ass")):
            return httpx.Response(
                sidecar_status, headers={"content-type": "text/plain"}
            )
        return httpx.Response(200, headers=video_headers)

    return fake_guarded_request


@pytest.mark.asyncio
async def test_extract_mp4_success(resolver, monkeypatch):
    monkeypatch.setattr(
        "app.resolvers.direct_url.validate_url_full", lambda url, **kw: url
    )
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory(
            {
                "content-type": "video/mp4",
                "content-length": "15728640",
                "accept-ranges": "bytes",
            }
        ),
    )

    result = await resolver.extract("https://example.com/vids/sample.mp4")

    assert result.metadata.sourceType.value == "DIRECT_URL"
    assert result.metadata.title == "sample"
    assert len(result.formats) == 1
    track = result.formats[0]
    assert track.kind == MediaKind.VIDEO
    assert track.container == "mp4"
    assert track.estimatedBytes == 15728640
    assert track.resumable is True
    assert result.streamUrls == {"direct-0": "https://example.com/vids/sample.mp4"}
    assert result.downloadAvailable is True


@pytest.mark.asyncio
async def test_extract_audio_mp3(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory({"content-type": "audio/mpeg", "content-length": "5000000"}),
    )
    result = await resolver.extract("https://example.com/audio/song.mp3")
    assert result.formats[0].kind == MediaKind.AUDIO
    assert result.formats[0].container == "mp3"


@pytest.mark.asyncio
async def test_extract_falls_back_to_extension_for_octet_stream(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory({"content-type": "application/octet-stream"}),
    )
    result = await resolver.extract("https://example.com/vids/cliplong.webm")
    assert result.formats[0].kind == MediaKind.VIDEO
    assert result.formats[0].container == "webm"


@pytest.mark.asyncio
async def test_extract_unknown_media_type_raises(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory({"content-type": "text/html"}),
    )
    # URL with no media extension + text/html content-type -> unknown
    with pytest.raises(UpstreamError):
        await resolver.extract("https://example.com/page")


@pytest.mark.asyncio
async def test_sidecar_subtitle_detected(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory(
            {"content-type": "video/mp4"}, sidecar_status=200
        ),
    )
    result = await resolver.extract("https://example.com/vids/movie.mp4")
    # .srt, .vtt, .ass all probed and 200 -> 3 subtitle tracks
    kinds = {s.defaultFormat for s in result.subtitles}
    assert SubtitleFormat.SRT in kinds
    assert SubtitleFormat.VTT in kinds
    assert SubtitleFormat.ASS in kinds
    assert all(s.trackId.startswith("sidecar:") for s in result.subtitles)
    assert all(s.languageCode == "und" for s in result.subtitles)


@pytest.mark.asyncio
async def test_fetch_subtitle_srt(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)

    async def fake_request(method, url, **kwargs):
        return httpx.Response(
            200,
            headers={"content-type": "text/plain"},
            content=b"1\n00:00:01,000 --> 00:00:02,000\nHello\n\n",
        )

    monkeypatch.setattr("app.resolvers.direct_url.guarded_request", fake_request)

    content, fmt = await resolver.fetch_subtitle(
        "https://example.com/v/movie.mp4", "sidecar:srt", "srt"
    )
    assert "Hello" in content
    assert fmt == "srt"


@pytest.mark.asyncio
async def test_fetch_subtitle_vtt_to_srt_conversion(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)

    vtt_content = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello world\n"

    async def fake_request(method, url, **kwargs):
        return httpx.Response(
            200,
            headers={"content-type": "text/vtt"},
            content=vtt_content.encode(),
        )

    monkeypatch.setattr("app.resolvers.direct_url.guarded_request", fake_request)

    content, fmt = await resolver.fetch_subtitle(
        "https://example.com/v/movie.mp4", "sidecar:vtt", "srt"
    )
    assert fmt == "srt"
    assert "00:00:01,000" in content  # comma, not dot
    assert "Hello world" in content
    assert "WEBVTT" not in content


# --- sidecar URL derivation ---
def test_sidecar_url_replaces_extension():
    assert (
        _sidecar_url("https://example.com/vids/sample.mp4", "srt")
        == "https://example.com/vids/sample.srt"
    )


def test_sidecar_url_preserves_query():
    assert (
        _sidecar_url("https://example.com/v.mp4?token=abc&x=1", "vtt")
        == "https://example.com/v.vtt?token=abc&x=1"
    )


def test_sidecar_url_no_extension():
    assert (
        _sidecar_url("https://example.com/stream", "ass")
        == "https://example.com/stream.ass"
    )


# --- srt <-> vtt conversion round trip ---
def test_srt_to_vtt_and_back():
    srt = "1\n00:00:01,000 --> 00:00:04,500\nFirst line\nSecond line\n\n2\n00:00:05,000 --> 00:00:06,000\nThird\n"
    vtt = _srt_to_vtt(srt)
    assert vtt.startswith("WEBVTT")
    assert "00:00:01.000" in vtt
    assert "1\n" not in vtt.split("-->")[0]  # cue number stripped
    back = _vtt_to_srt(vtt)
    assert "00:00:01,000" in back
    assert "First line" in back
    assert "Third" in back


def test_vtt_to_srt_strips_webvtt_header():
    vtt = "WEBVTT\nKind: captions\n\n00:00:01.000 --> 00:00:02.000 line:90%\nHi\n"
    srt = _vtt_to_srt(vtt)
    assert "WEBVTT" not in srt
    assert "00:00:01,000" in srt
    assert "line:90%" not in srt
    assert "Hi" in srt


# --- Content-Type allowlist for subtitle sidecars ---
@pytest.mark.parametrize(
    "content_type,ext,allowed",
    [
        ("text/plain", "srt", True),
        ("text/vtt; charset=utf-8", "vtt", True),
        ("text/srt", "srt", True),
        ("application/x-subrip", "srt", True),
        ("text/ass", "ass", True),
        ("text/x-ass", "ass", True),
        ("application/octet-stream", "srt", True),
        ("application/octet-stream", "vtt", True),
        ("application/octet-stream", "ass", True),
        ("application/octet-stream", "mp4", False),
        ("video/mp4", "srt", False),
        ("audio/mpeg", "vtt", False),
        ("image/png", "ass", False),
        ("text/html", "srt", False),
        (None, "srt", False),
        ("", "srt", False),
    ],
)
def test_subtitle_content_type_allowlist(content_type, ext, allowed):
    assert _subtitle_content_type_allowed(content_type, ext) is allowed


# --- Sidecar path traversal guard (post-urlunparse path) ---
def test_sidecar_traversal_segment_rejected():
    with pytest.raises(InvalidUrlError):
        _validate_sidecar_url("https://example.com/vids/../private/movie.srt")


def test_sidecar_normal_url_passes_traversal_check():
    _validate_sidecar_url("https://example.com/vids/movie.srt")  # no raise


@pytest.mark.asyncio
async def test_fetch_subtitle_traversal_rejected(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    with pytest.raises(InvalidUrlError):
        await resolver.fetch_subtitle(
            "https://example.com/a/../movie.mp4", "sidecar:srt", "srt"
        )


@pytest.mark.asyncio
async def test_sidecar_binary_content_type_skipped_on_probe(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)

    async def fake_request(method, url, **kwargs):
        if any(url.endswith(f".{e}") for e in ("srt", "vtt", "ass")):
            return httpx.Response(200, headers={"content-type": "video/mp4"})
        return httpx.Response(
            200,
            headers={"content-type": "video/mp4", "accept-ranges": "bytes"},
        )

    monkeypatch.setattr("app.resolvers.direct_url.guarded_request", fake_request)
    result = await resolver.extract("https://example.com/v/movie.mp4")
    assert result.subtitles == []


@pytest.mark.asyncio
async def test_fetch_subtitle_binary_content_type_rejected(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)

    async def fake_request(method, url, **kwargs):
        return httpx.Response(
            200, headers={"content-type": "video/mp4"}, content=b"\x00\x01\x02"
        )

    monkeypatch.setattr("app.resolvers.direct_url.guarded_request", fake_request)
    with pytest.raises(UpstreamError):
        await resolver.fetch_subtitle(
            "https://example.com/v/movie.mp4", "sidecar:srt", "srt"
        )


# --- resumable flag: a 206 partial response implies range support ---
@pytest.mark.asyncio
async def test_resumable_flag_from_206_partial_response(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)

    async def fake_request(method, url, **kwargs):
        if method == "HEAD":
            return httpx.Response(405)  # HEAD not allowed -> ranged GET fallback
        return httpx.Response(
            206,
            headers={
                "content-type": "video/mp4",
                "content-range": "bytes 0-1023/15728640",
                "content-length": "1024",
            },
        )

    monkeypatch.setattr("app.resolvers.direct_url.guarded_request", fake_request)
    result = await resolver.extract("https://example.com/v/sample.mp4")
    assert result.formats[0].resumable is True


# --- downloadAvailable only with a genuine probed track ---
@pytest.mark.asyncio
async def test_direct_download_available_matches_probed_formats(resolver, monkeypatch):
    monkeypatch.setattr("app.resolvers.direct_url.validate_url_full", lambda url, **kw: url)
    monkeypatch.setattr(
        "app.resolvers.direct_url.guarded_request",
        _fake_guarded_request_factory(
            {"content-type": "audio/mpeg", "content-length": "1000"}
        ),
    )
    result = await resolver.extract("https://example.com/a/song.mp3")
    assert result.downloadAvailable is True
    assert len(result.formats) == 1
    assert result.streamUrls == {"direct-0": "https://example.com/a/song.mp3"}
