# Hermes Glasses handoff

Read [AGENTS.md](AGENTS.md), then:

- [Voice wake-up context](docs/voice-wakeup/CONTEXT.md): hardware results and troubleshooting history.
- [Voice wake-up plan](docs/voice-wakeup/PLAN.md)
- [Photo capture plan](docs/photo-capture/PLAN.md): decisions, Phase 0 measurements and what was built.

These files are the shared handoff for continuing work in a new chat.

## Current state (13 October 2026)

- **Voice wake-up** ("Hey Hermes") is implemented, experimental and opt-in, and was tested on the Fold7 with Wayfarer Gen 2. While wake-up is on, the app reconnects automatically after the glasses end the session. That was built and installed, but has not been verified on the glasses.
- **Photo questions** work end to end in code:
  - Triggers are spoken visual questions ("what am I looking at", "read this"…) or the phone **Look** button.
  - The glasses take an experimental standalone DAT photo at MEDIUM/MEDIUM: 720×1280 HEIC, about 140 KB, about 5 s over Bluetooth.
  - The phone converts it to JPEG and sends it to the bridge as `/v1/turn` `image`. Hermes passes it to the local vision model.
  - The bridge is deployed, and a live image turn answered in about 13 s. A spoken photo question on the glasses is not yet verified.
- Nothing from this work is committed yet (branch `voice-wakeup`).

## Hard-won facts

- The Wayfarer Gen 2 touchpad cannot trigger the app. With DAT 1.0, no Inputs events arrive. A single tap PAUSES the DAT session, a double tap ENDS it, and a long press opens Meta AI. The capture button takes the glasses' own photo. Use voice or phone triggers.
- Glasses-to-phone photo transfer runs at about 25–30 KB/s, so time scales with file size. The glasses always produce HEIC. `PhotoCaptureData.timestamp` is the arrival time; the shutter time is in EXIF.
- DAT can re-deliver an earlier, abandoned photo. The app filters these by EXIF time and deletes `files/recv/dat` at launch.
- The installed bridge runs from `~/.local/share/hermes-glasses-bridge`. For a Python-only change, `ditto` the `hermes_voice_bridge` package there, then `launchctl kickstart -k gui/$(id -u)/ai.hermes.glasses-bridge`. The full installer also rewrites the runtime config.
- The saved wake-up toggle decides whether the app auto-connects at launch. If the app sits idle, check that wake-up is on.
