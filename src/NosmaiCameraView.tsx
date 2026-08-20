import type { ReactElement } from 'react';
import type { NosmaiCameraViewProps } from './types';

export function NosmaiCameraView(
  _props: NosmaiCameraViewProps
): ReactElement | null {
  throw new Error(
    "'@nosmai/react-native-camera-sdk' is only supported on Android and iOS."
  );
}
