"""Unit tests for app.ssrf_guard — pure functions, DNS monkeypatch, redirect following."""
import socket

import httpx
import pytest

from app.ssrf_guard import (
    InvalidUrlError,
    SSRFBlockedError,
    UpstreamError,
    CONNECT_TIMEOUT,
    POOL_TIMEOUT,
    READ_TIMEOUT,
    WRITE_TIMEOUT,
    _build_timeout,
    is_ip_unsafe,
    parse_and_validate_url,
    resolve_and_validate_host,
    validate_url_full,
    redact_url,
)


# --- is_ip_unsafe ---
@pytest.mark.parametrize(
    "ip,unsafe",
    [
        # loopback / private RFC1918
        ("127.0.0.1", True),
        ("10.0.0.5", True),
        ("192.168.1.1", True),
        ("172.16.0.1", True),
        # link-local incl. cloud metadata
        ("169.254.169.254", True),  # cloud metadata (link-local)
        ("169.254.1.10", True),
        # unspecified / broadcast-ish
        ("0.0.0.0", True),  # unspecified
        # multicast
        ("224.0.0.1", True),
        ("239.255.255.255", True),
        # reserved
        ("240.0.0.1", True),
        # CGNAT shared address space 100.64.0.0/10 (NOT flagged by ipaddress attrs)
        ("100.64.0.1", True),
        ("100.127.255.255", True),
        # TEST-NET / documentation ranges (RFC 5737)
        ("192.0.2.10", True),
        ("198.51.100.7", True),
        ("203.0.113.9", True),
        # IPv6 loopback / link-local / unique-local / documentation / multicast
        ("::1", True),  # IPv6 loopback
        ("fe80::1", True),  # IPv6 link-local
        ("fc00::1", True),  # IPv6 unique-local (ULA)
        ("fd12:3456:789a::1", True),  # ULA inside fc00::/7
        ("2001:db8::1", True),  # IPv6 documentation prefix RFC 3849
        ("ff02::1", True),  # IPv6 multicast
        ("::", True),  # IPv6 unspecified
        # Safe public addresses
        ("8.8.8.8", False),
        ("1.1.1.1", False),
        ("2001:4860:4860::8888", False),
    ],
)
def test_is_ip_unsafe(ip, unsafe):
    assert is_ip_unsafe(ip) is unsafe


# --- parse_and_validate_url (scheme / length / host) ---
def test_rejects_file_scheme():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("file:///etc/passwd")


def test_rejects_ftp_scheme():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("ftp://example.com/x")


def test_rejects_gopher_scheme():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("gopher://example.com/")


def test_rejects_data_scheme():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("data:text/html,<h1>hi")


def test_rejects_empty_url():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("")


def test_rejects_too_long_url():
    long = "https://example.com/" + "a" * 3000
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url(long, max_len=2048)


def test_rejects_missing_host():
    with pytest.raises(InvalidUrlError):
        parse_and_validate_url("http:///path")


def test_rejects_raw_private_ip_host():
    with pytest.raises(SSRFBlockedError):
        parse_and_validate_url("http://127.0.0.1/admin")


def test_rejects_raw_linklocal_ip_host():
    with pytest.raises(SSRFBlockedError):
        parse_and_validate_url("http://169.254.169.254/latest/meta-data/")


def test_accepts_public_host():
    scheme, host = parse_and_validate_url("https://example.com/video.mp4")
    assert scheme == "https"
    assert host == "example.com"


# --- resolve_and_validate_host with monkeypatched DNS ---
def test_dns_resolving_private_ip_blocked(monkeypatch):
    def fake_getaddrinfo(host, *args, **kwargs):
        # host "evil.com" resolves to a private IP (DNS rebinding simulation)
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.0.0.1", 0))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    with pytest.raises(SSRFBlockedError):
        resolve_and_validate_host("evil.com")


def test_dns_resolving_public_ip_allowed(monkeypatch):
    def fake_getaddrinfo(host, *args, **kwargs):
        return [
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("93.184.216.34", 0)),
            (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2606:2800:220:1:248:1893:25c8:1946", 0)),
        ]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    ips = resolve_and_validate_host("example.com")
    assert "93.184.216.34" in ips


def test_dns_failure_raises_upstream(monkeypatch):
    def fake_getaddrinfo(host, *args, **kwargs):
        raise socket.gaierror("Name or service not known")

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    with pytest.raises(UpstreamError):
        resolve_and_validate_host("nonexistent.invalid")


def test_validate_url_full_blocks_localhost(monkeypatch):
    # localhost resolves to 127.0.0.1 via real getaddrinfo (no network needed)
    with pytest.raises(SSRFBlockedError):
        validate_url_full("http://localhost:8000/secret")


# --- guarded_request: manual redirect following with per-hop re-validation ---
class _FakeAsyncClient:
    """Simulates a chain of redirects. Records every requested URL."""

    def __init__(self, responses):
        # responses: list of (url_substring_match, httpx.Response)
        self._responses = responses
        self.requested_urls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def request(self, method, url, **kwargs):
        self.requested_urls.append(str(url))
        for match, resp in self._responses:
            if match in str(url):
                return resp
        return httpx.Response(200, content=b"ok")


@pytest.mark.asyncio
async def test_redirect_to_internal_ip_blocked(monkeypatch):
    """A public URL that 302-redirects to http://127.0.0.1/ must be blocked at the redirect hop."""
    import app.ssrf_guard as guard

    # First hop: public host returns 302 to internal.
    redirect_resp = httpx.Response(
        302, headers={"Location": "http://127.0.0.1/internal"}
    )
    fake = _FakeAsyncClient([("example.com", redirect_resp)])

    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    # Allow initial host (example.com) DNS to resolve to a public IP.
    monkeypatch.setattr(
        guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"] if host != "127.0.0.1" else (_ for _ in ()).throw(SSRFBlockedError("blocked"))
    )

    with pytest.raises(SSRFBlockedError):
        await guard.guarded_request("GET", "https://example.com/start", max_redirects=5)

    # The initial request was made, but the redirect to 127.0.0.1 was blocked.
    assert any("example.com" in u for u in fake.requested_urls)


@pytest.mark.asyncio
async def test_too_many_redirects_raises_upstream(monkeypatch):
    import app.ssrf_guard as guard

    # Every request returns a 302 to a different (valid) host -> infinite loop.
    counter = {"n": 0}

    async def fake_request(self, method, url, **kwargs):
        counter["n"] += 1
        return httpx.Response(302, headers={"Location": f"https://example.com/r{counter['n']}"})

    monkeypatch.setattr(_FakeAsyncClient, "request", fake_request)
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: _FakeAsyncClient([]))
    monkeypatch.setattr(guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"])

    with pytest.raises(UpstreamError, match="redirect"):
        await guard.guarded_request("GET", "https://example.com/start", max_redirects=3)


# --- CGNAT / documentation IPs discovered via DNS are blocked ---
def test_dns_cgnat_address_blocked(monkeypatch):
    def fake_getaddrinfo(host, *args, **kwargs):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("100.64.0.5", 0))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    with pytest.raises(SSRFBlockedError):
        resolve_and_validate_host("shared-nat.example")


def test_dns_testnet_documentation_address_blocked(monkeypatch):
    def fake_getaddrinfo(host, *args, **kwargs):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("198.51.100.20", 0))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)
    with pytest.raises(SSRFBlockedError):
        resolve_and_validate_host("doc.example")


# --- Redirect hop hardening ---
@pytest.mark.asyncio
async def test_redirect_to_cloud_metadata_ip_blocked(monkeypatch):
    """A 302 to the cloud metadata link-local address must be blocked at hop 2."""
    import app.ssrf_guard as guard

    redirect_resp = httpx.Response(
        302, headers={"Location": "http://169.254.169.254/latest/meta-data/"}
    )
    fake = _FakeAsyncClient([("example.com", redirect_resp)])
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    monkeypatch.setattr(guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"])

    with pytest.raises(SSRFBlockedError):
        await guard.guarded_request("GET", "https://example.com/start")
    assert any("example.com" in u for u in fake.requested_urls)


@pytest.mark.asyncio
async def test_redirect_to_non_http_scheme_rejected(monkeypatch):
    """A Location pointing at file:// must be rejected before any request."""
    import app.ssrf_guard as guard

    redirect_resp = httpx.Response(302, headers={"Location": "file:///etc/passwd"})
    fake = _FakeAsyncClient([("example.com", redirect_resp)])
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: fake)
    monkeypatch.setattr(guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"])

    with pytest.raises(InvalidUrlError):
        await guard.guarded_request("GET", "https://example.com/start")


@pytest.mark.asyncio
async def test_redirect_loop_detected(monkeypatch):
    """Same Location visited twice => UPSTREAM_ERROR (not an endless follow)."""
    import app.ssrf_guard as guard

    async def fake_request(self, method, url, **kwargs):
        return httpx.Response(302, headers={"Location": "https://example.com/loop"})

    monkeypatch.setattr(_FakeAsyncClient, "request", fake_request)
    monkeypatch.setattr(guard.httpx, "AsyncClient", lambda **kw: _FakeAsyncClient([]))
    monkeypatch.setattr(guard, "resolve_and_validate_host", lambda host: ["93.184.216.34"])

    with pytest.raises(UpstreamError, match="loop"):
        await guard.guarded_request("GET", "https://example.com/start", max_redirects=5)


# --- Distinct httpx socket timeouts ---
def test_distinct_default_timeouts():
    assert (CONNECT_TIMEOUT, READ_TIMEOUT, WRITE_TIMEOUT, POOL_TIMEOUT) == (
        5.0,
        15.0,
        15.0,
        5.0,
    )
    timeout = _build_timeout(None)
    assert timeout.connect == 5.0
    assert timeout.read == 15.0
    assert timeout.write == 15.0
    assert timeout.pool == 5.0


# --- redact_url (log-safe userinfo removal) ---
def test_redact_url_strips_userinfo_credentials():
    out = redact_url("https://user:secretpass@api.example.com/v1/resolve?url=x")
    assert "secretpass" not in out
    assert "user:" not in out
    assert "api.example.com" in out


def test_redact_url_leaves_credential_free_urls_intact():
    url = "https://cdn.example.com/video.mp4?token=abc"
    assert redact_url(url) == url


def test_redact_url_unparseable_input_never_raises():
    # urlparse rarely raises; redact_url must return a string for any input and
    # never propagate an exception to a log path.
    out = redact_url(":::not a url:::")
    assert isinstance(out, str)
    assert "secretpass" not in out

