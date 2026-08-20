import { useRef, useState } from 'react';
import {
  Platform,
  Pressable,
  StyleSheet,
  View,
  type GestureResponderEvent,
  type LayoutChangeEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { NosmaiCameraSdk } from './NosmaiCameraSdk';
import NativeNosmaiCameraView, {
  type NativeCameraErrorEvent,
  type NativeCameraReadyEvent,
} from './NosmaiCameraViewNativeComponent';
import { NosmaiSdkError } from './errors';
import type { NosmaiCameraViewProps, NosmaiGameTapDetails } from './types';

const ANDROID_PREVIEW_ASPECT_RATIO = 9 / 16;

interface PreviewSize {
  width: number;
  height: number;
}

export function resolveAndroidPreviewSize(
  containerWidth: number,
  containerHeight: number
): PreviewSize {
  if (containerWidth <= 0 || containerHeight <= 0) {
    return { width: 0, height: 0 };
  }

  let width = containerWidth;
  let height = width / ANDROID_PREVIEW_ASPECT_RATIO;

  if (height > containerHeight) {
    height = containerHeight;
    width = height * ANDROID_PREVIEW_ASPECT_RATIO;
  }

  return { width, height };
}

export function NosmaiCameraView({
  style,
  onLayout,
  onReady,
  onError,
  enableGameTapHandling = true,
  onGameTap,
  ...props
}: NosmaiCameraViewProps) {
  const previewSize = useRef({ width: 0, height: 0 });
  const [androidPreviewFrame, setAndroidPreviewFrame] = useState<
    (PreviewSize & { left: number; top: number }) | undefined
  >();

  const handleReady = onReady
    ? (event: NativeSyntheticEvent<NativeCameraReadyEvent>) => {
        const platform = event.nativeEvent.platform;
        onReady({ platform: platform === 'ios' ? 'ios' : 'android' });
      }
    : undefined;

  const handleError = onError
    ? (event: NativeSyntheticEvent<NativeCameraErrorEvent>) => {
        onError({
          code: event.nativeEvent.code,
          message: event.nativeEvent.message,
        });
      }
    : undefined;

  const handleLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    if (Platform.OS === 'android') {
      const resolvedSize = resolveAndroidPreviewSize(width, height);
      previewSize.current = resolvedSize;
      const nextFrame = {
        ...resolvedSize,
        left: (width - resolvedSize.width) / 2,
        top: (height - resolvedSize.height) / 2,
      };
      setAndroidPreviewFrame((currentFrame) => {
        if (
          currentFrame?.width === nextFrame.width &&
          currentFrame.height === nextFrame.height &&
          currentFrame.left === nextFrame.left &&
          currentFrame.top === nextFrame.top
        ) {
          return currentFrame;
        }
        return nextFrame;
      });
    } else {
      previewSize.current = { width, height };
    }
    onLayout?.(event);
  };

  const reportGameTapError = (error: unknown) => {
    if (!onError) return;

    const sdkError = NosmaiSdkError.fromUnknown(error);
    onError({
      code: sdkError.code,
      message: sdkError.message,
      details: sdkError.details,
    });
  };

  const handleGameTap = async (event: GestureResponderEvent) => {
    const { width, height } = previewSize.current;
    if (width <= 0 || height <= 0) return;

    const locationX = Math.min(Math.max(event.nativeEvent.locationX, 0), width);
    const locationY = Math.min(
      Math.max(event.nativeEvent.locationY, 0),
      height
    );
    const tap: NosmaiGameTapDetails = {
      normalizedX: locationX / width,
      normalizedY: locationY / height,
      locationX,
      locationY,
      previewWidth: width,
      previewHeight: height,
    };

    try {
      if (onGameTap) {
        await onGameTap(tap);
        return;
      }

      if (await NosmaiCameraSdk.isGameReady()) {
        await NosmaiCameraSdk.sendGameTap(tap.normalizedX, tap.normalizedY);
      }
    } catch (error) {
      reportGameTapError(error);
    }
  };

  const previewFrameStyle =
    Platform.OS === 'android' && androidPreviewFrame
      ? [styles.previewFrame, androidPreviewFrame]
      : StyleSheet.absoluteFill;

  return (
    <View
      collapsable={false}
      onLayout={handleLayout}
      style={[styles.container, style]}
    >
      <NativeNosmaiCameraView
        {...props}
        onCameraReady={handleReady}
        onCameraError={handleError}
        style={previewFrameStyle}
      />
      {enableGameTapHandling ? (
        <Pressable
          accessible={false}
          onPress={handleGameTap}
          style={previewFrameStyle}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#000000',
    overflow: 'hidden',
  },
  previewFrame: {
    position: 'absolute',
  },
});
