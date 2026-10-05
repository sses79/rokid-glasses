#!/bin/sh
# Copy capture runs (run.json + clips) from the glasses into tests/manual-runs/,
# then replay them through the current gesture code.
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
REMOTE=/sdcard/Android/data/dev.tim.gesturelab/files/runs
mkdir -p tests/manual-runs
if ! adb shell "ls $REMOTE" >/dev/null 2>&1; then
  echo "No runs on the glasses yet. Open the CAPTURE page and tap the temple to record."
  exit 0
fi
adb pull "$REMOTE/." tests/manual-runs/
echo
# Gradle indents the tests' own output by four spaces; show just that.
./gradlew :app:testDebugUnitTest --tests '*ReplayRunsTest*' --rerun-tasks --console=plain 2>&1 | grep -E "^ {4}" | sed 's/^    //' || true
