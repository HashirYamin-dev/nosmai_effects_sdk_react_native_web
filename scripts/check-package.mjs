import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const packageRoot = fileURLToPath(new URL('..', import.meta.url));
const require = createRequire(import.meta.url);
const npmCache = mkdtempSync(join(tmpdir(), 'nosmai-npm-cache-'));
const result = spawnSync(
  'npm',
  ['pack', '--dry-run', '--ignore-scripts', '--json'],
  {
    cwd: packageRoot,
    encoding: 'utf8',
    env: {
      ...process.env,
      NO_COLOR: '1',
      npm_config_cache: npmCache,
    },
  }
);
rmSync(npmCache, { recursive: true, force: true });

if (result.status !== 0) {
  process.stderr.write(result.stderr || result.stdout);
  process.exit(result.status ?? 1);
}

let report;
try {
  report = JSON.parse(result.stdout);
} catch {
  const lastBracket = result.stdout.lastIndexOf(']');
  const starts = [...result.stdout.matchAll(/(?:^|\n)(\[)/g)].map(
    (match) => (match.index ?? 0) + match[0].length - 1
  );
  for (const start of starts.reverse()) {
    try {
      const candidate = JSON.parse(result.stdout.slice(start, lastBracket + 1));
      if (Array.isArray(candidate) && Array.isArray(candidate[0]?.files)) {
        report = candidate;
        break;
      }
    } catch {
      // Bob writes lines such as "[module]" before npm's final JSON report.
    }
  }
  if (!report) {
    throw new Error('npm pack did not return a JSON report.');
  }
}

const files = report[0]?.files?.map(({ path }) => path) ?? [];
if (files.length === 0) {
  throw new Error('npm pack returned an empty file list.');
}

const expoPluginPath =
  require.resolve('@nosmai/react-native-effects-sdk/app.plugin');
const expoPlugin = require(expoPluginPath);
if (typeof expoPlugin !== 'function') {
  throw new Error('Expo app.plugin export must resolve to a config function.');
}

const allowedRoots = ['android/', 'docs/', 'ios/', 'lib/', 'src/'];
const allowedFiles = new Set([
  'CHANGELOG.md',
  'CONTRIBUTING.md',
  'LICENSE',
  'README.md',
  'NosmaiReactNativeCameraSdk.podspec',
  'app.plugin.js',
  'package.json',
]);
const requiredFiles = [
  'NosmaiReactNativeCameraSdk.podspec',
  'app.plugin.js',
  'android/build.gradle',
  'android/consumer-rules.pro',
  'android/src/main/AndroidManifest.xml',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidCapture.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidController.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiEffectParameterAdapter.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidFrameStream.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidGallery.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidMediaTypes.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiAndroidRecorder.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiCameraView.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiCameraSdkModule.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiMediaMuxer.kt',
  'android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiRecordingFailurePolicy.java',
  'ios/NosmaiCameraSdk.mm',
  'ios/NosmaiIOSController.h',
  'ios/NosmaiIOSController.mm',
  'ios/NosmaiIOSFrameStream.h',
  'ios/NosmaiIOSFrameStream.mm',
  'ios/NosmaiRecordingFailurePolicy.h',
  'ios/NosmaiRecordingFailurePolicy.mm',
  'ios/Resources/PrivacyInfo.xcprivacy',
  'lib/module/index.js',
  'lib/plugin/index.d.ts',
  'lib/plugin/index.js',
  'lib/plugin/types.d.ts',
  'lib/plugin/withAndroid.js',
  'lib/plugin/withIos.js',
  'lib/typescript/src/index.d.ts',
  'package.json',
];
const forbidden = [
  /(^|\/)\.env(?:\.|$)/i,
  /(^|\/)(?:license[-_]?key|secret|credentials?)(?:\/|\.|-|_|$)/i,
  /\.(?:aar|a|der|dylib|jar|jks|key|keystore|mobileprovision|nosmai|onnx|p12|pem|so|task|tflite)$/i,
  /\.framework(?:\/|$)/i,
  /\.xcframework(?:\/|$)/i,
  /(^|\/)(?:node_modules|Pods)(?:\/|$)/,
  /(^|\/)(?:build|\.cxx|DerivedData)(?:\/|$)/,
  /(^|\/)(?:android|ios)\/generated(?:\/|$)/,
];
const forbiddenContents = [
  {
    name: 'complete Nosmai license key',
    pattern: /\bNOSMAI-[A-Za-z0-9_-]{32,}\b/,
  },
  {
    name: 'private key material',
    pattern: /-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----/,
  },
  {
    name: 'invalid never-returning camera-view declaration',
    pattern: /declare function NosmaiCameraView[^;]*:\s*never\s*;/,
  },
  {
    name: 'React Native 0.86-only event dispatcher call',
    pattern: /UIManagerHelper\.getEventDispatcher\(reactContext\)/,
  },
];

const unexpected = files.filter(
  (path) =>
    !allowedFiles.has(path) &&
    !allowedRoots.some((root) => path.startsWith(root))
);
const prohibited = files.filter((path) =>
  forbidden.some((pattern) => pattern.test(path))
);
const missing = requiredFiles.filter((path) => !files.includes(path));
const prohibitedContents = [];
for (const path of files) {
  const absolutePath = join(packageRoot, path);
  let content;
  try {
    if (!statSync(absolutePath).isFile()) continue;
    content = readFileSync(absolutePath, 'utf8');
  } catch {
    continue;
  }
  for (const forbiddenContent of forbiddenContents) {
    if (forbiddenContent.pattern.test(content)) {
      prohibitedContents.push({
        path,
        reason: forbiddenContent.name,
      });
    }
  }
}

if (
  unexpected.length > 0 ||
  prohibited.length > 0 ||
  missing.length > 0 ||
  prohibitedContents.length > 0
) {
  if (unexpected.length > 0) {
    console.error('Unexpected npm package paths:', unexpected);
  }
  if (prohibited.length > 0) {
    console.error('Forbidden npm package artifacts:', prohibited);
  }
  if (missing.length > 0) {
    console.error('Required npm package files are missing:', missing);
  }
  if (prohibitedContents.length > 0) {
    console.error(
      'Forbidden npm package contents:',
      prohibitedContents.map(({ path, reason }) => `${path} (${reason})`)
    );
  }
  process.exit(1);
}

console.log(`npm package audit passed (${files.length} files).`);
