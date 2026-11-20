#!/usr/bin/env bash
# Build, install, grant, and launch the personal M0 build.
set -euo pipefail

cd "$(dirname "$0")/.."
PACKAGE=anomaly.hex.android
VARIANT=debug

if [[ "${1:-}" == "--release" ]]; then
  VARIANT=release
  if [[ ! -f keystore.properties ]]; then
    echo "release needs keystore.properties; see keystore.properties.example" >&2
    exit 1
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH" >&2
  exit 1
fi

./gradlew ":app:assemble${VARIANT^}"
adb wait-for-device
adb install -r "app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"

# The runtime prompts still work; these just save tapping through them.
adb shell pm grant "$PACKAGE" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb shell dumpsys deviceidle whitelist "+$PACKAGE" >/dev/null 2>&1 || true

adb shell am start -n "$PACKAGE/.MainActivity" >/dev/null
echo "Installed. M0: record, background the app, lock the phone, and check the WAV."
