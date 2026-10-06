#!/usr/bin/env node
import { cp, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const packageRoot = path.resolve(here, '..');
const source = path.join(packageRoot, 'web_assets');

const targetArg = process.argv[2] ?? 'public';
const targetRoot = path.resolve(process.cwd(), targetArg);

await mkdir(targetRoot, { recursive: true });

await cp(
  path.join(source, 'nosmai_bridge.js'),
  path.join(targetRoot, 'nosmai_bridge.js'),
  { force: true },
);

await cp(
  path.join(source, 'nosmai'),
  path.join(targetRoot, 'nosmai'),
  { recursive: true, force: true },
);

console.log(`[Nosmai] Web assets copied to ${targetRoot}`);
console.log(`[Nosmai] Bridge: ${path.join(targetRoot, 'nosmai_bridge.js')}`);
console.log(`[Nosmai] Runtime: ${path.join(targetRoot, 'nosmai')}`);
