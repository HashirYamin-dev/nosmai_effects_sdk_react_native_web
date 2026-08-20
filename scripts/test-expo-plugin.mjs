import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const packageRoot = fileURLToPath(new URL('..', import.meta.url));
const packageManifest = JSON.parse(
  readFileSync(join(packageRoot, 'package.json'), 'utf8')
);
const fixtureRoot = mkdtempSync(join(tmpdir(), 'nosmai-expo-plugin-'));
const fixtureNodeModules = join(fixtureRoot, 'node_modules');
const fakeAar = Buffer.concat([
  Buffer.from([0x50, 0x4b, 0x03, 0x04]),
  Buffer.from('Nosmai Expo config-plugin smoke-test AAR'),
]);
const fakeAarHash = createHash('sha256').update(fakeAar).digest('hex');

function packageDirectory(packageName) {
  return dirname(require.resolve(`${packageName}/package.json`));
}

function linkPackage(packageName, sourceDirectory) {
  const destination = join(fixtureNodeModules, ...packageName.split('/'));
  mkdirSync(dirname(destination), { recursive: true });
  symlinkSync(
    sourceDirectory,
    destination,
    process.platform === 'win32' ? 'junction' : 'dir'
  );
}

function runPrebuild(platform) {
  const expoCli = require.resolve('expo/bin/cli');
  const result = spawnSync(
    process.execPath,
    [expoCli, 'prebuild', '--no-install', '--platform', platform],
    {
      cwd: fixtureRoot,
      encoding: 'utf8',
      env: {
        ...process.env,
        CI: '1',
        EXPO_NO_DOTENV: '1',
        EXPO_NO_TELEMETRY: '1',
        NO_COLOR: '1',
        __UNSAFE_EXPO_HOME_DIRECTORY: join(fixtureRoot, '.expo-home'),
      },
    }
  );
  if (result.status !== 0) {
    process.stderr.write(result.stdout);
    process.stderr.write(result.stderr);
    throw new Error(`Expo ${platform} prebuild failed.`);
  }
}

function countMatches(source, pattern) {
  return source.match(pattern)?.length ?? 0;
}

function findInfoPlist(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const entryPath = join(directory, entry.name);
    if (entry.isDirectory()) {
      const nested = findInfoPlist(entryPath);
      if (nested !== undefined) return nested;
    } else if (entry.name === 'Info.plist') {
      return entryPath;
    }
  }
  return undefined;
}

try {
  mkdirSync(join(fixtureRoot, 'vendor'), { recursive: true });
  writeFileSync(join(fixtureRoot, 'vendor', 'nosmai-release.aar'), fakeAar);
  writeFileSync(
    join(fixtureRoot, 'package.json'),
    `${JSON.stringify(
      {
        name: 'nosmai-expo-plugin-fixture',
        version: '1.0.0',
        private: true,
        dependencies: {
          '@nosmai/react-native-camera-sdk': packageManifest.version,
          'expo': '~54.0.36',
          'react': '19.1.0',
          'react-native': '0.81.5',
        },
      },
      null,
      2
    )}\n`
  );
  writeFileSync(
    join(fixtureRoot, 'app.config.js'),
    `module.exports = {
  expo: {
    name: 'Nosmai Expo Plugin Fixture',
    slug: 'nosmai-expo-plugin-fixture',
    android: { package: 'com.nosmai.expopluginfixture' },
    ios: { bundleIdentifier: 'com.nosmai.expopluginfixture' },
    plugins: [[
      '@nosmai/react-native-camera-sdk',
      {
        androidAarPath: './vendor/nosmai-release.aar',
        androidAarSha256: '${fakeAarHash}',
        cameraPermission: 'Fixture camera permission',
        microphonePermission: 'Fixture microphone permission',
        photoLibraryAddPermission: 'Fixture photo permission'
      }
    ]]
  }
};
`
  );

  mkdirSync(fixtureNodeModules, { recursive: true });
  linkPackage('expo', packageDirectory('expo'));
  linkPackage('react', packageDirectory('react'));
  linkPackage('react-native', packageDirectory('react-native'));
  linkPackage('@nosmai/react-native-camera-sdk', packageRoot);

  runPrebuild('android');
  runPrebuild('android');

  const appGradle = readFileSync(
    join(fixtureRoot, 'android', 'app', 'build.gradle'),
    'utf8'
  );
  const gradleProperties = readFileSync(
    join(fixtureRoot, 'android', 'gradle.properties'),
    'utf8'
  );
  const copiedAar = readFileSync(
    join(fixtureRoot, 'android', 'app', 'libs', 'nosmai-release.aar')
  );

  if (!copiedAar.equals(fakeAar)) {
    throw new Error('Expo plugin did not copy the configured Android AAR.');
  }
  if (
    countMatches(
      appGradle,
      /implementation files\("libs\/nosmai-release\.aar"\)/g
    ) !== 1
  ) {
    throw new Error('Expo plugin must add exactly one Android AAR dependency.');
  }
  if (
    countMatches(gradleProperties, /^reactNativeArchitectures=arm64-v8a$/gm) !==
      1 ||
    countMatches(gradleProperties, /^newArchEnabled=true$/gm) !== 1
  ) {
    throw new Error(
      'Expo plugin must configure exactly one ARM64/New-Architecture property.'
    );
  }

  runPrebuild('ios');
  const infoPlistPath = findInfoPlist(join(fixtureRoot, 'ios'));
  if (infoPlistPath === undefined) {
    throw new Error('Expo prebuild did not generate an iOS Info.plist.');
  }
  const infoPlist = readFileSync(infoPlistPath, 'utf8');
  for (const expected of [
    'Fixture camera permission',
    'Fixture microphone permission',
    'Fixture photo permission',
  ]) {
    if (!infoPlist.includes(expected)) {
      throw new Error(`Expo plugin did not add: ${expected}`);
    }
  }

  const expectedPeers = {
    expo: '>=54.0.0 <55.0.0',
    react: '>=19.1.0 <20',
    'react-native': '>=0.81.5 <0.82.0',
  };
  if (
    JSON.stringify(packageManifest.peerDependencies) !==
    JSON.stringify(expectedPeers)
  ) {
    throw new Error(
      'Package peers must remain pinned to the qualified Expo 54 / React Native 0.81 compatibility line.'
    );
  }

  console.log(
    'Expo SDK 54 / React Native 0.81 New-Architecture config-plugin prebuild smoke test passed.'
  );
} finally {
  rmSync(fixtureRoot, { recursive: true, force: true });
}
