import { Nosmai } from '@nosmai/web-sdk';

const root = typeof window !== 'undefined' ? window : globalThis;

root.NosmaiReactNativeWeb = Object.freeze({
  Nosmai,
  version: '0.1.0-alpha.2',
});
