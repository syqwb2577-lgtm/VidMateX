# VidMateX Resolver Backend

A self-hosted FastAPI service that resolves direct media URLs and (optionally)
platform metadata for the VidMateX Android app. The app never ships a
hardcoded backend endpoint or API key — you deploy this yourself.

**API contract is frozen — see `../CONTRACTS.md` §2 and the Android Retrofit
interface `core/network/BackendApi.kt`. This backend matches both exactly.**

---

## 1. What it does

- `POST /extract` — given a URL, returns metadata + available media tracks +
  direct stream URLs + subtitle tracks.
- `POST /subtitle` — downloads a specific subtitle track and returns its text.
- `GET /health` — liveness check + active resolver list.
- **SSRF protection** on every outbound request (see §5).
- **Optional shared-secret auth** via `X-API-Key` header (constant-time
  comparison; missing/wrong key → 401 `UNAUTHORIZED` with the frozen envelope).
- **Per-IP rate limiting** (in-memory sliding window; HTTP 429 carries a
  `Retry-After: <seconds>` header). `slowapi` is pinned in requirements.txt as
  a drop-in alternative (e.g. with a shared store); the built-in limiter keeps
  the frozen JSON envelope exact.
- **Request/response size caps.**
- **Outbound hygiene:** fixed descriptive `User-Agent` on every guarded
  request; credential-bearing URL userinfo (`user:pass@host`) redacted from
  errors/logs; no request/response headers (which may carry secrets) logged.

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
python3 -m pytest tests -q
```

### Environment variables

All values are read from the process environment at startup; `.env.example`
contains placeholders only (never commit real secrets).

| Variable | Default | Meaning |
|---|---|---|
| `RESOLVER_API_KEY` | (empty = disabled) | Shared-secret auth; requests must send matching `X-API-Key`. Missing/wrong → 401 `UNAUTHORIZED`. |
| `PORT` | `8000` | HTTP listen port (always behind a TLS reverse proxy in production). |
| `ALLOWED_ORIGINS` | (empty) | Comma-separated CORS origins; native app does not use CORS. |
| `MAX_REDIRECTS` | `5` | Max outbound redirect hops; each hop re-validated for SSRF. |
| `MAX_RESPONSE_BYTES` | `26214400` (25 MB) | Outbound response body cap; overflow → `UPSTREAM_ERROR`. |
| `REQUIRE_HTTPS_UPSTREAM` | (empty/0 = off) | Set to `1` to reject plain `http://` upstream URLs; only `https://` outbound then allowed. Off by default for compatibility with older http media origins. |
| `SSRF_PIN_DNS` | `1` | DNS-rebinding mitigation: pin connect-time resolution to the validated IP set; `0` disables. No-op on the uvloop C resolver — pair with egress firewall (§10). |
| `RATE_LIMIT_PER_MINUTE` | `30` | Per-IP sliding-window limit on `/extract` and `/subtitle`; `0` disables. 429 responses include `Retry-After` seconds. |
| `MAX_REQUEST_BYTES` | `65536` (64 KB) | Request body cap (enforced with or without `Content-Length`); overflow → 413. |
| `YTDLP_PATH` | (empty = disabled) | OPTIONAL opt-in yt-dlp executable path; see §6. Not in `requirements.txt`. |
| `YTDLP_TIMEOUT` | `60` | yt-dlp subprocess timeout in seconds (only when `YTDLP_PATH` set). |

Socket timeouts are code constants in `app/ssrf_guard.py` (connect 5 s,
read/write 15 s, pool 5 s) rather than environment variables.

---

## 3. Deploy (production)

The Android app **enforces `https://`** for the backend base URL (see
`NetworkModule` / `RetrofitFactory`). A plain `http://` endpoint will be
rejected by the app. You must terminate TLS in front of this service.

### Option A — Any VPS + Caddy (simplest, automatic HTTPS)

```Caddyfile
# /etc/caddy/Caddyfile
resolver.yourdomain.com {
    reverse_proxy localhost:8000
}
```

Caddy obtains and renews Let's Encrypt certificates automatically.

### Option B — fly.io / Render / Railway

- Use the provided `Dockerfile`.
- Set environment variables (see `.env.example`) in the platform dashboard.
- All these platforms provide automatic HTTPS on their provided domains.

### Option C — Docker

```bash
docker build -t vidmatex-resolver backend/
docker run -d -p 8000:8000 --env-file backend/.env vidmatex-resolver
# Put behind Caddy / nginx / Traefik for TLS.
```

Docker notes:
- The image runs as a non-root `appuser` and contains only the Python deps;
  it does **not** bundle yt-dlp (opt-in path, §6). To enable it, mount an
  executable into the container (e.g. `-v /usr/local/bin/yt-dlp:/usr/local/bin/yt-dlp:ro`)
  or build a custom image that installs yt-dlp, then set `YTDLP_PATH`.
- No stateful volume is required (stateless service); restrict container
  capabilities and memory (the response cap keeps peak body memory bounded).
- Add a container `HEALTHCHECK` against `GET /health` behind the proxy if
  your orchestrator supports it; `/health` is exempt from rate limiting.

**Hardening checklist:**
- Set `RESOLVER_API_KEY` to a long random string.
- Set `ALLOWED_ORIGINS` to an explicit list (or leave default; native app
  doesn't need CORS).
- Set `RATE_LIMIT_PER_MINUTE` appropriate for your scale.
- Optionally set `REQUIRE_HTTPS_UPSTREAM=1` to block plain-http upstream fetches.
- Enforce an **egress firewall / proxy** denying RFC1918, loopback,
  link-local (incl. `169.254.169.254`), CGNAT `100.64.0.0/10` and TEST-NET
  ranges; this is the authoritative SSRF control (see §10).
- If behind a reverse proxy, configure it to forward the real client IP
  (`X-Forwarded-For`) so per-IP rate limiting is accurate. Multi-instance
  deployments need a shared-store limiter (e.g. slowapi + Redis); documented
  limitation — see §10.

---

## 4. Point the Android app at it

In the VidMateX app: **Settings → Resolver endpoint** — enter your full
`https://` URL **ending with `/`**, e.g. `https://resolver.yourdomain.com/`.
If you set `RESOLVER_API_KEY`, also enter it in **Settings → Resolver API key**.

The base URL must end with `/` (Retrofit `@POST("extract")` appends to it).

---

## 5. SSRF protection (mandatory, on every outbound request)

All outbound HTTP requests go through `app/ssrf_guard.py`:

1. **Scheme** must be `http` or `https` (`file://`, `ftp://`, `gopher://`,
   `data:` etc. rejected → `INVALID_URL`). No filesystem path can be fetched.
2. **URL length** capped at 2048 characters (enforced by `schemas.py` and the
   guard; `ExtractRequest`/`SubtitleRequest` reject longer URLs with 400).
3. **DNS resolution** via `socket.getaddrinfo` (IPv4 + IPv6). **Any** resolved
   IP in the following families is rejected → `SSRF_BLOCKED` (HTTP 403); raw-IP
   hosts are checked directly, hostnames after DNS:
   - private RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`),
     loopback (`127.0.0.0/8`, `::1`), unspecified (`0.0.0.0`, `::`);
   - link-local, incl. cloud metadata `169.254.169.254` and the whole
     `fe80::/10` IPv6 range;
   - multicast (`224.0.0.0/4`, `ff00::/8`), reserved (`240.0.0.0/4`);
   - CGNAT shared address space `100.64.0.0/10` (RFC 6598);
   - TEST-NET / documentation ranges `192.0.2.0/24`, `198.51.100.0/24`,
     `203.0.113.0/24` (RFC 5737) and IPv6 documentation `2001:db8::/32`;
   - IPv6 unique-local `fc00::/7`.
4. **Redirects followed manually** (default max 5, `MAX_REDIRECTS`). Every
   hop's `Location` must parse to `http`/`https` and its host must be
   re-resolved + re-validated before the next request. Redirect loops (a URL
   visited twice) and hop counts above the limit raise `UPSTREAM_ERROR`.
5. **Distinct httpx timeouts**: connect ~5 s, read/write ~15 s, pool ~5 s,
   applied to every guarded request. Transport/timeout errors
   (`ConnectTimeout`, `ReadTimeout`, connection resets, protocol errors) are
   mapped to `UPSTREAM_ERROR` (HTTP 502) — no raw exception or stack trace
   reaches the client.
6. **Response size cap** (default 25 MB, `MAX_RESPONSE_BYTES`); oversized
   responses are aborted → `UPSTREAM_ERROR`. Subtitle downloads capped at 5 MB.
7. **Request body cap** (default 64 KB, `MAX_REQUEST_BYTES`) → HTTP 413 with
   the frozen error envelope. The middleware reads `await request.body()` even
   when no `Content-Length` header is sent (chunked encoding), so oversized
   bodies cannot slip past on missing headers. Request bodies are tiny JSON.
8. **Subtitle Content-Type allowlist**: sidecar probes and subtitle downloads
   accept only `text/plain`, `text/vtt`, `text/srt`, `application/x-subrip`,
   `text/ass`/`text/x-ass`; `application/octet-stream` is accepted only when
   the sidecar extension is `srt`/`vtt`/`ass`. Binary media types
   (`video/*`, `audio/*`, `image/*`, …) are rejected.
9. **Sidecar path traversal guard**: derived sidecar URLs are rebuilt with
   `urlunparse` and rejected if the path contains a `..` segment.
10. **No local file writes**: the backend is stateless; it only issues HTTP
    requests and returns JSON. No endpoint reads or writes server files.
11. **User-Agent**: every guarded request sends a fixed, descriptive
    `User-Agent` (`VidMateX-Resolver/1.0.0 ...`); caller headers may extend or
    override it but cannot strip safety headers.
12. **Secret hygiene in logs/errors**: URLs carrying `user:password@`
    userinfo are passed through `redact_url()` before appearing in error
    messages or warnings; request/response headers (which may carry keys) are
    never logged. The `X-API-Key` itself is only compared, never logged.
13. **HTTPS-upstream policy (opt-in)**: set `REQUIRE_HTTPS_UPSTREAM=1` to
    reject plain `http://` upstream URLs at validation time; off by default.
14. **DNS-rebinding pinning (`SSRF_PIN_DNS`, default on)**: after the host
    passes validation, connect-time `getaddrinfo` for that host returns only
    the validated IPs for the duration of the request, removing most of the
    check-to-use gap. See the honest limitation in §10 (limitations).

**Honest limitation:** DNS is resolved before the httpx connection is made, so
there is a small TOCTOU (time-of-check-to-time-of-use) window where a host
could resolve to a safe IP at check time and a different (internal) IP at
connect time — DNS rebinding. For a fully TOCTOU-safe deployment, resolve the
IP yourself and connect to it with a Host/SNI override, or run behind an
egress proxy that enforces IP policy. This implementation matches the approach
specified in CONTRACTS §2.

---

## 6. Optional opt-in yt-dlp wrapper (disabled by default)

Set `YTDLP_PATH` to the path of a `yt-dlp` executable (e.g.
`/usr/local/bin/yt-dlp`) to enable real format + stream + subtitle resolution
for YouTube/Facebook/TikTok. **This is disabled by default.** When disabled,
platform links resolve metadata only and `downloadAvailable=false`.

**Compliance / user responsibility (read before enabling):**
- Enabling downloads via `YTDLP_PATH` is **your** responsibility: you must
  comply with each platform's Terms of Service, copyright law in your
  jurisdiction, and any robots/usage restrictions. The upstream project
  (`yt-dlp`) is an external tool with its own license; it is not bundled and
  not pinned by this repository.
- Default mode never bypasses DRM, paywalls, login walls, or region locks.
  Encrypted/DRM-protected media cannot be produced as playable stream URLs;
  such entries are skipped rather than fabricated.
- Platform metadata in default mode comes **only** from each platform's
  official oEmbed endpoint (YouTube, Facebook, TikTok public oEmbed). There is
  no page scraping, no cookie/login storage, and no account impersonation.

Guardrails implemented in code:
- The input URL always passes through the full SSRF validator
  (scheme/length/DNS) before the subprocess is spawned; an invalid URL aborts
  before `create_subprocess_exec` runs.
- yt-dlp is invoked via `asyncio.create_subprocess_exec` with an argument
  vector (`[YTDLP_PATH, "-j", "--no-playlist", "--no-warnings", "--", url]`)
  — no shell, no concatenation, so the URL cannot inject shell metacharacters.
- Hard timeout (`YTDLP_TIMEOUT`, default 60 s); non-zero exit or unparseable
  stdout JSON raises `UPSTREAM_ERROR` (no partial/fabricated response).
- Only entries that carry a video (`vcodec`) or audio (`acodec`) codec become
  tracks; thumbnails/manifest/attachment entries are skipped. `downloadAvailable`
  is `true` only when at least one real format was mapped; empty results keep
  `downloadAvailable=false` with the not-permitted message.
- Subtitle URLs returned by yt-dlp are re-validated through the SSRF guard and
  checked against the subtitle Content-Type allowlist before download.

Install yt-dlp separately (e.g. `pip install yt-dlp` or your package manager).
It is **not** in `requirements.txt`, and the provided Docker image does not
bundle it; to use this opt-in path, mount an executable into the image or
build a custom image that installs yt-dlp.

---

## 7. Error codes (frozen)

All errors return `{"error":{"code":"<CODE>","message":"..."}}`:

| Code | HTTP | Meaning |
|---|---|---|
| `INVALID_URL` | 400 | Malformed URL, bad scheme, too long, bad request body |
| `SSRF_BLOCKED` | 403 | Host resolves to a disallowed IP range |
| `PLATFORM_NOT_SUPPORTED` | 400 | No resolver handles this URL |
| `PLATFORM_DOWNLOAD_NOT_PERMITTED` | 403 | Reserved — platform download not permitted (default flow returns 200 with `downloadAvailable:false` instead) |
| `UPSTREAM_ERROR` | 502 | DNS failure, upstream HTTP error, too many redirects, oversized response, yt-dlp failure |
| `RATE_LIMITED` | 429 | Per-IP rate limit exceeded; response carries a `Retry-After: <seconds>` header |
| `INTERNAL_ERROR` | 500 | Unhandled server error |
| `UNAUTHORIZED` | 401 | Only when `RESOLVER_API_KEY` is set and the `X-API-Key` header is missing/wrong. Not in the frozen list; added because auth needs a code. |

---

## 8. Example requests & responses

### `GET /health`

```bash
curl https://resolver.yourdomain.com/health
```
```json
{"status":"ok","version":"1.0.0","resolvers":["youtube","facebook","tiktok","direct_url"]}
```

### `POST /extract` — direct media URL (success)

```bash
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -H "X-API-Key: your-secret" \
  -d '{"url":"https://example.com/videos/sample.mp4"}'
```
```json
{
  "metadata": {
    "sourceType": "DIRECT_URL",
    "sourceUrl": "https://example.com/videos/sample.mp4",
    "sourceId": "direct",
    "title": "sample",
    "author": null,
    "durationSec": null,
    "thumbnailUrl": null,
    "description": null
  },
  "formats": [
    {
      "trackId": "direct-0",
      "kind": "VIDEO",
      "container": "mp4",
      "heightPx": null,
      "widthPx": null,
      "fps": null,
      "vcodec": null,
      "acodec": null,
      "bitrateBps": null,
      "sampleRate": null,
      "estimatedBytes": 15728640,
      "language": null,
      "resumable": true
    }
  ],
  "subtitles": [
    {
      "trackId": "sidecar:srt",
      "languageCode": "und",
      "languageName": "Undetermined",
      "kind": "CREATOR_PROVIDED",
      "isTranslatable": true,
      "defaultFormat": "SRT"
    }
  ],
  "streamUrls": {"direct-0": "https://example.com/videos/sample.mp4"},
  "downloadAvailable": true,
  "message": null
}
```

### `POST /subtitle` — fetch sidecar subtitle

```bash
curl -X POST https://resolver.yourdomain.com/subtitle \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/videos/sample.mp4","trackId":"sidecar:srt","format":"srt"}'
```
```json
{
  "content": "1\n00:00:01,000 --> 00:00:04,000\nHello world\n\n",
  "format": "srt"
}
```

### `POST /extract` — platform link (not permitted, metadata only)

```bash
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'
```
```json
{
  "metadata": {
    "sourceType": "YOUTUBE",
    "sourceUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    "sourceId": "watch?v=dQw4w9WgXcQ",
    "title": "Rick Astley - Never Gonna Give You Up (Official Music Video)",
    "author": "Rick Astley",
    "durationSec": null,
    "thumbnailUrl": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    "description": null
  },
  "formats": [],
  "subtitles": [],
  "streamUrls": {},
  "downloadAvailable": false,
  "message": "This platform does not permit downloading via this integration. To enable downloads, deploy a compliant resolver and set the YTDLP_PATH environment variable (opt-in, your responsibility). See backend/README.md."
}
```

### Error example — SSRF blocked

```bash
curl -X POST https://resolver.yourdomain.com/extract \
  -H "Content-Type: application/json" \
  -d '{"url":"http://169.254.169.254/latest/meta-data/"}'
```
```json
{"error":{"code":"SSRF_BLOCKED","message":"URL host resolves to a disallowed (private/loopback/...) IP: 169.254.169.254"}}
```

---

## 9. OpenAPI / docs

- Interactive Swagger UI: `/docs`
- ReDoc: `/redoc`
- Raw OpenAPI schema: `/openapi.json`

---

## 10. Limitations (honest)

1. **TOCTOU DNS window (reduced, not eliminated)** — the host is DNS-resolved
   and validated before the httpx connection is opened. With `SSRF_PIN_DNS=1`
   (default), connect-time `getaddrinfo` for that host is pinned to the
   validated IP set for the request duration, closing most of the
   check-to-use gap on the default asyncio event loop. Honest residual gaps:
   (a) under **uvloop** (uvicorn `[standard]` default) the event loop's C
   getaddrinfo bypasses the Python patch, so the pinning is inactive there;
   (b) IP-level pinning to a hostname cannot override TLS SNI/cert validation
   with this httpx version. Mitigations, in increasing strength: (a) place
   the backend behind an **egress proxy / firewall** that denies RFC1918,
   loopback, link-local, CGNAT and metadata ranges at the network layer
   (authoritative under uvloop and in production); (b) keep `SSRF_PIN_DNS=1`
   on the asyncio loop for defense-in-depth; (c) run the service with no
   inbound LAN route and a restricted security group. Application-level
   checks (this file) are defense-in-depth, not a replacement for network
   policy.
2. **Rate limiting is in-memory** — restart resets counters; multiple
   instances do not share state. Behind a load balancer, each instance counts
   independently. Configure your proxy for per-IP limiting if you need strict
   global limits.
3. **Direct URL resolution** does not probe video dimensions/bitrate (only
   Content-Type, Content-Length, Accept-Ranges/206). `heightPx`/`fps`/
   `bitrateBps`/`durationSec` stay null when unavailable and are never
   fabricated. The Android app handles nulls gracefully.
4. **Sidecar subtitle language is unknown** (`languageCode: "und"`) because a
   bare sidecar file carries no language metadata. The user picks the language
   in the app.
5. **Subtitle format conversion** is best-effort srt↔vtt only. ASS is returned
   natively and never converted. Complex WebVTT features (cue settings,
   regions, STYLE blocks) are stripped on conversion.
6. **Platform metadata** comes from official oEmbed endpoints and may be
   absent (then title falls back to "Untitled"). No scraping, no login/cookie
   storage, no DRM decryption, no paywall or region-lock bypass. DRM-protected
   and auth-walled media never yields playable stream URLs; such formats are
   skipped, not fabricated.
7. **yt-dlp opt-in branch** is unit-tested with a monkeypatched subprocess
   (argument vector, JSON mapping, empty-format and failure paths); the real
   yt-dlp binary is not executed in CI, and its availability/output changes
   upstream over time. Validate the binary and your ToS compliance before
   enabling in production.
8. **No persistent storage** — the backend is fully stateless. Expiring stream
   URLs (from yt-dlp) are consumed immediately by the app.
