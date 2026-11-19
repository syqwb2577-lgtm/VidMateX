# VidMateX Privacy Policy

**Last updated:** 2026-11-19

> **Customize before publishing.** This policy describes the default build of
> VidMateX. If you add analytics, crash reporting, or your own server
> endpoints, update this policy and replace the contact address in §9 before
> distributing.

VidMateX is a local-first media downloader and subtitle tool. This policy
explains what data the app accesses, where it goes, and what the app never
does. The app has no account system and does not track users. **There is no
VidMateX-operated server**: no data is sent to "the authors", because no such
collection exists.

---

## 1. Data collected

**No personal data is collected by VidMateX itself.** There is:

- no account or login,
- no analytics SDK,
- no crash-reporting SDK (none is bundled),
- no advertising identifier collection,
- no telemetry or usage-event reporting.

Downloaded media files and their metadata remain **on your device** in the
folder you choose (Storage Access Framework / MediaStore). The app does not
upload them anywhere.

### Data held on-device (app-private storage)

| Data | Stored where | Purpose |
|---|---|---|
| Downloaded media files | User-selected folder (SAF / MediaStore) | Playback, share, delete by user action |
| Download history, queue, resume state | Local Room database (`app-private`) | Restore queue after app restart; list completed downloads |
| Non-secret settings (theme, language, quality defaults, require-Wi-Fi, embed mode) | DataStore Preferences | Persist user preferences |
| Secrets: resolver API key, Whisper API key | Dedicated encrypted DataStore file; Android Keystore AES/GCM/NoPadding, 256-bit key; only Base64 ciphertext at rest | Authenticate to user-configured endpoints |
| Endpoint settings (resolver base URL, Whisper endpoint, LibreTranslate URL) | DataStore Preferences | Remember configured services |
| Temporary download/embed files (`*.tmp`, cache) | App-private cache/files dir; auto-cleaned | In-flight processing |

---

## 2. Data flow — network paths

The app makes only network requests you initiate or explicitly configure:

1. **Direct media URLs.** When you paste a direct `.mp4`/`.m4a`/`.webm`/… URL,
   the device downloads the file directly from its origin server. No proxy or
   VidMateX server is involved.
2. **User-configured resolver endpoint (optional).** Platform links
   (YouTube/Facebook/TikTok) may be sent to a self-hosted resolver you enter in
   Settings. The included FastAPI backend returns metadata only by default
   (`downloadAvailable=false`) and never invents stream URLs. The optional
   `YTDLP_PATH` download branch is disabled by default and requires the
   operator's explicit opt-in. If a resolver API key is set, it is sent as an
   `X-API-Key` header over HTTPS. Before any outbound fetch, the backend
   validates the target URL (http/https only; private, loopback, link-local,
   CGNAT, and documentation IP ranges rejected) and revalidates every redirect
   hop through the same SSRF guard; this protects the operator's server, and
   no request is made to an internal or disallowed address.
3. **Subtitle translation (user-selected provider).**
   - **Google ML Kit (default):** translation runs **on-device**; no subtitle
     text leaves the phone. The ML Kit language model is downloaded on first
     use from Google's distribution; no user text is sent with that download.
   - **LibreTranslate (optional):** when you enter a self-hosted/LibreTranslate
     endpoint, subtitle text (cue lines) is sent to that endpoint for
     translation. Only use a server you trust or self-host.
4. **Whisper speech-to-text (optional).** When you configure a Whisper endpoint
   and tap transcribe, **audio bytes of the local file you pick** (chunked below
   the provider upload limit) are sent to that endpoint to return SRT/VTT
   text. No audio is uploaded unless an endpoint is configured and transcription
   explicitly started. The API key, when required, travels as an
   `Authorization: Bearer` header over HTTPS and is never logged.
5. **Preview playback** uses network streams only when you play a resolved URL;
   playback remains local through Media3 after the buffer starts.

No request is sent to a VidMateX-operated server, because none exists.

---

## 3. Secret storage and transport (privacy-relevant security)

- Secrets (resolver API key, Whisper API key) are encrypted at rest with the
  Android Keystore hardware-backed key (AES/GCM/NoPadding, 256-bit key, alias
  `vidmatex_secret_key_v1`). Only Base64 GCM ciphertext tokens are persisted;
  plaintext exists solely in memory while a call is in progress.
- Legacy plaintext rows are re-encrypted on the next settings write. If the
  Keystore is unavailable on a device, a documented passthrough fallback
  prevents startup crashes; that fallback is a degraded state, not the normal
  path, and values are never logged (logs expose length only).
- Transport: configured endpoints must use `https://`; plain `http` public
  hosts are rejected by the client validators (loopback development hosts
  excepted).
- Log hygiene: API keys and `Authorization`/`X-API-Key` headers are never
  written to logs; redaction shows token length only. URLs containing userinfo
  are stripped before logging, and worker logs carry job id, path, and language
  rather than secrets or full credential strings.
- You can erase all local secrets and data at any time (§6).

---

## 4. Permissions table

| Permission | Purpose | Reversible? |
|---|---|---|
| `INTERNET` | Download media from URLs you provide; reach the resolver / LibreTranslate / Whisper endpoints you configure. | Always available; deny = offline use only |
| `ACCESS_NETWORK_STATE` | Detect connectivity before enqueueing downloads/transcription; show offline errors clearly. | Yes (system settings) |
| `POST_NOTIFICATIONS` (Android 13+) | Show progress/completion notifications with pause/resume/retry actions. Downloads continue if denied; no notifications. | Revocable in system settings |
| `FOREGROUND_SERVICE` | Keep the download/transcription job alive while the app is backgrounded. | Required for background downloads; no separate runtime prompt beyond notifications on Android 14+ flows. |
| `FOREGROUND_SERVICE_DATA_SYNC` | Type declaration for data-sync foreground work (downloads). | Same as above. |
| `WAKE_LOCK` | Keep the CPU awake during an active download so it does not stall in Doze. | Released when the job finishes. |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule persisted WorkManager jobs after device restart. | Revocable in system settings (jobs simply restart manually). |
| `REQUEST_INSTALL_PACKAGES` | Optional self-update flow: user-confirmed installation of an APK the user chooses; the app never installs without user confirmation. | Can be denied; then updates are sideloaded manually. |
| Storage via **SAF / MediaStore** (no broad legacy storage permission) | Save files to a folder the user picks; `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` are not requested on modern Android. | Folder grant can be revoked via system settings. |

The app does **not** request location, contacts, SMS, call logs, microphone,
camera, Bluetooth, or accounts. Whisper transcription acts on **files you
choose**, not live microphone audio.

---

## 5. Third-party services and data recipients

| Service | Data shared | Operator responsibility |
|---|---|---|
| Google ML Kit Translation (on-device) | None leaves the device; model download from Google on first use | Subject to ML Kit Terms: https://developers.google.com/ml-kit/terms |
| LibreTranslate (optional) | Subtitle cue text | Privacy policy of the instance you configure; prefer self-hosting |
| Self-hosted resolver backend (optional) | Pasted source URL, requested formats, optional subtitle fetch | You operate the server; TLS and `RESOLVER_API_KEY` recommended (`docs/BACKEND_SETUP.md`) |
| Whisper endpoint (optional, self-host or paid OpenAI-compatible API) | Audio bytes of chosen file; `Authorization: Bearer` header when keyed | You choose the provider; its pricing/privacy apply (`docs/WHISPER_SETUP.md`) |
| Direct media origin servers | Your IP and the requested URL (standard HTTP request) | Origin server's privacy policy applies |

No SDK in the app advertises or profiles users.

---

## 6. Your controls

- Delete any downloaded file from the Downloads/History screen (on-device
  deletion).
- Clear all app data (settings, keys, database, caches): Android Settings →
  Apps → VidMateX → Storage → Clear data.
- Revoke notification permission and folder access at any time in system
  settings.
- Add, change, or remove every configured endpoint and API key in the app's
  Settings screen; leaving fields blank disables the service.
- There is no account and no server-side copy, so there is nothing to export
  or delete remotely: everything is local.

---

## 7. Children's safety

VidMateX is a general-purpose tool and is not directed at children under 13;
it collects no data from anyone. Guardians should supervise downloads and
ensure obtained content is lawful and age-appropriate.

---

## 8. Compliance note

VidMateX does not bypass DRM (Widevine/FairPlay/PlayReady) or authentication.
You are responsible for content you download: use only content you own, that
is public domain, or that you are permitted to download under applicable law
and platform Terms. Platform resolution requires your own self-hosted
endpoint; the app ships no built-in scraper and the default backend never
invents stream URLs.

---

## 9. Contact

Privacy questions: **[your-email@example.com]** — replace with a real contact
address before publishing. Operators who self-host or redistribute must keep
this policy accurate for their build, especially any added analytics, crash
reporting, or server endpoints.
