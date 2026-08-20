import { NosmaiErrorCode, NosmaiSdkError } from './errors';
import type {
  NosmaiFrameMetadata,
  NosmaiRawFrame,
  NosmaiRawFrameColorRange,
  NosmaiRawFrameFormat,
  NosmaiRawFramePlane,
} from './types';

type UnknownRecord = Readonly<Record<string, unknown>>;

export const NOSMAI_MAX_RAW_FRAME_BYTES = 8 * 1024 * 1024;

function invalidFramePayload(field: string): never {
  throw new NosmaiSdkError(
    NosmaiErrorCode.nativeFailure,
    `Invalid native processed-frame payload: ${field}.`,
    { operation: 'getLatestFrame', field }
  );
}

function recordValue(
  value: unknown,
  field = 'expected an object'
): UnknownRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return invalidFramePayload(field);
  }
  return value as UnknownRecord;
}

function safeInteger(
  value: unknown,
  field: string,
  minimum: number,
  maximum = Number.MAX_SAFE_INTEGER
): number {
  if (
    typeof value !== 'number' ||
    !Number.isSafeInteger(value) ||
    value < minimum ||
    value > maximum
  ) {
    return invalidFramePayload(`${field} must be a safe integer`);
  }
  return value;
}

function finiteNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    return invalidFramePayload(`${field} must be a non-negative finite number`);
  }
  return value;
}

function frameFormat(value: unknown): NosmaiRawFrameFormat {
  switch (value) {
    case 'i420':
    case 'rgba8888':
    case 'bgra8888':
    case 'nv12':
      return value;
    default:
      return invalidFramePayload('format is unsupported');
  }
}

function colorRange(value: unknown): NosmaiRawFrameColorRange {
  switch (value) {
    case 'full':
    case 'video':
    case 'unknown':
      return value;
    default:
      return invalidFramePayload('colorRange is unsupported');
  }
}

function normalizedPlanes(
  value: unknown,
  format: NosmaiRawFrameFormat,
  frameWidth: number,
  frameHeight: number,
  totalByteLength: number
): NosmaiRawFramePlane[] {
  if (!Array.isArray(value)) {
    return invalidFramePayload('planes must be an array');
  }
  const expectedCount = format === 'i420' ? 3 : format === 'nv12' ? 2 : 1;
  if (value.length !== expectedCount) {
    return invalidFramePayload(
      `${format} must contain exactly ${expectedCount} plane${expectedCount === 1 ? '' : 's'}`
    );
  }

  let nextOffset = 0;
  const planes = value.map((rawPlane, index) => {
    const plane = recordValue(rawPlane, `planes[${index}] must be an object`);
    const offset = safeInteger(plane.offset, `planes[${index}].offset`, 0);
    const byteLength = safeInteger(
      plane.byteLength,
      `planes[${index}].byteLength`,
      1,
      NOSMAI_MAX_RAW_FRAME_BYTES
    );
    const bytesPerRow = safeInteger(
      plane.bytesPerRow,
      `planes[${index}].bytesPerRow`,
      1,
      NOSMAI_MAX_RAW_FRAME_BYTES
    );
    const width = safeInteger(plane.width, `planes[${index}].width`, 1);
    const height = safeInteger(plane.height, `planes[${index}].height`, 1);

    if (offset !== nextOffset) {
      return invalidFramePayload(`planes[${index}].offset is not contiguous`);
    }
    if (bytesPerRow * height !== byteLength) {
      return invalidFramePayload(
        `planes[${index}].byteLength does not match its stride and height`
      );
    }

    const isChromaPlane = index > 0;
    const expectedWidth =
      (format === 'i420' || format === 'nv12') && isChromaPlane
        ? Math.ceil(frameWidth / 2)
        : frameWidth;
    const expectedHeight =
      (format === 'i420' || format === 'nv12') && isChromaPlane
        ? Math.ceil(frameHeight / 2)
        : frameHeight;
    if (width !== expectedWidth || height !== expectedHeight) {
      return invalidFramePayload(
        `planes[${index}] dimensions do not match ${format} geometry`
      );
    }

    const bytesPerPixel =
      format === 'rgba8888' || format === 'bgra8888'
        ? 4
        : format === 'nv12' && isChromaPlane
          ? 2
          : 1;
    if (bytesPerRow < width * bytesPerPixel) {
      return invalidFramePayload(`planes[${index}].bytesPerRow is too small`);
    }
    if (nextOffset + byteLength > totalByteLength) {
      return invalidFramePayload(`planes[${index}] exceeds byteLength`);
    }
    nextOffset += byteLength;

    return { offset, byteLength, bytesPerRow, width, height };
  });

  if (nextOffset !== totalByteLength) {
    return invalidFramePayload('planes do not cover byteLength exactly');
  }
  return planes;
}

function normalizedMetadata(value: unknown): NosmaiFrameMetadata {
  const data = recordValue(value);
  const format = frameFormat(data.format);
  const width = safeInteger(data.width, 'width', 1);
  const height = safeInteger(data.height, 'height', 1);
  const byteLength = safeInteger(
    data.byteLength,
    'byteLength',
    1,
    NOSMAI_MAX_RAW_FRAME_BYTES
  );

  return {
    sequence: safeInteger(data.sequence, 'sequence', 1),
    timestampSeconds: finiteNumber(data.timestampSeconds, 'timestampSeconds'),
    width,
    height,
    format,
    colorRange: colorRange(data.colorRange),
    byteLength,
    planes: normalizedPlanes(data.planes, format, width, height, byteLength),
    droppedFrames: safeInteger(data.droppedFrames, 'droppedFrames', 0),
  };
}

function validateBase64(value: unknown, byteLength: number): string {
  if (typeof value !== 'string') {
    return invalidFramePayload('dataBase64 must be a string');
  }
  const expectedLength = Math.ceil(byteLength / 3) * 4;
  if (
    value.length !== expectedLength ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(
      value
    )
  ) {
    return invalidFramePayload('dataBase64 does not match byteLength');
  }
  const expectedPadding = byteLength % 3 === 0 ? 0 : 3 - (byteLength % 3);
  if (!value.endsWith('='.repeat(expectedPadding))) {
    return invalidFramePayload('dataBase64 padding does not match byteLength');
  }
  return value;
}

export function normalizeRawFrame(value: unknown): NosmaiRawFrame {
  const metadata = normalizedMetadata(value);
  const data = value as UnknownRecord;
  return {
    ...metadata,
    dataBase64: validateBase64(data.dataBase64, metadata.byteLength),
  };
}

/** Event payloads are advisory, so malformed native events are ignored. */
export function normalizeFrameAvailable(
  value: unknown
): NosmaiFrameMetadata | undefined {
  try {
    return normalizedMetadata(value);
  } catch {
    return undefined;
  }
}
