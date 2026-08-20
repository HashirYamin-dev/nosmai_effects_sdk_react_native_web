import { createRunOncePlugin, type ConfigPlugin } from 'expo/config-plugins';

import packageJson from '../../package.json';
import type { NosmaiExpoPluginProps } from './types';
import { withNosmaiAndroid } from './withAndroid';
import { withNosmaiIos } from './withIos';

const withNosmaiCameraSdk: ConfigPlugin<NosmaiExpoPluginProps | undefined> = (
  config,
  props = {}
) => {
  config = withNosmaiAndroid(config, props);
  config = withNosmaiIos(config, props);
  return config;
};

export { withNosmaiCameraSdk };
export type { NosmaiExpoPluginProps } from './types';

export default createRunOncePlugin(
  withNosmaiCameraSdk,
  packageJson.name,
  packageJson.version
);
