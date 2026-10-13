# Current context and handoff

Last updated: 13 October 2026.

## User intent and current stage

The owner wants a hands-free assistant on Ray-Ban Wayfarer Gen 2 glasses, using the existing Hermes Agent and local MLX model on the Mac. The immediate next feature is voice wake-up. Camera support is desired later.

The plan has been reviewed (notes in PLAN.md). On 13 October, the route-independent wake logic and an experimental, opt-in DAT-transcript standby were implemented and installed on the Fold7 with `adb install -r`. No service, model download or new dependency was added. Hardware results are recorded below only after they are observed. The proposed default phrase is `Hey Hermes`; the user has not explicitly selected it yet.

## Repository

- Origin: https://github.com/Allendior/rayban-meta-glasses-hermes
- Inspected HEAD: `bffe22b` (`Harden public release for security and submission readiness`).
- Existing uncommitted implementation changes are `hermes_voice_bridge/speech.py` and `tests/test_bridge.py`: macOS TTS now produces 16-bit mono 44.1 kHz PCM WAV instead of AIFF, with a real macOS regression test. Preserve these changes.
- Repository entry points: [AGENTS.md](../../AGENTS.md) and [CLAUDE.md](../../CLAUDE.md).
- Feature plan: [PLAN.md](PLAN.md).

## Hardware and local software

- Mac: Apple Silicon, macOS; `uv` available. Checkout environment uses Python 3.12.14.
- Android: Samsung Galaxy Z Fold7, model `SM-F966B`, Android 16. ADB was used successfully; recheck connection/authorization in the next chat.
- Glasses: Ray-Ban Wayfarer Gen 2; the user reports current firmware.
- Meta AI phone app handles pairing, registration, DAT runtime and glasses permission grants. The user reports Meta's own assistant is unavailable in their region.
- Build: JDK 17 at `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`; Android SDK at `~/Library/Android/sdk`, platform/build tools 36. `android/run_gradle.sh` sets the required paths.
- Android application ID/namespace: `ai.hermes.glasses`; callback scheme: `hermesglasses`.
- DAT dependencies: `mwdat-core`, `mwdat-speech`, `mwdat-inputs`, all 1.0.0. No camera module is integrated in this app yet.
- This development build uses Meta's documented application ID/client token `0` pair. Do not substitute production credentials without a specific need and matched configuration.

## Live services and configuration

Verified again on 13 October 2026:

| Component | Address/state |
|---|---|
| Phone-facing bridge | `http://<mac-tailscale-ip>:8788` over Tailscale; health reports OK and Hermes reachable |
| Hermes API | `http://127.0.0.1:8642`, bearer-authenticated and loopback-only |
| Local model endpoint | `http://127.0.0.1:11234/v1` |
| Loaded model reported by server | `ddalcu/Qwen3.8-Flash-Next-MLX-Serve-iQ-MLX-4.7bpw` |
| Hermes configured default | provider label `omlx`, model `Qwen3.8-Flash-Next-oQ4e-mtp` |

The configured provider/model names and the live server's loaded model differ. Existing live Hermes turns succeeded. Verify the endpoint and actual route before relying on names; do not change the user's model defaults as part of wake-up work.

The bridge uses macOS system voices, not a cloned voice. Replies can use English, Hindi or French. API session IDs preserve conversation continuity.

Installed runtime: `~/.local/share/hermes-glasses-bridge`.
LaunchAgent: `~/Library/LaunchAgents/ai.hermes.glasses-bridge.plist` (label `ai.hermes.glasses-bridge`).
Logs: runtime `work/logs/bridge.log` and `bridge.error.log`.

The source checkout's `.env` is ignored and mode 0600; its absolute `HF_HOME` path was updated after relocation. Installed service configuration is in the runtime's own `.env`. Keys were preserved and are intentionally not copied into this handoff.

After relevant bridge edits, use the reviewed install workflow to synchronize the deployed runtime. `scripts/install_service.sh` expects `API_SERVER_KEY` or `HERMES_API_KEY` in its environment; source it securely from the existing configuration without printing it. Preserve the runtime bridge key and tailnet-only binding. Source changes alone do not update the running service.

## Wake-up build status (13 October 2026)

- Android unit tests: 51 pass (24 new wake/matcher tests); `test assembleDebug` succeeds; bridge tests are 17/17.
- Debug APK was installed on the Fold7 (ADB authorized) and launched. The phone was on its lock screen at the time.
- User-observed on 13 October with the Fold7 and Wayfarer Gen 2: “Hey Hermes” wake-up works and Hermes returns spoken answers. The SDK does not write speech lifecycle events to logcat, so this is the wearer's report, not log evidence. Microphone source, locked-phone use, the false-activation sample and repeated-cycle counts are still unrecorded.
- Added after that: whole-utterance voice commands. “Cancel”, “stop”, “never mind” and “forget it” drop the current question. “Stop listening” and “go to sleep”, alone after the wake phrase or combined with it, turn wake-up off and save that setting. When wake-up is on and Meta AI is registered, the app now reconnects the glasses session at launch, so standby resumes without tapping. It is built, tested and installed; not yet tried on the glasses.

## What was verified

- 17 Python bridge tests pass, including real PCM WAV generation.
- 20 Android unit tests and `test assembleDebug` passed; rebuilt successfully after moving the repo.
- Hermes Glasses APK was installed on the Fold7 on 12 October.
- A real authenticated request originating on the phone traversed Tailscale → bridge → Hermes → local model, returned text, and downloaded a valid WAV.
- Live English/French turns preserved the same Hermes conversation ID.
- Prior checks confirmed unauthenticated requests get 401 and ordinary LAN access to the bridge/Hermes API is refused.
- A real glasses-spoken Hermes question after DAT installation has **not** been confirmed. Establish this baseline first.

## Troubleshooting history to retain

1. A bridge URL without `:8788` failed. The full phone URL is `http://<mac-tailscale-ip>:8788`.
2. Hermes Glasses sessions stopped with `START_ERROR_DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED`. The SDK's persisted developer-mode flag was true. The user subsequently found DAT SDK/runtime **not installed**, installed it, and resolved that prerequisite. General firmware updates alone did not supply the runtime.
3. There is a diagnosed but unfixed Hermes Glasses UI issue: `handleGlassesError` searches description text for `UPDATE_REQUIRED`/`update required`, but the SDK description says the glasses need an update. This can hide the update button. The session STOPPED callback can also overwrite the useful error. A fix was discussed, then the user installed DAT; no Android fix was made. Use typed `DeviceSessionError` if addressing this later.
4. Current input subscription is ACTION_BUTTON only. Do not assume it maps to the Gen 2 capture button or temple touchpad; hardware behavior was not verified.
5. 13 October: phone "Test Mac bridge" failed with a connect timeout to `<mac-tailscale-ip>:8788`. The bridge was healthy and listening. The cause was Tailscale: the phone's VPN was off, and the Mac's Tailscale could not reach the coordination server while OpenVPN was connected (it reported IPv6 "no route to host"). Disconnecting OpenVPN restored Tailscale, and the phone then reached the bridge over TCP. The phone needs no exit node or LAN access. Check OpenVPN first if this happens again.
6. 13 October: after a relaunch, wake-up stayed idle and Connect did nothing. The status read “Wearables has been already initialized”. `Wearables` is process-wide, so an Activity recreated in the same process (for example after folding or unfolding the Fold7) got `WearablesError.ALREADY_INITIALIZED` and treated it as fatal. Fix: that typed error now counts as ready, and `MainActivity` declares `configChanges` for size, layout and density so a fold no longer recreates it or drops the DeviceSession. Built and installed; verification on the device is pending.
7. 13 October, from logcat on the device: a DAT Speech session streams many utterances, and the glasses end it **about 60 s after start**. The log shows “Speech session completed” exactly 60 s after start, even with activity in between. SDK 1.0.0 clears its internal listening flag on completion but leaves the public `state` at STARTED, so the app could not see the session die. Standby went deaf after 60 s, and every other “Start glasses listening” tap only stopped the dead session. Fix in `MainActivity`: track session age; a 5 s watchdog restarts a standby session older than 50 s; a capture restarts any session older than 40 s. `stop()` then `start()` takes about 100 ms. Verified in logs: restarts at about 52 s intervals; a wake detected on a 41 s-old session got a fresh session; wake-only and combined “Hey Hermes, …” turns both answered.
8. **Locked phone, first evidence (13 October):** the screen was locked from 10:32:40 to 10:34:31 with Hermes Glasses as the last app on screen. The process stayed `TOP_SLEEPING`, not frozen. A standby restart ran at 10:33:32, and a full wake → question → reply turn ran at 10:33:58–10:34:20 while locked. This is about 2 minutes, not the 5-minute acceptance check, and it was not tested after switching to another app. No foreground service exists yet; the battery setting was not changed.
9. Foreground service added on 13 October: `WakeListeningService`, type `connectedDevice`. It runs while wake-up is on, shows a low-importance notification with a Stop action, and holds a partial wake lock (8 h cap) so the 50 s session restarts keep running while the phone sleeps. The voice pipeline still lives in `MainActivity`; the service only keeps the process foreground. `stopWithTask` means swiping the app away from Recents stops wake-up. Verified on the device: `isForeground=true`, `types=0x10`, wake lock held. After a relaunch with the phone locked, no DAT session started. Auto-reconnect while locked is unproven, and the 5-minute locked and app-switch checks are pending.
10. **Locked-phone acceptance, 13 October (logcat plus screen events):** the phone was locked from 10:40:38 and still locked at 10:55. The foreground service kept the process running, and session restarts ran every 50 s throughout (more than 14 minutes). A combined “Hey Hermes, …” turn at 10:51:28, about 11 minutes after locking, was answered. Two wake-only attempts (10:52:58 and 10:54:47) hit the fixed 10 s question timeout. Every successful wake-only turn that day took 6–8 s from wake cue to final transcript. The timeout is now 8 s of silence, extended by partials; this is built and installed but not yet verified on the glasses. Still pending: the app-switch check, the notification Stop action, the five-minute false-activation sample, and whether launch auto-reconnects without tapping Connect (after the unlock at 10:39:30 the session started at 10:39:43, and it is unknown whether Connect was tapped).
11. **Test round, 13 October 10:57–11:01 (logcat):** the app was relaunched while locked. No DAT session started until the unlock at 10:57:09; the session started at 10:57:14, apparently without a tap, which suggests auto-reconnect works once the phone is unlocked. A wake-only turn with a long question took 18 s from wake cue to final and was captured: the silence-based timeout worked. With **Chrome in the foreground** (from 10:59:38), a wake → question → reply turn at 11:00:00–11:00:55 succeeded, and the screen turned off mid-reply. That passes the app-switch check. Battery is still optimized (not Unrestricted), so these results hold without that setting. The notification Stop action and the false-activation sample remain untested.
12. The wake-up notification was invisible: POST_NOTIFICATIONS was only requested from the toggle, so wake-up restored from the saved setting never asked. The app now asks whenever the service starts. A foreground notification posted while the permission is denied is not shown after a later grant, so the grant callback restarts the service to post it again. Verified on the device: the notification is posted (low importance, so it sits in Samsung's “Silent” section). The Stop action is not yet tested.
13. Notification Stop verified on 13 October from the system log: `notification_action_clicked` at 11:08:19, then the service stopped and the notification was cancelled. The wearer re-enabled wake-up from the app at 11:08:52.
15. **Glasses input test, 13 October 11:55–11:57 (logcat).** An "Input test" toggle subscribes to CAPTOUCH, CAPTURE_BUTTON and ACTION_BUTTON and only logs events (tag `HermesInput`). The first run was invalid. The DAT session started at 11:55:16, and the Meta AI app's WearableStateTracker showed it as `UNKNOWN_APP`. At 11:55:32 the active-apps list became empty, a touchpad media-next followed, and DAT reported `STOP_ERROR_SESSION_UNAVAILABLE` at 11:55:36. So no session existed when the test was on, and no DAT input events arrived. Cause not proven: possibly a touchpad swipe or back gesture closing the app on the glasses while only ACTION_BUTTON was subscribed. Glasses-native handling seen with no DAT session: a touchpad tap sent MEDIA_PLAY/PAUSE and a swipe sent MEDIA_NEXT to the phone (Spotify). A capture-button short press ran the glasses' own `CAPTURE`. A hold ran `CAPTURE_VIDEO` for about 4 s, and a double press ran `CAPTURE`. These native captures probably go to the Meta AI gallery. A touchpad long press left no trace. The test needs repeating with the session confirmed STARTED. Second run, 11:59–12:00: the session started at 11:59:25 and the test turned on at 11:59:42 with the session up. No DAT input events and no native tap actions were logged until 11:59:58. Then the active-apps list emptied, MEDIA_NEXT fired, and DAT reported `STOP_ERROR_SESSION_UNAVAILABLE` ("Session ended by device"). The same pattern appeared in both runs, so a touchpad gesture closes the app on the glasses. The third run identifies it as most likely a **double tap**, which sends MEDIA_NEXT. Swipes never appeared as media keys. Speech also completed 30 s after start in this run, not 60 s. The app now logs input attach, state and errors and session state and errors under `HermesInput`, because the status line overwrote them. Third run, 12:04–12:05, with `HermesInput` logging:
    - The session STARTED at 12:04:28.
    - Inputs attached and reached ACTIVE, first for ACTION_BUTTON and then, at 12:04:36, for CAPTOUCH + CAPTURE_BUTTON + ACTION_BUTTON. There was no permission error, so the developer-mode `0` credentials allow Inputs.
    - **No InputEvent of any kind arrived.**
    - 12:04:45, a single tap: the DAT session went **PAUSED** and Speech completed, with no Select event and no native play/pause.
    - 12:04:48, a double tap: the app was removed from the glasses' active apps and MEDIA_NEXT fired.
    - 12:04:52: the session went STARTED again on its own.
    - 12:04:54: the session ended with `SESSION_ENDED_BY_DEVICE`.
    - After that, with no session: a tap toggled music, and the capture button took a native photo (`CAPTURE`). In the second run, a long press opened Meta AI (`ASSISTANT`, 11:58:36).
    - Conclusion: on Wayfarer Gen 2 with DAT 1.0, touchpad gestures do not reach a DAT app as events. A tap pauses the session, and a double tap ends it, which also kills wake standby until the user reconnects. Whether the capture button reaches the app while a session is active is still untested.
16. Following those results, 13 October: the input-test toggle and its `HermesInput` logging were removed. Inputs is back to ACTION_BUTTON only. **Auto-reconnect was added:** while wake-up is on, a STOPPED session, or a failed `createSession`, schedules a reconnect after 3, 5, 10, 30 and then every 60 s. The counter resets when a session reaches STARTED. Before reconnecting, the app checks that wake-up is still on, no session exists, and Meta AI registration is REGISTERED. A new session cancels the previous session's collectors. Built, tested and installed; not yet verified on the glasses.
14. Historical docs are upstream history: the Mac mini, cloned voice and CAPTOUCH descriptions in existing docs are not verified facts about the owner's setup.

## Veyra reference experience

Veyra (`com.geno.veyra`, a third-party Gemini-based assistant for these glasses) was inspected as an implementation reference only. Its APK has a local Vosk wake engine with an optional, configurable wake phrase, an offline model of roughly 40 MB, and a separate wake-word service. Its active session listens continuously. This is not source code to copy, and it does not show that its microphone route fits this app.

## References already consulted

- https://github.com/facebook/meta-wearables-dat-android/blob/main/AGENTS.md — official SDK integration and lifecycle guidance.
- https://github.com/facebook/meta-wearables-dat-android/blob/main/plugins/mwdat-android/skills/voice-invocations/SKILL.md — experimental voice-launched apps; not implemented here.
- https://github.com/facebook/meta-wearables-dat-android/issues/192 — Gen 2 DAT runtime/session-start reports. The error can also indicate failure to launch the runtime, not only an outdated version.
- https://wearables.developer.meta.com/docs/develop/dat/build-integration-android/ — Developer Mode uses omitted/0 credentials.
- https://www.reddit.com/r/RaybanMeta/comments/1wqsq3j/veyra_v010_gemini_voice_assistant_for_meta/ — developer's Veyra test-build announcement.

Verify current official API/library details when implementing; links and observed behavior are evidence from this session, not a permanent compatibility guarantee.

## Suggested prompt for the new chat

> Work in this repository. Read AGENTS.md and docs/voice-wakeup/CONTEXT.md and PLAN.md. Continue the voice wake-up work, beginning with the physical-device microphone and locked-phone feasibility checks. Preserve the existing WAV fix, local Hermes/MLX setup, private credentials and working typed-question path. Keep camera integration for a later phase and update the handoff with verified decisions/results.
