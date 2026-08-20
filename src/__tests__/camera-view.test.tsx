jest.mock('../NosmaiCameraViewNativeComponent', () => ({
  __esModule: true,
  default: 'NativeNosmaiCameraView',
}));
jest.mock('../NosmaiCameraSdk', () => ({
  NosmaiCameraSdk: {
    isGameReady: jest.fn(),
    sendGameTap: jest.fn(),
  },
}));
jest.mock('react', () => ({
  ...jest.requireActual('react'),
  useRef: (initialValue: unknown) => ({ current: initialValue }),
  useState: (initialValue: unknown) => [initialValue, jest.fn()],
}));

import type { ReactElement } from 'react';
import type { GestureResponderEvent, LayoutChangeEvent } from 'react-native';
import { NosmaiCameraSdk } from '../NosmaiCameraSdk';
import {
  NosmaiCameraView,
  resolveAndroidPreviewSize,
} from '../NosmaiCameraView.native';
import type { NosmaiCameraViewProps } from '../types';

interface NativeViewTestProps {
  cameraPosition?: 'front' | 'back';
  mirror?: boolean;
  onCameraReady?: (event: { nativeEvent: { platform: string } }) => void;
  onCameraError?: (event: {
    nativeEvent: { code: string; message: string };
  }) => void;
}

interface GameTapOverlayTestProps {
  onPress?: (event: GestureResponderEvent) => void;
}

interface CameraViewContainerTestProps {
  children: readonly [
    ReactElement<NativeViewTestProps>,
    ReactElement<GameTapOverlayTestProps> | null,
  ];
  onLayout?: (event: LayoutChangeEvent) => void;
}

const sdkMock = NosmaiCameraSdk as unknown as jest.Mocked<
  Pick<typeof NosmaiCameraSdk, 'isGameReady' | 'sendGameTap'>
>;

function renderCameraView(props: NosmaiCameraViewProps) {
  const container = NosmaiCameraView(
    props
  ) as ReactElement<CameraViewContainerTestProps>;
  return {
    container,
    nativeView: container.props.children[0],
    tapOverlay: container.props.children[1],
  };
}

describe('NosmaiCameraView native adapter', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    sdkMock.isGameReady.mockResolvedValue(true);
    sdkMock.sendGameTap.mockResolvedValue(true);
  });

  it('forwards view props and normalizes ready/error callbacks', () => {
    const onReady = jest.fn();
    const onError = jest.fn();
    const { nativeView } = renderCameraView({
      cameraPosition: 'back',
      mirror: false,
      onReady,
      onError,
    });

    expect(nativeView.props.cameraPosition).toBe('back');
    expect(nativeView.props.mirror).toBe(false);

    nativeView.props.onCameraReady?.({ nativeEvent: { platform: 'ios' } });
    nativeView.props.onCameraError?.({
      nativeEvent: { code: 'E_CAMERA_DEVICE', message: 'Camera failed.' },
    });

    expect(onReady).toHaveBeenCalledWith({ platform: 'ios' });
    expect(onError).toHaveBeenCalledWith({
      code: 'E_CAMERA_DEVICE',
      message: 'Camera failed.',
    });
  });

  it('maps the Android native platform to the public contract value', () => {
    const onReady = jest.fn();
    const { nativeView } = renderCameraView({
      onReady,
    });

    nativeView.props.onCameraReady?.({
      nativeEvent: { platform: 'android' },
    });

    expect(onReady).toHaveBeenCalledWith({ platform: 'android' });
  });

  it('fits the Android preview to 9:16 without stretching', () => {
    expect(resolveAndroidPreviewSize(1080, 2400)).toEqual({
      width: 1080,
      height: 1920,
    });
    expect(resolveAndroidPreviewSize(1200, 1000)).toEqual({
      width: 562.5,
      height: 1000,
    });
    expect(resolveAndroidPreviewSize(0, 1000)).toEqual({
      width: 0,
      height: 0,
    });
  });

  it('normalizes preview taps and forwards them to a ready game', async () => {
    const onLayout = jest.fn();
    const { container, tapOverlay } = renderCameraView({ onLayout });

    const layoutEvent = {
      nativeEvent: { layout: { height: 400, width: 200, x: 0, y: 0 } },
    } as LayoutChangeEvent;
    container.props.onLayout?.(layoutEvent);
    tapOverlay?.props.onPress?.({
      nativeEvent: { locationX: 50, locationY: 300 },
    } as GestureResponderEvent);
    await Promise.resolve();
    await Promise.resolve();

    expect(onLayout).toHaveBeenCalledWith(layoutEvent);
    expect(sdkMock.isGameReady).toHaveBeenCalledTimes(1);
    expect(sdkMock.sendGameTap).toHaveBeenCalledWith(0.25, 0.75);
  });

  it('lets a custom game tap callback replace automatic forwarding', async () => {
    const onGameTap = jest.fn(async () => undefined);
    const { container, tapOverlay } = renderCameraView({ onGameTap });

    container.props.onLayout?.({
      nativeEvent: { layout: { height: 200, width: 100, x: 0, y: 0 } },
    } as LayoutChangeEvent);
    tapOverlay?.props.onPress?.({
      nativeEvent: { locationX: 25, locationY: 100 },
    } as GestureResponderEvent);
    await Promise.resolve();

    expect(onGameTap).toHaveBeenCalledWith({
      locationX: 25,
      locationY: 100,
      normalizedX: 0.25,
      normalizedY: 0.5,
      previewHeight: 200,
      previewWidth: 100,
    });
    expect(sdkMock.isGameReady).not.toHaveBeenCalled();
    expect(sdkMock.sendGameTap).not.toHaveBeenCalled();
  });

  it('removes the tap overlay when automatic handling is disabled', () => {
    const { tapOverlay } = renderCameraView({
      enableGameTapHandling: false,
    });

    expect(tapOverlay).toBeNull();
  });
});
