# Hermes Glasses: start here

This is the owner's working checkout of this repository.

Before planning or changing voice activation, read:

1. [Current context and handoff](docs/voice-wakeup/CONTEXT.md)
2. [Voice wake-up plan](docs/voice-wakeup/PLAN.md)

Photo capture and analysis is implemented (experimental DAT standalone photo, MEDIUM/MEDIUM, sent as JPEG through the bridge): see [Photo capture plan](docs/photo-capture/PLAN.md).

Voice wake-up is implemented as an experimental opt-in feature with a foreground service; CONTEXT.md records what was verified on hardware and what remains.

## Working rules

- Preserve existing changes in `hermes_voice_bridge/speech.py` and `tests/test_bridge.py`: they provide Android-compatible PCM WAV speech.
- Reuse the Android app, authenticated Mac bridge, and existing Hermes conversations. Preserve typed questions and phone-button activation.
- Verify the actual microphone source, DAT lifecycle, and behavior with the phone locked before choosing a wake detector or claiming hands-free support.
- Use the plan's observable acceptance checks. Keep camera integration, motion gestures, voice cloning, and model-provider changes outside the first wake-up feature.
- Treat `Hey Hermes` as the proposed default, subject to the user's preference. It is not an implemented command.
- The Mac bridge runs from a separate installed runtime. Source edits do not automatically update that service.
- Keep credentials and voice recordings out of tracked files. Never print API keys, authenticated URLs, or unredacted private conversations while debugging.
- Prioritize current user instructions and live evidence over historical setup notes. `docs/FIRST_MILESTONE.md` describes the upstream author's setup; `docs/FUTURE_MOTION_TRIGGERS.md` mentions CAPTOUCH, while this checkout currently subscribes to ACTION_BUTTON.
- Update the linked context and plan when an implementation decision or verification result changes. Do not mark a hardware check passed based only on unit tests.

## Baseline commands

Run from the repository root:

```sh
uv sync --locked
.venv/bin/python -m unittest discover -s tests -v
./android/run_gradle.sh test assembleDebug
git diff --check
```

For hardware work, first run `adb devices -l` using the Android SDK's platform-tools and confirm the intended authorized phone.
