jest.mock('../NativeNosmaiCameraSdk', () => ({
  __esModule: true,
  default: {
    startFrameStream: jest.fn(),
    stopFrameStream: jest.fn(),
    getLatestFrame: jest.fn(),
    isFrameStreamActive: jest.fn(),
    onFrameAvailable: jest.fn(),
  },
}));

import NativeNosmaiCameraSdk from '../NativeNosmaiCameraSdk';
import { NosmaiCameraSdk } from '../NosmaiCameraSdk';
import { NosmaiErrorCode } from '../errors';

const nativeMock = NativeNosmaiCameraSdk as jest.Mocked<
  typeof NativeNosmaiCameraSdk
>;

const rgbaFrame = {
  sequence: 1,
  timestampSeconds: 2.25,
  width: 1,
  height: 1,
  format: 'rgba8888',
  colorRange: 'full',
  byteLength: 4,
  planes: [
    {
      offset: 0,
      byteLength: 4,
      bytesPerRow: 4,
      width: 1,
      height: 1,
    },
  ],
  droppedFrames: 0,
  dataBase64: 'AAECAw==',
};

describe('processed frame stream JavaScript contract', () => {
  beforeEach(() => {
    jest.resetAllMocks();
  });

  it('uses a conservative default rate and forwards an explicit valid rate', async () => {
    nativeMock.startFrameStream.mockResolvedValue(undefined);

    await expect(NosmaiCameraSdk.startFrameStream()).resolves.toBeUndefined();
    expect(nativeMock.startFrameStream).toHaveBeenLastCalledWith(2);

    await expect(
      NosmaiCameraSdk.startFrameStream({ maxFramesPerSecond: 5 })
    ).resolves.toBeUndefined();
    expect(nativeMock.startFrameStream).toHaveBeenLastCalledWith(5);
  });

  it.each([0, 6, 1.5, Number.NaN])(
    'rejects unsafe maxFramesPerSecond %p before native entry',
    async (maxFramesPerSecond) => {
      await expect(
        NosmaiCameraSdk.startFrameStream({ maxFramesPerSecond })
      ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
      expect(nativeMock.startFrameStream).not.toHaveBeenCalled();
    }
  );

  it('rejects a malformed options container before native entry', async () => {
    await expect(
      NosmaiCameraSdk.startFrameStream(null as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    await expect(
      NosmaiCameraSdk.startFrameStream([] as never)
    ).rejects.toMatchObject({ code: NosmaiErrorCode.invalidArgument });
    expect(nativeMock.startFrameStream).not.toHaveBeenCalled();
  });

  it('atomically maps an empty native slot to undefined', async () => {
    nativeMock.getLatestFrame.mockResolvedValue(null);

    await expect(NosmaiCameraSdk.getLatestFrame()).resolves.toBeUndefined();
  });

  it('strictly normalizes an explicitly consumed native frame', async () => {
    nativeMock.getLatestFrame.mockResolvedValue(rgbaFrame);

    await expect(NosmaiCameraSdk.getLatestFrame()).resolves.toEqual(rgbaFrame);
  });

  it('forwards stop and active-state queries', async () => {
    nativeMock.stopFrameStream.mockResolvedValue(undefined);
    nativeMock.isFrameStreamActive.mockResolvedValue(true);

    await expect(NosmaiCameraSdk.stopFrameStream()).resolves.toBeUndefined();
    await expect(NosmaiCameraSdk.isFrameStreamActive()).resolves.toBe(true);
  });

  it('forwards valid availability metadata and ignores malformed events', () => {
    const subscription = { remove: jest.fn() };
    nativeMock.onFrameAvailable.mockImplementation((listener) => {
      listener(rgbaFrame);
      listener({ ...rgbaFrame, planes: [] });
      return subscription;
    });
    const listener = jest.fn();

    expect(NosmaiCameraSdk.addFrameAvailableListener(listener)).toBe(
      subscription
    );
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith(
      expect.objectContaining({ sequence: 1, format: 'rgba8888' })
    );
  });
});
