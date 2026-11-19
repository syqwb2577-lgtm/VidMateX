# VidMateX — Manual Device Verification Guide

> **Status:** Written, not yet executed. This sandbox has no Android emulator/device, so
> none of the steps below have been run. Every item is a **manual checklist** to perform
> on a physical device or API 24–36 emulator. Mark each row PASS/FAIL with the device
> model, API level, and build variant (debug).
>
> **Instrumented counterparts** (compile-ready, run with `connectedDebugAndroidTest`):
> `app/src/androidTest/` (Hilt startup, Media3 playback, WorkManager init, Arabic RTL,
> navigation), `feature/settings/.../SecretStoreInstrumentedTest.kt` (Keystore AES/GCM),
> `feature/history/.../HistoryScreenRenderInstrumentedTest.kt`,
> `feature/home/.../HomeClipboardInstrumentedTest.kt`. Room DAO + migration tests live in
> `core/database/src/androidTest/` (owned by another agent).

## Legend

- **Device type:** 📱 = physical device required; 🤖 = emulator acceptable; ⚠️ = emulator may work but physical device strongly recommended.
- **Pass criterion:** the explicit condition that must be true. Anything else = FAIL.

## Prerequisites (all tests)

1. A device or emulator running Android 7.0 (API 24) through Android 16 (API 36), 64-bit ABI only (`arm64-v8a` or `x86_64` — ffmpeg-kit ships no 32-bit binaries).
2. Debug build installed: `./gradlew :app:installDebug` (APK packaging is a maintainer-machine step; CI never builds APKs).
3. Backend resolver running (optional for playback/RTL tests; required for download analysis): see `docs/BACKEND_SETUP.md`. Set the resolver URL in Settings.
4. A small valid MP4 + SRT sidecar on the device (push via `adb push` to `Download/`).
5. Arabic locale available on the device (add Arabic in system language settings).

---

## 1. App startup 🤖

**Prerequisites:** Debug build installed; no crash on previous run.

**Steps:**
1. Force-stop the app. Launch from the launcher icon.
2. Observe the splash → Home screen transition.
3. Check logcat for `FATAL EXCEPTION` during startup.

**Expected result:** App launches to the Home tab (URL input + Analyze button visible) within ~2 s; no crash dialog; Hilt `@HiltAndroidApp` graph builds without `IllegalStateException`.

**Pass:** Home screen visible, no `FATAL EXCEPTION` in logcat, no "App keeps stopping" dialog.

---

## 2. Bottom-bar navigation 🤖

**Prerequisites:** App at Home tab.

**Steps:** Tap each of the 5 bottom tabs in order: Home → Downloads → Subtitles → History → Settings → Home.

**Expected result:** Each tab renders its screen without a crash; back stack is preserved; the selected tab highlight follows the tap; unknown routes fall back to Home (no blank screen).

**Pass:** All 5 tabs render; no crash; tab highlight matches current screen; returning to Home restores prior state.

---

## 3. Hilt DI initialization 🤖

**Prerequisites:** Clean install (clear app data first).

**Steps:**
1. Launch the app.
2. In logcat, filter for `Hilt` and `Dagger` and `VidMateXApplication`.
3. Confirm `HiltWorkerFactory` is constructed (WorkManager on-demand init).

**Expected result:** No `HiltIllegalStateException`, no missing-binding errors; `VidMateXApplication.onCreate` completes; `workManagerConfiguration` getter returns a non-null `Configuration` with the Hilt worker factory.

**Pass:** No Hilt missing-binding/crash in logcat; WorkManager initializes on demand (no `WorkManager not initialized` error when a download is enqueued).

---

## 4. Room database initialization 🤖

**Prerequisites:** Clean install (app data cleared).

**Steps:**
1. Launch app, navigate to Downloads and History tabs.
2. Enqueue one download (any URL the resolver accepts).
3. Check logcat for Room `Migration` / `openHelper` errors.

**Expected result:** `AppDatabase` builds at schema version 5 (or current) without destructive recreation; the enqueued download appears in History with state QUEUED/ACTIVE.

**Pass:** Download row persists in History; no "Migration didn't properly handle" or "expected schema not found" Room error in logcat.

---

## 5. Database migrations (MIGRATION_3_4, MIGRATION_4_5) 🤖

**Prerequisites:** An APK built at an older schema version (v3 and v4) — or run the instrumented migration test: `./gradlew :core:database:connectedDebugAndroidTest`.

**Steps (instrumented, preferred):**
1. Run `AppDatabaseMigrationTest` — it creates DB at v1/v2/v3, inserts rows, runs migrations, validates data survival.
2. For MIGRATION_4_5 (durable transcription table `pending_transcriptions`, added by another agent at DB v5): confirm the table exists after migration and rows survive.

**Expected result:** `migrate3To4_addsOrphanedColumnAndUniqueSubtitleIndex` passes (orphaned column defaults to 0; duplicate subtitle rows dedup to 1; unique index present). MIGRATION_4_5 creates `pending_transcriptions` without dropping existing download/history rows.

**Pass:** All migration tests green; pre-existing download rows survive both migrations; `pending_transcriptions` table exists after v5.

---

## 6. WorkManager initialization 🤖

**Prerequisites:** App installed; resolver URL configured.

**Steps:**
1. Enqueue a download from Home.
2. Pull down the notification shade; observe the download notification channel.
3. Check logcat for `WorkManager` and `HiltWorkerFactory`.

**Expected result:** `DownloadWorker` (a `@HiltWorker`) is constructed by `HiltWorkerFactory`; no `WorkerFactory` exception; the job enters ENQUEUED → RUNNING.

**Pass:** Download starts; no `Could not instantiate worker` or `HiltWorkerFactory` error in logcat.

---

## 7. Background tasks continue 📱

**Prerequisites:** A download in ACTIVE state.

**Steps:**
1. Start a download. Press Home to background the app.
2. Wait 30 s. Reopen the app → History tab.
3. (Physical device) Turn the screen off for 1 min, then turn on and reopen.

**Expected result:** Download progress advanced while backgrounded; WorkManager continued the job under the foreground service.

**Pass:** `downloadedBytes` increased during the background period; job did not reset to QUEUED.

---

## 8. Download notifications 📱

**Prerequisites:** API 33+ device (POST_NOTIFICATIONS runtime permission).

**Steps:**
1. On first download, when the permission dialog appears, choose "Allow".
2. Observe progress notification with percent/speed/ETA.
3. Cancel via the notification action; retry via the notification action.
4. Repeat with permission denied — verify a rationale snackbar appears and denial does not block the foreground worker.

**Expected result:** Notification channel exists; progress updates; Cancel/Retry actions work; POST_NOTIFICATIONS denial shows rationale but does not crash or block downloads.

**Pass:** Progress notification visible on API 33+ with permission granted; denial degrades gracefully (no crash, download continues).

---

## 9. Foreground service 📱

**Prerequisites:** Download in progress; Android 12+ (foreground service launch restrictions).

**Steps:**
1. Start a download.
2. Observe the foreground-service notification (ongoing, non-dismissible).
3. Kill the app from recents mid-download.

**Expected result:** `FOREGROUND_SERVICE_DATA_SYNC` permission used; service stays alive after app swipe-away; download resumes or marks state correctly on next launch.

**Pass:** Foreground notification is ongoing (cannot be swiped away) during download; after swipe-away, next app launch shows the job in a consistent state (ACTIVE/PAUSED/FAILED, not lost).

---

## 10. Storage access (SAF + app-private) ⚠️

**Prerequisites:** A completed download; Android 10+ (scoped storage).

**Steps:**
1. Complete a download. Tap Open → choose a file manager.
2. Verify the file lands in `Download/VidMateX/` (or app-private location per policy).
3. Delete the file from a file manager, then tap Open again in History.

**Expected result:** File is accessible via the system file picker; missing-file case shows a toast/error state (not a crash); `..` path traversal is rejected.

**Pass:** Completed file opens in an external player; missing-file case shows a friendly error (no `FileUriExposedException` or crash).

---

## 11. Media3 playback (local MP4) 🤖

**Prerequisites:** A valid MP4 in `Download/`; completed download row in History.

**Steps:**
1. History → tap Preview on the completed row.
2. PlayerView renders the video. Tap play.
3. Pause, play, seek via the progress bar, fullscreen toggle, rotate device.

**Expected result:** ExoPlayer reaches STATE_READY; video renders; play/pause toggles; seek moves currentPosition; rotation does not recreate the activity (manifest `configChanges`); no `PlaybackException`.

**Pass:** Video plays; seek bar reflects position; rotation preserves playback; no playback error card.

---

## 12. Audio track selection 🤖

**Prerequisites:** MP4 with ≥2 audio tracks (or a test MKV/MP4 multi-audio file).

**Steps:**
1. Open Preview → audio track menu.
2. Select each audio track; select Off.

**Expected result:** `selectAudioTrack(index)` updates `state.audioTracks`; the selected track plays; Off disables audio without stopping video.

**Pass:** Audio language changes with selection; Off mutes audio; no crash when switching tracks mid-playback.

---

## 13. Subtitle track selection (embedded) 🤖

**Prerequisites:** MP4 with embedded subtitle tracks (tx3g/WebVTT).

**Steps:**
1. Open Preview → CC/subtitle menu.
2. Toggle CC on; select each embedded subtitle language; select Off.
3. Seek to a segment with cues; verify cue text renders.

**Expected result:** `TRACK_TYPE_TEXT` selection flips; cues show/hide with the CC toggle; Off disables text tracks without dropping them.

**Pass:** Caption text renders when CC on and a subtitle track selected; cues hide when CC off; no crash.

---

## 14. External (sidecar) subtitles 🤖

**Prerequisites:** A `.srt`/`.vtt` sidecar file alongside the MP4 (same base name) or selected via the picker.

**Steps:**
1. Open Preview with the sidecar path supplied (`Media3PlayerController.prepare(url, subtitleUri=...)`).
2. Verify the sidecar appears in the subtitle menu; select it.
3. Seek to a cue timestamp; verify caption renders.

**Expected result:** Sidecar `MediaItem.SubtitleConfiguration` is attached; cues render; delay +/- (500 ms steps) shifts cue timing within ±1 h clamp; reset returns to 0.

**Pass:** Sidecar cues render; subtitle delay controls update `state.subtitleDelayMs` within clamp; no crash.

---

## 15. Arabic RTL 🤖

**Prerequisites:** Device system language set to Arabic (العربية); `values-ar/strings.xml` present.

**Steps:**
1. Switch device language to Arabic.
2. Launch app; inspect Home, bottom bar, Settings, History.
3. Verify text alignment is right-to-left; progress bars fill right-to-left; icons mirror.

**Expected result:** `android:supportsRtl="true"` takes effect; Compose `LocalLayoutDirection` resolves to `Rtl`; all tab labels render in Arabic (no English fallback except untranslated strings); no mirrored-text corruption.

**Pass:** Entire UI mirrors to RTL; Arabic strings display; no cut-off text or layout overflow.

---

## 16. FFmpeg native library load ⚠️

**Prerequisites:** 64-bit device (ffmpeg-kit-full-gpl:8.1.9 ships arm64-v8a + x86_64 only).

**Steps:**
1. Settings → trigger a probe that loads FFmpeg (or start a hard-subtitle embed).
2. Check logcat for `ffmpeg-kit` / `UnsatisfiedLinkError`.

**Expected result:** `FFmpegKit` native libs load; `FFmpegKitConfig` initializes; no `UnsatisfiedLinkError`; on 32-bit devices the install is blocked by ABI filter (not a runtime crash).

**Pass:** FFmpeg command executes (returns RC 0 on a probe); no `UnsatisfiedLinkError` in logcat.

---

## 17. Hard subtitle embed (burn-in) 📱

**Prerequisites:** A completed download with a subtitle track; FFmpeg loaded.

**Steps:**
1. History → choose Embed → Hard (burn-in) → start.
2. `EmbedWorker` runs (foreground); wait for completion.
3. Play the output file in an external player with CC disabled.

**Expected result:** `libx264` (GPL) encodes the burned-in track; output file is larger than input; captions are visible in the video raster with CC off.

**Pass:** Embed completes with state COMPLETED; output plays; captions visible with CC disabled; FFmpeg RC 0.

---

## 18. Soft subtitle embed (mux) 📱

**Prerequisites:** A completed download with a subtitle track; FFmpeg loaded.

**Steps:**
1. History → choose Embed → Soft (mux) → start.
2. Wait for completion; play output in an external player.

**Expected result:** Subtitle stream is muxed into the container (not burned); external player offers a subtitle track selection; selecting it shows captions; with CC off no captions render.

**Pass:** Embed completes; output container has a text/subtitle stream; external player can toggle it.

---

## 19. Playback position restore 🤖

**Prerequisites:** A partially watched video (play ~20 s, leave Preview).

**Steps:**
1. Play a video to ~20 s. Leave Preview (ON_STOP pauses + persists position via `RoomPlaybackPositionStore`).
2. Reopen the same item from History → Preview.
3. Complete a video (>95%) and reopen.

**Expected result:** Reopen seeds a seek to the saved position (within ±2 s); completed videos are marked completed and do NOT resume (start from 0 or show "watched" state).

**Pass:** Partial-watch reopen resumes near the saved position; completed video reopen does not resume.

---

## 20. App restart recovery (incl. pending transcriptions) 📱

**Prerequisites:** An in-progress download AND an in-progress/pending transcription (Whisper) when the app is killed.

**Steps:**
1. Enqueue a download and a transcription job.
2. Force-stop the app (or reboot the device) mid-job.
3. Relaunch. Inspect History and Subtitles tabs.

**Expected result:** In-progress downloads resume or retry from `ResumeDecisionStore`; pending transcriptions (durable `pending_transcriptions` table, DB v5) are re-enqueued by `AutoTranscribeObserver` on next launch; no job is silently lost; a failed job shows an error state with Retry.

**Pass:** After restart, every pre-kill job is visible in a recoverable state (ACTIVE/PAUSED/QUEUED/FAILED-with-Retry); `pending_transcriptions` rows are re-enqueued; no orphaned/ghost progress.

---

## Summary log (fill in on device)

| # | Item | Device | API | Result (PASS/FAIL) | Notes |
|---|------|--------|-----|--------------------|-------|
| 1 | Startup | | | | |
| 2 | Navigation | | | | |
| 3 | Hilt DI | | | | |
| 4 | Room init | | | | |
| 5 | Migrations 3→4, 4→5 | | | | |
| 6 | WorkManager init | | | | |
| 7 | Background tasks | | | | |
| 8 | Download notifications | | | | |
| 9 | Foreground service | | | | |
| 10 | Storage access | | | | |
| 11 | Media3 playback | | | | |
| 12 | Audio track | | | | |
| 13 | Subtitle track | | | | |
| 14 | External subtitles | | | | |
| 15 | Arabic RTL | | | | |
| 16 | FFmpeg native load | | | | |
| 17 | Hard subtitle embed | | | | |
| 18 | Soft subtitle embed | | | | |
| 19 | Position restore | | | | |
| 20 | App restart recovery | | | | |
