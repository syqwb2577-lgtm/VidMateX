"""Hardening regression tests (mock-based; no real network).

Covers the resolver contract items from the backend hardening spec:
  - 401 unauthorized (missing / wrong / valid X-API-Key; envelope shape)
  - 429 rate-limited path with Retry-After metadata
  - upstream connect/read timeouts mapped to UPSTREAM_ERROR (502)
  - malformed upstream JSON / HTTP error handling (no stack-trace leak)
  - redirect to private/CGNAT IP rejected; HTTPS-policy flag
  - oversized outbound response body rejected
  - direct-url happy-path schema + invalid URL rejection
  - documented schema examples validated against Pydantic models
  - default User-Agent on outbound requests; credential-bearing URLs redacted
"""
from __future__ import annotations

import importlib

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

import app.ssrf_guard as guard
from app.schemas import (
    ErrorEnvelope,
    ExtractRequest,
    ExtractResponse,
    HealthResponse,
    MediaKind,
    MediaTrack,
    SourceType,
    SubtitleRequest,
    SubtitleResponse,
    VideoMetadata,
)
from app.ssrf_guard import (
    DEFAULT_USER_AGENT,
    InvalidUrlError,
    SSRFBlockedError,
    UpstreamError,
    guarded_get_text,
    guarded_request,
    parse_and_validate_url,
    redact_url,
)
from app.resolvers.direct_url import DirectUrlResolver
from app.resolvers.platform import NOT_PERMITTED_MESSAGE, YouTubeResolver


# ---------------------------------------------------------------------------
# Fake outbound clients (replace httpx.AsyncClient; no sockets are opened).
# ---------------------------------------------------------------------------
class _RaisingClient:
    """Fake AsyncClient whose request always raises the given transport error."""

    def __init__(self, exc: Exception):
        self._exc = exc
        self.calls: list[tuple] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def request(self, method, url, **kwargs):
        self.calls.append((method, str(url)))
        raise self._exc


class _ScriptedClient:
    """Fake AsyncClient returning scripted responses per URL substring."""

    def __init__(self, responses):
        self._responses = responses
        self.requested: list[str] = []
        self.constructor_kwargs: dict = {}

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def request(self, method, url, **kwargs):
        self.requested.append(str(url))
        for match, resp in self._responses:
            if match in str(url):
                return resp
        return httpx.Response(200, content=b"ok")


class _ChunkedOversized(httpx.Response):
    """Streams two chunks whose combined size exceeds the small cap."""

    async def aiter_bytes(self, chunk_size=65536):
        yield b"a" * 10
        yield b"b" * 10

    async def aclose(self):
        self._content = b""


def _patch_public_dns(monkeypatch):
    monkeypatch.setattr(
        guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"]
    )


# ---------------------------------------------------------------------------
# 1. Auth: 401 missing / wrong key, exact envelope; valid key accepted.
# ---------------------------------------------------------------------------
def _reload_main_with(monkeypatch, **env):
    import app.main as main

    for key, value in env.items():
        if value is None:
            monkeypatch.delenv(key, raising=False)
        else:
            monkeypatch.setenv(key, value)
    importlib.reload(main)
    return main


def test_auth_missing_header_returns_401_envelope(monkeypatch, fake_resolver):
    main = _reload_main_with(monkeypatch, RESOLVER_API_KEY="secret123")
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    client = TestClient(main.app)
    try:
        for endpoint in ("/extract", "/subtitle"):
            body = (
                {"url": "https://example.com/v.mp4"}
                if endpoint == "/extract"
                else {
                    "url": "https://example.com/v.mp4",
                    "trackId": "sidecar:srt",
                    "format": "srt",
                }
            )
            r = client.post(endpoint, json=body)
            assert r.status_code == 401
            payload = r.json()
            assert set(payload.keys()) == {"error"}
            assert set(payload["error"].keys()) == {"code", "message"}
            assert payload["error"]["code"] == "UNAUTHORIZED"
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


def test_auth_wrong_key_returns_401(monkeypatch, fake_resolver):
    main = _reload_main_with(monkeypatch, RESOLVER_API_KEY="secret123")
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    client = TestClient(main.app)
    try:
        r = client.post(
            "/extract",
            json={"url": "https://example.com/v.mp4"},
            headers={"X-API-Key": "wrong-key"},
        )
        assert r.status_code == 401
        assert r.json()["error"]["code"] == "UNAUTHORIZED"
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


def test_auth_valid_key_accepted_on_protected_routes(monkeypatch, fake_resolver):
    main = _reload_main_with(monkeypatch, RESOLVER_API_KEY="secret123")
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    client = TestClient(main.app)
    try:
        r1 = client.post(
            "/extract",
            json={"url": "https://example.com/v.mp4"},
            headers={"X-API-Key": "secret123"},
        )
        assert r1.status_code == 200
        r2 = client.post(
            "/subtitle",
            json={
                "url": "https://example.com/v.mp4",
                "trackId": "sidecar:srt",
                "format": "srt",
            },
            headers={"X-API-Key": "secret123"},
        )
        assert r2.status_code == 200
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


def test_health_stays_open_when_auth_enabled(monkeypatch):
    main = _reload_main_with(monkeypatch, RESOLVER_API_KEY="secret123")
    client = TestClient(main.app)
    try:
        r = client.get("/health")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


# ---------------------------------------------------------------------------
# 2. Rate limiting: 429 with Retry-After metadata.
# ---------------------------------------------------------------------------
def test_429_response_carries_retry_after(monkeypatch, fake_resolver):
    main = _reload_main_with(monkeypatch, RATE_LIMIT_PER_MINUTE="1")
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    client = TestClient(main.app)
    try:
        r1 = client.post("/extract", json={"url": "https://example.com/v.mp4"})
        assert r1.status_code == 200
        r2 = client.post("/extract", json={"url": "https://example.com/v.mp4"})
        assert r2.status_code == 429
        assert r2.json()["error"]["code"] == "RATE_LIMITED"
        retry_after = r2.headers.get("Retry-After")
        assert retry_after is not None
        assert int(retry_after) >= 1
    finally:
        monkeypatch.setenv("RATE_LIMIT_PER_MINUTE", "1000")
        importlib.reload(main)


# ---------------------------------------------------------------------------
# 3. Upstream timeouts / transport errors -> UpstreamError (mapped to 502).
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_connect_timeout_mapped_to_upstream_error(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _RaisingClient(httpx.ConnectTimeout("connection timed out"))
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    with pytest.raises(UpstreamError):
        await guarded_request("GET", "https://example.com/video.mp4")


@pytest.mark.asyncio
async def test_read_timeout_mapped_to_upstream_error(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _RaisingClient(httpx.ReadTimeout("read timed out"))
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    with pytest.raises(UpstreamError):
        await guarded_request("GET", "https://example.com/video.mp4")


@pytest.mark.asyncio
async def test_guarded_get_text_http_500_mapped(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _ScriptedClient([])
    monkeypatch.setattr(
        guard.httpx,
        "AsyncClient",
        lambda **kw: _ScriptedClient([("example.com", httpx.Response(500, content=b"boom"))]),
    )
    with pytest.raises(UpstreamError):
        await guarded_get_text("https://example.com/oembed")


# ---------------------------------------------------------------------------
# 4. Malformed upstream JSON / failures -> graceful metadata fallback,
#    still metadata-only (no downloads).
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_malformed_oembed_json_falls_back_to_metadata(monkeypatch):
    monkeypatch.setattr(
        "app.resolvers.platform.validate_url_full", lambda url, **kw: url
    )

    async def broken_json(url, **kwargs):
        return "{this is not valid json {{{"

    monkeypatch.setattr("app.resolvers.platform.guarded_get_text", broken_json)
    result = await YouTubeResolver().extract("https://www.youtube.com/watch?v=abc")
    assert result.downloadAvailable is False
    assert result.formats == []
    assert result.streamUrls == {}
    assert result.subtitles == []
    assert result.metadata.sourceType == SourceType.YOUTUBE
    assert result.metadata.title == "Untitled"  # fallback title


@pytest.mark.asyncio
async def test_oembed_connect_error_falls_back_to_metadata(monkeypatch):
    monkeypatch.setattr(
        "app.resolvers.platform.validate_url_full", lambda url, **kw: url
    )

    async def connect_error(url, **kwargs):
        raise httpx.ConnectTimeout("oembed host unreachable")

    monkeypatch.setattr("app.resolvers.platform.guarded_get_text", connect_error)
    result = await YouTubeResolver().extract("https://www.youtube.com/watch?v=abc")
    assert result.downloadAvailable is False
    assert result.formats == []
    assert result.streamUrls == {}
    assert result.message == NOT_PERMITTED_MESSAGE


# ---------------------------------------------------------------------------
# 5. Redirect re-validation: private/CGNAT targets blocked; https policy.
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_redirect_to_cgnat_ip_blocked(monkeypatch):
    redirect_resp = httpx.Response(
        302, headers={"Location": "http://100.64.0.9/internal"}
    )
    fake = _ScriptedClient([("example.com", redirect_resp)])
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)

    def resolve(host):
        if host == "100.64.0.9":
            raise SSRFBlockedError("CGNAT range blocked")
        return ["93.184.216.34"]

    monkeypatch.setattr(guard, "resolve_and_validate_host", resolve)
    with pytest.raises(SSRFBlockedError):
        await guard.guarded_request("GET", "https://example.com/start")
    assert any("example.com" in u for u in fake.requested)


def test_require_https_policy_rejects_plain_http(monkeypatch):
    monkeypatch.setattr(guard, "REQUIRE_HTTPS_UPSTREAM", True)
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("http://example.com/video.mp4")
    scheme, host = parse_and_validate_url("https://example.com/video.mp4")
    assert scheme == "https" and host == "example.com"


# ---------------------------------------------------------------------------
# 6. Oversized outbound response body -> UpstreamError (not served).
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_oversized_outbound_body_aborted(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _ScriptedClient(
        [("example.com", _ChunkedOversized(status_code=200))]
    )
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    with pytest.raises(UpstreamError, match="too large"):
        await guarded_request("GET", "https://example.com/big", max_bytes=15)


# ---------------------------------------------------------------------------
# 7. Direct-url resolver: invalid URL rejected; supported-URL predicate.
# ---------------------------------------------------------------------------
def test_direct_resolver_rejects_non_http_scheme():
    resolver = DirectUrlResolver()
    assert resolver.supports("ftp://example.com/video.mp4") is False
    assert resolver.supports("file:///etc/passwd") is False
    assert resolver.supports("https://example.com/video.mp4") is True


@pytest.mark.asyncio
async def test_direct_resolver_extract_rejects_bad_scheme():
    resolver = DirectUrlResolver()
    with pytest.raises(InvalidUrlError):
        await resolver.extract("ftp://example.com/video.mp4")


# ---------------------------------------------------------------------------
# 8. Outbound request hygiene: User-Agent set; credential URLs redacted.
# ---------------------------------------------------------------------------
@pytest.mark.asyncio
async def test_default_user_agent_sent_on_guarded_request(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _ScriptedClient([])

    def factory(**kwargs):
        fake.constructor_kwargs = kwargs
        return fake

    monkeypatch.setattr(guard.httpx, "AsyncClient", factory)
    await guarded_request("GET", "https://example.com/v.mp4")
    assert fake.constructor_kwargs["headers"]["User-Agent"] == DEFAULT_USER_AGENT


@pytest.mark.asyncio
async def test_caller_user_agent_override_allowed(monkeypatch):
    _patch_public_dns(monkeypatch)
    fake = _ScriptedClient([])

    def factory(**kwargs):
        fake.constructor_kwargs = kwargs
        return fake

    monkeypatch.setattr(guard.httpx, "AsyncClient", factory)
    await guarded_request(
        "GET", "https://example.com/v.mp4", headers={"User-Agent": "custom-probe/1.0"}
    )
    assert fake.constructor_kwargs["headers"]["User-Agent"] == "custom-probe/1.0"


def test_redact_url_strips_userinfo():
    safe = redact_url("https://admin:sup3rsecret@example.com/path?x=1")
    assert "sup3rsecret" not in safe
    assert "admin" not in safe
    assert "example.com" in safe
    # Plain URLs are returned unchanged.
    assert redact_url("https://example.com/x") == "https://example.com/x"


# ---------------------------------------------------------------------------
# 9. Schema validation of the documented contract examples.
# ---------------------------------------------------------------------------
def test_platform_example_validates_metadata_only():
    body = {
        "metadata": {
            "sourceType": "YOUTUBE",
            "sourceUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "sourceId": "watch?v=dQw4w9WgXcQ",
            "title": "Mock Title",
            "author": "Mock Author",
            "durationSec": None,
            "thumbnailUrl": "https://img.example/t.jpg",
            "description": None,
        },
        "formats": [],
        "subtitles": [],
        "streamUrls": {},
        "downloadAvailable": False,
        "message": NOT_PERMITTED_MESSAGE,
    }
    resp = ExtractResponse.model_validate(body)
    assert resp.downloadAvailable is False
    assert resp.streamUrls == {}
    assert resp.formats == []
    assert resp.message == NOT_PERMITTED_MESSAGE


def test_direct_url_example_validates():
    body = {
        "metadata": {
            "sourceType": "DIRECT_URL",
            "sourceUrl": "https://example.com/videos/sample.mp4",
            "sourceId": "direct",
            "title": "sample",
        },
        "formats": [
            {
                "trackId": "direct-0",
                "kind": "VIDEO",
                "container": "mp4",
                "estimatedBytes": 15728640,
                "resumable": True,
            }
        ],
        "subtitles": [],
        "streamUrls": {"direct-0": "https://example.com/videos/sample.mp4"},
        "downloadAvailable": True,
        "message": None,
    }
    resp = ExtractResponse.model_validate(body)
    assert resp.formats[0].trackId == "direct-0"
    assert resp.formats[0].kind == MediaKind.VIDEO
    assert resp.downloadAvailable is True


def test_subtitle_example_validates():
    resp = SubtitleResponse.model_validate(
        {"content": "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n", "format": "srt"}
    )
    assert resp.format == "srt"
    assert "Hello" in resp.content


def test_health_example_validates():
    resp = HealthResponse.model_validate(
        {"status": "ok", "version": "1.0.0", "resolvers": ["direct_url"]}
    )
    assert resp.status == "ok"
    assert "direct_url" in resp.resolvers


def test_error_envelope_example_validates():
    env = ErrorEnvelope.model_validate(
        {"error": {"code": "SSRF_BLOCKED", "message": "host blocked"}}
    )
    assert env.error.code == "SSRF_BLOCKED"
    assert env.error.message == "host blocked"


def test_request_ignores_unknown_fields_consistently():
    req = ExtractRequest(url="https://example.com/", mystery_field=123)
    assert req.url == "https://example.com/"
    assert "mystery_field" not in req.model_dump()
    sub = SubtitleRequest(
        url="https://example.com/", trackId="sidecar:srt", format="srt", extra=True
    )
    assert sub.trackId == "sidecar:srt"


def test_rejects_invalid_enum_and_format_pattern():
    with pytest.raises(ValidationError):
        VideoMetadata(
            sourceType="NO_SUCH_TYPE",
            sourceUrl="https://example.com/",
            sourceId="x",
            title="t",
        )
    with pytest.raises(ValidationError):
        SubtitleRequest(
            url="https://example.com/", trackId="sidecar:srt", format="pdf"
        )


def test_response_model_ignores_unknown_top_level_fields():
    body = {
        "metadata": {
            "sourceType": "DIRECT_URL",
            "sourceUrl": "https://example.com/x.mp4",
            "sourceId": "direct",
            "title": "x",
        },
        "formats": [],
        "subtitles": [],
        "streamUrls": {},
        "downloadAvailable": True,
        "message": None,
        "unexpected_future_field": {"nested": 1},
    }
    resp = ExtractResponse.model_validate(body)
    dumped = resp.model_dump()
    assert "unexpected_future_field" not in dumped
    assert resp.downloadAvailable is True
