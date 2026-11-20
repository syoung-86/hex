# Android M0 handoff

**Date:** 2026-11-20
**State:** M0 is installed on the 9a and recording works (the owner recorded a
clip and confirmed it). Backgrounded recording with the foreground service also
passes. M1's engine is wired, the Parakeet CTC model is pushed to the app's
private storage, and the APK builds; the transcription measurement is waiting
for the phone to reconnect. This file is the work log, the evidence, and the
place to record the device results.

- Plan: [`../docs/plans/android.md`](../docs/plans/android.md)
- App: this directory; start with [`README.md`](README.md)
- Scope: personal, one Pixel 9a, sideloaded, no distribution

## What was decided

| Topic | Decision |
| --- | --- |
| Implementation | Native Kotlin + Jetpack Compose, not Rust/Rune |
| Speech engine | sherpa-onnx (prebuilt arm64, Parakeet/Whisper/SenseVoice) |
| Insertion | Accessibility service + floating bubble; IME is the fallback |
| Activation | Bubble first; Quick Tap, QS tile, assistant role, volume hold next |
| Service | One long-lived `microphone` foreground service started from the app |
| Default model | Parakeet TDT-CTC 110M int8; 0.6B v3 is the optional accuracy model |
| Scope | Dictation only: no history, output steps, scripts, meetings, CLI, sync |
| Package | `anomaly.hex.android`, no `INTERNET` permission |
| Speech model | Parakeet TDT-CTC 110M int8, 131 MB, pushed with `adb` (not in the APK) |
| Runtime | sherpa-onnx `v1.13.5` via JitPack, arm64-v8a only |

The long version, including the activation trade-offs and the risk table, is in
the plan.

## What exists today

```text
android/
  README.md                 build, run, and the M0 device checklist
  HANDOFF.md                this file
  scripts/setup.sh          build, install, grant, launch
  scripts/doctor.sh         device, service, permissions, files, log
  app/                      one module
    .../audio/              WavFile, Levels, Recorder, Recordings
    .../engine/             Engine (sherpa-onnx, Parakeet CTC)
    .../service/            CaptureService (microphone FGS)
    .../ui/                 HexTheme, HexApp, DotMeter
    .../HexApplication.kt   crash logging
    .../DebugLog.kt         bounded in-memory log + logcat
```

M0 can: request mic/notifications, record 16 kHz mono WAVs (60 s cap, 0.3 s
minimum), show a rolling level meter, play/share recordings, run the
foreground service, and show a debug log.

M1 adds: Parakeet TDT-CTC 110M int8 in the app's private storage, a Transcribe
action on every recording, a transcript block with load/run/real-time numbers,
and Unload/Refresh. It cannot insert text or show a bubble yet.

## Verified here (Linux, no phone attached)

Toolchain used for the check, kept outside the repo:

- JDK 21 (Temurin), Android SDK 35 + build-tools 35.0.0
- Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.10, Compose BOM 2025.02.00
- Wrapper committed; `gradle-wrapper.jar` regenerated for 8.11.1

Commands and results:

```sh
cd android
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- `assembleDebug`: success, `app-debug.apk` 54 MB (31 MB of it sherpa-onnx's
  arm64 natives; arm64-v8a is the only ABI)
- `testDebugUnitTest`: 6/6 pass (WAV header, sample round-trip, WAV decode,
  RMS/dB)
- `lintDebug`: 0 errors
- Merged manifest: `CaptureService` has `foregroundServiceType=0x80`
  (`microphone`); no `INTERNET` permission
- Lint's one real complaint, a `MissingPermission` on `AudioRecord.Builder`,
  is suppressed with a comment because the UI checks `RECORD_AUDIO` first
- sherpa-onnx `v1.13.5` resolved from JitPack; the AAR's
  `libonnxruntime.so`, `libsherpa-onnx-jni.so`, and C/C++ libs package for
  arm64
- The model was pushed to the test phone and the sizes match; its hashes are
  in the README and below

Not verified yet: on-device transcription (the phone disconnected before the
measurement), screen-locked capture, Bluetooth route behavior, and battery
cost.

## First run on the 9a

1. Enable USB debugging, connect, accept the RSA prompt.
2. `cd android && ./scripts/setup.sh`
3. Run the checklist in [`README.md`](README.md#device-checklist) and record the
   answers below.
4. `./scripts/doctor.sh` when something looks wrong; `adb logcat -s HexM0` for
   the in-app log mirrored to logcat.

### Results (fill in)

| Check | Result |
| --- | --- |
| Dot row flat in silence, moves with speech | not observed yet |
| 5 s file is 160,044 bytes and plays | format and length verified; playback not tried |
| Service + record + Home + lock 10 s still saves | Home pass; lock not tried |
| Stop after 0.2 s gives the short-capture error | not tried |
| 90 s recording stops at 60 s (~1.92 MB) | not tried |
| Recording works with the service stopped | not tried |
| Bluetooth headset: which mic wins | not tried |
| Engine card says ready / Installed · 131 MB | model files present; UI not re-checked |
| Transcribe a speech clip | pending phone reconnect |
| Cold load after Unload | pending |
| Model `test_wavs/0.wav` | pending |
| RSS after transcribe / after Unload | pending |
| Ten dictations, no throttle/OOM | pending |

### First device run (2026-11-20)

- `./scripts/setup.sh` installed the debug APK, granted the microphone and
  notifications, and whitelisted battery; the app launches and renders.
- Two test captures saved: 10.8 s (346,280 B) and 5.2 s (167,720 B).
- The pulled 5.2 s file is RIFF PCM: 16,000 Hz, mono, 16-bit, 83,838 frames,
  5.24 s. It was near-silent (peak 0.4% FS), consistent with a quiet room; it
  is not evidence of a dead microphone.
- The 5.2 s capture happened with Hex backgrounded: the service stayed
  `isForeground=true`, `types=0x00000080` (microphone), and the WAV still
  finished. This is the M0 question answered.
- The owner recorded a 1.9 s clip by hand and confirmed it worked.

## Research behind the plan

- Pixel 9a: Tensor G4, 8 GB RAM, Android 16, 7 years of updates, power + volume
  only. Quick Tap is supported:
  <https://support.google.com/pixelphone/answer/7443425>
- Quick Tap reliability improved with Android 16 QPR2:
  <https://www.androidauthority.com/google-pixel-quick-tap-gesture-improvement-3622638/>
- A third-party app can hold `ROLE_ASSISTANT` and take long-press power or the
  corner swipe; ChatGPT already does:
  <https://9to5google.com/2025/03/14/chatgpt-default-assistant-android/>
- Volume keys can be observed and filtered by an `AccessibilityService` with
  `FLAG_REQUEST_FILTER_KEY_EVENTS`; only one such service can be active:
  <https://developer.android.com/reference/android/accessibilityservice/AccessibilityService>
- Wispr Flow's Android shape is the bubble + accessibility model:
  <https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings>
- A voice IME can record while its window is visible, so hold-to-talk needs no
  foreground service; background use does:
  <https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start>
- Microphone foreground services started from the background are restricted on
  Android 12+, and `microphone` cannot start from `BOOT_COMPLETED` on 15+; the
  plan starts one from the app and reuses it.
- sherpa-onnx Android: prebuilt AAR via JitPack
  (`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:<pin>`), Parakeet TDT int8 and a
  VAD + offline ASR demo:
  <https://k2-fsa.github.io/sherpa/onnx/java-api/android.html>,
  <https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo/parakeet-tdt-0.6b-v2.html>
- Sideloaded apps get Android 15+ restricted settings (App info → ⋮ → Allow
  restricted settings) before accessibility/overlay grants; Android 17's
  Advanced Protection Mode blocks accessibility unless
  `isAccessibilityTool="true"`:
  <https://developer.android.com/privacy-and-security/advanced-protection-mode>

## Effort estimate

One experienced Android dev: MVP (bubble, insertion, engine, styles) about
2–3 weeks focused; something trusted daily 4–6 weeks part-time. The iOS app is
the comparison: about 23k lines of Swift, 37 commits, July–November 2026. The
engine is off the shelf; insertion and activation polish are the project.

## M1 engine (in progress)

Done:

- sherpa-onnx `v1.13.5` from JitPack; only `arm64-v8a` is packaged, which
  keeps the debug APK at 54 MB.
- Model: Parakeet TDT-CTC 110M int8, `model.int8.onnx` 131,652,171 B
  (`9177a914…81fc1a`) and `tokens.txt` 9,953 B (`450e56bd…e6cd10`), from the
  sherpa-onnx `asr-models` release; NVIDIA CC-BY-4.0.
- The model is in `files/models/parakeet-ctc-110m/` in app-private storage, so
  it survives updates and the app still has no `INTERNET` permission. It is
  pushed with `adb` (commands in the README); SAF import comes later.
- `Engine` singleton: `OfflineRecognizer` with
  `OfflineNemoEncDecCtcModelConfig`, 4 threads, loaded on the IO dispatcher and
  kept warm; **Unload** releases it. `WavFile.decodePcm16` feeds it.
- UI: a **Transcribe** action on every recording, a transcript block with run,
  audio, load, and × realtime, and an Engine card with ready/missing,
  **Refresh**, **Unload**, and **Transcribe last**.

Still to do on the phone:

1. Reconnect, install the new APK, and tap **Refresh** (or force-stop and
   relaunch) so the Engine card re-reads the files.
2. Transcribe the owner's clip and the model's `test_wavs/0.wav`.
3. Record cold load, run time, real-time factor, and RSS
   (`adb shell dumpsys meminfo anomaly.hex.android`).
4. Acceptance: a warm 5 s clip transcribes in under 1.2 s on the 9a.
5. Then feed the live recorder's PCM straight into the engine instead of
   round-tripping through a WAV.

After that, M2 (accessibility bubble and insertion), then M3 (activation), with
the order and evidence defined in the plan.

## Open items

- Release signing is not set up. M0 installs debug; `keystore.properties` and
  a local keystore are needed for `--release`.
- `compileSdk` is 35 in the scaffold; the plan targets 36. Bump with the next
  Android Studio/Gradle upgrade and re-run lint.
- The assistant role, volume hold, and Quick Tap are all still untested on
  hardware. The plan deliberately keeps them optional.
- All of this is committed on the `android` branch; `dev` is untouched. The
  branch exists to keep a personal experiment out of the shared line until the
  phone checks pass.

## Changelog

- 2026-11-20: wrote the plan; scaffolded M0; verified build, tests, and lint;
  installed on the 9a; recorded foreground, backgrounded, and hand-made clips;
  background recording with the foreground service passed.
- 2026-11-20: wired M1 (sherpa-onnx + Parakeet CTC 110M), pushed the model,
  built the 54 MB APK; transcription measurement awaits the phone.
