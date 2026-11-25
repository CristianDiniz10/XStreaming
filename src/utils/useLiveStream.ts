import React from 'react';
import {Alert, ToastAndroid} from 'react-native';
import i18next from '../i18n';
import liveStream, {LiveState} from './liveStream';

const {t} = i18next;

/**
 * Shared "live on YouTube" logic for the stream screens.
 *
 * @param requestKeyframe optional function that asks the Xbox for a full picture
 *   (keyframe). YouTube needs one at least every 2-4 seconds; the Xbox only sends
 *   them now and then, so while live we ask every 2 seconds.
 * @param onOpenSettings called when the user has no stream key yet.
 */
export const useLiveStream = (
  requestKeyframe?: () => void,
  onOpenSettings?: () => void,
) => {
  const [liveState, setLiveState] = React.useState<LiveState>('idle');
  const [bitrateKbps, setBitrateKbps] = React.useState(0);
  const keyframeRef = React.useRef(requestKeyframe);
  keyframeRef.current = requestKeyframe;

  React.useEffect(() => {
    liveStream
      .getLiveState()
      .then(setLiveState)
      .catch(() => {});
    const s1 = liveStream.onLiveState(e => {
      setLiveState(e.state);
      if (e.state === 'idle') {
        setBitrateKbps(0);
        if (e.message) {
          const msg =
            e.message === 'auth'
              ? t('LiveErrorAuth')
              : `${t('LiveErrorEnded')} (${e.message})`;
          ToastAndroid.show(msg, ToastAndroid.LONG);
        }
      } else if (e.state === 'live') {
        ToastAndroid.show(t('LiveStarted'), ToastAndroid.SHORT);
        keyframeRef.current?.();
      }
    });
    const s2 = liveStream.onLiveStats(e => setBitrateKbps(e.bitrateKbps));
    return () => {
      s1?.remove();
      s2?.remove();
      // Leaving the game screen ends the live.
      liveStream.stopLive().catch(() => {});
    };
  }, []);

  // Ask for a keyframe every 2 s while connected/live.
  React.useEffect(() => {
    if (liveState === 'idle') {
      return;
    }
    keyframeRef.current?.();
    const timer = setInterval(() => keyframeRef.current?.(), 2000);
    return () => clearInterval(timer);
  }, [liveState]);

  const toggleLive = React.useCallback(async () => {
    const current = await liveStream.getLiveState().catch(() => 'idle');
    if (current !== 'idle') {
      await liveStream.stopLive().catch(() => {});
      setLiveState('idle');
      ToastAndroid.show(t('LiveStopped'), ToastAndroid.SHORT);
      return;
    }
    const result = await liveStream.startLive().catch(err => {
      ToastAndroid.show(err?.message || 'Error', ToastAndroid.LONG);
      return 'error';
    });
    if (result === 'no_key') {
      Alert.alert(
        t('LiveNoKeyTitle'),
        t('LiveNoKeyText'),
        onOpenSettings
          ? [
              {text: t('Cancel')},
              {text: t('LiveOpenSettings'), onPress: () => onOpenSettings()},
            ]
          : [{text: 'OK'}],
      );
    } else if (result === 'started') {
      setLiveState('connecting');
    }
  }, [onOpenSettings]);

  return {liveState, bitrateKbps, toggleLive};
};

export default useLiveStream;
