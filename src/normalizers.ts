import type {
  ActiveBackgroundSource,
  ActiveEffectsMode,
  GalleryMediaType,
  LicenseStatus,
  NosmaiActiveEffects,
  NosmaiCloudDownloadResult,
  NosmaiCloudFilterPage,
  NosmaiDownloadProgressEvent,
  NosmaiEffectParameter,
  NosmaiEffectParameterType,
  NosmaiEffectParameterValue,
  NosmaiFilter,
  NosmaiGallerySaveResult,
  NosmaiGameEvent,
  NosmaiNativeError,
  NosmaiPhotoResult,
  NosmaiRecordingProgressEvent,
  NosmaiRecordingResult,
  PackageType,
} from './types';
import { NosmaiErrorCode, NosmaiSdkError } from './errors';

type UnknownRecord = Readonly<Record<string, unknown>>;

function asRecord(value: unknown): UnknownRecord {
  return typeof value === 'object' && value !== null
    ? (value as UnknownRecord)
    : {};
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function stringValue(value: unknown, fallback = ''): string {
  return typeof value === 'string' ? value : fallback;
}

function numberValue(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function booleanValue(value: unknown): boolean {
  return value === true || value === 1 || value === 'true';
}

function invalidNativePayload(operation: string, field: string): never {
  throw new NosmaiSdkError(
    NosmaiErrorCode.nativeFailure,
    `Invalid native ${operation} payload: ${field}.`,
    { operation, field }
  );
}

function strictRecord(value: unknown, operation: string): UnknownRecord {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return invalidNativePayload(operation, 'expected an object');
  }
  return value as UnknownRecord;
}

function strictString(
  value: unknown,
  operation: string,
  field: string
): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    return invalidNativePayload(
      operation,
      `${field} must be a non-empty string`
    );
  }
  return value.trim();
}

function strictNumber(
  value: unknown,
  operation: string,
  field: string,
  options: { integer?: boolean; positive?: boolean } = {}
): number {
  if (
    typeof value !== 'number' ||
    !Number.isFinite(value) ||
    value < 0 ||
    (options.positive === true && value <= 0) ||
    (options.integer === true && !Number.isSafeInteger(value))
  ) {
    return invalidNativePayload(
      operation,
      `${field} must be a ${options.positive === true ? 'positive' : 'non-negative'}${
        options.integer === true ? ' safe integer' : ' finite number'
      }`
    );
  }
  return value;
}

function strictFileUri(
  value: unknown,
  operation: string,
  field: string
): string {
  const uri = strictString(value, operation, field);
  if (!(
    (uri.startsWith('file:///') && uri.length > 'file:///'.length) ||
    (uri.startsWith('file://localhost/') &&
      uri.length > 'file://localhost/'.length)
  )) {
    return invalidNativePayload(
      operation,
      `${field} must be an absolute local file URI`
    );
  }
  return uri;
}

function strictGalleryUri(value: unknown, operation: string): string {
  const uri = strictString(value, operation, 'uri');
  const hasPersistentIdentifier =
    (uri.startsWith('content://') && uri.length > 'content://'.length) ||
    (uri.startsWith('ph://') && uri.length > 'ph://'.length);
  if (!hasPersistentIdentifier) {
    return invalidNativePayload(
      operation,
      'uri must be a persistent content:// or ph:// media URI'
    );
  }
  return uri;
}

function strictAbsolutePath(
  value: unknown,
  operation: string,
  field: string
): string {
  const path = strictString(value, operation, field);
  if (
    !path.startsWith('/') ||
    path.includes('\0') ||
    !path.toLowerCase().endsWith('.nosmai')
  ) {
    return invalidNativePayload(
      operation,
      `${field} must be an absolute .nosmai path`
    );
  }
  return path;
}

export function normalizePhotoResult(value: unknown): NosmaiPhotoResult {
  const operation = 'capturePhoto';
  const data = strictRecord(value, operation);
  const mimeType = strictString(data.mimeType, operation, 'mimeType');
  if (mimeType !== 'image/jpeg') {
    return invalidNativePayload(operation, 'mimeType must be image/jpeg');
  }

  return {
    uri: strictFileUri(data.uri, operation, 'uri'),
    width: strictNumber(data.width, operation, 'width', {
      integer: true,
      positive: true,
    }),
    height: strictNumber(data.height, operation, 'height', {
      integer: true,
      positive: true,
    }),
    fileSizeBytes: strictNumber(
      data.fileSizeBytes,
      operation,
      'fileSizeBytes',
      { integer: true, positive: true }
    ),
    mimeType,
  };
}

export function normalizeRecordingResult(
  value: unknown
): NosmaiRecordingResult {
  const operation = 'stopRecording';
  const data = strictRecord(value, operation);
  const mimeType = strictString(data.mimeType, operation, 'mimeType');
  if (mimeType !== 'video/mp4') {
    return invalidNativePayload(operation, 'mimeType must be video/mp4');
  }
  if (typeof data.hasAudio !== 'boolean') {
    return invalidNativePayload(operation, 'hasAudio must be a boolean');
  }

  return {
    uri: strictFileUri(data.uri, operation, 'uri'),
    durationSeconds: strictNumber(
      data.durationSeconds,
      operation,
      'durationSeconds'
    ),
    fileSizeBytes: strictNumber(
      data.fileSizeBytes,
      operation,
      'fileSizeBytes',
      { integer: true, positive: true }
    ),
    mimeType,
    hasAudio: data.hasAudio,
  };
}

export function normalizeGallerySaveResult(
  value: unknown,
  expectedMediaType: GalleryMediaType
): NosmaiGallerySaveResult {
  const operation =
    expectedMediaType === 'photo' ? 'saveImageToGallery' : 'saveVideoToGallery';
  const data = strictRecord(value, operation);
  const mediaType = strictString(data.mediaType, operation, 'mediaType');
  if (mediaType !== expectedMediaType) {
    return invalidNativePayload(
      operation,
      `mediaType must be ${expectedMediaType}`
    );
  }

  return {
    uri: strictGalleryUri(data.uri, operation),
    mediaType,
  };
}

export function normalizeRecordingDuration(value: unknown): number {
  return strictNumber(value, 'getCurrentRecordingDuration', 'durationSeconds');
}

export function normalizeRecordingProgress(
  value: unknown
): NosmaiRecordingProgressEvent | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const durationSeconds = (value as UnknownRecord).durationSeconds;
  if (
    typeof durationSeconds !== 'number' ||
    !Number.isFinite(durationSeconds) ||
    durationSeconds < 0
  ) {
    return undefined;
  }
  return { durationSeconds };
}

const MAX_EFFECT_PARAMETER_COUNT = 256;
const MAX_EFFECT_PARAMETER_NAME_LENGTH = 128;
const MAX_EFFECT_PARAMETER_TYPE_LENGTH = 64;
const MAX_EFFECT_PARAMETER_DISPLAY_NAME_LENGTH = 256;
const MAX_EFFECT_PARAMETER_DESCRIPTION_LENGTH = 2_048;
const MAX_EFFECT_PARAMETER_STRING_LENGTH = 16_384;
const MAX_EFFECT_PARAMETER_ENUM_LENGTH = 256;
const MAX_EFFECT_PARAMETER_VECTOR_LENGTH = 64;
const MAX_EFFECT_PARAMETER_OPTION_COUNT = 128;
const MAX_EFFECT_PARAMETER_OPTION_LENGTH = 256;

function containsEffectMetadataControlCharacter(value: string): boolean {
  for (let index = 0; index < value.length; index += 1) {
    const code = value.charCodeAt(index);
    if (code < 0x20 || code === 0x7f) return true;
  }
  return false;
}

function safeEffectParameterName(value: unknown): string | undefined {
  if (
    typeof value !== 'string' ||
    value.length > MAX_EFFECT_PARAMETER_NAME_LENGTH
  ) {
    return undefined;
  }
  const name = value.trim();
  return name.length > 0 && !containsEffectMetadataControlCharacter(name)
    ? name
    : undefined;
}

function sanitizeEffectMetadataText(
  value: unknown,
  maximumLength: number
): string | undefined {
  if (typeof value !== 'string') return undefined;
  let sanitized = '';
  const sourceLength = Math.min(value.length, maximumLength);
  for (let index = 0; index < sourceLength; index += 1) {
    const code = value.charCodeAt(index);
    if (code >= 0x20 && code !== 0x7f) sanitized += value[index];
  }
  return sanitized;
}

function normalizeEffectParameterType(
  value: unknown
): NosmaiEffectParameterType {
  const raw =
    typeof value === 'string' &&
    value.length <= MAX_EFFECT_PARAMETER_TYPE_LENGTH
      ? value.trim().toLowerCase()
      : '';
  switch (raw) {
    case 'float':
    case 'double':
    case 'number':
      return 'float';
    case 'int':
    case 'integer':
      return 'int';
    case 'bool':
    case 'boolean':
      return 'bool';
    case 'string':
    case 'text':
      return 'string';
    case 'vector':
    case 'vec2':
    case 'vec3':
    case 'vec4':
    case 'color':
    case 'color3':
    case 'color4':
      return 'vector';
    case 'enum':
    case 'select':
    case 'option':
      return 'enum';
    default:
      return 'unknown';
  }
}

function normalizeEffectParameterValue(
  value: unknown,
  type: NosmaiEffectParameterType
): NosmaiEffectParameterValue {
  switch (type) {
    case 'float':
      return typeof value === 'number' && Number.isFinite(value) ? value : null;
    case 'int':
      return typeof value === 'number' && Number.isSafeInteger(value)
        ? value
        : null;
    case 'bool':
      return typeof value === 'boolean'
        ? value
        : value === 0 || value === 1
          ? value === 1
          : null;
    case 'string':
      return (
        sanitizeEffectMetadataText(value, MAX_EFFECT_PARAMETER_STRING_LENGTH) ??
        null
      );
    case 'vector':
      return Array.isArray(value) &&
        value.length <= MAX_EFFECT_PARAMETER_VECTOR_LENGTH &&
        value.every(
          (component) =>
            typeof component === 'number' && Number.isFinite(component)
        )
        ? value
        : null;
    case 'enum':
      if (typeof value === 'string') {
        return (
          sanitizeEffectMetadataText(value, MAX_EFFECT_PARAMETER_ENUM_LENGTH) ??
          null
        );
      }
      return typeof value === 'number' && Number.isFinite(value) ? value : null;
    case 'unknown':
      if (typeof value === 'string') {
        return (
          sanitizeEffectMetadataText(
            value,
            MAX_EFFECT_PARAMETER_STRING_LENGTH
          ) ?? null
        );
      }
      return typeof value === 'boolean' ||
        (typeof value === 'number' && Number.isFinite(value))
        ? value
        : null;
  }
}

/** Normalizes the bridge-safe `{ items: [...] }` authored-parameter envelope. */
export function normalizeEffectParameters(
  value: unknown
): NosmaiEffectParameter[] {
  const operation = 'getEffectParameters';
  const data = strictRecord(value, operation);
  if (!Array.isArray(data.items)) {
    return invalidNativePayload(operation, 'items must be an array');
  }

  const seen = new Set<string>();
  return data.items
    .slice(0, MAX_EFFECT_PARAMETER_COUNT)
    .flatMap((candidate) => {
      if (
        typeof candidate !== 'object' ||
        candidate === null ||
        Array.isArray(candidate)
      ) {
        return [];
      }
      const raw = candidate as UnknownRecord;
      const name = safeEffectParameterName(raw.name);
      if (name === undefined) return [];
      const type = normalizeEffectParameterType(raw.type);
      const rawPassId = raw.passId;
      const passId =
        typeof rawPassId === 'number' &&
        Number.isSafeInteger(rawPassId) &&
        rawPassId >= 0
          ? rawPassId
          : undefined;
      const identity = `${passId ?? 'native'}:${name}`;
      if (seen.has(identity)) return [];
      seen.add(identity);

      const rawMinimum = raw.minValue ?? raw.min;
      const rawMaximum = raw.maxValue ?? raw.max;
      const minimum =
        typeof rawMinimum === 'number' && Number.isFinite(rawMinimum)
          ? rawMinimum
          : undefined;
      const maximum =
        typeof rawMaximum === 'number' && Number.isFinite(rawMaximum)
          ? rawMaximum
          : undefined;
      const hasRange =
        raw.hasRange === true &&
        minimum !== undefined &&
        maximum !== undefined &&
        minimum <= maximum;

      const displayName = sanitizeEffectMetadataText(
        raw.displayName,
        MAX_EFFECT_PARAMETER_DISPLAY_NAME_LENGTH
      );
      const description = sanitizeEffectMetadataText(
        raw.description,
        MAX_EFFECT_PARAMETER_DESCRIPTION_LENGTH
      );
      const options = Array.isArray(raw.options)
        ? raw.options
            .slice(0, MAX_EFFECT_PARAMETER_OPTION_COUNT)
            .flatMap((option) => {
              const normalized = sanitizeEffectMetadataText(
                option,
                MAX_EFFECT_PARAMETER_OPTION_LENGTH
              );
              return normalized === undefined ? [] : [normalized];
            })
        : [];

      return [
        {
          name,
          type,
          displayName:
            displayName !== undefined && displayName.trim().length > 0
              ? displayName
              : name,
          description: description ?? '',
          currentValue: normalizeEffectParameterValue(raw.currentValue, type),
          defaultValue: normalizeEffectParameterValue(raw.defaultValue, type),
          hasRange,
          ...(hasRange ? { minValue: minimum, maxValue: maximum } : {}),
          options,
          ...(passId === undefined ? {} : { passId }),
        },
      ];
    });
}

export function normalizeEffectParameterNumber(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return invalidNativePayload(
      'getEffectParameterValue',
      'value must be a finite number'
    );
  }
  return value;
}

function normalizePackageType(value: unknown): PackageType {
  switch (value) {
    case 'effect':
    case 'background':
    case 'beauty_effect':
    case 'game':
    case 'games':
      return value === 'games' ? 'game' : value;
    default:
      return 'filter';
  }
}

export function normalizeGameEvent(
  value: unknown
): NosmaiGameEvent | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const raw = value as UnknownRecord;
  const event = optionalString(raw.event)?.trim();
  const game = optionalString(raw.game)?.trim();
  const sequence = raw.sequence;
  const data = raw.data;
  if (
    event === undefined ||
    game === undefined ||
    typeof sequence !== 'number' ||
    !Number.isSafeInteger(sequence) ||
    sequence < 0 ||
    typeof data !== 'object' ||
    data === null ||
    Array.isArray(data)
  ) {
    return undefined;
  }
  return {
    event,
    game,
    sequence,
    data: data as Readonly<Record<string, unknown>>,
  };
}

function normalizeMode(value: unknown): ActiveEffectsMode {
  switch (value) {
    case 'idle':
    case 'effectsFilters':
    case 'filtersBackground':
    case 'beautyFilters':
    case 'beautyBackground':
      return value;
    default:
      return 'unknown';
  }
}

function normalizeBackgroundSource(value: unknown): ActiveBackgroundSource {
  switch (value) {
    case 'none':
    case 'manual':
    case 'filter':
    case 'package':
    case 'effect':
      return value;
    default:
      return 'unknown';
  }
}

export function normalizeLicenseStatus(value: unknown): LicenseStatus {
  switch (typeof value === 'string' ? value.toLowerCase() : value) {
    case 'valid':
      return 'valid';
    case 'invalid':
      return 'invalid';
    case 'expired':
      return 'expired';
    case 'unverified':
      return 'unverified';
    default:
      return 'unknown';
  }
}

export function normalizeFilter(value: unknown): NosmaiFilter | undefined {
  if (typeof value !== 'object' || value === null) {
    return undefined;
  }

  const data = asRecord(value);
  const path = stringValue(data.path);
  const id = optionalString(data.id) ?? optionalString(data.filterId) ?? path;
  const name =
    optionalString(data.name) ?? optionalString(data.displayName) ?? id;

  return {
    id,
    filterId: optionalString(data.filterId),
    backendId: optionalString(data.backendId),
    name,
    displayName: optionalString(data.displayName) ?? name,
    description: stringValue(data.description),
    path,
    fileSize: numberValue(data.fileSize),
    location: data.type === 'cloud' ? 'cloud' : 'local',
    packageType: normalizePackageType(
      data.filterType ?? data.packageType ?? data.sourceType
    ),
    isFree: booleanValue(data.isFree),
    isDownloaded: booleanValue(data.isDownloaded),
    previewUrl:
      optionalString(data.previewUrl) ?? optionalString(data.previewPath),
    category: optionalString(data.category),
    downloadCount: numberValue(data.downloadCount),
    price: numberValue(data.price),
    version: optionalString(data.version),
    author: optionalString(data.author),
    minSdkVersion: optionalString(data.minSDKVersion ?? data.minSdkVersion),
    created: optionalString(data.created),
    tags: Array.isArray(data.tags)
      ? data.tags.filter((tag): tag is string => typeof tag === 'string')
      : undefined,
  };
}

function compareText(left: string, right: string): number {
  if (left < right) {
    return -1;
  }
  if (left > right) {
    return 1;
  }
  return 0;
}

function filterTieBreakKey(filter: NosmaiFilter): string {
  return JSON.stringify([
    filter.id,
    filter.filterId ?? '',
    filter.backendId ?? '',
    filter.name,
    filter.displayName,
    filter.description,
    filter.path,
    filter.fileSize,
    filter.location,
    filter.packageType,
    filter.isFree,
    filter.isDownloaded,
    filter.previewUrl ?? '',
    filter.category ?? '',
    filter.downloadCount,
    filter.price,
    filter.version ?? '',
    filter.author ?? '',
    filter.minSdkVersion ?? '',
    filter.created ?? '',
    filter.tags ?? [],
  ]);
}

function compareCatalogFilters(
  left: NosmaiFilter,
  right: NosmaiFilter
): number {
  return (
    compareText(
      left.displayName.toLowerCase(),
      right.displayName.toLowerCase()
    ) ||
    compareText(left.displayName, right.displayName) ||
    compareText(left.packageType, right.packageType) ||
    compareText(left.path, right.path) ||
    compareText(left.id, right.id) ||
    compareText(filterTieBreakKey(left), filterTieBreakKey(right))
  );
}

/**
 * Normalizes the TurboModule-safe `{ items: [...] }` catalog envelope.
 * Package paths are the stable local identity, so malformed paths are omitted
 * and duplicate paths collapse deterministically before the result is exposed.
 */
export function normalizeLocalFilters(value: unknown): NosmaiFilter[] {
  const items = asRecord(value).items;
  if (!Array.isArray(items)) {
    return [];
  }

  const filters = items.flatMap((item) => {
    const data = asRecord(item);
    const path = typeof data.path === 'string' ? data.path.trim() : '';
    if (path.length === 0) {
      return [];
    }

    const filter = normalizeFilter({ ...data, path });
    return filter === undefined ? [] : [filter];
  });

  filters.sort(compareCatalogFilters);

  const seenPaths = new Set<string>();
  return filters.filter((filter) => {
    if (seenPaths.has(filter.path)) {
      return false;
    }
    seenPaths.add(filter.path);
    return true;
  });
}

export function normalizeCloudFilterPage(
  value: unknown
): NosmaiCloudFilterPage {
  const operation = 'getCloudFilters';
  const data = strictRecord(value, operation);
  if (!Array.isArray(data.items)) {
    return invalidNativePayload(operation, 'items must be an array');
  }

  const filters: NosmaiFilter[] = [];
  const seenIds = new Set<string>();
  data.items.forEach((item, index) => {
    const raw = strictRecord(item, operation);
    const filterId = strictString(
      raw.filterId ?? raw.id,
      operation,
      `items[${index}].filterId`
    );
    if (seenIds.has(filterId)) {
      return;
    }

    const rawId = optionalString(raw.id);
    const backendId =
      optionalString(raw.backendId) ??
      (rawId !== undefined && rawId !== filterId ? rawId : undefined);
    const normalized = normalizeFilter({
      ...raw,
      id: filterId,
      filterId,
      backendId,
      type: 'cloud',
    });
    if (normalized === undefined) {
      return invalidNativePayload(operation, `items[${index}] is invalid`);
    }
    const filter = normalized.isDownloaded
      ? {
          ...normalized,
          path: strictAbsolutePath(
            normalized.path,
            operation,
            `items[${index}].path`
          ),
        }
      : normalized;
    seenIds.add(filterId);
    filters.push(filter);
  });

  const pagination = strictRecord(data.pagination, operation);
  const currentPage = strictNumber(
    pagination.currentPage,
    operation,
    'pagination.currentPage',
    { integer: true, positive: true }
  );
  const totalPages = strictNumber(
    pagination.totalPages,
    operation,
    'pagination.totalPages',
    { integer: true, positive: true }
  );
  const totalItems = strictNumber(
    pagination.totalItems,
    operation,
    'pagination.totalItems',
    { integer: true }
  );
  const itemsPerPage = strictNumber(
    pagination.itemsPerPage,
    operation,
    'pagination.itemsPerPage',
    { integer: true, positive: true }
  );
  if (
    typeof pagination.hasNextPage !== 'boolean' ||
    typeof pagination.hasPreviousPage !== 'boolean'
  ) {
    return invalidNativePayload(
      operation,
      'pagination next/previous flags must be booleans'
    );
  }

  return {
    filters,
    pagination: {
      currentPage,
      totalPages,
      totalItems,
      itemsPerPage,
      hasNextPage: pagination.hasNextPage,
      hasPreviousPage: pagination.hasPreviousPage,
    },
  };
}

export function normalizeCloudDownloadResult(
  value: unknown,
  expectedFilterId: string
): NosmaiCloudDownloadResult {
  const operation = 'downloadCloudFilter';
  const data = strictRecord(value, operation);
  const filterId = strictString(data.filterId, operation, 'filterId');
  if (filterId !== expectedFilterId) {
    return invalidNativePayload(
      operation,
      'filterId does not match the requested filter'
    );
  }
  if (typeof data.alreadyDownloaded !== 'boolean') {
    return invalidNativePayload(
      operation,
      'alreadyDownloaded must be a boolean'
    );
  }
  return {
    filterId,
    path: strictAbsolutePath(data.path, operation, 'path'),
    alreadyDownloaded: data.alreadyDownloaded,
  };
}

export function normalizeDownloadProgress(
  value: unknown
): NosmaiDownloadProgressEvent | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return undefined;
  }
  const data = value as UnknownRecord;
  const filterId = optionalString(data.filterId)?.trim();
  const progress = data.progress;
  if (
    filterId === undefined ||
    filterId.length === 0 ||
    typeof progress !== 'number' ||
    !Number.isFinite(progress) ||
    progress < 0 ||
    progress > 1
  ) {
    return undefined;
  }
  return { filterId, progress };
}

export function normalizeActiveEffects(value: unknown): NosmaiActiveEffects {
  const data = asRecord(value);
  const activeBackgroundPath = optionalString(
    data.activeBackgroundPath ?? data.activeBackgroundPackagePath
  );

  return {
    mode: numberValue(data.mode),
    modeName: normalizeMode(data.modeName ?? 'idle'),
    activeFilterPath: optionalString(data.activeFilterPath),
    activeEffectPath: optionalString(data.activeEffectPath),
    activeBackgroundPath,
    hasBackground: booleanValue(data.hasBackground ?? data.backgroundActive),
    backgroundSource: numberValue(data.backgroundSource),
    backgroundSourceName: normalizeBackgroundSource(
      data.backgroundSourceName ?? 'none'
    ),
    hasBeautyEffect: booleanValue(data.hasBeautyEffect),
    hasBuiltInBeauty: booleanValue(data.hasBuiltInBeauty),
    hasManualBackground: booleanValue(
      data.hasManualBackground ?? data.hasManualBackgroundConfig
    ),
    activeFilter: normalizeFilter(data.activeFilterInfo ?? data.activeFilter),
    activeEffect: normalizeFilter(data.activeEffectInfo ?? data.activeEffect),
  };
}

export function normalizeNativeError(value: unknown): NosmaiNativeError {
  const data = asRecord(value);
  const details = asRecord(data.details);

  return {
    code: stringValue(data.code, 'E_NATIVE_FAILURE'),
    message: stringValue(
      data.message,
      'The native Nosmai SDK operation failed.'
    ),
    details: Object.keys(details).length > 0 ? details : undefined,
  };
}
