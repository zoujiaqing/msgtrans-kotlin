#!/bin/bash
# msgtrans in a real Android app (interop/android-app-kotlin21: Kotlin 2.1, AGP, R8 release build) on a device or
# emulator: builds the release APK from published or staged artifacts, installs it, launches it and waits for its
# verdict in logcat (tag msgtrans-smoke). Exit status 0 on PASS.
# Usage: android-app-smoke.sh [gradle arguments, e.g. -PstagedRepositories=/path/a,/path/b]
# Needs ANDROID_HOME and one device in `adb devices` (or ANDROID_SERIAL).
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
adb="$sdk/platform-tools/adb"
app=com.netonstream.smoke

(cd "$here" && ANDROID_HOME="$sdk" ./gradlew -q -p interop/android-app-kotlin21 "$@" assembleRelease)
"$adb" install -r -t "$here/interop/android-app-kotlin21/build/outputs/apk/release/android-app-kotlin21-release.apk" > /dev/null
"$adb" shell am force-stop "$app"
"$adb" logcat -c
"$adb" shell am start -n "$app/.SmokeActivity" > /dev/null
for _ in $(seq 1 120); do
    log=$("$adb" logcat -d -s msgtrans-smoke:I AndroidRuntime:E)
    if grep -qE "msgtrans-smoke: (PASS|FAIL)|FATAL EXCEPTION" <<< "$log"; then break; fi
    sleep 1
done
echo "$log" | grep -E "msgtrans-smoke|AndroidRuntime" || true
grep -q "msgtrans-smoke: PASS" <<< "$log"
