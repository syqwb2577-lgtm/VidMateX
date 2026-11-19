# Security — VidMateX

This document describes the threat model, implemented controls, and reporting
process for VidMateX (Android client + optional self-hosted FastAPI resolver).
It pairs with `docs/EXTERNAL_SERVICES.md`, `docs/FFMPEG.md`, and
`docs/TESTING_GUIDE.md`.

## 1. Boundaries and trust assumptions

- **Trust boundary 1 — Android device.** Downloads, settings, and encrypted
  secrets live in app-private storage. The app never ships a hardcoded server,
  key, or endpoint.
- **Trust boundary 2 — User-configured backend.** The resolver backend
  (`backend/`) is self-hosted by the operator; VidMateX does not operate a
  server. The app only connects to an `https://` endpoint the user enters.
- **Trust boundary 3 — Remote origins.** Direct media URLs and platform
  metadata responses are untrusted input; parsing and size limits apply
  before anything reaches a player or FFmpeg vector.
- **Out of scope / documented limits:** physical device compromise, a rooted
  device, user-supplied malicious APKs, and server-side compromise of an
  operator's own host.

## 2. Backend (`backend/`) threat model and controls

| Threat | Control | Code / evidence |
|---|---|---|
| SSRF to private/loopback/link-local/CGNAT/TEST-NET/doc-IP ranges on `/extract` | Every outbound URL resolved and validated; private IPv4 ranges, IPv6 ULA/link-local/loopback, multicast/reserved ranges, and documentation IPs rejected; non-`http(s)` schemes rejected | `backend/app/ssrf_guard.py`; 47 cases in `tests/test_ssrf_guard.py` |
| Redirect-driven SSRF (30x to internal IP) | Manual redirect follow (max 5 hops); every redirect `Location` re-validated through the same SSRF guard before fetch | `backend/app/ssrf_guard.py`; redirect tests in `test_ssrf_guard.py` |
| Large upstream body exhausting memory | Response body cap (default 25 MB, `MAX_RESPONSE_BYTES`); overflow returns upstream error, partial body discarded | `backend/app/main.py`, `ssrf_guard.py` |
| Oversized request body (JSON) | Request body cap (default 64 KB, `MAX_REQUEST_BYTES`); 413 on overflow; enforcement even without `Content-Length` | `backend/app/main.py` middleware; `test_endpoints.py` |
| Abuse / scraping | Per-IP sliding-window rate limit (default 30/min, `RATE_LIMIT_PER_MINUTE`); `429` JSON envelope; `0` disables | `backend/app/main.py`; `tests/test_endpoints.py` |
| Unauthenticated metadata/proxy use | Optional shared-secret auth: `RESOLVER_API_KEY` compared against the `X-API-Key` header; missing/wrong → `401 UNAUTHORIZED`; empty key disables auth (operator choice) | `backend/app/main.py`; `test_endpoints.py` |
| Content-type / content confusion | Outbound fetches are method- and content-type checked; platform oEmbed paths only parse known JSON shapes; direct URLs expose headers but never execute payloads | `backend/app/resolvers/`; `test_direct_url.py` |
| DNS rebinding / TOCTOU between resolve and connect | Guard pins the validated address family/host through the request; residual window acknowledged in §7 (no kernel-level pinning in Python) | `backend/app/ssrf_guard.py` |
| Shell injection via URL or `yt-dlp` arguments | yt-dlp (optional, disabled by default) runs as an argv subprocess, never through a shell; input URL SSRF-validated first; timeout enforced; not in `requirements.txt` | `backend/app/resolvers/platform.py`; `test_platform.py` |
| Platform Terms-of-Service abuse | Default metadata-only (`downloadAvailable=false`); real downloads require the operator's explicit `YTDLP_PATH` opt-in; no DRM/auth bypass code | `backend/app/resolvers/platform.py`; `docs/EXTERNAL_SERVICES.md` |
| CORS abuse | Methods limited to `GET, POST`; headers limited to `X-API-Key, Content-Type`; origins configurable via `ALLOWED_ORIGINS`; credentials disabled | `backend/app/main.py` |
| Dependency supply chain | Pinned `backend/requirements.txt`; tests run against pinned set; no post-install scripts beyond the pinned wheels | `backend/requirements.txt` |

### Backend hardening checklist (operator)

Set a long random `RESOLVER_API_KEY`; terminate TLS (Caddy/nginx/Traefik); set
explicit `ALLOWED_ORIGINS` if browsers call the API; apply an egress firewall
denying private ranges (defense-in-depth against the DNS TOCTOU window); keep
the image patched; run as the non-root user defined in `backend/Dockerfile`.

## 3. Android client threat model and controls

| Threat | Control | Location |
|---|---|---|
| Stored secrets (resolver API key, Whisper API key) leaked via backups/logs | Android Keystore AES/GCM/NoPadding, 256-bit key (`vidmatex_secret_key_v1`); only Base64 ciphertext tokens persisted in a dedicated DataStore file; plaintext exists solely in memory; legacy plain rows re-encrypted on next write; Keystore-unavailable fallback is a documented passthrough that never logs values | `feature/settings/security/SecretStore.kt`, `SecretCodecs.kt` (7 unit cases) |
| Secret exfiltration through logs/network | OkHttp logging interceptor level `NONE`; worker payloads carry path/language/job id only; error messages redact long token-like strings; `redact()` exposes length only | `core/network/NetworkModule.kt`, `media/processor/SpeechToTextImpls.kt`, `SecretCodecs.kt` |
| Cleartext credential transport | Backend/Whisper/LibreTranslate endpoints must be `https://`; plain `http` accepted only for loopback development hosts; network security config denies cleartext by default | `core/network/BackendUrlValidator.kt`, `media/processor/SpeechToTextImpls.kt`, `app/src/main/res/xml/network_security_config.xml` |
| Malicious URL (javascript:/file:/data:/ftp:/intent:, userinfo, oversized) | `UrlValidator` allow-list (`http`,`https`), blocked scheme list, 2048-char cap, userInfo rejection, private-host detection; backend base URL validator rejects non-HTTPS/host-less values | `core/common/UrlValidator.kt` (23 cases), `core/network/BackendUrlValidator.kt` (9 cases) |
| FFmpeg command injection (filter graph / paths) | Argument-vector builder only; fixed allowlisted tokens (`-c:v libx264`, presets, log levels); user paths are file paths, never shell strings; filter-path single-quote/backslash/colon escaping; no shell invocation | `media/processor/FFmpegArgs.kt` (16 vector cases); host validation `scripts/validate_ffmpeg_args.sh` |
| Path traversal in downloaded filenames | `sanitizeFileName` strips separators/unsafe chars, collapses separators, truncates to 120 chars, keeps Unicode letters (Arabic preserved) | `feature/downloads/DownloadWorker.kt` helpers; `DownloadWorkerHelpersTest.kt` |
| Exported-component abuse | Receiver `exported=false`; FileProvider `exported=false` + `grantUriPermissions=true`; no exported Activity beyond the launcher; WorkManager default initializer removed (on-demand Hilt init) | manifests; `VidMateXApplication.kt` |
| Unprotected background work | WorkManager exponential backoff; retry classifier separates transient (network/timeout/429/5xx) from permanent (storage/permission/unsupported/resolver/cancelled); embed retries capped at 2 | `feature/downloads/RetryPolicy.kt`, `ResumePolicy.kt`; `feature/subtitles/transcription/TranscriptionRetryPolicy.kt` |
| Unnecessary permissions | Minimal set: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES` (self-update flow, user-confirmed) | `app/src/main/AndroidManifest.xml` |
| Storage access scope (no broad file access) | Downloads go through the user-selected SAF tree or MediaStore; legacy `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` are not requested on API 24+; file names are sanitized against path traversal; no hardcoded external-storage paths | `feature/downloads/FileStorageHelper.kt`, `FileNamePolicy.kt`, manifest |
| URL / SSRF / redirect policy (backend) | Every resolver outbound URL is scheme-allowed (`http`/`https` only), host-validated against private/loopback/link-local/CGNAT/TEST-NET/doc ranges, and each redirect `Location` is revalidated before fetch; client side rejects non-HTTPS public endpoints and blocked schemes | `backend/app/ssrf_guard.py`, `core/common/UrlValidator.kt`, `core/network/BackendUrlValidator.kt` |
| Log hygiene | OkHttp logging interceptor at level `NONE`; workers log job id/path/language only; long token-like strings are redacted to length; API keys and `Authorization` headers are never printed; backend access logs do not record query credentials | `core/network/NetworkModule.kt`, `SecretCodecs.kt`, `backend/app/main.py` |
| Dependency provenance | Every coordinate verified against Maven Central / Google Maven metadata during the compatibility audit; FFmpeg fork provenance table in `docs/FFMPEG.md`; no removed-repo (`maven.arthenica.com`) references remain | `docs/COMPATIBILITY.md`, `docs/FFMPEG.md` |
| Analytics / tracking | No analytics, crash-reporting, advertising, or telemetry SDK; no device identifiers sent | build scripts; manifests |

## 4. Network security notes

- The app enforces `https://` for the resolver base URL and Whisper endpoint;
  plain `http` public hosts are rejected by the validators.
- API keys travel only as headers (`X-API-Key`, `Authorization: Bearer …`)
  over HTTPS; they are never query parameters and never printed.
- Subtitle text and media bytes flow between the device and the endpoint the
  user configured; the app does not proxy media through VidMateX-operated
  infrastructure (none exists).

## 5. Supply chain and build hygiene

- No APK/AAB artifact in CI; workflows are validation-only (`ci.yml`);
  packaging tasks are scanned and rejected by the `project-structure` job.
- `.gitignore` excludes `build/`, `.gradle/`, `local.properties`, `*.apk`,
  `*.aab`, `*.jks`, `*.keystore`, `*.p12`, `keystore.properties`.
- Secret scan job (`secret-scan`) flags `sk-*`, `Bearer …`, and 32+ char
  assigned key/secret/token/password values in committed source/config;
  `*.example` files (placeholders) and narrative docs are excluded.
- Release keystores must be created offline and never committed (see
  `docs/DISTRIBUTION_CHECKLIST.md` §D).

## 6. Reporting a vulnerability

- Do **not** open a public GitHub issue for security problems.
- Contact the maintainer privately with: affected module/file, reproduction
  steps (or a minimized test), expected vs observed behavior, and suggested
  severity (confidentiality / integrity / availability).
- Acknowledgment target: 7 days; coordinated disclosure timeline agreed
  privately; fixes land in a tagged commit with CVE-style reference where
  applicable. Out-of-scope reports: user-supplied malicious files, rooted
  devices, and third-party services the operator self-hosts.

## 7. Known limitations (honest list)

1. **DNS TOCTOU:** the backend validates a resolved IP, then httpx reconnects
   and may re-resolve under DNS rebinding; mitigate with egress firewall
   deny rules and pinned hosts (§2 hardening).
2. **In-memory rate limit** resets on backend restart and is per single
   instance; multi-instance deployments need a shared store (e.g. Redis).
3. **Keystore fallback:** if hardware Keystore is unavailable/corrupt on a
   device, the cipher degrades to passthrough so startup never crashes; this
   is a documented fallback, visible in app diagnostics only (backend label).
4. **Instrumented verification pending:** Keystore encrypt/decrypt,
   WorkManager scheduling, FFmpegKit native calls, ExoPlayer playback, and
   Room migrations require a device/emulator; see `docs/TESTING_GUIDE.md` §4.
5. **Native FFmpeg provenance:** the maintained fork is GPL-3.0 and ships
   only `arm64-v8a`/`x86_64`; 32-bit ARM devices cannot load native code and
   fail through the normal error path (`docs/FFMPEG.md` §2).
6. **Backend yt-dlp opt-in:** enabling third-platform downloads is the
   operator's legal responsibility; the code provides no circumvention of
   DRM, login, or access controls.
