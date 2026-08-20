import NativeNosmaiCameraSdk from './NativeNosmaiCameraSdk';
import { NosmaiErrorCode, NosmaiSdkError } from './errors';
import { normalizeFrameAvailable, normalizeRawFrame } from './frameNormalizers';
import {
  normalizeActiveEffects,
  normalizeCloudDownloadResult,
  normalizeCloudFilterPage,
  normalizeDownloadProgress,
  normalizeEffectParameterNumber,
  normalizeEffectParameters,
  normalizeFilter,
  normalizeGallerySaveResult,
  normalizeGameEvent,
  normalizeLicenseStatus,
  normalizeLocalFilters,
  normalizeNativeError,
  normalizePhotoResult,
  normalizeRecordingDuration,
  normalizeRecordingProgress,
  normalizeRecordingResult,
} from './normalizers';
import type {
  CameraConfiguration,
  NosmaiBackgroundConfiguration,
  NosmaiCameraApi,
  NosmaiCloudFilterQuery,
  NosmaiFilter,
  NosmaiFlashMode,
  NosmaiFrameStreamOptions,
  NosmaiHsbAdjustment,
  NosmaiMakeupConfiguration,
  NosmaiMakeupType,
  NosmaiTorchMode,
  NosmaiReshapeType,
  NosmaiRgbAdjustment,
  NosmaiRgbColor,
  NosmaiWhiteBalance,
  PackageType,
} from './types';

function requireNonEmpty(value: string, field: string): string {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (normalized.length === 0) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must not be empty.`
    );
  }
  return normalized;
}

function requireLocalFileUri(value: string, field: string): string {
  const uri = requireNonEmpty(value, field);
  const hasAbsoluteFilePath =
    (uri.startsWith('file:///') && uri.length > 'file:///'.length) ||
    (uri.startsWith('file://localhost/') &&
      uri.length > 'file://localhost/'.length);

  if (hasAbsoluteFilePath && !uri.includes('?') && !uri.includes('#')) {
    return uri;
  }

  throw new NosmaiSdkError(
    NosmaiErrorCode.invalidArgument,
    `${field} must be an absolute local file:// URI without a query or fragment.`
  );
}

function optionalMediaName(name: string | null | undefined): string | null {
  if (name === null || name === undefined) {
    return null;
  }

  const normalized = requireNonEmpty(name, 'name');
  if (
    normalized === '.' ||
    normalized === '..' ||
    normalized.includes('.') ||
    normalized.includes('/') ||
    normalized.includes('\\') ||
    normalized.includes('\0')
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'name must be a file-name stem, not a path.'
    );
  }
  return normalized;
}

async function callNative<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    throw NosmaiSdkError.fromUnknown(error);
  }
}

async function callNativeVoid(operation: () => Promise<void>): Promise<void> {
  await callNative(operation);
}

function configureCamera(configuration: CameraConfiguration): Promise<void> {
  return callNativeVoid(() =>
    NativeNosmaiCameraSdk.configureCamera(
      configuration.position,
      configuration.sessionPreset ?? null
    )
  );
}

function effectPath(effect: NosmaiFilter | string): string {
  return requireNonEmpty(
    typeof effect === 'string' ? effect : effect.path,
    'effect path'
  );
}

function applyEffect(packagePath: string): Promise<boolean> {
  return callNative(() =>
    NativeNosmaiCameraSdk.applyEffect(
      requireNonEmpty(packagePath, 'packagePath')
    )
  );
}

function optionalPackageType(
  packageType: PackageType | undefined
): PackageType | null {
  if (packageType === undefined) {
    return null;
  }

  switch (packageType) {
    case 'filter':
    case 'effect':
    case 'background':
    case 'beauty_effect':
    case 'game':
      return packageType;
    default:
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'packageType must be filter, effect, background, beauty_effect, or game.'
      );
  }
}

function requireFiniteRange(
  value: number,
  field: string,
  minimum: number,
  maximum: number
): number {
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < minimum ||
    value > maximum
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must be a finite number from ${minimum} through ${maximum}.`
    );
  }
  return value;
}

function requireIntegerRange(
  value: number,
  field: string,
  minimum: number,
  maximum: number
): number {
  const normalized = requireFiniteRange(value, field, minimum, maximum);
  if (!Number.isSafeInteger(normalized)) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must be a safe integer.`
    );
  }
  return normalized;
}

function requireCloudIdentifier(filter: NosmaiFilter | string): string {
  const candidate =
    typeof filter === 'string'
      ? filter
      : typeof filter === 'object' && filter !== null
        ? (filter.filterId ?? filter.id)
        : '';
  const identifier = requireNonEmpty(candidate, 'filterId');
  if (
    identifier === '.' ||
    identifier === '..' ||
    identifier.includes('..') ||
    identifier.includes('/') ||
    identifier.includes('\\') ||
    identifier.includes('\0')
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'filterId contains an unsafe path sequence.'
    );
  }
  return identifier;
}

function requireRgbColor(
  color: NosmaiRgbColor,
  field = 'color'
): Required<NosmaiRgbColor> {
  if (typeof color !== 'object' || color === null) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must contain red, green, and blue values.`
    );
  }
  return {
    red: requireFiniteRange(color.red, `${field}.red`, 0, 1),
    green: requireFiniteRange(color.green, `${field}.green`, 0, 1),
    blue: requireFiniteRange(color.blue, `${field}.blue`, 0, 1),
    alpha: requireFiniteRange(color.alpha ?? 1, `${field}.alpha`, 0, 1),
  };
}

const MAKEUP_STYLES: Readonly<Record<NosmaiMakeupType, readonly string[]>> = {
  lipstick: ['classic', 'matte', 'natural'],
  eyeshadow: ['smokey', 'shimmer', 'natural'],
  blusher: ['round', 'contour', 'natural'],
  eyelash: ['natural', 'dramatic', 'wispy'],
  eyebrow: ['natural', 'bold', 'arched'],
};

function requireMakeupType(value: NosmaiMakeupType): NosmaiMakeupType {
  if (!Object.prototype.hasOwnProperty.call(MAKEUP_STYLES, value)) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'makeupType must be lipstick, eyeshadow, blusher, eyelash, or eyebrow.'
    );
  }
  return value;
}

const RESHAPE_RANGES: Readonly<
  Record<NosmaiReshapeType, readonly [number, number]>
> = {
  lip: [-1, 1],
  faceSlim: [-1.2, 1.2],
  eye: [-1.3, 1.3],
  nose: [-0.5, 0.5],
  chin: [-0.8, 0.8],
  brow: [-1, 1],
  browThickness: [-1, 1],
  jaw: [-1, 1],
  mouthWidth: [-1, 1],
  forehead: [-1, 1],
};

const MAX_CLOUD_PAGE = 2_147_483_647;
const MAX_EFFECT_PARAMETER_NAME_LENGTH = 128;
const MAX_EFFECT_PARAMETER_STRING_LENGTH = 16_384;

function containsControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

function requireEffectParameterName(value: string): string {
  if (
    typeof value !== 'string' ||
    value.length > MAX_EFFECT_PARAMETER_NAME_LENGTH ||
    containsControlCharacter(value)
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `parameterName must be at most ${MAX_EFFECT_PARAMETER_NAME_LENGTH} characters and contain no control characters.`
    );
  }
  return requireNonEmpty(value, 'parameterName');
}

function requireEffectParameterString(value: string): string {
  if (
    typeof value !== 'string' ||
    value.length > MAX_EFFECT_PARAMETER_STRING_LENGTH ||
    containsControlCharacter(value)
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `value must be a string of at most ${MAX_EFFECT_PARAMETER_STRING_LENGTH} characters with no control characters.`
    );
  }
  return value;
}

function requireFlashMode(value: NosmaiFlashMode): NosmaiFlashMode {
  if (value !== 'off' && value !== 'on' && value !== 'auto') {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'flash mode must be off, on, or auto.'
    );
  }
  return value;
}

function requireTorchMode(value: NosmaiTorchMode): NosmaiTorchMode {
  if (value !== 'off' && value !== 'on') {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'torch mode must be off or on.'
    );
  }
  return value;
}

function requireReshapeType(value: NosmaiReshapeType): NosmaiReshapeType {
  if (!Object.prototype.hasOwnProperty.call(RESHAPE_RANGES, value)) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'reshapeType is not supported.'
    );
  }
  return value;
}

function requireCloudQuery(query?: NosmaiCloudFilterQuery): {
  packageType: PackageType | null;
  version: '2.0.0';
  page: number;
  limit: number;
  fetchAllPages: boolean;
} {
  if (
    query !== undefined &&
    (typeof query !== 'object' || query === null || Array.isArray(query))
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'query must be a cloud-filter query object.'
    );
  }
  const version = query?.version ?? '2.0.0';
  if (version !== '2.0.0') {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'version must be 2.0.0.'
    );
  }
  const page =
    query?.page === undefined
      ? 0
      : requireIntegerRange(query.page, 'page', 1, MAX_CLOUD_PAGE);
  const limit = requireIntegerRange(query?.limit ?? 20, 'limit', 1, 100);
  if (
    query?.fetchAllPages !== undefined &&
    typeof query.fetchAllPages !== 'boolean'
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      'fetchAllPages must be a boolean.'
    );
  }
  return {
    packageType: optionalPackageType(query?.packageType),
    version,
    page,
    limit,
    fetchAllPages: query?.fetchAllPages ?? page === 0,
  };
}

function setBeautyValue(control: string, value: number): Promise<void> {
  return callNativeVoid(() =>
    NativeNosmaiCameraSdk.setBeautyValue(control, value)
  );
}

function setColorAdjustment(
  control: string,
  value1: number,
  value2 = 0,
  value3 = 0
): Promise<void> {
  return callNativeVoid(() =>
    NativeNosmaiCameraSdk.setColorAdjustment(control, value1, value2, value3)
  );
}

export const NosmaiCameraSdk: NosmaiCameraApi = {
  initialize(licenseKey) {
    return callNative(() =>
      NativeNosmaiCameraSdk.initialize(
        requireNonEmpty(licenseKey, 'licenseKey')
      )
    );
  },

  configureCamera,

  startProcessing() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.startProcessing());
  },

  stopProcessing() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.stopProcessing());
  },

  pauseCamera() {
    return callNative(() => NativeNosmaiCameraSdk.pauseCamera());
  },

  resumeCamera() {
    return callNative(() => NativeNosmaiCameraSdk.resumeCamera());
  },

  switchCamera() {
    return callNative(() => NativeNosmaiCameraSdk.switchCamera());
  },

  hasFlash() {
    return callNative(() => NativeNosmaiCameraSdk.hasFlash());
  },

  hasTorch() {
    return callNative(() => NativeNosmaiCameraSdk.hasTorch());
  },

  async setFlashMode(mode) {
    return callNative(() =>
      NativeNosmaiCameraSdk.setFlashMode(requireFlashMode(mode))
    );
  },

  async setTorchMode(mode) {
    return callNative(() =>
      NativeNosmaiCameraSdk.setTorchMode(requireTorchMode(mode))
    );
  },

  async getFlashMode() {
    const mode = await callNative(() => NativeNosmaiCameraSdk.getFlashMode());
    return requireFlashMode(mode as NosmaiFlashMode);
  },

  async getTorchMode() {
    const mode = await callNative(() => NativeNosmaiCameraSdk.getTorchMode());
    return requireTorchMode(mode as NosmaiTorchMode);
  },

  cleanup() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.cleanup());
  },

  async startFrameStream(options: NosmaiFrameStreamOptions = {}) {
    if (
      typeof options !== 'object' ||
      options === null ||
      Array.isArray(options)
    ) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'options must be a frame-stream configuration object.'
      );
    }
    const maxFramesPerSecond = requireIntegerRange(
      options.maxFramesPerSecond ?? 2,
      'maxFramesPerSecond',
      1,
      5
    );
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.startFrameStream(maxFramesPerSecond)
    );
  },

  stopFrameStream() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.stopFrameStream());
  },

  async getLatestFrame() {
    const value = await callNative(() =>
      NativeNosmaiCameraSdk.getLatestFrame()
    );
    return value === null ? undefined : normalizeRawFrame(value);
  },

  isFrameStreamActive() {
    return callNative(() => NativeNosmaiCameraSdk.isFrameStreamActive());
  },

  async capturePhoto() {
    return normalizePhotoResult(
      await callNative(() => NativeNosmaiCameraSdk.capturePhoto())
    );
  },

  startRecording() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.startRecording());
  },

  async stopRecording() {
    return normalizeRecordingResult(
      await callNative(() => NativeNosmaiCameraSdk.stopRecording())
    );
  },

  isRecording() {
    return callNative(() => NativeNosmaiCameraSdk.isRecording());
  },

  async getCurrentRecordingDuration() {
    return normalizeRecordingDuration(
      await callNative(() =>
        NativeNosmaiCameraSdk.getCurrentRecordingDuration()
      )
    );
  },

  async saveImageToGallery(imageUri, name) {
    return normalizeGallerySaveResult(
      await callNative(() =>
        NativeNosmaiCameraSdk.saveImageToGallery(
          requireLocalFileUri(imageUri, 'imageUri'),
          optionalMediaName(name)
        )
      ),
      'photo'
    );
  },

  async saveVideoToGallery(videoUri, name) {
    return normalizeGallerySaveResult(
      await callNative(() =>
        NativeNosmaiCameraSdk.saveVideoToGallery(
          requireLocalFileUri(videoUri, 'videoUri'),
          optionalMediaName(name)
        )
      ),
      'video'
    );
  },

  applyEffect,

  applyFilter(packagePath) {
    return applyEffect(packagePath);
  },

  async getActiveEffects() {
    return normalizeActiveEffects(
      await callNative(() => NativeNosmaiCameraSdk.getActiveEffects())
    );
  },

  async getActiveFilterInfo() {
    return normalizeFilter(
      await callNative(() => NativeNosmaiCameraSdk.getActiveFilterInfo())
    );
  },

  async getActiveEffectInfo() {
    return normalizeFilter(
      await callNative(() => NativeNosmaiCameraSdk.getActiveEffectInfo())
    );
  },

  async getEffectParameters() {
    return normalizeEffectParameters(
      await callNative(() => NativeNosmaiCameraSdk.getEffectParameters())
    );
  },

  async getEffectParameterValue(parameterName) {
    const name = requireEffectParameterName(parameterName);
    return normalizeEffectParameterNumber(
      await callNative(() =>
        NativeNosmaiCameraSdk.getEffectParameterValue(name)
      )
    );
  },

  async setEffectParameter(parameterName, value) {
    const name = requireEffectParameterName(parameterName);
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'value must be a finite number.'
      );
    }
    return callNative(() =>
      NativeNosmaiCameraSdk.setEffectParameter(name, value)
    );
  },

  async setEffectParameterString(parameterName, value) {
    return callNative(() =>
      NativeNosmaiCameraSdk.setEffectParameterString(
        requireEffectParameterName(parameterName),
        requireEffectParameterString(value)
      )
    );
  },

  isGameReady() {
    return callNative(() => NativeNosmaiCameraSdk.isGameReady());
  },

  sendGameTap(normalizedX, normalizedY) {
    return callNative(() =>
      NativeNosmaiCameraSdk.sendGameTap(
        requireFiniteRange(normalizedX, 'normalizedX', 0, 1),
        requireFiniteRange(normalizedY, 'normalizedY', 0, 1)
      )
    );
  },

  sendGameInput(name, normalizedX, normalizedY, value = 1) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'value must be a finite number.'
      );
    }
    return callNative(() =>
      NativeNosmaiCameraSdk.sendGameInput(
        requireNonEmpty(name, 'name'),
        requireFiniteRange(normalizedX, 'normalizedX', 0, 1),
        requireFiniteRange(normalizedY, 'normalizedY', 0, 1),
        value
      )
    );
  },

  pauseGame() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.pauseGame());
  },

  resumeGame() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.resumeGame());
  },

  restartGame() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.restartGame());
  },

  async getLocalFilters(packageType) {
    const nativePackageType = optionalPackageType(packageType);
    return normalizeLocalFilters(
      await callNative(() =>
        NativeNosmaiCameraSdk.getLocalFilters(nativePackageType)
      )
    );
  },

  async getDebugFilters(packageType) {
    const nativePackageType = optionalPackageType(packageType);
    return normalizeLocalFilters(
      await callNative(() =>
        NativeNosmaiCameraSdk.getDebugFilters(nativePackageType)
      )
    );
  },

  isCloudFilterEnabled() {
    return callNative(() => NativeNosmaiCameraSdk.isCloudFilterEnabled());
  },

  async getCloudFilters(query) {
    const options = requireCloudQuery(query);
    return normalizeCloudFilterPage(
      await callNative(() =>
        NativeNosmaiCameraSdk.getCloudFilters(
          options.packageType,
          options.version,
          options.page,
          options.limit,
          options.fetchAllPages
        )
      )
    );
  },

  async downloadCloudFilter(filter) {
    const filterId = requireCloudIdentifier(filter);
    return normalizeCloudDownloadResult(
      await callNative(() =>
        NativeNosmaiCameraSdk.downloadCloudFilter(filterId)
      ),
      filterId
    );
  },

  removeCloudFilter(filter) {
    return callNative(() =>
      NativeNosmaiCameraSdk.removeCloudFilter(requireCloudIdentifier(filter))
    );
  },

  removeEffect(effect) {
    return callNative(() =>
      NativeNosmaiCameraSdk.removeEffect(effectPath(effect))
    );
  },

  clearFilter() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearFilter());
  },

  clearAREffect() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearAREffect());
  },

  clearAll() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearAll());
  },

  isBeautyEffectEnabled() {
    return callNative(() => NativeNosmaiCameraSdk.isBeautyEffectEnabled());
  },

  isAdvancedFiltersEnabled() {
    return callNative(() => NativeNosmaiCameraSdk.isAdvancedFiltersEnabled());
  },

  async setSkinSmoothing(level) {
    return setBeautyValue(
      'skinSmoothing',
      requireFiniteRange(level, 'level', 0, 1)
    );
  },

  async setSkinWhitening(level) {
    return setBeautyValue(
      'skinWhitening',
      requireFiniteRange(level, 'level', 0, 1)
    );
  },

  async setTeethWhitening(level) {
    return setBeautyValue(
      'teethWhitening',
      requireFiniteRange(level, 'level', 0, 1)
    );
  },

  async clearBeauty() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearBeauty());
  },

  async applyMakeup(configuration: NosmaiMakeupConfiguration) {
    if (typeof configuration !== 'object' || configuration === null) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'configuration must be a makeup configuration.'
      );
    }
    const makeupType = requireMakeupType(configuration.type);
    const supportedStyles = MAKEUP_STYLES[makeupType];
    if (!supportedStyles.includes(configuration.style)) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        `style is not supported for ${makeupType}.`
      );
    }
    const intensity = requireFiniteRange(
      configuration.intensity ?? 1,
      'intensity',
      0,
      1
    );
    const color =
      makeupType === 'eyelash'
        ? { red: 0, green: 0, blue: 0, alpha: 1 }
        : requireRgbColor(
            (
              configuration as Exclude<
                NosmaiMakeupConfiguration,
                { type: 'eyelash' }
              >
            ).color
          );
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.applyMakeup(
        makeupType,
        configuration.style,
        color.red,
        color.green,
        color.blue,
        intensity
      )
    );
  },

  async setMakeupIntensity(makeupType, intensity) {
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.setMakeupIntensity(
        requireMakeupType(makeupType),
        requireFiniteRange(intensity, 'intensity', 0, 1)
      )
    );
  },

  async removeMakeup(makeupType) {
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.removeMakeup(requireMakeupType(makeupType))
    );
  },

  isMakeupActive(makeupType) {
    return callNative(() =>
      NativeNosmaiCameraSdk.isMakeupActive(requireMakeupType(makeupType))
    );
  },

  async clearMakeup() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearMakeup());
  },

  async setReshape(type, value) {
    const reshapeType = requireReshapeType(type);
    const range = RESHAPE_RANGES[reshapeType];
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.setReshape(
        reshapeType,
        requireFiniteRange(value, 'value', range[0], range[1])
      )
    );
  },

  async clearReshaping() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearReshapes());
  },

  async setEyeColor(color, intensity = 0.5) {
    const normalized = requireRgbColor(color);
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.setEyeColor(
        normalized.red,
        normalized.green,
        normalized.blue,
        requireFiniteRange(intensity, 'intensity', 0, 1)
      )
    );
  },

  async setEyeColorIntensity(intensity) {
    return callNativeVoid(() =>
      NativeNosmaiCameraSdk.setEyeColorIntensity(
        requireFiniteRange(intensity, 'intensity', 0, 1)
      )
    );
  },

  async removeEyeColor() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.removeEyeColor());
  },

  isEyeColorActive() {
    return callNative(() => NativeNosmaiCameraSdk.isEyeColorActive());
  },

  async setBrightness(brightness) {
    return setColorAdjustment(
      'brightness',
      requireFiniteRange(brightness, 'brightness', -1, 1)
    );
  },

  async setContrast(contrast) {
    return setColorAdjustment(
      'contrast',
      requireFiniteRange(contrast, 'contrast', 0, 2)
    );
  },

  async setRgbAdjustment(adjustment: NosmaiRgbAdjustment) {
    if (typeof adjustment !== 'object' || adjustment === null) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'adjustment must contain red, green, and blue channel multipliers.'
      );
    }
    return setColorAdjustment(
      'rgb',
      requireFiniteRange(adjustment.red, 'adjustment.red', 0, 2),
      requireFiniteRange(adjustment.green, 'adjustment.green', 0, 2),
      requireFiniteRange(adjustment.blue, 'adjustment.blue', 0, 2)
    );
  },

  async setSharpening(level) {
    return setColorAdjustment(
      'sharpening',
      requireFiniteRange(level, 'level', 0, 1)
    );
  },

  async setGrayscale(enabled) {
    if (typeof enabled !== 'boolean') {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'enabled must be a boolean.'
      );
    }
    return setColorAdjustment('grayscale', enabled ? 1 : 0);
  },

  async setHue(degrees) {
    return setColorAdjustment(
      'hue',
      requireFiniteRange(degrees, 'degrees', 0, 360)
    );
  },

  async setWhiteBalance(configuration: NosmaiWhiteBalance) {
    if (typeof configuration !== 'object' || configuration === null) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'configuration must contain temperature and tint.'
      );
    }
    return setColorAdjustment(
      'whiteBalance',
      requireFiniteRange(
        configuration.temperature,
        'configuration.temperature',
        2000,
        8000
      ),
      requireFiniteRange(configuration.tint, 'configuration.tint', -1, 1)
    );
  },

  async setHsb(adjustment: NosmaiHsbAdjustment) {
    if (typeof adjustment !== 'object' || adjustment === null) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'adjustment must contain hue, saturation, and brightness.'
      );
    }
    return setColorAdjustment(
      'hsb',
      requireFiniteRange(adjustment.hue, 'adjustment.hue', -360, 360),
      requireFiniteRange(adjustment.saturation, 'adjustment.saturation', 0, 2),
      requireFiniteRange(adjustment.brightness, 'adjustment.brightness', 0, 2)
    );
  },

  async resetColorAdjustments() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.resetColorAdjustments());
  },

  async setBackground(configuration: NosmaiBackgroundConfiguration) {
    if (typeof configuration !== 'object' || configuration === null) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'configuration must be a background configuration.'
      );
    }
    switch (configuration.mode) {
      case 'blur':
        return callNativeVoid(() =>
          NativeNosmaiCameraSdk.setBackground(
            'blur',
            null,
            0,
            0,
            0,
            1,
            requireFiniteRange(configuration.blurStrength, 'blurStrength', 0, 1)
          )
        );
      case 'color': {
        const color = requireRgbColor(configuration.color);
        return callNativeVoid(() =>
          NativeNosmaiCameraSdk.setBackground(
            'color',
            null,
            color.red,
            color.green,
            color.blue,
            color.alpha,
            0
          )
        );
      }
      case 'image':
      case 'video':
        return callNativeVoid(() =>
          NativeNosmaiCameraSdk.setBackground(
            configuration.mode,
            requireLocalFileUri(configuration.uri, 'uri'),
            0,
            0,
            0,
            1,
            0
          )
        );
      default:
        throw new NosmaiSdkError(
          NosmaiErrorCode.invalidArgument,
          'background mode must be blur, color, image, or video.'
        );
    }
  },

  async clearBackground() {
    return callNativeVoid(() => NativeNosmaiCameraSdk.clearBackground());
  },

  addActiveEffectsChangedListener(listener) {
    return NativeNosmaiCameraSdk.onActiveEffectsChanged((value) => {
      listener(normalizeActiveEffects(value));
    });
  },

  addLicenseStatusChangedListener(listener) {
    return NativeNosmaiCameraSdk.onLicenseStatusChanged((value) => {
      listener(normalizeLicenseStatus(value));
    });
  },

  addErrorListener(listener) {
    return NativeNosmaiCameraSdk.onError((value) => {
      listener(normalizeNativeError(value));
    });
  },

  addRecordingProgressListener(listener) {
    return NativeNosmaiCameraSdk.onRecordingProgress((value) => {
      const progress = normalizeRecordingProgress(value);
      if (progress !== undefined) {
        listener(progress);
      }
    });
  },

  addDownloadProgressListener(listener) {
    return NativeNosmaiCameraSdk.onDownloadProgress((value) => {
      const progress = normalizeDownloadProgress(value);
      if (progress !== undefined) {
        listener(progress);
      }
    });
  },

  addGameEventListener(listener) {
    return NativeNosmaiCameraSdk.onGameEvent((value) => {
      const event = normalizeGameEvent(value);
      if (event !== undefined) {
        listener(event);
      }
    });
  },

  addFrameAvailableListener(listener) {
    return NativeNosmaiCameraSdk.onFrameAvailable((value) => {
      const metadata = normalizeFrameAvailable(value);
      if (metadata !== undefined) {
        listener(metadata);
      }
    });
  },
};
