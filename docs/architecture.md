# Architecture

## Layers

```text
Application
  ├─ NosmaiCameraView (Fabric native component)
  └─ NosmaiCameraSdk (typed JavaScript singleton)
       ↓ React Native Codegen
TurboModule + native view manager
       ↓ platform controller
Android Phase 2: Camera2 → OES/YUV → NosmaiPreviewView → Nosmai SDK
iOS Phase 3: AVCapture → NosmaiCore camera → native UIView → Nosmai SDK
       ↓
Source → filter/effect graph → rendered preview/capture/recording
```

The JavaScript layer never processes camera frames and never parses protected
`.nosmai` packages. Camera ownership, GPU resources, SDK calls, and permission
results stay native.

## Ownership and lifecycle

- The TurboModule is process-scoped; initialize once per process.
- A mounted native view owns its preview attachment, not the license/session.
- Unmount stops and detaches only resources owned by that view. It must not let
  a stale view tear down a newer preview.
- `cleanup()` is final logical session teardown. Reinitialization is required
  before reuse. Android SDK 3.0.0 keeps its process core alive because its full
  cleanup does not reset `NosmaiCore`; see the documented compatibility note.
- Both platforms require an explicit recording stop before caller-driven camera
  teardown. Lifecycle interruption performs best-effort recording finalization,
  then stops camera input before SDK processing.
- UI/view changes run on the main thread; GPU/camera work uses native queues.

Android uses one process-wide `NosmaiAndroidController`. The TurboModule and
Fabric manager adopt the active React context through the same registry;
generation-bound lifecycle listeners prevent an old bridge teardown from
affecting its replacement. Monotonic session, view, and camera generations reject
stale unmounts, Camera2 callbacks, OES textures, and license callbacks. Blocking
Camera2 open/close work is serialized away from the main thread; YUV plane
buffers are consumed synchronously on the camera thread before `Image.close()`.
Android effect mutations use a FIFO. Cleanup stops admission, lets an operation
already inside the native SDK settle, cancels queued native entry, then completes
transition/executor/main/GL-fenced clears before tearing down processing.
Beauty, makeup, reshape, color, and manual-background mutations use that FIFO
and emit a fresh authoritative pipeline snapshot after each mutation. Cloud
catalog snapshots run on a separate serial worker; downloads are coalesced by
filter ID and emit progress without blocking the camera/UI queue.
Rendered still capture uses PixelCopy from the SDK's preview SurfaceView and
writes JPEG files on a dedicated media executor. Android recording combines the
SDK-rendered MP4 stream with a microphone AAC track through a resource-safe
MediaExtractor/MediaMuxer stage before resolving the stop Promise.

iOS uses one process-wide `NosmaiIOSController` shared by the TurboModule and
Fabric component. Weak active-view/module sinks plus session, view, and camera
generations prevent stale React objects and asynchronous native callbacks from
mutating a replacement session. Effect mutations use a main-queue-confined FIFO:
each native completion and authoritative pipeline-state transition finishes
before the next apply/remove/clear begins. Cleanup drains that FIFO before its
final clear barrier and fences the SDK 3.0.x effects queue before allowing a new
session. UIKit state is main-queue confined; blocking AVCapture transitions run
on a serial camera queue. Readiness is emitted once per camera generation from
the SDK's processed-frame callback.
Visual mutations share the effect FIFO and fence the native effects queue before
their Promise resolves. Cloud list/pagination snapshots and downloads are
serialized away from the main queue; same-ID callers share one native transfer.
NosmaiCore owns iOS photo and recording production; the controller serializes
media admission, freezes duration before native stop, writes JPEG output away
from the main queue, and uses Photos add-only changes for explicit gallery
exports. Both controllers emit generation-bound progress and never send raw
JPEG bytes through the React Native boundary.

## Pipeline state

Native pipeline state is authoritative. The public snapshot normalizes platform
field aliases but does not infer state from the last JavaScript method call.
Installed catalog results are also normalized at the JavaScript boundary,
sorted deterministically, and de-duplicated by non-empty local package path.
Cloud results keep native item and pagination snapshots atomic and de-duplicate
by downloadable ID while retaining server order.

- Color filter: one independent slot.
- AR effect and beauty-effect package: one shared slot.
- Background package/manual background: separate background state.
- Built-in beauty/makeup interactions follow native SDK policy.

Call scoped clears (`clearFilter`, `clearAREffect`) when ownership matters.
`clearAll` is deliberately explicit; ambiguous legacy `removeAllFilters`
semantics are not part of the new contract.

## Binary distribution

- Android: the consuming app supplies one verified `nosmai-release.aar`; the
  library uses it as compile-only and never packages it.
- iOS: the plugin pod depends on `NosmaiCameraSDK ~> 3.0.3`; no copied framework.
- iOS generic `iphoneOS` Debug and Release builds target `generic/platform=iOS`
  and can be verified without an attached iPhone. Licensed rendering, lifecycle,
  license-event, and effect behavior has been manually smoke-tested with a
  signed Release build on one physical arm64 iPhone; signed Debug and wider
  device/version qualification still require physical devices.
- The iOS privacy manifest declares `SystemBootTime` reason `35F9.1` because the
  controller uses `NSProcessInfo.processInfo.systemUptime` for monotonic
  deadlines and debounce timing.
- npm exclusions block `.aar`, `.framework`, `.xcframework`, `.nosmai`, and
  common key/env filenames as defense in depth.
- Native runtime tests require authorized physical arm64 devices and app-bound
  platform keys.

## React Native architecture support

Codegen output is app-generated rather than committed because generated C++ and
platform glue are React-Native-version-coupled. This compatibility line is
qualified on React Native 0.81.5 with the New Architecture enabled. The shared
native controllers remain architecture-neutral, but no legacy-architecture
TurboModule/view adapter is shipped or supported by this branch.
