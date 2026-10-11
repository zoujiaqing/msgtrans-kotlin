#!/bin/bash
# msgtrans over QUIC on an Android device or emulator without an app (msgtrans-quic's jvmTest AndroidSmoke): the JVM
# classes and their runtime classpath are converted to dex with d8 and run with app_process, next to the NDK-built
# libneton_openssl.so of openssl-kotlin (scripts/build-jni.sh <androidNative target>).
# Usage: android-smoke.sh <libneton_openssl.so for the device's ABI> [extra gradle arguments, e.g. --include-build ../openssl-kotlin]
# Needs ANDROID_HOME (adb, build-tools' d8) and one device in `adb devices`.
set -euo pipefail
here=$(cd "$(dirname "$0")/.." && pwd)
so=$(cd "$(dirname "$1")" && pwd)/$(basename "$1"); shift
sdk=${ANDROID_HOME:-$HOME/Library/Android/sdk}
adb="$sdk/platform-tools/adb"
d8=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)d8
work="$here/msgtrans-quic/build/android-smoke"
device=/data/local/tmp/neton-smoke

(cd "$here" && ./gradlew -q "$@" :msgtrans-quic:androidSmokeClasspath)
rm -rf "$work/jars" "$work/dex" && mkdir -p "$work/jars" "$work/dex"
n=0
while read -r entry; do
    [ -e "$entry" ] || continue
    n=$((n + 1))
    if [ -d "$entry" ]; then (cd "$entry" && jar cf "$work/jars/$n.jar" .); else cp "$entry" "$work/jars/$n-$(basename "$entry")"; fi
done < "$work/classpath.txt"
"$d8" --release --min-api 26 --output "$work/dex" "$work"/jars/*.jar
"$adb" shell rm -rf "$device"
"$adb" shell mkdir -p "$device"
"$adb" push -q "$work"/dex/classes*.dex "$device/"
"$adb" push -q "$so" "$device/libneton_openssl.so"
classpath=$("$adb" shell ls "$device" | tr -d '\r' | grep '\.dex$' | sed "s|^|$device/|" | paste -sd: -)
echo "device: $("$adb" shell getprop ro.product.model | tr -d '\r'), Android $("$adb" shell getprop ro.build.version.release | tr -d '\r'), $("$adb" shell getprop ro.product.cpu.abi | tr -d '\r')"
"$adb" shell "CLASSPATH=$classpath app_process -Djava.library.path=$device /system/bin msgtrans.transport.AndroidSmokeKt"
