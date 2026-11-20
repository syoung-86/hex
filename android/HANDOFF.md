# Android M0 handoff

**Date:** 2026-11-20
**State:** the plan and the M0 scaffold exist and build. Nothing has run on the
phone yet. This file is the work log, the evidence, and the place to record the
device results.

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
    .../service/            CaptureService (microphone FGS)
    .../ui/                 HexTheme, HexApp, DotMeter
    .../HexApplication.kt   crash logging
    .../DebugLog.kt         bounded in-memory log + logcat
```

M0 can: request mic/notifications, record 16 kHz mono WAVs (60 s cap, 0.3 s
minimum), show a rolling level meter, play/share recordings, run the
foreground service, and show a debug log. It cannot transcribe, insert text, or
show a bubble yet.

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

- `assembleDebug`: success, `app-debug.apk` 24,217,126 bytes
- `testDebugUnitTest`: 5/5 pass (WAV header and sample round-trip, RMS/dB)
- `lintDebug`: 0 errors, 8 warnings
- Merged manifest: `CaptureService` has `foregroundServiceType=0x80`
  (`microphone`); no `INTERNET` permission
- Lint's one real complaint, a `MissingPermission` on `AudioRecord.Builder`,
  is suppressed with a comment because the UI checks `RECORD_AUDIO` first

Not verified: install, real mic levels, service survival in the background or
with the screen locked, Bluetooth route behavior, and battery cost. Those need
the phone.

## First run on the 9a

1. Enable USB debugging, connect, accept the RSA prompt.
2. `cd android && ./scripts/setup.sh`
3. Run the checklist in [`README.md`](README.md#m0-checklist) and record the
   answers below.
4. `./scripts/doctor.sh` when something looks wrong; `adb logcat -s HexM0` for
   the in-app log mirrored to logcat.

### Results (fill in)

| Check | Result |
| --- | --- |
| Dot row flat in silence, moves with speech | |
| 5 s file is 160,044 bytes and plays | |
| Service + record + Home + lock 10 s still saves | |
| Stop after 0.2 s gives the short-capture error | |
| 90 s recording stops at 60 s (~1.92 MB) | |
| Recording works with the service stopped | |
| Bluetooth headset: which mic wins | |

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

## Next: M1 (engine)

1. Add the JitPack repository and pin the sherpa-onnx dependency.
2. Model import screen: `ACTION_OPEN_DOCUMENT_TREE` over a `hex-models`
   directory, copy into `filesDir/models/`, verify size and SHA-256, store a
   manifest. No downloader.
3. `Engine` singleton: build an `OfflineRecognizer` with
   `modelType = "nemo_transducer"`, `numThreads = 4`; load on IO, unload on trim
   and after 5 minutes idle.
4. A "Transcribe" action on M0's recordings, showing text, wall time, and cold
   load time.
5. Measure and record here: cold load, real-time factor for 3/5/15 s clips,
   release-to-text, RSS (`adb shell dumpsys meminfo anomaly.hex.android`).
6. Acceptance: a warm 5 s clip transcribes in under 1.2 s; no OOM on the 8 GB
   device; then feed the live recorder's PCM into the same path.

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
  recorded the unanswered device checks.
