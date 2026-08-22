# Changelog

## 0.1.0-expo54-rn081.0

- Improved performance, stability, and reliability.

## Earlier development work

- Added an Expo SDK 57 config plugin that provisions the private Android AAR,
  verifies an optional SHA-256, configures the ARM64/New-Architecture host,
  adds iOS usage descriptions, and remains safe across CNG prebuilds.
- Added Expo development-build/EAS setup guidance and package-audit coverage for
  the exported `app.plugin` entry point.
- Added the React Native 0.86 library/example foundation.
- Added TurboModule and Fabric camera-view Codegen contracts.
- Added typed core lifecycle, protected-effect pipeline, state, events, and
  errors.
- Added Android Kotlin and iOS Objective-C++ Phase 1 native boundaries.
- Added proprietary binary/credential packaging protections and platform docs.
- Implemented the Android shared controller, Fabric preview host, Camera2 OES
  path, YUV fallback, lifecycle, camera switching, and view/module events.
- Implemented Android unified package application, authoritative pipeline state,
  path-safe scoped removal, scoped clears, and full clear handling.
- Serialized Android effect mutations through a FIFO; cleanup settles an active
  native mutation, cancels queued native entry, and waits for transition/
  executor/main/GL-fenced clears before processing teardown.
- Added an Android runtime-permission and lifecycle smoke harness to the example.
- Fixed late-mounted Android preview children remaining `0x0` under Fabric by
  explicitly measuring and laying out the SDK preview inside its native host.
- Added a development-only Android manifest override for deterministic YUV
  fallback qualification while preserving automatic OES selection by default.
- Added an example-only local-effect smoke harness for applying, querying,
  removing, and clearing an authorized device-side `.nosmai` package without
  bundling protected fixtures in the repository or npm package.
- Verified visible OES and forced-YUV paths, repeated lifecycle/camera-ID stress,
  the default debug runtime, and an authorized local-effect state sequence on a
  physical Pixel 7.
- Implemented the iOS process-wide controller, Fabric preview attachment,
  camera permission/start/stop/pause/resume/switch lifecycle, stale-view guards,
  and first-processed-frame readiness event.
- Implemented iOS license/error/pipeline events and local protected-package
  apply, metadata/state query, exact path removal, scoped clears, and full clear.
- Serialized iOS effect mutations and cleanup behind a native-state barrier, and
  hardened preview replacement plus inactive/active camera-processing recovery.
- Guarded iOS effect apply until native processing is active and fenced the
  asynchronous SDK 3.0.x clear queue before cleanup can resolve.
- Added an iOS bridge privacy manifest declaring `SystemBootTime` reason
  `35F9.1` for the controller's `systemUptime`-based monotonic timing.
- Added `getLocalFilters(packageType?)` across TypeScript, Android, and iOS for
  deterministic installed production-catalog discovery and rich metadata, plus
  an example catalog smoke control and stable `E_LOCAL_CATALOG` failures.
- Passed an R8-minified Android release build and a Pixel 7 catalog bridge smoke
  query; non-empty production-catalog metadata remains a device gate.
- Hardened the npm audit with required-output checks, build-directory rejection,
  and inline credential/private-key scanning without echoing matched values.
- Passed generic `iphoneOS` Debug and Release builds against the device-only
  Nosmai Effects SDK without an attached iPhone, plus a signed physical Release
  build/install/launch gate. A user-confirmed smoke on one physical iPhone
  covers licensed initialization, first processed preview, lifecycle/camera
  controls, background/foreground recovery, and local effect apply/query/remove/
  full-clear. Explicit cleanup/reinitialize and scoped-clear runtime, signed
  physical Debug, the wider iPhone/iOS matrix, and the wider Android device
  matrix remain pending.
- Added a traversal-safe `nosmai-documents:///` iOS path for authorized
  device-only effect fixtures without committing protected packages.
- Completed the example controls for the current core surface with active
  filter/effect info, scoped clears, typed catalog scopes, and explicit cleanup.
- Expanded JavaScript contract coverage for lifecycle/clear delegation, active
  state and package-info queries, path validation, active-effects events, and
  the Fabric view callback adapter.
- Hardened release packaging by explicitly rejecting generated Android/iOS
  Codegen trees and running the full check plus tarball audit before pack or
  publish.
- Added file-URI-based rendered photo capture, video recording/finalization,
  monotonic recording-progress events, recording-state queries, and explicit
  photo/video gallery export across TypeScript, Android, and iOS.
- Added Android microphone recording with AAC muxing, PixelCopy/JPEG capture,
  scoped MediaStore/legacy gallery writes, and media lifecycle guards; added
  iOS NosmaiCore capture/recording integration plus Photos add-only export.
- Added deterministic Android/JVM and iOS/Foundation recording failure-policy
  runners for watchdog generations, low storage, audio-mux fallback/warnings,
  and cleanup/lifecycle races, plus CI coverage that needs no proprietary SDK.
- Added example media controls and required microphone/photo-library usage
  descriptions. Physical media runtime qualification remains deferred until
  the final testing phase.
- Added a typed cloud catalog with category filtering, fetch-all or explicit
  pagination, atomic pagination metadata, safe ID de-duplication, coalesced
  downloads, normalized progress events, strict package-path verification, and
  exact sandboxed removal on Android and iOS.
- Added cross-platform beauty, custom-RGB layered makeup, signed ten-axis face
  reshape, modern eye color, brightness/contrast/RGB/sharpen/grayscale/hue/
  white-balance/full-HSB controls, and scoped reset APIs.
- Added advanced-license-gated manual blur, color, bounded local-image, and local-
  video background modes while preserving authored background-package state.
- Added selected-camera flash/torch capability and mode APIs. Android rendered
  photos use bounded capture-time illumination with exposure-driven auto flash,
  exact-once preparation timeout, and guaranteed light restoration; torch is
  restricted to explicit off/on on both platforms.
- Added canonical authored `.nosmai` parameter metadata plus numeric/string
  getters and setters on Android and iOS, with FIFO serialization, optional
  pass IDs, strict input limits, and `E_EFFECT_PARAMETER` refusal errors.
- Added a bounded processed raw-frame API on Android and iOS with latest-only
  backpressure, byte-free availability events, explicit off-main base64 pulls,
  format/color-range/plane metadata, lifecycle cancellation, and strict native
  payload validation.
- Expanded the example into camera, package/cloud, and visual-control harnesses,
  including flash/torch, authored parameters, and processed-frame sampling;
  JavaScript contract coverage includes cloud normalization/progress,
  cross-platform value scales, clear scoping, backgrounds, lights, parameters,
  and frame safety limits.
