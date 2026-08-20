import { useEffect, useState } from 'react';
import {
  Button,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import {
  NosmaiCameraSdk,
  NosmaiCameraView,
  NosmaiErrorCode,
  NosmaiSdkError,
} from '@nosmai/react-native-camera-sdk';
import type {
  NosmaiActiveEffects,
  NosmaiCloudFilterPage,
  NosmaiEffectParameter,
  NosmaiFilter,
  NosmaiFlashMode,
  NosmaiFrameMetadata,
  NosmaiPhotoResult,
  NosmaiRecordingResult,
  NosmaiTorchMode,
  PackageType,
} from '@nosmai/react-native-camera-sdk';

const CATALOG_PACKAGE_TYPES: readonly (PackageType | undefined)[] = [
  undefined,
  'filter',
  'effect',
  'background',
  'beauty_effect',
];
const CONTROL_MODES = ['camera', 'packages', 'visual'] as const;
type ControlMode = (typeof CONTROL_MODES)[number];

function describeActiveEffects(state: NosmaiActiveEffects): string {
  const activeSlots = [
    ['filter', state.activeFilterPath],
    ['effect', state.activeEffectPath],
    ['background', state.activeBackgroundPath],
  ]
    .filter((value): value is [string, string] => Boolean(value[1]))
    .map(([slot, path]) => {
      const segments = path.split('/').filter(Boolean);
      return `${slot}=${segments[segments.length - 1] ?? path}`;
    });

  return `${state.modeName}: ${activeSlots.length > 0 ? activeSlots.join(', ') : 'none'}`;
}

function describeActiveEffectsForAutomation(
  state: NosmaiActiveEffects
): string {
  return [
    `mode=${state.modeName}`,
    `filter=${state.activeFilterPath ?? 'none'}`,
    `effect=${state.activeEffectPath ?? 'none'}`,
    `background=${state.activeBackgroundPath ?? 'none'}`,
  ].join('; ');
}

function describeFilter(filter: NosmaiFilter | undefined): string {
  if (!filter) {
    return 'none';
  }

  const segments = filter.path.split('/').filter(Boolean);
  const label =
    filter.displayName ||
    filter.name ||
    segments[segments.length - 1] ||
    filter.path;
  return `${label} (${filter.packageType})`;
}

function formatDuration(durationSeconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(durationSeconds));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${seconds.toString().padStart(2, '0')}`;
}

function mediaFileName(uri: string): string {
  const segments = uri.split('/').filter(Boolean);
  return segments[segments.length - 1] ?? uri;
}

function describeEffectParameter(
  parameter: NosmaiEffectParameter | undefined
): string {
  if (!parameter) {
    return 'none';
  }
  const currentValue =
    parameter.currentValue === null
      ? 'unavailable'
      : JSON.stringify(parameter.currentValue);
  return `${parameter.name} (${parameter.type})=${currentValue}`;
}

function describeFrame(metadata: NosmaiFrameMetadata | undefined): string {
  if (!metadata) {
    return 'Frame stream: no frame available';
  }
  return `Frame #${metadata.sequence}: ${metadata.width}x${metadata.height} ${metadata.format}/${metadata.colorRange}, ${metadata.byteLength} bytes, dropped=${metadata.droppedFrames}`;
}

function retainFrameMetadata(
  metadata: NosmaiFrameMetadata
): NosmaiFrameMetadata {
  return {
    sequence: metadata.sequence,
    timestampSeconds: metadata.timestampSeconds,
    width: metadata.width,
    height: metadata.height,
    format: metadata.format,
    colorRange: metadata.colorRange,
    byteLength: metadata.byteLength,
    planes: metadata.planes,
    droppedFrames: metadata.droppedFrames,
  };
}

export default function App() {
  const [licenseKey, setLicenseKey] = useState('');
  const [cameraPosition, setCameraPosition] = useState<'front' | 'back'>(
    'front'
  );
  const [effectState, setEffectState] = useState('idle: none');
  const [effectStateDetails, setEffectStateDetails] = useState(
    'mode=idle; filter=none; effect=none; background=none'
  );
  const [catalogState, setCatalogState] = useState('Catalog: not queried');
  const [activePackageInfo, setActivePackageInfo] = useState(
    'Active package info: not queried'
  );
  const [effectParameters, setEffectParameters] = useState<
    NosmaiEffectParameter[]
  >([]);
  const [selectedEffectParameterIndex, setSelectedEffectParameterIndex] =
    useState(0);
  const [effectParameterValue, setEffectParameterValue] = useState('0.5');
  const [catalogTypeIndex, setCatalogTypeIndex] = useState(0);
  const [recording, setRecording] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);
  const [lastPhoto, setLastPhoto] = useState<NosmaiPhotoResult>();
  const [lastRecording, setLastRecording] = useState<NosmaiRecordingResult>();
  const [flashAvailable, setFlashAvailable] = useState<boolean>();
  const [torchAvailable, setTorchAvailable] = useState<boolean>();
  const [flashMode, setFlashMode] = useState<NosmaiFlashMode>('off');
  const [torchMode, setTorchMode] = useState<NosmaiTorchMode>('off');
  const [frameStreamActive, setFrameStreamActive] = useState(false);
  const [latestFrameMetadata, setLatestFrameMetadata] =
    useState<NosmaiFrameMetadata>();
  const [busy, setBusy] = useState(false);
  const [controlMode, setControlMode] = useState<ControlMode>('camera');
  const [cloudCatalog, setCloudCatalog] = useState<NosmaiCloudFilterPage>();
  const [cloudPage, setCloudPage] = useState(1);
  const [cloudFetchAll, setCloudFetchAll] = useState(false);
  const [selectedCloudIndex, setSelectedCloudIndex] = useState(0);
  const [downloadProgress, setDownloadProgress] = useState('Download: idle');
  const [visualState, setVisualState] = useState(
    'Visual capabilities: not queried'
  );
  const [backgroundUri, setBackgroundUri] = useState('');
  const [status, setStatus] = useState(
    Platform.OS === 'ios'
      ? 'Phase 4 iOS feature harness ready'
      : 'Phase 4 Android feature harness ready'
  );
  const catalogPackageType = CATALOG_PACKAGE_TYPES[catalogTypeIndex];
  const selectedCloudFilter = cloudCatalog?.filters[selectedCloudIndex];
  const selectedEffectParameter =
    effectParameters[selectedEffectParameterIndex];

  useEffect(() => {
    const licenseSubscription = NosmaiCameraSdk.addLicenseStatusChangedListener(
      (value) => {
        setStatus(`License: ${value}`);
      }
    );
    const errorSubscription = NosmaiCameraSdk.addErrorListener((error) => {
      if (error.code === NosmaiErrorCode.recordingInterrupted) {
        setRecording(false);
      }
      setStatus(`${error.code}: ${error.message}`);
    });
    const effectSubscription = NosmaiCameraSdk.addActiveEffectsChangedListener(
      (state) => {
        setEffectState(describeActiveEffects(state));
        setEffectStateDetails(describeActiveEffectsForAutomation(state));
      }
    );
    const recordingSubscription = NosmaiCameraSdk.addRecordingProgressListener(
      ({ durationSeconds }) => {
        setRecordingDuration(durationSeconds);
      }
    );
    const downloadSubscription = NosmaiCameraSdk.addDownloadProgressListener(
      ({ filterId, progress }) => {
        setDownloadProgress(
          `Download ${filterId}: ${Math.round(progress * 100)}%`
        );
      }
    );
    const frameSubscription = NosmaiCameraSdk.addFrameAvailableListener(
      (metadata) => {
        setLatestFrameMetadata(metadata);
      }
    );

    return () => {
      licenseSubscription.remove();
      errorSubscription.remove();
      effectSubscription.remove();
      recordingSubscription.remove();
      downloadSubscription.remove();
      frameSubscription.remove();
      NosmaiCameraSdk.cleanup().catch(() => undefined);
    };
  }, []);

  const run = async (label: string, operation: () => Promise<unknown>) => {
    if (busy) {
      return;
    }

    setBusy(true);
    try {
      setStatus(`${label}…`);
      const result = await operation();
      const resultText =
        typeof result === 'boolean' || typeof result === 'string'
          ? `: ${result}`
          : '';
      setStatus(`${label} completed${resultText}`);
    } catch (error) {
      const sdkError = NosmaiSdkError.fromUnknown(error);
      setStatus(`${sdkError.code}: ${sdkError.message}`);
    } finally {
      setBusy(false);
    }
  };

  const startCamera = async () => {
    if (Platform.OS === 'android') {
      const permission = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CAMERA
      );
      if (permission !== PermissionsAndroid.RESULTS.GRANTED) {
        throw new NosmaiSdkError(
          NosmaiErrorCode.cameraPermission,
          'Camera permission was not granted.'
        );
      }
    }

    await NosmaiCameraSdk.configureCamera({ position: cameraPosition });
    await NosmaiCameraSdk.startProcessing();
    await refreshLightCapabilities();
  };

  const stopCamera = async () => {
    await NosmaiCameraSdk.stopProcessing();
    setFrameStreamActive(false);
    setTorchMode('off');
  };

  const pauseCamera = async () => {
    const paused = await NosmaiCameraSdk.pauseCamera();
    if (paused) {
      setFrameStreamActive(false);
      setTorchMode('off');
    }
    return paused;
  };

  const resumeCamera = async () => {
    const resumed = await NosmaiCameraSdk.resumeCamera();
    if (resumed) {
      await refreshLightCapabilities();
    }
    return resumed;
  };

  const switchCamera = async () => {
    const switched = await NosmaiCameraSdk.switchCamera();
    if (switched) {
      setCameraPosition((value) => (value === 'front' ? 'back' : 'front'));
      setFrameStreamActive(false);
      setLatestFrameMetadata(undefined);
      await refreshLightCapabilities();
    }
    return switched;
  };

  const refreshLightCapabilities = async () => {
    const [hasFlash, hasTorch, currentFlashMode, currentTorchMode] =
      await Promise.all([
        NosmaiCameraSdk.hasFlash(),
        NosmaiCameraSdk.hasTorch(),
        NosmaiCameraSdk.getFlashMode(),
        NosmaiCameraSdk.getTorchMode(),
      ]);
    setFlashAvailable(hasFlash);
    setTorchAvailable(hasTorch);
    setFlashMode(currentFlashMode);
    setTorchMode(currentTorchMode);
    return `flash=${hasFlash} (${currentFlashMode}); torch=${hasTorch} (${currentTorchMode})`;
  };

  const cycleFlashMode = async () => {
    const modes: readonly NosmaiFlashMode[] = ['off', 'on', 'auto'];
    const currentIndex = modes.indexOf(flashMode);
    const nextMode = modes[(currentIndex + 1) % modes.length]!;
    const applied = await NosmaiCameraSdk.setFlashMode(nextMode);
    if (applied) {
      setFlashMode(nextMode);
    }
    return applied;
  };

  const toggleTorch = async () => {
    const nextMode: NosmaiTorchMode = torchMode === 'off' ? 'on' : 'off';
    const applied = await NosmaiCameraSdk.setTorchMode(nextMode);
    if (applied) {
      setTorchMode(nextMode);
    }
    return applied;
  };

  const startFrameStream = async () => {
    await NosmaiCameraSdk.startFrameStream({ maxFramesPerSecond: 2 });
    setLatestFrameMetadata(undefined);
    setFrameStreamActive(true);
    return 'latest-only stream active at up to 2 fps';
  };

  const stopFrameStream = async () => {
    await NosmaiCameraSdk.stopFrameStream();
    setFrameStreamActive(false);
    return 'stopped';
  };

  const pullLatestFrame = async () => {
    const frame = await NosmaiCameraSdk.getLatestFrame();
    if (!frame) {
      setLatestFrameMetadata(undefined);
      return 'slot empty';
    }
    setLatestFrameMetadata(retainFrameMetadata(frame));
    return `frame #${frame.sequence}, ${frame.byteLength} bytes`;
  };

  const ensureAndroidAudioPermission = async () => {
    if (Platform.OS !== 'android') {
      return;
    }
    const permission = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO
    );
    if (permission !== PermissionsAndroid.RESULTS.GRANTED) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.recordingPermission,
        'Microphone permission was not granted.'
      );
    }
  };

  const ensureAndroidGalleryPermission = async () => {
    if (
      Platform.OS !== 'android' ||
      typeof Platform.Version !== 'number' ||
      Platform.Version >= 29
    ) {
      return;
    }
    const permission = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE
    );
    if (permission !== PermissionsAndroid.RESULTS.GRANTED) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.galleryPermission,
        'Gallery write permission was not granted.'
      );
    }
  };

  const capturePhoto = async () => {
    const photo = await NosmaiCameraSdk.capturePhoto();
    setLastPhoto(photo);
    return `${mediaFileName(photo.uri)} (${photo.width}x${photo.height})`;
  };

  const startRecording = async () => {
    await ensureAndroidAudioPermission();
    await NosmaiCameraSdk.startRecording();
    setLastRecording(undefined);
    setRecordingDuration(0);
    setRecording(true);
  };

  const stopRecording = async () => {
    try {
      const result = await NosmaiCameraSdk.stopRecording();
      setRecordingDuration(result.durationSeconds);
      setLastRecording(result);
      return `${mediaFileName(result.uri)} (${formatDuration(
        result.durationSeconds
      )}, audio=${result.hasAudio})`;
    } finally {
      const stillRecording = await NosmaiCameraSdk.isRecording().catch(
        () => false
      );
      setRecording(stillRecording);
    }
  };

  const saveLastPhoto = async () => {
    if (!lastPhoto) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Capture a photo before saving it.'
      );
    }
    await ensureAndroidGalleryPermission();
    const saved = await NosmaiCameraSdk.saveImageToGallery(lastPhoto.uri);
    return saved.uri;
  };

  const saveLastVideo = async () => {
    if (!lastRecording) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Record a video before saving it.'
      );
    }
    await ensureAndroidGalleryPermission();
    const saved = await NosmaiCameraSdk.saveVideoToGallery(lastRecording.uri);
    return saved.uri;
  };

  const refreshActiveEffects = async () => {
    const state = await NosmaiCameraSdk.getActiveEffects();
    setEffectState(describeActiveEffects(state));
    setEffectStateDetails(describeActiveEffectsForAutomation(state));
  };

  const refreshActivePackageInfo = async () => {
    const filter = await NosmaiCameraSdk.getActiveFilterInfo();
    const effect = await NosmaiCameraSdk.getActiveEffectInfo();
    setActivePackageInfo(
      `Filter: ${describeFilter(filter)}; AR/beauty: ${describeFilter(effect)}`
    );
  };

  const clearAllEffects = async () => {
    await NosmaiCameraSdk.clearAll();
    await refreshActiveEffects();
    await refreshActivePackageInfo();
    setEffectParameters([]);
    setSelectedEffectParameterIndex(0);
  };

  const clearFilter = async () => {
    await NosmaiCameraSdk.clearFilter();
    await refreshActiveEffects();
    await refreshActivePackageInfo();
  };

  const clearAREffect = async () => {
    await NosmaiCameraSdk.clearAREffect();
    await refreshActiveEffects();
    await refreshActivePackageInfo();
    setEffectParameters([]);
    setSelectedEffectParameterIndex(0);
  };

  const refreshEffectParameters = async (preferredName?: string) => {
    const parameters = await NosmaiCameraSdk.getEffectParameters();
    setEffectParameters(parameters);
    const preferredIndex = preferredName
      ? parameters.findIndex((parameter) => parameter.name === preferredName)
      : -1;
    setSelectedEffectParameterIndex(preferredIndex >= 0 ? preferredIndex : 0);
    return `${parameters.length} authored parameter${parameters.length === 1 ? '' : 's'}`;
  };

  const selectNextEffectParameter = () => {
    if (effectParameters.length === 0) {
      return;
    }
    setSelectedEffectParameterIndex(
      (value) => (value + 1) % effectParameters.length
    );
  };

  const readSelectedEffectParameter = async () => {
    if (!selectedEffectParameter) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Query and select an authored effect parameter first.'
      );
    }
    const value = await NosmaiCameraSdk.getEffectParameterValue(
      selectedEffectParameter.name
    );
    setEffectParameterValue(String(value));
    await refreshEffectParameters(selectedEffectParameter.name);
    return value;
  };

  const setSelectedEffectParameterNumber = async () => {
    if (!selectedEffectParameter) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Query and select an authored effect parameter first.'
      );
    }
    const applied = await NosmaiCameraSdk.setEffectParameter(
      selectedEffectParameter.name,
      Number(effectParameterValue)
    );
    await refreshEffectParameters(selectedEffectParameter.name);
    return applied;
  };

  const setSelectedEffectParameterString = async () => {
    if (!selectedEffectParameter) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Query and select an authored effect parameter first.'
      );
    }
    const applied = await NosmaiCameraSdk.setEffectParameterString(
      selectedEffectParameter.name,
      effectParameterValue
    );
    await refreshEffectParameters(selectedEffectParameter.name);
    return applied;
  };

  const refreshLocalCatalog = async () => {
    const filters = await NosmaiCameraSdk.getLocalFilters(catalogPackageType);
    const counts = filters.reduce<Record<string, number>>((result, filter) => {
      result[filter.packageType] = (result[filter.packageType] ?? 0) + 1;
      return result;
    }, {});
    const summary = Object.entries(counts)
      .map(([type, count]) => `${type}=${count}`)
      .join(', ');
    setCatalogState(
      `Catalog ${catalogPackageType ?? 'all'}: ${filters.length}${
        summary.length > 0 ? ` (${summary})` : ''
      }`
    );
  };

  const refreshCloudCatalog = async (
    page = cloudPage,
    preferredFilterId: string | null = null,
    fetchAllPages = cloudFetchAll
  ) => {
    const cloudEnabled = await NosmaiCameraSdk.isCloudFilterEnabled();
    if (!cloudEnabled) {
      setCloudCatalog(undefined);
      setSelectedCloudIndex(0);
      setCatalogState('Cloud filters are disabled for this license.');
      throw new NosmaiSdkError(
        NosmaiErrorCode.cloudDisabled,
        'Cloud filters are disabled for this license.'
      );
    }
    const result = await NosmaiCameraSdk.getCloudFilters(
      fetchAllPages
        ? {
            packageType: catalogPackageType,
            limit: 20,
            fetchAllPages: true,
          }
        : {
            packageType: catalogPackageType,
            page,
            limit: 10,
          }
    );
    setCloudCatalog(result);
    setCloudPage(result.pagination.currentPage);
    setCloudFetchAll(fetchAllPages);
    const preferredIndex =
      preferredFilterId === null
        ? -1
        : result.filters.findIndex((filter) => filter.id === preferredFilterId);
    const nextIndex = preferredIndex >= 0 ? preferredIndex : 0;
    setSelectedCloudIndex(nextIndex);
    const selected = result.filters[nextIndex];
    setCatalogState(
      `Cloud enabled; ${catalogPackageType ?? 'all'}: page ${result.pagination.currentPage}/${result.pagination.totalPages}, ${result.pagination.totalItems} total${
        selected ? `; selected=${selected.displayName}` : ''
      }`
    );
    return result.filters.length;
  };

  const selectNextCloudFilter = () => {
    const filterCount = cloudCatalog?.filters.length ?? 0;
    if (filterCount === 0) {
      return;
    }
    setSelectedCloudIndex((value) => (value + 1) % filterCount);
  };

  const cycleCatalogPackageType = () => {
    setCatalogTypeIndex((value) => (value + 1) % CATALOG_PACKAGE_TYPES.length);
    setCloudPage(1);
    setCloudFetchAll(false);
    setCloudCatalog(undefined);
    setSelectedCloudIndex(0);
    setDownloadProgress('Download: idle');
  };

  const downloadSelectedCloudFilter = async () => {
    if (!selectedCloudFilter) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Fetch and select a cloud filter first.'
      );
    }
    setDownloadProgress(`Download ${selectedCloudFilter.id}: starting`);
    const result =
      await NosmaiCameraSdk.downloadCloudFilter(selectedCloudFilter);
    setDownloadProgress(
      `Download ${result.filterId}: ready${
        result.alreadyDownloaded ? ' (cached)' : ''
      }`
    );
    await refreshCloudCatalog(cloudPage, result.filterId);
    return result.path;
  };

  const applySelectedCloudFilter = async () => {
    if (!selectedCloudFilter?.isDownloaded || !selectedCloudFilter.path) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Download the selected cloud filter before applying it.'
      );
    }
    return NosmaiCameraSdk.applyEffect(selectedCloudFilter.path);
  };

  const removeSelectedCloudFilter = async () => {
    if (!selectedCloudFilter) {
      throw new NosmaiSdkError(
        NosmaiErrorCode.invalidState,
        'Fetch and select a cloud filter first.'
      );
    }
    const removed =
      await NosmaiCameraSdk.removeCloudFilter(selectedCloudFilter);
    await refreshCloudCatalog(cloudPage, null);
    return removed;
  };

  const refreshVisualCapabilities = async () => {
    const [beauty, advanced] = await Promise.all([
      NosmaiCameraSdk.isBeautyEffectEnabled(),
      NosmaiCameraSdk.isAdvancedFiltersEnabled(),
    ]);
    const [lipstickResult, eyeColorResult] = await Promise.allSettled([
      NosmaiCameraSdk.isMakeupActive('lipstick'),
      NosmaiCameraSdk.isEyeColorActive(),
    ]);
    const lipstick =
      lipstickResult?.status === 'fulfilled'
        ? lipstickResult.value
        : 'unavailable';
    const eyeColor =
      eyeColorResult?.status === 'fulfilled'
        ? eyeColorResult.value
        : 'unavailable';
    const summary = `Beauty=${beauty}; advanced=${advanced}; lipstick=${lipstick}; eyeColor=${eyeColor}`;
    setVisualState(summary);
    return summary;
  };

  const applyBeautyPreset = async () => {
    await NosmaiCameraSdk.setSkinSmoothing(0.55);
    await NosmaiCameraSdk.setSkinWhitening(0.25);
    await NosmaiCameraSdk.setTeethWhitening(0.35);
  };

  const applyMakeupPreset = async () => {
    await NosmaiCameraSdk.applyMakeup({
      type: 'lipstick',
      style: 'matte',
      color: { red: 0.8, green: 0.12, blue: 0.24 },
      intensity: 0.7,
    });
    await NosmaiCameraSdk.applyMakeup({
      type: 'eyeshadow',
      style: 'shimmer',
      color: { red: 0.4, green: 0.22, blue: 0.65 },
      intensity: 0.45,
    });
    await NosmaiCameraSdk.applyMakeup({
      type: 'blusher',
      style: 'natural',
      color: { red: 0.95, green: 0.35, blue: 0.42 },
      intensity: 0.35,
    });
    await NosmaiCameraSdk.applyMakeup({
      type: 'eyelash',
      style: 'wispy',
      intensity: 0.6,
    });
    await NosmaiCameraSdk.applyMakeup({
      type: 'eyebrow',
      style: 'arched',
      color: { red: 0.2, green: 0.12, blue: 0.08 },
      intensity: 0.55,
    });
  };

  const applyReshapePreset = async () => {
    await NosmaiCameraSdk.setReshape('faceSlim', 0.35);
    await NosmaiCameraSdk.setReshape('eye', 0.2);
    await NosmaiCameraSdk.setReshape('nose', 0.15);
    await NosmaiCameraSdk.setReshape('chin', 0.1);
  };

  const applyEyeColorPreset = () =>
    NosmaiCameraSdk.setEyeColor({ red: 0.12, green: 0.55, blue: 0.85 }, 0.55);

  const applyColorPreset = async () => {
    await NosmaiCameraSdk.setBrightness(0.05);
    await NosmaiCameraSdk.setContrast(1.1);
    await NosmaiCameraSdk.setRgbAdjustment({ red: 1.04, green: 1, blue: 0.96 });
    await NosmaiCameraSdk.setSharpening(0.2);
    await NosmaiCameraSdk.setGrayscale(false);
    await NosmaiCameraSdk.setHue(8);
    await NosmaiCameraSdk.setWhiteBalance({ temperature: 6500, tint: 0 });
  };

  const applyHsbPreset = () =>
    NosmaiCameraSdk.setHsb({
      hue: 5,
      saturation: 1.05,
      brightness: 1.02,
    });

  const clearVisuals = async () => {
    await NosmaiCameraSdk.clearBeauty();
    await NosmaiCameraSdk.resetColorAdjustments();
    await NosmaiCameraSdk.clearBackground();
    await refreshVisualCapabilities();
  };

  const cleanup = async () => {
    await NosmaiCameraSdk.cleanup();
    setRecording(false);
    setRecordingDuration(0);
    setFrameStreamActive(false);
    setLatestFrameMetadata(undefined);
    setFlashMode('off');
    setTorchMode('off');
    setEffectState('idle: none');
    setEffectStateDetails(
      'mode=idle; filter=none; effect=none; background=none'
    );
    setActivePackageInfo('Active package info: not queried');
    setEffectParameters([]);
    setSelectedEffectParameterIndex(0);
  };

  return (
    <View style={styles.screen}>
      <StatusBar barStyle="light-content" />
      <View style={styles.preview}>
        <NosmaiCameraView
          style={StyleSheet.absoluteFill}
          cameraPosition={cameraPosition}
          mirror={cameraPosition === 'front'}
          onReady={({ platform }) => setStatus(`Camera ready on ${platform}`)}
          onError={(error) => setStatus(`${error.code}: ${error.message}`)}
        />
      </View>

      <ScrollView
        contentContainerStyle={styles.controls}
        style={styles.controlsScroll}
      >
        <View style={styles.titleRow}>
          <Text style={styles.title}>Nosmai Camera SDK</Text>
          <Text
            accessibilityLabel={`Current controls: ${controlMode}. Show next control group.`}
            accessibilityRole="button"
            onPress={() => {
              if (!busy) {
                setControlMode((value) => {
                  const index = CONTROL_MODES.indexOf(value);
                  return CONTROL_MODES[(index + 1) % CONTROL_MODES.length]!;
                });
              }
            }}
            style={styles.modeToggle}
            testID="controls-mode-toggle"
          >
            {controlMode === 'camera'
              ? 'Packages'
              : controlMode === 'packages'
                ? 'Visual'
                : 'Camera'}
          </Text>
        </View>
        <Text style={styles.status}>{status}</Text>
        {controlMode === 'packages' ? (
          <>
            <Text
              accessibilityLabel={`Effect state: ${effectStateDetails}`}
              numberOfLines={2}
              style={styles.effectState}
              testID="effect-state"
            >
              Effect state: {effectState}
            </Text>
            <Text style={styles.effectState} testID="catalog-state">
              {catalogState}
            </Text>
            <Text style={styles.effectState} testID="active-package-info">
              {activePackageInfo}
            </Text>
            <Text style={styles.effectState} testID="effect-parameter-state">
              Authored parameters: {effectParameters.length}; selected=
              {describeEffectParameter(selectedEffectParameter)}
            </Text>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Query"
                onPress={() => run('Query effects', refreshActiveEffects)}
                testID="query-effects"
              />
              <Button
                disabled={busy}
                title="Info"
                onPress={() =>
                  run('Query active package info', refreshActivePackageInfo)
                }
                testID="query-active-info"
              />
              <Button
                disabled={busy}
                title="Catalog"
                onPress={() => run('Query local catalog', refreshLocalCatalog)}
                testID="query-local-catalog"
              />
              <Button
                disabled={busy}
                title={`Type: ${catalogPackageType ?? 'all'}`}
                onPress={cycleCatalogPackageType}
                testID="catalog-package-type"
              />
            </View>
            <TextInput
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setEffectParameterValue}
              placeholder="Selected parameter value"
              placeholderTextColor="#7f8795"
              style={styles.input}
              value={effectParameterValue}
            />
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Parameters"
                onPress={() =>
                  run('Query effect parameters', refreshEffectParameters)
                }
                testID="query-effect-parameters"
              />
              <Button
                disabled={busy || effectParameters.length === 0}
                title="Select Next"
                onPress={selectNextEffectParameter}
                testID="select-next-effect-parameter"
              />
              <Button
                disabled={busy || !selectedEffectParameter}
                title="Read Number"
                onPress={() =>
                  run('Read effect parameter', readSelectedEffectParameter)
                }
                testID="read-effect-parameter"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || !selectedEffectParameter}
                title="Set Number"
                onPress={() =>
                  run(
                    'Set numeric effect parameter',
                    setSelectedEffectParameterNumber
                  )
                }
                testID="set-effect-parameter-number"
              />
              <Button
                disabled={busy || !selectedEffectParameter}
                title="Set Text"
                onPress={() =>
                  run(
                    'Set string effect parameter',
                    setSelectedEffectParameterString
                  )
                }
                testID="set-effect-parameter-string"
              />
            </View>
            <Text style={styles.effectState} testID="cloud-selected-filter">
              Cloud selected:{' '}
              {selectedCloudFilter
                ? `${selectedCloudFilter.displayName} (${selectedCloudFilter.isDownloaded ? 'downloaded' : 'remote'})`
                : 'none'}
            </Text>
            <Text style={styles.effectState} testID="cloud-download-progress">
              {downloadProgress}
            </Text>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || cloudFetchAll || cloudPage <= 1}
                title="Prev"
                onPress={() =>
                  run('Previous cloud page', () =>
                    refreshCloudCatalog(cloudPage - 1, null, false)
                  )
                }
                testID="cloud-previous-page"
              />
              <Button
                disabled={busy}
                title={cloudFetchAll ? 'Cloud all' : `Cloud p${cloudPage}`}
                onPress={() => run('Query cloud catalog', refreshCloudCatalog)}
                testID="query-cloud-catalog"
              />
              <Button
                disabled={
                  busy ||
                  cloudFetchAll ||
                  cloudPage >= (cloudCatalog?.pagination.totalPages ?? 1)
                }
                title="Next"
                onPress={() =>
                  run('Next cloud page', () =>
                    refreshCloudCatalog(cloudPage + 1, null, false)
                  )
                }
                testID="cloud-next-page"
              />
              <Button
                disabled={busy}
                title="All"
                onPress={() =>
                  run('Fetch all cloud filters', () =>
                    refreshCloudCatalog(1, null, true)
                  )
                }
                testID="query-all-cloud-filters"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || !selectedCloudFilter}
                title="Select Next"
                onPress={selectNextCloudFilter}
                testID="select-next-cloud-filter"
              />
              <Button
                disabled={busy || !selectedCloudFilter}
                title="Download"
                onPress={() =>
                  run('Download cloud filter', downloadSelectedCloudFilter)
                }
                testID="download-cloud-filter"
              />
              <Button
                disabled={busy || !selectedCloudFilter?.isDownloaded}
                title="Apply Cloud"
                onPress={() =>
                  run('Apply cloud filter', applySelectedCloudFilter)
                }
                testID="apply-cloud-filter"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || !selectedCloudFilter?.isDownloaded}
                title="Remove Download"
                onPress={() =>
                  run('Remove cloud filter', removeSelectedCloudFilter)
                }
                testID="remove-cloud-filter"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Clear Filter"
                onPress={() => run('Clear filter', clearFilter)}
                testID="clear-filter"
              />
              <Button
                accessibilityLabel="Clear AR and beauty effect"
                disabled={busy}
                title="Clear AR"
                onPress={() => run('Clear AR/beauty effect', clearAREffect)}
                testID="clear-ar-effect"
              />
              <Button
                disabled={busy}
                title="Clear All"
                onPress={() => run('Clear all effects', clearAllEffects)}
                testID="clear-all-effects"
              />
            </View>
          </>
        ) : controlMode === 'visual' ? (
          <>
            <Text style={styles.effectState} testID="visual-capabilities">
              {visualState}
            </Text>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Capabilities"
                onPress={() =>
                  run('Query visual capabilities', refreshVisualCapabilities)
                }
                testID="query-visual-capabilities"
              />
              <Button
                disabled={busy}
                title="Beauty"
                onPress={() => run('Apply beauty preset', applyBeautyPreset)}
                testID="apply-beauty-preset"
              />
              <Button
                disabled={busy}
                title="Clear Beauty"
                onPress={() =>
                  run('Clear beauty', () => NosmaiCameraSdk.clearBeauty())
                }
                testID="clear-beauty"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Makeup"
                onPress={() => run('Apply makeup preset', applyMakeupPreset)}
                testID="apply-makeup-preset"
              />
              <Button
                disabled={busy}
                title="Reshape"
                onPress={() => run('Apply reshape preset', applyReshapePreset)}
                testID="apply-reshape-preset"
              />
              <Button
                disabled={busy}
                title="Eye Color"
                onPress={() => run('Apply eye color', applyEyeColorPreset)}
                testID="apply-eye-color"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Clear Makeup"
                onPress={() =>
                  run('Clear makeup', () => NosmaiCameraSdk.clearMakeup())
                }
                testID="clear-makeup"
              />
              <Button
                disabled={busy}
                title="Clear Reshape"
                onPress={() =>
                  run('Clear reshape', () => NosmaiCameraSdk.clearReshaping())
                }
                testID="clear-reshape"
              />
              <Button
                disabled={busy}
                title="Clear Eyes"
                onPress={() =>
                  run('Clear eye color', () => NosmaiCameraSdk.removeEyeColor())
                }
                testID="clear-eye-color"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Color"
                onPress={() => run('Apply color preset', applyColorPreset)}
                testID="apply-color-preset"
              />
              <Button
                disabled={busy}
                title="HSB"
                onPress={() => run('Apply HSB preset', applyHsbPreset)}
                testID="apply-hsb-preset"
              />
              <Button
                disabled={busy}
                title="Reset Color"
                onPress={() =>
                  run('Reset color', () =>
                    NosmaiCameraSdk.resetColorAdjustments()
                  )
                }
                testID="reset-color"
              />
            </View>
            <TextInput
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setBackgroundUri}
              placeholder="Absolute file:// image or video URI"
              placeholderTextColor="#7f8795"
              style={styles.input}
              value={backgroundUri}
            />
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Blur BG"
                onPress={() =>
                  run('Apply blur background', () =>
                    NosmaiCameraSdk.setBackground({
                      mode: 'blur',
                      blurStrength: 0.6,
                    })
                  )
                }
                testID="background-blur"
              />
              <Button
                disabled={busy}
                title="Color BG"
                onPress={() =>
                  run('Apply color background', () =>
                    NosmaiCameraSdk.setBackground({
                      mode: 'color',
                      color: { red: 0.08, green: 0.12, blue: 0.2 },
                    })
                  )
                }
                testID="background-color"
              />
              <Button
                disabled={busy}
                title="Clear BG"
                onPress={() =>
                  run('Clear background', () =>
                    NosmaiCameraSdk.clearBackground()
                  )
                }
                testID="background-clear"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy}
                title="Image BG"
                onPress={() =>
                  run('Apply image background', () =>
                    NosmaiCameraSdk.setBackground({
                      mode: 'image',
                      uri: backgroundUri,
                    })
                  )
                }
                testID="background-image"
              />
              <Button
                disabled={busy}
                title="Video BG"
                onPress={() =>
                  run('Apply video background', () =>
                    NosmaiCameraSdk.setBackground({
                      mode: 'video',
                      uri: backgroundUri,
                    })
                  )
                }
                testID="background-video"
              />
              <Button
                disabled={busy}
                title="Clear Visuals"
                onPress={() => run('Clear visual controls', clearVisuals)}
                testID="clear-visuals"
              />
            </View>
          </>
        ) : (
          <>
            <Text style={styles.effectState} testID="camera-light-state">
              Flash:{' '}
              {flashAvailable === undefined
                ? 'not queried'
                : flashAvailable
                  ? flashMode
                  : 'unavailable'}
              ; Torch:{' '}
              {torchAvailable === undefined
                ? 'not queried'
                : torchAvailable
                  ? torchMode
                  : 'unavailable'}
            </Text>
            <Text style={styles.effectState} testID="frame-stream-state">
              {frameStreamActive ? 'Streaming; ' : 'Stopped; '}
              {describeFrame(latestFrameMetadata)}
            </Text>
            <TextInput
              autoCapitalize="none"
              autoCorrect={false}
              onChangeText={setLicenseKey}
              placeholder="Paste a development license key"
              placeholderTextColor="#7f8795"
              secureTextEntry
              style={styles.input}
              value={licenseKey}
            />
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || recording || licenseKey.trim().length === 0}
                title="Initialize"
                onPress={() =>
                  run('Initialize', () =>
                    NosmaiCameraSdk.initialize(licenseKey)
                  )
                }
              />
              <Button
                disabled={busy || recording}
                title="Start"
                onPress={() => run('Start processing', startCamera)}
              />
              <Button
                disabled={busy || recording}
                title="Stop"
                onPress={() => run('Stop processing', stopCamera)}
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || recording}
                title="Pause"
                onPress={() => run('Pause camera', pauseCamera)}
              />
              <Button
                disabled={busy}
                title="Resume"
                onPress={() => run('Resume camera', resumeCamera)}
              />
              <Button
                disabled={busy || recording}
                title="Switch"
                onPress={() => run('Switch camera', switchCamera)}
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || recording}
                title="Lights"
                onPress={() =>
                  run('Query flash and torch', refreshLightCapabilities)
                }
                testID="query-camera-lights"
              />
              <Button
                disabled={busy || recording || flashAvailable === false}
                title={`Flash ${flashMode}`}
                onPress={() => run('Set flash mode', cycleFlashMode)}
                testID="cycle-flash-mode"
              />
              <Button
                disabled={busy || recording || torchAvailable === false}
                title={`Torch ${torchMode}`}
                onPress={() => run('Set torch mode', toggleTorch)}
                testID="toggle-torch-mode"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || recording || frameStreamActive}
                title="Start Frames"
                onPress={() => run('Start frame stream', startFrameStream)}
                testID="start-frame-stream"
              />
              <Button
                disabled={busy || !frameStreamActive}
                title="Pull Frame"
                onPress={() => run('Pull latest frame', pullLatestFrame)}
                testID="pull-latest-frame"
              />
              <Button
                disabled={busy || !frameStreamActive}
                title="Stop Frames"
                onPress={() => run('Stop frame stream', stopFrameStream)}
                testID="stop-frame-stream"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || recording}
                title="Capture"
                onPress={() => run('Capture photo', capturePhoto)}
                testID="capture-photo"
              />
              <Button
                disabled={busy || recording || frameStreamActive}
                title="Record"
                onPress={() => run('Start recording', startRecording)}
                testID="start-recording"
              />
              <Button
                disabled={busy || !recording}
                title={`Stop ${formatDuration(recordingDuration)}`}
                onPress={() => run('Stop recording', stopRecording)}
                testID="stop-recording"
              />
            </View>
            <View style={styles.buttonRow}>
              <Button
                disabled={busy || !lastPhoto || recording}
                title="Save Photo"
                onPress={() => run('Save photo', saveLastPhoto)}
                testID="save-photo"
              />
              <Button
                disabled={busy || !lastRecording || recording}
                title="Save Video"
                onPress={() => run('Save video', saveLastVideo)}
                testID="save-video"
              />
              <Button
                disabled={busy || recording}
                title="Cleanup"
                onPress={() => run('Cleanup SDK', cleanup)}
                testID="cleanup-sdk"
              />
            </View>
          </>
        )}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: '#080b12',
  },
  preview: {
    flex: 1,
    margin: 16,
    overflow: 'hidden',
    borderRadius: 20,
    backgroundColor: '#111827',
  },
  controls: {
    paddingHorizontal: 20,
    paddingBottom: 20,
    gap: 12,
  },
  controlsScroll: {
    flexGrow: 0,
    maxHeight: '52%',
  },
  title: {
    color: '#ffffff',
    fontSize: 22,
    fontWeight: '700',
  },
  titleRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  modeToggle: {
    color: '#60a5fa',
    fontSize: 16,
    fontWeight: '600',
    minWidth: 72,
    paddingHorizontal: 8,
    paddingVertical: 10,
    textAlign: 'center',
  },
  status: {
    color: '#aeb7c7',
  },
  effectState: {
    color: '#93c5fd',
    fontSize: 12,
  },
  input: {
    height: 46,
    borderWidth: 1,
    borderColor: '#334155',
    borderRadius: 10,
    color: '#ffffff',
    paddingHorizontal: 12,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
});
