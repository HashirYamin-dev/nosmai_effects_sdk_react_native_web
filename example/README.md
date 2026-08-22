# Nosmai Effects SDK for React Native example

This is a development harness for the Android/iOS camera core and Phase 4
feature-parity surface. It is not a general React Native starter application.
Use the top-right control to cycle through **Camera**, **Packages**, and
**Visual** groups. The harness covers lifecycle/media, installed-package
catalogs and cloud packages, download progress/pagination,
beauty/makeup/reshape/eye color, color/HSB, and all four manual background
modes. It also exposes flash/torch, authored package parameters, and bounded
processed raw-frame sampling. Generic `iphoneOS` Debug and Release builds can
be verified without an attached iPhone; physical runtime qualification is
still required for camera, cloud, visual, light, and frame behavior.

## Requirements

- For Android runtime checks, a physical `arm64-v8a` device with developer
  options enabled and an authorized Nosmai Android SDK 3.0.4 AAR.
- For iOS builds, macOS/Xcode, CocoaPods, and authorized access to
  `NosmaiCameraSDK ~> 3.0.4`.
- For iOS runtime checks, a physical arm64 iPhone running iOS 15 or newer.
- A locally supplied development license key bound to the example application's
  platform identity. Enter it at runtime in the secure field; the example does
  not preload or persist license keys. Android and iOS use the neutral example
  identity `nosmai.reactnativecamerasdk.example`.
- The Node/Yarn versions declared by the root package.

An Android emulator can exercise JavaScript and bridge startup, but it cannot
complete the Nosmai rendering gate. The current iOS framework has no simulator
slice, so iOS Simulator is not a supported build or runtime destination.

## Add the proprietary AAR locally

From the repository root, create this ignored directory and copy exactly one
authorized artifact into it:

```text
example/android/app/libs/nosmai-release.aar
```

The example Gradle project detects that exact path. Do not rename the file, add
another AAR copy, or place the artifact under the library's `android/` folder.
The AAR is intentionally ignored by Git and excluded from the npm package.
Verify its checksum through your authorized delivery channel before building.

## Install and run

From the repository root:

```sh
yarn install
yarn example start
```

In another terminal, with the physical device visible to `adb devices`:

```sh
yarn example android
```

For a local release-build gate (the example release config uses a development
signature and is not for distribution):

```sh
cd example/android
./gradlew app:assembleRelease -PreactNativeArchitectures=arm64-v8a
```

To additionally qualify the library's consumer rules through R8, append
`-PnosmaiMinifyRelease=true` to that command. Minification remains off by
default so the development harness stays easy to debug.

To exercise the YUV fallback deterministically, build the same harness with the
development-only manifest switch enabled:

```sh
cd example/android
./gradlew app:assembleRelease \
  -PreactNativeArchitectures=arm64-v8a \
  -PnosmaiForceYuv=true
```

The default is `false`; production apps should normally use automatic OES/YUV
selection. The underlying manifest key is
`com.nosmai.camerasdk.reactnative.FORCE_YUV` for device qualification and
emergency compatibility overrides. A host app can opt in directly under its
`<application>` element:

```xml
<meta-data
  android:name="com.nosmai.camerasdk.reactnative.FORCE_YUV"
  android:value="true" />
```

This is a build-time setting, so rebuild and reinstall the app after changing
it.

## Verify generic iOS device builds

After installing the example pods, run the generic device builds from the
repository root:

```sh
xcodebuild \
  -workspace example/ios/ReactNativeCameraSdkExample.xcworkspace \
  -scheme ReactNativeCameraSdkExample \
  -configuration Debug \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build

xcodebuild \
  -workspace example/ios/ReactNativeCameraSdkExample.xcworkspace \
  -scheme ReactNativeCameraSdkExample \
  -configuration Release \
  -sdk iphoneos \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO build
```

These commands verify compilation and linking against the device-only SDK
without a connected iPhone. They do not qualify licensed runtime behavior.

## Android runtime smoke sequence

1. Confirm the preview component is visible. It is mounted before any start
   request is made.
2. Paste the intended app-bound Android key into the secure field, then tap
   **Initialize**. The example does not preload or persist the value.
3. Tap **Start**, grant camera permission when Android asks, and wait for the
   `Camera ready on android` status. `startProcessing()` resolving alone does
   not prove that a processed frame rendered.
4. Exercise pause/resume, front/back switching, stop/start, background/foreground,
   and a React reload. Watch both the view error and global asynchronous-error
   status paths.
5. In **Packages**, tap **Catalog** after initialization and confirm the installed
   production-package count is returned without exposing protected contents.
   Use **Type** to cycle through all/filter/effect/background/beauty-effect
   catalog scopes before querying. **Info**, **Clear Filter**, **Clear AR**, and
   **Clear All** exercise the remaining supported package-state operations.
6. Still in **Packages**, use **Cloud p1**, **Prev/Next**, **All**, **Select
   Next**, **Download**, **Apply Cloud**, and **Remove Download** to exercise the
   license capability, paginated/fetch-all catalogs, transfer progress,
   application, and exact local removal.
7. After applying an authored package, use **Parameters**, **Select Next**,
   **Read Number**, **Set Number**, and **Set Text** to inspect and change its
   exposed parameter surface. A package may validly expose no parameters, and
   selecting the wrong setter for a parameter type should produce the stable
   effect-parameter error.
8. In **Visual**, exercise capabilities, beauty, all layered makeup, reshape,
   eye color, color/HSB, scoped clears, and blur/color backgrounds. Supply a
   readable absolute `file://` URI before testing image/video backgrounds.
9. Return to **Camera**, switch to a camera with a light, then use **Lights**,
   **Flash**, and **Torch**. Flash is a still-capture preference; torch lights
   the preview continuously. Use **Start Frames**, **Pull Frame**, and **Stop
   Frames** to verify availability metadata and one explicitly consumed
   processed frame. The harness never displays or logs its base64 bytes.
10. Use **Cleanup** for explicit logical-session teardown;
    initialize again before starting a new session.
11. After the processed preview is ready, use **Capture** for a rendered JPEG.
    Use **Record** and **Stop** for an MP4 while watching the elapsed timer.
    Android asks for microphone permission before recording. **Save Photo** and
    **Save Video** export the most recent cache file to the system gallery; API
    24–28 may also ask for legacy gallery-write permission.

## iOS physical-runtime qualification

On one authorized physical iPhone, the signed Release harness passed build,
install, and launch gates. The user-confirmed manual smoke covered licensed
initialization, first processed-frame readiness, pause/resume, camera switching,
background/foreground recovery, and package-state operations. The newly added
explicit cleanup/reinitialize and scoped-clear controls, signed Debug runtime,
and the wider supported iPhone/iOS matrix remain open. A generic `iphoneOS`
build by itself is not evidence that runtime checks passed.

The media harness also declares `NSMicrophoneUsageDescription` and
`NSPhotoLibraryAddUsageDescription`. On the final media qualification pass,
verify capture dimensions, active effects, requested mirroring, recording
audio/video sync and duration, background interruption, and both gallery-save
buttons. These media checks have not yet been claimed by the earlier core smoke.

Do not print, log, screenshot, paste into issue trackers, or send a complete
license key to analytics. The npm package excludes this example, but a key
hardcoded in the example remains visible in Git history if committed. Never
commit the AAR or protected effect fixtures. Android and iOS keys are normally
different and app-bound.

## Current limitations

- Licensed OES and forced-YUV first-processed-frame checks plus repeated
  pause/resume, camera-ID switch, and background/foreground stress passed on one
  Pixel 7 release device. The default OES debug runtime and an authorized local
  effect apply/query/remove/clear sequence also passed there. A broader device
  matrix remains a Phase 2 gate.
- The Android 3.0.4 process core is retained across logical cleanup; reuse the
  same key within one process.
- Cloud catalog/download/remove and the complete visual-control surface are
  implemented; their physical Android/iOS qualification is deferred to the
  final testing phase.
- Capture, recording/progress, and gallery export are implemented in source;
  physical Android/iOS media qualification is intentionally deferred to the
  final testing phase.
- Flash/torch, authored effect parameters, and latest-only processed raw-frame
  sampling are implemented. Real light/exposure behavior, authored package
  parameter semantics, frame formats/strides, and sustained device performance
  remain in the final physical Android/iOS qualification phase. Raw frames use
  a bounded CPU/base64 pull API, not a zero-copy texture or WebRTC transport.
- The Phase 3 iOS core and one signed physical Release smoke pass. Explicit
  cleanup/reinitialize and scoped-clear controls, signed Debug runtime, repeated
  stress, and the wider supported iPhone/iOS matrix remain open.
