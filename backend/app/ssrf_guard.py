"""SSRF protection for all outbound requests.
Every outbound HTTP request made by the backend MUST go through the functions
in this module. Guarantees:
1. URL scheme must be http or https (file://, ftp://, gopher://, etc. rejected).
   When REQUIRE_HTTPS_UPSTREAM=1, plain http:// upstreams are additionally
   rejected (policy opt-in; off by default).
2. URL length capped (default 2048, matching schemas.MAX_URL_LEN).
3. Hostname resolved via socket.getaddrinfo (IPv4 + IPv6). ANY resolved IP in
   private / loopback / link-local (incl. cloud metadata 169.254.169.254 and
   fe80::/10) / multicast / reserved / unspecified / CGNAT 100.64.0.0/10 /
   TEST-NET documentation ranges / unique-local fc00::/7 / documentation
   2001:db8::/32 ranges is rejected.
4. Redirects followed manually (default max 5). Every hop's Location must
   parse to http/https and its host re-resolved + re-validated before the next
   request; redirect loops and excessive hops raise UPSTREAM_ERROR.
5. Response bodies read with a hard byte cap; oversized responses are aborted.
6. Distinct httpx timeouts: connect ~5s, read/write ~15s, pool ~5s. Any
   httpx transport/timeout error is mapped to UpstreamError (UPSTREAM_ERROR,
   HTTP 502) — no raw exception or stack trace reaches the client.
7. A descriptive User-Agent is sent on every guarded request; caller-supplied
   headers are merged on top. URLs carrying userinfo (user:pass@host) are
   redacted before they appear in error messages or logs; response/request
   headers (which may carry secrets) are never logged.
8. DNS rebinding mitigation: after validation, connect-time resolution for the
   target host is pinned to the pre-validated IP set (SSRF_PIN_DNS, default on)
   for the duration of the request. Honest limitation: on the uvloop event
   loop the C getaddrinfo bypasses the Python patch, so network-layer egress
   policy (firewall denying private/link-local/CGNAT ranges) remains the
   authoritative control there; see backend/README.md §10.
"""
from __future__ import annotations

import ipaddress
import logging
import os
import socket
import unittest.mock
from contextlib import contextmanager
from typing import Iterator, Optional, Tuple
from urllib.parse import urlparse, urlunparse

import httpx

from .schemas import MAX_URL_LEN

logger = logging.getLogger("vidmatex.ssrf")

def _env_int(name: str, default: int) -> int:
    """Integer env lookup with a safe fallback on malformed values."""
    try:
        return int((os.environ.get(name, "") or str(default)).strip())
    except ValueError:
        return default


# Default caps (overridable via environment; same names as backend/.env.example).
DEFAULT_MAX_REDIRECTS = _env_int("MAX_REDIRECTS", 5)
DEFAULT_MAX_RESPONSE_BYTES = _env_int("MAX_RESPONSE_BYTES", 25 * 1024 * 1024)
REDIRECT_STATUSES = (301, 302, 303, 307, 308)

# Distinct httpx timeouts (seconds): fast connect / pool acquisition, more
# generous read/write for metadata probes and subtitle downloads.
CONNECT_TIMEOUT = 5.0
READ_TIMEOUT = 15.0
WRITE_TIMEOUT = 15.0
POOL_TIMEOUT = 5.0

# Identifiable, non-secret User-Agent for every guarded outbound request.
DEFAULT_USER_AGENT = "VidMateX-Resolver/1.0.0 (+self-hosted FastAPI media resolver; SSRF-guarded)"

# Policy switches (read once at import; tests monkeypatch the module attrs).
# REQUIRE_HTTPS_UPSTREAM: reject plain http:// upstream URLs when enabled.
# SSRF_PIN_DNS: pin connect-time DNS resolution to validated IPs (see §8 above).
REQUIRE_HTTPS_UPSTREAM = (
    os.environ.get("REQUIRE_HTTPS_UPSTREAM", "").strip().lower()
    in ("1", "true", "yes", "on")
)
PIN_DNS_TO_VALIDATED_IPS = (
    os.environ.get("SSRF_PIN_DNS", "1").strip().lower() not in ("0", "false", "no", "off")
)

# --- Explicitly rejected IP families (IANA special registries) ---
# Python's ipaddress attribute checks (is_private / is_loopback /
# is_link_local / is_multicast / is_reserved / is_unspecified) cover most
# ranges, but CGNAT 100.64.0.0/10 is NOT flagged by them, and the TEST-NET /
# documentation ranges are listed here explicitly so policy is readable and
# directly unit-tested per family.
_UNSAFE_V4 = (
    ipaddress.ip_network("100.64.0.0/10"),    # CGNAT shared address space (RFC 6598)
    ipaddress.ip_network("192.0.2.0/24"),     # TEST-NET-1 (RFC 5737 documentation)
    ipaddress.ip_network("198.51.100.0/24"),  # TEST-NET-2 (RFC 5737 documentation)
    ipaddress.ip_network("203.0.113.0/24"),   # TEST-NET-3 (RFC 5737 documentation)
)
_UNSAFE_V6 = (
    ipaddress.ip_network("fc00::/7"),         # unique-local addresses (ULA, RFC 4193)
    ipaddress.ip_network("fe80::/10"),         # link-local (RFC 4291; incl. fe80::/10)
    ipaddress.ip_network("2001:db8::/32"),    # documentation prefix (RFC 3849)
)


class BackendError(Exception):
    """Base class with an associated ErrorCode string and optional headers."""

    code: str = "INTERNAL_ERROR"

    def __init__(
        self,
        message: str,
        code: Optional[str] = None,
        headers: Optional[dict] = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        self.headers = headers or {}


class InvalidUrlError(BackendError):
    code = "INVALID_URL"


class SSRFBlockedError(BackendError):
    code = "SSRF_BLOCKED"


class UpstreamError(BackendError):
    code = "UPSTREAM_ERROR"


def redact_url(url: str) -> str:
    """Return a log-safe copy with any ``user:password@`` userinfo removed.

    Upstream URLs embedded in error messages/logs must never carry credentials.
    Unparseable input collapses to a generic placeholder rather than raising.
    """
    try:
        parsed = urlparse(url)
        if parsed.username is None and parsed.password is None:
            return url
        host = parsed.hostname or ""
        netloc = host + (f":{parsed.port}" if parsed.port else "")
        return urlunparse(parsed._replace(netloc=f"***@{netloc}"))
    except Exception:
        return "<unparseable-url>"


def is_ip_unsafe(ip_str: str) -> bool:
    """Return True if the IP address is in any disallowed range.
    Rejects: private, loopback, link-local (incl. cloud metadata
    169.254.169.254 and the whole fe80::/10), multicast, reserved,
    unspecified, CGNAT 100.64.0.0/10, TEST-NET documentation ranges
    192.0.2.0/24 / 198.51.100.0/24 / 203.0.113.0/24, unique-local fc00::/7,
    and the IPv6 documentation prefix 2001:db8::/32.
    """
    try:
        ip = ipaddress.ip_address(ip_str)
    except ValueError:
        return True  # unparseable -> treat as unsafe
    if any(
        [
            ip.is_private,
            ip.is_loopback,
            ip.is_link_local,
            ip.is_multicast,
            ip.is_reserved,
            ip.is_unspecified,
        ]
    ):
        return True
    networks = _UNSAFE_V4 if ip.version == 4 else _UNSAFE_V6
    return any(ip in net for net in networks)


def parse_and_validate_url(url: str, max_len: int = MAX_URL_LEN) -> Tuple[str, str]:
    """Validate scheme + length + host (+ optional https policy).
    Returns (scheme, host). Raises InvalidUrlError for malformed URLs,
    non-http(s) schemes, or plain http:// when REQUIRE_HTTPS_UPSTREAM is set.
    """
    if not isinstance(url, str) or not url:
        raise InvalidUrlError("URL must be a non-empty string")
    if len(url) > max_len:
        raise InvalidUrlError(f"URL exceeds maximum length of {max_len} characters")
    try:
        parsed = urlparse(url)
    except Exception as exc:  # pragma: no cover - urlparse rarely raises
        raise InvalidUrlError(f"Malformed URL: {exc}") from exc
    if parsed.scheme.lower() not in ("http", "https"):
        raise InvalidUrlError(
            f"Unsupported URL scheme '{parsed.scheme}'. Only http and https are allowed."
        )
    if REQUIRE_HTTPS_UPSTREAM and parsed.scheme.lower() == "http":
        raise InvalidUrlError(
            "Plain http:// upstream URL rejected: REQUIRE_HTTPS_UPSTREAM policy "
            "requires https:// for all outbound requests."
        )
    host = parsed.hostname
    if not host:
        raise InvalidUrlError("URL is missing a hostname")
    # If host is a raw IP literal, check it directly. Hostnames are validated
    # later via DNS in resolve_and_validate_host(). (is_ip_unsafe() returns True
    # for unparseable strings, so we must only call it on actual IP literals.)
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass  # hostname — DNS check happens later
    else:
        if is_ip_unsafe(host):
            raise SSRFBlockedError(
                f"URL host resolves to a disallowed (private/loopback/...) IP: {host}"
            )
    return parsed.scheme.lower(), host


def resolve_and_validate_host(host: str) -> list[str]:
    """Resolve a hostname via getaddrinfo (IPv4+IPv6) and validate every IP.
    Returns the list of resolved IP strings. Raises SSRFBlockedError if any
    resolved IP is in a disallowed range. Raises UpstreamError on DNS failure.
    """
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise UpstreamError(f"DNS resolution failed for host '{host}': {exc}") from exc
    ips: set[str] = set()
    for family, _type, _proto, _canon, sockaddr in infos:
        ip = sockaddr[0]
        ips.add(ip)
        if is_ip_unsafe(ip):
            logger.warning("Blocked host '%s' resolving to disallowed IP %s", host, ip)
            raise SSRFBlockedError(
                f"Host '{host}' resolves to a disallowed IP: {ip} "
                "(private/loopback/link-local/multicast/reserved/unspecified)"
            )
    return sorted(ips)


def validate_url_full(url: str, max_len: int = MAX_URL_LEN) -> str:
    """One-shot: scheme/length/policy check + DNS resolution of the host.
    Returns the validated URL string. Raises InvalidUrlError / SSRFBlockedError /
    UpstreamError.
    """
    _scheme, host = parse_and_validate_url(url, max_len=max_len)
    resolve_and_validate_host(host)
    return url


def _build_timeout(timeout: Optional[float | httpx.Timeout]) -> httpx.Timeout:
    """Build distinct connect / read / write / pool timeouts by default.
    A scalar legacy value or an explicit httpx.Timeout can be passed to
    override; callers that omit `timeout` get hardened per-socket timeouts.
    """
    if timeout is None:
        return httpx.Timeout(
            connect=CONNECT_TIMEOUT,
            read=READ_TIMEOUT,
            write=WRITE_TIMEOUT,
            pool=POOL_TIMEOUT,
        )
    if isinstance(timeout, httpx.Timeout):
        return timeout
    return httpx.Timeout(timeout)


@contextmanager
def _pinned_resolution(host: str, ips: list[str]) -> Iterator[None]:
    """Pin connect-time ``socket.getaddrinfo`` for ``host`` to validated IPs.

    Between SSRF validation (check time) and the TCP/TLS connect (use time),
    a DNS-rebinding host could return a different address. While the patched
    context is active, resolution for ``host`` returns only ``ips``; every
    other host falls through to the real resolver. No-op when pinning is
    disabled or no IPs were validated. Limitation: under uvloop the event
    loop's C getaddrinfo bypasses this Python patch; use an egress firewall
    there (backend/README.md §10).
    """
    if not PIN_DNS_TO_VALIDATED_IPS or not ips:
        yield
        return
    real_gai = socket.getaddrinfo

    def locked_gai(host_arg, port, family=0, type=0, proto=0, flags=0):
        if host_arg != host:
            return real_gai(host_arg, port, family, type, proto, flags)
        entries = []
        for ip in ips:
            version = ipaddress.ip_address(ip).version
            fam = socket.AF_INET if version == 4 else socket.AF_INET6
            if family not in (0, fam):
                continue
            socktype = type or socket.SOCK_STREAM
            pro = proto or socket.IPPROTO_TCP
            sockaddr = (ip, port) if version == 4 else (ip, port, 0, 0)
            entries.append((fam, socktype, pro, "", sockaddr))
        if not entries:
            raise socket.gaierror(f"pinned resolution failed for {host}")
        return entries

    with unittest.mock.patch.object(socket, "getaddrinfo", locked_gai):
        yield


async def guarded_request(
    method: str,
    url: str,
    *,
    headers: Optional[dict] = None,
    max_redirects: int = DEFAULT_MAX_REDIRECTS,
    max_bytes: int = DEFAULT_MAX_RESPONSE_BYTES,
    timeout: Optional[float | httpx.Timeout] = None,
    read_body: bool = True,
) -> httpx.Response:
    """Make an HTTP request with full SSRF guarding and manual redirect following.
    Every redirect hop's Location header is re-parsed, re-resolved, and
    re-validated (scheme must be http/https; DNS host must not resolve to a
    disallowed IP) before the next request is issued. Redirect loops and
    excessive hop counts raise UpstreamError. Response bodies are read up to
    `max_bytes`; larger responses raise UpstreamError. Transport/timeout
    errors are mapped to UpstreamError (no stack trace leaks).
    Returns the final httpx.Response (with body already read into memory when
    read_body=True).
    """
    current_url = validate_url_full(url)
    request_headers = {"User-Agent": DEFAULT_USER_AGENT}
    if headers:
        # Caller headers (e.g. Range) may extend/override the default UA, but
        # required safety headers are never stripped.
        request_headers.update(headers)
    async with httpx.AsyncClient(
        timeout=_build_timeout(timeout),
        follow_redirects=False,
        headers=request_headers,
    ) as client:
        seen_urls: set[str] = set()
        for hop in range(max_redirects + 1):
            # Re-validate current URL (scheme/length/policy) and resolve DNS
            # each hop; pin the validated IPs for the connect that follows.
            _scheme, host = parse_and_validate_url(current_url)
            ips = resolve_and_validate_host(host)
            seen_urls.add(current_url)
            try:
                with _pinned_resolution(host, ips):
                    resp = await client.request(method, current_url)
            except httpx.HTTPError as exc:
                # Connect/read/write/pool timeouts, connection resets, protocol
                # errors -> clean UPSTREAM_ERROR (HTTP 502) envelope.
                logger.warning(
                    "Upstream %s %s failed: %s",
                    method,
                    redact_url(current_url),
                    type(exc).__name__,
                )
                raise UpstreamError(
                    f"Upstream {method} request failed ({type(exc).__name__}) "
                    f"for host '{host}'"
                ) from exc
            if resp.status_code in REDIRECT_STATUSES:
                location = resp.headers.get("location")
                if not location:
                    # No Location header despite 3xx — treat as final response.
                    break
                # Resolve relative Location against current URL.
                try:
                    next_url = str(httpx.URL(current_url).join(location))
                except Exception as exc:
                    raise UpstreamError(f"Invalid redirect Location: {exc}") from exc
                # Every redirect target must parse to http(s), satisfy the
                # https policy, and its DNS host must validate (no private /
                # metadata / CGNAT / doc IP) before the next request leaves
                # the process.
                _n_scheme, n_host = parse_and_validate_url(next_url)
                resolve_and_validate_host(n_host)
                if next_url in seen_urls:
                    logger.warning("Redirect loop detected at %s", redact_url(next_url))
                    raise UpstreamError(
                        f"Redirect loop detected: {redact_url(next_url)} was already visited"
                    )
                current_url = next_url
                continue
            # Final (non-redirect) response: read body with size cap.
            if read_body:
                await _read_with_cap(resp, max_bytes)
            return resp
        raise UpstreamError(f"Exceeded maximum of {max_redirects} redirects")


async def _read_with_cap(resp: httpx.Response, max_bytes: int) -> None:
    """Read response body into memory, aborting if it exceeds max_bytes."""
    chunks: list[bytes] = []
    received = 0
    async for chunk in resp.aiter_bytes(chunk_size=65536):
        received += len(chunk)
        if received > max_bytes:
            await resp.aclose()
            logger.warning(
                "Upstream response aborted: exceeded %d byte cap", max_bytes
            )
            raise UpstreamError(
                f"Upstream response too large (exceeded {max_bytes} bytes)"
            )
        chunks.append(chunk)
    # httpx reads lazily; assemble the body so resp.text/resp.content works.
    resp._content = b"".join(chunks)


async def guarded_head(
    url: str, *, headers: Optional[dict] = None, **kwargs
) -> httpx.Response:
    """Guarded HEAD request. Body not read (HEAD has none)."""
    return await guarded_request("HEAD", url, headers=headers, read_body=False, **kwargs)


async def guarded_get_text(
    url: str, *, max_bytes: int = DEFAULT_MAX_RESPONSE_BYTES, **kwargs
) -> str:
    """Guarded GET returning the response body decoded as text."""
    resp = await guarded_request("GET", url, max_bytes=max_bytes, read_body=True, **kwargs)
    if resp.status_code >= 400:
        raise UpstreamError(
            f"Upstream returned HTTP {resp.status_code} for {redact_url(url)}"
        )
    try:
        return resp.text
    except (UnicodeError, httpx.DecodingError) as exc:
        raise UpstreamError(
            f"Upstream response could not be decoded as text ({type(exc).__name__})"
        ) from exc


__all__ = [
    "BackendError",
    "InvalidUrlError",
    "SSRFBlockedError",
    "UpstreamError",
    "DEFAULT_MAX_REDIRECTS",
    "DEFAULT_MAX_RESPONSE_BYTES",
    "CONNECT_TIMEOUT",
    "READ_TIMEOUT",
    "WRITE_TIMEOUT",
    "POOL_TIMEOUT",
    "DEFAULT_USER_AGENT",
    "REQUIRE_HTTPS_UPSTREAM",
    "PIN_DNS_TO_VALIDATED_IPS",
    "redact_url",
    "is_ip_unsafe",
    "parse_and_validate_url",
    "resolve_and_validate_host",
    "validate_url_full",
    "guarded_request",
    "guarded_head",
    "guarded_get_text",
]
