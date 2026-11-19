"""Endpoint contract tests — shapes, error codes, auth, rate limiting.

All resolver network calls are replaced by a FakeResolver (no outbound IO).
"""
import importlib

import pytest


def test_health_shape(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["version"] == "1.0.0"
    assert isinstance(body["resolvers"], list)
    assert "direct_url" in body["resolvers"]


def test_extract_response_shape(client, fake_resolver, monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)

    r = client.post("/extract", json={"url": "https://example.com/v.mp4"})
    assert r.status_code == 200
    body = r.json()
    # Frozen top-level keys (plus downloadAvailable/message extras).
    assert set(body.keys()) >= {"metadata", "formats", "subtitles", "streamUrls"}
    md = body["metadata"]
    assert set(md.keys()) >= {
        "sourceType", "sourceUrl", "sourceId", "title",
        "author", "durationSec", "thumbnailUrl", "description",
    }
    fmt = body["formats"][0]
    assert set(fmt.keys()) >= {
        "trackId", "kind", "container", "heightPx", "widthPx", "fps",
        "vcodec", "acodec", "bitrateBps", "sampleRate", "estimatedBytes",
        "language", "resumable",
    }
    assert body["streamUrls"]["t1"] == "https://example.com/v.mp4"


def test_subtitle_response_shape(client, fake_resolver, monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)

    r = client.post(
        "/subtitle",
        json={"url": "https://example.com/v.mp4", "trackId": "sidecar:srt", "format": "srt"},
    )
    assert r.status_code == 200
    body = r.json()
    assert set(body.keys()) == {"content", "format"}
    assert body["format"] == "srt"
    assert "Hi" in body["content"]


# --- Error codes ---
def test_error_invalid_url_bad_scheme(client):
    r = client.post("/extract", json={"url": "ftp://example.com/x"})
    assert r.status_code == 400
    body = r.json()
    assert body["error"]["code"] == "INVALID_URL"
    assert "message" in body["error"]


def test_error_invalid_url_empty_body(client):
    r = client.post("/extract", json={"url": ""})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_URL"


def test_error_invalid_url_too_long(client):
    r = client.post("/extract", json={"url": "https://example.com/" + "a" * 3000})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_URL"


def test_error_ssrf_blocked_raw_private_ip(client):
    r = client.post("/extract", json={"url": "http://127.0.0.1/admin"})
    assert r.status_code == 403
    assert r.json()["error"]["code"] == "SSRF_BLOCKED"


def test_error_ssrf_blocked_linklocal(client):
    r = client.post("/extract", json={"url": "http://169.254.169.254/latest/meta-data/"})
    assert r.status_code == 403
    assert r.json()["error"]["code"] == "SSRF_BLOCKED"


def test_error_platform_not_supported_when_no_resolvers(client, monkeypatch):
    import app.main as main

    monkeypatch.setattr(main, "RESOLVERS", [])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    r = client.post("/extract", json={"url": "https://example.com/x"})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "PLATFORM_NOT_SUPPORTED"


def test_error_upstream_from_resolver(client, monkeypatch):
    import app.main as main
    from app.ssrf_guard import UpstreamError

    class BoomResolver:
        name = "boom"

        def supports(self, url):
            return True

        async def extract(self, url):
            raise UpstreamError("upstream exploded")

        async def fetch_subtitle(self, url, track_id, fmt):
            raise UpstreamError("upstream exploded")

    monkeypatch.setattr(main, "RESOLVERS", [BoomResolver()])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    r = client.post("/extract", json={"url": "https://example.com/x"})
    assert r.status_code == 502
    assert r.json()["error"]["code"] == "UPSTREAM_ERROR"


def test_error_envelope_shape(client):
    """Every error must be exactly {'error': {'code': ..., 'message': ...}}."""
    r = client.post("/extract", json={"url": "file:///etc/passwd"})
    body = r.json()
    assert set(body.keys()) == {"error"}
    assert set(body["error"].keys()) == {"code", "message"}


# --- Auth (X-API-Key) ---
def test_auth_disabled_by_default(client):
    # RESOLVER_API_KEY not set -> no auth required
    r = client.post("/extract", json={"url": "http://127.0.0.1/x"})
    assert r.status_code != 401


def test_auth_enabled_requires_header(monkeypatch):
    import app.main as main

    monkeypatch.setenv("RESOLVER_API_KEY", "secret123")
    importlib.reload(main)
    from fastapi.testclient import TestClient

    client = TestClient(main.app)
    try:
        r = client.post("/extract", json={"url": "http://127.0.0.1/x"})
        assert r.status_code == 401
        assert r.json()["error"]["code"] == "UNAUTHORIZED"

        r2 = client.post(
            "/extract",
            json={"url": "http://127.0.0.1/x"},
            headers={"X-API-Key": "secret123"},
        )
        assert r2.status_code != 401  # passes auth (fails SSRF, that's fine)
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


# --- Rate limiting ---
def test_rate_limit_returns_429(monkeypatch, fake_resolver):
    import app.main as main

    monkeypatch.setenv("RATE_LIMIT_PER_MINUTE", "1")
    importlib.reload(main)
    from fastapi.testclient import TestClient

    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)

    client = TestClient(main.app)
    try:
        r1 = client.post("/extract", json={"url": "https://example.com/v.mp4"})
        assert r1.status_code == 200
        r2 = client.post("/extract", json={"url": "https://example.com/v.mp4"})
        assert r2.status_code == 429
        assert r2.json()["error"]["code"] == "RATE_LIMITED"
        # /health is exempt from rate limit
        rh = client.get("/health")
        assert rh.status_code == 200
    finally:
        monkeypatch.setenv("RATE_LIMIT_PER_MINUTE", "1000")
        importlib.reload(main)


# --- Subtitle format validation ---
def test_subtitle_bad_format_rejected(client):
    r = client.post(
        "/subtitle",
        json={"url": "https://example.com/v.mp4", "trackId": "x", "format": "pdf"},
    )
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_URL"


# --- Request body size cap (HTTP 413, frozen error envelope) ---
def test_oversized_body_with_content_length_rejected(client, monkeypatch):
    import app.main as main
    monkeypatch.setattr(main, "MAX_REQUEST_BYTES", 1024)
    payload = b'{"url":"' + b"x" * 5000 + b'"}'
    r = client.post(
        "/extract",
        content=payload,
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "INVALID_URL"
    assert "message" in r.json()["error"]


def test_oversized_body_without_content_length_rejected(client, monkeypatch):
    # Chunked request (no Content-Length header) must still be capped by
    # reading the body in the middleware.
    import app.main as main
    monkeypatch.setattr(main, "MAX_REQUEST_BYTES", 1024)
    payload = b'{"url":"' + b"x" * 5000 + b'"}'

    def chunked():
        yield payload

    r = client.post(
        "/extract",
        content=chunked(),
        headers={"Content-Type": "application/json"},
    )
    assert r.status_code == 413
    assert r.json()["error"]["code"] == "INVALID_URL"


def test_small_body_accepted(client, monkeypatch, fake_resolver):
    import app.main as main
    monkeypatch.setattr(main, "MAX_REQUEST_BYTES", 1024)
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    r = client.post("/extract", json={"url": "https://example.com/v.mp4"})
    assert r.status_code == 200


# --- Auth: missing / wrong / correct X-API-Key ---
def test_auth_wrong_key_rejected(monkeypatch, fake_resolver):
    import app.main as main
    monkeypatch.setenv("RESOLVER_API_KEY", "secret123")
    importlib.reload(main)
    from fastapi.testclient import TestClient

    client = TestClient(main.app)
    monkeypatch.setattr(main, "RESOLVERS", [fake_resolver])
    monkeypatch.setattr(main, "validate_url_full", lambda url, **kw: url)
    try:
        bad = client.post(
            "/extract",
            json={"url": "https://example.com/v.mp4"},
            headers={"X-API-Key": "wrong-key"},
        )
        assert bad.status_code == 401
        assert bad.json()["error"]["code"] == "UNAUTHORIZED"

        good = client.post(
            "/subtitle",
            json={
                "url": "https://example.com/v.mp4",
                "trackId": "sidecar:srt",
                "format": "srt",
            },
            headers={"X-API-Key": "secret123"},
        )
        assert good.status_code != 401
    finally:
        monkeypatch.delenv("RESOLVER_API_KEY", raising=False)
        importlib.reload(main)


# --- /health reports opt-in yt-dlp status ---
def test_health_shows_ytdlp_optin_when_enabled(client, monkeypatch):
    import app.main as main
    monkeypatch.setattr(main, "YTDLP_PATH", "/usr/local/bin/yt-dlp")
    body = client.get("/health").json()
    assert any("yt-dlp" in name for name in body["resolvers"])


def test_health_hides_ytdlp_when_disabled(client):
    body = client.get("/health").json()
    assert not any("yt-dlp" in name for name in body["resolvers"])


# --- Regression: filesystem schemes blocked; schema URL length cap ---
def test_file_scheme_rejected(client):
    r = client.post("/extract", json={"url": "file:///etc/passwd"})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_URL"


def test_schema_rejects_url_over_max_length():
    from pydantic import ValidationError
    from app.schemas import MAX_URL_LEN, ExtractRequest

    with pytest.raises(ValidationError):
        ExtractRequest(url="https://example.com/" + "a" * MAX_URL_LEN)
