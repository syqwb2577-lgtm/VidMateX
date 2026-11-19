# VidMateX — Build on Android Phone (Termux)

This guide lets you build VidMateX on an Android phone using Termux. No
desktop computer required. Building on a phone is slow (15–40+ minutes for
the first build). GitHub Actions in this repository run **validation only**
(backend tests, Gradle model check, JVM unit tests) and **do not produce an
APK**; a local build per §7 is the phone-based packaging path.

> **Important:** Install Termux from **F-Droid** (https://f-droid.org/packages/com.termux/).
> The Google Play Store version of Termux is outdated and unmaintained and will
> fail with package errors. Do not use the Play Store build.

---

## 1. Install Termux and update packages

1. Download and install Termux from F-Droid.
2. Open Termux and run:

```bash
pkg update && pkg upgrade -y
```

If prompted about configuration file differences, choose the default (press
Enter / type `Y`).

---

## 2. Install build dependencies

```bash
pkg install -y git openjdk-17 wget unzip
```

Verify Java 17:

```bash
java -version   # should show openjdk version "17.x.x"
```

---

## 3. Install Android command-line tools

Pick a cmdline-tools zip. The URL below is pinned to a known-good build; check
https://developer.android.com/studio#command-tools for the latest number and
update `CMDLINE_TOOLS_ZIP` if needed.

```bash
# Pinned known-good version. Replace the number with the latest from
# https://developer.android.com/studio#command-tools if you prefer.
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"

cd ~
wget "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
mkdir -p "$HOME/android-sdk/cmdline-tools"
unzip -q "${CMDLINE_TOOLS_ZIP}" -d "$HOME/android-sdk/cmdline-tools"
# sdkmanager expects the layout .../cmdline-tools/latest/bin/sdkmanager
mv "$HOME/android-sdk/cmdline-tools/cmdline-tools" "$HOME/android-sdk/cmdline-tools/latest"
```

Set environment variables (add these to `~/.bashrc` so they persist):

```bash
echo 'export ANDROID_HOME=$HOME/android-sdk' >> ~/.bashrc
echo 'export ANDROID_SDK_ROOT=$HOME/android-sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools' >> ~/.bashrc
source ~/.bashrc
```

---

## 4. Accept licenses and install SDK packages

```bash
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

Confirm:

```bash
sdkmanager --list_installed
```

---

## 5. Optional system Gradle (the committed wrapper is normally sufficient)

The project wrapper pins Gradle 8.11.1 (AGP 8.10.1 requires ≥ 8.11.1); you do
not normally need a system Gradle. Install one only if a tooling script needs it:

```bash
cd ~
wget https://services.gradle.org/distributions/gradle-8.11.1-bin.zip
unzip -q gradle-8.11.1-bin.zip -d "$HOME/gradle"
echo 'export PATH=$PATH:$HOME/gradle/gradle-8.11.1/bin' >> ~/.bashrc
source ~/.bashrc
gradle --version   # should show Gradle 8.11.1
```

---

## 6. Get the source — the Gradle wrapper is already committed

The repo **commits** the Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/gradle-wrapper.jar`, pinned to Gradle 8.11.1). You do **not**
need to generate it. Just make it executable:

```bash
cd ~
git clone <your-repo-url> vidmatex
cd ~/vidmatex
chmod +x gradlew
./gradlew --version
```

(`scripts/generate_wrapper.sh` is kept only for reference if you ever need to
re-pin the wrapper version.)

---

## 7. Build locally on the phone (optional packaging step)

> This command is a maintainer/local-user packaging step. The repository
> snapshot was **not built here**, and CI contains no packaging task. Run it
> only when you intentionally want a debug APK on your own machine/phone.

```bash
cd ~/vidmatex
./gradlew :app:assembleDebug
```

The debug APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it on the same (or another) phone:

```bash
pkg install -y android-tools
adb install app/build/outputs/apk/debug/app-debug.apk   # if adb over network is set up
# or use a file manager to tap the APK and sideload it
```

**First build is slow** (Gradle daemon + Kotlin/Compose compilation). On a
mid-range phone expect 15–40 minutes. Subsequent builds are much faster. If the
build is killed by the low-memory killer, apply the troubleshooting in Section
11. GitHub CI validates tests without packaging (Section 10).

---

## 8. Git setup (push and run validation GitHub Actions)

Configure your identity once:

```bash
git config --global user.name "Your Name"
git config --global user.email "you@example.com"
```

Create an empty repository on github.com via a browser (do **not** initialize it
with a README — the local repo already has files). Then:

```bash
cd ~/vidmatex
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```

You will be prompted for a GitHub username and a **Personal Access Token**
(create one at https://github.com/settings/tokens with `repo` scope). Termux
does not support interactive browser login, so use the token as the password.

---

## 9. Edit code on the phone with Acode editor

1. Install **Acode** from F-Droid or Play Store.
2. Open Acode → menu → "Open folder" → select `~/vidmatex` (grant storage
   access when prompted; on Android 11+ use the system file-picker to grant
   access to the Termux home directory, or enable Acode's "all files access").
3. Edit any `.kt`, `.kts`, `.xml`, or `.md` file and save.
4. Switch back to Termux to build or commit:

```bash
cd ~/vidmatex
git add .
git commit -m "describe your change"
git push
```

---

## 10. What GitHub Actions produces (validation only)

On every push to `main`/`master` and every pull request,
`.github/workflows/ci.yml` runs:

- `backend-tests` — `python -m pytest backend/tests -q` (149 cases; 102 functions / 5 files at last record);
- `gradle-validation` — `./gradlew help --no-configuration-cache` on JDK 17;
- `android-unit-tests` — `./gradlew testDebugUnitTest` (JVM tests only);
- `project-structure` — required files present; no packaging task name in workflows;
- `secret-scan` — high-entropy/key-pattern scan.

It does **not** build an APK/AAB, and no `build.yml` APK workflow exists in
this repository. To package a debug build, use §7 on this phone or a desktop
with JDK 17 and SDK Platform 36; signed releases follow
`docs/DISTRIBUTION_CHECKLIST.md` with an offline keystore.

---

## 11. Troubleshooting

### Build runs out of memory / is killed (OOM)

Cap Gradle's heap and disable the configuration cache:

```bash
export GRADLE_OPTS="-Xmx1536m -Dorg.gradle.daemon=false"
echo 'org.gradle.jvmargs=-Xmx1536m' >> ~/vidmatex/gradle.properties
echo 'org.gradle.unsafe.configuration-cache=false' >> ~/vidmatex/gradle.properties
echo 'org.gradle.parallel=false' >> ~/vidmatex/gradle.properties
```

On low-RAM phones (≤4 GB), also close all other apps and pin Termux in the
recents screen so Android does not kill it.

### `sdkmanager: command not found`

`PATH` was not applied. Re-run `source ~/.bashrc` or check Section 3.

### `Exception in thread "main" java.lang.UnsupportedClassVersionError`

You are not on JDK 17. Run `pkg install -y openjdk-17` and confirm
`java -version` shows 17. AGP 8.10.1 requires JDK 17.

### `SDK location not found. Define a valid SDK location with an ANDROID_HOME`

`ANDROID_HOME` is not set or `sdkmanager` did not install the packages. Re-run
Sections 3–4 and confirm `echo $ANDROID_HOME` prints a path that exists.

### `git push` asks for password repeatedly

Use a GitHub Personal Access Token as the password, or set up a credential
helper: `git config --global credential.helper store` (stores the token in
plaintext in `~/.git-credentials` — acceptable on a personal phone).

### Acode cannot see the Termux home directory

Grant Acode "All files access" in Android Settings → Apps → Acode → Permissions,
or copy the project to shared storage (`cp -r ~/vidmatex ~/storage/shared/`) and
open that copy in Acode, then build from the shared path in Termux.
