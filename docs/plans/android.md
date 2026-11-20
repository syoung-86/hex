# Android Plan (personal build)

**Status:** M0, M1, and the M2 bubble work on the 9a. Capture and the
foreground service work; Parakeet TDT-CTC 110M transcribes on-device (987 ms
cold load, 36–55× realtime, +179 MB RSS); the bubble inserts at the cursor in
the test field and undo restores it. Real-app insertion and the remaining M2
checks are next. The work log, measured numbers, and device checklist live in
[`../../android/HANDOFF.md`](../../android/HANDOFF.md). This is a personal,
single-device build for one Pixel 9a,
installed by `adb` and used by nobody else. It is not a Hex platform, has no
distribution channel, no updater, and no support surface. Nothing here changes
the desktop or iOS contracts, and nothing here ships in a release.

Decision (2026-11-20): phone-only, one user, no Play Store, no public APK, no
telemetry, no network permission in v1.

## The Goal

Hold something, talk, and the words land at the cursor in whatever app is open.
Everything runs on the phone. Activation has to work with only a power button
and a volume rocker, and text insertion has to work without switching keyboards.

Success is one sentence: **for a week I dictate with Hex on the 9a and stop
reaching for Gboard's microphone.**

## Device Contract

| Area | Contract |
| --- | --- |
| Device | Pixel 9a (`GXQ96`), Tensor G4, 8 GB RAM, arm64-v8a |
| OS | Android 16, `compileSdk`/`targetSdk` 36, `minSdk` 33 (AGSL, `TileService`) |
| Install | self-signed release APK over `adb install -r`, keystore local and ignored |
| Data | app-private storage; model imported once by the user |
| Network | none in v1 (no `INTERNET` permission); model files pushed over adb |
| Distribution | none. Do not publish, do not add to downloads, do not use a public app id |
| Support | me. logcat plus an in-app debug log export |
| Failure mode | a broken build is one `adb install` away from fixed |

## Decisions

**D1. Native Kotlin, not Rust.** The desktop crate has no `lib.rs`; it is a
Rune/winit application and the transcription engine is wired through desktop
concerns. The iOS app already set the precedent: reimplement the product, reuse
the thinking. Android gets Jetpack Compose, not Rune.

**D2. sherpa-onnx for speech.** Prebuilt arm64 AAR via JitPack
(`com.github.k2-fsa.sherpa-onnx:sherpa-onnx:<pin>`), Kotlin API, no NDK. It
covers Parakeet TDT, Parakeet TDT-CTC 110M, SenseVoice, and Whisper. This is the
single biggest shortcut in the project; do not start by cross-compiling
`transcribe.cpp`.

**D3. Floating bubble plus accessibility, not a keyboard.** An
`AccessibilityService` owns the bubble (as a `TYPE_ACCESSIBILITY_OVERLAY`
window, so no "display over other apps" grant) and inserts text into the focused
field. It does not replace Gboard. This is the Wispr Flow shape, and it is the
only shape that gives hold-to-talk in every app without a focus-stealing
activity switch. Keep a minimal IME as the fallback plan (D3b below), not the
starting point.

**D4. One process, one long-lived foreground service.** The accessibility
service, the bubble, the recorder, the engine, and the settings all live in the
app process. A `microphone` foreground service ("Hex is ready") is started by
the first bubble tap and kept alive; triggers then only start recording inside
it. This avoids a new foreground-service start on every trigger, which Android
12+ restricts.

**D5. Parakeet TDT-CTC 110M by default.** Small enough to keep warm on an 8 GB
phone, fast enough for push-to-talk. Parakeet 0.6B v3 int8 is the optional
"accuracy" model for when the 110M model misses names. Model files are imported,
never downloaded, in v1.

**D6. Activation is a menu, not one shortcut.** The bubble is the primary
trigger because it keeps input focus. Quick Tap, the assistant role, and volume
hold are additions to test in week one; see [Activation](#activation).

**D7. Scope is dictation only.** No History, no Output steps, no user scripts,
no Meetings, no Annotate, no CLI/SDK, no sync. One "undo last insert" is enough
memory for v1.

## Architecture

One Gradle module. One process.

```text
MainActivity (Compose)
  setup • settings • model import • debug log

HexAccessibilityService        (bound by the system while enabled)
  bubble overlay (TYPE_ACCESSIBILITY_OVERLAY, touchable, draggable)
  focus tracking (editable nodes, password/OTP suppression)
  insertion (ACTION_SET_TEXT splice, clipboard fallback)
  optional key filter (volume hold)

DictationService               (microphone FGS, START_STICKY)
  AudioCapture   AudioRecord 16 kHz mono PCM16, RMS levels
  Vad            Silero ONNX (sherpa-onnx) or RMS fallback
  Engine         OfflineRecognizer, lazy load, warm window
  Controller     Idle → Listening → Transcribing → Inserting → Idle
  notification   "Hex is ready" / Stop / Cancel

Settings (DataStore)
  style, model, keepWarm, bubbleOnFocus, volumeHold, assistantTrigger
```

Threads:

- Audio thread: `AudioRecord.read` into a 60 s ring; computes RMS per 20 ms;
  feeds VAD; posts levels at ~30 Hz.
- Engine thread: one inference at a time; no cancellation mid-inference (a 5 s
  clip is short enough to let finish), but stale results are dropped by
  generation number, same as iOS `DictationController`.
- Main thread: state, bubble animation, insertion.

The state machine mirrors the iOS one where it matters: minimum 0.30 s capture,
60 s maximum, a generation counter, and a result that is still deliverable for
30 s after the UI closes.

## Activation

Ranked by whether the trigger steals input focus. Focus-stealing triggers need
a fallback (clipboard paste) and are therefore secondary.

| Trigger | Setup | Keeps focus | Notes |
| --- | --- | --- | --- |
| Bubble tap / hold | enable accessibility | yes | primary; tap = hands-free, hold = push-to-talk |
| Quick Settings tile | add tile | mostly yes | `TileService`; start a transparent activity to start the FGS if needed |
| Notification action | none | yes | "Dictate" action on the ready notification |
| Volume hold | accessibility + opt-in | yes | `FLAG_REQUEST_FILTER_KEY_EVENTS`; filter only while an editable field is focused, pass through otherwise |
| Quick Tap (double-tap back) | Settings → System → Gestures → Quick Tap → Open app | no | confirmed on the 9a; transparent activity, then return; may drop the field's focus |
| Digital assistant (long-press power) | Settings → Apps → Default apps → Digital assistant app | no | `ACTION_ASSIST` + `ROLE_ASSISTANT`; displaces Gemini; needs its own session UI |
| Launcher shortcut | none | no | useful for testing |
| Headset media button | `MediaSession` | yes | stretch goal; only if a spare button is wanted |

The daily flow should be: press and hold the bubble, speak, release, text
appears. That is the desktop interaction with a thumb instead of a key.

### Bubble

- Shown when the accessibility service sees an editable node with input focus,
  hidden in password/OTP/card fields and in `FLAG_SECURE` windows.
- Positioned just above the IME window when one exists (read the IME window's
  bounds via `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`); otherwise draggable, and the
  position persists.
- States: idle (dot), listening (dot scope + timer + levels), transcribing,
  inserted (brief "Undo" chip), error (tap for the reason).
- Tap starts hands-free listening and stops on VAD silence; press-and-hold is
  push-to-talk and stops on release. Both are one gesture, so there is no mode
  setting to get wrong.
- The overlay must be `FLAG_NOT_FOCUSABLE` so tapping it never moves focus away
  from the host app.

### Assistant role

`ACTION_ASSIST` plus `ROLE_ASSISTANT`. On a Pixel the user picks Hex under
Settings → Apps → Default apps → Digital assistant app, then long-press power
(or corner swipe) invokes it. Unlike a keyboard, this gives no key-release
event, so a session ends on VAD silence or a tap. It also displaces Gemini as
the long-press target (Gemini stays reachable from its app).

This path needs a `VoiceInteractionService` and a session window. Try it in M3;
keep it only if the session can stay out of the way and the field keeps focus.
If not, drop it: Quick Tap plus the bubble already covers the "activate from
outside" case.

### Volume hold

An accessibility service with `FLAG_REQUEST_FILTER_KEY_EVENTS` receives volume
key down/up before the system does. Hold volume-down for 250 ms to start
listening, release to stop. Two rules keep it livable:

- Only consume the key while an editable field is focused; otherwise return
  `false` so volume works normally.
- Do not consume it while the screen is off (behavior varies by ROM, and the
  accessibility service may not see the event at all).

Only one app can filter keys at a time, so this conflicts with Button Mapper
and similar tools. It is opt-in and off by default.

## Text Insertion

The accessibility service is the only place that knows about the target field.

1. `rootInActiveWindow.findFocus(FOCUS_INPUT)`; require `isEditable`, not
   `isPassword`, and an input type that allows text.
2. Read `text` and `selectionStart`/`selectionEnd`.
3. Splice the transcript at the selection, applying Hex's spacing rules (a
   leading space when the character before the cursor is not whitespace or a
   line start; no double space; style-dependent casing).
4. `ACTION_SET_TEXT` with the full new string and the new selection.
5. Re-read the node and compare. On mismatch, or when the node exposes no
   text, fall back to: put the transcript on the clipboard, focus the node,
   `ACTION_PASTE`, and show a "Copied" chip if that also fails.
6. Keep the node, the inserted range, and the previous text for 30 s so the
   bubble can offer "Undo" (restore the previous text and selection).

Known weak spots to test, not to assume: Chrome's address bar, Slack, Discord,
Gmail's compose, Compose text fields, WebViews, and anything with a custom
`InputConnection`. Password fields and banking apps are out of scope by design.

If insertion quality is poor after M2, switch to **D3b: a minimal IME**.
`InputMethodService` with a Hex key, `commitText`, backspace, return, and
"switch keyboard" — no QWERTY. It is more reliable and needs no accessibility
grant, at the cost of replacing the keyboard while dictating. Do not build both
before measuring.

## Audio

- `AudioRecord`, 16 kHz mono PCM16, `VOICE_RECOGNITION` source, 20 ms reads.
- 60 s maximum ring; 0.30 s minimum; discard shorter taps.
- RMS levels for the dot scope; no audio ever leaves the app.
- VAD: sherpa-onnx Silero VAD for auto-stop (700 ms of silence after speech),
  with an RMS-energy fallback while the model is loading.
- End on: bubble tap/release, VAD silence in hands-free mode, notification Stop,
  or a 60 s cap.
- Interruptions: audio focus loss, a phone call (`AudioManager.mode` change),
  or the mic being taken by another app. Finalize captures of 300 ms or more,
  cancel shorter ones. Prefer the built-in mic; Bluetooth HFP can wait.
- The engine is only fed 16 kHz float samples; keep the WAV writer for debug
  builds only.

## Engine

- `OfflineRecognizer` with `OfflineTransducerModelConfig` and
  `modelType = "nemo_transducer"` for Parakeet, `OfflineSenseVoiceModelConfig`
  for SenseVoice. `numThreads = 4` on the 9a; expose 2–6 in settings for
  experiments.
- Load lazily on the first dictation, not at boot. Unload after 5 minutes idle,
  on `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`, and on the notification's
  "Free memory" action.
- Models (pick one for v1, import the rest later):
  - Parakeet TDT-CTC 110M int8 — default, English, small, fast.
  - Parakeet 0.6B v3 int8 — optional, ~600 MB encoder, ~1 GB RSS while
    decoding; only with the warm model loaded, and expect the low-memory
    killer to have opinions.
  - SenseVoice Small int8 (~250 MB), Whisper base.en int8 (~75 MB) as
    alternatives for accuracy/size tests.
- Model import: SAF from `Download/hex-models/`, copy into
  `filesDir/models/<id>/`, verify a pinned SHA-256 and total size, write a
  `manifest.json`, and never re-verify on every load.
- No partial transcripts in v1. The desktop contract is "text ready when you
  let go"; sherpa's simulated streaming is a stretch goal after the rest works.
- Latency budget on the 9a: 5 s utterance, release to inserted text under
  1.2 s with the 110M model and the model already warm. Record the actual
  number in this document after M1.

## UI

Three surfaces, no tabs:

- **Setup** (first run and when something breaks): microphone, accessibility,
  notifications, battery exemption, model import, and a test box that records,
  transcribes, and inserts into itself. Each row shows live status and the
  exact Settings path. Show the Android 15+ "Allow restricted settings" step
  before accessibility can be enabled for a sideloaded APK.
- **Bubble**: described above; the whole feature lives here.
- **Settings**: style (Polished, Casual, Notes, Verbatim), model, warm window,
  activation toggles (bubble, volume hold, assistant), "copy last result",
  debug log export, and a "Free memory" action.

No History, no Output, no Labs. Port Hex's formatter and vocabulary matching
from iOS (`TranscriptFormatter`, `VocabularySpelling`, `CleanupReconstruction`)
and keep their unit tests; those are small and they are what makes the text
feel like Hex.

## Manifest and Permissions

v1, deliberately small:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- no INTERNET, no overlay permission: the bubble is an accessibility overlay -->
```

The accessibility service config:

```xml
<accessibility-service
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagRequestFilterKeyEvents"
    android:canRequestFilterKeyEvents="true"
    android:canRetrieveWindowContent="true"
    android:isAccessibilityTool="true"
    android:notificationTimeout="100" />
```

`isAccessibilityTool="true"` is honest here (voice input is an assistive input
method) and matters on Android 17, where Advanced Protection Mode blocks
accessibility for apps that do not declare it. Do not enable Advanced
Protection Mode on the phone; it also blocks sideloading.

Sideloaded apps get "restricted settings" on Android 15+: after installing,
App info → ⋮ → **Allow restricted settings**, then enable the accessibility
service. The setup screen must say this, and `scripts/setup.sh` should offer the
`adb shell settings put secure enabled_accessibility_services ...` fallback.

## Build, Install, Debug

```text
android/
  app/                     Kotlin + Compose, one module
  scripts/setup.sh         build, install, push model, grant, enable service
  scripts/doctor.sh        adb logcat -s Hex, current window, focused node dump
  README.md                the one-page personal runbook
```

- Gradle with the current stable AGP, Kotlin 2.x, Compose BOM; JitPack for
  sherpa-onnx, pinned.
- A local keystore generated once and kept outside the repo
  (`~/.android/hex-personal.jks`); `keystore.properties` is gitignored. No Play,
  no key rotation, no signing ceremony.
- `scripts/setup.sh` is the whole installation story: `assembleRelease`,
  `adb install -r`, push the model into `Download/hex-models/` if present,
  print the two manual permission steps. Since this is one device, scripted
  setup is allowed in a way the shipped product's never could be.
- Logging: a ring buffer in memory plus an opt-in file, exported through the
  share sheet. Transcript text is redacted unless a debug toggle is on.
- Tests: unit tests for formatter, spacing, vocabulary, and the state machine;
  one instrumentation test for the inserter against a test activity. No CI, no
  goldens, no release workflow. Keep `./gradlew assembleDebug` green.

## Milestones

Each milestone ends with evidence measured on the 9a, recorded here.

**M0 — Skeleton (1 day).** App builds and installs; mic permission; record 5 s
to a WAV; play it back; levels move. Evidence: file size and a listen.

Scaffolded at [`android/`](../../android/README.md). `assembleDebug`, the WAV
and level unit tests, and `lintDebug` (0 errors) pass locally; the device
checklist in `android/README.md` is still to run. M0 pins `compileSdk 35`; M1
bumps to 36 with the next toolchain update.

**M1 — Engine (2–3 days).** Import the 110M model; transcribe a bundled WAV and
then live mic. Evidence: real-time factor, RSS, cold load time, and the
release-to-text number for 3 s, 5 s, and 15 s clips.

Wired: sherpa-onnx `v1.13.5` (JitPack), Parakeet TDT-CTC 110M int8 as a single
131 MB ONNX pushed with `adb` into app-private storage; a Transcribe action
per recording and an Engine card (Refresh, Unload, Transcribe last).

Measured on the 9a: 987 ms cold load; 78 ms for 3.0 s, 90 ms for 4.4 s,
136 ms for 7.4 s, and 335 ms for 14.9 s (36–55× realtime); +179 MB RSS while
loaded; the model's 7.4 s reference audio is transcribed exactly. A warm 5 s
clip is far under the 1.2 s target. Detail in
[`android/HANDOFF.md`](../../android/HANDOFF.md).

**M2 — Bubble and insertion (3–5 days).** Accessibility overlay bubble, focus
detection, splice insertion, undo, password suppression, clipboard fallback.
Evidence: successful inserts in Messages, Gmail, Chrome, Slack, Discord, a
Compose app, and a WebView; a list of the ones that failed and why.

Built: `HexAccessibilityService` + `BubbleView` + `Inserter`, the setup card,
and a test field. Build, tests, and lint pass. Verified on the 9a in the test
field: two insertions at the cursor and a working undo. Real-app insertion, a
password field, and switching apps mid-dictation are still open; M2 is not done
until items 3–6 in `android/README.md` pass.

**M3 — Activation (2–3 days).** Quick Tap, QS tile, notification action,
volume-hold, and (optionally) the assistant role. Evidence: each trigger
recorded from a cold process, with the field still focused on completion.

**M4 — Polish (3–5 days).** Warm/cold policy, styles and spacing, error and
recovery states, setup screen, debug export, battery check. Evidence: a day of
normal use with no ANR, no OOM, and no stuck bubble.

**M5 — Week of use (7 days, no code).** Use only Hex for dictation. Log every
failure: wrong field, lost focus, late text, missed speech, model miss. Then
decide whether D3b (the IME) is needed, whether 0.6B is worth the memory, and
whether the assistant role survives.

Rough total: 2–3 weeks of focused work for a usable tool, 4–6 weeks of
part-time work for something trusted daily. The engine is off the shelf; the
insertion and activation polish is the project.

## Acceptance Targets

- 5 s utterance: under 1.2 s from release to inserted text, model warm.
- 50 dictations in a day: zero dropped captures, zero ANRs, zero OOM kills.
- Works in: Messages, Gmail compose, Chrome address bar, Slack, Discord, a
  notes app, a Compose field, a WebView field.
- Survives: screen lock mid-dictation, an incoming call, a Bluetooth headset
  connect, and a battery-saver day.
- Idle: model unloaded after 5 minutes, under 50 MB RSS, 0% CPU, one quiet
  notification.
- Privacy: no network permission; airplane mode changes nothing.

## Risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Accessibility insertion is unreliable in some apps | high | clipboard fallback first; IME (D3b) if M5 says so |
| Sideloaded accessibility is blocked by restricted settings / AAPM | medium | setup screen with the exact steps; `isAccessibilityTool`; adb fallback; do not enable AAPM |
| 0.6B model OOMs alongside a heavy browser | medium | 110M default; free on trim; large heap not a fix for native RSS |
| Foreground service killed or mic denied after background start | medium | one long-lived service started by a bubble tap; verify in M0; no boot-start |
| VAD stops too early for slow speakers | low | 700 ms silence, minimum speech duration, tap to stop always works |
| Tensor G4 throttles in long sessions | low | short clips; unload on idle; no meetings |
| Sideload/setup friction every time the phone updates | low | runbook plus `setup.sh`; it is one device |
| Scope creep toward History/Output/Labs | medium | D7; this plan is the scope |

## Non-Goals

- Publishing anything, Play Store, F-Droid, TestFlight equivalent, downloads
  page, release notes.
- Desktop or iOS parity. No History, Output steps, user scripts, Meetings,
  Annotate, CLI, SDK, local API, live activities, widgets.
- Updater, telemetry, crash reporting, accounts, sync.
- Multi-device, tablets, foldables, other OEMs, other Android versions.
- Reusing the Rust crate or the Rune UI.

## Open Questions (answer during M2–M5)

1. Bubble or IME as the permanent daily driver?
2. Is Quick Tap's focus loss acceptable, or is the bubble the only external
   trigger worth keeping?
3. Does the assistant role earn its Gemini displacement?
4. 110M or 0.6B as the default after a week of real names and jargon?
5. Do learned fixes / vocabulary belong in a personal build, or is the formatter
   enough?
6. Is 700 ms of silence the right auto-stop, or should hands-free default to
   tap-to-stop?
