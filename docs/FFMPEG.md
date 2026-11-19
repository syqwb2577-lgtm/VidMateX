# FFmpeg Integration Notes — VidMateX (`:media:processor`)

Scope: dependency provenance, native/runtime requirements, the argument-vector security
policy, the feature support matrix, and known limitations for every FFmpeg operation in
`:media:processor`. Host validation evidence: [`ffmpeg_validation_output.txt`](./ffmpeg_validation_output.txt)
produced by [`../scripts/validate_ffmpeg_args.sh`](../scripts/validate_ffmpeg_args.sh).

> **Validation status (honest):** argument vectors were executed on the **host ffmpeg
> 4.4.2** build (all scenarios PASS, see §7 and `TESTING_GUIDE.md` §7). The Android
> native library itself was **not executed** in this environment (no Android SDK/device);
> Kotlin unit tests are source-ready and run under `./gradlew testDebugUnitTest` in CI.

---

## 1. Dependency provenance

### 1.1 Table

| Item | Retired (removed) | Selected (active) | Verified alternative (documented, not used) |
|---|---|---|---|
| Maven coordinate | `com.arthenica:ffmpeg-kit-full-gpl:6.0-2` | `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` | `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` |
| Artifact URL | retired; old repo `https://maven.arthenica.com/repository/public/` (removed from `settings.gradle.kts`) | https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full-gpl/8.1.9/ | https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full/8.1.9/ |
| Metadata | unavailable (prebuilt distribution ended April 2025) | https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full-gpl/maven-metadata.xml — latest/release `8.1.9`, `lastUpdated` 2026-09-26 | https://repo1.maven.org/maven2/dev/ffmpegkit-maintained/ffmpeg-kit-full/maven-metadata.xml — latest/release `8.1.9`, `lastUpdated` 2026-09-26 |
| Main artifact size | n/a | AAR `ffmpeg-kit-full-gpl-8.1.9.aar` = 37,085,788 bytes (2026-09-25 15:21) | AAR `ffmpeg-kit-full-8.1.9.aar` = 31,057,330 bytes (2026-09-25 15:21) |
| Java package | `com.arthenica.ffmpegkit.*` | **unchanged** `com.arthenica.ffmpegkit.*` (drop-in) | unchanged `com.arthenica.ffmpegkit.*` |
| License (POM) | GPL-3.0 (GPL build; x264/x265) | **GPL-3.0** primary + LGPL-3.0 for permissive components (POM `licenses`; description: "plus GPL encoders (x264/x265/xvidcore/vid.stab). GPL-3.0 — copyleft applies to your app") | **LGPL-3.0** (POM description: "combined (no GPL)"); **does not ship `libx264`/`libx265`** |
| Build status | EOL — Arthenica retired prebuilt FFmpegKit distributions April 2025 | Actively maintained fork (Jokobee); FFmpeg 8.1 LTS line; Android NDK r27c; SDK 35; 16 KB page-size support; sources jar published | Same maintainer, LGPL flavor |
| Verified date | — | 2026-11-19 (metadata + POM + AAR listing + sources jar inspected) | 2026-11-19 (same inspection) |
| Native ABIs (`jni/`) | armeabi-v7a, arm64-v8a, x86, x86_64 (4) | **`arm64-v8a` + `x86_64` only (2)** — verified 2026-11-19 by unzipping the AAR: 10 `.so` per ABI, **no `armeabi-v7a`, no `x86`**. 32-bit devices cannot run the app; add explicit `ndk.abiFilters += listOf("arm64-v8a", "x86_64")` in `app/build.gradle.kts` or switch to a 32-bit-capable build. | same 2-ABI set |
| Transitive dependency | `com.arthenica:smart-exception-java` (old repo) | `com.arthenica:smart-exception-java:0.2.1` — resolved from **Maven Central**: https://repo1.maven.org/maven2/com/arthenica/smart-exception-java/0.2.1/ | same |

### 1.1.1 Hard ban: retired Arthenica Maven coordinates

The following **Maven coordinates** MUST NOT appear in any `build.gradle.kts` or
`gradle/libs.versions.toml` entry (they are retired/EOL and absent from Maven Central):

* `com.arthenica:mobile-ffmpeg-full-gpl`, `com.arthenica:mobile-ffmpeg-full`,
  `com.arthenica:mobile-ffmpeg-min-gpl`, and the rest of the legacy `mobile-ffmpeg-*`
  artifact family;
* `com.arthenica:ffmpeg-kit-full-gpl:6.0-2` and every other `com.arthenica:ffmpeg-kit-*`
  6.x artifact (prebuilt distributions retired April 2025).

Distinction that must not be confused: the maintained fork keeps the **Java package
name** `com.arthenica.ffmpegkit.*` (the `import` lines in `FFmpegProcessorImpl.kt`). A
Java package name is not a Maven coordinate; those imports are required and allowed.
Only the retired group/artifact coordinates above are banned. The active dependency is
catalog alias `ffmpeg-kit-full-gpl-maintained` →
`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`; this file never edits the catalog.

### 1.2 Selection decision (why `-full-gpl` and not the LGPL `-full`)

The maintained group publishes two equivalent Java-API AARs. Re-verification of the
native build banners inside each AAR (strings of `libffmpegkit.so`, arm64-v8a) showed:

* `ffmpeg-kit-full` (LGPL, "no GPL") configure line: **no** `--enable-libx264`,
  **no** `--enable-libx265`; available H.264 encoders are `libopenh264`, `h264_mediacodec`,
  `mpeg4`. It cannot execute the app's hard-burn vector (`-c:v libx264 -crf 18 -preset medium`).
* `ffmpeg-kit-full-gpl` (GPL) configure line: `--enable-gpl --enable-libx264
  --enable-libx265 --enable-libass --enable-libmp3lame`; it is the POM-described
  "drop-in replacement for com.arthenica:ffmpeg-kit-full-gpl" and preserves:
  1. the required hard-burn encoder/quality vector (`libx264 crf 18 preset medium`);
  2. the project's existing GPL-3.0 licensing posture (`LICENSE`, `docs/DISTRIBUTION_CHECKLIST.md`);
  3. the same Java API (`com.arthenica.ffmpegkit.*`) with zero Kotlin signature changes.

The LGPL AAR remains a documented option for a future relicensing effort: switching to
`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` additionally requires replacing the
x264 burn vector with a supported encoder (e.g. `libopenh264` bitrate-controlled) and
re-reviewing distribution obligations; tracked as a future option, not performed here.

### 1.3 Java API verification (sources jar `ffmpeg-kit-full-gpl-8.1.9-sources.jar`)

All APIs used by `:media:processor` exist in 8.1.9 (both flavors share identical Java
sources, 42 files):

| Usage in code | Maintained 8.1.9 API |
|---|---|
| `FFmpegKit.executeWithArgumentsAsync(array) { … }` | `FFmpegSession executeWithArgumentsAsync(String[], FFmpegSessionCompleteCallback)` |
| `FFmpegKit.executeWithArguments(array).returnCode` (Whisper split) | `FFmpegSession executeWithArguments(String[])`; `Session.getReturnCode()` |
| `FFmpegKit.cancel(session.sessionId)` | `static void cancel(long sessionId)` |
| `FFmpegKitConfig.enableStatisticsCallback { stats -> … }` | `static void enableStatisticsCallback(StatisticsCallback)` (`@FunctionalInterface`) |
| `stats.time` | `double Statistics.getTime()` — **seconds** in 8.1.9 (the retired 6.0 reported long milliseconds); `FFmpegProcessorImpl` converts to ms (`(time * 1000).toLong()`) |
| `ReturnCode.isSuccess(rc)` | `static boolean isSuccess(ReturnCode)`; `ReturnCode.getValue()` |
| `session.allLogsAsString` | `String AbstractSession.getAllLogsAsString()` |
| `session.sessionId` | `long AbstractSession.getSessionId()` |

`com.arthenica.ffmpegkit` imports are intentional (fork package name retained); the
`app/proguard-rules.pro` keep rules for `com.arthenica.ffmpegkit.**` and
`com.arthenica.smartexception.**` remain valid.

---

## 2. Native build / runtime requirements

* **minSdk:** 24 (`media/processor/build.gradle.kts` `defaultConfig { minSdk = 24 }`);
  AAR built targeting Android SDK 35, NDK r27c, 16 KB page-size aware.
* **ABIs shipped by the AAR:** `arm64-v8a`, `x86_64` only (verified from the AAR
  `jni/` listing). There is **no `armeabi-v7a` and no `x86`** archive: 32-bit ARM
  devices will not load native libraries; operations then fail through the normal error
  path (last FFmpeg log line) and the Whisper splitter falls back to single upload.
  Consider ABI splits / `abiFilters` if 32-bit support becomes a requirement.
* **Key components present (GPL build banner):** `libass` (SRT/ASS hard burn),
  `libmp3lame` (MP3 extraction/Whisper chunks), `libx264` (burn-in), `libx265`,
  `libfontconfig` + `libfreetype` + `libfribidi` + `libharfbuzz` (font matching/shaping),
  `mov_text` MP4 subtitle encoder, AAC encoder, WebVTT encoder.
* **Arabic / RTL text:** libass shapes text through fontconfig; Arabic glyphs render
  only when a font with Arabic coverage (e.g. Noto Sans Arabic) exists on the device.
  `EmbedStyle.fontName` defaults to `"Sans"`; a device without Arabic-capable sans
  falls back to tofu boxes. The app should ship or select an Arabic font before
  promising Arabic hard burn on all devices (known limitation §6).
* **Subtitle format support for hard burn (`subtitles=` filter, libass):**
  * **SRT** — direct; `charenc=UTF-8` set in the filter so UTF-8 Arabic/CJK decode.
  * **ASS** — direct (style overrides merged via `force_style`).
  * **VTT** — **not accepted directly** by libass. `SubtitleParser.convert(VTT → SRT/ASS)`
    must run before `burnHardSubtitles`; the host validation converts a VTT fixture to
    SRT and burns that (scenario 4b).
* **Soft embed:** `mov_text` subtitles are MP4-only; callers pass `.mp4` output.
  Input streams are remuxed (`-c:v copy -c:a copy`); the SRT/ASS sidecar is converted
  to `mov_text` by FFmpeg.

---

## 3. Security argument policy

1. **Array vectors only.** Every FFmpeg invocation builds `List<String>` through
   `FFmpegArgs` and calls `executeWithArguments(String[])`/`executeWithArgumentsAsync(...)`.
   There is no shell, no `bash -c`, and no string concatenation of user input into a
   command line (enforced by `FFmpegArgsTest.no shell metacharacters ever appear`).
2. **Allowlisted tokens.** Fixed literals only for options/encoders/presets/log levels;
   CRF is clamped to `0..51`; presets come from the `Preset` enum; log levels from the
   `LogLevel` enum. App-controlled values: input/output paths and `EmbedStyle` fields
   (numeric sizes/widths, enum alignment, ASS colour strings, font name).
3. **Filter-path escaping.** Paths embedded in the filter graph pass through
   `FFmpegArgs.escapeFilterPath` (backslash doubling, single-quote escape, colon escape)
   before single-quote wrapping.
4. **No secret material.** API keys/endpoints never enter FFmpeg vectors; error log
   lines are sanitized (`FFmpegProcessorImpl.sanitizeLogPaths`): absolute paths are
   reduced to basenames so failure messages expose only app-controlled temp file names.
5. **Failure surface:** non-zero `ReturnCode` maps to `FFmpegError.NonZeroExit(exitCode,
   sanitizedLog)`; linkage/initialization failures (missing `.so`/ABI) map to
   `FFmpegError.NativeUnavailable`; cancellation propagates as a coroutine
   `CancellationException` (never converted into a success/error result); insufficient
   free space maps to `FFmpegError.InsufficientStorage(required, available)`. Typed
   errors become `AppResult.Error(message, recoverable)`; the pure mapper
   (`FFmpegError.fromReturnCode` / `fromThrowable`) is unit tested in `FFmpegJobModelTest`.
6. **Job model (pure):** `FFmpegJob(operation, inputs, output, options)` with options
   `burnCodec`, `crf`, `preset`, `audioBitrateKbps`, `segmentSeconds`, `softSubtitleCodec`,
   `languageTag`, `targetContainer`. `FFmpegJobPlanner.vector(job, style)` turns the job
   into the `List<String>` vector; the implementation never hand-builds a second vector.
7. **Storage pre-check (pure helper + device query):** `StorageEstimate.fits(available,
   estimated, slack)` compares usable bytes against a conservative estimate plus a
   64 MiB slack; estimators cover MP3 (`seconds × kbps / 8`), stream-copy remux/mux
   (≈ source bytes), hard burn (1.1× ceiling), and segment splits (≈ source bytes). The
   device layer reads `File.parentFile.usableSpace`; if the size cannot be queried the
   check fails open rather than blocking a legitimate job. Pure math: `FFmpegJobModelTest`.
8. **Native release path (no handle leaks):** the statistics callback is registered once
   in `init` and retains no per-session object; completed `FFmpegSession` instances are
   not stored. Cancellation (`invokeOnCancellation` or public `cancelActive()`) calls
   `FFmpegKit.cancel(sessionId)` and clears the active id. Whisper chunk files are deleted
   in a `finally` block; ML Kit translator clients are closed in `finally`
   (`MlKitTranslator`). No `FFmpegSession` handle is cached across jobs.
9. **Large-file / streaming posture:** vectors use stream copy (`-c copy`) for remux,
   mux and segment splits; MP3 extraction and Whisper chunks re-encode in a streaming
   pipeline; the segment muxer (`-f segment -segment_time N`) produces parts instead of
   buffering a whole file. No whole-file byte buffers are held in this module; OkHttp
   request bodies stream `File` contents (`asRequestBody`), and STT chunk uploads send
   parts, never the original file in memory.
10. **No fabricated native success:** unit tests assert tokens, parsers, mappers and
    policy only; no test mocks a successful FFmpegKit/ML Kit call as proof of device
    functionality. Native execution evidence can only come from a device/CI run.

---

## 4. Feature support matrix

| Feature | Builder / vector | Status | Evidence |
|---|---|---|---|
| Extract audio to MP3 (192 kbps, video dropped) | `extractAudioToMp3` → `-vn -c:a libmp3lame -b:a 192k` | Implemented; host-validated | Scenario 1; `FFmpegArgsTest.extract audio vector…` |
| Soft embed (selectable `mov_text` track) | `embedSoftSubtitles` → `-map 0 -map 1 -c:v copy -c:a copy -c:s mov_text -movflags +faststart` (+`language=` metadata) | Implemented; host-validated (mov_text stream present) | Scenario 2; soft-embed test |
| Hard burn SRT | `burnHardSubtitles` → `subtitles=…:charenc=UTF-8` + `force_style` + `libx264 -crf 18 -preset medium -c:a copy` | Implemented; host-validated (no subtitle stream in output) | Scenario 3; hard-burn SRT test |
| Hard burn ASS | same vector, `.ass` input | Implemented; host-validated | Scenario 4; ASS-source test |
| Hard burn VTT | parser VTT→SRT/ASS conversion first; filter receives converted file | Implemented in parser contract; conversion fixture host-validated; direct VTT burn unsupported by libass (by design) | Scenario 4b; parser tests |
| Arabic text | `charenc=UTF-8` + `FontName` shaping; Arabic cue in SRT/ASS/VTT fixtures | Vector complete; glyph rendering depends on device Arabic font | Scenarios 3/4/4b fixtures include Arabic lines |
| UTF-8 decoding | `charenc=UTF-8` in filter; SRT parser strips BOM | Implemented; asserted in tests | hard-burn test |
| Font size | `force_style=Fontsize=<EmbedStyle.fontSize>` | Implemented; asserted | hard-burn test (`Fontsize=28`) |
| Font colour | `PrimaryColour`; outline `OutlineColour`; background `BackColour` | Implemented; asserted | hard-burn test (keys present) |
| Outline | `Outline=<outlineWidth>` | Implemented; asserted | hard-burn test |
| Alignment / position | `Alignment=<alignment>`, `MarginV=<marginV>` | Implemented; asserted | hard-burn test |
| Quality preservation — stream copy | soft embed & mux use `-c:v copy -c:a copy`; audio extracted only for MP3 | Implemented; host-validated (copy paths) | Scenarios 2/5 |
| Quality preservation — burn encode | `libx264 -crf 18 -preset medium` (visually near-lossless) | Implemented in vector; host-validated on ffmpeg 4.4.2 | Scenarios 3/4 |
| Progress reporting | statistics callback → `progressPercent` (coarse 5–95 ramp, 100 on success) + `lastEncodingTimeMs` | Implemented; on-device values unprofiled (§6) | code inspection; existing report notes |
| Error handling | last sanitized log line; exit code; cancel on coroutine cancellation; STT split failure → single-upload fallback | Implemented; unit coverage for parser/sanitizer logic | code; STT fallback comment |
| Whisper chunking (large files) | `-vn -acodec libmp3lame -b:a 64k -ar 16000 -ac 1 -f segment -segment_time 600 chunk_%03d.mp3` | Implemented; host-validated on 12 s fixture (≥1 MP3 chunk) | Scenario 6 |
| Mux separate video/audio | `-map 0:v -map 1:a -c:v copy -c:a copy -shortest` | Implemented; host-validated | Scenario 5; mux test |
| Remux container change (stream copy) | `remuxContainer` → `-c copy -f <mp4\|matroska\|webm>`; unsupported extension rejected before FFmpeg | Implemented; host-validated mp4→mkv | Scenario 7; `FFmpegJobModelTest.remux vector…` |
| Soft embed WebM/WebVTT | `softSubtitleCodecFor("webm")` → `-c:s webvtt`; MP4 stays `mov_text`; MKV `srt`; unknown containers rejected | Implemented; host-validated webm+webvtt | Scenario 8; `FFmpegArgsTest.softSubtitleCodecFor…` |
| Segment split by time (video/audio copy) | `splitByTimeSegment` → `-c copy -f segment -segment_time N -reset_timestamps 1 pattern` | Implemented; host-validated copy split | Scenario 9; `FFmpegJobModelTest.split vector…` |
| Job model + vector planner | `FFmpegJob`/`FFmpegJobOptions`/`FFmpegJobPlanner` pure; one vector per operation | Implemented; JVM-tested (not executed here) | `FFmpegJobModelTest` |
| Insufficient-storage pre-check | `StorageEstimate.fits(available, estimated, slack)` pure; device reads `usableSpace` | Implemented; JVM-tested math; device I/O unprofiled | `FFmpegJobModelTest.storage…` |
| Typed error mapping | `FFmpegError`: NonZeroExit / NativeUnavailable / Cancelled / InsufficientStorage / InvalidInput / UnknownFailure | Implemented; JVM-tested mapper | `FFmpegJobModelTest.typed errors…` |

---

## 5. Argument vectors (mirrored by the host validation script)

The script `scripts/validate_ffmpeg_args.sh` mirrors the exact token lists emitted by
the Kotlin builders and runs them under host ffmpeg 4.4.2:

* **Extract:** `-y -loglevel error -i video.mp4 -vn -c:a libmp3lame -b:a 192k audio.mp3`
* **Soft embed:** `-y -loglevel error -i video.mp4 -i en_ar.srt -map 0 -map 1 -c:v copy -c:a copy -c:s mov_text -metadata:s:s:0 language=ar -movflags +faststart soft.mp4`
* **Hard burn SRT/ASS:** `-y -loglevel error -i video.mp4 -vf subtitles='<file>':force_style='…':charenc=UTF-8 -c:v libx264 -crf 18 -preset medium -c:a copy hard.mp4`
* **Mux:** `-y -loglevel error -i video.mp4 -i audio.m4a -map 0:v -map 1:a -c:v copy -c:a copy -shortest mux.mp4`
* **Whisper chunk:** `-y -i chunk_src.wav -vn -acodec libmp3lame -b:a 64k -ar 16000 -ac 1 -f segment -segment_time 600 chunk_%03d.mp3`
* **Remux (stream copy):** `-y -loglevel error -i in.mp4 -c copy -f matroska out.mkv` (`muxerForContainer`; `mp4`/`webm`/`matroska`)
* **Soft embed WebM/WebVTT:** `-y -loglevel error -i in.webm -i sub.vtt -map 0 -map 1 -c:v copy -c:a copy -c:s webvtt soft.webm` (no `+faststart`; that flag is MP4-only)
* **Segment split (copy):** `-y -loglevel error -i in.mp4 -c copy -f segment -segment_time 1 -reset_timestamps 1 chunk_%03d.mp4`
* **Planner seam:** all production vectors pass `FFmpegJobPlanner.vector(job, style)`; the host script mirrors the same tokens, and `FFmpegJobModelTest` asserts exact lists on the JVM.

Pass/fail per scenario and the raw console transcript: `docs/ffmpeg_validation_output.txt`
(41 `RESULT:PASS`, 0 `RESULT:FAIL` on 2026-11-19, host ffmpeg 4.4.2-0ubuntu0.22.04.1;
scenarios 7–9 added in the media-processing completion pass).

---

## 6. Known limitations

1. **Coarse progress:** `progressPercent` ramps from encoding seconds against an assumed
   600-second baseline; exact 0–100% requires probing output duration (not done).
   `lastEncodingTimeMs` comes from statistics (`getTime()` seconds × 1000).
2. **No device/CI execution in this environment:** no Android SDK, no JDK 17, no Gradle
   binary; Kotlin sources and JUnit4 tests are source-ready but were not compiled or run
   here, and the native AAR was not loaded. Host validation covers argument **vectors and
   host-ffmpeg behavior**, not JNI loading, fontconfig on-device, ML Kit models, network
   services, or WorkManager scheduling. Pure logic tests (vectors, parsers, mappers,
   storage math, adapter policy) require only a plain JVM once Gradle/JDK are available.
3. **ABI coverage:** `arm64-v8a` + `x86_64` only; 32-bit ARM (armeabi-v7a) devices lack
   natives and receive the typed `NativeUnavailable` error rather than a crash.
4. **Arabic font dependency:** rendering requires an Arabic-coverage font on device;
   `Sans` may tofu on some devices.
5. **GPL-3.0 copyleft (selected build):** `libx264`/`libx265` are GPL; distributing the
   APK under `ffmpeg-kit-full-gpl` carries GPL source-disclosure obligations (see
   `docs/DISTRIBUTION_CHECKLIST.md` and root `LICENSE`). The LGPL `ffmpeg-kit-full`
   alternative (§1.2) removes those obligations but requires replacing the x264 burn
   vector; it was not adopted in this release.
6. **Version drift:** host ffmpeg 4.4.2 vs AAR FFmpeg 8.1; filter syntax used here is
   stable across both, but pixel-level encode output and encoder defaults differ.
7. **VTT burn-in is two-step:** parser conversion precedes filtering; callers must not
   pass a `.vtt` path to `burnHardSubtitles`.
8. **Storage estimates are conservative ceilings:** the pre-check fails fast on obviously
   insufficient space but may over-estimate; it never rejects on unknown `usableSpace`
   (fail-open). Exact output sizes require a completed encode or a container probe.
9. **Platform adapters are metadata-only:** YouTube/Facebook/TikTok resolution delegates
   to an optional, operator-configured backend; the device never scrapes pages, runs
   yt-dlp, or fabricates stream URLs (see `source/adapters` `SourceCompliance`).

---

## 7. Validation run summary (host)

* Script: `scripts/validate_ffmpeg_args.sh` (bash, `set -o pipefail`, mktemp workdir,
  automatic cleanup).
* Fixtures: 2 s 320×240 `testsrc` + silent AAC; English+Arabic SRT; WebVTT; ASS;
  12 s 440 Hz sine for chunking.
* Scenarios: (1) MP3 extraction, (2) soft embed mov_text, (3) hard burn SRT,
  (4) hard burn ASS, (4b) VTT→SRT conversion burn, (5) mux copy/shortest,
  (6) 16 kHz mono MP3 segment split, (7) remux stream copy mp4→mkv,
  (8) WebVTT soft embed into WebM, (9) video segment split via stream copy.
* Result: **41 PASS / 0 FAIL**, overall exit 0; transcript (including a scope/reconciliation
  section) in `docs/ffmpeg_validation_output.txt`. Android device run: pending CI/device;
  native ABI execution, ML Kit and network services remain unverified externally.
