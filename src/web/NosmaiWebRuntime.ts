import { NosmaiErrorCode, NosmaiSdkError } from '../errors';
import type {
  CameraConfiguration,
  CameraPosition,
  LicenseStatus,
  NosmaiActiveEffects,
  NosmaiCloudDownloadResult,
  NosmaiCloudFilterPage,
  NosmaiCloudFilterQuery,
  NosmaiDownloadProgressEvent,
  NosmaiEventSubscription,
  NosmaiFilter,
  NosmaiGameEvent,
  NosmaiNativeError,
  NosmaiPaginationInfo,
  NosmaiRecordingProgressEvent,
  PackageType,
} from '../types';

type WebNosmaiCtor = {
  initialize(
    licenseKey: string,
    options?: { assetBase?: string }
  ): Promise<{ ok: boolean; licence?: unknown; error?: unknown }>;
  readonly instance: any;
};

type WebCanvas = any;
type WebBlob = any;

function browserWindow(): any {
  return (globalThis as any).window;
}

function browserDocument(): any {
  return (globalThis as any).document;
}

function browserUrl(): any {
  return (globalThis as any).URL;
}

function browserBlobCtor(): any {
  return (globalThis as any).Blob;
}


type ListenerMap = {
  activeEffects: (state: NosmaiActiveEffects) => void;
  license: (status: LicenseStatus) => void;
  error: (error: NosmaiNativeError) => void;
  recording: (progress: NosmaiRecordingProgressEvent) => void;
  download: (progress: NosmaiDownloadProgressEvent) => void;
  game: (event: NosmaiGameEvent) => void;
};

type ListenerKey = keyof ListenerMap;

const listeners: Record<ListenerKey, Set<(payload: any) => void>> = {
  activeEffects: new Set(),
  license: new Set(),
  error: new Set(),
  recording: new Set(),
  download: new Set(),
  game: new Set(),
};

let bridgePromise: Promise<WebNosmaiCtor> | null = null;
let sdk: any = null;
let canvas: WebCanvas | null = null;
let canvasAttached = false;
let cameraPosition: CameraPosition = 'front';
let processingRequested = false;
let paused = false;
let backgroundActive = false;
let currentEffectSource: string | undefined;
let currentEffectInfo: NosmaiFilter | undefined;
let licenceStatus: LicenseStatus = 'unverified';
type WebMirrorMode = 'auto' | 'on' | 'off';

let mirrorMode: WebMirrorMode = 'auto';

let onViewReady: (() => void) | undefined;
let onViewError: ((error: NosmaiNativeError) => void) | undefined;

const cloudObjectUrls = new Map<string, string>();
const cloudFilterIdsByPath = new Map<string, string>();
const generatedObjectUrls = new Set<string>();

function emit<K extends ListenerKey>(
  key: K,
  value: Parameters<ListenerMap[K]>[0]
) {
  for (const listener of listeners[key]) {
    try {
      listener(value);
    } catch {
      // Consumer listener errors must never break the SDK operation.
    }
  }
}

function toNativeError(error: unknown): NosmaiNativeError {
  const sdkError = NosmaiSdkError.fromUnknown(error);
  return {
    code: sdkError.code,
    message: sdkError.message,
    details: sdkError.details,
  };
}

function report(error: unknown): NosmaiSdkError {
  const sdkError = NosmaiSdkError.fromUnknown(error);
  const normalized = toNativeError(sdkError);
  emit('error', normalized);
  onViewError?.(normalized);
  return sdkError;
}

export function notImplemented(operation: string): never {
  throw new NosmaiSdkError(
    NosmaiErrorCode.notImplemented,
    `${operation} is not available on Web.`
  );
}

function requireBrowser() {
  if (!browserWindow() || !browserDocument()) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.notImplemented,
      'Nosmai Web requires a browser DOM environment.'
    );
  }
}

function nonEmpty(value: string, field: string): string {
  const normalized = typeof value === 'string' ? value.trim() : '';
  if (!normalized) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.invalidArgument,
      `${field} must not be empty.`
    );
  }
  return normalized;
}

function mapLicense(value: unknown, fallback: LicenseStatus): LicenseStatus {
  const candidates: unknown[] = [value];

  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    candidates.push(
      object.status,
      object.state,
      object.licenceStatus,
      object.licenseStatus
    );

    if (object.valid === true || object.isValid === true) return 'valid';
    if (object.expired === true || object.isExpired === true) return 'expired';
  }

  for (const candidate of candidates) {
    const text = typeof candidate === 'string' ? candidate.toLowerCase() : '';
    if (text.includes('expire')) return 'expired';
    if (text.includes('invalid')) return 'invalid';
    if (text.includes('valid')) return 'valid';
    if (text.includes('unverified')) return 'unverified';
  }

  return fallback;
}

async function loadBridge(): Promise<WebNosmaiCtor> {
  requireBrowser();

  const win = browserWindow();
  const doc = browserDocument();
  const existing = win?.NosmaiReactNativeWeb?.Nosmai;
  if (existing) return existing;

  if (bridgePromise) return bridgePromise;

  bridgePromise = new Promise<WebNosmaiCtor>((resolve, reject) => {
    const previous = doc.querySelector(
      'script[data-nosmai-rn-web-bridge="true"]'
    );

    const finish = () => {
      const ctor = win?.NosmaiReactNativeWeb?.Nosmai;
      if (!ctor) {
        reject(
          new NosmaiSdkError(
            NosmaiErrorCode.initialization,
            'Nosmai Web bridge loaded but did not expose NosmaiReactNativeWeb.'
          )
        );
        return;
      }
      resolve(ctor);
    };

    if (previous) {
      if (win?.NosmaiReactNativeWeb?.Nosmai) {
        finish();
        return;
      }
      previous.addEventListener('load', finish, { once: true });
      previous.addEventListener(
        'error',
        () =>
          reject(
            new NosmaiSdkError(
              NosmaiErrorCode.initialization,
              'Failed to load /nosmai_bridge.js.'
            )
          ),
        { once: true }
      );
      return;
    }

    const script = doc.createElement('script');
    script.src = '/nosmai_bridge.js';
    script.async = true;
    script.dataset.nosmaiRnWebBridge = 'true';
    script.addEventListener('load', finish, { once: true });
    script.addEventListener(
      'error',
      () =>
        reject(
          new NosmaiSdkError(
            NosmaiErrorCode.initialization,
            'Failed to load /nosmai_bridge.js. Run the Nosmai web asset copy step first.'
          )
        ),
      { once: true }
    );
    doc.head.appendChild(script);
  });

  try {
    return await bridgePromise;
  } catch (error) {
    bridgePromise = null;
    throw error;
  }
}

function requireSdk(): any {
  if (!sdk) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.notInitialized,
      'Call NosmaiCameraSdk.initialize() before using the Web SDK.'
    );
  }
  return sdk;
}

async function ensureCanvasAttached(): Promise<void> {
  const instance = requireSdk();

  if (!canvas) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.noPreview,
      'NosmaiCameraView must be mounted before starting Web processing.'
    );
  }

  if (canvasAttached) return;

  await instance.attach(canvas);
  canvasAttached = true;
  onViewReady?.();
}

function packageTypeFromCategory(category: unknown): PackageType {
  switch (category) {
    case 'filter':
      return 'filter';
    case 'background':
      return 'background';
    case 'beautyEffect':
    case 'beauty_effect':
      return 'beauty_effect';
    case 'game':
      return 'game';
    default:
      return 'effect';
  }
}

function webCategory(packageType: PackageType | undefined): string | undefined {
  switch (packageType) {
    case 'filter':
      return 'filter';
    case 'effect':
      return 'effect';
    case 'background':
      return 'background';
    case 'beauty_effect':
      return 'beautyEffect';
    case 'game':
      return undefined;
    case undefined:
      return undefined;
    default:
      return undefined;
  }
}

function firstString(
  object: Record<string, unknown>,
  keys: readonly string[],
  fallback = ''
): string {
  for (const key of keys) {
    const value = object[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  }
  return fallback;
}

function firstNumber(
  object: Record<string, unknown>,
  keys: readonly string[],
  fallback = 0
): number {
  for (const key of keys) {
    const value = object[key];
    if (typeof value === 'number' && Number.isFinite(value)) return value;
  }
  return fallback;
}

function firstBoolean(
  object: Record<string, unknown>,
  keys: readonly string[],
  fallback = false
): boolean {
  for (const key of keys) {
    const value = object[key];
    if (typeof value === 'boolean') return value;
  }
  return fallback;
}

export function cloudIdentifier(filter: NosmaiFilter | string): string {
  if (typeof filter === 'string') return nonEmpty(filter, 'filterId');

  const object = filter as NosmaiFilter & Record<string, unknown>;
  return nonEmpty(
    firstString(object, [
      'filterId',
      'backendId',
      'id',
      'effectId',
      'identifier',
      'packageId',
      'slug',
    ]),
    'filterId'
  );
}

export function normalizeWebEffect(
  value: unknown,
  fallbackPath = ''
): NosmaiFilter {
  const object =
    value && typeof value === 'object'
      ? (value as Record<string, unknown>)
      : {};

  const displayName = firstString(
    object,
    ['displayName', 'name', 'title', 'label'],
    'Nosmai Effect'
  );
  const id = firstString(
    object,
    ['id', 'effectId', 'filterId', 'identifier', 'packageId', 'slug', 'url'],
    displayName
  );
  const source = firstString(object, ['source'], 'local');
  const pathValue = firstString(object, ['url', 'path'], fallbackPath || id);
  const category = firstString(object, ['category'], 'effect');

  return {
    id,
    filterId: id,
    backendId: id,
    name: displayName,
    displayName,
    description: firstString(object, ['description']),
    path: pathValue,
    fileSize: firstNumber(object, ['fileSize', 'size']),
    location: source === 'cloud' ? 'cloud' : 'local',
    packageType: packageTypeFromCategory(category),
    isFree: firstBoolean(object, ['isFree'], true),
    isDownloaded: firstBoolean(object, ['isDownloaded']),
    previewUrl: firstString(object, ['previewUrl', 'thumbnailUrl']) || undefined,
    category: category || undefined,
    downloadCount: firstNumber(object, ['downloadCount']),
    price: firstNumber(object, ['price']),
    version: firstString(object, ['version']) || undefined,
    author: firstString(object, ['author']) || undefined,
    minSdkVersion: firstString(object, ['minSdkVersion']) || undefined,
    created: firstString(object, ['created', 'createdAt']) || undefined,
    tags: Array.isArray(object.tags)
      ? object.tags.filter((tag): tag is string => typeof tag === 'string')
      : undefined,
  };
}

function currentActiveEffects(): NosmaiActiveEffects {
  const instance = sdk;
  const effectActive = Boolean(instance?.effects?.isActive);
  const beautyActive = Number(instance?.beauty?.activeMask ?? 0) !== 0;
  const type = String(instance?.effects?.activeType ?? '').toLowerCase();

  let modeName: NosmaiActiveEffects['modeName'] = 'idle';
  if (backgroundActive && beautyActive) modeName = 'beautyBackground';
  else if (backgroundActive && effectActive) modeName = 'filtersBackground';
  else if (beautyActive) modeName = 'beautyFilters';
  else if (effectActive) modeName = 'effectsFilters';

  const packageType = currentEffectInfo?.packageType;
  const isFilter = packageType === 'filter' || type === 'filter';

  return {
    mode:
      modeName === 'idle'
        ? 0
        : modeName === 'effectsFilters'
          ? 1
          : modeName === 'filtersBackground'
            ? 2
            : modeName === 'beautyFilters'
              ? 3
              : 4,
    modeName,
    activeFilterPath: isFilter ? currentEffectSource : undefined,
    activeEffectPath: !isFilter && effectActive ? currentEffectSource : undefined,
    activeBackgroundPath: backgroundActive ? 'web:manual' : undefined,
    hasBackground: backgroundActive,
    backgroundSource: backgroundActive ? 1 : 0,
    backgroundSourceName: backgroundActive ? 'manual' : 'none',
    hasBeautyEffect: beautyActive,
    hasBuiltInBeauty: beautyActive,
    hasManualBackground: backgroundActive,
    activeFilter: isFilter ? currentEffectInfo : undefined,
    activeEffect: !isFilter ? currentEffectInfo : undefined,
  };
}

export function emitActiveEffectsChanged() {
  emit('activeEffects', currentActiveEffects());
}


let fallbackCameraStream: any = null;

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'object' && error !== null) {
    const value = error as Record<string, unknown>;
    if (typeof value.message === 'string') return value.message;
    if (typeof value.name === 'string') return value.name;
  }
  return String(error);
}

function stopFallbackCameraStream() {
  const stream = fallbackCameraStream;
  fallbackCameraStream = null;

  try {
    const tracks = stream?.getTracks?.() ?? [];
    for (const track of tracks) {
      try {
        track.stop?.();
      } catch {}
    }
  } catch {}
}

async function startBrowserCamera(instance: any): Promise<void> {
  const camera = instance.camera;
  if (camera.isRunning) return;

  let firstError: unknown;

  try {
    // Prefer the React Native API's requested mobile-style facing.
    await camera.start({ position: cameraPosition });
    return;
  } catch (error) {
    firstError = error;
    console.warn(
      `[Nosmai Web] Could not open requested "${cameraPosition}" camera. ` +
        `Falling back to the browser default camera.`,
      error
    );
  }

  try {
    if (camera.isRunning) camera.stop();
  } catch {}

  stopFallbackCameraStream();

  const nav = (globalThis as any).navigator;
  const mediaDevices = nav?.mediaDevices;

  if (!mediaDevices?.getUserMedia) {
    throw new NosmaiSdkError(
      NosmaiErrorCode.cameraUnavailable,
      'Browser camera API is unavailable. Use HTTPS or localhost in a supported browser.'
    );
  }

  try {
    // "ideal" never requires a physical front/back classification. On desktop,
    // Chrome is therefore free to use an external/default webcam.
    const preferredFacing =
      cameraPosition === 'front' ? 'user' : 'environment';

    let stream: any;

    try {
      stream = await mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: preferredFacing },
        },
        audio: false,
      });
    } catch {
      // Last-resort desktop path: request any available video input.
      stream = await mediaDevices.getUserMedia({
        video: true,
        audio: false,
      });
    }

    fallbackCameraStream = stream;

    // Web SDK alpha.2 explicitly accepts an existing MediaStream.
    await camera.start({ stream });
  } catch (fallbackError) {
    stopFallbackCameraStream();

    const first = errorMessage(firstError);
    const second = errorMessage(fallbackError);

    throw new NosmaiSdkError(
      NosmaiErrorCode.cameraUnavailable,
      `Unable to open a browser camera. Requested ${cameraPosition} camera failed: ${first}. Browser default camera failed: ${second}.`,
      {
        requestedPosition: cameraPosition,
        requestedCameraError: first,
        fallbackCameraError: second,
      }
    );
  }
}

export const webRuntime = {
  async initialize(licenseKey: string): Promise<boolean> {
    try {
      const Nosmai = await loadBridge();
      const result = await Nosmai.initialize(nonEmpty(licenseKey, 'licenseKey'), {
        assetBase: '/nosmai',
      });

      sdk = Nosmai.instance;
      licenceStatus = mapLicense(result.licence, result.ok ? 'valid' : 'invalid');
      emit('license', licenceStatus);

      if (!result.ok) {
        throw result.error ?? new NosmaiSdkError(
          NosmaiErrorCode.initialization,
          'Nosmai Web initialization failed.'
        );
      }

      if (canvas) await ensureCanvasAttached();
      return true;
    } catch (error) {
      throw report(error);
    }
  },

  setCameraConfiguration(configuration: CameraConfiguration) {
    cameraPosition = configuration.position;
  },
setMirrorMode(mode: WebMirrorMode) {
  mirrorMode = mode;

  if (sdk) {
    sdk.camera.setMirror(mode);
  }
},
  async startProcessing(): Promise<void> {
  try {
    processingRequested = true;
    paused = false;

    await ensureCanvasAttached();

    const instance = requireSdk();

    if (!instance.camera.isRunning) {
      await startBrowserCamera(instance);
    }

    // Apply mirror after camera has started.
    instance.camera.setMirror(mirrorMode);

  } catch (error) {
      processingRequested = false;
      throw report(error);
    }
  },

  async stopProcessing(): Promise<void> {
    try {
      processingRequested = false;
      paused = false;
      const instance = requireSdk();
      if (instance.camera.isRunning) instance.camera.stop();
      stopFallbackCameraStream();
    } catch (error) {
      throw report(error);
    }
  },

  async pauseCamera(): Promise<boolean> {
    try {
      const instance = requireSdk();
      if (instance.camera.isRunning) instance.camera.stop();
      paused = true;
      return true;
    } catch (error) {
      throw report(error);
    }
  },

  async resumeCamera(): Promise<boolean> {
    try {
      if (!processingRequested) {
        throw new NosmaiSdkError(
          NosmaiErrorCode.invalidState,
          'Call startProcessing() before resumeCamera().'
        );
      }
      await ensureCanvasAttached();
      const instance = requireSdk();
      if (!instance.camera.isRunning) {
        await startBrowserCamera(instance);
      }
      paused = false;
      return true;
    } catch (error) {
      throw report(error);
    }
  },

  async switchCamera(): Promise<boolean> {
    try {
      const switched = Boolean(await requireSdk().camera.switchCamera());
      if (switched) {
        cameraPosition = cameraPosition === 'front' ? 'back' : 'front';
      }
      return switched;
    } catch (error) {
      throw report(error);
    }
  },

  async cleanup(): Promise<void> {
    try {
      const instance = sdk;
      if (instance) {
        try {
          if (instance.camera?.isRunning) instance.camera.stop();
        } catch {}
        try {
          await instance.effects?.clear?.();
        } catch {}
        try {
          instance.beauty?.clear?.();
        } catch {}
        try {
          instance.background?.clear?.();
        } catch {}
        try {
          instance.color?.clear?.();
        } catch {}
        try {
          instance.dispose?.();
        } catch {}
      }
    } finally {
      sdk = null;
      canvasAttached = false;
      processingRequested = false;
      paused = false;
      backgroundActive = false;
      currentEffectSource = undefined;
      currentEffectInfo = undefined;
      licenceStatus = 'unverified';

      for (const url of generatedObjectUrls) browserUrl()?.revokeObjectURL?.(url);
      generatedObjectUrls.clear();
      cloudObjectUrls.clear();
      cloudFilterIdsByPath.clear();
    }
  },

  registerCanvas(
    nextCanvas: WebCanvas,
    callbacks: {
      onReady?: () => void;
      onError?: (error: NosmaiNativeError) => void;
    } = {}
  ): () => void {
    canvas = nextCanvas;
    canvasAttached = false;
    onViewReady = callbacks.onReady;
    onViewError = callbacks.onError;

    if (sdk) {
      void ensureCanvasAttached().catch((error) => {
        report(error);
      });
    }

    return () => {
      if (canvas === nextCanvas) {
        canvas = null;
        canvasAttached = false;
        onViewReady = undefined;
        onViewError = undefined;
      }
    };
  },

  getCanvas(): WebCanvas | null {
    return canvas;
  },

  getSdk(): any {
    return requireSdk();
  },

  isPaused(): boolean {
    return paused;
  },

  getLicenseStatus(): LicenseStatus {
    return licenceStatus;
  },

  getActiveEffects(): NosmaiActiveEffects {
    return currentActiveEffects();
  },

  setCurrentEffect(source?: string, info?: NosmaiFilter) {
    currentEffectSource = source;
    currentEffectInfo = info;
    emitActiveEffectsChanged();
  },

  clearCurrentEffect() {
    currentEffectSource = undefined;
    currentEffectInfo = undefined;
    emitActiveEffectsChanged();
  },

  setBackgroundActive(active: boolean) {
    backgroundActive = active;
    emitActiveEffectsChanged();
  },

  addListener<K extends ListenerKey>(
    key: K,
    listener: ListenerMap[K]
  ): NosmaiEventSubscription {
    const handler = listener as (payload: any) => void;
    listeners[key].add(handler);
    return {
      remove() {
        listeners[key].delete(handler);
      },
    };
  },

  emitActiveEffectsChanged() {
    emitActiveEffectsChanged();
  },

  emitDownload(filterId: string, progress: number) {
    emit('download', {
      filterId,
      progress: Math.min(Math.max(progress, 0), 1),
    });
  },

  emitRecording(durationSeconds: number) {
    emit('recording', { durationSeconds });
  },

  emitGame(event: NosmaiGameEvent) {
    emit('game', event);
  },

  async listCloud(query?: NosmaiCloudFilterQuery): Promise<NosmaiCloudFilterPage> {
    const instance = requireSdk();
    const page = query?.page ?? 1;
    const limit = query?.limit ?? 20;
    const fetchAll = query?.fetchAllPages ?? query?.page === undefined;
    const category = webCategory(query?.packageType);

    const all: NosmaiFilter[] = [];
    let currentPage = page;
    let totalPages = page;
    let total = 0;
    let hasNextPage = false;

    do {
      const raw = await instance.cloud.list({
        ...(category ? { category } : {}),
        page: currentPage,
        limit,
      });

      const items = Array.isArray(raw?.items) ? raw.items : [];
      let normalized = items.map((item: unknown) => normalizeWebEffect(item));

      if (query?.packageType === 'game') {
        normalized = normalized.filter((item: NosmaiFilter) => item.packageType === 'game');
      }

      all.push(...normalized);
      totalPages = Number(raw?.totalPages ?? currentPage) || currentPage;
      total = Number(raw?.total ?? all.length) || all.length;
      hasNextPage = Boolean(raw?.hasNextPage);

      if (!fetchAll || !hasNextPage) break;
      currentPage += 1;
    } while (currentPage <= totalPages);

    const pagination: NosmaiPaginationInfo = {
      currentPage: fetchAll ? 1 : page,
      totalPages: fetchAll ? 1 : totalPages,
      totalItems: total,
      itemsPerPage: fetchAll ? all.length : limit,
      hasNextPage: fetchAll ? false : hasNextPage,
      hasPreviousPage: fetchAll ? false : page > 1,
    };

    return { filters: all, pagination };
  },

  async downloadCloud(
    filter: NosmaiFilter | string
  ): Promise<NosmaiCloudDownloadResult> {
    const instance = requireSdk();
    const filterId = cloudIdentifier(filter);
    const alreadyDownloaded = Boolean(
      await instance.cloud.isDownloaded(filterId)
    );

    const bytes = await instance.cloud.download(
      filterId,
      (progress: number) => webRuntime.emitDownload(filterId, progress)
    );

    const oldUrl = cloudObjectUrls.get(filterId);
    if (oldUrl) {
      browserUrl()?.revokeObjectURL?.(oldUrl);
      generatedObjectUrls.delete(oldUrl);
    }

    const BlobCtor = browserBlobCtor();
    const urlApi = browserUrl();
    if (!BlobCtor || !urlApi) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.notImplemented,
        'Blob/Object URL APIs are unavailable in this browser.'
      );
    }
    const blob = new BlobCtor([bytes], { type: 'application/octet-stream' });
    const path = urlApi.createObjectURL(blob);
    cloudObjectUrls.set(filterId, path);
    cloudFilterIdsByPath.set(path, filterId);
    generatedObjectUrls.add(path);

    return {
      filterId,
      path,
      alreadyDownloaded,
    };
  },

  async removeCloud(filter: NosmaiFilter | string): Promise<boolean> {
    const instance = requireSdk();
    const filterId = cloudIdentifier(filter);
    await instance.cloud.clearCache(filterId);

    const url = cloudObjectUrls.get(filterId);
    if (url) {
      browserUrl()?.revokeObjectURL?.(url);
      cloudObjectUrls.delete(filterId);
      cloudFilterIdsByPath.delete(url);
      generatedObjectUrls.delete(url);
    }
    return true;
  },

  cloudFilterIdForPath(path: string): string | undefined {
    return cloudFilterIdsByPath.get(path);
  },

  async applyCloudFilter(filterId: string): Promise<void> {
    const instance = requireSdk();

    await instance.cloud.apply(
      filterId,
      (progress: number) => webRuntime.emitDownload(filterId, progress)
    );
  },

  createObjectUrl(blob: WebBlob): string {
    const urlApi = browserUrl();
    if (!urlApi) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.notImplemented,
        'Object URL APIs are unavailable in this browser.'
      );
    }
    const url = urlApi.createObjectURL(blob);
    generatedObjectUrls.add(url);
    return url;
  },
};
