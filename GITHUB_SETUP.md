# GitHub Setup — VidMateX

**Full guide:** [`docs/GITHUB_SETUP.md`](docs/GITHUB_SETUP.md) (repository
init, `.gitignore`, secrets hygiene, enabling workflows, manual release note).

Quick summary:

1. `git init` in this folder; verify `.gitignore` excludes `build/`,
   `.gradle/`, `local.properties`, `*.apk`, `*.aab`, `*.jks`, `keystore.properties`,
   and Python caches/venvs.
2. Commit the committed Gradle wrapper (`gradlew`, `gradlew.bat`,
   `gradle/wrapper/gradle-wrapper.jar`) and push to `main`.
3. `.github/workflows/ci.yml` runs validation on push/PR: backend pytest
   (149 cases; 102 functions / 5 files), Gradle `help` (JDK 17),
   `testDebugUnitTest`, structure checks, and a secret scan. **This repository
   has no APK/AAB workflow** — release packaging is a manual, offline, signed
   process documented in `docs/DISTRIBUTION_CHECKLIST.md` and
   `docs/GITHUB_SETUP.md` §5.
4. Secrets live in `backend/.env` on the host or in the encrypted app Settings;
   `*.example` files hold empty placeholders only.
5. External-service setup (backend, Whisper, LibreTranslate, optional
   yt-dlp): see `docs/EXTERNAL_SERVICES.md`, `docs/BACKEND_SETUP.md`,
   `docs/WHISPER_SETUP.md`.
6. After the first push, enforce branch protection on `main`: require the five
   CI checks green, require a PR review where feasible, block force pushes,
   and never commit keystores (release signing stays offline/manual). Full
   pre-upload hygiene list: `docs/GITHUB_SETUP.md` §1 step 6 and
   `docs/DISTRIBUTION_CHECKLIST.md` §G.

Environment reference: JDK 17; Android SDK `platforms;android-36` and
`build-tools;36.0.0`; Python 3.12 backend venv. Compatibility evidence:
`docs/COMPATIBILITY.md`.
