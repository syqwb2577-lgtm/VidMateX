# VidMateX — Repair Report

**Scope:** full static audit + repair of the supplied `VidMateX` source ZIP.
**No APK / AAB was built, signed, or packaged.** No `assemble*` / `bundle*`
task was executed. All checks below are **static** (source inspection, Gradle
configuration review, dependency-compatibility review, XML/YAML validation,
test-source review, security review). A real Android build is still required
to confirm compilation — see §6.

---

## Addendum — 2026-11-19 reconciliation round (history above preserved unchanged)

The original report's later-state notes have been superseded as follows; the
historical sections remain for audit trail:

- **§8 item 1 (Whisper "stub only") — superseded:** the `TranscriptionWorker`
  (`@HiltWorker`), multipart client, >24 MB chunking, SRT/VTT output, retry
  policy, path builders, and endpoint validator now exist; pure helper tests
  are source-ready; a live run still needs a configured endpoint and a device.
- **Dependency note:** the retired `com.arthenica:ffmpeg-kit-full-gpl:6.0-2`
  coordinate is gone; the active coordinate is
  `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (`docs/FFMPEG.md`).
- **Compatibility:** AGP 8.10.1 + Gradle 8.11.1 + `compileSdk/targetSdk 36`;
  Kotlin 1.9.24 and Compose Compiler 1.5.14 retained with documented evidence
  (`docs/COMPATIBILITY.md`).
- **CI:** the former APK/AAB-emitting `build.yml` was removed; `ci.yml` is
  validation-only (backend pytest 123 executed green; Gradle help;
  `testDebugUnitTest`; structure; secret scan).
- **Secrets:** resolver/Whisper keys now use Android Keystore AES/GCM at rest
  (`feature/settings/security/`); §8 item 1's plaintext-storage note no longer
  applies to the current code.
- **Tests:** backend suite = 123 passed; Kotlin inventory = 31 files / 240
  source-ready `@Test` cases (`docs/TESTING_GUIDE.md`); no Kotlin test was
  executed in this environment.

---

Original project preserved untouched at extraction; repaired copy is
`VidMateX_FIXED/`.

---

## 1. Critical issues found & fixed (would break build or runtime)

| # | Severity | File | Problem | Fix |
|---|---|---|---|---|
| 1 | **Build fail** | `core/common/build.gradle.kts` | Missing `api(project(":core:model"))`; common classes import core.model symbols | Added dependency + Hilt/kapt |
| 2 | **Build fail** | `core/common/Dispatchers.kt` | No Hilt `@Binds` for `DispatcherProvider` → 6+ injection sites unresolved | Added `DispatcherProviderModule` |
| 3 | **Runtime crash** | `app/.../VidMateXApplication.kt` | Did **not** implement `Configuration.Provider` / inject `HiltWorkerFactory`, while manifest removed default `WorkManagerInitializer` → every `@HiltWorker` (DownloadWorker, EmbedWorker) crashes | Implemented on-demand Hilt-WorkManager init |
| 4 | **Runtime crash** | `app/src/main/res/values/themes.xml` | `PostSplash` parent was platform `android:Theme.Material.NoActionBar` (dark-only, no Material3) → splash→theme transition broken | Switched to `Theme.Material3.DayNight.NoActionBar` + material dep |
| 5 | **Behavior** | `core/network/NetworkModule.kt` | `HttpsOnlyInterceptor` rejected permitted `http` direct-URL downloads (allowed by `UrlValidator`) | Removed; HTTPS enforced at backend base URL |
| 6 | **Repo readiness** | repo root | `gradle-wrapper.jar` / `gradlew` / `gradlew.bat` absent; `.gitignore` ignored them | Generated wrapper (Gradle 8.7), committed, un-ignored |
| 7 | **i18n** | `feature/downloads`, `feature/history` | No `res/` at all → all UI text hardcoded, no Arabic | Created `values/` + `values-ar/strings.xml`, converted UI to resources |
| 8 | **Arabic** | `media/processor/FFmpegArgs.kt` | `subtitles=` filter lacked `charenc=UTF-8` → Arabic burned as mojibake | Added `charenc=UTF-8` + `FontName` |
| 9 | **Arabic** | `feature/subtitles/SubtitlesViewModel.kt` | `sanitizeFileName` stripped all non-ASCII → Arabic filenames corrupted | Allowed Unicode letters |
| 10 | **Reliability** | `feature/downloads/DownloadWorker.kt` | `BackoffPolicy.LINEAR`; `fail()` passed empty title to notifications | `EXPONENTIAL`; real entity title propagated |

## 2. Completeness additions (not errors, required by spec)
- `SpeechToText` interface + `WhisperSpeechToText` (optional API-based stub,
  no hardcoded key) + `NoOpSpeechToText` default (Whisper/STT integration point).
- `SubtitleParser.shift()` (sync) + `detectLanguage()` (Arabic heuristic), with
  new unit tests.
- Media3 wired into `app` + `Media3PlayerController` (preview-player point).
- `network_security_config.xml` (cleartext denied).
- `ci.yml` (unit tests + lint, no APK) alongside `build.yml` (manual/release).
- `LICENSE` (GPL-3.0), `CONTRIBUTING.md`, `GITHUB_SETUP.md`,
  `local.properties.example`.
- `AppSettings.whisperEndpoint/whisperApiKey`, `MediaTrack.resumable`,
  `EmbedStyle.fontName` model fields.

## 3. Modified files (34)
```
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/app/vidmatex/MainActivity.kt
app/src/main/kotlin/app/vidmatex/VidMateXApplication.kt
app/src/main/kotlin/app/vidmatex/ui/theme/Theme.kt
app/src/main/res/values/themes.xml
core/common/build.gradle.kts
core/common/src/main/kotlin/app/vidmatex/core/common/Constants.kt
core/common/src/main/kotlin/app/vidmatex/core/common/Dispatchers.kt
core/common/src/main/kotlin/app/vidmatex/core/common/UrlValidator.kt
core/model/src/main/kotlin/app/vidmatex/core/model/Models.kt
core/network/src/main/kotlin/app/vidmatex/core/network/NetworkModule.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/DownloadManagerImpl.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/DownloadWorker.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/EmbedWorker.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/DownloadsScreen.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/DownloadsViewModel.kt
feature/downloads/src/main/kotlin/app/vidmatex/feature/downloads/NotificationHelper.kt
feature/history/src/main/kotlin/app/vidmatex/feature/history/HistoryScreen.kt
feature/history/src/main/kotlin/app/vidmatex/feature/history/HistoryViewModel.kt
feature/home/src/main/kotlin/app/vidmatex/feature/home/HomeViewModel.kt
feature/home/src/main/res/values/strings.xml
feature/home/src/main/res/values-ar/strings.xml
feature/subtitles/src/main/kotlin/app/vidmatex/feature/subtitles/SubtitlesScreen.kt
feature/subtitles/src/main/kotlin/app/vidmatex/feature/subtitles/SubtitlesViewModel.kt
feature/subtitles/src/main/res/values/strings.xml
feature/subtitles/src/main/res/values-ar/strings.xml
media/processor/src/main/kotlin/app/vidmatex/media/processor/FFmpegArgs.kt
media/processor/src/main/kotlin/app/vidmatex/media/processor/MediaContracts.kt
media/processor/src/main/kotlin/app/vidmatex/media/processor/MediaModule.kt
media/processor/src/main/kotlin/app/vidmatex/media/processor/SubtitleParserImpl.kt
.github/workflows/build.yml
.gitignore
gradle.properties
README.md
```

## 4. Added files (18, incl. wrapper + docs written after this report)
```
gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar
LICENSE, CONTRIBUTING.md, GITHUB_SETUP.md, CHANGELOG.md, REPAIR_REPORT.md
local.properties.example
.github/workflows/ci.yml
app/src/main/kotlin/app/vidmatex/player/Media3PlayerController.kt
app/src/main/res/xml/network_security_config.xml
feature/downloads/src/main/res/values/strings.xml
feature/downloads/src/main/res/values-ar/strings.xml
feature/history/src/main/res/values/strings.xml
feature/history/src/main/res/values-ar/strings.xml
media/processor/src/main/kotlin/app/vidmatex/media/processor/SpeechToTextImpls.kt
media/processor/src/test/kotlin/app/vidmatex/media/processor/SubtitleShiftAndLanguageTest.kt
```

## 5. Removed files
**None.** No source, resource, or required asset was deleted. Cleaned only
ephemeral `build/` / `.gradle/` outputs created during wrapper generation
(never part of the source tree).

## 6. Checks performed (static, no APK)
- ✅ Full source audit of all 56 Kotlin files; unresolved-reference /
  wrong-import / signature-mismatch review across module boundaries.
- ✅ Gradle configuration inspection: `settings.gradle.kts` (12 modules), all
  12 `build.gradle.kts`, `gradle/libs.versions.toml` (every `libs.*` alias
  used is defined; verified by script), `gradle.properties`.
- ✅ Dependency-compatibility review: AGP 8.5.2 ↔ Gradle 8.7 ↔ JDK 17 ↔
  Kotlin 1.9.24 ↔ Compose compiler 1.5.14 ↔ Compose BOM 2024.06.00 ↔ Hilt
  2.51.1 (kapt) ↔ Room 2.6.1 ↔ WorkManager 2.9.0 — mutually compatible.
- ✅ Hilt injection review: `@HiltAndroidApp`, `@AndroidEntryPoint`,
  `@HiltViewModel`, `@HiltWorker`/`@AssistedInject`, `@Module`/`@InstallIn`/
  `@Binds`/`@Provides`; missing `DispatcherProvider` binding fixed.
- ✅ Room review: entities, DAO SQL, type converters, `@Database` consistency.
- ✅ WorkManager review: on-demand init, foreground-service permission + type,
  `setProgress` keys, cancellation, retry policy.
- ✅ FFmpegKit review: correct `com.arthenica.ffmpegkit` 6.0 API; argument
  lists (no shell injection); Arabic `charenc=UTF-8` + font.
- ✅ Subtitle review: SRT/VTT/ASS parse + export, UTF-8/BOM, sync shift,
  language detection, selected-language==saved-language tracking, RTL preview.
- ✅ XML validation: every `.xml` (manifests, resources, file_paths,
  network_security_config) parses.
- ✅ YAML validation: both workflows parse; no APK artifact produced by us.
- ✅ Security review: URL validation/sanitization, no hardcoded secrets
  (scanned), HTTPS/cleartext policy, minimal permissions, FileProvider
  `exported=false`, receiver `exported=false`, no path traversal in filenames.
- ✅ Repo hygiene: no `*.apk`/`*.aab`/`*.jks`/`*.keystore`/`local.properties`;
  wrapper committed; `.gitignore` correct.
- ✅ Unit-test source review: `SubtitleParserImplTest` + added
  `SubtitleShiftAndLanguageTest` match impl API.

## 7. Checks that REQUIRE a later Android build (not performed, by design)
- ⏳ Kotlin/Java compilation + kapt (Hilt/Room codegen) — run
  `./gradlew :app:assembleDebug` or at least `./gradlew help compileDebugKotlin`.
- ⏳ Actual unit-test execution (`./gradlew testDebugUnitTest`) — wired in
  `ci.yml`; first GitHub Actions run validates.
- ⏳ Lint with full Android SDK (`./gradlew :app:lintDebug`).
- ⏳ Resource linking / manifest merge / R8 (release) correctness.
- ⏳ Runtime behavior: WorkManager scheduling, FFmpegKit execution on-device,
  ML Kit model download, SAF picker, notification permission flow.
- ⏳ Workflow execution on GitHub Actions — prepared only; the repository owner
  must push and let it run. No "build passed" claim is made.

## 8. Remaining limitations (intentional, documented)
1. **Whisper/STT** is an interface + stub only (`WhisperSpeechToText`). A live
   HTTP integration and a settings UI toggle are future work; default binding
   is `NoOpSpeechToText`. Endpoint/key fields exist in `AppSettings`.
2. **Platform resolvers** (YouTube/Facebook/TikTok) are **not** built in. They
   require the user's self-hosted resolver endpoint (`backendBaseUrl`). No
   platform API was invented; no DRM bypass.
3. **Download resume** uses OkHttp `Range` when the server advertises
   `Accept-Ranges: bytes` (`MediaTrack.resumable`). Servers that don't advertise
   it restart from zero — correct, not a bug.
4. **`FFmpegProcessor`** does not yet expose per-session progress callbacks to
   callers (interface signature change deferred); embed progress is coarse.
5. **Media3** playback is wired via `Media3PlayerController` but not yet
   attached to a preview screen UI — integration point provided.
6. **Arabic translations**: added for all string keys; a native speaker review
   is recommended before publication.
7. **License**: default is GPL-3.0 due to `ffmpeg-kit-full-gpl`. If you switch
   to the LGPL FFmpegKit variant, you may relicense your own code.
8. `docs/PRIVACY_POLICY.md` is a template — customize before distributing.
