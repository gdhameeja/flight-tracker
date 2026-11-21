#!/usr/bin/env bash
# Builds the debug APK inside Docker (same image as running-app).
# Output: android/app/build/outputs/apk/debug/app-debug.apk
# Usage: docker/build-apk.sh [extra gradle tasks...]
set -euo pipefail
cd "$(dirname "$0")/.."

docker build -q -t flighttracker-android-build -f docker/android-build.Dockerfile docker > /dev/null
# Gradle's downloads persist between builds: a Docker volume here, or a folder
# when GRADLE_CACHE is set (GitHub Actions caches that folder between runs)
if [ -n "${GRADLE_CACHE:-}" ]; then
  mkdir -p "$GRADLE_CACHE"
  GRADLE_MOUNT="$GRADLE_CACHE:/gradle"
else
  docker volume create flighttracker-gradle-cache > /dev/null
  docker run --rm -v flighttracker-gradle-cache:/gradle flighttracker-android-build chown "$(id -u):$(id -g)" /gradle
  GRADLE_MOUNT="flighttracker-gradle-cache:/gradle"
fi

# Version from the commit count: a later commit is always a higher versionCode,
# and a GitHub release and a local build of the same commit agree.
VERSION_CODE=$(git rev-list --count HEAD 2>/dev/null || echo 1)
VERSION_NAME="1.$VERSION_CODE"

# The debug signing key lives in .android-user/ (git-ignored; on GitHub it comes
# from the DEBUG_KEYSTORE_B64 secret). Same key every build, so updates install
# over the app instead of needing an uninstall.
mkdir -p .android-user
docker run --rm flighttracker-android-build cat /etc/passwd > .android-user/passwd
grep -q "^[^:]*:[^:]*:$(id -u):" .android-user/passwd ||
  echo "builder:x:$(id -u):$(id -g)::/tmp/home:/bin/bash" >> .android-user/passwd

docker run --rm -u "$(id -u):$(id -g)" \
  -e HOME=/tmp/home -e GRADLE_USER_HOME=/gradle -e ANDROID_USER_HOME=/app/.android-user \
  -v "$PWD/.android-user/passwd":/etc/passwd:ro \
  -v "$PWD":/app -v "$GRADLE_MOUNT" \
  flighttracker-android-build docker/inside-build.sh "$VERSION_CODE" "$VERSION_NAME" "$@"
echo "APK: android/app/build/outputs/apk/debug/app-debug.apk (version $VERSION_NAME)"
