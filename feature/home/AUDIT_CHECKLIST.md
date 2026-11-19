# Home audit checklist (module-local, §9)

Scope: `feature/home/src/main/kotlin/app/vidmatex/feature/home/` (ViewModel, UiState, Screen)
and its resources. Data comes from `DownloadManager.resolveTracks` (`:core:common`) and
`SourceRegistry` (`:source:adapters`); this module owns UI state and validation only.

## 1. Control → wiring matrix

| Control | Wired? | Evidence | Status |
|---|---|---|---|
| Link field (two-way) | Yes | `OutlinedTextField(value=state.link, onValueChange=viewModel::onLinkChange)` | IMPLEMENTED BUT NOT VERIFIED |
| Paste button + clipboard offer | Yes | `pasteFromClipboard()` reads clipboard; offer chip appears when clipboard URL exists | IMPLEMENTED BUT NOT VERIFIED |
| Analyze button | Yes; enabled only when link non-blank and not loading | `viewModel.analyze()` | IMPLEMENTED BUT NOT VERIFIED |
| Input validation before resolution | Yes | `resolveLink()` → `UrlValidator.isValidUrl`; invalid → error state; empty/blank rejected before network | COMPLETE AND VERIFIED (source audit; validator covered in core) |
| Resolved metadata card (title/author/duration/thumbnail) | Yes | renders `state.metadata` only after `resolveTracks` success; no fabricated rows | IMPLEMENTED BUT NOT VERIFIED |
| Detected source badge | Yes | `state.detectedSource`; informational chip (no action required) | IMPLEMENTED BUT NOT VERIFIED |
| Audio-only filter chip | Yes | `setAudioOnly`; disables video quality chips; hides subtitles block | IMPLEMENTED BUT NOT VERIFIED |
| Video quality chips | Yes | `selectQuality(track)`; selected comparison by `trackId`; disabled while audio-only | IMPLEMENTED BUT NOT VERIFIED |
| Audio quality chips | Yes | `selectQuality(track)`; bitrate sub-label | IMPLEMENTED BUT NOT VERIFIED |
| Subtitle Off + language chips | Yes | `selectSubtitle(null)` / `selectSubtitle(sub)` | IMPLEMENTED BUT NOT VERIFIED |
| Download mode selector (stream/queue) | Yes | `setMode(DownloadMode)` | IMPLEMENTED BUT NOT VERIFIED |
| Download button | Yes; enabled when `canDownload && !isEnqueuing` | `download()` picks selected track(s) and enqueues via manager | IMPLEMENTED BUT NOT VERIFIED |
| Loading spinner | Yes | `state.isLoading` | IMPLEMENTED BUT NOT VERIFIED |
| Error snackbar | Yes | `state.snackbarMessage` → `LaunchedEffect` shows bar; `dismissSnackbar()` clears | IMPLEMENTED BUT NOT VERIFIED |
| No hardcoded recent/fake items | Verified | Screen renders only `state.metadata`/lists produced by the manager; initial state empty; `HomeEmptyState` shown pre-analysis | COMPLETE AND VERIFIED (source audit) |

## 2. Fixes applied in this pass

| Item | Change | Evidence |
|---|---|---|
| Hardcoded English error literal in VM | Replaced with `context.getString(R.string.home_error_no_tracks)` | `HomeViewModel.kt` (resolveTracks empty branch) |
| Resource parity | `values`/`values-ar` key sets identical (parity diff empty) | `feature/home/src/main/res/values*/strings.xml` |

## 3. Remaining / blocked

- Resolution and enqueue paths require device/network against real source adapters; JVM
  unit coverage for this module was not requested and there is no pure-logic class to
  extract beyond existing validator reuse.
- Clipboard behavior (API 24+) device-verified: consent toast on API < 31, direct read
  otherwise (existing implementation; no change here).
