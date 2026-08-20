# Contributing

This is proprietary Nosmai software. Contributions require prior authorization
and are governed by [LICENSE](LICENSE).

## Development workflow

The root contains the library and `example/` contains a React Native Community
CLI app linked to the local package.

```sh
yarn install
yarn check
```

Useful commands:

- `yarn example start` — Metro.
- `yarn example android` — Android example.
- `yarn example ios` — iOS example.
- `yarn lint` — ESLint/Prettier validation.
- `yarn typecheck` — TypeScript validation.
- `yarn test` — unit tests.
- `yarn test:native:failure` — SDK-independent Android recording failure
  policies; on macOS the same command also runs the iOS Foundation policies.
- `yarn prepare` — build distributable JavaScript and declarations.
- `yarn package:check` — fail if the npm tarball contains an unexpected or
  prohibited native/credential artifact.

Native builds require an authorized Nosmai SDK artifact. Put the Android AAR at
`example/android/app/libs/nosmai-release.aar`; iOS resolves
`NosmaiCameraSDK ~> 3.0.3` through CocoaPods. Never commit either binary, a
license key, protected test effect, model, symbol file, or internal SDK log.

The recording failure-policy runners need no device, key, or proprietary SDK
binary. Their scope and physical-test boundary are documented in
[native recording failure tests](docs/native-recording-failure-tests.md).

Use runtime input or ignored local configuration for development license keys.
The example harness must not preload or persist a key. Before a pull request,
inspect `git status`, run `yarn check`, generate Codegen artifacts for both
platforms, and confirm the npm tarball contains no proprietary artifact or
credential.

Changes to public API names, lifecycle ownership, native dependency versions,
pipeline clear semantics, or package distribution require maintainer review.
