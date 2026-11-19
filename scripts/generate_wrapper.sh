#!/usr/bin/env bash
# Regenerate the Gradle wrapper locally (optional recovery only).
#
# The committed tree already includes gradle/wrapper/gradle-wrapper.jar and
# gradlew; this script exists for cases where the wrapper jar is missing or
# the wrapper must be regenerated without the binary. No CI workflow builds
# APK/AAB; CI only runs validation/test tasks (see .github/workflows/ci.yml).
#
# Requires: curl and unzip; downloads Gradle to a temp dir. Pinned to match
# gradle/wrapper/gradle-wrapper.properties (AGP 8.10.1 requires Gradle >= 8.11.1).
set -euo pipefail

GRADLE_VERSION="8.11.1"
GRADLE_ZIP="gradle-${GRADLE_VERSION}-bin.zip"
GRADLE_URL="https://services.gradle.org/distributions/${GRADLE_ZIP}"
TMP_DIR="$(mktemp -d)"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[generate_wrapper] Project root: ${PROJECT_ROOT}"
echo "[generate_wrapper] Downloading Gradle ${GRADLE_VERSION} ..."
curl -fsSL "${GRADLE_URL}" -o "${TMP_DIR}/${GRADLE_ZIP}"
echo "[generate_wrapper] Unzipping ..."
unzip -q "${TMP_DIR}/${GRADLE_ZIP}" -d "${TMP_DIR}"
export PATH="${TMP_DIR}/gradle-${GRADLE_VERSION}/bin:${PATH}"
gradle --version
echo "[generate_wrapper] Running 'gradle wrapper' in project root ..."
cd "${PROJECT_ROOT}"
gradle wrapper --gradle-version "${GRADLE_VERSION}" --distribution-type bin --no-daemon
chmod +x gradlew
echo "[generate_wrapper] Done. Wrapper files:"
ls -la gradlew gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties
rm -rf "${TMP_DIR}"
echo "[generate_wrapper] You can now run ./gradlew <validation/test task> (no assemble/bundle task)."
