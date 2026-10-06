import { build } from 'esbuild';
import { cp, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const outDir = path.join(root, 'web_assets');
const require = createRequire(import.meta.url);
const webSdkPackage = require.resolve('@nosmai/web-sdk/package.json');
const webSdkRoot = path.dirname(webSdkPackage);

await mkdir(outDir, { recursive: true });

await build({
  entryPoints: [path.join(here, 'src', 'nosmai_bridge.js')],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: ['es2020'],
  outfile: path.join(outDir, 'nosmai_bridge.js'),
  sourcemap: false,
  minify: false,
  legalComments: 'none',
});

for (const folder of ['engine', 'engine-baseline', 'models']) {
  await cp(
    path.join(webSdkRoot, folder),
    path.join(outDir, 'nosmai', folder),
    { recursive: true, force: true },
  );
}

console.log(`[Nosmai] Web bridge/assets built at ${outDir}`);
