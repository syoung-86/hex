# Hex for Android (personal M2)

The personal, single-device Pixel 9a build in
[`../docs/plans/android.md`](../docs/plans/android.md). M0 proved capture and
the foreground service, M1 added the on-device engine, and M2 adds the
accessibility bubble that inserts into the focused field.

Current state, evidence, and next steps: [`HANDOFF.md`](HANDOFF.md).

What works:

- microphone permission and notification permission
- one `AudioRecord` at 16 kHz mono, written as a WAV
- a rolling level meter, 60 s cap, 0.3 s minimum
- playback, share, and a bounded in-memory debug log
- a `microphone` foreground service ("Hex is ready") that survives
  backgrounding and screen lock
- on-device transcription with Parakeet TDT-CTC 110M int8, with load, run, and
  real-time timings shown under each recording
- an accessibility bubble over any focused text field: hold to talk, release to
  insert, tap for hands-free, tap after insertion to undo

The bubble is new and has not been exercised on the phone yet; enable the
service and run the M2 items in the checklist below.

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

The app id is `anomaly.hex.android`. It has no `INTERNET` permission. The debug
APK is about 54 MB: sherpa-onnx's arm64 natives are 31 MB of that. The model
is not in the APK.

## M1: the speech model

The model lives in the app's private storage, so it survives app updates and
needs no permission. Download the release once and push it with `adb`:

```sh
curl -LO https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2
tar xjf sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2
MODEL=sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8

adb shell run-as anomaly.hex.android mkdir -p files/models/parakeet-ctc-110m
adb shell "run-as anomaly.hex.android sh -c 'cat > files/models/parakeet-ctc-110m/model.int8.onnx'" < "$MODEL/model.int8.onnx"
adb shell "run-as anomaly.hex.android sh -c 'cat > files/models/parakeet-ctc-110m/tokens.txt'" < "$MODEL/tokens.txt"
adb shell run-as anomaly.hex.android chmod 600 files/models/parakeet-ctc-110m/*
```

Verify on the computer first:

| File | Bytes | SHA-256 |
| --- | --- | --- |
| `model.int8.onnx` | 131,652,171 | `9177a9146cf32ee0cc8152276ef95116f312018d316be37ccf57f7efea81fc1a` |
| `tokens.txt` | 9,953 | `450e56bd2f036fe5b6aa821865838cc5aa9d8b0106134ce9a9ba0664abe6cd10` |

The model is NVIDIA's `parakeet-tdt_ctc-110m`, CC-BY-4.0, exported and int8
quantized by the sherpa-onnx project. Attribution matters if this ever leaves
the phone. The Engine card's **Refresh** button re-checks the files; **Unload**
frees the ~131 MB of weights.

## M2: the bubble

The bubble is an accessibility overlay (`TYPE_ACCESSIBILITY_OVERLAY`), so it
never takes focus from the app behind it and needs no "display over other
apps" grant. Enable the service once:

1. **Settings → Apps → Hex → ⋮ → Allow restricted settings** (Android 15+
   hides accessibility from sideloaded apps until this is on).
2. **Settings → Accessibility → Hex dictation → On**.

The Hex **Bubble** card shows the live state and opens Accessibility settings.
`adb` cannot turn the service on for a sideloaded build; the two taps above are
manual by design.

Then focus the card's **Test insertion** field. The bubble appears above the
keyboard:

- press and hold to record, release to transcribe and insert
- tap to start hands-free and tap again to stop
- drag to move it (the position is remembered)
- tap the "undo" state within 8 seconds to restore the field

Insertion uses `ACTION_SET_TEXT` with the field's selection, adding a leading
space when the cursor is mid-sentence. Fields whose text cannot be read, and
fields that reject `ACTION_SET_TEXT`, fall back to the clipboard; the bubble
shows a check or says it copied. Password, OTP, and card fields never show the
bubble.

If it misbehaves, `adb logcat -s HexM0` names the path (`inserted`, `pasted`,
`copied`, or the error).

## Device checklist

M0:

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

M1:

1. The Engine card says `ready` and `Installed · 131 MB`.
2. Transcribe a clip with speech: text appears under it with run, audio, and
   load times.
3. Tap **Unload**, then transcribe again; note the cold load time separately.
4. Transcribe the model's `test_wavs/0.wav` (push it into
   `files/recordings/`) and read the result.
5. `adb shell dumpsys meminfo anomaly.hex.android | grep -E 'TOTAL|Native'`
   after a transcription, then after Unload.
6. Ten dictations in a row: no thermal throttle, no OOM, no growing latency.

### M2 (after enabling the service)

1. The Bubble card says `on`, and the service appears in Accessibility.
2. The test field shows the bubble above the keyboard; holding it records and
   releasing inserts at the cursor.
3. A real app (Messages, Gmail, Chrome) gets the text at the cursor, with a
   leading space when mid-sentence.
4. A password field hides the bubble; a field that rejects `ACTION_SET_TEXT`
   still gets the transcript on the clipboard.
5. Undo restores the field exactly, including its previous selection.
6. Switching apps mid-dictation copies instead of inserting into the wrong
   field.

Pull a debuggable build's files:

```sh
adb shell run-as anomaly.hex.android ls files/recordings
adb exec-out run-as anomaly.hex.android cat files/recordings/<name>.wav > /tmp/hex.wav
```

A release build is not debuggable; use Share, or `adb shell run-as` only on
debug.

## Notes

- The service is started by the visible activity because Android 12+ blocks
  microphone foreground-service starts from the background. The bubble's tap
  starts it, and the same service is reused for every trigger.
- The bubble is `TYPE_ACCESSIBILITY_OVERLAY`, not `SYSTEM_ALERT_WINDOW`; there
  is still no overlay permission.
- VAD and live/streaming text are not implemented; the bubble transcribes the
  finished WAV and deletes it after a successful insertion or copy. Nothing is
  downloaded by the app.
