# History audit checklist (module-local, §9)

Scope: `feature/history/src/main/.../` (ViewModel, UiState, Screen, `HistoryFormat`) and
`HistoryFormatTest`. List data flows from `HistoryRepository` (`:core:common`) backed by
Room in `:core:database`; this module owns presentation formatting and actions.

## 1. Control → wiring matrix

| Control | Wired? | Evidence | Status |
|---|---|---|---|
| List from data layer | Yes | `viewModel.items.collectAsStateWithLifecycle()` over repository flow; no local fake list | IMPLEMENTED BUT NOT VERIFIED |
| Open (completed) | Yes | `openItem(item)` → ACTION_VIEW with type/extension intent; missing file → toast | IMPLEMENTED BUT NOT VERIFIED |
| Share | Yes | `shareItem(item)` → ACTION_SEND stream intent with chooser | IMPLEMENTED BUT NOT VERIFIED |
| Delete | Yes | `deleteItem(item)` → repository delete; list flow updates | IMPLEMENTED BUT NOT VERIFIED |
| Retry (failed/cancelled) | Yes | `retryItem(item)` → repository retry action; worker re-enqueues | IMPLEMENTED BUT NOT VERIFIED |
| Preview (completed) | Yes | `previewItem(item)` → nav argument to `VideoPreviewScreen` | IMPLEMENTED BUT NOT VERIFIED |
| Progress bar + percent | Yes | `HistoryFormat.percent(...)`; `LinearProgressIndicator` | COMPLETE AND VERIFIED (JVM for formatter) |
| Size / speed / ETA rows | Yes | `HistoryFormat.formatBytes`, `formatSpeed`, `formatEta`; `formatClock` added | COMPLETE AND VERIFIED (JVM for formatter) |
| Date grouping today/yesterday/older | Yes | `HistoryFormat.dateBucket` pure; `groupByDate` labels via resources | COMPLETE AND VERIFIED (JVM bucket tests) |
| Error expand/collapse | Yes | local `expanded` state per card; shows `item.errorMessage` | IMPLEMENTED BUT NOT VERIFIED |
| Empty state | Yes | `HistoryEmptyState` when list empty | IMPLEMENTED BUT NOT VERIFIED |
| Hardcoded toast literal removed | Yes | `"File not found on device"` → `R.string.history_toast_file_missing` | COMPLETE AND VERIFIED (source scan) |

## 2. `HistoryFormat` coverage (extended)

Pure, Android-free additions (except `java.util.Calendar`, `java.util.Locale`, JVM-safe):

- `formatClock(sec)`: null/≤0 → em dash; `m:ss` under an hour; `h:mm:ss` above.
- `dateBucket(createdAtMs, nowMs)`: `TODAY / YESTERDAY / OLDER` by calendar year +
  day-of-year; invalid/zero/future-negative values collapse to `OLDER`.

`HistoryFormatTest.kt`: 18 `@Test` total (11 pre-existing + 7 added):

| Added case | Method |
|---|---|
| Size tier boundaries (999 B / 1 KB / 1000 KB / 1.0 MB / 1000.0 MB / 1.0 GB) | `formatBytes tier boundaries` |
| Clock null/zero/negative dash | `formatClock null zero and negative show dash` |
| Clock minutes/hours (0:59, 1:00, 59:59, 1:00:00, 1:01:01) | `formatClock minutes and hours` |
| Same calendar day across morning/evening | `dateBucket sameCalendarDayIsToday...` |
| Previous calendar day = yesterday | `dateBucket previousCalendarDayIsYesterday` |
| Older days / zero / negative = older | `dateBucket olderDaysAndUnknownValuesAreOlder` |
| ETA 3599 s / 3600 s boundaries | `eta boundary just underAnHourAndExactlyHour` |

Existing tests untouched; existing tests preserved per environment rules.

## 3. Remaining / blocked

- Room flow emission, intents (open/share/preview), and repository retry require a device;
  instrumented coverage lives in the downloads/core shards, not here.
- File existence check at open time (`File(path).exists()`) uses app-private/SAF paths;
  SAF-content URIs rely on the resolver-provided URI — device QA item.
