import {
  TurboModuleRegistry,
  type CodegenTypes,
  type TurboModule,
} from 'react-native';

export type NativePhotoResult = {
  uri: string;
  width: number;
  height: number;
  fileSizeBytes: number;
  mimeType: string;
};

export type NativeRecordingResult = {
  uri: string;
  durationSeconds: number;
  fileSizeBytes: number;
  mimeType: string;
  hasAudio: boolean;
};

export type NativeGallerySaveResult = {
  uri: string;
  mediaType: string;
};

export type NativeRecordingProgressEvent = {
  durationSeconds: number;
};

export type NativeDownloadProgressEvent = {
  filterId: string;
  progress: number;
};

export interface Spec extends TurboModule {
  initialize(licenseKey: string): Promise<boolean>;
  configureCamera(
    position: string,
    sessionPreset: string | null
  ): Promise<void>;
  startProcessing(): Promise<void>;
  stopProcessing(): Promise<void>;
  pauseCamera(): Promise<boolean>;
  resumeCamera(): Promise<boolean>;
  switchCamera(): Promise<boolean>;
  hasFlash(): Promise<boolean>;
  hasTorch(): Promise<boolean>;
  setFlashMode(mode: string): Promise<boolean>;
  setTorchMode(mode: string): Promise<boolean>;
  getFlashMode(): Promise<string>;
  getTorchMode(): Promise<string>;
  cleanup(): Promise<void>;

  startFrameStream(maxFramesPerSecond: number): Promise<void>;
  stopFrameStream(): Promise<void>;
  getLatestFrame(): Promise<CodegenTypes.UnsafeObject | null>;
  isFrameStreamActive(): Promise<boolean>;

  capturePhoto(): Promise<NativePhotoResult>;
  startRecording(): Promise<void>;
  stopRecording(): Promise<NativeRecordingResult>;
  isRecording(): Promise<boolean>;
  getCurrentRecordingDuration(): Promise<number>;
  saveImageToGallery(
    imageUri: string,
    name: string | null
  ): Promise<NativeGallerySaveResult>;
  saveVideoToGallery(
    videoUri: string,
    name: string | null
  ): Promise<NativeGallerySaveResult>;

  applyEffect(packagePath: string): Promise<boolean>;
  getActiveEffects(): Promise<CodegenTypes.UnsafeObject>;
  getActiveFilterInfo(): Promise<CodegenTypes.UnsafeObject | null>;
  getActiveEffectInfo(): Promise<CodegenTypes.UnsafeObject | null>;
  getEffectParameters(): Promise<CodegenTypes.UnsafeObject>;
  getEffectParameterValue(parameterName: string): Promise<number>;
  setEffectParameter(parameterName: string, value: number): Promise<boolean>;
  setEffectParameterString(
    parameterName: string,
    value: string
  ): Promise<boolean>;
  isGameReady(): Promise<boolean>;
  sendGameTap(normalizedX: number, normalizedY: number): Promise<boolean>;
  sendGameInput(
    name: string,
    normalizedX: number,
    normalizedY: number,
    value: number
  ): Promise<boolean>;
  pauseGame(): Promise<void>;
  resumeGame(): Promise<void>;
  restartGame(): Promise<void>;
  getLocalFilters(
    packageType: string | null
  ): Promise<CodegenTypes.UnsafeObject>;
  getDebugFilters(
    packageType: string | null
  ): Promise<CodegenTypes.UnsafeObject>;
  isCloudFilterEnabled(): Promise<boolean>;
  getCloudFilters(
    packageType: string | null,
    version: string,
    page: number,
    limit: number,
    fetchAllPages: boolean
  ): Promise<CodegenTypes.UnsafeObject>;
  downloadCloudFilter(filterId: string): Promise<CodegenTypes.UnsafeObject>;
  removeCloudFilter(filterId: string): Promise<boolean>;
  removeEffect(packagePath: string): Promise<boolean>;
  clearFilter(): Promise<void>;
  clearAREffect(): Promise<void>;
  clearAll(): Promise<void>;

  isBeautyEffectEnabled(): Promise<boolean>;
  isAdvancedFiltersEnabled(): Promise<boolean>;
  setBeautyValue(control: string, value: number): Promise<void>;
  clearBeauty(): Promise<void>;
  applyMakeup(
    makeupType: string,
    style: string,
    red: number,
    green: number,
    blue: number,
    intensity: number
  ): Promise<void>;
  setMakeupIntensity(makeupType: string, intensity: number): Promise<void>;
  removeMakeup(makeupType: string): Promise<void>;
  isMakeupActive(makeupType: string): Promise<boolean>;
  clearMakeup(): Promise<void>;
  setReshape(reshapeType: string, value: number): Promise<void>;
  clearReshapes(): Promise<void>;
  setEyeColor(
    red: number,
    green: number,
    blue: number,
    intensity: number
  ): Promise<void>;
  setEyeColorIntensity(intensity: number): Promise<void>;
  removeEyeColor(): Promise<void>;
  isEyeColorActive(): Promise<boolean>;
  setColorAdjustment(
    control: string,
    value1: number,
    value2: number,
    value3: number
  ): Promise<void>;
  resetColorAdjustments(): Promise<void>;
  setBackground(
    mode: string,
    resourceUri: string | null,
    red: number,
    green: number,
    blue: number,
    alpha: number,
    blurStrength: number
  ): Promise<void>;
  clearBackground(): Promise<void>;

  readonly onActiveEffectsChanged: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onLicenseStatusChanged: CodegenTypes.EventEmitter<string>;
  readonly onError: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onRecordingProgress: CodegenTypes.EventEmitter<NativeRecordingProgressEvent>;
  readonly onDownloadProgress: CodegenTypes.EventEmitter<NativeDownloadProgressEvent>;
  readonly onGameEvent: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
  readonly onFrameAvailable: CodegenTypes.EventEmitter<CodegenTypes.UnsafeObject>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NosmaiCameraSdk');
