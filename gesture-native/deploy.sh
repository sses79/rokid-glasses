#!/bin/sh
# Build the debug app, install it on the connected glasses and launch it.
set -e
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
./gradlew :app:assembleDebug --console=plain -q
adb install -r app/build/outputs/apk/debug/app-debug.apk
# The glasses have no touch screen to answer a permission dialog.
adb shell pm grant dev.tim.gesturelab android.permission.CAMERA
adb shell am start -S -n dev.tim.gesturelab/.MainActivity
echo "Running. Follow it with: adb logcat -s GestureLab"
