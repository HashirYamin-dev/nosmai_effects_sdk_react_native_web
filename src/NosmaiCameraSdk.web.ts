import { NosmaiErrorCode, NosmaiSdkError } from './errors';
import { webRuntime, normalizeWebEffect, notImplemented } from './web/NosmaiWebRuntime';
import type {
  NosmaiCameraApi,
  NosmaiEffectParameter,
  NosmaiMakeupConfiguration,
  NosmaiMakeupType,
  NosmaiReshapeType,
  NosmaiRgbColor,
  PackageType,
} from './types';

function requireFinite(
  value: number,
  field: string,
  min: number,
  max: number
): number {
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < min ||
    value > max
  ) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must be between ${min} and ${max}.`
    );
  }
  return value;
}

function requireName(value: string, field: string): string {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!normalized) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must not be empty.`
    );
  }
  return normalized;
}

const makeupLayer: Record<NosmaiMakeupType, number> = {
  lipstick: 0,
  eyeshadow: 1,
  blusher: 2,
  eyelash: 3,
  eyebrow: 4,
};

const makeupStyle: Readonly<Record<NosmaiMakeupType, Readonly<Record<string, number>>>> = {
  lipstick: { classic: 0, matte: 1, natural: 2 },
  // Web alpha.2 does not expose a separate "smokey" style; natural is the
  // compatibility fallback used by the previous Nosmai Web integration.
  eyeshadow: { smokey: 2, shimmer: 1, natural: 2 },
  blusher: { round: 0, contour: 1, natural: 2 },
  eyelash: { natural: 0, dramatic: 1, wispy: 2 },
  eyebrow: { natural: 0, bold: 1, arched: 2 },
};

const reshapeType: Record<NosmaiReshapeType, number> = {
  lip: 0,
  faceSlim: 1,
  eye: 2,
  nose: 3,
  chin: 4,
  brow: 5,
  browThickness: 6,
  jaw: 7,
  mouthWidth: 8,
  forehead: 9,
};

function webRgb(color: NosmaiRgbColor) {
  return {
    r: requireFinite(color.red, 'color.red', 0, 1),
    g: requireFinite(color.green, 'color.green', 0, 1),
    b: requireFinite(color.blue, 'color.blue', 0, 1),
  };
}

function mapEffectParameters(values: unknown[]): NosmaiEffectParameter[] {
  return values.map((value) => {
    const object =
      value && typeof value === 'object'
        ? (value as Record<string, unknown>)
        : {};

    const type =
      typeof object.type === 'string'
        ? object.type
        : 'unknown';

    const normalizedType: NosmaiEffectParameter['type'] =
      type === 'float' ||
      type === 'int' ||
      type === 'bool' ||
      type === 'string' ||
      type === 'vector' ||
      type === 'enum'
        ? type
        : 'unknown';

    const currentValue =
      (object.currentValue ?? object.value ?? null) as NosmaiEffectParameter['currentValue'];

    const defaultValue =
      (object.defaultValue ?? null) as NosmaiEffectParameter['defaultValue'];

    return {
      name: String(object.name ?? ''),
      type: normalizedType,
      displayName: String(object.displayName ?? object.name ?? ''),
      description: String(object.description ?? ''),
      currentValue,
      defaultValue,
      hasRange: Boolean(object.hasRange ?? (
        typeof object.minValue === 'number' && typeof object.maxValue === 'number'
      )),
      minValue:
        typeof object.minValue === 'number' ? object.minValue : undefined,
      maxValue:
        typeof object.maxValue === 'number' ? object.maxValue : undefined,
      options: Array.isArray(object.options)
        ? object.options.filter((item): item is string => typeof item === 'string')
        : [],
      passId: typeof object.passId === 'number' ? object.passId : undefined,
    };
  });
}

let recordingTicker: ReturnType<typeof setInterval> | undefined;
let recordingStartedAt = 0;

function stopRecordingTicker() {
  if (recordingTicker !== undefined) {
    clearInterval(recordingTicker);
    recordingTicker = undefined;
  }
}

export const NosmaiCameraSdk: NosmaiCameraApi = {
  initialize(licenseKey) {
    return webRuntime.initialize(licenseKey);
  },

  async configureCamera(configuration) {
    if (!configuration || (configuration.position !== 'front' && configuration.position !== 'back')) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'camera position must be front or back.'
      );
    }
    webRuntime.setCameraConfiguration(configuration);
  },

  startProcessing() {
    return webRuntime.startProcessing();
  },

  stopProcessing() {
    return webRuntime.stopProcessing();
  },

  pauseCamera() {
    return webRuntime.pauseCamera();
  },

  resumeCamera() {
    return webRuntime.resumeCamera();
  },

  switchCamera() {
    return webRuntime.switchCamera();
  },

  async hasFlash() {
    return false;
  },

  async hasTorch() {
    return false;
  },

  async setFlashMode(mode) {
    if (mode !== 'off') return false;
    return true;
  },

  async setTorchMode(mode) {
    if (mode !== 'off') return false;
    return true;
  },

  async getFlashMode() {
    return 'off';
  },

  async getTorchMode() {
    return 'off';
  },

  cleanup() {
    stopRecordingTicker();
    return webRuntime.cleanup();
  },

  async startFrameStream() {
    notImplemented('startFrameStream');
  },

  async stopFrameStream() {
    // Web alpha.2 does not expose raw processed CPU frame delivery.
  },

  async getLatestFrame() {
    return undefined;
  },

  async isFrameStreamActive() {
    return false;
  },

  async capturePhoto() {
    const instance = webRuntime.getSdk();
    const blob: any = await instance.recording.capturePhoto('image/jpeg', 0.92);
    const canvas = webRuntime.getCanvas();
    return {
      uri: webRuntime.createObjectUrl(blob),
      width: canvas?.width ?? 0,
      height: canvas?.height ?? 0,
      fileSizeBytes: blob.size,
      mimeType: 'image/jpeg',
    };
  },

  async startRecording() {
    const instance = webRuntime.getSdk();
    if (!instance.recording.isSupported) {
      notImplemented('startRecording');
    }
    instance.recording.start();
    recordingStartedAt = Date.now();
    stopRecordingTicker();
    recordingTicker = setInterval(() => {
      const durationSeconds = Math.max(
        0,
        (Date.now() - recordingStartedAt) / 1000
      );
      webRuntime.emitRecording(durationSeconds);
    }, 500);
  },

  async stopRecording() {
    const instance = webRuntime.getSdk();
    const result = await instance.recording.stop();
    stopRecordingTicker();

    if (!result?.success || !result?.blob) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.recordingStop,
        result?.error || 'Nosmai Web recording failed.'
      );
    }

    const blob = result.blob as any;
    return {
      uri: webRuntime.createObjectUrl(blob),
      durationSeconds: Number(result.duration ?? 0),
      fileSizeBytes: Number(result.fileSize ?? blob.size),
      mimeType: String(result.mimeType ?? blob.type ?? 'video/webm'),
      hasAudio: false,
    };
  },

  async isRecording() {
    return Boolean(webRuntime.getSdk().recording.isRecording);
  },

  async getCurrentRecordingDuration() {
    const recorder = webRuntime.getSdk().recording;
    return Number(recorder.duration ?? 0);
  },

  async saveImageToGallery() {
    notImplemented('saveImageToGallery');
  },

  async saveVideoToGallery() {
    notImplemented('saveVideoToGallery');
  },

  async applyEffect(packagePath) {
    const source = requireName(packagePath, 'packagePath');
    const instance = webRuntime.getSdk();
    const cloudFilterId = webRuntime.cloudFilterIdForPath(source);

    if (cloudFilterId) {
      await webRuntime.applyCloudFilter(cloudFilterId);
    } else {
      await instance.effects.apply(source);
    }

    webRuntime.setCurrentEffect(
      source,
      normalizeWebEffect(instance.effects.active, source)
    );
    return true;
  },

  async applyFilter(packagePath) {
    const source = requireName(packagePath, 'packagePath');
    const instance = webRuntime.getSdk();
    const cloudFilterId = webRuntime.cloudFilterIdForPath(source);

    if (cloudFilterId) {
      await webRuntime.applyCloudFilter(cloudFilterId);
    } else {
      await instance.effects.apply(source);
    }

    webRuntime.setCurrentEffect(
      source,
      normalizeWebEffect(instance.effects.active, source)
    );
    return true;
  },

  async getActiveEffects() {
    return webRuntime.getActiveEffects();
  },

  async getActiveFilterInfo() {
    const state = webRuntime.getActiveEffects();
    return state.activeFilter;
  },

  async getActiveEffectInfo() {
    const state = webRuntime.getActiveEffects();
    return state.activeEffect;
  },

  async getEffectParameters() {
    const values = webRuntime.getSdk().effects.parameters();
    return mapEffectParameters(Array.isArray(values) ? values : []);
  },

  async getEffectParameterValue(parameterName) {
    return Number(
      webRuntime.getSdk().effects.getParameter(
        requireName(parameterName, 'parameterName')
      )
    );
  },

  async setEffectParameter(parameterName, value) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'value must be a finite number.'
      );
    }
    return Boolean(
      webRuntime.getSdk().effects.setParameter(
        requireName(parameterName, 'parameterName'),
        value
      )
    );
  },

  async setEffectParameterString(parameterName, value) {
    return Boolean(
      webRuntime.getSdk().effects.setParameterString(
        requireName(parameterName, 'parameterName'),
        String(value)
      )
    );
  },

  async isGameReady() {
    return Boolean(webRuntime.getSdk().game.isReady);
  },

  async sendGameTap(normalizedX, normalizedY) {
    return Boolean(
      webRuntime.getSdk().game.tap(
        requireFinite(normalizedX, 'normalizedX', 0, 1),
        requireFinite(normalizedY, 'normalizedY', 0, 1)
      )
    );
  },

  async sendGameInput(name, normalizedX, normalizedY, value = 1) {
    if (!Number.isFinite(value)) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        'value must be a finite number.'
      );
    }
    return Boolean(
      webRuntime.getSdk().game.input(
        requireName(name, 'name'),
        requireFinite(normalizedX, 'normalizedX', 0, 1),
        requireFinite(normalizedY, 'normalizedY', 0, 1),
        value
      )
    );
  },

  async pauseGame() {
    webRuntime.getSdk().game.pause();
  },

  async resumeGame() {
    webRuntime.getSdk().game.resume();
  },

  async restartGame() {
    webRuntime.getSdk().game.restart();
  },

  async getLocalFilters(_packageType?: PackageType) {
    return [];
  },

  async getDebugFilters(_packageType?: PackageType) {
    return [];
  },

  async isCloudFilterEnabled() {
    return Boolean(webRuntime.getSdk().isCloudFilterEnabled);
  },

  getCloudFilters(query) {
    return webRuntime.listCloud(query);
  },

  downloadCloudFilter(filter) {
    return webRuntime.downloadCloud(filter);
  },

  removeCloudFilter(filter) {
    return webRuntime.removeCloud(filter);
  },

  async removeEffect(effect) {
    const state = webRuntime.getActiveEffects();
    const requested =
      typeof effect === 'string'
        ? effect
        : effect.path;

    const active = state.activeEffectPath ?? state.activeFilterPath;
    if (!active || active !== requested) return false;

    await webRuntime.getSdk().effects.clear();
    webRuntime.clearCurrentEffect();
    return true;
  },

  async clearFilter() {
    await webRuntime.getSdk().effects.clear();
    webRuntime.clearCurrentEffect();
  },

  async clearAREffect() {
    await webRuntime.getSdk().effects.clear();
    webRuntime.clearCurrentEffect();
  },

  async clearAll() {
    const instance = webRuntime.getSdk();
    await instance.effects.clear();
    instance.beauty.clear();
    instance.background.clear();
    instance.color.clear();
    webRuntime.clearCurrentEffect();
    webRuntime.setBackgroundActive(false);
  },

  async isBeautyEffectEnabled() {
    return Boolean(webRuntime.getSdk().isBeautyEffectEnabled);
  },

  async isAdvancedFiltersEnabled() {
    const instance = webRuntime.getSdk();
    if (typeof instance.isFeatureEnabled === 'function') {
      try {
        if (instance.isFeatureEnabled('advancedFilters')) return true;
      } catch {}
      try {
        if (instance.isFeatureEnabled('advanced_filters')) return true;
      } catch {}
    }
    return Boolean(instance.color);
  },

  async setSkinSmoothing(level) {
    webRuntime.getSdk().beauty.setSkinSmoothing(
      requireFinite(level, 'level', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async setSkinWhitening(level) {
    webRuntime.getSdk().beauty.setSkinWhitening(
      requireFinite(level, 'level', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async setTeethWhitening(level) {
    webRuntime.getSdk().beauty.setTeethWhitening(
      requireFinite(level, 'level', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async clearBeauty() {
    webRuntime.getSdk().beauty.clear();
    webRuntime.emitActiveEffectsChanged();
  },

  async applyMakeup(configuration: NosmaiMakeupConfiguration) {
    const layer = makeupLayer[configuration.type];
    const style = makeupStyle[configuration.type][configuration.style];

    if (style === undefined) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidArgument,
        `Unsupported ${configuration.type} style: ${configuration.style}.`
      );
    }

    const colour =
      configuration.type === 'eyelash'
        ? { r: 0, g: 0, b: 0 }
        : webRgb(configuration.color);

    webRuntime.getSdk().beauty.makeup.apply(
      layer,
      style,
      colour,
      requireFinite(configuration.intensity ?? 1, 'intensity', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async setMakeupIntensity(makeupType, intensity) {
    webRuntime.getSdk().beauty.makeup.setIntensity(
      makeupLayer[makeupType],
      requireFinite(intensity, 'intensity', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async removeMakeup(makeupType) {
    webRuntime.getSdk().beauty.makeup.remove(makeupLayer[makeupType]);
    webRuntime.emitActiveEffectsChanged();
  },

  async isMakeupActive(makeupType) {
    return Boolean(
      webRuntime.getSdk().beauty.makeup.isActive(makeupLayer[makeupType])
    );
  },

  async clearMakeup() {
    webRuntime.getSdk().beauty.makeup.clear();
    webRuntime.emitActiveEffectsChanged();
  },

  async setReshape(type, value) {
    const ranges: Record<NosmaiReshapeType, readonly [number, number]> = {
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
    const [min, max] = ranges[type];
    webRuntime.getSdk().beauty.reshape.set(
      reshapeType[type],
      requireFinite(value, 'value', min, max)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async clearReshaping() {
    webRuntime.getSdk().beauty.reshape.clear();
    webRuntime.emitActiveEffectsChanged();
  },

  async setEyeColor(color, intensity = 0.5) {
    webRuntime.getSdk().beauty.setEyeColor(
      webRgb(color),
      requireFinite(intensity, 'intensity', 0, 1)
    );
    webRuntime.emitActiveEffectsChanged();
  },

  async setEyeColorIntensity(intensity) {
    webRuntime.getSdk().beauty.setEyeColorIntensity(
      requireFinite(intensity, 'intensity', 0, 1)
    );
  },

  async removeEyeColor() {
    webRuntime.getSdk().beauty.setEyeColorIntensity(0);
    webRuntime.emitActiveEffectsChanged();
  },

  async isEyeColorActive() {
    return Boolean(webRuntime.getSdk().beauty.isEyeColorActive);
  },

  async setBrightness(brightness) {
    webRuntime.getSdk().color.setBrightness(
      requireFinite(brightness, 'brightness', -1, 1)
    );
  },

  async setContrast(contrast) {
    webRuntime.getSdk().color.setContrastMultiplier(
      requireFinite(contrast, 'contrast', 0, 2)
    );
  },

  async setRgbAdjustment(adjustment) {
    webRuntime.getSdk().color.setRgbGains(
      requireFinite(adjustment.red, 'adjustment.red', 0, 2),
      requireFinite(adjustment.green, 'adjustment.green', 0, 2),
      requireFinite(adjustment.blue, 'adjustment.blue', 0, 2)
    );
  },

  async setSharpening(level) {
    webRuntime.getSdk().beauty.setSharpening(
      requireFinite(level, 'level', 0, 1)
    );
  },

  async setGrayscale(enabled) {
    webRuntime.getSdk().color.setGrayscale(Boolean(enabled));
  },

  async setHue(degrees) {
    webRuntime.getSdk().color.setHsb(
      requireFinite(degrees, 'degrees', -180, 180)
    );
  },

  async setWhiteBalance(configuration) {
    webRuntime.getSdk().color.setWhiteBalance(
      configuration.temperature,
      configuration.tint
    );
  },

  async setHsb(adjustment) {
    webRuntime.getSdk().color.setHsb(
      adjustment.hue,
      adjustment.saturation,
      adjustment.brightness
    );
  },

  async resetColorAdjustments() {
    webRuntime.getSdk().color.clear();
  },

  async setBackground(configuration) {
    const background = webRuntime.getSdk().background;
    switch (configuration.mode) {
      case 'blur':
        background.blur(
          requireFinite(configuration.blurStrength, 'blurStrength', 0, 1)
        );
        break;
      case 'color': {
        const color = webRgb(configuration.color);
        background.color(
          color,
          requireFinite(configuration.color.alpha ?? 1, 'alpha', 0, 1)
        );
        break;
      }
      case 'image':
        await background.image(requireName(configuration.uri, 'uri'));
        break;
      case 'video':
        await background.video(requireName(configuration.uri, 'uri'));
        break;
    }
    webRuntime.setBackgroundActive(true);
  },

  async clearBackground() {
    webRuntime.getSdk().background.clear();
    webRuntime.setBackgroundActive(false);
  },

  addActiveEffectsChangedListener(listener) {
    return webRuntime.addListener('activeEffects', listener);
  },

  addLicenseStatusChangedListener(listener) {
    return webRuntime.addListener('license', listener);
  },

  addErrorListener(listener) {
    return webRuntime.addListener('error', listener);
  },

  addRecordingProgressListener(listener) {
    return webRuntime.addListener('recording', listener);
  },

  addDownloadProgressListener(listener) {
    return webRuntime.addListener('download', listener);
  },

  addGameEventListener(listener) {
    return webRuntime.addListener('game', listener);
  },

  addFrameAvailableListener(_listener) {
    return { remove() {} };
  },
};

export default NosmaiCameraSdk;
