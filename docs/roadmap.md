# Implementation roadmap

The plugin is being delivered behind explicit phase gates. A phase is complete
only when its listed automated checks and supported-device checks pass.

## Phase 1 — foundation (complete)

- Publishable Yarn workspace and Community CLI example on React Native 0.81.5.
- Typed TurboModule plus Fabric camera-view Codegen specs.
- Stable core API/types/errors/event contract.
- Kotlin and Objective-C++ native boundaries with honest unfinished errors.
- Proprietary AAR/framework/key/effect packaging exclusions.
- TypeScript, lint, unit-test, library-build, and Codegen CI baseline.
- Android API/ABI and iOS deployment/dependency requirements documented.

## Phase 2 — Android core

- [x] Wire `NosmaiSDK 3.0.x` without bundling its AAR.
- [x] Implement `NosmaiPreviewView` host and Camera2 ownership.
- [x] Prefer OES input with readiness listener, watchdog, and device fallback.
- [x] Implement YUV fallback with correct strides, pixel strides, and rotation.
- [x] Initialize, configure, start/stop, pause/resume, switch, and deterministic
      view/module cleanup.
- [x] Emit preview readiness, camera errors, license status, and authoritative
      pipeline-state events on the React Native queue.
- [x] Verify the licensed release build on a physical `arm64-v8a` device:
      initialization, first processed OES frame, front/back switch, pause/resume,
      background/foreground, and stop/release.
- [x] Verify a visible forced-YUV first frame and repeated pause/resume,
      camera-ID switch, and background/foreground stress on the Pixel 7 gate device.
- [x] Verify the default OES debug runtime and an authorized local-effect
      apply/query/remove/clear sequence on the Pixel 7 gate device.
- [x] Pass an R8-minified `arm64-v8a` example release build with the bridge's
      reflected SDK members retained by consumer rules.
- [x] Serialize effect mutations through a FIFO; during cleanup, settle an
      already-entered native mutation, cancel queued native entry, and complete
      transition/executor/main/GL-fenced clears before processing teardown.
- [ ] Verify the wider supported physical-device matrix.

The source/build gate plus OES/YUV release smoke and repeated lifecycle stress
on one physical device are complete, as are the debug and local-effect gates on
that device. Phase 2 remains open until the wider device-matrix gate passes.
The Android SDK 3.0.4 compatibility line also needs a public license listener and a restart-safe full
cleanup before those compatibility adapters can be removed.

## Phase 3 — iOS core

- [x] Link `NosmaiCameraSDK ~> 3.0.4` through CocoaPods only.
- [x] Attach `NosmaiCore.camera` preview ownership to the Fabric host.
- [x] Implement configure/start/stop/pause/resume/switch, foreground/background
      release, stale-view protection, and exact-once Promise settlement.
- [x] Emit first-processed-frame view readiness plus license, error, and
      authoritative pipeline-state module events.
- [x] Implement local protected-package apply/query/remove/scoped-clear/full-clear.
- [x] Verify generic `iphoneOS` Debug and Release builds with the device-only SDK
      and no attached iPhone.
- [x] Declare the `SystemBootTime` required-reason API category as `35F9.1` for
      the controller's `systemUptime`-based monotonic timing.
- [x] Build, sign, install, and launch the Release harness on a physical arm64
      iPhone.
- [x] Complete a user-confirmed Release smoke on one physical iPhone covering
      licensed initialization, first-frame readiness, lifecycle/camera controls,
      background/foreground recovery, and an authorized effect apply/state/remove/
      full-clear sequence.
- [ ] Verify explicit cleanup/reinitialize and scoped-clear controls, signed
      Debug runtime, and the wider supported iPhone/iOS matrix, including repeated
      teardown/restart stress.

## Phase 4 — feature parity

- [x] Expose the typed installed production package catalog and normalized rich
      metadata on Android and iOS; JavaScript, Codegen, and generic native build
      gates pass without bundling protected packages.
- [x] Run the minified Pixel 7 bridge smoke query; the fixture-free example
      returns a successful empty production catalog.
- [ ] Qualify catalog contents/category semantics on the wider Android matrix
      with non-empty production entries and on a licensed physical iPhone.
- [x] Implement cloud catalog/download/remove with progress, atomic pagination,
      same-ID coalescing, scoped cleanup protection, and strict local-path handling.
- [ ] Run the cloud network/download/remove matrix on physical Android and iOS
      devices, including offline, timeout, retry, pagination, and concurrent calls.
- [x] Implement the shared typed recording/progress, rendered photo-capture,
      and gallery-export APIs, including Android microphone audio muxing and iOS
      Photos add-only export.
- [ ] Run physical Android/iOS media qualification: rendered effects and
      mirroring, audio/video sync, interruption/finalization, gallery permissions,
      storage pressure, repeated capture/recording, and lifecycle stress. Android
      coverage must exercise front/back capture and recording on both default OES
      and forced-YUV inputs, including the supported mirror policy.
- [x] Add SDK-independent native fault-injection policy runners for current/stale
      recording watchdogs, low-storage classification, audio-mux silent-video
      fallback/warnings, and exact-once cleanup/lifecycle settlement races. Real
      media-framework and proprietary-SDK fault injection remains in the physical
      qualification gate above.
- [x] Implement beauty, custom-color makeup, signed ten-axis reshape, eye color,
      color/HSB controls, scoped clears, and blur/color/image/video backgrounds.
- [x] Resolve the inherited Flutter parity issues for beauty scaling, full HSB,
      real capability checks, catalog categories, broad clears, and progress/state
      events in the React Native contract.
- [ ] Run the complete visual-control combination and performance matrix on
      physical Android/iOS devices.
- [x] Add cross-platform flash/torch capability and mode controls, including
      capture-time Android rendered-photo illumination and safe reset behavior.
- [x] Add canonical authored effect-parameter metadata, scalar/string mutation,
      FIFO serialization, input hardening, and stable refusal errors.
- [x] Add bounded latest-only processed raw-frame sampling with byte-free
      availability events, explicit base64 pulls, plane/stride metadata,
      backpressure counters, and lifecycle cancellation on both platforms.
- [ ] Run the flash/torch and authored-parameter physical Android/iOS matrix,
      including front/back capability changes, low-light auto flash, capture
      cancellation, package-type aliases, ranges, enum/string/vector metadata,
      and lifecycle cleanup.
- [ ] Run the processed-frame physical format/performance matrix across Android
      OES/YUV and iOS BGRA/NV12 paths, including sustained backpressure,
      lifecycle interruption, and consumer decoding.

## Phase 5 — release

- [x] Complete example controls for every currently supported lifecycle,
      effect-state, scoped-clear, and installed-catalog operation.
- [ ] Complete unit, contract, Codegen, native build, lifecycle stress, and
      supported-device matrix gates.
- [x] Add and compile the React Native 0.81.5 New-Architecture compatibility
      line required by the Expo SDK 54 customer host.
- [ ] Add legacy-architecture adapters only if a separate customer requirement
      is approved; this compatibility line intentionally requires New Architecture.
- [x] npm tarball audit proving no proprietary binaries, keys, models, or effects.
- [x] Reject generated native Codegen output from the tarball and enforce the
      full check plus package audit before packing or publishing.
- [ ] Finalize versioned documentation, changelog, release tag, and controlled
      publishing.
