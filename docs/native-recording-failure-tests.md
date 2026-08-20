# Native recording failure tests

Run the deterministic native fault-injection policies with:

```sh
yarn test:native:failure
```

The command compiles the Android policy and test runner with the host JDK. On
macOS it also compiles and runs the iOS Foundation policy with Xcode's compiler.
Neither runner links or executes the proprietary Nosmai SDK.

The production recorders call the same policy helpers exercised by these tests.
Coverage is grouped around the recording failure boundaries:

- current, stale, wrong-state, and already-settled start/stop/finalization
  watchdog generations;
- Android `ENOSPC` markers/errno and iOS `NSFileWriteOutOfSpaceError` plus nested
  POSIX `ENOSPC`, all mapped to `E_RECORDING_STORAGE_FULL`;
- successful audio/video output, audio-mux failure with playable silent-video
  fallback and `E_RECORDING_AUDIO_MUX` warning, and failure when no playable
  video exists;
- cleanup admission, in-flight teardown settlement, stale lifecycle callbacks,
  and duplicate exact-once settlement.

These are deterministic policy and race-boundary tests, not media-framework
integration tests. Physical qualification is still required to force the real
SDK to lose a callback, exhaust device storage, fail a device codec/muxer, and
interrupt an active camera/recording across the supported OS/device matrix.
