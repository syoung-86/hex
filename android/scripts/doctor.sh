#!/usr/bin/env bash
# What the phone thinks is happening, without opening a GUI.
set -euo pipefail

PACKAGE=anomaly.hex.android

echo "== device =="
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release

echo
echo "== foreground =="
adb shell dumpsys activity activities \
  | grep -E 'mResumedActivity|topResumedActivity' | head -3 || true

echo
echo "== service =="
adb shell dumpsys activity services "$PACKAGE" | head -40 || true

echo
echo "== permissions =="
adb shell dumpsys package "$PACKAGE" | grep -E 'RECORD_AUDIO|POST_NOTIFICATIONS' || true

echo
echo "== recordings =="
adb shell run-as "$PACKAGE" ls -l files/recordings 2>/dev/null || echo "(release build: not debuggable)"

echo
echo "== log =="
adb logcat -d -s HexM0:V AndroidRuntime:E | tail -60
