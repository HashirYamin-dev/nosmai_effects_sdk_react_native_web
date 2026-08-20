jest.mock('../NativeNosmaiCameraSdk', () => ({
  __esModule: true,
  default: {
    initialize: jest.fn(),
    configureCamera: jest.fn(),
    startProcessing: jest.fn(),
    stopProcessing: jest.fn(),
    pauseCamera: jest.fn(),
    resumeCamera: jest.fn(),
    switchCamera: jest.fn(),
    hasFlash: jest.fn(),
    hasTorch: jest.fn(),
    setFlashMode: jest.fn(),
    setTorchMode: jest.fn(),
    getFlashMode: jest.fn(),
    getTorchMode: jest.fn(),
    cleanup: jest.fn(),
    capturePhoto: jest.fn(),
    startRecording: jest.fn(),
    stopRecording: jest.fn(),
    isRecording: jest.fn(),
    getCurrentRecordingDuration: jest.fn(),
    saveImageToGallery: jest.fn(),
    saveVideoToGallery: jest.fn(),
    applyEffect: jest.fn(),
    getActiveEffects: jest.fn(),
    getActiveFilterInfo: jest.fn(),
    getActiveEffectInfo: jest.fn(),
    getEffectParameters: jest.fn(),
    getEffectParameterValue: jest.fn(),
    setEffectParameter: jest.fn(),
    setEffectParameterString: jest.fn(),
    isGameReady: jest.fn(),
    sendGameTap: jest.fn(),
    sendGameInput: jest.fn(),
    pauseGame: jest.fn(),
    resumeGame: jest.fn(),
    restartGame: jest.fn(),
    getLocalFilters: jest.fn(),
    getDebugFilters: jest.fn(),
    isCloudFilterEnabled: jest.fn(),
    getCloudFilters: jest.fn(),
    downloadCloudFilter: jest.fn(),
    removeCloudFilter: jest.fn(),
    removeEffect: jest.fn(),
    clearFilter: jest.fn(),
    clearAREffect: jest.fn(),
    clearAll: jest.fn(),
    isBeautyEffectEnabled: jest.fn(),
    isAdvancedFiltersEnabled: jest.fn(),
    setBeautyValue: jest.fn(),
    clearBeauty: jest.fn(),
    applyMakeup: jest.fn(),
    setMakeupIntensity: jest.fn(),
    removeMakeup: jest.fn(),
    isMakeupActive: jest.fn(),
    clearMakeup: jest.fn(),
    setReshape: jest.fn(),
    clearReshapes: jest.fn(),
    setEyeColor: jest.fn(),
    setEyeColorIntensity: jest.fn(),
    removeEyeColor: jest.fn(),
    isEyeColorActive: jest.fn(),
    setColorAdjustment: jest.fn(),
    resetColorAdjustments: jest.fn(),
    setBackground: jest.fn(),
    clearBackground: jest.fn(),
    onActiveEffectsChanged: jest.fn(),
    onLicenseStatusChanged: jest.fn(),
    onError: jest.fn(),
    onRecordingProgress: jest.fn(),
    onDownloadProgress: jest.fn(),
    onGameEvent: jest.fn(),
  },
}));

import NativeNosmaiCameraSdk from '../NativeNosmaiCameraSdk';
import { NosmaiCameraSdk } from '../NosmaiCameraSdk';
import { NosmaiErrorCode, NosmaiSdkError } from '../errors';

const nativeMock = NativeNosmaiCameraSdk as jest.Mocked<
  typeof NativeNosmaiCameraSdk
>;

describe('NosmaiCameraSdk JavaScript contract', () => {
  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('trims a key and returns native initialization acceptance unchanged', async () => {
    nativeMock.initialize.mockResolvedValue(true);

    await expect(
      NosmaiCameraSdk.initialize('  development-key  ')
    ).resolves.toBe(true);
    expect(nativeMock.initialize).toHaveBeenCalledWith('development-key');
  });

  it('rejects an empty key before entering native code', async () => {
    await expect(NosmaiCameraSdk.initialize('   ')).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
      message: 'licenseKey must not be empty.',
    });
    expect(nativeMock.initialize).not.toHaveBeenCalled();
  });

  it('uses a null native preset when JavaScript omits it', async () => {
    nativeMock.configureCamera.mockResolvedValue(undefined);

    await expect(
      NosmaiCameraSdk.configureCamera({ position: 'front' })
    ).resolves.toBeUndefined();
    expect(nativeMock.configureCamera).toHaveBeenCalledWith('front', null);
  });

  it('forwards an explicit camera preset unchanged', async () => {
    nativeMock.configureCamera.mockResolvedValue(undefined);

    await expect(
      NosmaiCameraSdk.configureCamera({
        position: 'back',
        sessionPreset: '720p',
      })
    ).resolves.toBeUndefined();
    expect(nativeMock.configureCamera).toHaveBeenCalledWith('back', '720p');
  });

  it('keeps void lifecycle operations void', async () => {
    nativeMock.startProcessing.mockResolvedValue(undefined);

    await expect(NosmaiCameraSdk.startProcessing()).resolves.toBeUndefined();
    expect(nativeMock.startProcessing).toHaveBeenCalledTimes(1);
  });

  it('delegates stop and cleanup to their distinct native methods', async () => {
    nativeMock.stopProcessing.mockResolvedValue(undefined);
    nativeMock.cleanup.mockResolvedValue(undefined);

    await expect(NosmaiCameraSdk.stopProcessing()).resolves.toBeUndefined();
    expect(nativeMock.stopProcessing).toHaveBeenCalledTimes(1);
    expect(nativeMock.cleanup).not.toHaveBeenCalled();

    jest.clearAllMocks();
    await expect(NosmaiCameraSdk.cleanup()).resolves.toBeUndefined();
    expect(nativeMock.cleanup).toHaveBeenCalledTimes(1);
    expect(nativeMock.stopProcessing).not.toHaveBeenCalled();
  });

  it('captures and strictly normalizes a processed photo file', async () => {
    nativeMock.capturePhoto.mockResolvedValue({
      uri: 'file:///tmp/nosmai-photo.jpg',
      width: 1080,
      height: 1920,
      fileSizeBytes: 245_760,
      mimeType: 'image/jpeg',
    });

    await expect(NosmaiCameraSdk.capturePhoto()).resolves.toEqual({
      uri: 'file:///tmp/nosmai-photo.jpg',
      width: 1080,
      height: 1920,
      fileSizeBytes: 245_760,
      mimeType: 'image/jpeg',
    });
  });

  it('starts, queries, and finalizes recording through distinct methods', async () => {
    nativeMock.startRecording.mockResolvedValue(undefined);
    nativeMock.isRecording.mockResolvedValue(true);
    nativeMock.getCurrentRecordingDuration.mockResolvedValue(1.25);
    nativeMock.stopRecording.mockResolvedValue({
      uri: 'file:///tmp/nosmai-video.mp4',
      durationSeconds: 1.5,
      fileSizeBytes: 1_048_576,
      mimeType: 'video/mp4',
      hasAudio: true,
    });

    await expect(NosmaiCameraSdk.startRecording()).resolves.toBeUndefined();
    await expect(NosmaiCameraSdk.isRecording()).resolves.toBe(true);
    await expect(NosmaiCameraSdk.getCurrentRecordingDuration()).resolves.toBe(
      1.25
    );
    await expect(NosmaiCameraSdk.stopRecording()).resolves.toEqual({
      uri: 'file:///tmp/nosmai-video.mp4',
      durationSeconds: 1.5,
      fileSizeBytes: 1_048_576,
      mimeType: 'video/mp4',
      hasAudio: true,
    });
  });

  it('preserves video-only output and delivers an audio-mux warning separately', async () => {
    nativeMock.stopRecording.mockResolvedValue({
      uri: 'file:///tmp/nosmai-video-only.mp4',
      durationSeconds: 2,
      fileSizeBytes: 524_288,
      mimeType: 'video/mp4',
      hasAudio: false,
    });
    const subscription = { remove: jest.fn() };
    nativeMock.onError.mockImplementation((listener) => {
      listener({
        code: NosmaiErrorCode.recordingAudioMux,
        message: 'The video was preserved without audio.',
      });
      return subscription;
    });
    const warningListener = jest.fn();

    expect(NosmaiCameraSdk.addErrorListener(warningListener)).toBe(
      subscription
    );
    await expect(NosmaiCameraSdk.stopRecording()).resolves.toMatchObject({
      uri: 'file:///tmp/nosmai-video-only.mp4',
      hasAudio: false,
    });
    expect(warningListener).toHaveBeenCalledWith({
      code: NosmaiErrorCode.recordingAudioMux,
      message: 'The video was preserved without audio.',
      details: undefined,
    });
  });

  it('saves local media URIs with normalized optional names', async () => {
    nativeMock.saveImageToGallery.mockResolvedValue({
      uri: 'content://media/images/42',
      mediaType: 'photo',
    });
    nativeMock.saveVideoToGallery.mockResolvedValue({
      uri: 'ph://video-asset-id',
      mediaType: 'video',
    });

    await expect(
      NosmaiCameraSdk.saveImageToGallery(
        '  file:///tmp/nosmai-photo.jpg  ',
        '  portrait  '
      )
    ).resolves.toEqual({
      uri: 'content://media/images/42',
      mediaType: 'photo',
    });
    expect(nativeMock.saveImageToGallery).toHaveBeenCalledWith(
      'file:///tmp/nosmai-photo.jpg',
      'portrait'
    );

    await expect(
      NosmaiCameraSdk.saveVideoToGallery(
        'file://localhost/tmp/nosmai-video.mp4'
      )
    ).resolves.toEqual({
      uri: 'ph://video-asset-id',
      mediaType: 'video',
    });
    expect(nativeMock.saveVideoToGallery).toHaveBeenCalledWith(
      'file://localhost/tmp/nosmai-video.mp4',
      null
    );
  });

  it('rejects non-file media input before entering native code', async () => {
    await expect(
      NosmaiCameraSdk.saveImageToGallery('https://example.com/photo.jpg')
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.saveVideoToGallery('file:///tmp/video.mp4?token=secret')
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });

    expect(nativeMock.saveImageToGallery).not.toHaveBeenCalled();
    expect(nativeMock.saveVideoToGallery).not.toHaveBeenCalled();
  });

  it('rejects a gallery display path before entering native code', async () => {
    await expect(
      NosmaiCameraSdk.saveImageToGallery(
        'file:///tmp/nosmai-photo.jpg',
        '../portrait'
      )
    ).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
      message: 'name must be a file-name stem, not a path.',
    });
    await expect(
      NosmaiCameraSdk.saveImageToGallery(
        'file:///tmp/nosmai-photo.jpg',
        'portrait.jpg'
      )
    ).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
      message: 'name must be a file-name stem, not a path.',
    });
    expect(nativeMock.saveImageToGallery).not.toHaveBeenCalled();
  });

  it('delegates pause and resume to their distinct native methods', async () => {
    nativeMock.pauseCamera.mockResolvedValue(true);
    nativeMock.resumeCamera.mockResolvedValue(true);

    await expect(NosmaiCameraSdk.pauseCamera()).resolves.toBe(true);
    expect(nativeMock.pauseCamera).toHaveBeenCalledTimes(1);
    expect(nativeMock.resumeCamera).not.toHaveBeenCalled();

    jest.clearAllMocks();
    await expect(NosmaiCameraSdk.resumeCamera()).resolves.toBe(true);
    expect(nativeMock.resumeCamera).toHaveBeenCalledTimes(1);
    expect(nativeMock.pauseCamera).not.toHaveBeenCalled();
  });

  it('delegates each clear operation to its exact native method', async () => {
    nativeMock.clearFilter.mockResolvedValue(undefined);
    nativeMock.clearAREffect.mockResolvedValue(undefined);
    nativeMock.clearAll.mockResolvedValue(undefined);

    await expect(NosmaiCameraSdk.clearFilter()).resolves.toBeUndefined();
    expect(nativeMock.clearFilter).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearAREffect).not.toHaveBeenCalled();
    expect(nativeMock.clearAll).not.toHaveBeenCalled();

    jest.clearAllMocks();
    await expect(NosmaiCameraSdk.clearAREffect()).resolves.toBeUndefined();
    expect(nativeMock.clearAREffect).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearFilter).not.toHaveBeenCalled();
    expect(nativeMock.clearAll).not.toHaveBeenCalled();

    jest.clearAllMocks();
    await expect(NosmaiCameraSdk.clearAll()).resolves.toBeUndefined();
    expect(nativeMock.clearAll).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearFilter).not.toHaveBeenCalled();
    expect(nativeMock.clearAREffect).not.toHaveBeenCalled();
  });

  it('preserves the documented benign false switch outcome', async () => {
    nativeMock.switchCamera.mockResolvedValue(false);

    await expect(NosmaiCameraSdk.switchCamera()).resolves.toBe(false);
    expect(nativeMock.switchCamera).toHaveBeenCalledTimes(1);
  });

  it('queries and controls flash and torch with distinct mode contracts', async () => {
    nativeMock.hasFlash.mockResolvedValue(true);
    nativeMock.hasTorch.mockResolvedValue(true);
    nativeMock.setFlashMode.mockResolvedValue(true);
    nativeMock.setTorchMode.mockResolvedValue(true);
    nativeMock.getFlashMode.mockResolvedValue('auto');
    nativeMock.getTorchMode.mockResolvedValue('on');

    await expect(NosmaiCameraSdk.hasFlash()).resolves.toBe(true);
    await expect(NosmaiCameraSdk.hasTorch()).resolves.toBe(true);
    await expect(NosmaiCameraSdk.setFlashMode('auto')).resolves.toBe(true);
    await expect(NosmaiCameraSdk.setTorchMode('on')).resolves.toBe(true);
    await expect(NosmaiCameraSdk.getFlashMode()).resolves.toBe('auto');
    await expect(NosmaiCameraSdk.getTorchMode()).resolves.toBe('on');

    expect(nativeMock.setFlashMode).toHaveBeenCalledWith('auto');
    expect(nativeMock.setTorchMode).toHaveBeenCalledWith('on');
    await expect(
      NosmaiCameraSdk.setTorchMode('auto' as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    expect(nativeMock.setTorchMode).toHaveBeenCalledTimes(1);
  });

  it('routes the deprecated filter alias through the canonical apply call', async () => {
    nativeMock.applyEffect.mockResolvedValue(true);

    await expect(
      NosmaiCameraSdk.applyFilter('  /effects/look.nosmai  ')
    ).resolves.toBe(true);
    expect(nativeMock.applyEffect).toHaveBeenCalledWith('/effects/look.nosmai');
  });

  it('rejects a blank effect path before entering native code', async () => {
    await expect(NosmaiCameraSdk.applyEffect('   ')).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
      message: 'packagePath must not be empty.',
    });
    expect(nativeMock.applyEffect).not.toHaveBeenCalled();
  });

  it('normalizes native Promise rejection metadata', async () => {
    nativeMock.applyEffect.mockRejectedValue({
      code: NosmaiErrorCode.effectApply,
      message: 'The SDK refused the package.',
      userInfo: { nativeCause: 'EffectException' },
    });

    const result = NosmaiCameraSdk.applyEffect('/effects/look.nosmai');
    await expect(result).rejects.toBeInstanceOf(NosmaiSdkError);
    await expect(result).rejects.toMatchObject({
      code: NosmaiErrorCode.effectApply,
      details: { nativeCause: 'EffectException' },
    });
  });

  it('returns false when a valid package path is not active', async () => {
    nativeMock.removeEffect.mockResolvedValue(false);

    await expect(
      NosmaiCameraSdk.removeEffect(' /effects/inactive.nosmai ')
    ).resolves.toBe(false);
    expect(nativeMock.removeEffect).toHaveBeenCalledWith(
      '/effects/inactive.nosmai'
    );
  });

  it('accepts a normalized filter object when removing an effect', async () => {
    nativeMock.removeEffect.mockResolvedValue(true);

    await expect(
      NosmaiCameraSdk.removeEffect({
        id: 'local-effect',
        name: 'Local effect',
        displayName: 'Local effect',
        description: '',
        path: ' /effects/local-effect.nosmai ',
        fileSize: 0,
        location: 'local',
        packageType: 'effect',
        isFree: true,
        isDownloaded: true,
        downloadCount: 0,
        price: 0,
      })
    ).resolves.toBe(true);
    expect(nativeMock.removeEffect).toHaveBeenCalledWith(
      '/effects/local-effect.nosmai'
    );
  });

  it('normalizes active state and optional package-info queries', async () => {
    nativeMock.getActiveEffects.mockResolvedValue({
      mode: 1,
      modeName: 'effectsFilters',
      activeEffectPath: '/effects/look.nosmai',
    });
    nativeMock.getActiveFilterInfo.mockResolvedValue(null);
    nativeMock.getActiveEffectInfo.mockResolvedValue({
      filterId: 'look',
      displayName: 'Look',
      path: '/effects/look.nosmai',
      filterType: 'effect',
    });

    await expect(NosmaiCameraSdk.getActiveEffects()).resolves.toMatchObject({
      mode: 1,
      modeName: 'effectsFilters',
      activeEffectPath: '/effects/look.nosmai',
    });
    await expect(
      NosmaiCameraSdk.getActiveFilterInfo()
    ).resolves.toBeUndefined();
    await expect(NosmaiCameraSdk.getActiveEffectInfo()).resolves.toMatchObject({
      id: 'look',
      displayName: 'Look',
      packageType: 'effect',
      path: '/effects/look.nosmai',
    });
  });

  it('normalizes authored effect parameter metadata and preserves optional pass IDs', async () => {
    nativeMock.getEffectParameters.mockResolvedValue({
      items: [
        {
          name: 'intensity',
          type: 'DOUBLE',
          displayName: 'Intensity',
          currentValue: 0.75,
          defaultValue: 0.5,
          hasRange: true,
          minValue: 0,
          maxValue: 1,
          options: [],
          passId: null,
        },
        {
          name: 'tint',
          type: 'color4',
          currentValue: [1, 0.5, 0.25, 1],
          defaultValue: [1, 1, 1, 1],
          hasRange: false,
        },
      ],
    });

    await expect(NosmaiCameraSdk.getEffectParameters()).resolves.toEqual([
      {
        name: 'intensity',
        type: 'float',
        displayName: 'Intensity',
        description: '',
        currentValue: 0.75,
        defaultValue: 0.5,
        hasRange: true,
        minValue: 0,
        maxValue: 1,
        options: [],
      },
      {
        name: 'tint',
        type: 'vector',
        displayName: 'tint',
        description: '',
        currentValue: [1, 0.5, 0.25, 1],
        defaultValue: [1, 1, 1, 1],
        hasRange: false,
        options: [],
      },
    ]);
  });

  it('bounds and sanitizes untrusted authored parameter metadata', async () => {
    nativeMock.getEffectParameters.mockResolvedValue({
      items: [
        {
          name: 'strength',
          type: 'float',
          displayName: 'Str\u0000ength',
          description: 'd'.repeat(2_100),
          currentValue: Number.NaN,
          defaultValue: Number.POSITIVE_INFINITY,
          hasRange: true,
          minValue: Number.NEGATIVE_INFINITY,
          maxValue: 1,
          options: Array.from(
            { length: 130 },
            (_, index) => `option\u0000-${index}`
          ),
          passId: -1,
        },
        {
          name: 'points',
          type: 'vector',
          currentValue: Array.from({ length: 65 }, () => 1),
          defaultValue: [0, Number.NaN],
        },
        {
          name: 'caption',
          type: 'string',
          currentValue: 'x'.repeat(17_000),
          defaultValue: 'ok\nvalue',
        },
        {
          name: 'n'.repeat(129),
          type: 'float',
          currentValue: 1,
        },
      ],
    });

    const parameters = await NosmaiCameraSdk.getEffectParameters();

    expect(parameters).toHaveLength(3);
    expect(parameters[0]).toMatchObject({
      name: 'strength',
      displayName: 'Strength',
      currentValue: null,
      defaultValue: null,
      hasRange: false,
    });
    expect(parameters[0]?.description).toHaveLength(2_048);
    expect(parameters[0]?.options).toHaveLength(128);
    expect(parameters[0]?.options[0]).toBe('option-0');
    expect(parameters[0]).not.toHaveProperty('passId');
    expect(parameters[1]).toMatchObject({
      currentValue: null,
      defaultValue: null,
    });
    expect(parameters[2]?.currentValue).toHaveLength(16_384);
    expect(parameters[2]?.defaultValue).toBe('okvalue');
  });

  it('reads and updates authored scalar and string parameters', async () => {
    nativeMock.getEffectParameterValue.mockResolvedValue(0);
    nativeMock.setEffectParameter.mockResolvedValue(true);
    nativeMock.setEffectParameterString.mockResolvedValue(true);

    await expect(
      NosmaiCameraSdk.getEffectParameterValue('  intensity  ')
    ).resolves.toBe(0);
    await expect(
      NosmaiCameraSdk.setEffectParameter('intensity', 0.8)
    ).resolves.toBe(true);
    await expect(
      NosmaiCameraSdk.setEffectParameterString('caption', '')
    ).resolves.toBe(true);

    expect(nativeMock.getEffectParameterValue).toHaveBeenCalledWith(
      'intensity'
    );
    expect(nativeMock.setEffectParameter).toHaveBeenCalledWith(
      'intensity',
      0.8
    );
    expect(nativeMock.setEffectParameterString).toHaveBeenCalledWith(
      'caption',
      ''
    );
  });

  it('rejects unsafe authored parameter inputs before native code', async () => {
    await expect(
      NosmaiCameraSdk.getEffectParameterValue('bad\u0000name')
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.setEffectParameter('intensity', Number.NaN)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.setEffectParameterString('caption', 'bad\nvalue')
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });

    expect(nativeMock.getEffectParameterValue).not.toHaveBeenCalled();
    expect(nativeMock.setEffectParameter).not.toHaveBeenCalled();
    expect(nativeMock.setEffectParameterString).not.toHaveBeenCalled();
  });

  it('requests and normalizes the complete local catalog by default', async () => {
    nativeMock.getLocalFilters.mockResolvedValue({
      items: [
        {
          path: ' /effects/z.nosmai ',
          displayName: 'Zulu',
          filterType: 'effect',
        },
        {
          path: '/filters/a.nosmai',
          displayName: 'Alpha',
          filterType: 'filter',
        },
      ],
    });

    await expect(NosmaiCameraSdk.getLocalFilters()).resolves.toMatchObject([
      { displayName: 'Alpha', path: '/filters/a.nosmai' },
      { displayName: 'Zulu', path: '/effects/z.nosmai' },
    ]);
    expect(nativeMock.getLocalFilters).toHaveBeenCalledWith(null);
  });

  it('passes a validated package type to the native catalog', async () => {
    nativeMock.getLocalFilters.mockResolvedValue({ items: [] });

    await expect(
      NosmaiCameraSdk.getLocalFilters('beauty_effect')
    ).resolves.toEqual([]);
    expect(nativeMock.getLocalFilters).toHaveBeenCalledWith('beauty_effect');
  });

  it('requests and normalizes bundled debug games', async () => {
    nativeMock.getDebugFilters.mockResolvedValue({
      items: [
        {
          id: 'dart-strike',
          displayName: 'Dart Strike',
          path: '/cache/dart_strike.nosmai',
          filterType: 'game',
        },
      ],
    });

    await expect(
      NosmaiCameraSdk.getDebugFilters('game')
    ).resolves.toMatchObject([
      { displayName: 'Dart Strike', packageType: 'game' },
    ]);
    expect(nativeMock.getDebugFilters).toHaveBeenCalledWith('game');
  });

  it('passes an installed catalog asset path back to apply unchanged', async () => {
    const assetPath = 'Nosmai_Filters/local-look/local-look.nosmai';
    nativeMock.getLocalFilters.mockResolvedValue({
      items: [{ path: assetPath, filterType: 'filter' }],
    });
    nativeMock.applyEffect.mockResolvedValue(true);

    const [installed] = await NosmaiCameraSdk.getLocalFilters('filter');
    await expect(
      NosmaiCameraSdk.applyEffect(installed?.path ?? '')
    ).resolves.toBe(true);
    expect(nativeMock.applyEffect).toHaveBeenCalledWith(assetPath);
  });

  it('rejects an unsupported package type before entering native code', async () => {
    await expect(
      NosmaiCameraSdk.getLocalFilters('unsupported' as never)
    ).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
      message:
        'packageType must be filter, effect, background, beauty_effect, or game.',
    });
    expect(nativeMock.getLocalFilters).not.toHaveBeenCalled();
  });

  it('preserves the stable native local-catalog failure code', async () => {
    nativeMock.getLocalFilters.mockRejectedValue({
      code: NosmaiErrorCode.localCatalog,
      message: 'The local catalog could not be read.',
    });

    await expect(
      NosmaiCameraSdk.getLocalFilters('filter')
    ).rejects.toMatchObject({
      code: NosmaiErrorCode.localCatalog,
      message: 'The local catalog could not be read.',
    });
  });

  it('fetches an atomic, de-duplicated cloud catalog with safe defaults', async () => {
    nativeMock.getCloudFilters.mockResolvedValue({
      items: [
        {
          filterId: 'bunny-face',
          id: 'backend-bunny-face',
          displayName: 'Bunny Face',
          description: 'AR ears and nose',
          path: '',
          filterType: 'effect',
          isFree: true,
          isDownloaded: false,
          previewUrl: 'https://cdn.example/bunny.png',
        },
        {
          filterId: 'bunny-face',
          displayName: 'Duplicate',
          path: '',
          filterType: 'effect',
        },
      ],
      pagination: {
        currentPage: 1,
        totalPages: 3,
        totalItems: 41,
        itemsPerPage: 20,
        hasNextPage: true,
        hasPreviousPage: false,
      },
    });

    await expect(NosmaiCameraSdk.getCloudFilters()).resolves.toMatchObject({
      filters: [
        {
          id: 'bunny-face',
          filterId: 'bunny-face',
          backendId: 'backend-bunny-face',
          displayName: 'Bunny Face',
          location: 'cloud',
          packageType: 'effect',
          isDownloaded: false,
        },
      ],
      pagination: {
        currentPage: 1,
        totalPages: 3,
        totalItems: 41,
        itemsPerPage: 20,
        hasNextPage: true,
        hasPreviousPage: false,
      },
    });
    expect(nativeMock.getCloudFilters).toHaveBeenCalledWith(
      null,
      '2.0.0',
      0,
      20,
      true
    );
  });

  it('returns the native cloud license capability unchanged', async () => {
    nativeMock.isCloudFilterEnabled.mockResolvedValue(true);

    await expect(NosmaiCameraSdk.isCloudFilterEnabled()).resolves.toBe(true);
    expect(nativeMock.isCloudFilterEnabled).toHaveBeenCalledTimes(1);
  });

  it('requests and preserves game cloud packages', async () => {
    nativeMock.getCloudFilters.mockResolvedValue({
      items: [
        {
          filterId: 'tap-runner',
          displayName: 'Tap Runner',
          path: '',
          filterType: 'games',
        },
      ],
      pagination: {
        currentPage: 1,
        totalPages: 1,
        totalItems: 1,
        itemsPerPage: 20,
        hasNextPage: false,
        hasPreviousPage: false,
      },
    });

    await expect(
      NosmaiCameraSdk.getCloudFilters({ packageType: 'game' })
    ).resolves.toMatchObject({
      filters: [{ filterId: 'tap-runner', packageType: 'game' }],
    });
    expect(nativeMock.getCloudFilters).toHaveBeenCalledWith(
      'game',
      '2.0.0',
      0,
      20,
      true
    );
  });

  it('validates and forwards game input controls', async () => {
    nativeMock.isGameReady.mockResolvedValue(true);
    nativeMock.sendGameTap.mockResolvedValue(true);
    nativeMock.sendGameInput.mockResolvedValue(true);
    nativeMock.pauseGame.mockResolvedValue(undefined);
    nativeMock.resumeGame.mockResolvedValue(undefined);
    nativeMock.restartGame.mockResolvedValue(undefined);

    await expect(NosmaiCameraSdk.isGameReady()).resolves.toBe(true);
    await expect(NosmaiCameraSdk.sendGameTap(0.25, 0.75)).resolves.toBe(true);
    await expect(
      NosmaiCameraSdk.sendGameInput('jump', 0.5, 0.4, 2)
    ).resolves.toBe(true);
    await NosmaiCameraSdk.pauseGame();
    await NosmaiCameraSdk.resumeGame();
    await NosmaiCameraSdk.restartGame();

    expect(nativeMock.sendGameTap).toHaveBeenCalledWith(0.25, 0.75);
    expect(nativeMock.sendGameInput).toHaveBeenCalledWith('jump', 0.5, 0.4, 2);
    await expect(NosmaiCameraSdk.sendGameTap(-0.1, 0.5)).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
    });
    await expect(
      NosmaiCameraSdk.sendGameInput(' ', 0.5, 0.5)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('uses single-page cloud pagination defaults when a page is supplied', async () => {
    nativeMock.getCloudFilters.mockResolvedValue({
      items: [],
      pagination: {
        currentPage: 2,
        totalPages: 2,
        totalItems: 11,
        itemsPerPage: 10,
        hasNextPage: false,
        hasPreviousPage: true,
      },
    });

    await expect(
      NosmaiCameraSdk.getCloudFilters({
        packageType: 'background',
        page: 2,
        limit: 10,
      })
    ).resolves.toMatchObject({ filters: [] });
    expect(nativeMock.getCloudFilters).toHaveBeenCalledWith(
      'background',
      '2.0.0',
      2,
      10,
      false
    );
  });

  it('rejects invalid cloud pagination and unsafe download identifiers', async () => {
    await expect(
      NosmaiCameraSdk.getCloudFilters({ page: 0 })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.getCloudFilters({ limit: 101 })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.getCloudFilters({ page: 2_147_483_648 })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.getCloudFilters('all' as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.downloadCloudFilter('../secret')
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.downloadCloudFilter(null as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    expect(nativeMock.getCloudFilters).not.toHaveBeenCalled();
    expect(nativeMock.downloadCloudFilter).not.toHaveBeenCalled();
  });

  it('downloads and removes cloud packages by their canonical filter ID', async () => {
    nativeMock.downloadCloudFilter.mockResolvedValue({
      filterId: 'bunny-face',
      path: '/app/cache/NosmaiCloudFilters/bunny-face.nosmai',
      alreadyDownloaded: false,
    });
    nativeMock.removeCloudFilter.mockResolvedValue(true);
    const filter = {
      id: 'bunny-face',
      filterId: 'bunny-face',
      name: 'Bunny Face',
      displayName: 'Bunny Face',
      description: '',
      path: '',
      fileSize: 0,
      location: 'cloud' as const,
      packageType: 'effect' as const,
      isFree: true,
      isDownloaded: false,
      downloadCount: 0,
      price: 0,
    };

    await expect(NosmaiCameraSdk.downloadCloudFilter(filter)).resolves.toEqual({
      filterId: 'bunny-face',
      path: '/app/cache/NosmaiCloudFilters/bunny-face.nosmai',
      alreadyDownloaded: false,
    });
    await expect(NosmaiCameraSdk.removeCloudFilter(filter)).resolves.toBe(true);
    expect(nativeMock.downloadCloudFilter).toHaveBeenCalledWith('bunny-face');
    expect(nativeMock.removeCloudFilter).toHaveBeenCalledWith('bunny-face');
  });

  it('forwards beauty levels on the shared zero-to-one scale', async () => {
    nativeMock.setBeautyValue.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setSkinSmoothing(0.25);
    await NosmaiCameraSdk.setSkinWhitening(0.5);
    await NosmaiCameraSdk.setTeethWhitening(0.75);

    expect(nativeMock.setBeautyValue.mock.calls).toEqual([
      ['skinSmoothing', 0.25],
      ['skinWhitening', 0.5],
      ['teethWhitening', 0.75],
    ]);
    await expect(NosmaiCameraSdk.setSkinSmoothing(1.01)).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
    });
  });

  it('returns native beauty and advanced-filter license capabilities', async () => {
    nativeMock.isBeautyEffectEnabled.mockResolvedValue(true);
    nativeMock.isAdvancedFiltersEnabled.mockResolvedValue(false);

    await expect(NosmaiCameraSdk.isBeautyEffectEnabled()).resolves.toBe(true);
    await expect(NosmaiCameraSdk.isAdvancedFiltersEnabled()).resolves.toBe(
      false
    );
  });

  it('applies typed makeup with custom RGB and keeps eyelash colorless', async () => {
    nativeMock.applyMakeup.mockResolvedValue(undefined);

    await NosmaiCameraSdk.applyMakeup({
      type: 'lipstick',
      style: 'matte',
      color: { red: 0.8, green: 0.2, blue: 0.3 },
      intensity: 0.6,
    });
    await NosmaiCameraSdk.applyMakeup({
      type: 'eyelash',
      style: 'wispy',
    });

    expect(nativeMock.applyMakeup.mock.calls).toEqual([
      ['lipstick', 'matte', 0.8, 0.2, 0.3, 0.6],
      ['eyelash', 'wispy', 0, 0, 0, 1],
    ]);
    await expect(
      NosmaiCameraSdk.applyMakeup({
        type: 'eyelash',
        style: 'matte',
      } as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('updates, queries, and removes one makeup layer without broad clearing', async () => {
    nativeMock.setMakeupIntensity.mockResolvedValue(undefined);
    nativeMock.isMakeupActive.mockResolvedValue(true);
    nativeMock.removeMakeup.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setMakeupIntensity('eyeshadow', 0.4);
    await expect(NosmaiCameraSdk.isMakeupActive('eyeshadow')).resolves.toBe(
      true
    );
    await NosmaiCameraSdk.removeMakeup('eyeshadow');

    expect(nativeMock.setMakeupIntensity).toHaveBeenCalledWith(
      'eyeshadow',
      0.4
    );
    expect(nativeMock.isMakeupActive).toHaveBeenCalledWith('eyeshadow');
    expect(nativeMock.removeMakeup).toHaveBeenCalledWith('eyeshadow');
    expect(nativeMock.clearMakeup).not.toHaveBeenCalled();
  });

  it('preserves signed, type-specific face reshape ranges', async () => {
    nativeMock.setReshape.mockResolvedValue(undefined);

    await expect(
      NosmaiCameraSdk.setReshape('faceSlim', -1.2)
    ).resolves.toBeUndefined();
    expect(nativeMock.setReshape).toHaveBeenCalledWith('faceSlim', -1.2);
    await expect(
      NosmaiCameraSdk.setReshape('nose', 0.51)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('forwards eye color and intensity without platform-specific scaling', async () => {
    nativeMock.setEyeColor.mockResolvedValue(undefined);
    nativeMock.setEyeColorIntensity.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setEyeColor({ red: 0.1, green: 0.6, blue: 0.9 }, 0.4);
    await NosmaiCameraSdk.setEyeColorIntensity(0.7);

    expect(nativeMock.setEyeColor).toHaveBeenCalledWith(0.1, 0.6, 0.9, 0.4);
    expect(nativeMock.setEyeColorIntensity).toHaveBeenCalledWith(0.7);
  });

  it('returns the native eye-color activity query unchanged', async () => {
    nativeMock.isEyeColorActive.mockResolvedValue(true);

    await expect(NosmaiCameraSdk.isEyeColorActive()).resolves.toBe(true);
    expect(nativeMock.isEyeColorActive).toHaveBeenCalledTimes(1);
  });

  it('forwards every HSB channel instead of dropping saturation or brightness', async () => {
    nativeMock.setColorAdjustment.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setHsb({
      hue: -45,
      saturation: 1.25,
      brightness: 0.8,
    });

    expect(nativeMock.setColorAdjustment).toHaveBeenCalledWith(
      'hsb',
      -45,
      1.25,
      0.8
    );
  });

  it('forwards brightness, RGB, sharpening, and grayscale controls', async () => {
    nativeMock.setColorAdjustment.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setBrightness(-0.15);
    await NosmaiCameraSdk.setRgbAdjustment({ red: 1.1, green: 0.9, blue: 1 });
    await NosmaiCameraSdk.setSharpening(0.3);
    await NosmaiCameraSdk.setGrayscale(true);

    expect(nativeMock.setColorAdjustment.mock.calls).toEqual([
      ['brightness', -0.15, 0, 0],
      ['rgb', 1.1, 0.9, 1],
      ['sharpening', 0.3, 0, 0],
      ['grayscale', 1, 0, 0],
    ]);
    await expect(
      NosmaiCameraSdk.setRgbAdjustment({ red: 2.01, green: 1, blue: 1 })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('validates the cross-platform color-control ranges', async () => {
    nativeMock.setColorAdjustment.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setContrast(2);
    await NosmaiCameraSdk.setHue(360);
    await NosmaiCameraSdk.setWhiteBalance({ temperature: 6500, tint: -0.2 });

    expect(nativeMock.setColorAdjustment.mock.calls).toEqual([
      ['contrast', 2, 0, 0],
      ['hue', 360, 0, 0],
      ['whiteBalance', 6500, -0.2, 0],
    ]);
    await expect(NosmaiCameraSdk.setContrast(2.01)).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
    });
    await expect(NosmaiCameraSdk.setHue(-1)).rejects.toMatchObject({
      code: NosmaiErrorCode.invalidArgument,
    });
    await expect(
      NosmaiCameraSdk.setWhiteBalance({ temperature: 9000, tint: 0 })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('serializes all four manual background modes', async () => {
    nativeMock.setBackground.mockResolvedValue(undefined);

    await NosmaiCameraSdk.setBackground({ mode: 'blur', blurStrength: 0.35 });
    await NosmaiCameraSdk.setBackground({
      mode: 'color',
      color: { red: 0.1, green: 0.2, blue: 0.3, alpha: 0.4 },
    });
    await NosmaiCameraSdk.setBackground({
      mode: 'image',
      uri: 'file:///tmp/background.jpg',
    });
    await NosmaiCameraSdk.setBackground({
      mode: 'video',
      uri: 'file://localhost/tmp/background.mp4',
    });

    expect(nativeMock.setBackground.mock.calls).toEqual([
      ['blur', null, 0, 0, 0, 1, 0.35],
      ['color', null, 0.1, 0.2, 0.3, 0.4, 0],
      ['image', 'file:///tmp/background.jpg', 0, 0, 0, 1, 0],
      ['video', 'file://localhost/tmp/background.mp4', 0, 0, 0, 1, 0],
    ]);
    await expect(
      NosmaiCameraSdk.setBackground({
        mode: 'image',
        uri: 'https://example.com/background.jpg',
      })
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
  });

  it('keeps visual clear operations scoped to their native methods', async () => {
    nativeMock.clearBeauty.mockResolvedValue(undefined);
    nativeMock.clearMakeup.mockResolvedValue(undefined);
    nativeMock.clearReshapes.mockResolvedValue(undefined);
    nativeMock.removeEyeColor.mockResolvedValue(undefined);
    nativeMock.resetColorAdjustments.mockResolvedValue(undefined);
    nativeMock.clearBackground.mockResolvedValue(undefined);

    await NosmaiCameraSdk.clearBeauty();
    await NosmaiCameraSdk.clearMakeup();
    await NosmaiCameraSdk.clearReshaping();
    await NosmaiCameraSdk.removeEyeColor();
    await NosmaiCameraSdk.resetColorAdjustments();
    await NosmaiCameraSdk.clearBackground();

    expect(nativeMock.clearBeauty).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearMakeup).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearReshapes).toHaveBeenCalledTimes(1);
    expect(nativeMock.removeEyeColor).toHaveBeenCalledTimes(1);
    expect(nativeMock.resetColorAdjustments).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearBackground).toHaveBeenCalledTimes(1);
    expect(nativeMock.clearAll).not.toHaveBeenCalled();
  });

  it('normalizes license events and returns the native subscription', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onLicenseStatusChanged.mockImplementation((listener) => {
      listener('VALID');
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addLicenseStatusChangedListener(listener)).toBe(
      subscription
    );
    expect(listener).toHaveBeenCalledWith('valid');
  });

  it('normalizes active-effects events and returns the native subscription', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onActiveEffectsChanged.mockImplementation((listener) => {
      listener({
        mode: 2,
        modeName: 'filtersBackground',
        backgroundActive: true,
        activeBackgroundPackagePath: '/backgrounds/studio.nosmai',
        hasBuiltInBeauty: true,
        hasManualBackgroundConfig: true,
      });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addActiveEffectsChangedListener(listener)).toBe(
      subscription
    );
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({
        modeName: 'filtersBackground',
        hasBackground: true,
        activeBackgroundPath: '/backgrounds/studio.nosmai',
        hasBuiltInBeauty: true,
        hasManualBackground: true,
      })
    );
  });

  it('normalizes asynchronous error events', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onError.mockImplementation((listener) => {
      listener({
        code: NosmaiErrorCode.cameraDisconnected,
        message: 'Camera disconnected.',
        details: { nativeCause: 'CameraDevice' },
      });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addErrorListener(listener)).toBe(subscription);
    expect(listener).toHaveBeenCalledWith({
      code: NosmaiErrorCode.cameraDisconnected,
      message: 'Camera disconnected.',
      details: { nativeCause: 'CameraDevice' },
    });
  });

  it('delivers valid recording progress and ignores malformed events', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onRecordingProgress.mockImplementation((listener) => {
      listener({ durationSeconds: -1 });
      listener({ durationSeconds: 2.5 });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addRecordingProgressListener(listener)).toBe(
      subscription
    );
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ durationSeconds: 2.5 });
  });

  it('delivers normalized cloud-download progress and drops malformed events', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onDownloadProgress.mockImplementation((listener) => {
      listener({ filterId: '   ', progress: 0.5 });
      listener({ filterId: 'bunny-face', progress: 1.1 });
      listener({ filterId: 'bunny-face', progress: 0.65 });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addDownloadProgressListener(listener)).toBe(
      subscription
    );
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({
      filterId: 'bunny-face',
      progress: 0.65,
    });
  });

  it('delivers valid game events and drops malformed payloads', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onGameEvent.mockImplementation((listener) => {
      listener({ event: '', game: 'runner', sequence: 1, data: {} });
      listener({
        event: 'score',
        game: 'runner',
        sequence: 7,
        data: { score: 42 },
      });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addGameEventListener(listener)).toBe(subscription);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({
      event: 'score',
      game: 'runner',
      sequence: 7,
      data: { score: 42 },
    });
  });
});
