import { NosmaiErrorCode } from '../errors';
import {
  NOSMAI_MAX_RAW_FRAME_BYTES,
  normalizeFrameAvailable,
  normalizeRawFrame,
} from '../frameNormalizers';

const rgbaMetadata = {
  sequence: 4,
  timestampSeconds: 12.5,
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
  droppedFrames: 2,
};

describe('processed raw-frame normalization', () => {
  it('strictly normalizes a consumed frame and preserves its plane layout', () => {
    expect(
      normalizeRawFrame({
        ...rgbaMetadata,
        dataBase64: 'AAECAw==',
      })
    ).toEqual({
      ...rgbaMetadata,
      dataBase64: 'AAECAw==',
    });
  });

  it('accepts metadata-only availability events', () => {
    expect(normalizeFrameAvailable(rgbaMetadata)).toEqual(rgbaMetadata);
  });

  it('accepts the Android I420 three-plane layout', () => {
    expect(
      normalizeRawFrame({
        sequence: 1,
        timestampSeconds: 1,
        width: 2,
        height: 2,
        format: 'i420',
        colorRange: 'full',
        byteLength: 6,
        planes: [
          { offset: 0, byteLength: 4, bytesPerRow: 2, width: 2, height: 2 },
          { offset: 4, byteLength: 1, bytesPerRow: 1, width: 1, height: 1 },
          { offset: 5, byteLength: 1, bytesPerRow: 1, width: 1, height: 1 },
        ],
        droppedFrames: 0,
        dataBase64: 'AAAAAAAA',
      })
    ).toMatchObject({ format: 'i420', byteLength: 6 });
  });

  it('accepts the iOS NV12 two-plane layout and its interleaved UV stride', () => {
    expect(
      normalizeFrameAvailable({
        sequence: 1,
        timestampSeconds: 1,
        width: 4,
        height: 2,
        format: 'nv12',
        colorRange: 'video',
        byteLength: 12,
        planes: [
          { offset: 0, byteLength: 8, bytesPerRow: 4, width: 4, height: 2 },
          { offset: 8, byteLength: 4, bytesPerRow: 4, width: 2, height: 1 },
        ],
        droppedFrames: 0,
      })
    ).toMatchObject({ format: 'nv12', byteLength: 12 });
  });

  it('ignores malformed advisory availability events', () => {
    expect(
      normalizeFrameAvailable({ ...rgbaMetadata, droppedFrames: -1 })
    ).toBeUndefined();
    expect(normalizeFrameAvailable(null)).toBeUndefined();
  });

  it('rejects unsupported formats and non-contiguous planes', () => {
    expect(() =>
      normalizeRawFrame({
        ...rgbaMetadata,
        format: 'native-texture',
        dataBase64: 'AAECAw==',
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(() =>
      normalizeRawFrame({
        ...rgbaMetadata,
        planes: [{ ...rgbaMetadata.planes[0], offset: 1 }],
        dataBase64: 'AAECAw==',
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });

  it('rejects inconsistent base64 and plane byte lengths', () => {
    expect(() =>
      normalizeRawFrame({ ...rgbaMetadata, dataBase64: 'AAAA' })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));

    expect(() =>
      normalizeRawFrame({
        ...rgbaMetadata,
        planes: [{ ...rgbaMetadata.planes[0], byteLength: 3 }],
        dataBase64: 'AAECAw==',
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });

  it('rejects format-specific chroma geometry and NV12 UV under-stride', () => {
    const nv12 = {
      sequence: 1,
      timestampSeconds: 1,
      width: 4,
      height: 2,
      format: 'nv12',
      colorRange: 'full',
      byteLength: 12,
      planes: [
        { offset: 0, byteLength: 8, bytesPerRow: 4, width: 4, height: 2 },
        { offset: 8, byteLength: 4, bytesPerRow: 4, width: 2, height: 1 },
      ],
      droppedFrames: 0,
    };

    expect(
      normalizeFrameAvailable({
        ...nv12,
        planes: [nv12.planes[0], { ...nv12.planes[1], width: 1 }],
      })
    ).toBeUndefined();
    expect(
      normalizeFrameAvailable({
        ...nv12,
        byteLength: 10,
        planes: [
          nv12.planes[0],
          {
            ...nv12.planes[1],
            byteLength: 2,
            bytesPerRow: 2,
          },
        ],
      })
    ).toBeUndefined();

    expect(
      normalizeFrameAvailable({
        sequence: 1,
        timestampSeconds: 1,
        width: 2,
        height: 2,
        format: 'i420',
        colorRange: 'full',
        byteLength: 7,
        planes: [
          { offset: 0, byteLength: 4, bytesPerRow: 2, width: 2, height: 2 },
          { offset: 4, byteLength: 2, bytesPerRow: 2, width: 2, height: 1 },
          { offset: 6, byteLength: 1, bytesPerRow: 1, width: 1, height: 1 },
        ],
        droppedFrames: 0,
      })
    ).toBeUndefined();
  });

  it('enforces the native frame bridge byte limit', () => {
    expect(() =>
      normalizeRawFrame({
        ...rgbaMetadata,
        byteLength: NOSMAI_MAX_RAW_FRAME_BYTES + 1,
        dataBase64: 'AAECAw==',
      })
    ).toThrow(expect.objectContaining({ code: NosmaiErrorCode.nativeFailure }));
  });
});
