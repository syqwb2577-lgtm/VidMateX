# VidMateX — Distribution Checklist

Run through this checklist before publishing a VidMateX build to any store or
sharing the APK publicly. Items marked **(BLOCKER)** must be resolved before
distribution.

---

## A. Legal & compliance

- [ ] **YouTube Terms of Service** — downloading YouTube content without the
  copyright holder's permission violates YouTube's ToS. VidMateX does not ship
  a built-in YouTube resolver; platform links resolve only through a
  **self-hosted resolver endpoint the user configures in Settings**. State this
  clearly in store listing and in-app. **(BLOCKER if you ship a resolver.)**
- [ ] **Facebook Terms of Service** — same restriction; no built-in resolver.
- [ ] **TikTok Terms of Service / Community Guidelines** — same restriction;
  no built-in resolver.
- [ ] **Google Play policy** — downloader apps are frequently restricted or
  rejected on Google Play. **Recommendation: distribute via sideload,
  F-Droid, GitHub Releases, or an alternative store**, not Google Play. If you
  attempt Google Play, be prepared to demonstrate only public-domain /
  user-owned content and no platform circumvention.
- [ ] **DMCA / copyright response process** — publish a copyright / DMCA
  contact (email + web form) and commit to responding to takedown notices
  within a stated window. Include it in the app's About screen and website.
- [ ] **In-app copyright policy** — show a one-screen notice on first launch:
  "Download only content you own or are permitted to download; respect
  platform ToS."
- [ ] **DRM never bypassed** — confirm no code path defeats Widevine, FairPlay,
  or any DRM. Encrypted streams must fail gracefully. **(BLOCKER)**
- [ ] **Privacy policy published** — `docs/PRIVACY_POLICY.md` is hosted at a
  public URL and linked from the store listing and app About screen.

## B. Security

- [ ] **No API keys / secrets in the APK** — verify:
  ```bash
  grep -RIi "api[_-]\?key\|secret\|token" app/src/  # should find only placeholder names
  # optionally: apktool d app-release.apk && grep -RIi "AIza\|sk_" .
  ```
  **(BLOCKER if any real key is found.)**
- [ ] **HTTPS only** — all network calls use `https://`. The direct-URL adapter
  allows `http` only for local-network sources; confirm the default rejects
  plaintext for public origins.
- [ ] **`minSdk = 24`** — confirmed in every module's `build.gradle.kts`.
- [ ] **Exported components audit** — in the merged manifest, every
  `android:exported="true"` component (Activity/Service/Receiver/Provider) is
  intentional and protected by a permission where needed. Run:
  ```bash
  ./gradlew :app:processReleaseManifest
  # inspect app/build/intermediates/merged_manifest/release/AndroidManifest.xml
  ```
- [ ] **No WebView** — the app uses no `WebView` (avoids JS-injection and
  remote-content attack surface). Confirm no `android.webkit.WebView` import.
- [ ] **Intent validation** — `ACTION_VIEW` / `ACTION_SEND` intents from the
  History screen are validated (non-empty URI, `resolveActivity` check before
  starting).
- [ ] **File access** — downloads go through SAF/MediaStore Uris, not hardcoded
  external storage paths.

## C. License

- [ ] **ffmpeg-kit-full-gpl (maintained fork) is GPL v3** — `:media:processor`
  depends on `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (GPL v3;
  provenance: `docs/FFMPEG.md` §1). **Consequence: if you distribute the app,
  you MUST make the complete corresponding source code (including your app
  code that links to it) available under GPL-compatible terms.** State this
  prominently. **(BLOCKER if distributed without source disclosure.)**
- [ ] **Consider non-GPL build** — if you do not need libass / x264 / x265 /
  other GPL filters, switch to the maintained LGPL `ffmpeg-kit-full:8.1.9`
  flavor to avoid the GPL source-disclosure obligation; document which variant
  your release uses.
- [ ] **ML Kit terms** — ML Kit on-device translation is governed by Google's
  ML Kit Terms; acknowledge in attribution.
- [ ] **yt-dlp (Unlicense)** — runs only on the **self-hosted backend** the
  user configures; it is not bundled in the APK. If you ship a backend, comply
  with its license (Unlicense — permissive, but keep the copyright notice).
- [ ] **Other deps** — collect a license list (`./gradlew :app:dependencies`
  or a license-plugin report) and include it in-app or in distribution.
- [ ] **Attribution screen** — list open-source licenses in the app.

## D. Signing

> Note: this repository's GitHub Actions are validation-only and contain **no
> APK/AAB packaging job**. Keystores and signing stay on the maintainer's
> machine; do not add keystore secrets to a public repository workflow.

- [ ] **Generate a release keystore** (keep it OFF the repository, in a safe
  place — losing it means you can never update the app):
  ```bash
  keytool -genkey -v \
    -keystore release-keystore.jks \
    -alias vidmatex \
    -keyalg RSA -keysize 2048 -validity 10000
  ```
- [ ] **Configure GitHub secrets** — in repo Settings → Secrets and variables
  → Actions, set:
  - `ANDROID_KEYSTORE_BASE64` = `base64 release-keystore.jks` (single-line)
  - `ANDROID_KEYSTORE_PASSWORD`
  - `ANDROID_KEY_ALIAS` (= `vidmatex`)
  - `ANDROID_KEY_PASSWORD`
- [ ] **Verify signed APK** — after a release build:
  ```bash
  apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
  ```
- [ ] **Never commit `*.jks` / `*.keystore`** — confirmed in `.gitignore`.
  **(BLOCKER if committed.)**

## E. Store listing assets

- [ ] App icon (512×512 PNG, no alpha for Google Play) and adaptive icon.
- [ ] Feature graphic (1024×500) if listing on a store that requires it.
- [ ] Screenshots: phone (at least 3), 7" tablet, 10" tablet if targeting them.
- [ ] Short description (≤80 chars) and full description, in English + Arabic
  (the app ships `values-ar`).
- [ ] Privacy policy URL (from Section A).
- [ ] Content rating questionnaire completed honestly.
- [ ] `versionCode` / `versionName` bumped for every release.

## F. Crash-free smoke test matrix

Run these on at least one physical device per API level. A crash in any row
blocks release.

| Device / API | Smoke test |
|---|---|
| API 24 (minSdk) | Install, launch, paste a direct MP4 URL, download, open from History, share from History, delete from History. |
| API 28 | Same + notification permission flow + download to SD card via SAF. |
| API 31 | Same + scoped-storage save location + subtitle download (no translation). |
| API 33/34 | Same + `POST_NOTIFICATIONS` runtime permission + ML Kit on-device translation toggle. |
| Low-memory / backgrounded | Start a download, background the app, kill it from recents, relaunch — state must restore from Room, no crash. |
| Airplane mode mid-download | Enqueue, toggle airplane mode — download must fail gracefully with a user-visible error, no crash. |
| Empty History | Fresh install → History screen shows empty state, no crash. |
| Long-press / share / open | Every History card action resolves; missing file shows a toast, no crash. |

---

## G. GitHub repository upload hygiene (public source tree)
Run before `git push` to a public remote; full commands in
`docs/GITHUB_SETUP.md` §1–§2.

- [ ] **No committed secrets.** The high-entropy scan (`sk-…`, `Bearer …`,
      32+ char assigned key/secret/token/password values) matches nothing in
      source/config; `*.example` files contain empty placeholders only. Any
      exposed value is rotated and moved to host env or encrypted app Settings.
- [ ] **No build outputs committed.** `build/`, `.gradle/`, `local.properties`,
      `*.apk`, `*.aab`, `*.ap_`, `__pycache__/`, `.pytest_cache/`, and
      `backend/.venv/` are ignored (`/.gitignore`) and absent from `git status`.
- [ ] **No signing material committed.** `*.jks`, `*.keystore`, `*.p12`,
      `*.pfx`, `keystore.properties`, `release-keystore.jks` ignored and
      absent; the keystore lives offline (§D).
- [ ] **Gradle wrapper present and valid.** `gradlew` (executable bit),
      `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and
      `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.11.1) are tracked;
      none are ignored.
- [ ] **Workflows valid and validation-only.** `.github/workflows/ci.yml`
      parses as YAML; it has zero `continue-on-error`; it contains no
      `assembleDebug`/`assembleRelease`/`bundleRelease` task and no
      `.apk`/`.aab` output step; the `project-structure` job's forbidden-task
      scan still passes.
- [ ] **Required documentation present.** `README.md`, `CHANGELOG.md`,
      `SECURITY.md`, `PRIVACY_POLICY.md`, `docs/TESTING_GUIDE.md`,
      `docs/COMPATIBILITY.md`, `docs/BACKEND_SETUP.md`, `docs/WHISPER_SETUP.md`,
      `docs/EXTERNAL_SERVICES.md`, `docs/FFMPEG.md`, `docs/GITHUB_SETUP.md`,
      `docs/PRIVACY_POLICY.md`, `docs/TERMUX_SETUP.md`, both `.env.example`
      files.
- [ ] **Branch protection on `main`:** PR required, the five CI checks
      (`backend-tests`, `gradle-validation`, `android-unit-tests`,
      `project-structure`, `secret-scan`) required green, no force pushes, no
      branch deletion.
- [ ] **Release signing stays offline/manual.** No keystore or signing password
      is ever added to GitHub Actions secrets or the public repo; signed
      artifacts are built on a maintainer machine (§D) and attached to a
      manually created GitHub Release together with matching source.

---

**Release sign-off:** All BLOCKER items resolved. Date: __________ Builder: __________
