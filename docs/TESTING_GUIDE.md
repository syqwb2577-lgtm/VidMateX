# Testing Guide — VidMateX

**Honesty boundary (read first):**

* The **backend Python suite was executed** in this environment; results are in §1 and are green.
* **No Gradle command and no Android SDK/JDK 17 build was executed here.** All Kotlin unit tests below are **source-ready pending CI**; none of them are claimed to have passed. The host here has JDK 11 while the project targets JVM 17 and requires Android SDK Platform 36.
* **No APK or AAB was built, signed, or packaged** in this round, and no workflow in this repository emits APK/AAB artifacts (see §5).
* Host FFmpeg argument-vector validation (§3) ran on a **desktop ffmpeg 4.4.2** binary; it validates vector correctness, not the Android native fork.

---

## 1. Backend Python suite — EXECUTED (green)

**Commands:**

```bash
# Local form (run from the backend package):
cd backend && python -m pytest -q

# CI form (run from repository root; the command used in ci.yml):
python -m pytest backend/tests -q
```
Setup once: `python3 -m venv .venv` (Python 3.12), activate it,
`pip install -r backend/requirements.txt`; `pytest.ini` uses
`asyncio_mode = auto`.

**Result (executed final state, 2026-11-19):** **`149 passed in 0.39s`** —
**102 `def test_` functions collected into 149 parametrized cases across
5 test files.** Dependencies pinned in `backend/requirements.txt`.

| Test module (`backend/tests/`) | Test functions | Collected cases | Covered contract area |
|---|---:|---:|---|
| `test_direct_url.py` | 20 | 35 | Direct-URL resolver: headers, range/resume metadata, container/kind sniffing, subtitle sidecar, security normalization |
| `test_endpoints.py` | 23 | 23 | FastAPI routes `/health`, `/extract`, `/subtitle`; error envelope codes; auth header; size caps; CORS methods |
| `test_hardening.py` | 26 | 26 | Auth envelope (missing/wrong/valid key, 401), 429 retry-after, timeout/upstream-error mapping, redirect-to-CGNAT block, HTTPS-upstream policy, oversized-body abort, scheme rejection, UA/userinfo redaction, schema strictness and example payloads |
| `test_platform.py` | 10 | 18 | Platform resolvers: metadata-only default, `downloadAvailable=false`, opt-in `YTDLP_PATH` contract, oEmbed-shape responses |
| `test_ssrf_guard.py` | 23 | 47 | SSRF guard: private/loopback/link-local/CGNAT/TEST-NET/doc-IP blocks, redirect re-validation, scheme enforcement, timeouts, byte caps |
| **Total** | **102** | **149** | 5 test files |

Coverage map to backend behavior: URL allow-list (private IPs, IPv6 ULA/link-local, doc ranges), redirect chains re-validated hop by hop, response body cap (25 MB default), request body cap (64 KB), per-IP rate-limit middleware shape, optional `X-API-Key` auth, and the frozen error envelope `{"error":{"code":…,"message":…}}`.

### CI execution

The `backend-tests` job in `.github/workflows/ci.yml` recreates this on every push/PR: Python 3.12, venv, `pip install -r backend/requirements.txt`, then `python -m pytest backend/tests -q`. The job fails on any non-zero exit; there is no error-continuation flag.

---

## 2. Android Kotlin unit tests — SOURCE-READY PENDING CI (not executed here)

**Totals (grep snapshot 2026-11-19, read-only):** **58 Kotlin test files, 447
`@Test` methods** across the modules below. JVM-only (`src/test`); no
Android-framework types in these tests. They run under
`./gradlew testDebugUnitTest` on JDK 17 (CI job `android-unit-tests` in
`ci.yml`). These counts are source-level grep results; **no Kotlin test was
executed in this environment** (no Android SDK / Gradle / JDK 17), and the
CI run remains authoritative. Per-module totals are in §2.3.

### 2.1 Inventory mapped to the required component list (request §12)

| # | Required component | Test file(s) | Cases | Pure seam exercised | Status |
|---|---|---|---:|---|---|
| 1 | **UrlValidator** | `core/common/.../UrlValidatorTest.kt` | 32 | private-IP/IPv6/doc-range detection, scheme block-list, normalize/canonicalize, `detectSource`, oversized/empty rejection | source-ready pending CI |
| 2 | **Resolver** (adapters) | `source/adapters/.../DirectUrlAdapterLogicTest.kt`; `core/network/.../BackendUrlValidatorTest.kt` | 11 + 9 | extension/container sniffing, content-type kind detection, HTTPS-only base-URL normalization | source-ready pending CI |
| 3 | **Backend API** | `core/network/.../BackendApiSerializationTest.kt`, `HealthResponseTest.kt`, `SafeApiCallTest.kt` | 4 + 4 + 12 | Retrofit DTO defaults/back-compat JSON, health payloads, exception→`AppResult` mapping | source-ready pending CI; backend API itself executed via §1 |
| 4 | **DownloadManager** | `feature/downloads/.../DownloadWorkerHelpersTest.kt`, `DownloadPoliciesExtraTest.kt` | 29 + 8 | resume decisions, retry classification, kind/dedup policy, display formatters; `DownloadManagerImpl` (WorkManager/Room) = device gap | source-ready pending CI (pure policies); impl = device |
| 5 | **DownloadWorker** | same two files | — | `ResumePolicy.decide`, content-range parse, file-name sanitize, retry kinds; worker lifecycle/foreground service = device gap | source-ready pending CI; lifecycle = device |
| 6 | **SubtitleParser** + standalone subtitle export | `media/processor/.../SubtitleParserImplTest.kt`, `SubtitleParserExtendedTest.kt`, `SubtitleFormatConversionTest.kt`, `SubtitleShiftAndLanguageTest.kt`, `SubtitleShiftIndexTest.kt`; `feature/subtitles/.../StandaloneSubtitleSpecBuilderTest.kt`, `SubtitlesRetryPlannerTest.kt` | 7 + 9 + 13 + 5 + 5 + 8 + 7 | SRT/VTT/ASS parse + round-trip, UTF-8/BOM/CRLF, shift clamp, language heuristic, conversions; standalone-transcription spec builder (title synthesis, `local://` source shape); retry-target planner | source-ready pending CI |
| 7 | **Subtitle Translation** | `media/processor/.../CueTranslationMapperTest.kt`, `TranslatorRegistryAndFakeTest.kt` | 10 + 7 | per-cue line replacement preserving index/timestamps, provider registry/no-op/unconfigured states, fake translator contract | source-ready pending CI |
| 8 | **WhisperSpeechToText** | `media/processor/.../WhisperSrtParserTest.kt`; `feature/subtitles/.../WhisperEndpointValidatorTest.kt`, `TranscriptionRetryPolicyTest.kt`, `TranscriptionPathsTest.kt`, `SourceLanguagesTest.kt` | 5 + 4 + 8 + 6 + 5 | SRT parser tolerances, endpoint validation (HTTPS/loopback dev), transient/permanent retry kinds, output path sanitizer, language dropdown mapping | source-ready pending CI; multipart upload/worker = device gap |
| 9 | **SettingsRepository** | `feature/settings/.../SettingsMapperTest.kt`, `security/SecretCodecsTest.kt`, `WhisperProbeUrlValidatorTest.kt` | 9 + 7 + 5 | snapshot→`AppSettings` mapping, enum fallbacks, quality-height parse, token wrap/unwrap/redact, Whisper probe-URL rule (HTTPS; loopback dev HTTP); DataStore/Keystore impl = device gap | source-ready pending CI; repository impl = device |
| 10 | **FFmpegArgs** | `media/processor/.../FFmpegArgsTest.kt` | 23 | audio extract, soft/hard burn vectors, mux, codec/flag allow-lists, filter-path escaping, no shell metacharacters | source-ready pending CI |
| 11 | **FFmpegProcessor** | covered via `FFmpegArgsTest.kt` vectors + §3 host validation | (23 vector cases) | argument vectors verified on host; `FFmpegProcessorImpl` JNI/`FFmpegKit` calls = device gap | vectors source-ready + host-validated; native processor = device |
| 12 | **EmbedWorker** | retry policy cases in `DownloadWorkerHelpersTest.kt` + `DownloadPoliciesExtraTest.kt` | (policy cases) | `RetryPolicy` classify/`embedRetryExhausted` cap; worker mode selection/FFmpeg session = device gap | policy source-ready; worker = device |
| 13 | **Media3PlayerController** | — (none) | 0 | No Android-free seam: controller binds `ExoPlayer`; state observation = device gap | device-only gap (documented) |
| 14 | **FileStorageHelper** | `feature/downloads/.../FileStorageHelperTest.kt` | 11 | extension/MIME maps for media/audio/subtitles, cleanup policy for stream temps vs partials | source-ready pending CI |
| 15 | **Room Database** | `core/database/.../DownloadEntityDefaultsTest.kt` | 3 | entity additive-column defaults/copy; DAO queries, migrations, `@Database` consistency = instrumented gap | entity defaults source-ready; DAO/migrations = device |
| 16 | **WorkManager** | policy/retry/path tests listed above | (pure helpers) | backoff/retry policy and path builders only; enqueue constraints, worker lifecycle, `setProgress` = device gap | policies source-ready; scheduling = device |

### 2.2 Other pure-model tests (supporting)

| File | Cases | Focus |
|---|---:|---|
| `core/model/.../DownloadSpecSerializationTest.kt` | 7 | Kotlinx-serialization back-compat (old JSON defaults, `audioOnly`/`AUDIO_ONLY` stability) |
| `feature/history/.../HistoryFormatTest.kt` | 11 | bytes/speed/ETA formatters, progress percent clamping, state badge keys |
| `feature/downloads/.../FormatUtilsTest.kt` (added this round) | 4 | speed/ETA/bytes display tiers |
| `feature/downloads/.../DownloadPoliciesExtraTest.kt` (added this round) | 8 | kind label edges, dedup guard key, 2xx-probe branch, permanent-failure classifications |
| `feature/settings/.../WhisperProbeUrlValidatorTest.kt` | 5 | Settings probe-URL rule: HTTPS accepted; loopback dev HTTP accepted; public HTTP/schemes rejected |
| `feature/subtitles/.../StandaloneSubtitleSpecBuilderTest.kt` | 8 | standalone transcription spec: title synthesis, `local://` source URL, DIRECT_URL metadata shape |
| `feature/subtitles/.../SubtitlesRetryPlannerTest.kt` | 7 | RetryTarget mapping (refetch track / download / resolve / none) incl. standalone-cue cases |
| Additional policy/hardening tests (RedirectSafety, BodyLimitPolicy, IntegrityPolicy, SpeedEstimator, InputValidator, FileNamePolicy, FormatSelectionPolicy, SecretTextRedactor, FilenameSanitizer, FFmpegJobModel, SubtitleHardening, WhisperChunkVector, chunk planner/dedup/importer/state mapper/request builder/response parser, export assembler, completed-audio hook) | see §2.3 | Network redirect/body policy, download integrity/speed/input/filename rules, secret redaction, FFmpeg job model and chunk vectors, transcription pipeline state and I/O policies | source-ready pending CI |

### 2.3 Module totals (JVM `@Test` methods; grep snapshot 2026-11-19)

| Module | Test files | `@Test` methods |
|---|---:|---:|
| `core/common` | 3 | 44 |
| `core/database` | 5 | 19 |
| `core/model` | 2 | 15 |
| `core/network` | 6 | 41 |
| `feature/downloads` | 9 | 85 |
| `feature/history` | 1 | 11 |
| `feature/settings` | 3 | 21 |
| `feature/subtitles` | 15 | 76 |
| `media/processor` | 12 | 120 |
| `source/adapters` | 2 | 15 |
| **Total** | **58** | **447** |

(`feature/home` ships no JVM test file; its state mapping is covered by
downloads/settings tests plus device verification.)

### 2.4 How Kotlin tests run in CI / locally

```bash
# Requires JDK 17 and Android SDK Platform 36 + build-tools 36.0.0
./gradlew testDebugUnitTest          # JVM unit tests only (no APK/AAB)
./gradlew help --no-configuration-cache   # model-resolution validation (ci.yml gradle-validation)
```

`androidTest` and Room migration tests are **not** run here and not part of `testDebugUnitTest`; they require an emulator/device (§4).

---

## 3. Host FFmpeg argument-vector validation (shard B evidence)

- Script: `scripts/validate_ffmpeg_args.sh`; output: `docs/ffmpeg_validation_output.txt`; provenance/security policy: `docs/FFMPEG.md`.
- **Result recorded in that file: OVERALL: ALL SCENARIOS PASS on host ffmpeg 4.4.2.** Six scenario families:
  1. extract audio to MP3 (`libmp3lame 192k`, `-vn`);
  2. soft subtitle embed (`mov_text`, stream copy, `+faststart`, language metadata);
  3. hard burn SRT (libass, `charenc=UTF-8`, `libx264 -crf 18 -preset medium`);
  4. hard burn ASS direct input, plus VTT→SRT-converted SRT burn;
  5. mux video+audio (`-map 0:v -map 1:a`, `-c copy`, `-shortest`);
  6. Whisper chunk split (16 kHz mono 64 kbps MP3, 600 s segments).
- **Scope caveat:** vectors execute against the desktop `ffmpeg` binary to prove argument construction, allow-listing, and filter escaping. The Android native AAR (`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`) was **not executed** in this environment; on-device FFmpegKit runs are device/CI manual verification items.

---

## 4. Device-only / instrumented gaps (documented, not executed)

| Gap | Why no JVM test | Verification needed (manual device checklist) |
|---|---|---|
| `DownloadManagerImpl` enqueue/cancel/state persistence | WorkManager + Room + Android framework | Device: enqueue/pause/resume/retry, kill-process restore, queue persists across restart |
| **WorkManager scheduling/lifecycle** | `CoroutineWorker`, constraints, backoff | Device: enqueue under Wi-Fi/charging constraints, observe progress, cancel, retry transient vs permanent failures |
| **Notifications (API 33+)** | `POST_NOTIFICATIONS` runtime permission + `NotificationManager` actions | Device: grant and deny the permission; progress notification and pause/resume/retry action buttons render and respond on Android 13/14/15/16 |
| `DownloadWorker` / `EmbedWorker` foreground service, progress | `CoroutineWorker`, foreground-service type, `FFmpegKit` JNI | Device run; foreground service survives backgrounding; `FOREGROUND_SERVICE_DATA_SYNC` declared (API 34) |
| **FFmpegKit native session / ABI** | `FFmpegKit` native `.so` (arm64-v8a / x86_64 only; no 32-bit ARM) | Device: extract audio, soft embed, hard burn, mux, Whisper chunk vectors; 32-bit ARM devices must fail through the normal error path, not crash |
| **Media3 ExoPlayer preview** | `ExoPlayer`/`Player` lifecycle | Device: play/pause/seek/volume, subtitle track selection, release on dispose/background |
| **Keystore encrypt/decrypt round trip** | `DataStore`, `android.security.keystore` AES/GCM | Device: store resolver/Whisper keys, restart app, read and redact; exercise the Keystore-unavailable fallback path; never log plaintext |
| **Room DAO queries + migrations** | SQLite/Room under instrumentation | Emulator: `DownloadDao`, `MediaMetadataDao`, `PlaybackProgressDao`, `SubtitleRecordDao` queries; migration 1→N with destructive fallback disabled |
| **ML Kit on-device translation** | Google ML Kit model download + on-device inference | Device: first-run model fetch; translate a short Arabic/English cue; confirm no network call after model load; offline toggle |
| **RTL / Arabic layout** | Compose layout direction + Arabic resources/fonts | Device: switch to Arabic (`values-ar`); verify mirroring, no clipped/truncated text, Arabic filenames and subtitle text render; hard-burn Arabic glyphs via the bundled font style |
| `TranscriptionWorker` multipart upload, chunking, WorkManager constraints | OkHttp multipart + WorkManager + files | Device against a configured Whisper endpoint (external service) |
| Resolver adapter network calls | Retrofit/OkHttp real network | Device/network test against deployed backend over HTTPS |

---

## 5. CI workflow map (validation-only; no APK/AAB)

`.github/workflows/ci.yml` jobs:

| Job | Command(s) | Outputs |
|---|---|---|
| `backend-tests` | venv + `pip install -r backend/requirements.txt`; `python -m pytest backend/tests -q` | pytest results; job summary |
| `gradle-validation` | `./gradlew help --no-configuration-cache` (JDK 17, SDK 36) | model resolution; summary |
| `android-unit-tests` | `./gradlew testDebugUnitTest` (JDK 17, SDK 36) | JUnit XML count in summary; no packaging task |
| `project-structure` | path existence checks; scan for forbidden packaging task names in workflows | pass/fail; summary |
| `secret-scan` | high-entropy/key-pattern grep over committed source/config; `*.example` placeholders excluded | pass/fail; summary |

There is **no** workflow building APK/AAB: the former `build.yml` was removed; the project-structure job fails the build if a packaging task name reappears in `.github/workflows`. No job sets an error-continuation flag.

---

## 6. Summary of what was NOT executed (and why)

1. No Gradle invocation (`help`, `testDebugUnitTest`, lint, assembly) — no Android SDK / Gradle distribution / JDK 17 in this environment (host JDK 11).
2. No Kotlin test assertion was evaluated; the 447 `@Test` methods in the §2 grep snapshot are source-level and must be executed by `./gradlew testDebugUnitTest` in CI (JDK 17 + SDK 36).
3. No Room migration/DAO instrumentation; no emulator/device runtime (WorkManager, FFmpegKit native, ExoPlayer, Keystore, SAF pickers, notifications).
4. No APK/AAB build, sign, or install; no release tag workflow.
5. No live call to an external service (Whisper, LibreTranslate, deployed resolver); those require user-provided endpoints documented in `docs/EXTERNAL_SERVICES.md`.

**Acceptance pointer:** feature-by-feature status and evidence paths are in `COMPLETION_REPORT.md`; dependency compatibility evidence in `docs/COMPATIBILITY.md`.

---

## Durable transcription recovery (pending_transcriptions, DB v5)

A durable `pending_transcriptions` table is being added at database schema **v5** by another agent (MIGRATION_4_5). It persists in-flight Whisper transcription jobs so that an app restart / process death / device reboot does not lose a queued transcription. On next launch, `AutoTranscribeObserver` (in `app/transcription/`) re-enqueues any rows still in a pending state.

- **How to verify:** see `docs/DEVICE_TESTING_GUIDE.md` §5 (migration 4→5) and §20 (app restart recovery, incl. pending transcriptions).
- **Status:** table + migration owned by the database agent; recovery observer wiring owned by the app/transcription agent; this guide references the expected behavior. If the table is not yet present at v5, the migration test in `core/database/src/androidTest/` will fail on device.

