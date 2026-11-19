# Changelog — VidMateX

---

## 2026-11-19 — SDK 36 compatibility, validation-only CI, test inventory, documentation reconciliation

**Source/static round. No Gradle execution, no APK/AAB output.** Backend suite
was executed: **149 pytest cases pass** (`python -m pytest backend/tests -q`;
102 test functions across 5 test files). Kotlin tests remain source-ready
pending CI (no Android SDK/Gradle/JDK 17 in this environment).

### Compatibility (`docs/COMPATIBILITY.md` — evidence table with sources)

- Bumped AGP `8.5.2` → **`8.10.1`** (first AGP line whose release notes state
  maximum API level 36; requires Gradle 8.11.1 and JDK 17).
- Gradle wrapper `8.7` → **`8.11.1`** (`gradle/wrapper/gradle-wrapper.properties`;
  wrapper JAR untouched, not executed).
- `compileSdk 34 → 36` in the app and all ten library modules; app
  `targetSdk 34 → 36`; `minSdk = 24` unchanged.
- Kept **Kotlin 1.9.24 + Compose Compiler 1.5.14** (official Compose↔Kotlin
  pairing; a Kotlin 2.x bump would force the Compose Gradle plugin swap and
  re-verification — documented fallback chain in `docs/COMPATIBILITY.md` §3).
- Verified coordinates on Maven Central / Google Maven: Room 2.6.1, Media3
  1.3.1, WorkManager 2.9.0, Hilt 2.51.1, Retrofit 2.11.0, OkHttp 4.12.0,
  ML Kit 17.0.3, DataStore 1.1.1, Navigation 2.7.7, Coil 2.6.0; FFmpeg fork
  `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (POM/AAR verified).
- No `androidx.security:security-crypto` coordinate added — secrets use the
  manual Android Keystore AES/GCM implementation.
- Catalog cross-check script: every `libs.*` accessor in `*.kts` resolves to a
  declared alias; ProGuard keeps for `com.arthenica.ffmpegkit.**` and
  `com.arthenica.smartexception.**` retained (fork keeps the old package name).

### CI — validation-only (`.github/workflows/ci.yml`; `build.yml` removed)

- New jobs: `backend-tests` (venv, pinned requirements, pytest),
  `gradle-validation` (`./gradlew help --no-configuration-cache`, JDK 17),
  `android-unit-tests` (`./gradlew testDebugUnitTest` only),
  `project-structure` (required paths + forbidden-task-name scan),
  `secret-scan` (high-entropy/key patterns; `.example` placeholders excluded).
- Removed the APK/AAB-building workflow entirely; no workflow contains a
  packaging task name; no error-continuation flag anywhere; `if: always()`
  summary steps publish per-job counts.

### Tests

- Backend: **149 passed** — `test_direct_url` 20 functions/35 cases,
  `test_endpoints` 23/23, `test_hardening` 26/26, `test_platform` 10/18,
  `test_ssrf_guard` 23/47 (102 functions total, 5 files) — recorded in
  `docs/TESTING_GUIDE.md`.
- Kotlin inventory (grep snapshot 2026-11-19): **58 test files / 447 JVM
  `@Test` methods**, mapped to the required class list (UrlValidator, Resolver,
  Backend API, DownloadManager, DownloadWorker, SubtitleParser, Subtitle
  Translation, WhisperSpeechToText, SettingsRepository, FFmpegArgs,
  FFmpegProcessor, EmbedWorker, Media3PlayerController, FileStorageHelper,
  Room Database, WorkManager); counts are source-level and must be confirmed by
  the CI `android-unit-tests` run.
- Added pure-JVM tests this round: `FormatUtilsTest` (4),
  `DownloadPoliciesExtraTest` (8); device-only gaps (WorkManager lifecycle,
  FFmpegKit JNI, ExoPlayer, Keystore, Room DAO/migrations) documented in
  `docs/TESTING_GUIDE.md` §4.

### Documentation and secrets

- Added/rewritten: `docs/COMPATIBILITY.md`, `SECURITY.md`, `PRIVACY_POLICY.md`,
  `docs/GITHUB_SETUP.md`, refreshed `README.md`, `COMPLETION_REPORT.md`,
  `docs/TESTING_GUIDE.md`, `docs/EXTERNAL_SERVICES.md`, `docs/BACKEND_SETUP.md`,
  `docs/WHISPER_SETUP.md`, `CONTRIBUTING.md`, `docs/DISTRIBUTION_CHECKLIST.md`,
  `REPAIR_REPORT.md` addendum.
- Root and backend `.env.example`: placeholder-only values; added documented
  placeholders for `WHISPER_ENDPOINT`, `WHISPER_API_KEY`, `BACKEND_BASE_URL`,
  `YTDLP_PATH`; grep-verified no real secrets.
- Whisper worker, Keystore encryption, resolver configuration, download
  lifecycle, subtitle flow, and backend hardening described with honest
  device/CI gaps; external-service configuration steps in
  `docs/EXTERNAL_SERVICES.md` and `docs/WHISPER_SETUP.md`.

---

# Changelog — VidMateX source repair (this release)

All changes are **source-only**. No APK was built, signed, or packaged.
Versions were **not** bumped (AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 kept)
because they are mutually compatible.

## Build & repository infrastructure
- **Added** committed Gradle wrapper: `gradlew`, `gradlew.bat`,
  `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.7). The original ZIP shipped
  only `gradle-wrapper.properties` and relied on CI to regenerate the wrapper.
- `.gitignore`: removed the rules that ignored `gradlew` / `gradlew.bat` /
  `gradle-wrapper.jar` (they are now committed); added explicit `*.apk`,
  `*.aab`, `*.ap_`.
- `gradle.properties`: raised heap to `-Xmx4096m`, global
  `android.defaults.buildfeatures.buildconfig=false`.
- **Added** `local.properties.example`.
- **Added** `LICENSE` (GPL-3.0, required by `ffmpeg-kit-full-gpl`).
- **Added** `CONTRIBUTING.md` (compliance + architecture rules).
- **Added** `GITHUB_SETUP.md` (Termux upload + GitHub Actions build guide).
- **Added** `.github/workflows/ci.yml` (push/PR: unit tests + lint, no APK).
- **Rewrote** `.github/workflows/build.yml` (manual / `v*` tag only; debug APK
  always; signed release APK only when `ANDROID_*` secrets present; wrapper no
  longer regenerated in CI; keystore scrubbed at end).
- `README.md`: updated for committed wrapper, two-workflow split, new docs.

## Core (`core/*`)
- `core/common/build.gradle.kts`: **added missing `api(project(":core:model"))`**
  (UrlValidator / SettingsRepository / DownloadManager all import core.model
  symbols — would have failed compilation); added Hilt plugin + kapt.
- `core/common/Dispatchers.kt`: **added missing Hilt `@Binds`**
  `DispatcherProvider -> DefaultDispatcherProvider` (6+ injection sites would
  have failed at compile time).
- `core/common/UrlValidator.kt`: hardened — 2048-char cap, explicit
  `BLOCKED_SCHEMES` (javascript/file/data/content/ftp/sms/tel/intent),
  userInfo rejected (credential-leak prevention), strict scheme check.
- `core/common/Constants.kt`: added empty placeholder base URLs / API key (no
  real secrets), `MAX_URL_LENGTH`, `ALLOWED_SCHEMES`, FFmpeg defaults.
- `core/network/NetworkModule.kt`: removed `HttpsOnlyInterceptor` (it broke
  permitted `http` direct-URL downloads; backend HTTPS is enforced at the
  Retrofit base URL); added OkHttp logging interceptor at level `NONE`
  (constant-controlled, since `buildConfig=false`).
- `core/model/Models.kt`: added `MediaTrack.resumable` (Accept-Ranges),
  `EmbedStyle.fontName` (Arabic glyph font), `AppSettings.whisperEndpoint` +
  `whisperApiKey` (user-provided, stored locally — never hardcoded).

## Media processing (`media/processor`)
- `FFmpegArgs.kt`: `subtitles=` filter now includes `FontName=Sans` and
  `:charenc=UTF-8` (was missing → Arabic mojibake on burn-in).
- `MediaContracts.kt`: added `SpeechToText` interface (Whisper integration
  point); added `SubtitleParser.shift()` and `detectLanguage()`.
- `SubtitleParserImpl.kt`: implemented `shift` (coerced ≥0 ms) and
  `detectLanguage` (Arabic Unicode-block heuristic; explicit source metadata
  always preferred). Existing `SubtitleParserImplTest` API preserved.
- **Added** `SpeechToTextImpls.kt`: `WhisperSpeechToText` (optional API-based
  stub, configurable endpoint/key from settings, no hardcoded creds) +
  `NoOpSpeechToText` (default). `MediaModule` binds `SpeechToText` → `NoOp`.
- **Added** unit test `SubtitleShiftAndLanguageTest.kt` (shift + language
  detection).

## Source adapters (`source/adapters`)
- Verified clean, no edits: `DirectUrlAdapter` (HEAD, no auth, resume via
  Accept-Ranges), `BackedSourceAdapter` (pluggable user-controlled backend —
  no invented platform APIs, no DRM bypass), `MediaSourceResolver` (URL
  validation first, ordered match), `SourceModule` (Hilt).

## App shell (`app`)
- **Critical** `VidMateXApplication.kt`: **added `Configuration.Provider` +
  injected `HiltWorkerFactory`** (was missing → `@HiltWorker`
  DownloadWorker/EmbedWorker would crash at runtime; manifest already removed
  the default `WorkManagerInitializer`).
- `MainActivity.kt`: added `POST_NOTIFICATIONS` runtime permission request
  (API 33+); theme call renamed to `VidMateXTheme`.
- `ui/theme/Theme.kt`: renamed `ThemeVidMateX` → `VidMateXTheme`; added
  optional `dynamicColor` (Material You, API 31+); pure Material3.
- `app/build.gradle.kts`: wired `media3-exoplayer` / `media3-ui` /
  `media3-common` (were declared in the catalog but unused); added
  `com.google.android.material:material` (needed for
  `Theme.Material3.DayNight.NoActionBar`).
- `res/values/themes.xml`: `PostSplash` parent changed from platform
  `android:Theme.Material.NoActionBar` (dark-only) to
  `Theme.Material3.DayNight.NoActionBar`.
- `AndroidManifest.xml`: added `android:networkSecurityConfig`.
- **Added** `res/xml/network_security_config.xml` (cleartext denied;
  debug-only user-cert trust override).
- **Added** `app.vidmatex.player.Media3PlayerController` (self-contained
  ExoPlayer wrapper — Media3 is now genuinely consumed; preview-player
  integration point).

## Feature modules (`feature/*`)
- `feature/downloads`: `BackoffPolicy.LINEAR → EXPONENTIAL`; both workers'
  `fail()` now pass the real entity title (was `""`); **created missing
  `res/values/strings.xml` + `res/values-ar/strings.xml`** (37 keys each —
  module previously had zero string resources, all UI text hardcoded);
  converted `DownloadsScreen`, `DownloadFilter`, `NotificationHelper` to
  string resources; removed unused import.
- `feature/history`: **had no `res/` at all** — created `values/` +
  `values-ar/strings.xml` (19 keys each); converted `HistoryScreen` + toast
  messages to resources; `groupByDate` now takes `Context` for localized
  Today/Yesterday.
- `feature/subtitles`: hardcoded "auto-generated" → string resource; preview
  cue `Text` gets `textDirection = TextDirection.Content` (Arabic RTL);
  `sanitizeFileName` regex fixed (was stripping all non-ASCII, breaking Arabic
  filenames); strings completed in both locales.
- `feature/home`: ViewModel snackbar/error messages → `context.getString`;
  added missing `home_error_no_selection` key (en + ar).
- `feature/settings`: verified clean — `SettingsRepositoryModule` has
  `@InstallIn`; all 44 string keys present in both locales.

## Verified intact (no change needed)
- All 12 modules in `settings.gradle.kts`; all `libs.*` catalog aliases resolve.
- `DownloadManager` / `SettingsRepository` interfaces match their impls exactly.
- Room entities/DAO/converters consistent; `AppDatabase` provided via Hilt in
  `feature/downloads` (works; not duplicated).
- `@HiltWorker` / `@AssistedInject` pattern correct; `PendingIntent.FLAG_IMMUTABLE`;
  receiver `exported=false`; FileProvider + `@xml/file_paths` present.
- All 5 screen composable signatures match `VidMateXNavHost` calls.
- No `TODO()` / `NotImplementedError`; no hardcoded secrets; no APK/keystore.

---

# VidMateX_COMPLETE (this release)
All changes are **source-only**. No Android APK was built, signed, or
packaged (this environment has no Android SDK / JDK 17). The Python backend
test suite **was executed**: 68 pytest tests pass. Android unit tests were
**written but not executed** (no Android SDK in this environment); they will
run in CI (`ci.yml`) on first push to GitHub.

## Backend (`backend/`) — FastAPI resolver (Agent A) — TESTED (68 pytest pass)
- **Added** `backend/` (20 files): FastAPI app with `GET /health`,
  `POST /extract`, `POST /subtitle` matching `core/network/BackendApi.kt`
  exactly (frozen contract, CONTRACTS §2).
- **SSRF guard** (`app/ssrf_guard.py`): DNS resolve, reject
  private/loopback/link-local/multicast/reserved/unspecified IPs, manual
  redirect follow (max 5, per-hop re-validation), response size cap (25 MB),
  request body cap (64 KB), non-http(s) scheme rejection.
- **DirectUrlResolver**: HEAD+probe, `Accept-Ranges` → `resumable`, sidecar
  subtitle detection (`.srt`/`.vtt`/`.ass` next to media).
- **Platform resolvers** (YouTube/Facebook/TikTok): metadata-only via official
  oEmbed, `downloadAvailable=false`, no stream URLs invented. Optional opt-in
  `YTDLP_PATH` wrapper (disabled by default, subprocess exec, no shell,
  timeout, SSRF-validated input).
- **Optional X-API-Key auth** (env `RESOLVER_API_KEY`, empty = disabled).
- **In-memory per-IP rate limit** (sliding window, JSON 429 envelope).
- **Added** `requirements.txt`, `.env.example`, `Dockerfile`, `pytest.ini`,
  `backend/README.md` (run/deploy/HTTPS/curl examples).
- **Tests**: 5 test files (`test_direct_url`, `test_endpoints`,
  `test_platform`, `test_ssrf_guard`, `conftest`) — **68 tests RAN and PASSED**.
- **Known limitations** (honest): TOCTOU DNS window, in-memory rate limit,
  direct-URL `heightPx`/`fps` null, sidecar language `"und"`, yt-dlp branch
  not executed in CI.

## Android core (`core/*`, `source/adapters`, Whisper) — Agent B — CODE COMPLETE, NOT RUN
- **`DownloadSpec`** (`core/model/Models.kt`): added 6 `@Serializable` fields
  (frozen, CONTRACTS §1): `subtitleContentOriginal`,
  `subtitleContentTranslated`, `useTranslatedSubtitle`, `subtitleShiftMs`,
  `saveOriginalSubtitleSidecar`, `translatedLanguageCode`. Added
  `resolverApiKey` to `AppSettings`. Added `downloadAvailable` + `message`
  to `ExtractResponse`.
- **Network** (`core/network`): `ApiSafety.kt` (`BackendException` sealed,
  `safeApiCall`), `ApiKeyInterceptor`, `backendApi(baseUrl, apiKey)` overload.
- **`UrlValidator.isLikelyPrivateHost`** (`core/common`).
- **`BackedSourceAdapter`** wired to real `BackendApi` with `safeApiCall`.
- **`DirectUrlAdapter`** sets `resumable` from `Accept-Ranges`.
- **`WhisperSpeechToText`** real impl (`media/processor/SpeechToTextImpls.kt`):
  OpenAI-compatible `/v1/audio/transcriptions` Multipart, SRT parse, >24 MB
  chunking via FFmpegKit, `onProgress` property, apiKey never logged.
  `SettingsAwareSpeechToText` delegating singleton bound in `MediaModule`
  (NoOp fallback when unconfigured).
- **Tests added (6 files, NOT executed)**: `UrlValidatorTest`,
  `DownloadSpecSerializationTest`, `SafeApiCallTest`,
  `BackendApiSerializationTest`, `DirectUrlAdapterLogicTest`,
  `WhisperSrtParserTest`.

## Android features (`feature/*`, `app/*`, FFmpeg) — Agent C — CODE COMPLETE, NOT RUN
- **CRITICAL translatedCues flow FIXED** (CONTRACTS §5):
  `SubtitlesViewModel.download()` applies `subtitleShiftMs` and serializes
  original + translated subtitle content into `DownloadSpec`;
  `DownloadWorker.resolveSubtitleText()` consumes serialized content (no
  re-fetch), writes chosen (translated if `useTranslatedSubtitle`) UTF-8 to
  `$id.sub.tmp` (EmbedWorker reads `entity.subtitlePath`), original sidecar
  saved when translation used; `EmbedWorker` uses `translatedLanguageCode`
  for soft-embed metadata.
- **Download system**: duplicate prevention, EXPONENTIAL retry, pause/resume
  (HTTP Range), notification actions, `cleanTempFiles` only `*.tmp`.
- **FFmpeg**: soft-embed (`mov_text`) + hard-burn (`charenc=UTF-8`,
  `FontName` from style, `crf 18 preset medium`), mux/extractAudio via
  `executeWithArguments(List<String>)`, `progressPercent` property (coarse).
- **VideoPreviewScreen** (Media3 ExoPlayer + PlayerView, play/pause/seek/
  volume, release on dispose) wired into nav from Downloads + History Preview
  buttons.
- **Settings**: Whisper endpoint + API key persisted
  (`PasswordVisualTransformation`). Click debounce in Home.
- **Tests added (4 files, NOT executed)**: `FFmpegArgsTest`,
  `SubtitleParserExtendedTest`, `DownloadWorkerHelpersTest`,
  `FileStorageHelperTest`.
- **Strings**: 30+ new string keys in both `en` + `ar` locales.
- **Known gaps** (honest): resolver API key Settings UI field not added (only
  endpoint; `resolverApiKey` model field exists), Whisper transcription worker
  deferred (UI + settings + impl done, no WorkManager worker to invoke on
  downloaded audio), FFmpeg progress coarse, Arabic hard-burn needs a device
  font with Arabic glyphs.

## CI & repository (Agent D — this integration)
- **`.github/workflows/ci.yml`**: removed forbidden `continue-on-error: true`
  on lint; added `backend-test` job (Python 3.12, `pip install -r
  backend/requirements.txt`, `python -m pytest backend/tests -q`). Three jobs:
  `unit-test` (`testDebugUnitTest`), `lint` (`lintDebug`), `backend-test`
  (pytest). No assemble/bundle. `build.yml` unchanged (manual/tag APK).
- **Root `.env.example`** created (references backend vars; notes Android uses
  Settings UI).
- **Docs added/updated**: `README.md`, `CHANGELOG.md` (this section),
  `docs/BACKEND_SETUP.md`, `docs/WHISPER_SETUP.md`, `docs/TESTING_GUIDE.md`,
  `docs/EXTERNAL_SERVICES.md`, `docs/PRIVACY_POLICY.md` (finalized),
  `GITHUB_SETUP.md` (extended), `docs/TERMUX_SETUP.md` (minor correction).
- **`COMPLETION_REPORT.md`** (Arabic): honest status table + 8 percentages.
- **Cleaned**: all `__pycache__` / `.pytest_cache` removed before packaging.
  No `build/` dirs, no `*.apk`/`*.aab`/`*.jks`/`local.properties` shipped.
