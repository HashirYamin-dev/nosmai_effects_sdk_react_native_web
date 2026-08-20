import {
  normalizeActiveEffects,
  normalizeCloudDownloadResult,
  normalizeCloudFilterPage,
  normalizeDownloadProgress,
  normalizeFilter,
  normalizeGallerySaveResult,
  normalizeLicenseStatus,
  normalizeLocalFilters,
  normalizeNativeError,
  normalizePhotoResult,
  normalizeRecordingDuration,
  normalizeRecordingProgress,
  normalizeRecordingResult,
} from '../normalizers';
import { NosmaiErrorCode } from '../errors';

describe('native payload normalization', () => {
  it('normalizes license statuses without leaking native casing', () => {
    expect(normalizeLicenseStatus('VALID')).toBe('valid');
    expect(normalizeLicenseStatus('EXPIRED')).toBe('expired');
    expect(normalizeLicenseStatus('future_status')).toBe('unknown');
  });

  it('normalizes cross-platform active effect field aliases', () => {
    expect(
      normalizeActiveEffects({
        mode: 2,
        modeName: 'filtersBackground',
        backgroundActive: true,
        activeBackgroundPackagePath: '/effects/background.nosmai',
        backgroundSource: 3,
        backgroundSourceName: 'package',
        hasBeautyEffect: true,
        hasBuiltInBeauty: 1,
        hasManualBackgroundConfig: 'true',
      })
    ).toMatchObject({
      mode: 2,
      modeName: 'filtersBackground',
      hasBackground: true,
      activeBackgroundPath: '/effects/background.nosmai',
      backgroundSource: 3,
      backgroundSourceName: 'package',
      hasBeautyEffect: true,
      hasBuiltInBeauty: true,
      hasManualBackground: true,
    });
  });

  it('creates a stable filter model from a minimal native payload', () => {
    expect(
      normalizeFilter({
        filterId: 'soft-light',
        displayName: 'Soft Light',
        path: '/filters/soft-light.nosmai',
        filterType: 'filter',
        previewPath: 'Nosmai_Filters/soft-light/soft-light_preview.png',
      })
    ).toMatchObject({
      id: 'soft-light',
      name: 'Soft Light',
      displayName: 'Soft Light',
      path: '/filters/soft-light.nosmai',
      packageType: 'filter',
      location: 'local',
      previewUrl: 'Nosmai_Filters/soft-light/soft-light_preview.png',
    });
  });

  it('uses the package path when native catalog identifiers are blank', () => {
    expect(
      normalizeFilter({
        id: '',
        filterId: '',
        displayName: 'Local package',
        path: 'Nosmai_Filters/local/local.nosmai',
      })
    ).toMatchObject({
      id: 'Nosmai_Filters/local/local.nosmai',
      name: 'Local package',
    });
  });

  it('normalizes, filters, de-duplicates, and sorts a local catalog', () => {
    const duplicateA = {
      id: 'second-choice',
      displayName: 'Zulu B',
      path: '/effects/zulu.nosmai',
      filterType: 'effect',
    };
    const duplicateB = {
      id: 'first-choice',
      displayName: 'Zulu A',
      path: ' /effects/zulu.nosmai ',
      filterType: 'effect',
    };
    const payload = {
      items: [
        duplicateA,
        null,
        { displayName: 'Missing path' },
        { path: '   ', displayName: 'Blank path' },
        {
          filterId: 'alpha',
          displayName: 'alpha',
          path: '/filters/alpha.nosmai',
          packageType: 'filter',
        },
        duplicateB,
      ],
    };

    expect(normalizeLocalFilters(payload)).toMatchObject([
      {
        id: 'alpha',
        displayName: 'alpha',
        path: '/filters/alpha.nosmai',
        packageType: 'filter',
      },
      {
        id: 'first-choice',
        displayName: 'Zulu A',
        path: '/effects/zulu.nosmai',
        packageType: 'effect',
      },
    ]);

    expect(
      normalizeLocalFilters({ items: [...payload.items].reverse() })
    ).toEqual(normalizeLocalFilters(payload));
  });

  it('returns an empty catalog for a malformed native envelope', () => {
    expect(normalizeLocalFilters(null)).toEqual([]);
    expect(normalizeLocalFilters({ items: 'not-an-array' })).toEqual([]);
    expect(normalizeLocalFilters([{ path: '/filters/direct.nosmai' }])).toEqual(
      []
    );
  });

  it('normalizes cloud items and de-duplicates them by downloadable ID', () => {
    expect(
      normalizeCloudFilterPage({
        items: [
          {
            filterId: 'look-1',
            id: 'backend-9',
            displayName: 'Look One',
            path: '/cache/look-1.nosmai',
            filterType: 'filter',
            isDownloaded: true,
          },
          {
            filterId: 'look-1',
            displayName: 'Duplicate',
            path: '',
            filterType: 'filter',
          },
          {
            filterId: 'look-2',
            displayName: 'Look Two',
            path: '',
            filterType: 'effect',
            isDownloaded: false,
          },
        ],
        pagination: {
          currentPage: 1,
          totalPages: 1,
          totalItems: 2,
          itemsPerPage: 20,
          hasNextPage: false,
          hasPreviousPage: false,
        },
      })
    ).toMatchObject({
      filters: [
        {
          id: 'look-1',
          filterId: 'look-1',
          backendId: 'backend-9',
          location: 'cloud',
          path: '/cache/look-1.nosmai',
          isDownloaded: true,
        },
        {
          id: 'look-2',
          filterId: 'look-2',
          location: 'cloud',
          path: '',
          packageType: 'effect',
          isDownloaded: false,
        },
      ],
      pagination: {
        currentPage: 1,
        totalPages: 1,
        totalItems: 2,
        itemsPerPage: 20,
        hasNextPage: false,
        hasPreviousPage: false,
      },
    });
  });

  it('rejects malformed atomic cloud catalog payloads', () => {
    expect(() =>
      normalizeCloudFilterPage({
        items: [],
        pagination: {
          currentPage: 1,
          totalPages: 1,
          totalItems: 0,
          itemsPerPage: 20,
          hasNextPage: 'false',
          hasPreviousPage: false,
        },
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(() =>
      normalizeCloudFilterPage({
        items: [
          {
            filterId: 'downloaded-relative-path',
            path: 'cache/downloaded-relative-path.nosmai',
            isDownloaded: true,
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
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(() =>
      normalizeCloudFilterPage({
        items: [
          {
            filterId: 'downloaded-without-path',
            isDownloaded: true,
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
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });

  it('strictly normalizes cloud downloads and tolerantly filters progress', () => {
    expect(
      normalizeCloudDownloadResult(
        {
          filterId: 'look-1',
          path: '/cache/look-1.nosmai',
          alreadyDownloaded: true,
        },
        'look-1'
      )
    ).toEqual({
      filterId: 'look-1',
      path: '/cache/look-1.nosmai',
      alreadyDownloaded: true,
    });

    expect(() =>
      normalizeCloudDownloadResult(
        {
          filterId: 'other-filter',
          path: '/cache/look-1.nosmai',
          alreadyDownloaded: false,
        },
        'look-1'
      )
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
    expect(() =>
      normalizeCloudDownloadResult(
        {
          filterId: 'look-1',
          path: 'relative/look-1.nosmai',
          alreadyDownloaded: false,
        },
        'look-1'
      )
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(
      normalizeDownloadProgress({ filterId: 'look-1', progress: 0.75 })
    ).toEqual({ filterId: 'look-1', progress: 0.75 });
    expect(
      normalizeDownloadProgress({ filterId: 'look-1', progress: -0.1 })
    ).toBeUndefined();
    expect(
      normalizeDownloadProgress({ filterId: '', progress: 0.5 })
    ).toBeUndefined();
    expect(
      normalizeDownloadProgress({ filterId: '   ', progress: 0.5 })
    ).toBeUndefined();
  });

  it('normalizes malformed error payloads safely', () => {
    expect(normalizeNativeError(null)).toEqual({
      code: 'E_NATIVE_FAILURE',
      message: 'The native Nosmai SDK operation failed.',
      details: undefined,
    });
  });

  it('strictly normalizes processed photo and recording files', () => {
    expect(
      normalizePhotoResult({
        uri: 'file:///tmp/photo.jpg',
        width: 720,
        height: 1280,
        fileSizeBytes: 32_768,
        mimeType: 'image/jpeg',
      })
    ).toEqual({
      uri: 'file:///tmp/photo.jpg',
      width: 720,
      height: 1280,
      fileSizeBytes: 32_768,
      mimeType: 'image/jpeg',
    });

    expect(
      normalizeRecordingResult({
        uri: 'file:///tmp/video.mp4',
        durationSeconds: 3.75,
        fileSizeBytes: 2_000_000,
        mimeType: 'video/mp4',
        hasAudio: false,
      })
    ).toEqual({
      uri: 'file:///tmp/video.mp4',
      durationSeconds: 3.75,
      fileSizeBytes: 2_000_000,
      mimeType: 'video/mp4',
      hasAudio: false,
    });
  });

  it('rejects unsafe or malformed native media results', () => {
    expect(() =>
      normalizePhotoResult({
        uri: '/tmp/photo.jpg',
        width: 720,
        height: 1280,
        fileSizeBytes: 32_768,
        mimeType: 'image/jpeg',
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(() =>
      normalizeRecordingResult({
        uri: 'file:///tmp/video.mp4',
        durationSeconds: Number.NaN,
        fileSizeBytes: 2_000_000,
        mimeType: 'video/quicktime',
        hasAudio: true,
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });

  it('normalizes only the requested persistent gallery media type', () => {
    expect(
      normalizeGallerySaveResult(
        { uri: 'content://media/images/7', mediaType: 'photo' },
        'photo'
      )
    ).toEqual({
      uri: 'content://media/images/7',
      mediaType: 'photo',
    });
    expect(
      normalizeGallerySaveResult(
        { uri: 'ph://video-id', mediaType: 'video' },
        'video'
      )
    ).toEqual({ uri: 'ph://video-id', mediaType: 'video' });

    expect(() =>
      normalizeGallerySaveResult(
        { uri: 'content://media/videos/8', mediaType: 'video' },
        'photo'
      )
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
    expect(() =>
      normalizeGallerySaveResult(
        { uri: 'content://', mediaType: 'photo' },
        'photo'
      )
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
    expect(() =>
      normalizeGallerySaveResult({ uri: 'ph://', mediaType: 'video' }, 'video')
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });

  it('strictly validates duration queries and drops malformed progress', () => {
    expect(normalizeRecordingDuration(1.75)).toBe(1.75);
    expect(() => normalizeRecordingDuration(-1)).toThrow(
      expect.objectContaining({ code: NosmaiErrorCode.nativeFailure })
    );

    expect(normalizeRecordingProgress({ durationSeconds: 0 })).toEqual({
      durationSeconds: 0,
    });
    expect(
      normalizeRecordingProgress({ durationSeconds: -0.1 })
    ).toBeUndefined();
    expect(
      normalizeRecordingProgress({ durationSeconds: '1' })
    ).toBeUndefined();
    expect(normalizeRecordingProgress(null)).toBeUndefined();
  });
});
