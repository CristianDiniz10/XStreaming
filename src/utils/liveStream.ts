import {
  NativeEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
} from 'react-native';
import {getLiveSettings} from '../store/liveStore';

const {LiveStreamModule} = NativeModules;

export type LiveState = 'idle' | 'connecting' | 'live';
export type LiveStats = {
  bitrateKbps: number;
  droppedVideoFrames: number;
  queued: number;
};

const emitter =
  Platform.OS === 'android' && LiveStreamModule
    ? new NativeEventEmitter(LiveStreamModule)
    : null;

export const isLiveSupported = () =>
  Platform.OS === 'android' && !!LiveStreamModule;

/** Joins server + key the way YouTube expects: rtmp://.../live2/<key> */
export const buildLiveUrl = (server: string, key: string) => {
  const s = (server || '').trim().replace(/\/+$/, '');
  return `${s}/${(key || '').trim()}`;
};

export type StartResult = 'started' | 'no_key' | 'unsupported';

export const startLive = async (): Promise<StartResult> => {
  if (!isLiveSupported()) {
    return 'unsupported';
  }
  const settings = getLiveSettings();
  if (!settings.streamKey.trim()) {
    return 'no_key';
  }
  let micEnabled = settings.micEnabled;
  if (micEnabled) {
    const granted = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.RECORD_AUDIO,
    ).catch(() => PermissionsAndroid.RESULTS.DENIED);
    micEnabled = granted === PermissionsAndroid.RESULTS.GRANTED;
  }
  await LiveStreamModule.startLive({
    url: buildLiveUrl(settings.server, settings.streamKey),
    micEnabled,
    micVolume: settings.micVolume,
    gameVolume: settings.gameVolume,
    audioBitrate: settings.audioBitrate,
  });
  return 'started';
};

export const stopLive = async () => {
  if (!isLiveSupported()) {
    return;
  }
  await LiveStreamModule.stopLive();
};

export const getLiveState = async (): Promise<LiveState> => {
  if (!isLiveSupported()) {
    return 'idle';
  }
  return LiveStreamModule.getState();
};

export const onLiveState = (
  cb: (e: {state: LiveState; message?: string}) => void,
) => emitter?.addListener('onLiveState', cb);

export const onLiveStats = (cb: (e: LiveStats) => void) =>
  emitter?.addListener('onLiveStats', cb);

export default {
  startLive,
  stopLive,
  getLiveState,
  onLiveState,
  onLiveStats,
  isLiveSupported,
  buildLiveUrl,
};
