# Photo capture and analysis plan

Status: implemented end to end on 13 October 2026 (bridge deployed, APK installed). Hardware acceptance checks are pending. Prepared 13 October 2026.

## Goal

The wearer asks Hermes about what they are looking at. The glasses take one photo, the phone sends it with the question to the Mac bridge, Hermes answers using the local vision model, and the reply is spoken through the glasses.

```text
"Hey Hermes, what am I looking at?"  (or phone "Look" button)
  → glasses capture one photo → phone JPEG → bridge /v1/turn (text + image)
  → Hermes session chat (multimodal) → local MLX vision model
  → short spoken reply → back to wake standby
```

## What was verified while planning

- **Local model:** `/v1/models` on `127.0.0.1:11234` reports the loaded `ddalcu/Qwen3.8-Flash-Next-MLX-Serve-iQ-MLX-4.7bpw` with `vision` capability and `text, image, video` input. Whether Hermes routes images to it unchanged is not yet tested.
- **Hermes API:** in `~/.hermes/hermes-agent/gateway/platforms/api_server.py`, `POST /api/sessions/{id}/chat` accepts `message` as either a string or a list of `text` and `image_url` / `input_image` parts. Images must be `data:image/...` or http(s) URLs. The request cap is 10 MB. No Hermes change is needed.
- **DAT SDK:** a separate artifact, `com.meta.wearable:mwdat-camera:1.0.0`, provides the camera. 1.0.1 fixes only stream bugs. `Permission.CAMERA` already exists in mwdat-core 1.0.0. These findings come from the SDK repo, its docs and the Maven POM. The camera has not been tried on the user's glasses.
  - Stable route: `session.addCamera(StreamConfiguration(...))` → `camera.stream.start()` → wait for `STREAMING` → `camera.stream.capturePhoto()` returns `PhotoData.Bitmap` or `PhotoData.HEIC`. Stream sizes go up to 720×1280. Over Bluetooth Classic the SDK may lower the resolution.
  - Experimental route: `camera.photo.capturePhoto(PhotoResolution.FULL, PhotoQuality.HIGH)` with no stream. The result arrives on `photo.photoStream`. After each `stop()` the camera must be removed and added again. Experimental APIs cannot ship to production channels.
  - Unknown: whether the camera works on Wayfarer Gen 2 under the developer-mode `0` credentials; whether Camera and Speech can run at the same time; capture latency; the privacy LED's behaviour.

## Phase 0: hardware feasibility (gate)

Do this before writing feature code. Use a throwaway debug button in `MainActivity`.

1. Add `mwdat-camera:1.0.0`. Request `DatPermission.CAMERA` through the existing `Wearables.RequestPermissionContract` flow.
2. After the session reaches STARTED, call `addCamera`. Start a short MEDIUM stream at a low frame rate and capture one photo. Log the size, format and timings: stream start, capture, total. Do not log image bytes.
3. Repeat with DAT Speech active in standby. Record whether Speech errors, stops or keeps running, and whether capture fails.
4. Try the experimental `camera.photo` route once and compare its resolution and latency.
5. Save one test image to the app's private storage and pull it with `adb` so its quality and orientation can be checked by eye. Delete it afterwards.

Deliverable: an evidence entry in `docs/voice-wakeup/CONTEXT.md` (or a new `docs/photo-capture/CONTEXT.md`), and a choice of route. **Default: the stable stream route**, unless the photo route is clearly better and works on Gen 2.

### Phase 0 results so far (13 October 2026, Fold7 + Wayfarer Gen 2, logcat)

The user chose the experimental standalone `camera.photo` route, because the app is never shipped to production. It is implemented in `camera/GlassesPhotoCapture.kt`, behind a "Take glasses photo (test)" button and a size picker.

- **The experimental photo route works on Gen 2 with developer-mode `0` credentials.** The Meta AI camera permission was granted. Setup (addCamera until Photo STARTED) took 76 ms. The glasses took the picture about 1 s after `sendCaptureCommand`.
- FULL/HIGH produces a HEIC of 3024×4032, about 1.8 MB, correctly oriented, with EXIF make `Meta`. Visual quality is good enough for analysis.
- **Transfer is the bottleneck.** The photo moves over Bluetooth (DAT "Airship") at about 24 KB/s, so a FULL/HIGH photo took about 85 s. A second transfer running in parallel was slower, about 10 KB/s.
- **The SDK re-delivers stale photos.** The first attempt hit the old 30 s timeout. The SDK then gave the next request that earlier, unfinished photo; its EXIF time was 11:22:14, a minute before. The new photo kept transferring in the background after the app stopped listening. The SDK then dropped it, but left the file in `files/recv/dat`. Fixes: photos timestamped before the request are discarded, the timeout is now 120 s, and the app clears `files/recv/dat` at launch.
- Wake standby (DAT Speech STARTED) was running during the first capture, and the capture still triggered. Whether wake detection keeps working during a long transfer is not yet known.
- Size comparison (11:29–11:32). Time is from the capture command until the photo arrived. No stale photos were skipped:

  | Preset | Pixels | HEIC size | Time |
  |---|---|---|---|
  | SMALL/LOW | 480×640 | 44 KB | 1.7 s |
  | SMALL/MEDIUM | 480×640 | 69 KB | 2.9 s |
  | MEDIUM/MEDIUM (×2) | 720×1280 | 142–143 KB | 5.4 s |
  | LARGE/MEDIUM | 1080×1920 | 251 KB | 8.7 s |
  | FULL/HIGH | 3024×4032 | 1.76 MB | 60.6 s |

  Throughput holds at about 25–30 KB/s, so time scales with file size.
- `PhotoCaptureData.timestamp` is epoch milliseconds, but it is the **arrival** time on the phone: +1.8 s after the tap for SMALL/LOW and +60.7 s for FULL/HIGH. It cannot detect a stale photo. The filter now uses the EXIF `DateTimeOriginal` shutter time instead. That field is present, and it is in the phone's local time with no offset (11:31:00 for a command sent at 11:30:59.7). Photos taken more than 3 s before the request are discarded. Not yet observed catching a real stale photo.
- Format: the glasses always encode HEIC. The API only offers resolution and quality, not a format choice. HEIC is already more compact than JPEG or WebP at equal quality, so transcoding cannot speed up the Bluetooth leg. Transcode on the phone instead, to JPEG for the bridge and model, where the phone → Mac link over Tailscale is fast.
- The capture notification listed one more asset on every request, and the SDK reported "Clearing N orphaned pending photo(s)" with N growing from 2 to 7. This suggests the glasses keep earlier captures in storage. Not yet checked: whether these photos persist on the glasses or sync to the Meta AI gallery. This is a privacy question.

### Decisions (13 October 2026)

- Default photo: MEDIUM resolution, MEDIUM quality (720×1280, about 140 KB HEIC, about 5 s), chosen by the user.
- The photo is converted on the phone to JPEG, longest edge at most 1280 px, quality 85, upright and without EXIF. JPEG rather than WebP, for model compatibility. The conversion cannot shorten the Bluetooth leg.
- The user reports that the test photos do **not** appear in the Meta AI app's gallery.
- No production release is planned, so the experimental `camera.photo` API is acceptable.
- If the photo fails, the turn ends with a failure tone and a status message. Hermes is not asked about the question without the photo.
- Photo turns use the same Hermes conversation as voice turns. This was not explicitly decided.

### What was built

- 13 October: the test-photo button sat directly under the wake-up toggle. A tap at 11:23:01 turned wake-up off and saved that, so later launches stayed idle: auto-reconnect only runs while wake-up is on. The Look button now sits under "Ask Hermes".

- Bridge: `/v1/turn` accepts an optional `image` field, a base64 JPEG of at most `MAX_IMAGE_BYTES` (default 3 MB) after decoding, checked by its magic bytes. With no text, the question defaults to "Briefly describe what I'm looking at." `HermesClient.turn(image_jpeg=...)` sends a `text` part plus an `image_url` part. Images are never written to disk or logs. 20 Python tests pass. Only the three changed package files were copied into the runtime (`ditto` plus `launchctl kickstart`); `.env` and the LaunchAgent are unchanged.
- Live checks: a stock macOS icon, sent directly to Hermes and then through the bridge over Tailscale, was described correctly in 12.7–12.8 s with spoken audio. A PNG was rejected with 400.
- Android:
  - `interaction/VisualIntent` (unit-tested) flags spoken questions such as "what am I looking at", "what's this" (only as the whole question), "read this", "look at…", "take a photo", "what does this say" and "describe…".
  - `TapToTalkEffect.Submit` and `ManualSubmit` carry `withImage`. A **Look** button forces a photo, using the typed text or "What am I looking at?".
  - `camera/PhotoEncoding` converts HEIC to JPEG with ImageDecoder.
  - `MainActivity.takePhotoForQuestion` checks the session and camera permission, captures, plays a shutter tone, shows a preview, and logs sizes and timings under the `HermesPhoto` tag.
  - The photo stays in memory; leftover `files/recv/dat` and the old `files/photo-test` are deleted at launch.

## Phase 1: bridge (Mac, Python)

Extend `/v1/turn` rather than adding an endpoint, so the session ID, language, TTS and auth paths stay the same.

- New optional field `image`: base64 JPEG (no data-URL prefix). Raise `_read_json`'s 1 MB cap to a separate limit for `/v1/turn`, set with `MAX_IMAGE_BYTES` (default 3 MB decoded). Reject anything that is not JPEG (check the magic bytes `FF D8 FF`) or that is over the limit, with 400.
- `HermesClient.turn(..., image_jpeg: bytes | None)`: when there is an image, send `message` as `[{"type":"text","text":...}, {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]`. Add to the instructions that the image is the wearer's current view from glasses, and keep the existing two-sentence, 240-character spoken limit.
- An image without text gets a default question: "Briefly describe what I'm looking at."
- Never write images to disk or logs on the bridge.
- Tests in `tests/test_bridge.py`: payload shape sent to a fake Hermes; rejection of non-JPEG, oversize and invalid base64 input; text-only turns unchanged.
- Reinstall the runtime with the reviewed `scripts/install_service.sh` workflow. Source edits don't update the running service.

## Phase 2: Android

- `GlassesCamera` wrapper (new, `ai.hermes.glasses.camera`). It holds the `Camera` for the session and exposes `suspend fun captureJpeg(): ByteArray`: start the stream, wait for STREAMING with a timeout, capture, stop the stream. It converts Bitmap or HEIC to JPEG, scales the long edge to ≤1280 px and uses quality ~80 (target under 400 KB). It uses a mutex for one capture at a time. On disconnect it closes and calls `removeCamera()`.
- `HermesBridgeClient.turn(..., imageJpeg: ByteArray? = null)` adds the base64 `image` field. Use a longer read timeout only if Phase 0 or Phase 3 timings show it is needed.
- Triggers:
  - A **"Look" button** on the phone uses the typed question, or the default question if the field is empty.
  - **Voice:** a pure `VisualIntentMatcher` in `interaction/`, unit-tested like `WakePhraseMatcher`. It flags questions such as "what am I looking at", "what is this", "read this", "look at…", "take a photo/picture" and "describe…". A flagged question captures a photo before it is submitted; others stay text-only. Prefer an explicit keyword ("look") if the matcher gives false positives.
  - Action button: unchanged for now.
- State machine: add `Submit(text, withImage)` (or a `SubmitWithImage` effect). Capture happens in the BUSY state, with Speech disarmed, so the reply and capture cannot re-trigger wake. A capture failure is spoken or shown as an error, and the turn falls back to asking without the image only if the user chose that. Default: report the failure.
- A distinct capture cue (shutter tone) plays when the photo is taken.
- Privacy: images are kept in memory only. A "Keep last photo" toggle is off by default. The UI shows a thumbnail of the photo that was sent.

## Phase 3: acceptance checks (hardware)

- "Hey Hermes, what am I looking at?" with the phone in a pocket returns a relevant spoken description. Record the end-to-end time and how it splits between capture, upload and Hermes.
- Reading short printed text (sign, label) works at arm's length.
- The phone Look button works with the screen on. The voice route works with the phone locked (foreground service is already in place).
- Five consecutive visual turns with no camera leak: the stream stops each time, there are no `CaptureInProgress` errors, and wake standby resumes after each.
- Text-only wake turns are unaffected, and the conversation ID is shared across text and image turns.
- Disconnecting or folding the glasses mid-capture recovers cleanly.

## Out of scope for this feature

Continuous video or live streaming to Hermes, multi-photo turns, saving to the phone gallery, the action-button photo trigger, and changes to models or providers.

## Decisions for the user

1. Trigger: is a voice intent ("what am I looking at…") plus a phone Look button enough, or do you also want an explicit word like "Hey Hermes, look"?
2. If the camera fails, should Hermes answer from text alone, or say the photo failed? (Default: say it failed.)
3. Hermes stores the image in its session history, so it can answer follow-ups like "what colour was it?" Is that acceptable, or should image turns use a separate session?
