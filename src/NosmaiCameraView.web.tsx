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

const WEB_CANVAS_WIDTH = 720;
const WEB_CANVAS_HEIGHT = 1280;

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

  // Keep consumer callbacks fresh without unregistering/re-registering the
  // WebGL canvas every time the parent React component re-renders.
  const onReadyRef = useRef(onReady);
  const onErrorRef = useRef(onError);

  onReadyRef.current = onReady;
  onErrorRef.current = onError;

  // IMPORTANT:
  // Attach this canvas only once for its mount lifecycle.
  // Re-attaching Nosmai's WebGL engine on every parent state update can reset
  // the rendering pipeline and make beauty/cloud effects appear inactive.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    return webRuntime.registerCanvas(canvas, {
      onReady: () => onReadyRef.current?.({ platform: 'web' }),
      onError: (error) => onErrorRef.current?.(error),
    });
  }, []);

  useEffect(() => {
    void NosmaiCameraSdk.configureCamera({
      position: cameraPosition,
    }).catch((error) => {
      const sdkError = NosmaiSdkError.fromUnknown(error);

      onErrorRef.current?.({
        code: sdkError.code,
        message: sdkError.message,
        details: sdkError.details,
      });
    });
  }, [cameraPosition]);
useEffect(() => {
  webRuntime.setMirrorMode(
    mirror === undefined
      ? 'auto'
      : mirror
        ? 'on'
        : 'off'
  );
}, [mirror]);
  const handleLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    previewSize.current = { width, height };

    // Do NOT change canvas.width / canvas.height after Nosmai has attached.
    // Setting either intrinsic canvas dimension resets the WebGL drawing
    // buffer/context state. The raw Web SDK baseline works with a stable
    // 720x1280 drawing buffer, so keep that stable for the full session.
    onLayout?.(event);
  };

  const reportTapError = (error: unknown) => {
    const sdkError = NosmaiSdkError.fromUnknown(error);

    onErrorRef.current?.({
      code: sdkError.code,
      message: sdkError.message,
      details: sdkError.details,
    });
  };

  const handleGameTap = async (event: GestureResponderEvent) => {
    const { width, height } = previewSize.current;

    if (width <= 0 || height <= 0) {
      return;
    }

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
        await NosmaiCameraSdk.sendGameTap(
          tap.normalizedX,
          tap.normalizedY
        );
      }
    } catch (error) {
      reportTapError(error);
    }
  };


  return (
    <View
      {...viewProps}
      collapsable={false}
      onLayout={handleLayout}
      style={[styles.container, style]}
    >
      <canvas
        ref={canvasRef}
        width={WEB_CANVAS_WIDTH}
        height={WEB_CANVAS_HEIGHT}
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: 0,
          width: '100%',
          height: '100%',
          display: 'block',
          objectFit: 'cover',
          pointerEvents: 'none',

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
