import {
  codegenNativeComponent,
  type CodegenTypes,
  type ViewProps,
} from 'react-native';

export type NativeCameraReadyEvent = Readonly<{
  platform: string;
}>;

export type NativeCameraErrorEvent = Readonly<{
  code: string;
  message: string;
}>;

export interface NativeProps extends ViewProps {
  cameraPosition?: string;
  mirror?: boolean;
  onCameraReady?: CodegenTypes.DirectEventHandler<NativeCameraReadyEvent>;
  onCameraError?: CodegenTypes.DirectEventHandler<NativeCameraErrorEvent>;
}

export default codegenNativeComponent<NativeProps>('NosmaiCameraView');
