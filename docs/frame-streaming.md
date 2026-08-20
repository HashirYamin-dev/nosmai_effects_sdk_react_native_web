# Processed raw-frame streaming

The raw-frame API exposes SDK-processed camera frames without automatically
pushing pixel bytes through the React Native event channel. It is intended for
bounded-rate analysis and integrations that can tolerate a CPU/base64 copy. It
is not a zero-copy encoder or WebRTC texture bridge.

## Lifecycle and backpressure

1. Initialize the SDK, mount the preview, and await `startProcessing()`.
2. Register `addFrameAvailableListener` and call `startFrameStream()`.
3. Treat each event as an availability hint. Call `getLatestFrame()` to
   atomically consume the current native slot.
4. Call `stopFrameStream()` before pausing or stopping the camera. Native
   lifecycle teardown also stops the stream and clears its slot.

Only one copied frame is retained natively. If another accepted frame arrives
before JavaScript consumes the slot, it replaces the older frame and increments
`droppedFrames`. Frames skipped by the configured rate limit also increment
that counter. `getLatestFrame()` returns `undefined` when the active slot is
empty, and concurrent reads reject with `E_INVALID_STATE`. Stopping the stream
while a read is encoding rejects that read with `E_OPERATION_CANCELLED`.

`maxFramesPerSecond` defaults to `2` and is limited to `1..5`. An individual
native frame is limited to 8 MiB before base64 encoding. This protects the
bridge from unbounded traffic, but applications must still consume frames only
as quickly as their analysis work permits. The rate limit bounds retained
native copies, metadata events, and bridge pulls; it does not reduce the SDK's
own offscreen render/readback callback cadence while the stream is active.

```ts
let readInFlight = false;
const subscription = NosmaiCameraSdk.addFrameAvailableListener(() => {
  if (readInFlight) return;
  readInFlight = true;
  void NosmaiCameraSdk.getLatestFrame()
    .then((frame) => {
      if (!frame) return;

      // Decode frame.dataBase64 in the integration that needs the CPU pixels.
      // Plane offsets and row strides describe the decoded byte sequence.
      analyzeFrame(frame);
    })
    .catch((error) => {
      // A concurrent lifecycle stop can reject with E_OPERATION_CANCELLED.
      console.warn('Processed-frame pull failed', error);
    })
    .finally(() => {
      readInFlight = false;
    });
});

await NosmaiCameraSdk.startFrameStream({ maxFramesPerSecond: 2 });

// Later:
await NosmaiCameraSdk.stopFrameStream();
subscription.remove();
```

## Formats and planes

The bridge reports the actual supported native callback format instead of
silently converting or guessing it:

- Android: tightly packed `i420` (Y, U, V planes) or `rgba8888`.
- iOS: stride-preserving `bgra8888`, or bi-planar `nv12` when the SDK provides
  a YUV pixel buffer.

`colorRange` is `full`, `video`, or `unknown`. Each plane specifies `offset`,
`byteLength`, `bytesPerRow`, `width`, and `height` inside the bytes obtained by
base64-decoding `dataBase64`. iOS row padding is retained and described by
`bytesPerRow`; consumers must not assume tightly packed rows.

Frame availability metadata and the consumed frame both contain a monotonic
stream `sequence`, `timestampSeconds`, dimensions, format, byte count, plane
layout, and cumulative `droppedFrames`. Because the native slot is latest-only,
the consumed frame is authoritative if a newer frame replaces the event's
snapshot before JavaScript reads it.

Android's native SDK also exposes a process-local GL texture callback, but GL
texture IDs and fences are context-bound and cannot safely cross the general
React Native bridge. That zero-copy surface is intentionally not part of this
portable API. Flutter's existing `startLiveFrameStream` methods are placeholders
and do not provide a reusable public contract.
