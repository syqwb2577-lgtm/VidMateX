# GitHub Upload & Validation Guide — VidMateX

Goal: publish the repository to GitHub so the **validation-only** CI runs on
every push/PR, while keeping secrets, keystores, and build outputs out of the
repo. This guide contains **no APK/AAB packaging step**: this repository's
workflows deliberately do not build release artifacts (see §5).

## 1. One-time repository preparation

1. **Install tooling locally** (or use Termux, `docs/TERMUX_SETUP.md`):
   Git, JDK 17, Android cmdline-tools with `platforms;android-36` and
   `build-tools;36.0.0`.
2. **Inspect the working tree before init:**
   ```bash
   cd VidMateX_FINAL
   git init
   git status
   ```
3. **Confirm `.gitignore` covers secrets and outputs** (already present):
   `build/`, `.gradle/`, `local.properties`, `*.apk`, `*.aab`, `*.ap_`,
   `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `keystore.properties`,
   Python caches and `backend/.venv/`.
4. **Remove anything sensitive before the first commit:**
   ```bash
   grep -RInE "sk-|Bearer [A-Za-z0-9]|BEGIN (RSA|PRIVATE)|password=" \
     --exclude="*.example" --exclude-dir=.git .
   ```
   Placeholders in `*.example` files are expected; real values are not.
5. **Commit the committed wrapper** (`gradlew`, `gradlew.bat`,
   `gradle/wrapper/gradle-wrapper.jar`) — do not ignore or delete them.
6. **Pre-commit hygiene checklist (clean GitHub upload):**
   - [ ] No secrets: the secret-scan patterns (`sk-…`, `Bearer …`, 32+ char
         assigned key/secret/token/password values) match nothing outside
         `*.example` placeholders; test-only synthetic strings use short,
         obviously fake values.
   - [ ] No build outputs: `build/`, `.gradle/`, `local.properties`,
         `*.apk`, `*.aab`, `*.ap_`, `*.jks`, `*.keystore`, Python
         `__pycache__/`, `.pytest_cache/`, and `backend/.venv/` are ignored
         and absent from the commit (`git status` clean of them).
   - [ ] Wrapper present and executable: `gradlew` (executable bit),
         `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
         `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.11.1) tracked.
   - [ ] Workflows valid: `.github/workflows/ci.yml` parses as YAML
         (e.g. `python -c "import yaml; yaml.safe_load('.github/workflows/ci.yml')"`),
         has zero `continue-on-error`, and contains no
         `assembleDebug`/`assembleRelease`/`bundleRelease` task and no
         `.apk`/`.aab` output step.
   - [ ] Required docs present: `README.md`, `CHANGELOG.md`, `SECURITY.md`,
         `PRIVACY_POLICY.md`, `docs/TESTING_GUIDE.md`, `docs/COMPATIBILITY.md`,
         `docs/BACKEND_SETUP.md`, `docs/WHISPER_SETUP.md`,
         `docs/EXTERNAL_SERVICES.md`, `docs/FFMPEG.md`, `docs/GITHUB_SETUP.md`,
         `.env.example`, `backend/.env.example`.

## 2. First push

```bash
git add .
git commit -m "chore: initial validation-ready snapshot"
git branch -M main
git remote add origin https://github.com/<your-org>/<your-repo>.git
git push -u origin main
```

Repository Settings → **Actions → General → Workflow permissions**:
- Grant **Read and write** permission if you later add artifact uploads; the
  current validation jobs only need **Read**.
- No repository secrets are required by the validation-only workflows.

### 2.1 Branch protection (Settings → Rules → Rulesets / Branches)
On `main` (and `master` if used):
- [ ] **Require a pull request before merging**; require at least one review
      for maintainer teams (single-maintainer repos may dismiss this, but keep
      CI required).
- [ ] **Require status checks to pass before merging**; mark `backend-tests`,
      `gradle-validation`, `android-unit-tests`, `project-structure`, and
      `secret-scan` as required (disable "require branches to be up to date"
      only if queueing is unavailable).
- [ ] **Require conversation resolution** and **do not allow force pushes**;
      disable branch deletion for `main`.
- [ ] Keep release tags protected; signing keys are never repository secrets
      (see §5). Release artifacts are produced offline/manually, never by a
      public workflow.

## 3. Enabling and reading the workflows

`.github/workflows/ci.yml` runs automatically on pushes to `main`/`master`,
pull requests, and manual `workflow_dispatch` runs:

| Job | What it proves |
|---|---|
| `backend-tests` | `python -m pytest backend/tests -q` on Python 3.12 (149 cases; 102 functions / 5 files at last recorded run) |
| `gradle-validation` | `./gradlew help --no-configuration-cache` on JDK 17 (model resolution) |
| `android-unit-tests` | `./gradlew testDebugUnitTest` (JVM unit tests; no packaging) |
| `project-structure` | Required files exist; forbidden packaging task names absent from workflows |
| `secret-scan` | High-entropy/key-pattern scan over source/config; placeholders excluded |

Each job publishes a Markdown summary (`GITHUB_STEP_SUMMARY`, `if: always()`).
A red job must be fixed before merging; no job continues past failure.

## 4. Environment variables and external services (not repository secrets)

| Setting | Where it lives |
|---|---|
| `RESOLVER_API_KEY`, `YTDLP_PATH`, backend tunables | Server `backend/.env` on the host (never committed) |
| Resolver URL + key, Whisper endpoint + key, LibreTranslate URL | Entered in the Android **Settings** screen; encrypted at rest (Keystore) |
| `WHISPER_ENDPOINT` / `WHISPER_API_KEY` documentation | `.env.example` placeholders only |

Never paste a real key into a workflow file, commit message, or issue. If one
is exposed, rotate it immediately.

## 5. Release builds (manual, outside this repository's CI)

This repo contains **no workflow that emits APK or AAB files**; the removed
APK workflow was replaced by validation jobs. To produce a distributable
artifact, use a maintainer machine (not CI secrets in a public repo):

1. Complete `docs/DISTRIBUTION_CHECKLIST.md` (legal, security, license, assets).
2. Create the release keystore offline; store it outside the repo (§D of that
   checklist); never commit `*.jks`.
3. Run packaging locally on JDK 17 with SDK Platform 36; verify the signed
   artifact; distribute via sideload/F-Droid/GitHub Releases with source code
   available (GPL-3.0 obligations from the FFmpeg GPL build).

## 6. Tags and releases (once manually signed)

- Tag conventions remain `vMAJOR.MINOR.PATCH`; create the GitHub Release
  after a verified local build, attaching the locally signed artifact and the
  corresponding source archive.
- Update `CHANGELOG.md` and `COMPLETION_REPORT.md` before tagging.
- Keep `versionCode`/`versionName` bumped for every release per the checklist.

## 7. Troubleshooting CI

| Symptom | Likely fix |
|---|---|
| `gradle-validation` fails on SDK license/SDK missing | Runner sets `platforms;android-36` + `build-tools;36.0.0`; ensure `android-actions/setup-android` step ran |
| `android-unit-tests` fails on Kotlin/AGP warning | Compare `docs/COMPATIBILITY.md` §3 fallback chain (Kotlin 2.2.21 + Compose Gradle plugin) before changing versions |
| `secret-scan` red | Move real value to host env/app settings; keep only placeholders in `*.example` |
| `project-structure` red | A required doc/file path changed; restore it or update the check list in `ci.yml` |
| Backend job red | Reproduce locally: `source ../.venv_backend/bin/activate && python -m pytest backend/tests -q` |
