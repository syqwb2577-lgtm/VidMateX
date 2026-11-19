# Contributing to VidMateX

Thanks for your interest in improving VidMateX! This document explains how to
report issues, submit changes, and stay compatible with the project's legal and
technical constraints.

## 1. Legal & compliance constraints (non-negotiable)

VidMateX is **GPL-3.0** (see `LICENSE`), primarily because `:media:processor`
depends on `ffmpeg-kit-full-gpl`. Any contribution you submit is licensed under
GPL-3.0.

The following will **not** be merged:

- Code that bypasses DRM (Widevine / FairPlay / PlayReady), authentication, or
  platform access restrictions.
- Hard-coded integrations that scrape or reverse-engineer a platform's private
  API against that platform's Terms of Service.
- Hard-coded API keys, signing passwords, tokens, or any private credential.
  Use `local.properties` (never committed), environment variables, or GitHub
  Secrets.
- APK files, keystores (`*.jks`, `*.keystore`, `*.p12`), or `local.properties`
  in pull requests.

Platform support is added **only** through the pluggable `SourceAdapter`
interface in `:source:adapters`, and only for sources whose terms permit it.
For platforms such as YouTube / Facebook / TikTok, the intended path is a
**self-hosted resolver endpoint** configured by the end user in Settings —
never a built-in scraper.

## 2. Branching & commits

- Base all work on `main`.
- Use concise, imperative commit messages (`fix(downloads): ...`,
  `feat(subtitles): ...`, `docs: ...`).
- Keep pull requests focused; one logical change per PR.
- Update or add unit tests when touching `:media:processor` parsing logic
  (`SubtitleParserImplTest`).

## 3. Code style & architecture

- **Language:** Kotlin. Target JVM 17. `kotlin.code.style=official`.
- **UI:** Jetpack Compose + Material 3. No hard-coded colors; use the
  `VidMateXTheme` color scheme. Support dark **and** light themes.
- **RTL:** every user-visible string must exist in both `values/strings.xml`
  and `values-ar/strings.xml`. Arabic text must render correctly (RTL).
- **DI:** Hilt (kapt). ViewModels use `@HiltViewModel`; Workers use
  `@HiltWorker` + `@AssistedInject`.
- **Modules:** `feature/*` depends on `core/*` **interfaces** only. Concrete
  implementations (e.g. `DownloadManagerImpl`) live in one feature module and
  are bound via Hilt. Do not add feature-to-feature dependencies.
- **Background work:** WorkManager. No foreground service without declaring
  `foregroundServiceType` and the matching permission.
- **File I/O:** Storage Access Framework / MediaStore. Never assume
  `WRITE_EXTERNAL_STORAGE` on API 29+. Sanitize file names (no path traversal).
- **Network:** HTTPS only. `usesCleartextTraffic=false`. No plaintext HTTP
  except an explicit debug-only override in `network_security_config.xml`.
- **FFmpeg:** build argument **lists** (`executeWithArguments`). Never build a
  shell command string from user input.

## 4. Building locally

Requires JDK 17 + Android SDK (`platforms;android-36`, `build-tools;36.0.0`).
Coordinated versions: AGP 8.10.1, Gradle 8.11.1, Kotlin 1.9.24 — evidence in
`docs/COMPATIBILITY.md`.

```bash
./gradlew help --no-configuration-cache   # model-resolution sanity check
./gradlew testDebugUnitTest              # JVM unit tests (run before pushing)
# Packaging (APK/AAB) is a maintainer-local release step; no CI workflow in
# this repository emits packaging artifacts. See docs/DISTRIBUTION_CHECKLIST.md.
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`) is
**committed** — do not delete it, and do not re-add it to `.gitignore`.

## 5. Pull request checklist

- [ ] `testDebugUnitTest` passes.
- [ ] No new hard-coded secrets, URLs that should be configurable, or TODOs
      without a tracking issue.
- [ ] New user-visible strings added to both `values/` and `values-ar/`.
- [ ] `.gitignore` still excludes `build/`, `.gradle/`, `local.properties`,
      `*.apk`, `*.aab`, `*.jks`.
- [ ] No `build/` output, IDE files, or binaries committed.
- [ ] If you changed a public interface in `core/*`, updated all implementers
      and noted it in the PR description.

## 6. Security reports

Do **not** open a public issue for security vulnerabilities. Contact the
maintainer privately with a reproducible description. We will acknowledge
within 7 days and coordinate a fix and disclosure timeline.
