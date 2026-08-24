import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import {
  copyFile,
  mkdir,
  open,
  realpath,
  rename,
  rm,
  stat,
} from 'node:fs/promises';
import path from 'node:path';

import {
  AndroidConfig,
  type ConfigPlugin,
  withAppBuildGradle,
  withDangerousMod,
  withGradleProperties,
} from 'expo/config-plugins';

import type { NosmaiExpoPluginProps } from './types';

export const DEFAULT_ANDROID_AAR_PATH = 'vendor/nosmai-release.aar';
export const ANDROID_AAR_NAME = 'nosmai-release.aar';
export const ANDROID_ARCHITECTURES_PROPERTY = 'reactNativeArchitectures';
export const ANDROID_ARCHITECTURES_VALUE = 'armeabi-v7a,arm64-v8a';

const GRADLE_BLOCK_BEGIN =
  '// @generated begin @nosmai/react-native-effects-sdk - expo prebuild (DO NOT MODIFY)';
const GRADLE_BLOCK_END = '// @generated end @nosmai/react-native-effects-sdk';
const MANUAL_AAR_DEPENDENCY =
  /implementation\s*(?:\(\s*)?files\s*\(\s*["']libs\/nosmai-release\.aar["']\s*\)\s*\)?/;

type GradlePropertiesItem = AndroidConfig.Properties.PropertiesItem;

function isPathInside(parentPath: string, candidatePath: string): boolean {
  const relativePath = path.relative(parentPath, candidatePath);
  return (
    relativePath === '' ||
    (!relativePath.startsWith(`..${path.sep}`) &&
      relativePath !== '..' &&
      !path.isAbsolute(relativePath))
  );
}

async function canonicalPath(filePath: string): Promise<string> {
  try {
    return await realpath(filePath);
  } catch {
    return path.resolve(filePath);
  }
}

function generatedDependencyBlock(language: 'groovy' | 'kt'): string {
  const dependency =
    language === 'kt'
      ? '    implementation(files("libs/nosmai-release.aar"))'
      : '    implementation files("libs/nosmai-release.aar")';

  return [
    GRADLE_BLOCK_BEGIN,
    'dependencies {',
    dependency,
    '}',
    GRADLE_BLOCK_END,
  ].join('\n');
}

export function addNosmaiAarDependency(
  source: string,
  language: string
): string {
  if (language !== 'groovy' && language !== 'kt') {
    throw new Error(
      `[Nosmai] Unsupported Android app Gradle language: ${language}.`
    );
  }

  const beginIndex = source.indexOf(GRADLE_BLOCK_BEGIN);
  const endIndex = source.indexOf(GRADLE_BLOCK_END);
  const hasBegin = beginIndex >= 0;
  const hasEnd = endIndex >= 0;

  if (hasBegin !== hasEnd || (hasBegin && endIndex < beginIndex)) {
    throw new Error(
      '[Nosmai] The generated Android Gradle block is incomplete. Remove the damaged Nosmai generated block and run Expo prebuild again.'
    );
  }

  const block = generatedDependencyBlock(language);
  if (hasBegin) {
    const afterEnd = endIndex + GRADLE_BLOCK_END.length;
    return `${source.slice(0, beginIndex)}${block}${source.slice(afterEnd)}`;
  }

  if (MANUAL_AAR_DEPENDENCY.test(source)) {
    return source;
  }

  return `${source.trimEnd()}\n\n${block}\n`;
}

export function upsertGradleProperty(
  properties: GradlePropertiesItem[],
  key: string,
  value: string
): GradlePropertiesItem[] {
  let found = false;
  const result = properties.filter((item) => {
    if (item.type !== 'property' || item.key !== key) return true;
    if (found) return false;
    found = true;
    item.value = value;
    return true;
  });

  if (!found) {
    result.push({ type: 'property', key, value });
  }
  return result;
}

function normalizeSha256(value: unknown): string | undefined {
  if (value === undefined) return undefined;
  if (typeof value !== 'string') {
    throw new Error(
      '[Nosmai] androidAarSha256 must contain exactly 64 hexadecimal characters.'
    );
  }
  const normalized = value.trim().toLowerCase();
  if (!/^[a-f0-9]{64}$/.test(normalized)) {
    throw new Error(
      '[Nosmai] androidAarSha256 must contain exactly 64 hexadecimal characters.'
    );
  }
  return normalized;
}

export async function sha256File(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const hash = createHash('sha256');
    const stream = createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

async function assertAarArchive(filePath: string): Promise<void> {
  const handle = await open(filePath, 'r');
  try {
    const signature = Buffer.alloc(4);
    const { bytesRead } = await handle.read(signature, 0, signature.length, 0);
    const isZip =
      bytesRead === signature.length &&
      signature[0] === 0x50 &&
      signature[1] === 0x4b &&
      ((signature[2] === 0x03 && signature[3] === 0x04) ||
        (signature[2] === 0x05 && signature[3] === 0x06) ||
        (signature[2] === 0x07 && signature[3] === 0x08));
    if (!isZip) {
      throw new Error(
        '[Nosmai] androidAarPath is not a valid AAR/ZIP archive.'
      );
    }
  } finally {
    await handle.close();
  }
}

export interface InstallAndroidAarOptions {
  androidAarPath?: unknown;
  androidAarSha256?: unknown;
  projectRoot: string;
  platformProjectRoot: string;
}

export async function installAndroidAar({
  androidAarPath,
  androidAarSha256,
  projectRoot,
  platformProjectRoot,
}: InstallAndroidAarOptions): Promise<string> {
  if (androidAarPath !== undefined && typeof androidAarPath !== 'string') {
    throw new Error('[Nosmai] androidAarPath must be a non-empty string.');
  }
  const configuredPath =
    androidAarPath?.trim() ||
    process.env.NOSMAI_ANDROID_AAR_PATH?.trim() ||
    DEFAULT_ANDROID_AAR_PATH;
  const sourcePath = path.isAbsolute(configuredPath)
    ? configuredPath
    : path.resolve(projectRoot, configuredPath);
  const destinationPath = path.join(
    platformProjectRoot,
    'app',
    'libs',
    ANDROID_AAR_NAME
  );

  if (
    isPathInside(path.resolve(platformProjectRoot), path.resolve(sourcePath))
  ) {
    throw new Error(
      '[Nosmai] Keep the source AAR outside the generated android directory so expo prebuild --clean cannot delete it.'
    );
  }

  if (path.extname(sourcePath).toLowerCase() !== '.aar') {
    throw new Error(
      '[Nosmai] androidAarPath must point to an authorized .aar file.'
    );
  }

  let sourceStat;
  try {
    sourceStat = await stat(sourcePath);
  } catch {
    throw new Error(
      '[Nosmai] Android AAR was not found. Set androidAarPath or NOSMAI_ANDROID_AAR_PATH to the authorized nosmai-release.aar file.'
    );
  }
  if (!sourceStat.isFile()) {
    throw new Error(
      '[Nosmai] androidAarPath must point to an authorized .aar file.'
    );
  }

  if (
    isPathInside(
      await canonicalPath(platformProjectRoot),
      await realpath(sourcePath)
    )
  ) {
    throw new Error(
      '[Nosmai] Keep the source AAR outside the generated android directory so expo prebuild --clean cannot delete it.'
    );
  }

  await assertAarArchive(sourcePath);

  const expectedSha256 = normalizeSha256(
    androidAarSha256 ?? process.env.NOSMAI_ANDROID_AAR_SHA256
  );
  const sourceSha256 = await sha256File(sourcePath);
  if (expectedSha256 !== undefined && sourceSha256 !== expectedSha256) {
    throw new Error(
      '[Nosmai] Android AAR SHA-256 verification failed. Refusing to copy the artifact.'
    );
  }

  await mkdir(path.dirname(destinationPath), { recursive: true });
  const temporaryPath = `${destinationPath}.${randomUUID()}.tmp`;
  try {
    await copyFile(sourcePath, temporaryPath);
    const copiedStat = await stat(temporaryPath);
    const copiedSha256 = await sha256File(temporaryPath);
    if (copiedStat.size !== sourceStat.size || copiedSha256 !== sourceSha256) {
      throw new Error(
        '[Nosmai] Android AAR copy verification failed. Refusing to install the artifact.'
      );
    }
    try {
      await rename(temporaryPath, destinationPath);
    } catch (error) {
      const code = (error as NodeJS.ErrnoException).code;
      if (code !== 'EEXIST' && code !== 'EPERM') throw error;
      await rm(destinationPath, { force: true });
      await rename(temporaryPath, destinationPath);
    }
  } finally {
    await rm(temporaryPath, { force: true });
  }
  return destinationPath;
}

export const withNosmaiAndroid: ConfigPlugin<NosmaiExpoPluginProps> = (
  config,
  props
) => {
  config = withGradleProperties(config, (modConfig) => {
    modConfig.modResults = upsertGradleProperty(
      modConfig.modResults,
      ANDROID_ARCHITECTURES_PROPERTY,
      ANDROID_ARCHITECTURES_VALUE
    );
    modConfig.modResults = upsertGradleProperty(
      modConfig.modResults,
      'newArchEnabled',
      'true'
    );
    return modConfig;
  });

  config = withAppBuildGradle(config, (modConfig) => {
    modConfig.modResults.contents = addNosmaiAarDependency(
      modConfig.modResults.contents,
      modConfig.modResults.language
    );
    return modConfig;
  });

  return withDangerousMod(config, [
    'android',
    async (modConfig) => {
      if (modConfig.modRequest.introspect) return modConfig;
      await installAndroidAar({
        androidAarPath: props.androidAarPath,
        androidAarSha256: props.androidAarSha256,
        projectRoot: modConfig.modRequest.projectRoot,
        platformProjectRoot: modConfig.modRequest.platformProjectRoot,
      });
      return modConfig;
    },
  ]);
};
