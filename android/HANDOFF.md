# Android M0 handoff

**Date:** 2026-11-20
**State:** M0–M2 work on the 9a: capture, on-device transcription, and the
accessibility bubble insert and undo in Hex, WhatsApp, and Gmail. M3's command
mode is built (local grammar, command screen, "Hex Commands" launcher) and its
12 unit tests and lint pass; the device commands are waiting for an unlock.
This file is the work log, the evidence, and the place to record the device
results.

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
    .../accessibility/      Inserter: focus, splice, clipboard fallback
    .../audio/              WavFile, Levels, Recorder, Recordings
    .../commands/           CommandParser, CommandRunner
    .../engine/             Engine (sherpa-onnx, Parakeet CTC)
    .../service/            CaptureService, HexAccessibilityService, BubbleView
    .../ui/                 HexTheme, HexApp, DotMeter, CommandScreen
    .../CommandActivity.kt  command listening surface
    .../HexApplication.kt   crash logging
    .../DebugLog.kt         bounded in-memory log + logcat
```

M0 can: request mic/notifications, record 16 kHz mono WAVs (60 s cap, 0.3 s
minimum), show a rolling level meter, play/share recordings, run the
foreground service, and show a debug log.

M1 adds: Parakeet TDT-CTC 110M int8 in the app's private storage, a Transcribe
action on every recording, a transcript block with load/run/real-time numbers,
and Unload/Refresh. It cannot insert text or show a bubble yet.

M2 adds: the accessibility service, the `TYPE_ACCESSIBILITY_OVERLAY` bubble,
focus and password handling, `ACTION_SET_TEXT` insertion with a clipboard
fallback, eight-second undo, and the setup card's test field.

M3 adds: a separate command surface, a local command grammar, intent execution
for timers, alarms, apps, the dialer, directions, search, and system actions,
and a "Hex Commands" launcher for Quick Tap.

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
- M2: `assembleDebug`, 6 unit tests, and `lintDebug` (0 errors, 12 warnings)
  pass. The manifest declares the accessibility service with
  `isAccessibilityTool="true"`; `adb shell settings put secure
  enabled_accessibility_services` is rejected for this sideloaded build, so
  the on/off toggle has to be manual
- M3: `assembleDebug`, 12 unit tests (6 audio + 6 command parser), and
  `lintDebug` (0 errors, 15 warnings) pass. The command activity and the
  "Hex Commands" launcher alias are installed; `SET_ALARM` is the only new
  permission and `INTERNET` is still absent

Not verified yet: the bubble and insertion flow (service not enabled),
screen-locked capture, Bluetooth route behavior, and battery cost.

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
| Engine card says ready / Installed · 131 MB | pass |
| Transcribe a speech clip | pass |
| Cold load after Unload | pass, 987 ms |
| Model `test_wavs/0.wav` | pass, exact sentence |
| RSS after transcribe / after Unload | pass, 482 / 303 MB RSS |
| Ten dictations, no throttle/OOM | not tried |
| Bubble card says on / service enabled | pass |
| Bubble over the test field | pass |
| Insert into a real app | pass (WhatsApp, Gmail) |
| Password field hides the bubble | not tried |
| Undo restores the field | pass (test field, Gmail) |
| Switch apps mid-dictation | not tried |
| Command: timer by voice | pending |
| Command: open an app | pending |
| Command: dialer | pending |
| Command: screenshot | pending |
| Command: unknown inserts nothing | pending |
| Typed command harness | pending |

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

### M1 measurement (2026-11-20)

All runs are on the Pixel 9a, model loaded in the app process, four threads.
"Load" is the model load inside that call; warm runs show 0.

| Clip | Audio | Decode | Speed | Load |
| --- | --- | --- | --- | --- |
| Reference, 3.0 s | 3,000 ms | 78 ms | 38.5× | warm |
| Owner's clip, first run | 4,379 ms | 124 ms | 35.3× | 1,146 ms |
| Owner's clip, after Unload | 4,379 ms | 90 ms | 48.7× | 987 ms |
| Reference, 7.4 s | 7,435 ms | 136 ms | 54.7× | warm |
| Reference, 14.9 s | 14,870 ms | 335 ms | 44.4× | warm |

- Acceptance was a warm 5 s clip under 1.2 s; the 7.4 s clip is 136 ms.
- Accuracy: the 7.4 s reference is exact — "Well, I don't wish to see it any
  more, observed Phoebe, turning away her eyes. It is certainly very like the
  old portrait." The owner's casual 4.4 s clip came back as "U this is a test
  for the M one buildild": two errors (a leading "U", and "buildild" for
  "build"). Short, quiet, informal speech is the hard case; the reference
  shows the model itself is fine.
- Memory: loaded, TOTAL PSS 367 MB and RSS 482 MB (native heap 228 MB);
  after Unload, PSS 188 MB and RSS 303 MB. The model costs about 179 MB.
- The Engine card, Transcribe action, transcript block, and Unload/Refresh all
  worked through the UI; no crash or jank.

### M2 device run (2026-11-20)

- Enabled the service by hand: App info → Allow restricted settings →
  Accessibility → Hex dictation. `settings get secure
  enabled_accessibility_services` then listed
  `anomaly.hex.android/.service.HexAccessibilityService`.
- Two bubble dictations in the test field inserted 21 characters each; the log
  shows `Bubble: inserted 21 chars in 107 ms` and `... in 193 ms` on a warm
  model. The WAVs were deleted after insertion.
- `Bubble: undo restored` confirms the eight-second undo.
- The owner confirmed the bubble, insertion, and undo behave well.
- WhatsApp exposed its empty composer as the field's text: 7 characters
  ("Message") with `hint=-1`, so the first build produced "hello.message".
  `Inserter` now treats common composer placeholders as empty, and logs the
  node shape (`text=7->0 ... placeholder=true`) so an unknown one is visible.
  The owner confirmed WhatsApp now inserts just the transcript.
- Gmail: `com.google.android.gm|editor`, five insertions of 2–12 characters in
  104–183 ms, and three `undo restored`. The owner confirmed it works.
- Still to try: a password field, switching apps mid-dictation, and a field that
  refuses `ACTION_SET_TEXT` (the clipboard fallback).

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

## M1 engine (done)

Wired:

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

Measured on the 9a: warm decode 90–136 ms for 4–7 s clips (36–55× realtime),
987 ms cold load, +179 MB RSS while loaded, exact text on the model's reference
audio. Details are in the measurement table above.

One M1 cleanup remains: feed the live recorder's PCM straight into the engine
instead of round-tripping through a WAV. That can happen with M3, since the
bubble already records and inserts in one flow.

## M2 bubble (works in the test field, WhatsApp, and Gmail)

Verified on the 9a: the service enables, the bubble appears over the focused
field, holding it records, releasing inserts at the cursor, and undo restores
the field. The log records insertions in Hex's test field (21 characters), in
WhatsApp (after the placeholder fix), and in Gmail (2–12 characters in
104–183 ms), with several `undo restored` entries. Password suppression and
app-switching are still to check.

Design:

- `HexAccessibilityService` owns the bubble and the only insertion path. It
  tracks the focused editable node from accessibility events (debounced 120 ms)
  and keeps it as the capture target.
- The bubble is a `TYPE_ACCESSIBILITY_OVERLAY` window with
  `FLAG_NOT_FOCUSABLE`, so the app behind it keeps input focus and no
  "display over other apps" grant is needed. It sits above the IME (the
  service reads the IME window's bounds), is draggable, and remembers its
  position.
- `BubbleView` is a 76 dp custom view: idle/listening/busy/inserted/error,
  level arc, timer, check, and undo. Tap toggles hands-free, a 250 ms hold is
  push-to-talk, a drag moves it, and a tap in the undo window restores.
- `Inserter` reads the node's text and selection, splices the transcript at the
  cursor with leading-space rules, and writes it with `ACTION_SET_TEXT` plus a
  selection. If the text is unreadable or the action is refused it focuses the
  node, pastes from the clipboard, and leaves the transcript there. Password,
  OTP, and card fields are suppressed.
- After a successful insert, the WAV is deleted and "undo" stays available for
  eight seconds; the previous text and selection are restored with
  `ACTION_SET_TEXT`. A pasted or copied result has no undo.
- The setup card shows the live accessibility state, the restricted-settings
  path, and a **Test insertion** `OutlinedTextField` so the bubble can be
  exercised without leaving Hex.

Enable it by hand (the shell cannot):

1. Settings → Apps → Hex → ⋮ → **Allow restricted settings**.
2. Settings → Accessibility → **Hex dictation** → On.

Then run M2 items 7–12 in the README: test field, a real app, a password field,
undo, and switching apps mid-dictation.

Known limits: a WAV round-trip rather than live PCM; no VAD auto-stop; undo only
after `ACTION_SET_TEXT`; the clipboard fallback overwrites the user's clipboard;
switching apps mid-dictation can end in `Copied` rather than insertion.

## M3 commands (built, device test pending)

The distinction is explicit: the bubble writes, the command surface acts. A
sentence that sounds like a command never becomes one by accident.

Design:

- `CommandActivity` is a separate, full-screen listening surface. It records
  one utterance, stops after ~0.9 s of silence (4 s with no speech at all, 12 s
  hard cap), transcribes with the same engine, parses, executes, shows
  "Done" or "No command", and finishes after 2.2 s.
- `CommandParser` is a local grammar (no model, no network): timers, alarms,
  open app, dial, navigate, search, home/back, screenshot, play/pause, volume.
  Anything else is `Unknown`; nothing is inserted.
- `CommandRunner` uses platform intents: `AlarmClock.ACTION_SET_TIMER` and
  `ACTION_SET_ALARM`, launcher intents for apps, `ACTION_DIAL` (the dialer, not
  a call), Maps navigation with a geo fallback, web search, accessibility
  global actions, media keys, and stream volume.
- The trigger is a **Hex Commands** launcher alias, so Quick Tap can open it
  (Settings → System → Gestures → Quick Tap → Open app → Hex Commands), plus
  the Commands card's **Speak a command**.
- The same card has a **Test a command** field and **Run** button; it exercises
  the parser and executor without the microphone, which is how the intents are
  verified from a desk.

Device plan (after unlocking): timer by voice, "open Chrome", "call 555 1234",
"take a screenshot", "I love you" producing no action, and the typed harness.
The first launch recorded 4 s of silence and saved the WAV, but the phone locked
before the screen processed it, so the device run is still open.

After that, M4 is activation (QS tile, assistant role, volume hold) and M5 is
polish, with the order and evidence defined in the plan.

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
  built the 54 MB APK, and measured it: 987 ms cold load, 78–335 ms decode for
  3–15 s clips (36–55× realtime), +179 MB RSS, exact reference transcript.
- 2026-11-20: built M2 (accessibility service, bubble, insertion, undo, setup
  card); build, tests, and lint pass. The accessibility toggle is manual and
  the device flow is not yet exercised.
- 2026-11-20: enabled the service and verified the bubble in the test field:
  two insertions at the cursor and a working undo.
- 2026-11-20: fixed composer placeholders exposed as field text (WhatsApp's
  "Message"); verified insertion and undo in WhatsApp and Gmail.
- 2026-11-20: built M3 commands: a separate command surface, the local
  grammar and runner, the "Hex Commands" launcher alias, and a typed harness;
  12 unit tests and lint pass. Device commands await an unlock.
