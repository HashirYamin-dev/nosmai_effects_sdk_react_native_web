import { useEffect, useRef } from 'react';
import {
  Pressable,
  StyleSheet,
  View,
  type GestureResponderEvent,
  type LayoutChangeEvent,
} from 'react-native';
import { NosmaiCameraSdk } from './NosmaiCameraSdk.web';
import { NosmaiSdkError } from './errors';
import type { NosmaiCameraViewProps, NosmaiGameTapDetails } from './types';
import { webRuntime } from './web/NosmaiWebRuntime';

export function NosmaiCameraView({
  style,
  onLayout,
  onReady,
  onError,
  cameraPosition = 'front',
  mirror,
  enableGameTapHandling = true,
  onGameTap,
  ...viewProps
}: NosmaiCameraViewProps) {
  const canvasRef = useRef<any>(null);
  const previewSize = useRef({ width: 0, height: 0 });

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    return webRuntime.registerCanvas(canvas, {
      onReady: () => onReady?.({ platform: 'web' }),
      onError,
    });
  }, [onError, onReady]);

  useEffect(() => {
    void NosmaiCameraSdk.configureCamera({ position: cameraPosition }).catch(
      (error) => {
        const sdkError = NosmaiSdkError.fromUnknown(error);
        onError?.({
          code: sdkError.code,
          message: sdkError.message,
          details: sdkError.details,
        });
      }
    );
  }, [cameraPosition, onError]);

  const handleLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    previewSize.current = { width, height };

    const canvas = canvasRef.current;
    if (canvas && width > 0 && height > 0) {
      const browserWindow = (globalThis as any).window;
      const ratio = browserWindow
        ? Math.min(Number(browserWindow.devicePixelRatio) || 1, 2)
        : 1;

      const pixelWidth = Math.max(1, Math.round(width * ratio));
      const pixelHeight = Math.max(1, Math.round(height * ratio));

      if (canvas.width !== pixelWidth) canvas.width = pixelWidth;
      if (canvas.height !== pixelHeight) canvas.height = pixelHeight;
    }

    onLayout?.(event);
  };

  const reportTapError = (error: unknown) => {
    const sdkError = NosmaiSdkError.fromUnknown(error);
    onError?.({
      code: sdkError.code,
      message: sdkError.message,
      details: sdkError.details,
    });
  };

  const handleGameTap = async (event: GestureResponderEvent) => {
    const { width, height } = previewSize.current;
    if (width <= 0 || height <= 0) return;

    const locationX = Math.min(
      Math.max(event.nativeEvent.locationX, 0),
      width
    );
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
      reportTapError(error);
    }
  };

  const shouldMirror = mirror ?? cameraPosition === 'front';

  return (
    <View
      {...viewProps}
      collapsable={false}
      onLayout={handleLayout}
      style={[styles.container, style]}
    >
      <canvas
        ref={canvasRef}
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          display: 'block',
          objectFit: 'cover',
          pointerEvents: 'none',
          transform: shouldMirror ? 'scaleX(-1)' : undefined,
        }}
      />
      {enableGameTapHandling ? (
        <Pressable
          accessible={false}
          onPress={handleGameTap}
          style={StyleSheet.absoluteFill}
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
});
