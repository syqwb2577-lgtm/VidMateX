# Whisper Setup — Speech-to-Text Endpoint

VidMateX includes a real `WhisperSpeechToText` implementation that talks to any
**OpenAI-compatible** speech-to-text endpoint, plus a WorkManager
`TranscriptionWorker` that turns a local audio/video file into SRT **and** VTT
subtitle files. The app ships with **no endpoint and no API key** — you configure one
in **Settings → Whisper endpoint** (and optionally **Whisper API key**). Until a
valid endpoint is configured, speech-to-text reports unavailable
(`SettingsAwareSpeechToText.isAvailable()` = false) and `transcribe()` returns a
clear recoverable `NoOpSpeechToText` error; all other features work normally.

> **Status:** App-side code complete — multipart upload, >24 MB chunking, SRT parse,
> progress forwarding, WorkManager worker, SRT+VTT output, retry/error mapping, and the
> subtitles-preview/translation/download chain are implemented as source. **Not
> executed on a device in this build environment** (no Android SDK/Gradle here);
> static source-only changes. Unit tests for the pure helpers are source-ready and
> pending CI (`./gradlew :feature:subtitles:testDebugUnitTest`).

---

## 0. End-to-end app workflow

1. **Configure (Settings tab).** Open **Settings → Whisper endpoint** and enter an
   OpenAI-compatible base URL (HTTPS recommended). Add a **Whisper API key** only
   when the provider requires one. Use **Test connection** for a lightweight
   reachability probe (no key attached to the probe).
2. **Open the Subtitles tab.** When the endpoint is valid, the Speech-to-text card
   shows the language dropdown and the **Transcribe audio or video file** button.
   When it is not configured, a guidance card points to Settings and no transcription
   control is activated.
3. **Pick a file.** The button launches the system file picker (`OpenDocument`,
   MIME filters `audio/*` and `video/*`). The granted URI is copied into app-private
   cache (`cacheDir/transcription_in/<jobId>.<ext>`) — the worker only reads absolute
   file paths, and a picker grant may not survive a worker restart.
4. **Choose the spoken language.** Dropdown: **Auto-detect** (default) plus
   `en / ar / fr / es / de / tr / zh`. Auto-detect omits the Whisper `language`
   form field so the server infers the language. An explicit code is sent as
   `language=<code>`.
5. **Worker runs.** `TranscriptionWorker` (`@HiltWorker`, `CoroutineWorker`, tag
   `vidmatex_transcription`) calls `SpeechToText.transcribe(path, lang)`. The
   progress card shows percentage (`progress`) and stage (`preparing`,
   `transcribing`, `writing`). Constraints: network connectivity; unmetered/Wi-Fi
   only when Settings → require Wi-Fi is enabled.
6. **Outputs.** On success the worker writes both files under
   `filesDir/transcriptions/<jobId>/`: `<jobId>.srt` and `<jobId>.vtt` (project
   `SubtitleParser.write(SRT/VTT, cues)`); success data carries `srt_path`,
   `vtt_path`, `cue_count`.
7. **Into the subtitle chain.** The ViewModel observes the worker by tag, parses the
   SRT with `SubtitleParser.parse(SRT, text)`, installs a synthetic `AUTOMATIC`
   subtitle track, populates `cues`, and enables the existing flows:
   preview → optional ML Kit / LibreTranslate translation (`applyTranslation`) →
   format/shift → `download()` serialization into `DownloadSpec` (translated > original
   priority) → soft/hard embed handoff.

**Post-download hook (not auto-wired).** A future enhancement can enqueue
`transcribeLocalFile(path, null)` when an audio-only download completes with a local
file path. The downloads module does not currently expose its completed file path to
this module; the follow-up is recorded in `_spec/CHANGES_REQUESTED.md` (§C2).

### Worker data contract

| Direction | Key | Type | Meaning |
|---|---|---|---|
| Input | `audio_path` | String | Absolute local file path (copied to cache for picker files) |
| Input | `source_lang` | String | ISO code; `""` = auto-detect (Whisper `language` omitted) |
| Input | `job_id` | String | Caller job id; output directory namespace |
| Progress | `progress` | Int 0..100 | Forwarded from the STT progress callback |
| Progress | `stage` | String | `preparing` / `transcribing` / `writing` |
| Output (success) | `srt_path` | String | Absolute SRT file path |
| Output (success) | `vtt_path` | String | Absolute VTT file path |
| Output (success) | `cue_count` | Int | Number of parsed cues |
| Output (failure) | `error_message` | String | Friendly, non-sensitive message |
| Output (failure) | `error_kind` | String | Retry-policy kind name |
| Output (failure) | `http_code` | Int | Server status, `-1` when none |

### Retry / error mapping

- **Retryable** (`Result.retry()`, WorkManager exponential backoff, **30 s** base
  interval, at most **3** transient attempts): `IOException`,
  `SocketTimeoutException`, `UnknownHostException`, HTTP **429**, HTTP **5xx**.
- **Permanent** (`Result.failure()`): missing/unreadable/bad local file; HTTP **4xx**
  except 429 (401/403 auth, 400/404 bad request); empty response body; zero
  recognizable cues; endpoint no longer configured.
- Secrets never appear in worker data or error messages; server error snippets are
  redacted (long token-like strings replaced) before surfacing.

---

## 1. Endpoint shape (what the app expects)

The app POSTs to `{endpoint}/v1/audio/transcriptions` (if your endpoint already
includes the path, it is used as-is; normalized by
`WhisperSpeechToText.buildTranscriptionUrl`).

**Request** (`multipart/form-data`):

| Field | Value |
|---|---|
| `file` | Audio bytes (filename included). If >24 MB, split into ~600-second chunks via FFmpegKit and each chunk transcribed with offset timestamps. |
| `model` | `whisper-1` |
| `response_format` | `srt` |
| `language` | Source language code if explicit; **omitted** (auto-detect) when the dropdown is Auto. |

**Header:** `Authorization: Bearer <apiKey>` — only sent when a non-blank API key
is configured. Never logged.

**Response:** SRT subtitle text, parsed into `List<SubtitleCue>` by the app.

Availability rule (`WhisperEndpointValidator`): the endpoint must be non-blank, a
parseable URL with a host, and use `https://`; plain `http://` is accepted only for
loopback development hosts (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2` emulator
host, `*.localhost`). Other schemes and plain-http public hosts report unavailable.

### 1.1 Settings / environment names and multipart contract

The Android app stores these in encrypted Settings (Keystore AES/GCM); the same
names appear as comments in `backend/.env.example` as app-side documentation —
**the resolver backend process does not read Whisper variables**.

| Setting / env name | Sent as | Notes |
|---|---|---|
| `WHISPER_ENDPOINT` | Base URL; final POST `{base}/v1/audio/transcriptions` | If the value already includes a path it is used as-is (normalized by `buildTranscriptionUrl`). `https://` required except loopback dev hosts. |
| `WHISPER_API_KEY` | Header `Authorization: Bearer <key>` | Sent only when non-blank; never logged, never included in worker data or error text. |

Multipart form fields the app sends (`multipart/form-data`):

| Field | Value |
|---|---|
| `file` | Audio/video bytes with filename; the worker copies the picked URI into app-private cache first. |
| `model` | `whisper-1` (OpenAI-compatible model name; self-host servers typically ignore/alias it). |
| `response_format` | `srt` (the app parses SRT cues and also emits VTT locally). |
| `language` | ISO code when the user picks a language; **omitted entirely** on Auto-detect so the server infers. |

Limits and retry behavior (what the app enforces before/around the request):

- **Single-upload ceiling: 24 MB** (`MAX_SINGLE_UPLOAD_BYTES`), staying under
  the common ~25 MB provider cap.
- **Large files:** >24 MB are re-encoded (FFmpegKit GPL fork) to 16 kHz mono
  64 kbps MP3 and split into **600-second (10-minute)** chunks
  (`CHUNK_DURATION_SEC`); each chunk is transcribed independently and cue
  timestamps are offset by the segment start before re-indexing.
- **Retryable transient failures:** `IOException`, `SocketTimeoutException`,
  `UnknownHostException`, HTTP **429**, and HTTP **5xx** — WorkManager
  exponential backoff, base **30 s**, at most **3** attempts.
- **Permanent failures (fail fast, no retry):** missing/unreadable file; HTTP
  **4xx** except 429 (401/403 auth, 400/404 bad request); empty body; zero
  recognizable cues; endpoint no longer configured.
- A rejected oversized segment after chunking surfaces a clear server-rejection
  error rather than a crash; network errors never include the API key.

---

## 2. Option A — Self-host faster-whisper-server (recommended)

[faster-whisper-server](https://github.com/fedirz/faster-whisper-server) is a
drop-in OpenAI-compatible Whisper server using `faster-whisper` (CTranslate2,
fast, low VRAM). It exposes `/v1/audio/transcriptions` exactly.

```bash
# With Docker (GPU):
docker run -d --gpus all -p 8000:8000 \
  fedirz/faster-whisper-server:latest-cuda \
  --model large-v3 --compute_type float16
# CPU-only (slower, but works on a VPS):
docker run -d -p 8000:8000 \
  fedirz/faster-whisper-server:latest-cpu \
  --model small --compute_type int8
```

Put it behind **Caddy / nginx / Traefik for HTTPS** (the app talks to your endpoint
over the network; use TLS). Then in the app:

- **Settings → Whisper endpoint**: `https://whisper.yourdomain.com/`
- **Settings → Whisper API key**: leave blank (or set one if you added auth to your
  reverse proxy).

Models: `tiny`, `base`, `small`, `medium`, `large-v3`. Larger = more accurate but
slower and more VRAM. `small`/`medium` is a good balance for a GPU VPS.

### Hardware guidance (approximate — verify on your instance)

CTranslate2/faster-whisper memory footprint depends on compute type and runtime;
figures below are planning approximations, not guarantees.

| Model | Rough params | int8 CPU RAM | fp16 GPU VRAM | Practical note |
|---|---|---|---|---|
| `tiny` | ~39 M | 0.3–0.5 GB | ~1 GB | Fastest; lower accuracy, short clips only |
| `base` | ~74 M | 0.5–0.8 GB | ~1 GB | Quick draft quality |
| `small` | ~244 M | ~1 GB | ~2 GB | Best CPU/quality balance; common self-host choice |
| `medium` | ~769 M | 2.5–3.5 GB | ~5 GB | Better accuracy; CPU slow for long audio |
| `large-v3` | ~1.55 B | 4–5 GB | ~10 GB | Best accuracy; prefer GPU; CPU usually slower than realtime |

- **CPU int8**: a modern multi-core x86 VPS transcribes `small` at roughly
  1–4× realtime depending on cores and RAM; `large-v3` on CPU is impractical for
  long files. Use swap only as a last resort (slow).
- **GPU**: a budget inference GPU (e.g. T4-class or consumer RTX) runs
  `small`/`medium`/`large-v3` faster than realtime. Match VRAM with the table above
  and leave headroom for the runtime and audio buffers.
- **whisper.cpp** quantization (`q5_1`, `q8_0`) trades a little accuracy for much
  lower RAM; see Option B.

---

## 3. Option B — Self-host whisper.cpp

[whisper.cpp](https://github.com/ggerganov/whisper.cpp) includes an
OpenAI-compatible server (`whisper-server`).

```bash
git clone https://github.com/ggerganov/whisper.cpp && cd whisper.cpp
bash ./models/download-ggml-model.sh small   # or base/medium/large-v3
cmake -B build && cmake --build build --config Release
./build/bin/whisper-server -m models/ggml-small.bin -p 8000 --convert
```

`--convert` auto-converts uploaded audio to the required WAV format. Put behind a
TLS reverse proxy. Endpoint in app: `https://whisper.yourdomain.com/`.

whisper.cpp runs on CPU (and has GPU backends) — good for a home server or a beefy
VPS. Quantized models (`q5_1`, `q8_0`) trade a little accuracy for much less RAM.

---

## 4. Option C — Paid OpenAI-compatible API

Any provider exposing `/v1/audio/transcriptions` works:

- **OpenAI** (`https://api.openai.com/`) — pay-per-minute; API key required.
- **Groq**, **DeepInfra**, **Together AI**, etc. — OpenAI-compatible Whisper
  endpoints, often faster and sometimes cheaper per minute.

In the app:

- **Whisper endpoint**: e.g. `https://api.openai.com/`
- **Whisper API key**: your key (stored encrypted on device; never logged).

### Approximate hosted cost (verify current console pricing before budgeting)

| Option | Approximate price | Approx. 1-hour audio | Notes |
|---|---|---|---|
| OpenAI `whisper-1` | ~$0.006 / minute (OpenAI model page lists Whisper transcription at $0.006) | ~$0.36/hr | Simple pay-as-you-go; 25 MB request cap handled by chunking |
| Groq / DeepInfra / other compatible hosts | Per-second or per-minute metering; commonly competitive with OpenAI for Whisper-class models, varies by model and tier | Check console | Free/rate-limited tiers may apply; speeds are often high |
| Self-host GPU spot VPS | Roughly $0.2–0.4 / GPU-hour (T4-class examples) | Only while running | Cheapest at steady volume; start/stop on demand |
| Self-host CPU VPS | Flat ~$5–20 / month depending on cores/RAM | Flat fee | Best for intermittent short jobs; large models slow |

Figures are planning estimates, not quotes; provider pricing and rate limits change
(OpenAI's Whisper model/pricing pages remain the authoritative source for the
$0.006/min list figure).

---

## 5. Chunking and large files

- **24 MB ceiling**: OpenAI-compatible servers commonly cap uploads near 25 MB; the
  app stays just below at **24 MB** (`MAX_SINGLE_UPLOAD_BYTES`).
- Files at or below the limit are uploaded as-is.
- Larger files are re-encoded by the maintained FFmpegKit GPL fork to **16 kHz mono
  64 kbps MP3** and split into fixed **600-second (10-minute)** segments
  (`CHUNK_DURATION_SEC`); each segment is transcribed separately and cue timestamps
  are offset by the segment start, then cues are re-indexed sequentially.
- If native FFmpegKit splitting fails on a device, the whole file is sent once and a
  clear server-rejection error is surfaced instead of crashing.
- Network failures, timeouts, 429 and 5xx responses retry with exponential backoff;
  permanent errors (bad file, 4xx auth, empty/zero-cue responses) fail fast.

---

## 6. Security

- **HTTPS in production.** The availability check requires `https://` except for
  loopback development hosts. A plain `http://` public endpoint reports unavailable;
  do not send API keys over cleartext public networks.
- **API key storage (encrypted on device).** The Whisper API key never ships in the
  APK and has no hardcoded default. It is persisted by the Settings module's secret
  store: Android Keystore hardware-backed AES/GCM/NoPadding 256-bit key
  (`AndroidKeystoreSecretCipher`); only Base64 GCM ciphertext is persisted, and the
  plaintext exists solely in memory at call time. A Keystore-unavailable device falls
  back to passthrough (documented degradation; legacy rows re-encrypt on next write).
- **Never logged.** HTTP logging interceptors are set to `NONE`; worker input data
  contains only path/language/job id (never the key); error messages redact long
  token-like strings and never echo the Authorization header.
- **Self-host hardening:** put the server behind a reverse proxy with auth or an
  internal VPN; an unauthenticated public Whisper endpoint can be abused by anyone
  who discovers the URL.
- **Key hygiene:** never commit a key to the repository; rotate a key if it is
  exposed; prefer per-device keys where the provider supports them.

---

## 7. What the app does with the transcription

The parsed SRT cues become subtitle cues you can translate, shift, and embed into the
video (soft or hard) like any other subtitle track:

1. Cues land in the existing preview pane; the installed track is marked
   `AUTOMATIC` with the explicit or detected language code.
2. Enable **Translate subtitles** to run the existing ML Kit / LibreTranslate flow
   on the transcribed cues.
3. Pick SRT/VTT/ASS output, apply timing shift if needed.
4. **Download** serializes cues into `DownloadSpec` (translated content preferred over
   original; shift applied before serialization); subtitle-only and video+subtitle
   modes are supported; soft/hard embed flows consume the resulting sidecar file.
5. Only the audio bytes for the configured job are sent to your configured endpoint;
   no transcription leaves the device otherwise.

### Known limits / non-goals

- **Audio-oriented input.** Whisper transcribes speech; music-only or silent files
  yield no cues and fail with the "no recognizable subtitles" message. The picker
  accepts `video/*`, but strict servers may reject non-audio containers; extracting
  the audio track locally before upload is a possible follow-up.
- **No live recording / streaming.** This release transcribes picked or downloaded
  files only; there is no microphone recording or realtime dictation.
- **Network required.** There is no on-device Whisper model in this release; the
  worker requires connectivity to the configured endpoint (Wi-Fi-only when
  `requireWifi` is on).
- **No diarization** (speaker labels) and no timestamps beyond cue start/end.
- File location: outputs remain app-private under `filesDir/transcriptions/<jobId>/`
  until a download/embed step exports them through the existing storage handoff.

---

## 8. Troubleshooting quick map

| Symptom | Likely cause / action |
|---|---|
| Card says endpoint not configured | Settings → enter valid HTTPS endpoint (and key if required) |
| 401 / 403 failure | API key missing, wrong, or rejected; check Settings key; no key logged in errors |
| Repeated network/timeout/5xx retries then failure | Endpoint unreachable or overloaded; check URL, network, server health; retries capped at 3 |
| Empty / zero-cue result | Silent or music-only file; unclear speech; try a clearer audio track or another model |
| Large file rejected after chunking | Server still rejects segments; lower bitrate/sample-rate server config or use a shorter segment |
| Progress stuck at queued | Wi-Fi-only constraint active without Wi-Fi, or WorkManager backoff; check Settings require-Wi-Fi |
