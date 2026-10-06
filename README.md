# Nosmai Effects SDK for React Native

The official typed React Native New Architecture bindings for Nosmai Effects.
Install the SDK as `@nosmai/react-native-effects-sdk`. The
native core covers camera preview and lifecycle, rendered photo/video media,
local and cloud protected packages, beauty/makeup/reshape/eye-color controls,
color/HSB adjustment, interactive camera games, and manual
blur/color/image/video backgrounds.

> Stable release: version `1.0.0` is the production React Native package for
> the supported compatibility baseline below. Validate camera, effect, and
> recording behavior on the physical device models targeted by your app.

## Compatibility

| Layer             | Supported baseline                                                                                         |
| ----------------- | ---------------------------------------------------------------------------------------------------------- |
| Expo              | SDK `54.x`; custom development/EAS build required (Expo Go is unsupported)                                 |
| React Native      | `0.81.5` compatibility line; `>=0.81.5 <0.82.0` peer range                                                 |
| React             | `19.1.x`                                                                                                   |
| Architecture      | New Architecture only (TurboModule + Fabric); legacy architecture is not supported                         |
| Android           | Effective RN minimum API 24; native SDK supports API 21; physical `arm64-v8a` or `armeabi-v7a` device      |
| iOS               | iOS 15+; physical arm64 iPhone or iPad                                                                      |
| Native Nosmai SDK | Android `3.0.4`; iOS `NosmaiCameraSDK 3.0.4`                                                               |

Version `1.0.0` targets Expo SDK 54, React Native 0.81.5, React 19.1, and the
React Native New Architecture. The current iOS SDK framework is device-only,
so bridge linking targets `generic/platform=iOS` rather than iOS Simulator.
Generic Debug and Release compilation does not need an attached iPhone, but
Nosmai rendering must be tested on a supported physical arm64 device.

## Expo SDK 54

Expo SDK 54 is supported through the packaged Expo config plugin. It uses the
React Native 0.81 and React 19.1 baseline supported by this stable release.
Expo Go cannot load Nosmai's custom native module, so use an Expo development
build or an EAS build.

Keep the authorized Android AAR outside the generated `android/` directory so
`expo prebuild --clean` cannot delete the source artifact. For example:

```text
vendor/nosmai-release.aar
```

Configure the package in `app.config.js`:

```js
export default {
  expo: {
    plugins: [
      [
        '@nosmai/react-native-effects-sdk',
        {
          androidAarPath:
            process.env.NOSMAI_ANDROID_AAR_PATH ??
            './vendor/nosmai-release.aar',
          androidAarSha256: process.env.NOSMAI_ANDROID_AAR_SHA256,
          cameraPermission:
            'Allow this app to use the camera for Nosmai effects.',
          microphonePermission:
            'Allow this app to use the microphone when recording video.',
          photoLibraryAddPermission:
            'Allow this app to save captured photos and videos.',
        },
      ],
    ],
  },
};
```

The plugin copies the AAR to the generated Android app, adds its runtime Gradle
dependency, restricts Android builds to `arm64-v8a` and `armeabi-v7a`, keeps
New Architecture enabled, and adds the three required iOS usage descriptions. Android manifest
permissions already merge from this library; the host app must still request
camera and microphone permissions at runtime.

```sh
npx expo install expo-dev-client
npx expo prebuild --clean
npx expo run:android
# or, on a physical iPhone:
npx expo run:ios --device
```

`--clean` recreates native directories, so commit or back up unrelated manual
native changes first. For EAS, provide the proprietary AAR through an approved
private build source or an EAS file environment variable; never put it in this
npm package or a public repository. License keys remain runtime, app-bound
values and are intentionally not accepted by the config plugin.

See [Expo SDK 54 integration](docs/expo-sdk-54.md) for options,
troubleshooting, and EAS notes.

## Installation

Install the stable JavaScript package:

```sh
yarn add @nosmai/react-native-effects-sdk
```

### Android native SDK

The npm package does not contain Nosmai's proprietary AAR. Download the
authorized Android `3.0.4` artifact and `SHA256SUMS` from the
[Nosmai Effects SDK for Android v3.0.4 release](https://github.com/nosmai/nosmai_effects_sdk_android/releases/tag/v3.0.4),
verify its checksum, and place exactly one copy at:

```text
android/app/libs/nosmai-release.aar
```

Add it to the consuming app, not to this library:

```gradle
android {
  defaultConfig {
    ndk {
      abiFilters "arm64-v8a", "armeabi-v7a"
    }
  }
}

dependencies {
  implementation files("libs/nosmai-release.aar")
}
```

The host application owns runtime permission requests. The library declares
`CAMERA`, `INTERNET`, `RECORD_AUDIO`, and legacy
`WRITE_EXTERNAL_STORAGE` (limited to API 28). Recording requires microphone
permission. Saving app-created media needs no storage permission on API 29+;
API 24–28 requires the host to grant legacy write permission. The plugin never
requests `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO`.

Call `PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CAMERA)` before
`startProcessing()`. The plugin intentionally does not present permission UI;
it rejects with `E_CAMERA_PERMISSION` when permission is missing. Android accepts
the default/720p preset and an optional 480p preset. The native SDK is
process-global, so only one `NosmaiCameraView` owns the active preview at a time;
a newer mounted view supersedes stale ownership.

Android prefers direct OES input. MediaTek-family hardware and known affected
brands start in YUV mode, while runtime OES errors or readiness timeouts trigger
a one-way YUV fallback for that preview session.

Android effect mutations run through one FIFO. During `cleanup()`, the bridge
stops admission, lets an operation that already entered the native SDK settle,
and cancels queued operations before native entry. Scoped clears wait for both
their callback and the native transition to become idle; full clear fences the
SDK effect executor, main queue, and preview GL queue before teardown. Test the
complete lifecycle on every Android device family supported by your app.

### iOS native SDK

The podspec depends on `NosmaiCameraSDK ~> 3.0.4`; it does not vendor a
framework. Install pods from the application:

```sh
cd ios
bundle exec pod install
```

Add `NSCameraUsageDescription` to the app's `Info.plist`. Recording also needs
`NSMicrophoneUsageDescription`, and gallery export needs
`NSPhotoLibraryAddUsageDescription`. The current
native framework has no simulator slice, so use a physical arm64 device for
camera/render verification. On first iOS start the bridge requests camera
access when authorization is undetermined; denied or restricted access rejects
with `E_CAMERA_PERMISSION`.

The packaged iOS privacy manifest declares the `SystemBootTime` required-reason
API category with reason `35F9.1`. The controller reads
`NSProcessInfo.processInfo.systemUptime` for monotonic operation deadlines and
debounce timing.

## Core API contract

The preview must be mounted before `startProcessing()`. Register license/error
listeners before `initialize()` because final license verification is
asynchronous. Mount this session only after the user submits an app-bound key,
and keep that prop stable for the session. This minimal component uses
`onLayout` as the mount boundary; an Android host must also request camera
permission before starting.

```tsx
import { useEffect, useState } from 'react';
import {
  NosmaiCameraSdk,
  NosmaiCameraView,
} from '@nosmai/react-native-effects-sdk';

function Preview({ licenseKey }: { licenseKey: string }) {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    if (!mounted) return;

    const licenseSubscription = NosmaiCameraSdk.addLicenseStatusChangedListener(
      (status) => {
        // Update application state. Do not log the key itself.
        handleLicenseStatus(status);
      }
    );
    const errorSubscription =
      NosmaiCameraSdk.addErrorListener(handleAsyncError);
    let disposed = false;

    const start = async () => {
      const initialized = await NosmaiCameraSdk.initialize(licenseKey);
      if (!initialized || disposed) return;
      await NosmaiCameraSdk.configureCamera({ position: 'front' });
      if (!disposed) await NosmaiCameraSdk.startProcessing();
    };
    void start().catch(handleOperationError);

    return () => {
      disposed = true;
      licenseSubscription.remove();
      errorSubscription.remove();
      void NosmaiCameraSdk.cleanup().catch(handleOperationError);
    };
  }, [licenseKey, mounted]);

  return (
    <NosmaiCameraView
      style={{ flex: 1 }}
      cameraPosition="front"
      mirror
      onLayout={() => setMounted(true)}
      onReady={({ platform }) => handlePreviewReady(platform)}
      onError={handleViewError}
    />
  );
}
```

Never commit, log, screenshot, or send a complete license key to analytics.
Android and iOS applications normally use different app-bound keys.

On the native SDK 3.0.4 compatibility line, `cleanup()` performs camera/preview/session teardown but
intentionally keeps the native process core initialized. The SDK's full cleanup
currently leaves its `NosmaiCore` reinitialization guard stale. A later session
in the same process must therefore use the same app-bound platform key;
attempting a different key rejects with `E_LICENSE_KEY_MISMATCH`. The bridges
keep this compatibility logic isolated until the native SDK exposes restart-safe
full teardown.

A resolved `startProcessing()` means the native start request was accepted; it
does not prove that a processed frame rendered. Treat the view's `onReady` as
the render-readiness signal. That signal has been verified on OES and forced-YUV
paths on one Pixel 7 and manually observed with the signed Release harness on
one physical iPhone. Applications should verify first-frame readiness on their
supported physical-device matrix before shipping.

The current public surface contains lifecycle, preview, rendered photo capture,
video recording/progress, gallery export, local and cloud protected-package
catalogs, download/remove progress, package application and scoped clearing,
beauty/makeup/reshape/eye color, color/HSB adjustment, four manual background
modes, flash/torch, authored package parameters, bounded processed raw-frame
sampling, pipeline state, and events. See [the API contract](docs/api-contract.md)
and [phase roadmap](docs/roadmap.md).

Capture and recording return app-owned temporary `file://` URIs. Pass those URIs to
`saveImageToGallery()` or `saveVideoToGallery()` when the user explicitly asks
to keep the media. Android returns a `content://` gallery URI and iOS returns a
`ph://` asset identifier. JPEG bytes are intentionally not copied across JSI.
Operational failures reject with stable `NosmaiSdkError` codes.

```ts
const progress = NosmaiCameraSdk.addRecordingProgressListener(
  ({ durationSeconds }) => updateRecordingTimer(durationSeconds)
);

const photo = await NosmaiCameraSdk.capturePhoto();
await NosmaiCameraSdk.saveImageToGallery(photo.uri);

// On Android, grant RECORD_AUDIO in the host before this call.
await NosmaiCameraSdk.startRecording();
// Keep effects active or change them while the rendered stream records.
const video = await NosmaiCameraSdk.stopRecording();
await NosmaiCameraSdk.saveVideoToGallery(video.uri);

progress.remove();
```

Recording must be stopped before configuring, pausing, switching, stopping, or
cleaning up the camera session. This preserves a final result URI instead of
silently discarding an in-progress recording. A lifecycle interruption is the
exception: native code performs best-effort finalization and reports an
asynchronous recording error when JavaScript does not own a pending stop.

On Android, recorded-video mirroring currently follows the native SDK's camera-
facing policy: front-camera video is mirrored and back-camera video is not. The
React Native `mirror` prop still controls the displayed preview and rendered
photo capture. Custom or inverted mirroring for recorded video requires
upstream native-SDK qualification.

Android SDK 3.0.x does not expose an abort/reset API for a recorder whose native
start or stop callback never arrives. The bridge watchdog settles the pending
operation and quarantines that camera session instead of attempting unsafe
reuse; the host process must be restarted before another camera session. This
policy is covered by SDK-independent stale/current-generation fault injection;
the host should restart the process if this native timeout occurs. See
[native recording failure tests](docs/native-recording-failure-tests.md).

`getLocalFilters()` returns every installed production package, or accepts one
of `filter`, `effect`, `background`, `beauty_effect`, and `game`. Returned paths are
directly reusable with `applyEffect()`; Android production assets remain
asset-relative, while filesystem packages remain subject to readability checks.
Authorized iOS fixtures stored in the app's Documents directory can be passed
as `nosmai-documents:///<relative-path>.nosmai`; traversal and symlink escapes
are rejected natively.

## Cloud packages

Cloud access is license-gated. `getCloudFilters()` returns the filters and its
pagination snapshot together, so concurrent requests cannot mix catalog data
with another request's pagination. Omit `page` for fetch-all behavior, or pass a
one-based page for explicit pagination. Package-type values are the same five
values used by the local catalog.

```ts
const progress = NosmaiCameraSdk.addDownloadProgressListener(
  ({ filterId, progress: value }) => {
    updateDownload(filterId, Math.round(value * 100));
  }
);

const page = await NosmaiCameraSdk.getCloudFilters({
  packageType: 'effect',
  page: 1,
  limit: 20,
});
const remote = page.filters[0];
if (remote) {
  const downloaded = await NosmaiCameraSdk.downloadCloudFilter(remote);
  await NosmaiCameraSdk.applyEffect(downloaded.path);
  await NosmaiCameraSdk.removeCloudFilter(remote);
}
progress.remove();
```

Downloaded package paths are native-owned absolute paths and can be passed
directly to `applyEffect()`. Removal targets only the exact resolved package
file inside the app sandbox. Catalog cleanup is deliberately disabled for
scoped/page requests. A bridge download watchdog rejects a lost callback after
315 seconds; the SDK has no cancellation API, so an underlying transfer may
still finish later.

For development builds, loose `.nosmai` files bundled under `assets/filters`
can be discovered without a production manifest/preview folder:

```ts
const debugPackages = await NosmaiCameraSdk.getDebugFilters();
const debugGames = await NosmaiCameraSdk.getDebugFilters('game');
```

Use `getLocalFilters()` for the installed production package catalog.

## Interactive games

Apply a `game` package through the same `applyEffect()` method. By default,
`NosmaiCameraView` automatically converts taps inside the preview to normalized
coordinates and forwards them to the active game:

```tsx
<NosmaiCameraView style={{ flex: 1 }} />
```

Listen for typed JSON-safe output events as usual:

```ts
const subscription = NosmaiCameraSdk.addGameEventListener((event) => {
  if (event.event === 'gameOver') {
    updateScore(event.data.score);
  }
});

subscription.remove();
```

To intercept taps, provide `onGameTap`. The callback replaces automatic
forwarding, so the application can decide whether to send the tap:

```tsx
<NosmaiCameraView
  style={{ flex: 1 }}
  onGameTap={async ({ normalizedX, normalizedY }) => {
    if (shouldForwardTap) {
      await NosmaiCameraSdk.sendGameTap(normalizedX, normalizedY);
    }
  }}
/>
```

Set `enableGameTapHandling={false}` to remove the tap overlay completely.
Named controls remain available through `sendGameInput()` for games that need
inputs beyond a basic tap.

Games own the visual pipeline exclusively while active. Normal camera lifecycle
operations pause and resume them automatically; explicit pause, resume, and
restart methods are also available.

## Flash, torch, and authored parameters

Flash is a still-photo preference (`off`, `on`, or `auto`); torch is continuous
preview illumination (`off` or `on`). Query `hasFlash()`/`hasTorch()` for the
selected camera, then check the boolean result from the setter. A setter returns
`false` when that camera cannot provide the requested mode. Camera-facing
changes and `cleanup()` reset both modes to `off` so illumination cannot leak
into a replacement session.

Android photos are rendered preview-frame captures, not Camera2 still requests.
The bridge therefore keeps ordinary repeating requests flash-free, temporarily
illuminates the preview only while capturing, and always restores torch state.
`auto` is accepted only when the device exposes auto-flash AE and illuminates
only after Camera2 reports that flash is required. iOS delegates the same public
preference to `NosmaiCamera`.

After applying an authored `.nosmai` package, inspect and update its declared
parameters:

```ts
const parameters = await NosmaiCameraSdk.getEffectParameters();
const intensity = parameters.find((item) => item.name === 'intensity');
if (intensity?.type === 'float') {
  await NosmaiCameraSdk.setEffectParameter('intensity', 0.75);
}
await NosmaiCameraSdk.setEffectParameterString('caption', 'Hello');
```

Metadata canonicalizes numeric, boolean, string, vector/color, and enum aliases.
`passId` is optional because Android SDK 3.0.x does not expose one. The scalar
getter supports float/int/bool; read string/vector/enum values from the metadata
snapshot. A missing or type-incompatible parameter rejects with
`E_EFFECT_PARAMETER`. Names are capped at 128 characters, string values at
16,384, and control characters are rejected before native entry. Because local
packages are untrusted input, returned metadata is also bounded: at most 256
parameters, 128 options per parameter, and 64 vector components. Text is
sanitized/capped and non-finite numbers or invalid ranges/pass IDs are omitted.

## Processed raw frames

The opt-in frame API is a bounded latest-frame pull surface for CPU analysis. A
small metadata event says that a frame is available; bytes cross the bridge only
when JavaScript explicitly calls `getLatestFrame()`. One native slot replaces
older unconsumed frames and reports the cumulative drop count.

```ts
let frameReadInFlight = false;
const frames = NosmaiCameraSdk.addFrameAvailableListener(() => {
  if (frameReadInFlight) return;
  frameReadInFlight = true;
  void NosmaiCameraSdk.getLatestFrame()
    .then((frame) => {
      if (frame) analyzeProcessedPixels(frame);
    })
    .catch((error) => {
      // Stop/switch/teardown can cancel a pull that is encoding off-main.
      console.warn('Processed-frame pull failed', error);
    })
    .finally(() => {
      frameReadInFlight = false;
    });
});

await NosmaiCameraSdk.startFrameStream({ maxFramesPerSecond: 2 });
// Later:
await NosmaiCameraSdk.stopFrameStream();
frames.remove();
```

The rate is limited to `1..5` fps and each native frame to 8 MiB. Android emits
packed I420 or RGBA; iOS emits stride-preserving BGRA or NV12. Plane offsets and
row strides describe the decoded base64 bytes. The rate limit bounds retained
copies, events, and bridge pulls; while active, the native SDK's offscreen frame
output still runs at camera/render cadence. This is intentionally a CPU/
base64 integration surface, not a zero-copy texture, encoder, or WebRTC bridge.
See [processed raw-frame streaming](docs/frame-streaming.md) for lifecycle,
backpressure, and format details.

## Visual controls

Applying beauty and color controls requires `isBeautyEffectEnabled()`. Applying
manual background segmentation requires `isAdvancedFiltersEnabled()`. Scoped
queries, removals, and resets remain available if entitlement changes. Values
use the same scale on Android and iOS: beauty/makeup/eye intensities and RGB
components are `0..1`, RGB multipliers are `0..2`, HSB saturation/brightness
are `0..2`, and manual background blur is `0..1`.

```ts
await NosmaiCameraSdk.setSkinSmoothing(0.5);
await NosmaiCameraSdk.applyMakeup({
  type: 'lipstick',
  style: 'matte',
  color: { red: 0.82, green: 0.14, blue: 0.24 },
  intensity: 0.65,
});
await NosmaiCameraSdk.setReshape('faceSlim', 0.3);
await NosmaiCameraSdk.setEyeColor({ red: 0.1, green: 0.55, blue: 0.85 }, 0.5);
await NosmaiCameraSdk.setHsb({
  hue: -10,
  saturation: 1.1,
  brightness: 1.02,
});
await NosmaiCameraSdk.setBackground({
  mode: 'blur',
  blurStrength: 0.6,
});
```

Image and video backgrounds accept readable absolute `file://` URIs; bytes are
never copied across JSI. The portable formats are JPEG/PNG/WebP and MP4/M4V/MOV.
Use the scoped clear APIs to preserve unrelated state:
`clearMakeup`, `clearReshaping`, `removeEyeColor`, `resetColorAdjustments`, and
`clearBackground`. `clearBeauty` removes skin beauty, makeup, reshaping, and eye
color without clearing package filters, color adjustments, or a manual
background.

## Development

```sh
yarn install
yarn check
# Android/JVM everywhere; iOS/Foundation is included on macOS with Xcode:
yarn test:native:failure
```

The Android and iOS harnesses accept app-bound development keys through a secure
runtime field and do not preload or persist them. Native SDK binaries and
protected test effects must never be committed or packed into npm.

## Documentation and support

- [React Native platform guide](https://nosmai.com/docs/effects/platforms/react-native/)
- [React Native installation](https://nosmai.com/docs/effects/installation/react-native/)
- [React Native quickstart](https://nosmai.com/docs/effects/quickstart/react-native/)
- [License key](https://nosmai.com/docs/effects/license-key/)
- [Troubleshooting](https://nosmai.com/docs/effects/troubleshooting/)
- [GitHub issues](https://github.com/nosmai/nosmai_effects_sdk_react_native/issues)


## License

Commercial/proprietary. See [LICENSE](https://github.com/HashirYamin-dev/nosmai_effects_sdk_react_native_web/blob/main/LICENSE). For authorization, contact `admin@nosmai.com`.

## Web support

This repository also includes React Native Web support powered by `@nosmai/web-sdk`.

The existing Android and iOS implementations remain unchanged. On Web, the package automatically uses the Web-specific implementation:

```
Android -> Native Nosmai Android SDK
iOS     -> Native Nosmai iOS SDK
Web     -> @nosmai/web-sdk
```

### Web setup

1. Install the SDK:

```
yarn add @nosmai/react-native-effects-sdk
```

2. If your app does not already use React Native Web:

```
yarn add react-native-web react-dom
```

3. Copy the required Web assets:

```
npx nosmai-copy-web-assets public
```

This copies:

```
public/
  nosmai_bridge.js
  nosmai/
    engine/
    engine-baseline/
    models/
```

4. Import the React Native API:

```
import {
  NosmaiCameraSdk,
  NosmaiCameraView
} from '@nosmai/react-native-effects-sdk'
```

5. Initialize and start the camera:

```
await NosmaiCameraSdk.initialize(NOSMAI_LICENSE_KEY)

await NosmaiCameraSdk.configureCamera({
  position: 'front'
})

await NosmaiCameraSdk.startProcessing()
```

6. Render the preview:

```
<NosmaiCameraView
  style={{ flex: 1 }}
  cameraPosition="front"
  mirror
/>
```

7. Check feature availability:

```
const cloudEnabled =
  await NosmaiCameraSdk.isCloudFilterEnabled()

const beautyEnabled =
  await NosmaiCameraSdk.isBeautyEffectEnabled()
```

8. Apply Beauty:

```
if (beautyEnabled) {
  await NosmaiCameraSdk.setSkinSmoothing(0.5)
}
```

9. Load and apply a Cloud Effect:

```
if (cloudEnabled) {
  const page = await NosmaiCameraSdk.getCloudFilters({
    page: 1,
    limit: 20
  })

  const filter = page.filters[0]

  if (filter) {
    const downloaded =
      await NosmaiCameraSdk.downloadCloudFilter(filter)

    await NosmaiCameraSdk.applyEffect(downloaded.path)
  }
}
```

10. Clean up when leaving the camera screen:

```
await NosmaiCameraSdk.cleanup()
```

### Web notes

- Use `http://localhost` for development or HTTPS in production.
- Browser camera permission must be allowed.
- Beauty and Cloud Filters depend on the active Nosmai licence and authorized Web origin.
- Different ports are different browser origins, for example `localhost:4173` and `localhost:4174`.
- Do not import `@nosmai/web-sdk` directly in the application. Use `@nosmai/react-native-effects-sdk`.