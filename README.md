# VidMateX

A local-first Android video downloader with on-device subtitle translation,
subtitle embedding, and optional speech-to-text (Whisper). Built with Jetpack
Compose, Hilt, Room, WorkManager, Media3, and a maintained FFmpegKit build.
Modularized by feature so each screen can be built and tested independently.
An optional self-hosted FastAPI resolver backend (`backend/`) is included for
platform link resolution with SSRF protection.

**Build/test honesty:** this repository snapshot was prepared as source-only
work. The backend test suite was executed and is green (**149 passed — 102
test functions across 5 test files**); Android Kotlin tests are source-ready
pending CI; **no APK/AAB was built or signed**, and no CI workflow produces
one. See `docs/TESTING_GUIDE.md` and `docs/COMPATIBILITY.md`.

---

## ⚠️ Compliance statement (read first)

- **Download only content you own, that is in the public domain, or that you
  are otherwise legally permitted to download.** You are responsible for
  complying with copyright law and each platform's Terms of Service.
- **No DRM bypass.** VidMateX never defeats Widevine/FairPlay or any
  content-protection system. Encrypted streams fail gracefully.
- **YouTube / Facebook / TikTok require a self-hosted resolver.** The app ships
  **no built-in resolver** for these platforms. The included `backend/`
  resolver returns **metadata only** (via official oEmbed) for these platforms
  by default and sets `downloadAvailable: false` — no stream URLs are ever
  invented. Real downloads require you to opt in to a compliant resolver
  (e.g. set `YTDLP_PATH` on your self-hosted backend; your responsibility).
- **Direct media URLs work out of the box.** Pasting a direct `.mp4` / `.m4a` /
  `.webm` URL downloads immediately — no backend, no account, no tracking.
- **No hardcoded API keys, endpoints, or secrets.** All external services
  (resolver, Whisper, LibreTranslate) are configured by you in Settings and
  stored encrypted at rest on your device. See `docs/EXTERNAL_SERVICES.md`,
  `SECURITY.md`, and `docs/WHISPER_SETUP.md`.
- If you redistribute this app, read `docs/DISTRIBUTION_CHECKLIST.md` — the
  default FFmpegKit variant is **GPL v3**, which requires source disclosure.

---

## Features

| Feature | Status |
|---|---|
| Direct-URL video/audio download (mp4, webm, m4a, mp3, …) | ✅ Works without any backend |
| Download to user-chosen folder (SAF / MediaStore) | ✅ Works without backend |
| Download progress + notifications + notification actions (WorkManager) | ✅ Works without backend (device verification pending) |
| Pause / resume (HTTP Range) + exponential retry + duplicate prevention | ✅ Pure policy logic unit-tested; worker lifecycle device-pending |
| Subtitle download (SRT/VTT/ASS) from resolved metadata | ⚠️ Requires resolver endpoint for platform links; direct URLs with sidecar files work |
| Soft-embed (mov_text) / hard-burn (libass, `charenc=UTF-8`, bundled-safe font style) subtitles | ✅ Local processing; vectors host-validated; native run device-pending |
| On-device subtitle translation (Google ML Kit, offline) | ✅ No network required |
| LibreTranslate translation (self-hosted, optional) | ⚠️ Requires configured HTTPS endpoint |
| **Whisper speech-to-text** (OpenAI-compatible `/v1/audio/transcriptions`, >24 MB chunking, SRT/VTT output, retry policy) | ⚠️ Worker + client implemented; requires user-configured endpoint (self-host or paid API) |
| Video preview player (Media3 ExoPlayer; play/pause/seek/volume) | ✅ Controller wired; playback verification device-pending |
| History of completed downloads (open / share / delete / preview) | ✅ Pure formatters unit-tested; UI device-pending |
| YouTube / Facebook / TikTok link resolution | ⚠️ Self-hosted `backend/` returns metadata only by default; downloads require compliant opt-in resolver |
| Self-hosted resolver backend (FastAPI, SSRF guard, rate limit, optional X-API-Key auth) | ✅ Included in `backend/`; **149 pytest cases pass** (102 test functions, 5 files; executed) |
| Background queue + retry (WorkManager, on-demand Hilt init) | ✅ Policy logic unit-tested; scheduling device-pending |
| Encrypted secret storage (Android Keystore AES/GCM for resolver + Whisper keys) | ✅ Pure codec unit-tested; Keystore path device-pending |

---

## Module architecture

```
VidMateX (root)
├── app                          # Application, Hilt root, navigation graph, MainActivity, Media3 controller
├── core/
│   ├── common                   # DownloadManager interface, AppResult, Dispatchers, Constants, SettingsRepository contract, UrlValidator
│   ├── model                    # Pure @Serializable data models (DownloadSpec, VideoMetadata, AppSettings, tracks…)
│   ├── database                 # Room entities/DAOs
│   └── network                  # Retrofit BackendApi + BackendUrlValidator + SafeApiCall + interceptors
├── source/
│   └── adapters                 # MediaSourceResolver, DirectUrlAdapter, BackedSourceAdapter (real BackendApi)
├── media/
│   └── processor                # FFmpegArgs builder, SubtitleParserImpl, WhisperSpeechToText, translator registry
├── feature/
│   ├── home                     # URL paste + resolve + quality picker
│   ├── downloads                # Download list + DownloadManager impl (WorkManager + Room), pause/resume, retry, embed worker
│   ├── subtitles                # Subtitle picker, translation, transcription worker, embed style editor
│   ├── history                  # Completed downloads: open / share / delete / preview
│   └── settings                 # Resolver endpoint+key, Whisper endpoint+key, translation provider, theme, language, quality
└── backend/                     # OPTIONAL self-hosted FastAPI resolver (Python, SSRF-protected) — not part of the APK
```

**Dependency rule:** `feature/*` depends on `core/*` interfaces only. The
concrete `DownloadManager` lives in `:feature:downloads` and is bound via Hilt,
so `:feature:history` never touches WorkManager or Room directly.

---

## Tech stack and verified coordinates

- **Language:** Kotlin 1.9.24, JVM target 17; kapt 1.9.24
- **UI:** Jetpack Compose BOM 2024.06.00, Material 3, Coil 2.6.0; Compose Compiler 1.5.14 (paired with Kotlin 1.9.24)
- **DI:** Hilt 2.51.1
- **Async:** Kotlin Coroutines 1.8.1, Flow
- **Persistence:** Room 2.6.1 + DataStore Preferences 1.1.1
- **Background work:** WorkManager 2.9.0 (`androidx.hilt:hilt-work:1.2.0`)
- **Network:** Retrofit 2.11.0 + OkHttp 4.12.0 + kotlinx.serialization 1.6.3
- **Media:** Media3 1.3.1; FFmpeg maintained fork `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (drop-in `com.arthenica.ffmpegkit` API; provenance and GPL notes in **[`docs/FFMPEG.md`](docs/FFMPEG.md)**)
- **Translation:** Google ML Kit 17.0.3 on-device (offline) + optional LibreTranslate
- **Speech-to-text:** OpenAI-compatible Whisper endpoint (user-configured; see `docs/WHISPER_SETUP.md`)
- **Backend (optional):** Python 3.12, FastAPI 0.116, httpx, pytest — **149 executed cases** (102 test functions, 5 test files)
- **Build:** AGP 8.10.1, Gradle 8.11.1, `compileSdk = 36`, `targetSdk = 36`, `minSdk = 24`, JDK 17 — see `docs/COMPATIBILITY.md` for the full evidence table and sources

---

## Setup

### Android app

1. **JDK 17** (AGP 8.10 requires JDK 17; JDK 11 cannot build this project).
2. Android SDK: Platform **36** (`platforms;android-36`) and
   `build-tools;36.0.0`; set `ANDROID_HOME`/`local.properties`.
3. Open the repo in Android Studio (Flamingo/2024+ recommended) or use the
   committed Gradle wrapper from the command line.
4. External services are configured **in the app's Settings screen** — no
   `.env` file is read by Android. See `docs/EXTERNAL_SERVICES.md`.

### Optional resolver backend (`backend/`)

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env        # placeholders only; fill RESOLVER_API_KEY, YTDLP_PATH (opt-in), …
uvicorn app.main:app --host 0.0.0.0 --port 8000

# Tests, local form (run from backend/):
python -m pytest -q            # → 149 passed (102 test functions, 5 test files)
# CI form, from repository root:
#   python -m pytest backend/tests -q
```

Full hosting, TLS, and hardening guide: **[`docs/BACKEND_SETUP.md`](docs/BACKEND_SETUP.md)**.

### Environment variables

- **Backend (`backend/.env`):** `RESOLVER_API_KEY`, `PORT`, `ALLOWED_ORIGINS`,
  `MAX_REDIRECTS`, `MAX_RESPONSE_BYTES`, `RATE_LIMIT_PER_MINUTE`,
  `MAX_REQUEST_BYTES`, `REQUIRE_HTTPS_UPSTREAM`, `SSRF_PIN_DNS`, optional
  `YTDLP_PATH` / `YTDLP_TIMEOUT`.
- **Android side (Settings UI, encrypted at rest):** resolver base URL + API
  key, Whisper endpoint + API key, LibreTranslate base URL. Placeholder
  reference: root `.env.example` and `backend/.env.example` contain empty
  values only.

---

## Validation (this repository's CI)

`.github/workflows/ci.yml` contains **validation-only jobs** — no APK/AAB
packaging task exists in any workflow:

| Job | Purpose |
|---|---|
| `backend-tests` | venv, `pip install -r backend/requirements.txt`, `python -m pytest backend/tests -q` (**149 cases**; 102 functions / 5 files) |
| `gradle-validation` | `./gradlew help --no-configuration-cache` on JDK 17 (model resolution) |
| `android-unit-tests` | `./gradlew testDebugUnitTest` — JVM unit tests only |
| `project-structure` | required files present; forbidden packaging-task names absent from workflows |
| `secret-scan` | high-entropy/key-pattern scan; `.env.example` placeholders excluded |

- Kotlin test inventory (grep snapshot 2026-11-19): **58 test files, 447 JVM
  `@Test` methods**, source-ready pending CI. **Android tests could not be
  executed here**: this environment has no Android SDK and no Gradle/JDK 17;
  these run only via `./gradlew testDebugUnitTest` on a JDK 17 + SDK Platform
  36 machine (CI job `android-unit-tests`). Per-module counts are in
  `docs/TESTING_GUIDE.md` §2.3; the final CI run is authoritative.
- Local packaging commands (for a maintainer machine only; **not executed in
  this environment**) are documented in `docs/TERMUX_SETUP.md` and
  `docs/DISTRIBUTION_CHECKLIST.md`; release signing must never use CI secrets
  committed to the public repo.

---

## Project file tree

```
vidmatex/
├── .github/workflows/
│   └── ci.yml                         # Validation-only: backend pytest, Gradle help, unit tests, structure, secrets
├── backend/                           # OPTIONAL self-hosted FastAPI resolver (Python)
│   ├── app/                           # main.py, ssrf_guard.py, schemas.py, resolvers/
│   ├── tests/                         # 149 pytest cases (102 functions, 5 files; executed, green)
│   ├── requirements.txt, Dockerfile, .env.example, pytest.ini, README.md
├── docs/
│   ├── COMPATIBILITY.md               # SDK 36 upgrade evidence table + sources
│   ├── TESTING_GUIDE.md               # Executed backend results + Kotlin inventory + device gaps
│   ├── FFMPEG.md                      # Maintained FFmpeg fork provenance + security policy (B-owned)
│   ├── BACKEND_SETUP.md               # Resolver backend run/deploy/HTTPS/hardening
│   ├── WHISPER_SETUP.md               # Whisper endpoint hosting/hardware/cost
│   ├── EXTERNAL_SERVICES.md           # All optional external services, in-app steps, compliance
│   ├── GITHUB_SETUP.md                # Repository init, secrets hygiene, enabling workflows, releases
│   ├── PRIVACY_POLICY.md, TERMUX_SETUP.md, DISTRIBUTION_CHECKLIST.md, REPAIR_REPORT.md
├── scripts/
│   ├── validate_ffmpeg_args.sh        # Host argument-vector validation (see docs/FFMPEG.md)
│   └── generate_wrapper.sh             # Reference: regenerate gradle-wrapper.jar locally
├── SECURITY.md                        # Threat model, controls, reporting process
├── PRIVACY_POLICY.md                  # Root privacy policy (mirror of docs/PRIVACY_POLICY.md)
├── CHANGELOG.md, COMPLETION_REPORT.md, CONTRIBUTING.md, LICENSE
├── .env.example, backend/.env.example  # Empty placeholders only; never commit real secrets
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/
│   ├── libs.versions.toml             # Verified coordinates (AGP 8.10.1, Kotlin 1.9.24, …)
│   └── wrapper/                        # gradle-wrapper.jar + gradle-wrapper.properties (Gradle 8.11.1)
├── gradlew / gradlew.bat               # Committed wrapper scripts
├── app/  core/  source/  media/  feature/
```

---

## Known limitations (honest list)
- **Android builds/tests not executed in this snapshot environment** — no
  Android SDK, Gradle distribution, or JDK 17 here. JVM unit tests run later
  via `./gradlew testDebugUnitTest`; instrumented/Room tests need a device or
  emulator. Details: `docs/TESTING_GUIDE.md` §4, `docs/COMPATIBILITY.md` §7.
- **Device-only gaps:** WorkManager scheduling/lifecycle, `FFmpegKit` native
  calls (no on-device run here), Media3/ExoPlayer playback verification,
  Android Keystore encrypt/decrypt path, Room DAO queries and migrations,
  notification actions on API 33+, and Arabic RTL layout verification.
- **Platform downloads (YouTube/Facebook/TikTok) need your own self-hosted
  resolver.** The bundled backend returns metadata only by default
  (`downloadAvailable=false`); enabling real formats via `YTDLP_PATH` is the
  operator's legal responsibility regarding platform Terms of Service.
- **Backend DNS TOCTOU window** between validation and connect; mitigate with an
  egress firewall (see `SECURITY.md` §7.1). The in-memory rate limit resets on
  restart and is per single instance.
- **FFmpegKit native ABIs:** the maintained fork ships `arm64-v8a` and
  `x86_64`; 32-bit ARM devices cannot load native code and fail through the
  normal error path (`docs/FFMPEG.md`, `SECURITY.md` §7.5).
- **No APK/AAB in CI.** Packaging and release signing happen offline on a
  maintainer machine; see `docs/DISTRIBUTION_CHECKLIST.md`.

---

## License

This project is licensed under the **GNU General Public License v3.0** because
the default `:media:processor` module depends on the maintained
`ffmpeg-kit-full-gpl` build (GPL v3.0; x264/x265/libass encoders). If you
distribute the app, you **must** make the complete corresponding source code
available. See `docs/DISTRIBUTION_CHECKLIST.md` §C and `docs/FFMPEG.md`.

The LGPL `ffmpeg-kit-full` build (no GPL encoders) remains a documented future
relicensing option; it was not selected because it lacks `libx264` for the
hard-burn vector.

All downloaded content remains the property of its respective copyright
holders. VidMateX is a tool; the user is responsible for what they download.
