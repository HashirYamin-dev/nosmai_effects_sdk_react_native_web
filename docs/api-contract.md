# Core API contract

This document defines the stable JavaScript contract shared by the Android and
iOS native cores. Android source/build verification plus licensed OES/YUV
preview, repeated lifecycle stress, debug runtime, and an authorized local-effect
sequence have passed on one Pixel 7. The iOS native core and generic `iphoneOS`
Debug/Release builds pass without an attached device. A signed physical Release
build/install/launch gate and user-confirmed runtime smoke pass on one iPhone;
signed Debug and wider-device qualification remain open.

## Current platform matrix

| Surface                                                           | Android                                                                                                   | iOS                                                                                                                      |
| ----------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| TurboModule and Fabric Codegen                                    | Implemented                                                                                               | Implemented                                                                                                              |
| SDK initialization and license events                             | Implemented through a 3.0.4 compatibility adapter                                                         | Implemented; manually passed in one signed physical Release smoke                                                        |
| `NosmaiCameraView` processed preview                              | Camera2 OES with YUV fallback; both paths verified on one Pixel 7                                         | Fabric-hosted AVCapture/processed preview; manually passed in one signed physical Release smoke                          |
| Configure/start/stop/pause/resume/switch                          | Implemented; repeated Pixel 7 stress passed, broader matrix open                                          | Implemented; one signed Release smoke passed, signed Debug/wider stress open                                             |
| Local protected-package apply/state/scoped clear                  | Implemented; authorized Pixel 7 effect gate passed                                                        | Implemented; physical Release apply/state/remove/full-clear passed on one iPhone, scoped-clear runtime open              |
| Installed production package catalog (`getLocalFilters`)          | Implemented; source/build gate passed, device result qualification open                                   | Implemented; generic device build passed, physical result qualification open                                             |
| Rendered photo capture, video recording/progress, gallery export  | Implemented; source, Codegen, Debug/Release build, and package gates pass; physical runtime gate deferred | Implemented; source, Codegen, generic device Debug/Release build, and package gates pass; physical runtime gate deferred |
| Cloud catalog/download/remove/progress/pagination                 | Implemented; physical network/runtime matrix pending                                                      | Implemented; physical network/runtime matrix pending                                                                     |
| Interactive game input/output/lifecycle                           | Implemented; physical runtime matrix pending                                                              | Implemented; physical runtime matrix pending                                                                              |
| Beauty, makeup, reshape, eye color, color/HSB, manual backgrounds | Implemented; physical visual matrix pending                                                               | Implemented; physical visual matrix pending                                                                              |
| Flash/torch and authored `.nosmai` parameters                     | Implemented; physical capture/parameter matrix pending                                                    | Implemented; physical capture/parameter matrix pending                                                                   |
| Bounded processed raw-frame stream                                | Implemented; physical format/performance matrix pending                                                   | Implemented; physical format/performance matrix pending                                                                  |

The native Nosmai SDK is process-global. The bridge permits one active preview
owner at a time, even across React context reloads; a newer mounted view
supersedes stale ownership.

## Lifecycle and camera

| API                                             | Result                                 | Contract                                                                                                                                                                                                                                                                                                                                                                        |
| ----------------------------------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `initialize(licenseKey)`                        | `Promise<boolean>`                     | Resolves `true` when native initialization is accepted. It is not a license-validity verdict; final status arrives by event. It never intentionally resolves `false`.                                                                                                                                                                                                           |
| `configureCamera({ position, sessionPreset? })` | `Promise<void>`                        | Configures `front` or `back`; Android validates the optional preset.                                                                                                                                                                                                                                                                                                            |
| `startProcessing()`                             | `Promise<void>`                        | Requires an initialized SDK, granted host camera permission, and a mounted preview. Resolution means the native start request was accepted, not that a processed frame rendered.                                                                                                                                                                                                |
| `stopProcessing()`                              | `Promise<void>`                        | Stops camera input before releasing the processing session. It does not imply final SDK cleanup.                                                                                                                                                                                                                                                                                |
| `pauseCamera()` / `resumeCamera()`              | `Promise<boolean>`                     | Resolve `true` when the requested state is reached (including an idempotent repeat). Calling either outside an active processing session rejects instead of returning `false`.                                                                                                                                                                                                  |
| `switchCamera()`                                | `Promise<boolean>`                     | Resolves `true` after an accepted switch. Resolves `false` only when a rapid/concurrent request is safely skipped. Invalid state and operational failures reject.                                                                                                                                                                                                               |
| `hasFlash()` / `hasTorch()`                     | `Promise<boolean>`                     | Reports capability for the selected camera only.                                                                                                                                                                                                                                                                                                                                |
| `setFlashMode(mode)`                            | `Promise<boolean>`                     | Accepts `off`, `on`, or exposure-driven `auto`; returns `false` when the selected camera cannot honor the mode.                                                                                                                                                                                                                                                                 |
| `setTorchMode(mode)`                            | `Promise<boolean>`                     | Accepts continuous illumination `off` or `on`; returns `false` when unsupported.                                                                                                                                                                                                                                                                                                |
| `getFlashMode()` / `getTorchMode()`             | `Promise<string>`                      | Returns the accepted mode, or `off` when the selected camera lacks that unit. Facing changes and cleanup reset both to `off`.                                                                                                                                                                                                                                                   |
| `startFrameStream(options?)`                    | `Promise<void>`                        | Starts one latest-only processed CPU-frame slot. `maxFramesPerSecond` is an integer from `1..5` and defaults to `2`. Requires an active processed preview.                                                                                                                                                                                                                      |
| `stopFrameStream()`                             | `Promise<void>`                        | Idempotently stops processed-frame delivery, clears the retained slot, and cancels an in-progress read. Camera lifecycle teardown also stops it.                                                                                                                                                                                                                                |
| `getLatestFrame()`                              | `Promise<NosmaiRawFrame \| undefined>` | Atomically consumes the latest slot, returning `undefined` when it is empty. A concurrent read rejects. The result contains base64 bytes plus authoritative format, plane, stride, timestamp, sequence, and drop metadata.                                                                                                                                                      |
| `isFrameStreamActive()`                         | `Promise<boolean>`                     | Reports whether the current logical camera session owns an active processed-frame callback.                                                                                                                                                                                                                                                                                     |
| `cleanup()`                                     | `Promise<void>`                        | Serial final logical-session teardown. Pending camera/session work invalidated by cleanup rejects with `E_OPERATION_CANCELLED`. Android settles an active native effect mutation and cancels queued native entry; iOS drains work already admitted to its native FIFO before the final clear barrier. Teardown failures reject with `E_CLEANUP`. Initialize again before reuse. |

Configure, pause, switch, stop-processing, and explicit cleanup requests reject
with `E_RECORDING_IN_PROGRESS` while a recording is starting, active, or being
finalized. Call and await `stopRecording()` first so the application receives
the final media URI. Native lifecycle interruption uses best-effort forced
finalization because JavaScript may already be suspended.

`NosmaiCameraView` is the only public preview view. Its initial contract is
`cameraPosition`, `mirror`, `onReady`, and `onError`. Mount the view before
calling `startProcessing()`. `onReady` marks the first processed frame rather
than merely an opened camera; it is the rendering-ready boundary.

On Android, effect mutations enter one FIFO. Cleanup closes admission, allows
the mutation already inside the native SDK to settle, and cancels queued work
before native entry. Scoped clear completion requires its callback and an idle
native transition. Full clear then fences the native effect executor, main
queue, and preview GL queue before processing teardown.

On iOS, effect work already admitted to the main-queue FIFO drains in order
before cleanup crosses its final native-state and effects-queue barriers. New
work is not admitted once cleanup begins.

Android camera permission is host-owned. On iOS, `startProcessing()` requests
camera access when status is undetermined; both platforms reject with
`E_CAMERA_PERMISSION` when access is denied. Android currently accepts
`default`, `high`, and 720p aliases, plus 480p aliases, for `sessionPreset`;
other values reject with `E_UNSUPPORTED_CAMERA_PRESET`.

On the native SDK 3.0.4 compatibility line, logical cleanup intentionally retains the native process core
because the upstream full-cleanup path is not restart-safe. A subsequent
session in the same process may reuse the same app-bound platform key. A
different key rejects with `E_LICENSE_KEY_MISMATCH`; the bridge never logs or
returns either key.

## Boolean results

Boolean results report a narrow operation outcome, not general readiness:

| API                             | `true`                                            | `false`                                           | Rejection                                                                                    |
| ------------------------------- | ------------------------------------------------- | ------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| `initialize`                    | Native initialization accepted                    | Not used                                          | Invalid key argument, mismatch, or initialization failure                                    |
| `pauseCamera` / `resumeCamera`  | Requested state reached                           | Not used                                          | Invalid session state, cancellation, or native failure                                       |
| `switchCamera`                  | Switch accepted/completed                         | Debounced or coalesced request was safely skipped | Invalid state, cancellation, or camera failure                                               |
| `applyEffect` / `applyFilter`   | Package applied while native processing is active | Not used                                          | Inactive/transitioning session, invalid path, native refusal, cancellation, or apply failure |
| `removeEffect`                  | Matching active package removed                   | Valid path was not active                         | Invalid path, cancellation, or clear failure                                                 |
| `removeCloudFilter`             | Exact downloaded package removed                  | No downloaded package existed for the ID          | Invalid ID, unsafe resolved path, or filesystem failure                                      |
| `setFlashMode` / `setTorchMode` | Requested light mode accepted                     | Selected camera cannot provide that mode          | Invalid mode, cancellation, or camera failure                                                |
| Authored parameter setters      | Native parameter accepted the typed value         | Not used                                          | Invalid input, missing/type-incompatible parameter, cancellation, or `E_EFFECT_PARAMETER`    |
| `isGameReady` / game input      | Active game is ready / input accepted              | No ready game or input was not accepted            | Invalid input, unavailable session, cancellation, or native failure                          |

`false` is reserved for documented, non-error outcomes. Callers must handle
Promise rejection for every operational failure.

## Protected packages

| API                             | Contract                                                                                                                                                                                                                                              |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `applyEffect(path)`             | Canonical unified application API for a readable local protected `.nosmai` package.                                                                                                                                                                   |
| `applyFilter(path)`             | Deprecated source-compatible alias for `applyEffect`.                                                                                                                                                                                                 |
| `getActiveEffects()`            | Returns the normalized authoritative pipeline snapshot.                                                                                                                                                                                               |
| `getActiveFilterInfo()`         | Returns active color-filter metadata when available.                                                                                                                                                                                                  |
| `getActiveEffectInfo()`         | Returns active AR/beauty-effect/game metadata when available.                                                                                                                                                                                         |
| `getEffectParameters()`         | Returns canonical metadata for the active authored package, or an empty list. Types normalize to float/int/bool/string/vector/enum/unknown; `passId` is optional. Untrusted metadata is bounded to 256 parameters, 128 options, and 64 vector values; text is sanitized and non-finite values are discarded.        |
| `getEffectParameterValue(name)` | Reads a numeric scalar parameter. Missing, string, vector, and incompatible parameters reject with `E_EFFECT_PARAMETER`.                                                                                                                              |
| `setEffectParameter(name, n)`   | Sets a finite numeric scalar. Native refusal rejects with `E_EFFECT_PARAMETER`; success resolves `true`.                                                                                                                                              |
| `setEffectParameterString(n,v)` | Sets a string property. Native refusal rejects with `E_EFFECT_PARAMETER`; success resolves `true`. Names are limited to 128 characters and values to 16,384; control characters are rejected.                                                         |
| `getLocalFilters(packageType?)` | Returns the normalized installed production catalog. Omit the argument for all supported package types, or pass `filter`, `effect`, `background`, `beauty_effect`, or `game`. Results are deterministically sorted and de-duplicated by non-empty local path. |
| `removeEffect(filterOrPath)`    | Removes the matching active package by path; returns `false` if it was not active.                                                                                                                                                                    |
| `clearFilter()`                 | Clears only the color-filter slot.                                                                                                                                                                                                                    |
| `clearAREffect()`               | Clears only the shared AR/beauty-effect/game slot.                                                                                                                                                                                                    |
| `clearAll()`                    | Explicitly requests full pipeline clearing.                                                                                                                                                                                                           |

An installed Android catalog item can carry an asset-relative path such as
`Nosmai_Filters/<name>/<name>.nosmai`; pass that returned path to
`applyEffect()` unchanged. The bridge verifies the production asset layout and
existence. External/downloaded packages still require a readable local file.
`previewUrl` may similarly contain a bundled preview path for a local item.

For authorized iOS development fixtures copied into the application's private
Documents directory, `nosmai-documents:///<relative-path>.nosmai` resolves
securely under that directory. Traversal and symlink escapes are rejected. This
keeps device-test packages outside the source tree and npm artifact.

Android rendered photos consume the processed preview, not a Camera2 JPEG
request. Flash mode is therefore a capture-time preference: the repeating
preview remains flash-free, `on` temporarily illuminates during capture, and
`auto` does so only after a supported auto-flash AE state reports
`FLASH_REQUIRED`. A preparation watchdog and the photo watchdog settle every
capture exactly once, and every terminal path restores the configured torch.
iOS delegates flash/photo behavior to `NosmaiCamera`. Torch remains continuous
on both platforms until explicitly disabled or a camera-facing/session reset.

## Cloud catalog and downloads

| API                                     | Contract                                                                                                                                                                                                                                                              |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `isCloudFilterEnabled()`                | Returns the current license capability. It does not indicate network reachability.                                                                                                                                                                                    |
| `getCloudFilters(query?)`               | Returns `{ filters, pagination }` as one atomic result. Query supports `packageType`, version `2.0.0`, one-based `page` up to `2147483647`, `limit` `1..100`, and `fetchAllPages`. Omitted page defaults to fetch-all; a supplied page defaults to one-page behavior. |
| `downloadCloudFilter(filterOrId)`       | Coalesces simultaneous requests for the same ID and returns `{ filterId, path, alreadyDownloaded }`. The path is a validated, readable absolute `.nosmai` file.                                                                                                       |
| `removeCloudFilter(filterOrId)`         | Deletes only the exact package resolved for that cloud ID and only inside the application's files/cache roots. It never performs substring scans.                                                                                                                     |
| `addDownloadProgressListener(listener)` | Best-effort `{ filterId, progress }` events, where progress is normalized to `0..1`. Completion or rejection is authoritative.                                                                                                                                        |

Catalog fetch and its list/pagination snapshot are serialized natively to avoid
cross-request state races. Items are de-duplicated by downloadable filter ID
while retaining server order. Preview URLs stay as URLs; package previews are
not converted to base64 across JSI. Scoped/type/page requests always disable
the native SDK's cleanup-removed behavior so they cannot delete packages from
categories that were absent from that response.

The SDK has no cloud-download cancellation primitive. A 315-second bridge
watchdog settles a lost JavaScript callback with `E_CLOUD_DOWNLOAD_TIMEOUT`,
but the underlying native transfer may still complete later. Calls made by a
logical session being torn down settle exactly once.

## Interactive games

`game` is a typed local or cloud package. Cloud requests use public
`packageType: 'game'` and the native bridge maps it to backend category
`games`. Applying a game uses `applyEffect()` and gives the game exclusive
visual ownership until another visual mode is applied.

| API | Contract |
| --- | --- |
| `isGameReady()` | Resolves whether an initialized active game can accept input. |
| `sendGameTap(x, y)` | Accepts finite normalized preview coordinates from `0..1`; resolves whether native accepted the tap. |
| `sendGameInput(name, x, y, value?)` | Sends a non-empty named input with normalized coordinates and a finite value; value defaults to `1`. |
| `pauseGame()` / `resumeGame()` | Optional manual overrides; normal camera lifecycle already pauses and resumes games. |
| `restartGame()` | Restarts the active game when present. |
| `addGameEventListener(listener)` | Emits validated `{ event, game, sequence, data }` payloads. Remove the subscription with its owning screen. |

## Beauty, makeup, reshape, and eye color

These operations require an initialized, actively processing pipeline. Applying
or changing beauty, makeup, reshape, eye-color, or color controls also requires
the beauty license capability. Scoped removals/resets and activity queries stay
available if entitlement changes, so stale native state can still be inspected
and cleared. JavaScript validates every value before native entry; native code
validates again.

| API                                                                         | Range and behavior                                                                                                                                                                          |
| --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `setSkinSmoothing`, `setSkinWhitening`, `setTeethWhitening`                 | `0..1`; `0` is neutral. No platform-specific `0..10` conversion.                                                                                                                            |
| `applyMakeup(configuration)`                                                | Applies `lipstick`, `eyeshadow`, `blusher`, `eyelash`, or `eyebrow`; intensity is `0..1`. All except eyelash require custom RGB `0..1`.                                                     |
| `setMakeupIntensity`, `removeMakeup`, `isMakeupActive`, `clearMakeup`       | Operate on the named layer; setting intensity requires that layer to be active. Clear does not affect reshape, eye color, color controls, packages, or background.                          |
| `setReshape(type, value)`                                                   | Uses stable cross-platform types `lip`, `faceSlim`, `eye`, `nose`, `chin`, `brow`, `browThickness`, `jaw`, `mouthWidth`, and `forehead`. Values are signed, with type-specific safe ranges. |
| `clearReshaping()`                                                          | Clears all ten mesh-warp axes only.                                                                                                                                                         |
| `setEyeColor`, `setEyeColorIntensity`, `removeEyeColor`, `isEyeColorActive` | RGB and intensity are `0..1`; omitted apply intensity defaults to `0.5`, and changing intensity requires active eye color. Uses the modern licensed eye-lens path on Android.               |
| `clearBeauty()`                                                             | Clears skin smoothing/whitening/teeth, all makeup, reshape, and eye color. It preserves color/HSB controls, package slots, and manual background.                                           |

Makeup style values are stable: lipstick `classic|matte|natural`, eyeshadow
`smokey|shimmer|natural`, blusher `round|contour|natural`, eyelash
`natural|dramatic|wispy`, and eyebrow `natural|bold|arched`.

Reshape ranges are: lip/brow/brow-thickness/jaw/mouth-width/forehead `-1..1`,
face-slim `-1.2..1.2`, eye `-1.3..1.3`, nose `-0.5..0.5`, and chin
`-0.8..0.8`. Applying built-in beauty can replace an authored package in the
shared AR/beauty slot according to the native SDK policy.

## Color and HSB controls

| API                     | Range / neutral                                                |
| ----------------------- | -------------------------------------------------------------- |
| `setBrightness`         | `-1..1`; neutral `0`                                           |
| `setContrast`           | `0..2`; neutral `1`                                            |
| `setRgbAdjustment`      | each channel `0..2`; neutral `1`                               |
| `setSharpening`         | `0..1`; neutral `0`                                            |
| `setGrayscale`          | boolean                                                        |
| `setHue`                | `0..360`; neutral `0`                                          |
| `setWhiteBalance`       | temperature `2000..8000`, tint `-1..1`; neutral `6500`, `0`    |
| `setHsb`                | hue `-360..360`; saturation and brightness `0..2`, neutral `1` |
| `resetColorAdjustments` | Restores only the controls in this table to neutral values.    |

`setHsb` forwards all three fields on both platforms. On Android it maps the
HSB brightness multiplier to the native additive brightness scale without
discarding saturation or brightness. Reset does not clear beauty, makeup,
reshape, eye color, packages, or background state.

## Manual backgrounds

Applying manual backgrounds requires `isAdvancedFiltersEnabled()` and an active
processing pipeline. `clearBackground()` remains available without that
entitlement so stale manual state can be removed.

| Configuration                    | Contract                                                                                            |
| -------------------------------- | --------------------------------------------------------------------------------------------------- |
| `{ mode: 'blur', blurStrength }` | Public scale `0..1`; Android converts to its native `0..100` configuration scale.                   |
| `{ mode: 'color', color }`       | RGBA components are `0..1`; alpha defaults to `1`.                                                  |
| `{ mode: 'image', uri }`         | A readable absolute local `file://` image URI. Decoding is bounded and performed off the UI thread. |
| `{ mode: 'video', uri }`         | A readable absolute local `file://` video URI used as the looping native background.                |
| `clearBackground()`              | Clears manual segmentation only; it does not remove an authored background package.                 |

The bridge never moves image/video bytes through JSI. Invalid, unreadable,
empty, directory, oversized, or unsupported resources reject with
`E_BACKGROUND_RESOURCE`. The portable cross-platform set is JPEG, PNG, or WebP
for images and MP4, M4V, or MOV for videos; applications should not rely on
additional platform-specific formats.

## Processed raw-frame stream

`startFrameStream({ maxFramesPerSecond? })` enables a latest-only processed CPU
frame slot. The option defaults to `2` and accepts integers from `1..5`.
`addFrameAvailableListener` emits metadata hints without pixel bytes;
`getLatestFrame()` atomically consumes the slot and is the authoritative frame
if a newer callback replaced the event snapshot. An empty slot resolves
`undefined`, while overlapping reads reject with `E_INVALID_STATE`.

Frames rate-limited natively or overwritten before consumption increment the
cumulative `droppedFrames` value. Each frame is capped at 8 MiB before base64
encoding. Android reports tightly packed `i420` or `rgba8888`; iOS reports
stride-preserving `bgra8888` or `nv12`. `colorRange`, plane offsets, byte
lengths, dimensions, and row strides describe the decoded sequence. Malformed
native payloads are rejected with `E_NATIVE_FAILURE` rather than reaching the
application unchecked. `maxFramesPerSecond` limits retained copies, events, and
bridge pulls, not the proprietary SDK's offscreen output cadence while active.

`stopFrameStream()` is idempotent. Pause, stop, camera switch, host suspension,
context retirement, and cleanup also clear the slot; an off-main base64 read
that loses its generation rejects with `E_OPERATION_CANCELLED`. The native SDK
processed-frame callback is multiplexed with iOS first-frame readiness, so
starting or stopping raw sampling cannot steal the preview-ready signal. This
surface makes a bounded CPU/base64 copy and is not a zero-copy GL/Metal texture
or WebRTC transport. See [the detailed frame contract](frame-streaming.md).

## Capture, recording, and gallery

| API                                   | Result and contract                                                                                                                                                                                     |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `capturePhoto()`                      | Returns `{ uri, width, height, fileSizeBytes, mimeType }`. The `file://` JPEG is written to app-owned temporary storage and contains the rendered preview/effects. Only one capture may be pending.     |
| `startRecording()`                    | Starts rendered MP4 recording. It resolves only after native recording is active. Android requires host-granted microphone permission; iOS requires the microphone usage description and authorization. |
| `stopRecording()`                     | Finalizes and returns `{ uri, durationSeconds, fileSizeBytes, mimeType, hasAudio }`. Duration is monotonic and the final result is authoritative.                                                       |
| `isRecording()`                       | Returns `true` for a recording session that is starting, active, or finalizing.                                                                                                                         |
| `getCurrentRecordingDuration()`       | Returns nonnegative elapsed seconds, or `0` when idle.                                                                                                                                                  |
| `saveImageToGallery(imageUri, name?)` | Copies a plugin-produced local JPEG to the system gallery and returns `{ uri, mediaType: 'photo' }`.                                                                                                    |
| `saveVideoToGallery(videoUri, name?)` | Copies a plugin-produced local MP4 to the system gallery and returns `{ uri, mediaType: 'video' }`.                                                                                                     |

Media methods use URIs rather than raw image byte arrays to avoid moving
multi-megabyte payloads through JSI. Capture/recording output remains in the
application cache until the OS evicts it; gallery export is explicit. Gallery
methods accept readable local `file://` URIs only. Android returns a
`content://` URI; iOS returns `ph://<local-identifier>`. Gallery export is
session-independent, so a valid cache URI can still be saved after SDK cleanup.
The optional `name` is a safe filename stem only; the platform appends the
correct `.jpg` or `.mp4` extension.

If native audio finalization fails but a valid playable MP4 exists,
`stopRecording()` preserves the video and resolves with `hasAudio: false`.
`addErrorListener` separately receives `E_RECORDING_AUDIO_MUX` as a non-fatal
warning. A missing or invalid MP4 still rejects the stop operation.

Android recorded-video mirroring currently follows the native SDK camera-facing
policy (front mirrored, back unmirrored), independently of the React Native
preview `mirror` prop. Rendered photo capture follows the displayed preview.
Custom or inverted recorded-video mirroring remains pending upstream native-SDK
qualification.

Android SDK 3.0.x has no public abort/reset primitive for a native recorder that
never delivers its start/stop callback. A bridge watchdog still settles the
JavaScript operation and allows ordered teardown, but the process-scoped camera
session is then quarantined and rejects reuse until the host process restarts.
Fault injection for this upstream limitation remains a release gate.

The Android host requests `RECORD_AUDIO` before recording. Saving app-created
media requires no storage permission on API 29+, while API 24–28 requires
`WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion=28`). iOS hosts declare
`NSMicrophoneUsageDescription` and `NSPhotoLibraryAddUsageDescription`.

## Events and operation failures

- `addActiveEffectsChangedListener`: full normalized pipeline snapshot.
- `addLicenseStatusChangedListener`: `valid`, `invalid`, `expired`,
  `unverified`, or `unknown`.
- `addErrorListener`: asynchronous/session failures with stable `code`,
  `message`, and optional details, plus documented non-fatal warnings such as
  an audio-mux fallback that preserved a playable video.
- `addRecordingProgressListener`: `{ durationSeconds }` approximately every
  500 ms while recording. Delivery is best effort; the stop result is final.
- `addDownloadProgressListener`: `{ filterId, progress }` while a cloud package
  downloads. Progress is `0..1`, best effort, and the download Promise is final.
- `addFrameAvailableListener`: byte-free metadata hint for the latest retained
  processed frame. The event is advisory; `getLatestFrame()` is authoritative.
- View callbacks: processed-preview-ready and asynchronous camera-error events
  scoped to the active view.

Each listener returns `{ remove(): void }`. Register license/error listeners
before `initialize()`.

A failure caused by a caller-invoked Promise operation is delivered by that
Promise rejection. It is not duplicated through `addErrorListener`. Global and
view error events are reserved for asynchronous failures that have no pending
caller Promise. Wrap rejected values with `NosmaiSdkError.fromUnknown()` to
preserve the native code and optional details.

## Stable error codes

`NosmaiErrorCode` exports every stable value so applications do not need string
literals. Some Camera2/OES values remain Android-specific; the same values stay
reserved for cross-platform contract stability where applicable.

| Group                      | Codes                                                                                                                                                                                                                                                                           |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Contract/fallback          | `E_INVALID_ARGUMENT`, `E_INVALID_STATE`, `E_NOT_INITIALIZED`, `E_NOT_IMPLEMENTED`, `E_NATIVE_FAILURE`                                                                                                                                                                           |
| Initialization/license     | `E_INITIALIZATION`, `E_LICENSE_CALLBACK_UNAVAILABLE`, `E_LICENSE_KEY_MISMATCH`                                                                                                                                                                                                  |
| Camera setup               | `E_UNSUPPORTED_CAMERA_PRESET`, `E_CAMERA_PERMISSION`, `E_CAMERA_UNAVAILABLE`, `E_CAMERA_FACING_UNAVAILABLE`, `E_CAMERA_OUTPUT_UNAVAILABLE`, `E_CAMERA_OES_SURFACE`                                                                                                              |
| Camera operation           | `E_CAMERA_ACCESS`, `E_CAMERA_OPEN`, `E_CAMERA_OPEN_TIMEOUT`, `E_CAMERA_CLOSE_TIMEOUT`, `E_CAMERA_DISCONNECTED`, `E_CAMERA_DEVICE`, `E_CAMERA_CONFIGURE`, `E_CAMERA_PREVIEW_START`, `E_CAMERA_FRAME`, `E_FRAME_STREAM`, `E_CAMERA_INTERRUPTED`, `E_CAMERA_START`, `E_NO_PREVIEW` |
| Processing/OES             | `E_PROCESSING_START`, `E_PROCESSING_STOP`, `E_PIPELINE_NOT_READY`, `E_OES_UNAVAILABLE`, `E_OES_FALLBACK`                                                                                                                                                                        |
| Protected packages/catalog | `E_INVALID_PACKAGE_PATH`, `E_EFFECT_APPLY`, `E_EFFECT_CLEAR`, `E_EFFECT_STATE`, `E_EFFECT_PARAMETER`, `E_LOCAL_CATALOG`                                                                                                                                                         |
| Cloud                      | `E_CLOUD_DISABLED`, `E_CLOUD_CATALOG`, `E_CLOUD_DOWNLOAD`, `E_CLOUD_DOWNLOAD_TIMEOUT`, `E_CLOUD_REMOVE`                                                                                                                                                                         |
| Visual controls            | `E_BEAUTY_DISABLED`, `E_ADVANCED_FILTERS_DISABLED`, `E_VISUAL_CONTROL`, `E_BACKGROUND_RESOURCE`                                                                                                                                                                                 |
| Capture/recording          | `E_CAPTURE_IN_PROGRESS`, `E_CAPTURE_FAILED`, `E_RECORDING_PERMISSION`, `E_RECORDING_IN_PROGRESS`, `E_NOT_RECORDING`, `E_RECORDING_START`, `E_RECORDING_STOP`, `E_RECORDING_STORAGE_FULL`, `E_RECORDING_WRITE`, `E_RECORDING_AUDIO_MUX`, `E_RECORDING_INTERRUPTED`               |
| Media/gallery              | `E_MEDIA_NOT_FOUND`, `E_GALLERY_PERMISSION`, `E_GALLERY_SAVE`                                                                                                                                                                                                                   |
| Session/cleanup            | `E_SESSION_DESTROYED`, `E_OPERATION_CANCELLED`, `E_CLEANUP`                                                                                                                                                                                                                     |

## Deferred groups

Forced catalog refresh remains deferred. Android-only debug discovery/decrypt
and texture hooks are intentionally excluded. Flash/torch, authored effect
parameters, and bounded processed-frame streaming are implemented in source;
their wider physical-device qualification remains a release gate.
