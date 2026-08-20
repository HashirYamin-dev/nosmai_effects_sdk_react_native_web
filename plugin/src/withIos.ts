import {
  type ConfigPlugin,
  type InfoPlist,
  withInfoPlist,
} from 'expo/config-plugins';

import type { NosmaiExpoPluginProps } from './types';

export const DEFAULT_CAMERA_PERMISSION =
  'Allow $(PRODUCT_NAME) to use the camera for Nosmai effects.';
export const DEFAULT_MICROPHONE_PERMISSION =
  'Allow $(PRODUCT_NAME) to use the microphone when recording video.';
export const DEFAULT_PHOTO_LIBRARY_ADD_PERMISSION =
  'Allow $(PRODUCT_NAME) to save captured photos and videos.';

function validatePermissionText(
  value: unknown,
  optionName: string
): string | undefined {
  if (value === undefined) return undefined;

  if (typeof value !== 'string') {
    throw new Error(`[Nosmai] ${optionName} must be a non-empty string.`);
  }

  const normalized = value.trim();
  if (normalized.length === 0) {
    throw new Error(`[Nosmai] ${optionName} must be a non-empty string.`);
  }
  return normalized;
}

function setPermissionDescription(
  infoPlist: InfoPlist,
  key: string,
  optionName: string,
  explicitValue: string | undefined,
  fallbackValue: string
): void {
  const normalized = validatePermissionText(explicitValue, optionName);
  if (normalized !== undefined) {
    infoPlist[key] = normalized;
    return;
  }

  const existing = infoPlist[key];
  if (typeof existing !== 'string' || existing.trim().length === 0) {
    infoPlist[key] = fallbackValue;
  }
}

export function applyNosmaiInfoPlist(
  infoPlist: InfoPlist,
  props: NosmaiExpoPluginProps
): InfoPlist {
  setPermissionDescription(
    infoPlist,
    'NSCameraUsageDescription',
    'cameraPermission',
    props.cameraPermission,
    DEFAULT_CAMERA_PERMISSION
  );
  setPermissionDescription(
    infoPlist,
    'NSMicrophoneUsageDescription',
    'microphonePermission',
    props.microphonePermission,
    DEFAULT_MICROPHONE_PERMISSION
  );
  setPermissionDescription(
    infoPlist,
    'NSPhotoLibraryAddUsageDescription',
    'photoLibraryAddPermission',
    props.photoLibraryAddPermission,
    DEFAULT_PHOTO_LIBRARY_ADD_PERMISSION
  );
  return infoPlist;
}

export const withNosmaiIos: ConfigPlugin<NosmaiExpoPluginProps> = (
  config,
  props
) =>
  withInfoPlist(config, (modConfig) => {
    modConfig.modResults = applyNosmaiInfoPlist(modConfig.modResults, props);
    return modConfig;
  });
