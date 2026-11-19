"""Platform resolver tests — metadata-only (no downloads) by default, oEmbed mocked."""
import json

import pytest

from app.resolvers import platform as platform_mod
from app.resolvers.platform import (
    FacebookResolver,
    TikTokResolver,
    YouTubeResolver,
    NOT_PERMITTED_MESSAGE,
)
from app.schemas import SourceType
from app.ssrf_guard import UpstreamError


@pytest.fixture(autouse=True)
def no_network(monkeypatch):
    """Block all real outbound calls; oEmbed returns canned JSON."""
    monkeypatch.setattr("app.resolvers.platform.validate_url_full", lambda url, **kw: url)

    async def fake_oembed(url, **kwargs):
        return '{"title":"Mock Title","author_name":"Mock Author","thumbnail_url":"https://img.example/t.jpg"}'

    monkeypatch.setattr("app.resolvers.platform.guarded_get_text", fake_oembed)


@pytest.mark.parametrize(
    "resolver_cls,url,source_type",
    [
        (YouTubeResolver, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", SourceType.YOUTUBE),
        (YouTubeResolver, "https://youtu.be/dQw4w9WgXcQ", SourceType.YOUTUBE),
        (YouTubeResolver, "https://m.youtube.com/watch?v=abc", SourceType.YOUTUBE),
        (FacebookResolver, "https://www.facebook.com/watch/?v=123", SourceType.FACEBOOK),
        (FacebookResolver, "https://fb.watch/abc123/", SourceType.FACEBOOK),
        (TikTokResolver, "https://www.tiktok.com/@user/video/123", SourceType.TIKTOK),
        (TikTokResolver, "https://vm.tiktok.com/abc123/", SourceType.TIKTOK),
    ],
)
@pytest.mark.asyncio
async def test_platform_metadata_only(resolver_cls, url, source_type):
    resolver = resolver_cls()
    assert resolver.supports(url)
    result = await resolver.extract(url)

    # Metadata present (from mocked oEmbed)
    assert result.metadata.sourceType == source_type
    assert result.metadata.title == "Mock Title"
    assert result.metadata.author == "Mock Author"
    assert result.metadata.thumbnailUrl == "https://img.example/t.jpg"

    # NO downloads — the key compliance guarantee
    assert result.formats == []
    assert result.streamUrls == {}
    assert result.subtitles == []
    assert result.downloadAvailable is False
    assert result.message == NOT_PERMITTED_MESSAGE


@pytest.mark.parametrize(
    "resolver_cls,url",
    [
        (YouTubeResolver, "https://example.com/not-youtube"),
        (FacebookResolver, "https://example.com/not-fb"),
        (TikTokResolver, "https://example.com/not-tiktok"),
    ],
)
def test_platform_does_not_support_other_urls(resolver_cls, url):
    assert not resolver_cls().supports(url)


@pytest.mark.asyncio
async def test_platform_oembed_failure_still_returns_metadata(monkeypatch):
    """If oEmbed fails, fall back to generic metadata — still no downloads."""
    from app.ssrf_guard import UpstreamError

    async def failing_oembed(url, **kwargs):
        raise UpstreamError("oembed down")

    monkeypatch.setattr("app.resolvers.platform.guarded_get_text", failing_oembed)
    resolver = YouTubeResolver()
    result = await resolver.extract("https://www.youtube.com/watch?v=abc")

    assert result.metadata.sourceType == SourceType.YOUTUBE
    assert result.metadata.title == "Untitled"  # fallback
    assert result.formats == []
    assert result.streamUrls == {}
    assert result.downloadAvailable is False


def test_ytdlp_disabled_by_default():
    from app.resolvers.platform import _ytdlp_enabled, YTDLP_PATH

    assert YTDLP_PATH == "" or YTDLP_PATH is None
    assert _ytdlp_enabled() is False


# --- Opt-in yt-dlp branch: monkeypatched subprocess, no real network ---
class _FakeYtdlpProc:
    def __init__(self, stdout: bytes, stderr: bytes = b"", returncode: int = 0):
        self._stdout = stdout
        self._stderr = stderr
        self.returncode = returncode

    async def communicate(self):
        return self._stdout, self._stderr


def _install_fake_ytdlp(monkeypatch, stdout: bytes, *, returncode: int = 0, stderr: bytes = b""):
    calls: list[tuple] = []

    async def fake_create(*args, **kwargs):
        calls.append(args)
        return _FakeYtdlpProc(stdout, stderr, returncode)

    monkeypatch.setattr(platform_mod.asyncio, "create_subprocess_exec", fake_create)
    monkeypatch.setattr(platform_mod, "YTDLP_PATH", "/fake/yt-dlp")
    return calls


@pytest.mark.asyncio
async def test_ytdlp_branch_maps_real_formats(monkeypatch):
    info = {
        "id": "abc123",
        "title": "Mock Title",
        "duration": 123,
        "formats": [
            {
                "format_id": "18",
                "url": "https://cdn.example/18.mp4",
                "ext": "mp4",
                "vcodec": "avc1.42E01E",
                "acodec": "mp4a.40.2",
                "height": 360,
                "width": 640,
            },
            {
                "format_id": "139",
                "url": "https://cdn.example/139.m4a",
                "ext": "m4a",
                "vcodec": "none",
                "acodec": "mp4a.40.5",
            },
            # Non-media entry (thumbnail) must NOT become a track.
            {
                "format_id": "thumb",
                "url": "https://cdn.example/thumb.jpg",
                "ext": "jpg",
                "vcodec": "none",
                "acodec": "none",
            },
        ],
        "subtitles": {"en": [{"ext": "vtt", "url": "https://cdn.example/en.vtt"}]},
    }
    calls = _install_fake_ytdlp(monkeypatch, json.dumps(info).encode())
    resolver = YouTubeResolver()
    result = await resolver.extract("https://www.youtube.com/watch?v=abc123")

    assert result.downloadAvailable is True
    assert [f.trackId for f in result.formats] == ["18", "139"]
    assert result.streamUrls == {
        "18": "https://cdn.example/18.mp4",
        "139": "https://cdn.example/139.m4a",
    }
    assert result.metadata.durationSec == 123
    # Argument vector: executable, flags, "--", URL. No shell, no concat.
    argv = calls[0]
    assert argv[0] == "/fake/yt-dlp"
    assert "-j" in argv and "--no-playlist" in argv
    assert "--" in argv
    assert argv[-1] == "https://www.youtube.com/watch?v=abc123"


@pytest.mark.asyncio
async def test_ytdlp_empty_formats_not_downloadable(monkeypatch):
    _install_fake_ytdlp(
        monkeypatch, json.dumps({"id": "x", "title": "T", "formats": []}).encode()
    )
    resolver = YouTubeResolver()
    result = await resolver.extract("https://www.youtube.com/watch?v=x")
    assert result.formats == []
    assert result.streamUrls == {}
    assert result.downloadAvailable is False
    assert result.message == NOT_PERMITTED_MESSAGE


def test_map_ytdlp_formats_skips_nonmedia_entries():
    formats, urls, _subs = platform_mod._map_ytdlp_formats(
        {
            "formats": [
                {
                    "format_id": "thumb",
                    "url": "https://x/t.jpg",
                    "ext": "jpg",
                    "vcodec": "none",
                    "acodec": "none",
                },
                {
                    "format_id": "18",
                    "url": "https://x/18.mp4",
                    "ext": "mp4",
                    "vcodec": "avc1",
                    "acodec": "mp4a",
                },
            ]
        }
    )
    assert [f.trackId for f in formats] == ["18"]
    assert urls == {"18": "https://x/18.mp4"}


@pytest.mark.asyncio
async def test_ytdlp_rejects_invalid_url_before_subprocess(monkeypatch):
    monkeypatch.setattr(platform_mod, "YTDLP_PATH", "/fake/yt-dlp")
    # Real validator, overriding the autouse passthrough patch.
    from app.ssrf_guard import validate_url_full as real_validate, InvalidUrlError

    monkeypatch.setattr(platform_mod, "validate_url_full", real_validate)
    calls: list = []

    async def fake_create(*args, **kwargs):
        calls.append(args)
        raise AssertionError("subprocess must not run for an invalid URL")

    monkeypatch.setattr(platform_mod.asyncio, "create_subprocess_exec", fake_create)
    with pytest.raises(InvalidUrlError):
        await platform_mod._run_ytdlp("ftp://example.com/video")
    assert calls == []


@pytest.mark.asyncio
async def test_ytdlp_unparseable_json_raises_upstream(monkeypatch):
    _install_fake_ytdlp(monkeypatch, b"this is not json{{{")
    with pytest.raises(UpstreamError):
        await platform_mod._run_ytdlp("https://www.youtube.com/watch?v=x")


@pytest.mark.asyncio
async def test_ytdlp_nonzero_exit_raises_upstream(monkeypatch):
    _install_fake_ytdlp(
        monkeypatch, b"", returncode=1, stderr=b"ERROR: Private video"
    )
    with pytest.raises(UpstreamError):
        await platform_mod._run_ytdlp("https://www.youtube.com/watch?v=x")
