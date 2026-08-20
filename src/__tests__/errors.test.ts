import { NosmaiErrorCode, NosmaiSdkError } from '../errors';

describe('NosmaiSdkError', () => {
  it('exports the complete stable error-code set without duplicates', () => {
    const codes = Object.values(NosmaiErrorCode);

    expect(new Set(codes).size).toBe(codes.length);
    expect(codes).toEqual(
      expect.arrayContaining([
        'E_INVALID_ARGUMENT',
        'E_INVALID_STATE',
        'E_NOT_INITIALIZED',
        'E_NOT_IMPLEMENTED',
        'E_NATIVE_FAILURE',
        'E_INITIALIZATION',
        'E_LICENSE_CALLBACK_UNAVAILABLE',
        'E_LICENSE_KEY_MISMATCH',
        'E_UNSUPPORTED_CAMERA_PRESET',
        'E_CAMERA_PERMISSION',
        'E_CAMERA_UNAVAILABLE',
        'E_CAMERA_FACING_UNAVAILABLE',
        'E_CAMERA_OUTPUT_UNAVAILABLE',
        'E_CAMERA_OES_SURFACE',
        'E_CAMERA_ACCESS',
        'E_CAMERA_OPEN',
        'E_CAMERA_OPEN_TIMEOUT',
        'E_CAMERA_CLOSE_TIMEOUT',
        'E_CAMERA_DISCONNECTED',
        'E_CAMERA_DEVICE',
        'E_CAMERA_CONFIGURE',
        'E_CAMERA_PREVIEW_START',
        'E_CAMERA_FRAME',
        'E_CAMERA_INTERRUPTED',
        'E_CAMERA_START',
        'E_NO_PREVIEW',
        'E_PROCESSING_START',
        'E_PROCESSING_STOP',
        'E_PIPELINE_NOT_READY',
        'E_OES_UNAVAILABLE',
        'E_OES_FALLBACK',
        'E_INVALID_PACKAGE_PATH',
        'E_EFFECT_APPLY',
        'E_EFFECT_CLEAR',
        'E_EFFECT_STATE',
        'E_EFFECT_PARAMETER',
        'E_LOCAL_CATALOG',
        'E_CLOUD_DISABLED',
        'E_CLOUD_CATALOG',
        'E_CLOUD_DOWNLOAD',
        'E_CLOUD_DOWNLOAD_TIMEOUT',
        'E_CLOUD_REMOVE',
        'E_BEAUTY_DISABLED',
        'E_ADVANCED_FILTERS_DISABLED',
        'E_VISUAL_CONTROL',
        'E_BACKGROUND_RESOURCE',
        'E_RECORDING_PERMISSION',
        'E_RECORDING_IN_PROGRESS',
        'E_NOT_RECORDING',
        'E_RECORDING_START',
        'E_RECORDING_STOP',
        'E_RECORDING_STORAGE_FULL',
        'E_RECORDING_WRITE',
        'E_RECORDING_AUDIO_MUX',
        'E_RECORDING_INTERRUPTED',
        'E_CAPTURE_IN_PROGRESS',
        'E_CAPTURE_FAILED',
        'E_MEDIA_NOT_FOUND',
        'E_GALLERY_PERMISSION',
        'E_GALLERY_SAVE',
        'E_SESSION_DESTROYED',
        'E_OPERATION_CANCELLED',
        'E_CLEANUP',
      ])
    );
  });

  it('preserves native bridge codes and messages', () => {
    const error = NosmaiSdkError.fromUnknown({
      code: 'E_LICENSE_INVALID',
      message: 'License validation failed.',
    });

    expect(error).toBeInstanceOf(NosmaiSdkError);
    expect(error.code).toBe('E_LICENSE_INVALID');
    expect(error.message).toBe('License validation failed.');
  });

  it('falls back to the stable native-failure code', () => {
    expect(NosmaiSdkError.fromUnknown('failure').code).toBe(
      NosmaiErrorCode.nativeFailure
    );
  });

  it('preserves native rejection userInfo as error details', () => {
    const error = NosmaiSdkError.fromUnknown({
      code: 'E_CAMERA_START',
      message: 'Camera could not start.',
      userInfo: { nativeCause: 'CameraAccessException' },
    });

    expect(error.details).toEqual({
      nativeCause: 'CameraAccessException',
    });
  });

  it('retains an existing typed error instance', () => {
    const error = new NosmaiSdkError(
      NosmaiErrorCode.operationCancelled,
      'The operation was cancelled.'
    );

    expect(NosmaiSdkError.fromUnknown(error)).toBe(error);
  });
});
