# Compatibility Audit & Minimal Coherent Upgrade (SDK 36)

**Audit date:** 2026-11-19
**Scope:** every `build.gradle.kts`, `gradle/libs.versions.toml`,
`gradle/wrapper/gradle-wrapper.properties`, root/settings Gradle scripts, both
Android manifests, and ProGuard rules.
**Build status: NO Gradle execution was performed in this environment.** This
document records a static, evidence-backed change set. Resolution, compilation,
lint, and packaging must be verified in CI (JDK 17) or on a developer machine —
see §7 known manual steps.

---

## 1. Why an upgrade is required

Google Play policy requires new apps and updates to target API 36 (Android 16)
(the initial 2026-08-31 deadline was extended to 2026-11-01; as of this audit,
2026-11-19, the requirement applies). The project previously declared
`compileSdk = 34` and `targetSdk = 34`. Android Studio / AGP enforce a minimum
AGP version per SDK platform; the minimal coherent change set below raises only
what SDK 36 forces and leaves everything else on verified coordinates.

## 2. Component change table (old → new)

| Component | Old | New | Reason | Source URL |
|---|---|---|---|---|
| Android Gradle Plugin | `8.5.2` | `8.10.1` | AGP 8.10 release notes state **maximum API level: API 36** and minimum Gradle **8.11.1**, JDK 17. AGP 8.9.0 maxes at API 35, so AGP 8.10.x is the smallest line that officially supports `compileSdk 36`; 8.10.1 is the latest 8.10 patch on Google Maven. | https://developer.android.com/build/releases/agp-8-10-0-release-notes ; https://maven.google.com/com/android/tools/build/gradle/maven-metadata.xml ; AGP 8.9 ceiling: https://developer.android.com/build/releases/agp-8-9-0-release-notes |
| Gradle wrapper (`distributionUrl`) | `8.7` | `8.11.1` | AGP 8.10.1 minimum compatible Gradle = 8.11.1 (same release notes). Wrapper JAR and script are unchanged; the wrapper was NOT executed. | https://developer.android.com/build/releases/agp-8-10-0-release-notes |
| `compileSdk` (app + 10 library modules) | `34` | `36` | SDK Platform 36 required by Play target policy and by AGP 8.10 (max API 36). All 11 Gradle modules now declare 36. | https://developer.android.com/build/releases/gradle-plugin (API level / AGP table) |
| `targetSdk` (app only) | `34` | `36` | Play policy: new apps/updates must target API 36. Library modules do not declare `targetSdk`. | Play target-level policy referenced in root README/CHANGELOG; AGP compatibility table same as above |
| `minSdk` | `24` | `24` (unchanged) | No SDK 36 requirement forces a higher floor; Android 7.0 coverage preserved. | — (unchanged) |
| Kotlin (`kotlin`, `kapt`) | `1.9.24` | `1.9.24` (unchanged) | See §3 evidence: no verified incompatibility; Kotlin bump would break the Compose Compiler pairing. | https://kotlinlang.org/docs/gradle-configure-project.html |
| Compose Compiler (`composeCompiler`) | `1.5.14` | `1.5.14` (unchanged) | Official Compose↔Kotlin map: 1.5.14 ⇄ Kotlin 1.9.24. Replacing it requires the Kotlin 2.x Compose Gradle plugin. | https://developer.android.com/jetpack/androidx/releases/compose-kotlin |
| Room | `2.6.1` | `2.6.1` (unchanged) | Coordinate verified present on Google Maven; no API-level incompatibility with AGP 8.10/JDK 17. kapt processor retained. | https://maven.google.com/androidx/room/room-runtime/maven-metadata.xml |
| Media3 (`media3-exoplayer`, `media3-ui`, `media3-datasource`) | `1.3.1` | `1.3.1` (unchanged) | Verified present on Google Maven; minSdk 24 compatible; no bump required for SDK 36. | https://maven.google.com/androidx/media3/media3-exoplayer/maven-metadata.xml |
| WorkManager (`work-runtime-ktx`) | `2.9.0` | `2.9.0` (unchanged) | 2.9.0 verified present; 2.9.1 also exists but no SDK-36 forcing change (minimal-change rule). Hilt Work integration (`androidx.hilt:hilt-work:1.2.0`) verified present. | https://maven.google.com/androidx/work/work-runtime-ktx/maven-metadata.xml ; https://maven.google.com/androidx/hilt/hilt-work/maven-metadata.xml |
| Hilt (`hilt-android`, `hilt-compiler`) | `2.51.1` | `2.51.1` (unchanged) | Verified present on Maven Central; kapt 1.9.24 processor chain unchanged. | https://repo1.maven.org/maven2/com/google/dagger/hilt-android/maven-metadata.xml |
| FFmpeg media engine | `com.arthenica:ffmpeg-kit-full-gpl:6.0-2` (retired Arthenica fork; absent from this tree) | `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` | Maintained drop-in replacement; same `com.arthenica.ffmpegkit` API; POM verified (aar packaging; transitive `com.arthenica:smart-exception-java:0.2.1`); AAR listed 2026-09-25. GPL-3.0 — see `docs/FFMPEG.md`. | https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full-gpl/maven-metadata.xml ; POM: https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full-gpl/8.1.9/ffmpeg-kit-full-gpl-8.1.9.pom |
| `security-crypto` | not declared | **not declared (intentional)** | Secrets are protected by a manual Android Keystore AES/GCM implementation (`feature/settings/security/SecretStore.kt`, `SecretCodecs.kt`). Adding `androidx.security:security-crypto` would be redundant and was explicitly avoided. | source: `feature/settings/src/main/kotlin/app/vidmatex/feature/settings/security/` |
| Retrofit / OkHttp / Moshi-ish JSON | Retrofit `2.11.0`, OkHttp `4.12.0`, kotlinx.serialization `1.6.3` | unchanged | Verified present (Retrofit 2.11.0 on Maven Central; OkHttp 4.12.0 line; OkHttp `HttpUrl` used by the pure `BackendUrlValidator`). | https://repo1.maven.org/maven2/com/squareup/retrofit2/retrofit/maven-metadata.xml ; https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml |
| ML Kit Translate | `17.0.3` | unchanged | Verified latest on Google Maven; on-device, no network dependency. | https://maven.google.com/com/google/mlkit/translate/maven-metadata.xml |
| Compose BOM | `2024.06.00` | unchanged | Compatible with Compose Compiler 1.5.14 / Kotlin 1.9.24; no SDK 36 forcing change. | catalog: `gradle/libs.versions.toml` |
| JVM target (`compileOptions`/`kotlinOptions`) | `17` | `17` (unchanged) | AGP 8.10 requires JDK 17 to run; bytecode target 17 matches CI toolchain. | https://developer.android.com/build/releases/agp-8-10-0-release-notes |

### Additional verified coordinates (spot check, unchanged versions)

| Coordinate | Verified |
|---|---|
| `androidx.datastore:datastore-preferences:1.1.1` | https://maven.google.com/androidx/datastore/datastore-preferences/maven-metadata.xml |
| `androidx.navigation:navigation-compose:2.7.7` | https://maven.google.com/androidx/navigation/navigation-compose/maven-metadata.xml |
| `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4` | https://maven.google.com/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml |
| `io.coil-kt:coil-compose:2.6.0` | https://repo1.maven.org/maven2/io/coil-kt/coil-compose/maven-metadata.xml |
| `org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24` | https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml |

## 3. Why Kotlin 1.9.24 was kept (evidence)

1. **Compose Compiler coupling.** The official Compose-to-Kotlin compatibility
   map pairs Compose Compiler `1.5.14` with Kotlin `1.9.24`
   (https://developer.android.com/jetpack/androidx/releases/compose-kotlin).
   Kotlin 2.0+ requires the separate `org.jetbrains.kotlin.plugin.compose`
   Gradle plugin and removes `kotlinCompilerExtensionVersion`; that swap,
   together with kapt/Hilt re-verification, exceeds the minimal SDK-36 change.
2. **KGP matrix.** The Kotlin Gradle plugin compatibility table lists KGP
   `1.9.20–1.9.25` at Gradle 6.8.3–8.1.1 and AGP 4.2.2–8.1.0 *fully tested*
   (https://kotlinlang.org/docs/gradle-configure-project.html). The same page
   states newer Gradle/AGP can run with possible deprecation warnings; no hard
   incompatibility with AGP 8.10 is documented.
3. **No breakage vector identified in this static audit:** kapt `1.9.24`,
   Room processor `2.6.1`, Hilt `2.51.1`, and the JVM-target-17 output are all
   compatible with the Kotlin 1.9 line.
4. **Fallback if CI surfaces an incompatibility:** the aligned chain would be
   Kotlin `2.2.21` + Compose Gradle plugin `2.2.21` (KGP 2.2.20–21 supports
   AGP up to 8.11.1 and Gradle up to 8.14) + AGP 8.10.1. That decision belongs
   to CI verification, not this static pass.

## 4. Catalog consistency (`libs.` accessors)

A script cross-checks every `libs.*` usage in `*.kts` against the aliases in
`gradle/libs.versions.toml` (libraries, plugins, versions). Result at audit
time: **all referenced accessors resolve**; no invented coordinates; no
declared-but-required coordinate was removed. The only false positive from the
checker was the literal path string `gradle/libs.versions.toml` in a comment.

## 5. Manifest & ProGuard audit

### `app/src/main/AndroidManifest.xml`

- Permissions present and consistent with §14 of the request:
  `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (runtime request
  on Android 13+), `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC`, `REQUEST_INSTALL_PACKAGES` (self-installer
  update flow, user-confirmed), `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.
- `WorkManagerInitializer` removed (`tools:node="remove"`); on-demand Hilt
  initialization lives in `VidMateXApplication`
  (`Configuration.Provider` + `HiltWorkerFactory`).
- `DownloadActionReceiver` (`feature/downloads`) declared with
  `android:exported="false"`; `FileProvider` declared with `exported="false"`
  and `android:grantUriPermissions="true"`.
- Network security config denies cleartext by default; debug builds may add
  user trust anchors. `tools:targetApi` raised to 36 alongside `targetSdk`.

### `feature/downloads/src/main/AndroidManifest.xml`

- Receiver for retry/delete intents; no new permissions required.

### ProGuard (`app/proguard-rules.pro`)

- Keeps retained:
  `com.arthenica.ffmpegkit.**`, `com.arthenica.smartexception.**`,
  `com.google.mlkit.**`, data/model/network serialization classes, Hilt and
  Room generated code, OkHttp/Okio platform signatures, Coroutines
  `suspend` machinery, Compose stability markers.
- The fork keeps the original `com.arthenica.ffmpegkit` package and pulls
  `com.arthenica.smart-exception-java:0.2.1` transitively, so both keep rules
  remain correct. R8/ProGuard syntax was reviewed statically; no execution was
  run.

### Android API-level behavior notes (SDK 33 → 36)
- **SDK 33 (Android 13) — notifications runtime permission.**
  `POST_NOTIFICATIONS` is no longer install-time granted; the app requests it
  at runtime (`MainActivity`) and degrades gracefully when denied (downloads
  continue, no notifications). `TARGET_API 33+` behavior must be verified on a
  device/emulator (see `docs/TESTING_GUIDE.md` §4).
- **SDK 34 (Android 14) — foreground-service types.** Every foreground service
  must declare a concrete `foregroundServiceType` and hold the matching
  permission. The download/transcription workers declare
  `FOREGROUND_SERVICE_DATA_SYNC` with the `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_DATA_SYNC` permissions; starting a data-sync foreground
  service without the declared type would crash on SDK 34+.
- **SDK 35 (Android 15) — edge-to-edge and gesture back.** Targeting 35+ forces
  edge-to-edge drawing; screens must handle window insets (status/navigation
  bars) through Compose inset modifiers, and predictive-back gestures should be
  exercised on device. These are layout/runtime behaviors, not compile errors,
  and are part of the manual device checklist (`docs/TESTING_GUIDE.md` §4).
- **SDK 36 (Android 16) — Play target requirement and behavior.** Play policy
  requires target API 36 for new apps/updates (§1); `targetSdk = 36` and
  `compileSdk = 36` are declared. System-behavior deltas versus 35 (per-app
  language settings, expanded inset/foreground restrictions, 16 KB page
  alignment for native libraries) require device verification; no behavior
  change was claimed statically here.
- **FFmpegKit native ABI caveat.** The maintained `ffmpeg-kit-full-gpl:8.1.9`
  build ships native libraries for `arm64-v8a` and `x86_64`; there are no
  32-bit ARM (`armeabi-v7a`) `.so` files. On 32-bit ARM devices the native
  processor fails through the normal error path instead of loading, while pure
  JVM logic (policy, argument vectors, subtitle parsing) still works. ABI
  coverage and on-device execution are manual verification items; the desktop
  host validation in `docs/FFMPEG.md` exercises argument vectors only.

## 6. JDK and CI requirement

| Item | Requirement |
|---|---|
| Build JDK | **JDK 17** (AGP 8.10 requires JDK 17; AGP 8.x no longer runs on JDK 11). |
| Host note | This audit environment has JDK 11 and **no Android SDK / Gradle distribution**, so no Android build, test, lint, or packaging command was executed. |
| CI runner | `actions/setup-java` with `distribution: temurin, java-version: 17`; Android SDK packages `platforms;android-36` and `build-tools;36.0.0`. |
| Bytecode | `compileOptions`/`kotlinOptions` remain on JVM target 17 across all modules. |

## 7. Manual steps / gaps (not executed here)

1. Install Android SDK Platform **36** (`platforms;android-36`) and
   `build-tools;36.0.0`; AGP selects build-tools automatically but the
   platform must be present.
2. Run `./gradlew help --no-configuration-cache` (validation only), then
   `./gradlew testDebugUnitTest` (JVM unit tests) on JDK 17.
3. Instrumented/Room migration tests and `androidTest` components require an
   emulator or device; they are documented as device/CI-only in
   `docs/TESTING_GUIDE.md`.
4. Native FFmpegKit execution (extract/mux/embed/chunk vectors) on
   `arm64-v8a`/`x86_64` devices and the graceful-failure path on devices without
   a matching ABI are device verification items; see §5 API notes and
   `docs/TESTING_GUIDE.md` §4.
5. No `assembleDebug`, `assembleRelease`, or `bundleRelease` task was run, and
   no APK/AAB artifact was produced; CI workflows are validation-only by design
   (see `.github/workflows/ci.yml`).

## 8. Honest status statement

Versions above were chosen from coordinates verified against Maven Central and
Google Maven metadata on 2026-11-19, plus the official Android and Kotlin
compatibility tables. Dependency *resolution* (downloading and building the
graph) and Kotlin/Java compilation were **not executed** in this environment.
Any CI warning (e.g. KGP 1.9.24 running above its tested Gradle band per §3)
must be triaged against the fallback chain listed there.
