import {
  applyNosmaiInfoPlist,
  DEFAULT_CAMERA_PERMISSION,
  DEFAULT_MICROPHONE_PERMISSION,
  DEFAULT_PHOTO_LIBRARY_ADD_PERMISSION,
} from '../withIos';

describe('Nosmai Expo iOS configuration', () => {
  it('adds safe defaults when the host app has no descriptions', () => {
    expect(applyNosmaiInfoPlist({}, {})).toEqual({
      NSCameraUsageDescription: DEFAULT_CAMERA_PERMISSION,
      NSMicrophoneUsageDescription: DEFAULT_MICROPHONE_PERMISSION,
      NSPhotoLibraryAddUsageDescription: DEFAULT_PHOTO_LIBRARY_ADD_PERMISSION,
    });
  });

  it('preserves existing host descriptions when options are omitted', () => {
    const infoPlist = {
      NSCameraUsageDescription: 'Existing camera text',
      NSMicrophoneUsageDescription: 'Existing microphone text',
      NSPhotoLibraryAddUsageDescription: 'Existing photo text',
    };

    expect(applyNosmaiInfoPlist(infoPlist, {})).toEqual(infoPlist);
  });

  it('uses explicit descriptions and remains idempotent', () => {
    const props = {
      cameraPermission: 'Custom camera text',
      microphonePermission: 'Custom microphone text',
      photoLibraryAddPermission: 'Custom photo text',
    };
    const first = applyNosmaiInfoPlist({}, props);
    const second = applyNosmaiInfoPlist(first, props);

    expect(second).toEqual({
      NSCameraUsageDescription: 'Custom camera text',
      NSMicrophoneUsageDescription: 'Custom microphone text',
      NSPhotoLibraryAddUsageDescription: 'Custom photo text',
    });
  });

  it('rejects empty permission descriptions', () => {
    expect(() => applyNosmaiInfoPlist({}, { cameraPermission: '   ' })).toThrow(
      'cameraPermission'
    );
  });
});
