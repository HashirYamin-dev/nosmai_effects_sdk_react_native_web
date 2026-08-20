import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

import {
  addNosmaiAarDependency,
  ANDROID_ARCHITECTURES_PROPERTY,
  ANDROID_ARCHITECTURES_VALUE,
  installAndroidAar,
  upsertGradleProperty,
} from '../withAndroid';

describe('Nosmai Expo Android configuration', () => {
  it('adds one Groovy dependency block and remains idempotent', () => {
    const source = 'plugins {\n}\n\nandroid {\n}\n';
    const first = addNosmaiAarDependency(source, 'groovy');
    const second = addNosmaiAarDependency(first, 'groovy');

    expect(second).toBe(first);
    expect(
      second.match(/implementation files\("libs\/nosmai-release\.aar"\)/g)
    ).toHaveLength(1);
  });

  it('supports Kotlin Gradle and preserves a manual dependency', () => {
    const kotlin = addNosmaiAarDependency('plugins {\n}\n', 'kt');
    expect(kotlin).toContain(
      'implementation(files("libs/nosmai-release.aar"))'
    );

    const manual = [
      'dependencies {',
      '    implementation files("libs/nosmai-release.aar")',
      '}',
      '',
    ].join('\n');
    expect(addNosmaiAarDependency(manual, 'groovy')).toBe(manual);
  });

  it('sets one authoritative ARM64 property', () => {
    const properties = upsertGradleProperty(
      [
        { type: 'comment', value: 'Architectures' },
        {
          type: 'property',
          key: ANDROID_ARCHITECTURES_PROPERTY,
          value: 'x86_64,arm64-v8a',
        },
        {
          type: 'property',
          key: ANDROID_ARCHITECTURES_PROPERTY,
          value: 'x86',
        },
      ],
      ANDROID_ARCHITECTURES_PROPERTY,
      ANDROID_ARCHITECTURES_VALUE
    );

    expect(
      properties.filter(
        (item) =>
          item.type === 'property' &&
          item.key === ANDROID_ARCHITECTURES_PROPERTY
      )
    ).toEqual([
      {
        type: 'property',
        key: ANDROID_ARCHITECTURES_PROPERTY,
        value: ANDROID_ARCHITECTURES_VALUE,
      },
    ]);
  });

  it('copies and verifies an authorized AAR', async () => {
    const projectRoot = await mkdtemp(
      path.join(tmpdir(), 'nosmai-expo-plugin-')
    );

    try {
      const sourcePath = path.join(projectRoot, 'vendor', 'sdk.aar');
      const sourceBytes = Buffer.concat([
        Buffer.from([0x50, 0x4b, 0x03, 0x04]),
        Buffer.from('fake authorized AAR for plugin test'),
      ]);
      await mkdir(path.dirname(sourcePath), { recursive: true });
      await writeFile(sourcePath, sourceBytes);
      const expectedHash = createHash('sha256')
        .update(sourceBytes)
        .digest('hex');

      const destination = await installAndroidAar({
        androidAarPath: 'vendor/sdk.aar',
        androidAarSha256: expectedHash.toUpperCase(),
        projectRoot,
        platformProjectRoot: path.join(projectRoot, 'android'),
      });

      expect(await readFile(destination)).toEqual(sourceBytes);
    } finally {
      await rm(projectRoot, { recursive: true, force: true });
    }
  });

  it('fails clearly for a missing or mismatched AAR', async () => {
    const projectRoot = await mkdtemp(
      path.join(tmpdir(), 'nosmai-expo-plugin-')
    );

    try {
      await expect(
        installAndroidAar({
          androidAarPath: 'vendor/missing.aar',
          projectRoot,
          platformProjectRoot: path.join(projectRoot, 'android'),
        })
      ).rejects.toThrow('Android AAR was not found');

      const sourcePath = path.join(projectRoot, 'sdk.aar');
      await writeFile(
        sourcePath,
        Buffer.concat([
          Buffer.from([0x50, 0x4b, 0x03, 0x04]),
          Buffer.from('fake'),
        ])
      );
      await expect(
        installAndroidAar({
          androidAarPath: sourcePath,
          androidAarSha256: '0'.repeat(64),
          projectRoot,
          platformProjectRoot: path.join(projectRoot, 'android'),
        })
      ).rejects.toThrow('SHA-256 verification failed');
    } finally {
      await rm(projectRoot, { recursive: true, force: true });
    }
  });

  it('rejects an AAR source inside the generated Android directory', async () => {
    const projectRoot = await mkdtemp(
      path.join(tmpdir(), 'nosmai-expo-plugin-')
    );

    try {
      await expect(
        installAndroidAar({
          androidAarPath: 'android/vendor/nosmai-release.aar',
          projectRoot,
          platformProjectRoot: path.join(projectRoot, 'android'),
        })
      ).rejects.toThrow('outside the generated android directory');
    } finally {
      await rm(projectRoot, { recursive: true, force: true });
    }
  });
});
