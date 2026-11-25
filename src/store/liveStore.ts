import {storage} from './mmkv';

// Live (YouTube) settings. Kept apart from the main settings on purpose:
// the main settings are written to the debug log and can be exported with
// "Transfer", and the stream key must never leave the phone.
const STORE_KEY = 'user.live';

export type LiveSettings = {
  server: string; // RTMP server, without the key
  streamKey: string;
  micEnabled: boolean;
  micVolume: number; // 0-200 (%)
  gameVolume: number; // 0-200 (%)
  audioBitrate: number; // kbps
};

export const YOUTUBE_RTMP = 'rtmp://a.rtmp.youtube.com/live2';
export const YOUTUBE_RTMPS = 'rtmps://a.rtmps.youtube.com:443/live2';

export const defaultLiveSettings: LiveSettings = {
  server: YOUTUBE_RTMP,
  streamKey: '',
  micEnabled: false,
  micVolume: 100,
  gameVolume: 100,
  audioBitrate: 128,
};

export const getLiveSettings = (): LiveSettings => {
  const raw = storage.getString(STORE_KEY);
  if (!raw) {
    return {...defaultLiveSettings};
  }
  try {
    return {...defaultLiveSettings, ...JSON.parse(raw)};
  } catch (e) {
    return {...defaultLiveSettings};
  }
};

export const saveLiveSettings = (settings: LiveSettings) => {
  storage.set(STORE_KEY, JSON.stringify(settings));
};
