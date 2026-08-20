export const NosmaiErrorCode = {
  invalidArgument: 'E_INVALID_ARGUMENT',
  invalidState: 'E_INVALID_STATE',
  notInitialized: 'E_NOT_INITIALIZED',
  notImplemented: 'E_NOT_IMPLEMENTED',
  nativeFailure: 'E_NATIVE_FAILURE',
  initialization: 'E_INITIALIZATION',
  licenseCallbackUnavailable: 'E_LICENSE_CALLBACK_UNAVAILABLE',
  licenseKeyMismatch: 'E_LICENSE_KEY_MISMATCH',
  unsupportedCameraPreset: 'E_UNSUPPORTED_CAMERA_PRESET',
  cameraPermission: 'E_CAMERA_PERMISSION',
  cameraUnavailable: 'E_CAMERA_UNAVAILABLE',
  cameraFacingUnavailable: 'E_CAMERA_FACING_UNAVAILABLE',
  cameraOutputUnavailable: 'E_CAMERA_OUTPUT_UNAVAILABLE',
  cameraOesSurface: 'E_CAMERA_OES_SURFACE',
  cameraAccess: 'E_CAMERA_ACCESS',
  cameraOpen: 'E_CAMERA_OPEN',
  cameraOpenTimeout: 'E_CAMERA_OPEN_TIMEOUT',
  cameraCloseTimeout: 'E_CAMERA_CLOSE_TIMEOUT',
  cameraDisconnected: 'E_CAMERA_DISCONNECTED',
  cameraDevice: 'E_CAMERA_DEVICE',
  cameraConfigure: 'E_CAMERA_CONFIGURE',
  cameraPreviewStart: 'E_CAMERA_PREVIEW_START',
  cameraFrame: 'E_CAMERA_FRAME',
  frameStream: 'E_FRAME_STREAM',
  cameraInterrupted: 'E_CAMERA_INTERRUPTED',
  cameraStart: 'E_CAMERA_START',
  noPreview: 'E_NO_PREVIEW',
  processingStart: 'E_PROCESSING_START',
  processingStop: 'E_PROCESSING_STOP',
  pipelineNotReady: 'E_PIPELINE_NOT_READY',
  oesUnavailable: 'E_OES_UNAVAILABLE',
  oesFallback: 'E_OES_FALLBACK',
  invalidPackagePath: 'E_INVALID_PACKAGE_PATH',
  effectApply: 'E_EFFECT_APPLY',
  effectClear: 'E_EFFECT_CLEAR',
  effectState: 'E_EFFECT_STATE',
  effectParameter: 'E_EFFECT_PARAMETER',
  localCatalog: 'E_LOCAL_CATALOG',
  debugFilters: 'E_DEBUG_FILTERS',
  cloudDisabled: 'E_CLOUD_DISABLED',
  cloudCatalog: 'E_CLOUD_CATALOG',
  cloudDownload: 'E_CLOUD_DOWNLOAD',
  cloudDownloadTimeout: 'E_CLOUD_DOWNLOAD_TIMEOUT',
  cloudRemove: 'E_CLOUD_REMOVE',
  beautyDisabled: 'E_BEAUTY_DISABLED',
  advancedFiltersDisabled: 'E_ADVANCED_FILTERS_DISABLED',
  visualControl: 'E_VISUAL_CONTROL',
  backgroundResource: 'E_BACKGROUND_RESOURCE',
  recordingPermission: 'E_RECORDING_PERMISSION',
  recordingInProgress: 'E_RECORDING_IN_PROGRESS',
  notRecording: 'E_NOT_RECORDING',
  recordingStart: 'E_RECORDING_START',
  recordingStop: 'E_RECORDING_STOP',
  recordingStorageFull: 'E_RECORDING_STORAGE_FULL',
  recordingWrite: 'E_RECORDING_WRITE',
  recordingAudioMux: 'E_RECORDING_AUDIO_MUX',
  recordingInterrupted: 'E_RECORDING_INTERRUPTED',
  captureInProgress: 'E_CAPTURE_IN_PROGRESS',
  captureFailed: 'E_CAPTURE_FAILED',
  mediaNotFound: 'E_MEDIA_NOT_FOUND',
  galleryPermission: 'E_GALLERY_PERMISSION',
  gallerySave: 'E_GALLERY_SAVE',
  sessionDestroyed: 'E_SESSION_DESTROYED',
  operationCancelled: 'E_OPERATION_CANCELLED',
  cleanup: 'E_CLEANUP',
} as const;

export type NosmaiErrorCodeValue =
  (typeof NosmaiErrorCode)[keyof typeof NosmaiErrorCode];

export class NosmaiSdkError extends Error {
  readonly code: string;
  readonly details?: Readonly<Record<string, unknown>>;

  constructor(
    code: string,
    message: string,
    details?: Readonly<Record<string, unknown>>
  ) {
    super(message);
    this.name = 'NosmaiSdkError';
    this.code = code;
    this.details = details;
  }

  static fromUnknown(error: unknown): NosmaiSdkError {
    if (error instanceof NosmaiSdkError) {
      return error;
    }

    if (typeof error === 'object' && error !== null) {
      const value = error as Record<string, unknown>;
      const code = typeof value.code === 'string' ? value.code : undefined;
      const message =
        typeof value.message === 'string' ? value.message : undefined;
      const details =
        typeof (value.details ?? value.userInfo) === 'object' &&
        (value.details ?? value.userInfo) !== null
          ? ((value.details ?? value.userInfo) as Readonly<
              Record<string, unknown>
            >)
          : undefined;

      if (code || message) {
        return new NosmaiSdkError(
          code ?? NosmaiErrorCode.nativeFailure,
          message ?? 'The native Nosmai SDK operation failed.',
          details
        );
      }
    }

    return new NosmaiSdkError(
      NosmaiErrorCode.nativeFailure,
      error instanceof Error
        ? error.message
        : 'The native Nosmai SDK operation failed.'
    );
  }
}
