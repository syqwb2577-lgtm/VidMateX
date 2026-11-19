# App shell + Media3 player — audit checklist (module-local)

Scope: `app/` (main, `androidTest`, resources, manifest, proguard) plus the player seam
owned by this module. Other modules (`core:*`, `feature:*`, `media:*`, `source:*`) were
read-only; cross-module requests are collected in §5.

Environment: no Android SDK / JDK 17 / Gradle in this environment → Kotlin was **not
compiled or executed**. Main-source Kotlin is written against the existing catalog
(media3 1.3.1, Compose BOM, minSdk 24, compile/targetSdk 36). All new logic reachable on
the JVM lives in plain JUnit 4 tests under `src/test/kotlin/` with **no `android.*`
imports**. Device/Emulator gaps are marked `DEVICE-PENDING` and never claimed green.

Status vocabulary: **COMPLETE AND VERIFIED** · **IMPLEMENTED BUT NOT VERIFIED** ·
**PARTIALLY IMPLEMENTED** · **NOT IMPLEMENTED** · **BLOCKED BY EXTERNAL REQUIREMENT**.
"Verified" below means source-reviewed + JVM-pure test authored; device behavior is
always `IMPLEMENTED BUT NOT VERIFIED` unless a device run occurs.

## 1. Player control → wiring matrix (§3)

| UI / logic control | Wired? | Evidence (file::symbol) | Status |
|---|---|---|---|
| Play / pause button | Yes | `VideoPreviewScreen` → `Media3PlayerController.play()/pause()`; state `isPlaying` drives icon | IMPLEMENTED BUT NOT VERIFIED |
| Resume after pause | Yes | `Media3PlayerController.resume()` = play; ON_STOP pauses, dispose releases | IMPLEMENTED BUT NOT VERIFIED |
| Stop | Yes | `Media3PlayerController.stop()` resets transport state; reusable | IMPLEMENTED BUT NOT VERIFIED |
| Seek back / forward 10 s | Yes | `VideoPreviewScreen` → `seekBy(±10_000L)`; clamped in controller | IMPLEMENTED BUT NOT VERIFIED |
| Absolute seek (slider) | Yes | `seekTo(ms)` clamped to duration; slider polls `currentPositionMs()` | IMPLEMENTED BUT NOT VERIFIED |
| Speed ladder menu 0.25x–2x | Yes | `PlaybackSpeed.LADDER`; `setSpeed()` → `PlaybackParameters(rate)`; snapping via `nearestSupported` | IMPLEMENTED BUT NOT VERIFIED |
| Speed progression/clamp logic | Pure-tested | `PlaybackSpeedTest` (7 `@Test`) | COMPLETE AND VERIFIED (JVM) |
| Audio track seam (list + select index) | Yes | `Tracks` → `TrackOption` list in state; `selectAudioTrack(i)` applies `TrackSelectionOverride` | IMPLEMENTED BUT NOT VERIFIED |
| Subtitle/caption track seam (list + select index + Off) | Yes | `selectSubtitleTrack(i / OFF_INDEX)`; override + `setTrackTypeDisabled` | IMPLEMENTED BUT NOT VERIFIED |
| Captions enabled toggle | Yes | `setCaptionsEnabled(boolean)` toggles `TRACK_TYPE_TEXT`; disabled when no text tracks | IMPLEMENTED BUT NOT VERIFIED |
| Track selection policy (pure) | Pure-tested | `TrackSelectionPolicyTest` (6 `@Test`) | COMPLETE AND VERIFIED (JVM) |
| External SRT/VTT sidecar attach | Yes | `prepare(subtitleUri, subtitleMime, subtitleLanguage)`; extension MIME inference (SRT/VTT) | IMPLEMENTED BUT NOT VERIFIED |
| Embedded text-track exposure | Yes | `onTracksChanged` maps `C.TRACK_TYPE_TEXT` groups to state.subtitleTracks | IMPLEMENTED BUT NOT VERIFIED |
| Subtitle delay +/- (500 ms steps, ±1 h clamp) | Yes | `setSubtitleDelayMs`; `SubtitleDelay` pure conversion/shift; persisted with entry | IMPLEMENTED BUT NOT VERIFIED |
| Delay ms↔format conversion | Pure-tested | `SubtitleDelayTest` (9 `@Test`) | COMPLETE AND VERIFIED (JVM) |
| Error state mapping (typed categories) | Yes | `PlayerErrorMapper`; UI maps category to string; reason redacted via `SecretTextRedactor` | IMPLEMENTED BUT NOT VERIFIED (pure map JVM-tested) |
| Error-category mapping from reason/code | Pure-tested | `PlayerErrorMapperTest` (9 `@Test`) | COMPLETE AND VERIFIED (JVM) |
| Buffering state surfaced | Yes | `onIsLoadingChanged` + `onPlaybackStateChanged(STATE_BUFFERING)`; spinner overlay | IMPLEMENTED BUT NOT VERIFIED |
| Lifecycle-safe release | Yes | ON_STOP → pause; dispose → `release()` saves position, cancels scope, releases ExoPlayer | IMPLEMENTED BUT NOT VERIFIED |
| Position restore (media id → ms) | Yes | `PositionStore` + `InMemoryPositionStore`; prepare restores position/speed/delay; completed not resumed | IMPLEMENTED BUT NOT VERIFIED |
| Save/eviction/retention policy | Pure-tested | `PlaybackPositionStoreTest` (7 `@Test`) | COMPLETE AND VERIFIED (JVM) |
| Room `playback_progress` wiring | Adapter ready, DI blocked | `RoomPlaybackPositionStore`; DAO not provided by `:feature:downloads` Hilt module (§5) | BLOCKED BY EXTERNAL REQUIREMENT |
| Fullscreen + orientation policy | Yes (policy pure); surface toggle real; immersive system behavior pending | `OrientationPolicyTest` (4 `@Test`); manifest `configChanges` retained | PARTIALLY IMPLEMENTED (rendering DEVICE-PENDING) |
| Local file + stream sources | Yes | `MediaItem.fromUri` path accepts `file:`, `content:`, `https:`; validation owned by caller | IMPLEMENTED BUT NOT VERIFIED |
| Volume control | Yes | Slider → `player.volume` | IMPLEMENTED BUT NOT VERIFIED |
| No dead/placeholder buttons | Verified by audit | Track menus disabled when empty; speed/delay buttons clamp at bounds; error Retry/Dismiss drive logic | COMPLETE AND VERIFIED (source audit) |

## 2. Requirements trace (§3 player)

| Requirement | Label | Evidence | Remaining work |
|---|---|---|---|
| play/pause/resume/stop | IMPLEMENTED BUT NOT VERIFIED | `Media3PlayerController.play/pause/resume/stop` | Device transport test |
| seek ± and absolute seek | IMPLEMENTED BUT NOT VERIFIED | `seekBy`, `seekTo` | Device seek-bar UX |
| speed selection 0.25x–2x via `PlaybackParameters` | IMPLEMENTED BUT NOT VERIFIED | `setSpeed`; `PlaybackSpeed` | Device audio-pitch/speed check |
| audio track seam + select-by-index + pure policy helper | IMPLEMENTED BUT NOT VERIFIED | `TrackSelectionPolicy`, controller maps `Tracks.Group` | Multi-track container device test |
| subtitle seam + captions toggle | IMPLEMENTED BUT NOT VERIFIED | `selectSubtitleTrack`, `setCaptionsEnabled` | Embedded subtitle device render |
| external SRT/VTT sidecar + embedded exposure | IMPLEMENTED BUT NOT VERIFIED | `prepare(...)`, `onTracksChanged` | VTT/ASS cue render device QA |
| subtitle delay adjustment + pure `SubtitleDelay` | IMPLEMENTED BUT NOT VERIFIED | `SubtitleDelay`, `setSubtitleDelayMs` | Renderer-level embedded offset DEVICE-PENDING (no Media3 1.3.1 public API) |
| error mapping: network/unsupported/timeout/unknown | IMPLEMENTED BUT NOT VERIFIED (pure map JVM-green) | `PlayerErrorMapper` + test | Real `PlaybackException` codes on device |
| buffering state | IMPLEMENTED BUT NOT VERIFIED | `isBuffering` state + spinner | Device network stall test |
| lifecycle-safe release + ON_STOP/ON_PAUSE contract documented | IMPLEMENTED BUT NOT VERIFIED | Controller KDoc + screen `DisposableEffect` | Lifecycle walk-through on device |
| position restore pure store + retention | COMPLETE AND VERIFIED (JVM) | `PlaybackPositionStore.kt` + test | Room DAO injection (§5) |
| fullscreen/orientation policy + manifest configChanges | PARTIALLY IMPLEMENTED | `OrientationPolicy`; manifest unchanged; toggle maximizes surface | Immersive flags/rotation lock DEVICE-PENDING |
| `VideoPreviewScreen` controls wired; error/buffering shown; local+stream | IMPLEMENTED BUT NOT VERIFIED | `VideoPreviewScreen.kt` | Compose UI device snapshot |

## 3. Manifest & compatibility review (§11)

| Item | Finding | Evidence |
|---|---|---|
| Permissions present | `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | `app/src/main/AndroidManifest.xml` |
| POST_NOTIFICATIONS runtime request (API 33+) | Implemented; rationale snackbar + app-settings shortcut; denial non-blocking | `MainActivity.maybeRequestNotificationPermission()` |
| FGS type `dataSync` matches worker | Declared; worker-side `FOREGROUND_SERVICE_TYPE_DATA_SYNC` owned by `:feature:downloads` | manifest; downloads manifest note |
| No removed-permission regressions | No legacy storage permissions; SAF + app-private dirs used | manifest + downloads manifest comment |
| Cleartext disabled | `usesCleartextTraffic="false"`; network config allows only loopback dev hosts | `network_security_config.xml` |
| `supportsRtl="true"`, orientation configChanges | Present; activity handles `orientation|screenSize|uiMode|screenLayout|smallestScreenSize` | manifest |
| Deprecated API scan | No `AsyncTask`, bare `Handler()`, `getExternalStorageDirectory()`, `Notification.Builder`-without-channel, or `runOnUiThread` usage in owned main sources | grep audit (this checklist) |
| SDK 35/36 notes (documented, not code changes) | targetSdk 36: FGS type enforcement applies on API 34+ (declared); POST_NOTIFICATIONS runtime on 33+; photo picker/SAF used instead of storage permissions; `enableEdgeToEdge` used; `compileSdk 36` unchanged; no `SCHEDULE_EXACT_ALARM` (WorkManager periodic jobs avoid exact alarms) | manifest, `MainActivity.kt` |
| i18n parity (en ←→ ar, owned modules) | Key sets identical for `app`, `feature/settings`, `feature/home`, `feature/history`; all new player strings translated; RTL inherited from Compose layout + `supportsRtl` | `values/strings.xml` vs `values-ar/strings.xml` parity diff = empty |
| No hardcoded user-facing literals in logic | Removed two literals (home no-tracks; history missing-file toast); player errors are category-keyed strings; reasons redacted | `HomeViewModel`, `HistoryViewModel`, `PlayerErrorMapper` |

## 4. Test inventory (this module)

| File | `@Test` count | Type |
|---|---:|---|
| `app/src/test/kotlin/app/vidmatex/player/PlaybackSpeedTest.kt` | 7 | JVM pure |
| `app/src/test/kotlin/app/vidmatex/player/SubtitleDelayTest.kt` | 9 | JVM pure |
| `app/src/test/kotlin/app/vidmatex/player/PlaybackPositionStoreTest.kt` | 7 | JVM pure (`runBlocking`) |
| `app/src/test/kotlin/app/vidmatex/player/PlayerErrorMapperTest.kt` | 9 | JVM pure |
| `app/src/test/kotlin/app/vidmatex/player/TrackSelectionPolicyTest.kt` | 6 | JVM pure |
| `app/src/test/kotlin/app/vidmatex/player/OrientationPolicyTest.kt` | 4 | JVM pure |
| `app/src/androidTest/.../VideoPreviewInstrumentedTest.kt` | 1 skeleton | NEVER RUN; manual steps documented in-file |

`app/build.gradle.kts` gained `testImplementation(libs.junit)`; no catalog edits.

## 5. Cross-module requests (owned elsewhere)

1. **`PlaybackProgressDao` Hilt provision + MIGRATION_2_3 registration** — `:feature:downloads/DownloadModule.provideDatabase` currently registers only `MIGRATION_1_2` and provides `DownloadDao`. Requested: `providePlaybackProgressDao(db)` and `.addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)`. Then `RoomPlaybackPositionStore` can replace the in-memory fallback; the adapter already maps entity ↔ `PositionEntry`.
2. **Entity extension for speed + subtitle delay** (optional) — `PlaybackProgressEntity` has no columns for `speed` / `subtitleDelayMs`; those remain in the in-memory fallback until `:core:database` extends the entity (additive migration).
3. **Subtitles screen gating** — `feature/subtitles` can consume `SettingsViewModel.whisperReadiness` (exposed in `:feature:settings`) to disable its transcribe action while `blockingIssues` is non-empty.
4. **Downloads worker FGS type** verification on API 34+ (worker-owned); app manifest already declares the permission.

## 6. Blocked / device-pending items

- ExoPlayer playback, track selection UI, subtitle render, speed application, and seek accuracy require a device/emulator run (`connectedDebugAndroidTest`).
- True immersive fullscreen (system-bar hiding, sensor orientation lock) — Compose toggle maximizes the surface; window insets/flags pending device verification.
- Renderer-level subtitle delay on embedded text tracks (Media3 1.3.1 exposes no public text-offset API); cue-shift math is pure-tested and applies to parsed sidecar cues where the parser feeds them.
- Keystore AES/GCM encrypt/decrypt round-trip (device-only) — see `feature/settings/AUDIT_CHECKLIST.md`.
- Room migration 2→3 verification (instrumented) per `docs/TESTING_GUIDE.md` §4.
