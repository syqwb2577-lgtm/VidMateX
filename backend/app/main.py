"""VidMateX resolver backend — FastAPI application.

Endpoints (frozen, CONTRACTS §2):
  GET  /health   -> {"status":"ok","version":"1.0.0","resolvers":[...]}
  POST /extract  -> {"metadata":..., "formats":[...], "subtitles":[...], "streamUrls":{...}}
  POST /subtitle -> {"content":"...", "format":"srt|vtt|ass"}

All errors return {"error":{"code":"<CODE>","message":"..."}} with codes from
schemas.ErrorCode. All outbound requests go through app.ssrf_guard.
"""
from __future__ import annotations

import hmac
import logging
import os
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from . import __version__
from .resolvers.base import BaseResolver, ExtractResult
from .resolvers.direct_url import DirectUrlResolver
from .resolvers.platform import (
    YTDLP_PATH,
    build_resolver_chain,
)
from .schemas import (
    ErrorEnvelope,
    ExtractRequest,
    ExtractResponse,
    HealthResponse,
    SubtitleRequest,
    SubtitleResponse,
)
from .ssrf_guard import BackendError, SSRFBlockedError, validate_url_full

logger = logging.getLogger("vidmatex.main")

# --- Configuration from environment ---
RESOLVER_API_KEY = os.environ.get("RESOLVER_API_KEY", "").strip()
PORT = int(os.environ.get("PORT", "8000"))
ALLOWED_ORIGINS_RAW = os.environ.get("ALLOWED_ORIGINS", "").strip()
ALLOWED_ORIGINS = [o.strip() for o in ALLOWED_ORIGINS_RAW.split(",") if o.strip()]
MAX_REDIRECTS = int(os.environ.get("MAX_REDIRECTS", "5"))
MAX_RESPONSE_BYTES = int(os.environ.get("MAX_RESPONSE_BYTES", str(25 * 1024 * 1024)))
RATE_LIMIT_PER_MINUTE = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "30"))
MAX_REQUEST_BYTES = int(os.environ.get("MAX_REQUEST_BYTES", "65536"))

# Error code -> HTTP status mapping.
_CODE_TO_STATUS: dict[str, int] = {
    "INVALID_URL": 400,
    "SSRF_BLOCKED": 403,
    "PLATFORM_NOT_SUPPORTED": 400,
    "PLATFORM_DOWNLOAD_NOT_PERMITTED": 403,
    "UPSTREAM_ERROR": 502,
    "RATE_LIMITED": 429,
    "INTERNAL_ERROR": 500,
    "UNAUTHORIZED": 401,
}

app = FastAPI(
    title="VidMateX Resolver Backend",
    version=__version__,
    description="Self-hosted media resolver for the VidMateX Android app.",
)

# --- CORS (restricted methods/headers; origins configurable via env) ---
# If ALLOWED_ORIGINS is empty we allow any origin (the Android app is native
# and does not use CORS), but restrict methods and headers. Set ALLOWED_ORIGINS
# in production to an explicit list.
app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS or ["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["X-API-Key", "Content-Type"],
)


# --- Request size cap middleware ---
def _payload_too_large_response() -> JSONResponse:
    return JSONResponse(
        status_code=413,
        content={
            "error": {
                "code": "INVALID_URL",
                "message": (
                    f"Request body exceeds maximum size of {MAX_REQUEST_BYTES} bytes"
                ),
            }
        },
    )


@app.middleware("http")
async def cap_request_size(request: Request, call_next):
    # Fast path: reject when Content-Length already announces an oversized body.
    cl = request.headers.get("content-length")
    if cl and cl.isdigit() and int(cl) > MAX_REQUEST_BYTES:
        return _payload_too_large_response()
    # When Content-Length is absent (chunked encoding / non-compliant clients),
    # read the body ourselves. Starlette caches it on request._body, so the
    # endpoint can still parse the JSON afterwards. Oversized bodies are
    # rejected with 413 and the frozen {"error": {"code", "message"}} envelope.
    if request.method in ("POST", "PUT", "PATCH"):
        body = await request.body()
        if len(body) > MAX_REQUEST_BYTES:
            return _payload_too_large_response()
    return await call_next(request)


# --- Rate limiting (in-memory, per-IP, sliding 60-second window). ---
# slowapi (0.1.9, installed per requirements.txt) was evaluated as the limiter
# backend, but this built-in in-memory limiter is used so that rate-limit
# failures return the exact frozen JSON error envelope
# ({"error":{"code":"RATE_LIMITED", ...}}) plus a Retry-After header, and so
# /health stays exempt without extra decorator wiring. slowapi remains a
# drop-in alternative (e.g. with a Redis backend) for multi-instance deploys;
# a slowapi Limiter's custom handler would need to reproduce this envelope.
# Limitation: counters are per-process and reset on restart; multiple
# instances behind a load balancer count independently.
import time as _time
from collections import deque as _deque

_rate_hits: dict[str, _deque] = {}


def _check_rate_limit(ip: str) -> None:
    if RATE_LIMIT_PER_MINUTE <= 0:
        return
    now = _time.time()
    hits = _rate_hits.setdefault(ip, _deque())
    while hits and now - hits[0] > 60.0:
        hits.popleft()
    if len(hits) >= RATE_LIMIT_PER_MINUTE:
        # Retry-After (seconds): how long until the oldest hit ages out of the
        # 60-second sliding window; floored at 1 so clients never see 0.
        retry_after = max(1, int(60.0 - (now - hits[0]) + 0.999))
        raise BackendError(
            f"Rate limit exceeded: max {RATE_LIMIT_PER_MINUTE} requests/minute per IP",
            code="RATE_LIMITED",
            headers={"Retry-After": str(retry_after)},
        )
    hits.append(now)


async def rate_limit_dep(request: Request):
    """FastAPI dependency — apply per-IP rate limiting to an endpoint."""
    ip = request.client.host if request.client else "unknown"
    _check_rate_limit(ip)


_RATE_LIMIT_ENABLED = RATE_LIMIT_PER_MINUTE > 0


# --- Auth dependency: optional X-API-Key shared secret ---
async def require_api_key(request: Request, x_api_key: Optional[str] = Header(default=None)):
    if not RESOLVER_API_KEY:
        return  # auth disabled
    provided = (x_api_key or "").strip()
    # Constant-time comparison to avoid timing side-channels on the shared
    # secret; empty/missing/non-matching keys all raise the same 401 envelope.
    if not provided or not hmac.compare_digest(
        provided.encode("utf-8"), RESOLVER_API_KEY.encode("utf-8")
    ):
        logger.warning(
            "Rejected %s request: missing or invalid X-API-Key (client=%s)",
            request.url.path,
            request.client.host if request.client else "unknown",
        )
        raise BackendError(
            "Invalid or missing X-API-Key header", code="UNAUTHORIZED"
        )


# --- Resolver chain (built once at startup) ---
RESOLVERS: list[BaseResolver] = build_resolver_chain()


def _pick_resolver(url: str) -> BaseResolver:
    for r in RESOLVERS:
        if r.supports(url):
            return r
    raise BackendError(
        "No resolver supports this URL. Supported: direct media URLs, YouTube/Facebook/TikTok metadata.",
        code="PLATFORM_NOT_SUPPORTED",
    )


def _error_response(
    code: str, message: str, headers: Optional[dict] = None
) -> JSONResponse:
    return JSONResponse(
        status_code=_CODE_TO_STATUS.get(code, 500),
        content={"error": {"code": code, "message": message}},
        headers=headers or None,
    )


# --- Exception handlers -> unified error envelope ---
@app.exception_handler(BackendError)
async def backend_error_handler(request: Request, exc: BackendError):
    return _error_response(exc.code, exc.message, headers=exc.headers)


@app.exception_handler(RequestValidationError)
async def validation_error_handler(request: Request, exc: RequestValidationError):
    # All request validation failures are URL/input problems. Return a fixed
    # generic message: Pydantic error detail can echo raw input (including a
    # URL with embedded credentials), so it must not be serialized to the
    # client or logs.
    return _error_response(
        "INVALID_URL",
        "Invalid request body: expected {\"url\": ...} (and trackId/format for /subtitle).",
    )


@app.exception_handler(Exception)
async def unhandled_error_handler(request: Request, exc: Exception):
    # Do not leak internal details; log server-side, return generic message.
    return _error_response("INTERNAL_ERROR", "Internal server error")


# --- Endpoints ---
@app.get("/health", response_model=HealthResponse)
async def health(request: Request):
    resolver_names = [r.name for r in RESOLVERS]
    if YTDLP_PATH:
        resolver_names.append("yt-dlp (opt-in enabled)")
    return HealthResponse(
        status="ok",
        version=__version__,
        resolvers=resolver_names,
    )


# /health has no rate_limit_dep -> exempt from rate limiting (liveness probes).

@app.post("/extract", response_model=ExtractResponse, dependencies=[Depends(require_api_key), Depends(rate_limit_dep)])
async def extract(request: Request, body: ExtractRequest):
    url = body.url
    validate_url_full(url)  # raises INVALID_URL / SSRF_BLOCKED / UPSTREAM_ERROR
    resolver = _pick_resolver(url)
    result: ExtractResult = await resolver.extract(url)
    return ExtractResponse(
        metadata=result.metadata,
        formats=result.formats,
        subtitles=result.subtitles,
        streamUrls=result.streamUrls,
        downloadAvailable=result.downloadAvailable,
        message=result.message,
    )


@app.post("/subtitle", response_model=SubtitleResponse, dependencies=[Depends(require_api_key), Depends(rate_limit_dep)])
async def subtitle(request: Request, body: SubtitleRequest):
    url = body.url
    validate_url_full(url)
    resolver = _pick_resolver(url)
    content, actual_fmt = await resolver.fetch_subtitle(url, body.trackId, body.format)
    return SubtitleResponse(content=content, format=actual_fmt)


if __name__ == "__main__":  # pragma: no cover
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=PORT, reload=False)
