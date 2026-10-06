# React Native Web support

The React Native package keeps the Android/iOS native implementation unchanged
and uses the Nosmai Web SDK in browsers.

## 1. Copy the browser runtime assets

From the consuming application:

```bash
npx nosmai-copy-web-assets public
```

This creates:

```text
public/
  nosmai_bridge.js
  nosmai/
    engine/
    engine-baseline/
    models/
```

The files must be served from the application root so these URLs work:

- `/nosmai_bridge.js`
- `/nosmai/engine/...`
- `/nosmai/engine-baseline/...`
- `/nosmai/models/...`

Run the copy command again whenever the Nosmai package/Web SDK version changes.

## 2. Use the same React Native API

```tsx
import {
  NosmaiCameraSdk,
  NosmaiCameraView,
} from '@nosmai/react-native-effects-sdk';

await NosmaiCameraSdk.initialize(NOSMAI_LICENSE_KEY);
await NosmaiCameraSdk.configureCamera({ position: 'front' });
await NosmaiCameraSdk.startProcessing();

<NosmaiCameraView style={{ flex: 1 }} />;
```

On Android/iOS the package continues to use the existing native TurboModule/Fabric
implementation. On Web, the package loads `/nosmai_bridge.js`, attaches the Web
SDK to the canvas owned by `NosmaiCameraView`, and serves engine/WASM/model
assets from `/nosmai`.

## Web limitations

The public Web SDK `0.1.0-alpha.2` does not expose exact equivalents for every
mobile-native API. Raw CPU frame streaming and platform gallery-save operations
therefore remain unavailable on Web and reject with `E_NOT_IMPLEMENTED`.

Browser camera access requires HTTPS or localhost and camera permission.
