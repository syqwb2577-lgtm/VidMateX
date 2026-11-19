# External Services — VidMateX
VidMateX is **local-first by design**. No external service is required for the
core features (direct-URL download, on-device ML Kit translation, FFmpeg
embedding, local playback). Every external integration below is **optional**,
**user-configured in Settings**, and **not hardcoded**. No API keys,
endpoints, or secrets ship with the app.

| Service | Required? | Configured in | Purpose | Notes |
|---|---|---|---|---|
| **Resolver backend** (`backend/`, self-host) | Optional | Settings → Resolver backend → Resolver server URL + Resolver API key (Test connection); backend env `RESOLVER_API_KEY` | Resolve platform links (YouTube/Facebook/TikTok) + direct-URL sidecar subtitles | Included in repo (`backend/`, FastAPI). Metadata-only for platforms by default; real downloads require opt-in `YTDLP_PATH` on the operator's own host (operator responsibility). SSRF-protected (private/loopback/link-local/CGNAT/TEST-NET/doc-IP blocked, redirect re-validation, distinct connect/read/write/pool timeouts, transport errors mapped to 502, body/response caps, fixed User-Agent, URL-credential redaction, DNS pinning), optional X-API-Key auth (401 envelope; 429 with `Retry-After`). Backend pytest suite green: **149 cases / 102 test functions** (see `docs/TESTING_GUIDE.md`). |
| **LibreTranslate** (self-host) | Optional | Settings → Translation provider → LibreTranslate + endpoint; documented env name `LIBRE_TRANSLATE_BASE_URL` | Subtitle translation via self-hosted LibreTranslate | Alternative to on-device ML Kit. Subtitle text sent to the endpoint you configure. Use HTTPS. No API key is bundled. |
| **Whisper endpoint** (self-host or paid OpenAI-compatible) | Optional | Settings → Whisper endpoint (`WHISPER_ENDPOINT`) + Whisper API key (`WHISPER_API_KEY`) | Speech-to-text: transcribe downloaded audio/video file into SRT/VTT cues via WorkManager `TranscriptionWorker` | Worker + client implemented; requires a configured endpoint. Self-host via faster-whisper-server / whisper.cpp, or paid OpenAI/Groq/DeepInfra. Multipart `file` + `model=whisper-1` + `response_format=srt`; `Authorization: Bearer` when key set; 24 MB single-upload cap, >24 MB split into 600-second chunks; 429/5xx retried ×3, 4xx fail fast. API key stored encrypted at rest (Android Keystore AES/GCM), never logged. See `docs/WHISPER_SETUP.md`. |
| **Google ML Kit Translation** (on-device) | Built-in, no service | Settings → Translation provider → ML Kit (default) | On-device subtitle translation (offline) | Runs entirely on-device; no text leaves the phone. Models downloaded on first use. No account, no API key. Subject to ML Kit Terms. |
| **Direct media origins** (any https URL) | N/A | Pasted by user | Downloading direct `.mp4`/`.m4a`/`.webm` files | Your device connects directly to the origin you pasted. No proxy. |

---
### Resolver backend — in-app configuration, hosting, resources, compliance

**In-app configuration (Android, exact steps):**
1. Open **Settings → Resolver backend**.
2. **Resolver server URL** = your backend's `https://` base URL, e.g.
   `https://your-resolver.example/` (the field is the single Resolver Server URL;
   it maps to `backendBaseUrl`; there is no second URL field). `http://` and
   malformed URLs are rejected by the client.
3. **Resolver API key** = the **same value** as the backend environment variable
   `RESOLVER_API_KEY`. Toggle the eye icon to show/hide it while typing.
4. Tap **Test connection**. The app calls `GET /health` (this route is exempt
   from auth and rate limiting) and shows a green **Connected · version … ·
   N resolver(s)** status, or a red inline error (not configured / invalid URL /
   timeout / no network / HTTP error). Error text never contains the key.
5. Fields persist as you type; the top-bar **Save** action confirms with a
   "Settings saved" snackbar. Leave both fields blank to disable the resolver.

**Backend-side matching variable:**
- Set `RESOLVER_API_KEY=<long-random-string>` in `backend/.env` (copy from
  `backend/.env.example`; placeholders only, no real secrets in the repo).
  The backend compares it (constant-time) against the `X-API-Key` header on
  `/extract` and `/subtitle`; a missing/wrong key returns HTTP 401
  `{"error":{"code":"UNAUTHORIZED",...}}`. If `RESOLVER_API_KEY` is empty,
  backend auth is disabled and the app may leave the app-side key blank.
  Per-IP rate limiting returns HTTP 429 `RATE_LIMITED` with a `Retry-After`
  header; `/health` is exempt. Use TLS in production; the app only sends the
  header over `https://`.

**Secret storage / transport security (app side):**
- The key travels only as the `X-API-Key` header over HTTPS; the HTTP logging
  interceptor level is `NONE` and no code logs the key (no `Log`/`println`/
  `printStackTrace` around secrets).
- At rest, both the resolver and Whisper API keys are encrypted with the
  Android Keystore (AES/GCM/NoPadding, 256-bit key, stable alias
  `vidmatex_secret_key_v1`) and stored as Base64 ciphertext tokens in a
  dedicated DataStore file (`vidmatex_settings_secrets`); non-secret settings
  remain ordinary preferences in `vidmatex_settings`. Legacy plaintext rows are
  removed on the next write. If the Keystore is unavailable on a device, a
  documented plain-passthrough fallback prevents startup crashes; this is a
  degradation, not the normal path. No key value is ever shipped in the APK.

**Hosting options (choose one; the Android app requires an `https://` base URL):**
1. **Small VPS + Caddy** (recommended): any 1 GB VPS (Debian/Ubuntu),
   `docker compose` or bare `uvicorn`, Caddy obtains Let's Encrypt TLS
   automatically. Full control over egress firewall rules (see security notes).
2. **PaaS** (fly.io / Render / Railway / similar): deploy `backend/Dockerfile`,
   set env vars in the dashboard; platforms terminate TLS on their domains.
3. **Self-hosted Docker behind nginx/Traefik**: `docker build -t
   vidmatex-resolver backend/`; terminate TLS at the proxy. The image runs as
   non-root and bundles no yt-dlp.
4. **LAN / personal lab**: the app enforces `https://`, so plain `http://` LAN
   addresses are rejected by the client; use a local TLS reverse proxy with a
   trusted certificate for testing.

**Minimum hardware / resources:**
- Stateless FastAPI + uvicorn service; **no media transcoding, no database, no
  persistent disk**. It returns metadata JSON and subtitle text; media bytes
  stream directly between the phone and the origin.
- Realistic minimum: **1 vCPU / 256 MB RAM** (128 MB works for single-user
  metadata mode); response bodies are capped (25 MB default; subtitles 5 MB).
- Opt-in `YTDLP_PATH` adds short-lived subprocess invocations; allow a few
  hundred MB extra peak and place the executable outside the app directory.

**Optional cost:**
- Software itself is free; the only cost is the host (free tiers or a
  low-cost VPS are sufficient). No paid API key, telemetry, or analytics
  dependency exists. Bandwidth for media downloads flows phone ↔ origin; the
  backend does not proxy large media bodies.

**Compliance notes:**
- Default mode fetches **public official oEmbed metadata only** for
  YouTube/Facebook/TikTok and never emits download stream URLs for them
  (`downloadAvailable=false`).
- Enabling `YTDLP_PATH` (opt-in, off by default) makes downloads possible, and
  it runs **only on the operator's self-hosted backend host** — it is not part
  of the Android app, not in `requirements.txt`, and not bundled in the
  Docker image. **The operator is responsible** for platform Terms of Service,
  copyright law, and any access restrictions in their jurisdiction. Do not
  use it for DRM-protected, paywalled, or private content; the service
  implements no DRM/auth bypass. Default platform mode emits no stream URLs
  and `downloadAvailable=false` with the not-permitted message.
- Operate behind TLS, set `RESOLVER_API_KEY`, restrict CORS, and apply an
  egress firewall that blocks private/link-local/CGNAT ranges (application
  SSRF guards are defense-in-depth against the DNS TOCTOU window).

---

## What is NOT included / hardcoded
- ❌ No built-in resolver for YouTube/Facebook/TikTok (no invented stream URLs,
  no DRM bypass, no login circumvention).
- ❌ No hardcoded API keys, endpoints, or secrets anywhere in source.
- ❌ No analytics / crash-reporting / advertising SDK.
- ❌ No VidMateX-operated server (none exists).
- ❌ No yt-dlp bundled (optional `YTDLP_PATH` opt-in on your self-hosted
  backend only; not in `requirements.txt`).

---
## Security guidance
- **Use HTTPS** for every endpoint you configure (resolver, Whisper,
  LibreTranslate). API keys are sent as headers; plain HTTP exposes them.
- **API keys** (resolver + Whisper) are encrypted at rest with the Android
  Keystore (AES/GCM/256) and held in a dedicated app-private DataStore file;
  they are never logged and never shipped in the APK. Set a device lock. Never
  commit a filled-in `.env` or a real key to the repo.
- **Self-hosted services** should be behind a reverse proxy with auth or on a
  private network; otherwise anyone who finds the URL can use them.
- **You are responsible** for any service you enable (cost, ToS compliance,
  security). The app only sends data to endpoints *you* enter.
