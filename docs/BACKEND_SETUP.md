# Backend Setup — VidMateX Resolver (FastAPI)
The optional self-hosted resolver backend lives in `backend/`. It resolves
direct media URLs and (optionally) platform metadata for the VidMateX Android
app. The app **never** ships a hardcoded backend endpoint or API key — you
deploy this yourself. The API contract is frozen (see `CONTRACTS.md` §2 and
`core/network/BackendApi.kt`) and this backend matches both exactly.

> The backend is fully tested: **149 pytest cases pass** collected from
> **102 `def test_` functions** (2026-11-19 hardening run; pre-hardening
> baseline was 123 cases / 76 functions — see `docs/TESTING_GUIDE.md` §1.2),
> via `python3 -m pytest backend/tests -q` (run from `backend/`).

---
## 1. What it does
- `POST /extract` — given a URL, returns metadata + available media tracks +
  direct stream URLs + subtitle tracks.
- `POST /subtitle` — downloads a specific subtitle track and returns its text.
- `GET /health` — liveness check + active resolver list.
- **SSRF protection** on every outbound request: DNS resolve + reject
  private/loopback/link-local (incl. `169.254.169.254`, `fe80::/10`)/
  multicast/reserved/unspecified/CGNAT `100.64.0.0/10`/TEST-NET doc ranges/
  ULA `fc00::/7`; manual redirect follow with per-hop re-validation, loop and
  hop-limit errors; distinct connect/read/write/pool timeouts; transport and
  timeout errors mapped to `UPSTREAM_ERROR` (502) without stack traces;
  response and request size caps (413 enforced even without
  `Content-Length`); fixed `User-Agent`; credential-bearing URL userinfo
  redacted from errors/logs; `SSRF_PIN_DNS` resolution pinning for DNS
  rebinding; optional `REQUIRE_HTTPS_UPSTREAM=1` plain-http rejection.
- **Subtitle safety**: Content-Type allowlist for sidecars/subtitle downloads
  (binary media rejected); sidecar path `..` traversal rejected after
  `urlunparse`.
- **Optional shared-secret auth** via `X-API-Key` header (env
  `RESOLVER_API_KEY`; empty = disabled; missing/wrong → 401 `UNAUTHORIZED`
  with the frozen envelope; constant-time comparison).
- **Per-IP rate limiting** (in-memory sliding window; JSON 429 envelope with a
  `Retry-After: <seconds>` header; `/health` exempt).

### Resolvers
| Resolver | Handles | Downloads? |
|---|---|---|
| `direct_url` | Direct http(s) media links (.mp4, .webm, .mp3, .m4a, …) | ✅ Yes (the URL itself) |
| `youtube` / `facebook` / `tiktok` | Platform links | ❌ No by default — metadata only via official oEmbed. Opt-in yt-dlp (see §6). |

For platform links, `/extract` returns HTTP 200 with `downloadAvailable: false`
and a `message` explaining that downloads are not permitted via this
integration. No stream URLs are ever invented.

---
## 2. Run locally
```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate   # or use system Python 3.12
pip install -r requirements.txt
cp .env.example .env                                  # edit as needed
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
Then open <http://localhost:8000/docs> for interactive OpenAPI docs, or
<http://localhost:8000/openapi.json> for the raw schema.

Run the test suite:
```bash
python3 -m pytest tests -q   # 149 cases passed (102 test functions), 2026-11-19 hardening run
```

---
## 3. Deploy (production)
The Android app **enforces `https://`** for the backend base URL. A plain
`http://` endpoint will be rejected by the app. You must terminate TLS in
front of this service.

### Option A — Any VPS + Caddy (simplest, automatic HTTPS)
```Caddyfile
# /etc/caddy/Caddyfile
resolver.yourdomain.com {
    reverse_proxy localhost:8000
}
```
Caddy obtains and renews Let's Encrypt certificates automatically.

### Option B — fly.io / Render / Railway
- Use the provided `backend/Dockerfile`.
- Set environment variables (see `.env.example`) in the platform dashboard.
- All these platforms provide automatic HTTPS on their provided domains.

### Option C — Docker
```bash
docker build -t vidmatex-resolver backend/
docker run -d -p 8000:8000 --env-file backend/.env vidmatex-resolver
# Put behind Caddy / nginx / Traefik for TLS.
```

**Dockerfile notes** (`backend/Dockerfile`): `python:3.12-slim` base, installs
only `requirements.txt`, copies `app/`, runs as non-root `appuser`, exposes
8000, and does **not** bundle yt-dlp (opt-in `YTDLP_PATH` path only). Mount
an executable or build a custom image when enabling that branch; terminate
TLS at the proxy (the app requires `https://`).

**Hardening checklist:**
- Set `RESOLVER_API_KEY` to a long random string.
- Set `ALLOWED_ORIGINS` to an explicit list (native app doesn't need CORS).
- Set `RATE_LIMIT_PER_MINUTE` appropriate for your scale.
- Optionally set `REQUIRE_HTTPS_UPSTREAM=1` to block plain-http upstreams.
- Add an egress firewall/proxy denying RFC1918, loopback, link-local
  (incl. `169.254.169.254`), CGNAT `100.64.0.0/10` and TEST-NET ranges.
- If behind a reverse proxy, forward the real client IP (`X-Forwarded-For`)
  so per-IP rate limiting is accurate; a shared-store limiter is required for
  multiple instances (see §9 limitation 2).

---
## 4. Environment variables (`.env`)
| Variable | Default | Meaning |
|---|---|---|
| `RESOLVER_API_KEY` | (empty) | Shared-secret auth. Empty = auth disabled. Same value goes in app Settings → Resolver API key. |
| `PORT` | `8000` | HTTP listen port (behind TLS reverse proxy in prod). |
| `ALLOWED_ORIGINS` | (empty) | Comma-separated CORS origins (native app doesn't use CORS). |
| `MAX_REDIRECTS` | `5` | Max outbound redirect hops (each re-validated for SSRF). |
| `MAX_RESPONSE_BYTES` | `26214400` (25 MB) | Response size cap. Oversized → `UPSTREAM_ERROR`. |
| `REQUIRE_HTTPS_UPSTREAM` | (empty/0 = off) | `1` rejects plain `http://` upstream URLs; only `https://` outbound then allowed. |
| `SSRF_PIN_DNS` | `1` | Pin connect-time DNS resolution to validated IPs (DNS-rebinding mitigation); `0` disables. No-op under uvloop — pair with egress firewall. |
| `RATE_LIMIT_PER_MINUTE` | `30` | Per-IP rate limit on /extract and /subtitle. 0 = disable. 429 responses include `Retry-After: <seconds>`. |
| `MAX_REQUEST_BYTES` | `65536` (64 KB) | Request body cap (bodies are tiny JSON). Oversized → 413. |
| `YTDLP_PATH` | (empty) | OPTIONAL opt-in yt-dlp executable path. DISABLED BY DEFAULT. |
| `YTDLP_TIMEOUT` | `60` | yt-dlp subprocess timeout (seconds), only if `YTDLP_PATH` set. |

---
## 5. Point the Android app at it
In the VidMateX app: **Settings → Resolver endpoint** — enter your full
`https://` URL **ending with `/`**, e.g. `https://resolver.yourdomain.com/`.
If you set `RESOLVER_API_KEY`, also enter it in **Settings → Resolver API key**.
The base URL must end with `/` (Retrofit `@POST("extract")` appends to it).

---
## 6. Optional opt-in yt-dlp wrapper (disabled by default)
Set `YTDLP_PATH` to the path of a `yt-dlp` executable to enable real format +
stream + subtitle resolution for YouTube/Facebook/TikTok. **This is disabled by
default.** Enabling it is entirely your responsibility regarding platform Terms
of Service, copyright law, and any access restrictions; default mode performs
no DRM, login, or paywall bypass, and non-media (thumbnail/manifest) entries are
not mapped as download tracks.
Guardrails: input URL is always SSRF-validated before the subprocess spawns;
yt-dlp invoked via `asyncio.create_subprocess_exec` with an argument vector
(no shell, no command injection); hard timeout; unparseable JSON / nonzero exit
returns `UPSTREAM_ERROR`; subtitle URLs returned by yt-dlp are re-validated
through the SSRF guard and checked against the subtitle Content-Type allowlist
before download. Install yt-dlp separately (`pip install yt-dlp` or your
package manager) — it is **not** in `requirements.txt`.

---
## 7. API contract summary (frozen)
Base URL ends with `/`. All requests/responses `application/json; charset=utf-8`.
Optional auth header `X-API-Key`.

- `GET /health` → `{"status":"ok","version":"1.0.0","resolvers":[...]}`
- `POST /extract` body `{"url":"https://..."}` →
  `{"metadata":..., "formats":[...], "subtitles":[...], "streamUrls":{...}, "downloadAvailable":bool, "message":str|null}`
- `POST /subtitle` body `{"url":"...","trackId":"...","format":"srt|vtt|ass"}` →
  `{"content":"<subtitle text>","format":"srt"}`

Error shape (HTTP 4xx/5xx): `{"error":{"code":"<CODE>","message":"..."}}`
(exactly two keys inside `error`). Status mapping: `INVALID_URL` 400,
`SSRF_BLOCKED` 403, `PLATFORM_NOT_SUPPORTED` 400,
`PLATFORM_DOWNLOAD_NOT_PERMITTED` 403, `UPSTREAM_ERROR` 502, `RATE_LIMITED`
429 (plus `Retry-After: <seconds>`), `INTERNAL_ERROR` 500 (generic message,
no stack trace), `UNAUTHORIZED` 401 (only when `RESOLVER_API_KEY` set).
Request schemas ignore unknown extra fields consistently; response schemas
serialize only declared fields. Codes: `INVALID_URL`, `SSRF_BLOCKED`,
`PLATFORM_NOT_SUPPORTED`, `PLATFORM_DOWNLOAD_NOT_PERMITTED`, `UPSTREAM_ERROR`,
`RATE_LIMITED`, `INTERNAL_ERROR`, `UNAUTHORIZED`.

---
## 8. Example curl requests
```bash
# Health
curl https://resolver.yourdomain.com/health

# Extract a direct media URL
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret" \
  -d '{"url":"https://example.com/videos/sample.mp4"}'

# Fetch a sidecar subtitle
curl -X POST https://resolver.yourdomain.com/subtitle \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/videos/sample.mp4","trackId":"sidecar:srt","format":"srt"}'

# Platform link (metadata only, downloadAvailable=false)
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'

# SSRF blocked (returns 403)
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
```

---
## 9. Limitations (honest)
1. **DNS rebinding window (reduced, not eliminated)** — validation resolves DNS
   before connect; `SSRF_PIN_DNS=1` (default) pins connect-time resolution to
   validated IPs on the asyncio loop. Under uvloop the C resolver bypasses the
   Python patch, and TLS SNI cannot be pinned to an IP with this httpx
   version; enforce an egress firewall/proxy denying private/link-local/CGNAT
   ranges as the authoritative control.
2. **Rate limiting is in-memory** — restart resets counters; multiple instances
   don't share state. 429 responses carry `Retry-After`; move to a shared-store
   limiter (e.g. slowapi + Redis) for clustered deployments.
3. **Direct URL resolution** does not probe video dimensions/bitrate —
   `heightPx`/`fps`/`bitrateBps` are null for direct URLs (app handles this).
4. **Sidecar subtitle language is unknown** (`languageCode: "und"`); user picks
   the language in the app.
5. **yt-dlp opt-in branch** is covered by unit tests with a monkeypatched
   subprocess (argv vector, format mapping, failure paths); the real yt-dlp
   binary is not executed in CI. Validate the binary and your ToS compliance
   before enabling `YTDLP_PATH`.
6. **No persistent storage** — the backend is fully stateless.
