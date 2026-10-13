# Voice wake-up plan

Status: route-independent logic and an experimental DAT-transcript standby implemented (13 October 2026); hardware baseline and acceptance checks **not yet run**.
Prepared: 13 October 2026.

## Review notes (13 October 2026)

Findings from inspecting the `mwdat-speech` 1.0.0 public API in the Gradle cache:

- `Speech` exposes only `start()`, `stop()`, and `StateFlow`s for state, transcriptions, locale and errors. `SpeechConfiguration` has no continuous or wake mode.
- Recognition is utterance-scoped: a glasses completion event ends listening and the state returns to STOPPED. A DAT-based standby must therefore re-arm after every utterance. Speech said during the restart gap is lost, so wake reliability depends on how short that gap is on real hardware.
- `transcriptions` is a `StateFlow`, so it conflates and drops a result equal to the previous one. Two identical consecutive finals (same text, final flag and confidence) would surface once. This is low-risk because partials normally intervene, but watch for it in the repeated-use check.
- Unknown and only checkable on hardware: whether each `start()` plays a glasses chime, the battery cost of constant re-arming, whether Speech survives a locked screen without a foreground service, and where transcription runs (glasses, phone or Meta cloud). The last matters for privacy, because standby sends all nearby speech through that recognizer.

Plan adjustments:

- The wake-phrase gate is a pure transcript matcher, so it is shared by either route (DAT Speech or Vosk text output).
- The DAT route is shipped behind an opt-in “voice wake-up (experimental)” toggle. Turning it on is the step-2 feasibility probe, not a claim of hands-free support.
- The SDK ends a Speech session about 60 s after start without updating `state`, so the app restarts sessions by age; see CONTEXT.md, troubleshooting item 7.
- Locked-phone use worked for about 2 minutes while the app was the top activity. A `connectedDevice` foreground service and a partial wake lock were then added so standby survives app switches and longer locks. Moving the pipeline itself out of the Activity is the next step if the Activity proves to be killed while the service runs.

## Intended experience

After initial pairing, permission grants, and explicitly enabling voice wake-up, the wearer can keep the phone in a pocket and ask Hermes through the glasses.

Proposed flow:

```text
Wake standby → “Hey Hermes” → question capture → local Hermes/MLX
             → spoken reply through glasses → wake standby
```

The wake phrase starts one question. After replying, the app returns to wake standby. It should not keep submitting ordinary conversation as questions. A standby detector still needs microphone audio or on-glasses transcripts; distinguish that clearly from an active question in the interface.

The existing Mac bridge and local model remain the response path. Initial setup can require the phone. Waking the app after it has been force-stopped is a separate capability and is not assumed here.

## 1. Establish the physical-device baseline

- Confirm the phone is authorized in ADB, glasses are paired, DAT runtime is installed, and microphone permission is granted.
- Test a typed question, then a phone-button-started spoken question in Hermes Glasses. Veyra working does not establish that Hermes Glasses' speech path works.
- Record DAT session/speech states and error codes without logging credentials or full utterances.
- Verify which microphone is used. Speak near the glasses with the phone away from the mouth; do not mistake phone-mic recognition for glasses-mic support.
- Test screen-off and locked-phone behavior, Bluetooth reconnect, and glasses folding/unfolding.

Deliverable: a short evidence record in CONTEXT.md identifying the working speech/audio route and any device limitation. Resolve a failing baseline before adding wake-up logic.

## 2. Choose the narrowest reliable activation path

Investigate in this order:

1. **Existing on-glasses DAT Speech:** determine whether it can continuously provide transcripts, reliably rearm between utterances, and run while the phone is locked. If so, a local transcript gate may detect the wake phrase without a second speech engine.
2. **Offline Android wake detector:** if the first route is unsuitable, evaluate a small offline engine such as Vosk, which was found in the tested Veyra APK. Prove that it receives the glasses microphone over a supported Bluetooth route. Review current Android foreground-service and microphone rules before implementation.

Avoid choosing a library solely because Veyra uses it. Capture the route, lifecycle, dependency/model size, and observed device behavior before committing to the design. Do not assume DAT Speech and Bluetooth call audio can safely run together.

Meta's experimental Voice Invocations API can launch an app with “Hey Meta, start {app name}.” Treat it as a separate possible enhancement: the user reports Meta's assistant is unavailable in their region, and voice invocation has not been tested on their hardware.

## 3. Implement the interaction and lifecycle

- Reuse `TapToTalkStateMachine` where its states fit; extend or extract a coordinator at the responsible boundary rather than duplicating the question pipeline.
- Make wake-up explicitly enabled/disabled, with a visible standby/capturing/busy status and an immediate stop control.
- Handle both a separate wake phrase followed by a question and a combined utterance such as “Hey Hermes, what time is it?” Preserve the question when stripping the phrase.
- Prevent repeated wake detections, duplicate submissions, and wake activation from the app's own cues or spoken replies.
- Suspend detection during processing/playback and rearm after completion or recoverable failure.
- Bound question capture with cancellation and a timeout. Recover cleanly from disconnects and release microphone resources when disabled.
- Retain phone-button and typed activation. Preserve current language selection, encrypted bridge key storage, bearer authentication, and conversation IDs.
- Add a foreground service and its permissions only if required by the proven microphone route and locked-phone acceptance. Implement the appropriate Android lifecycle rather than keeping recognition in an Activity that can disappear.

Implemented so far (unit-tested, not hardware-verified):

- `interaction/WakePhraseMatcher.kt`: greeting plus name at the start of an utterance (fillers skipped); case, punctuation and accent insensitive; tolerant name variants; keeps the original question wording. A bare name or a mid-sentence mention does not match.
- `TapToTalkStateMachine`: new STANDBY state and events for wake enable/disable, recognizer ready/stopped/failed, and a generation-checked question timeout for wake-started captures. The Activity times out after 8 s with no speech; partial transcripts restart the timer. A fixed 10 s limit cut off real questions, which took 6–8 s to arrive as finals. With wake-up off, behaviour and existing tests are unchanged. The detector is disarmed while BUSY and re-armed only after playback finishes or fails.
- `MainActivity`: toggle persisted in `SecureSettings.wakeEnabled`; DAT Speech re-arm loop (300 ms delay; it gives up after 5 stops within 1.5 s of arming); wake cue; standby transcripts are not shown on screen.

Original likely touch points:

- `android/app/src/main/java/ai/hermes/glasses/MainActivity.kt`
- `android/app/src/main/java/ai/hermes/glasses/interaction/`
- `android/app/src/main/java/ai/hermes/glasses/storage/SecureSettings.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/build.gradle.kts` if the chosen route needs a dependency

The bridge should need no protocol change for text-based wake activation.

## 4. Acceptance and verification

Automated checks should cover behavior that can regress: phrase matching, preserving a combined question, duplicate events, timeout/cancel, response feedback suppression, and return to standby after success/failure. Run the existing Android and bridge checks once the relevant changes are complete.

On the user's Fold7 and Ray-Ban Wayfarer Gen 2, verify:

| Check | Required result |
|---|---|
| Wake phrase | Starts question capture without a phone tap |
| Glasses microphone | Works with the phone away from the user's mouth |
| Locked phone | At least five minutes armed, then a successful wake/question/reply |
| Repeated use | Three consecutive wake/question/reply cycles return to standby |
| Ordinary conversation | No unintended Hermes submissions during a five-minute sample |
| Reply playback | Does not activate or submit another question |
| Network or Bluetooth interruption | Shows a meaningful state and recovers without duplicate questions |
| Disable wake-up | Releases the detector; no wake activation occurs |
| Existing controls | Typed questions and phone-button activation still work |

A five-minute sample is a practical acceptance check, not proof of zero false activations in all environments. Record failures and device limits honestly.

## Follow-up scope

Camera capture and visual questions come after voice wake-up is reliable. Motion gestures, cloning the user's voice, new model providers, and a replacement for Meta's native assistant are not part of this first feature.
