# feature/downloads — Internal Audit Checklist

Scope: `feature/downloads/` (main, test, `build.gradle.kts`, `DownloadModule.kt`).
Environment constraint: no Android SDK / JDK 17 / Gradle in this environment → Kotlin
is source-reviewed and policy math is covered by pure JUnit-4 tests that are **source-ready
(not executed)**. Runtime worker behavior is labelled *Implemented but not verified*.

Legend: ✅ covered · 🟡 implemented, device-verification pending · ⛔ blocked / cross-module request.

## 1. Worker (`DownloadWorker.kt`, `EmbedWorker.kt`)
- [x] Deterministic worker IDs (`WorkIds.download/embed`); pause/cancel target them.
- [x] Range resume: probe → 206 APPEND / 200·416 RESTART / unsupported error (`ResumePolicy`).
- [x] Resume verdict persisted via `dao.updateResumable(...)` + process-local `ResumeDecisionStore`.
- [x] Cancellation: loop checks `isStopped`; `CancellationException` rethrown; temps cleaned.
- [x] Retry: `RetryPolicy.classify`; `Result.retry()` only for network/timeout/429/5xx; embed capped.
- [x] Progress: bytes, total (Content-Length / Content-Range), smoothed speed, ETA → DAO + `setProgress` + notification.
- [🟡] Integrity: size vs `Content-Length`/`Content-Range`; `Content-MD5` verification; failures → FAILED (not completed).
- [🟡] Pre-network URL validation of spec URL and every resolved stream URL (`DownloadInputValidator`).
- [🟡] Response `Content-Type` allowlist (video/audio/octet-stream); unsupported → permanent error.
- [x] Foreground info carries `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on API 34+ (`NotificationHelper`).

## 2. Manager (`DownloadManagerImpl.kt`)
- [x] Kind-aware dedup: in-process guard key + DAO active/queued query (URL + quality + kind).
- [🟡] Pre-enqueue validation: invalid / private-IP / non-HTTPS / platform-metadata-only → typed `AppResult.Error`, no DB row, no fake progress.
- [🟡] WorkManager constraints: network connected; unmetered when `requireWifi`; unique work names (KEEP policy).
- [x] Pause keeps partial bytes; cancel deletes temps; retry clears non-resumable temps; backoff 10 s exponential.
- [x] Hilt binding `DownloadManagerImpl` → `DownloadManager` interface unchanged.

## 3. Pure policies (JVM-testable seams)
- [x] `ResumePolicy` (decision matrix), `RetryPolicy` (classification), `DownloadKinds` (kind/extension).
- [x] `DedupPolicy` (canonical URL + quality + kind; stable guard/dedup keys).
- [x] `FormatUtils` (speed/ETA/bytes formatters).
- [🟡] `DownloadInputValidator` (scheme allowlist, HTTPS, private/loopback rejection, platform compliance).
- [🟡] `FileNamePolicy` (control chars, traversal `..`, separators, executable-extension blocklist, length cap).
- [🟡] `SpeedEstimator` (EMA smoothed speed) + pure ETA helper.
- [🟡] `IntegrityPolicy` (size verdict; Content-MD5 parse/verify).
- [🟡] `FormatSelectionPolicy` (best video/audio fallback ordering; best-effort single item).
- [🟡] `WorkerInputPolicy` (worker input-data contract) + `RangeHeaderPolicy` (Accept-Ranges / Content-Range parse).

## 4. Storage (`FileStorageHelper.kt`, manifest)
- [x] Temp files only in `cacheDir`; cleanup policy keeps resumable stream partials, deletes derived temps.
- [x] Finalize: SAF tree (user-picked) → MediaStore Downloads (API 29+) → app-private external files dir (API < 29).
- [🟡] Legacy public-storage write replaced by app-specific `getExternalFilesDir(...)`; legacy storage permissions removed from module manifest.
- [x] No writes outside app/SAF/MediaStore locations; FileProvider serves app paths only.
- [x] Filenames sanitized at worker and finalize layers.

## 5. Notifications (`NotificationHelper.kt`, `DownloadActionReceiver.kt`)
- [x] Existing helper reused for progress/complete/failed/paused/processing; channels created.
- [x] Receiver actions: cancel (cleanup), pause (keep partials), resume (re-enqueue with persisted spec JSON).
- [⛔] `POST_NOTIFICATIONS` runtime request owned by app shell (`MainActivity`) — already present; no change here.

## 6. UI wiring (`DownloadsScreen.kt`, `DownloadsViewModel.kt`)
- [x] Screen observes real DAO state flow (percent, speed, ETA, bytes, error message).
- [x] Actions pause/resume/cancel/retry/delete/open/share wired through guarded VM calls.
- [🟡] Compliance note rendered for platform metadata-only links; invalid/permission/integrity errors shown as row error text.

## 7. Room wiring (`DownloadModule.kt`, `core/database`)
- [x] Registers `AppDatabase.MIGRATION_1_2`; **no** `fallbackToDestructiveMigration()`.
- [⛔] `MIGRATION_2_3` (integrity verdict column) not yet exposed by `:core:database`; request filed below.

## 8. Cross-module requests (not edited here)
1. `:core:database`: add `integrityVerdict TEXT` / `integrityMessage TEXT` on `downloads` and expose `MIGRATION_2_3`; `DownloadModule` will register it when available.
2. App shell strings: none blocked — new keys were added inside this module (`res/values`, `res/values-ar`) so no `app/src/main/res` edit was required.
3. Validators reused as-is: `:core:common` `UrlValidator.isValid/canonicalize/isLikelyPrivateHost`; `:core:network` `BackendUrlValidator` covers the resolver base URL (not direct media).
4. `:source:adapters` keeps platform adapters metadata-only (`downloadAvailable=false`); no scraping code added anywhere in this module.
