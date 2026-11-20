# Hex for Android (personal M0)

The personal, single-device Pixel 9a build in
[`../docs/plans/android.md`](../docs/plans/android.md). This milestone proves
capture and the foreground service; it has **no speech engine, no
accessibility service, and no bubble yet**.

Current state, evidence, and next steps: [`HANDOFF.md`](HANDOFF.md).

What works in M0:

- microphone permission and notification permission
- one `AudioRecord` at 16 kHz mono, written as a WAV
- a rolling level meter, 60 s cap, 0.3 s minimum
- playback, share, and a bounded in-memory debug log
- a `microphone` foreground service ("Hex is ready") that survives
  backgrounding and screen lock

## Build

Requires Android Studio (or JDK 17+ with Android SDK 35) and `adb`.

```sh
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or the whole thing, including permission grants:

```sh
./scripts/setup.sh
```

`./scripts/setup.sh --release` builds the release variant; it is signed only
when `android/keystore.properties` exists (copy
[`keystore.properties.example`](keystore.properties.example) and create the
keystore). `./scripts/doctor.sh` prints the device, the foreground activity, the
service, the permissions, and the log.

The app id is `anomaly.hex.android`. It has no `INTERNET` permission.

## M0 checklist

Run these on the 9a and write the answers into the plan.

1. The dot row rests flat in silence and moves with speech without pinning to
   full scale.
2. A 5 s recording plays back, shares to Files/Gmail, and is 160,044 bytes
   (`44 + 5 * 32,000`).
3. Start the service, start a recording, press Home, lock the phone for 10 s,
   unlock: the WAV is saved, the notification is still there, and the log has
   `saved`.
4. Tapping Stop 0.2 s after starting shows the short-capture error, and
   `files/recordings` has no new file.
5. A 90 s recording stops itself at 60 s and the file is about 1.92 MB.
6. With the service stopped, recording still works while the app is visible
   (the activity is foreground), and the log says the service was not started.
7. Record with a Bluetooth headset connected and note which microphone the
   system picks.

Pull a debuggable build's files:

```sh
adb shell run-as anomaly.hex.android ls files/recordings
adb exec-out run-as anomaly.hex.android cat files/recordings/<name>.wav > /tmp/hex.wav
```

A release build is not debuggable; use Share, or `adb shell run-as` only on
debug.

## Notes

- The service is started by the visible activity because Android 12+ blocks
  microphone foreground-service starts from the background. The next milestone
  reuses this one service for every trigger.
- `TYPE_ACCESSIBILITY_OVERLAY` and the bubble are deliberately absent here;
  they arrive with the accessibility service in M2.
- The model import, VAD, and sherpa-onnx engine arrive in M1. Nothing is
  downloaded by this app.
