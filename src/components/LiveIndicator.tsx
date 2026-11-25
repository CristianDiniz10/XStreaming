import React from 'react';
import {StyleSheet, Text, View} from 'react-native';
import {LiveState} from '../utils/liveStream';

interface Props {
  state: LiveState;
  bitrateKbps?: number;
}

const formatTimer = (seconds: number) => {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const mm = m.toString().padStart(2, '0');
  const ss = s.toString().padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
};

/** Small badge on top of the game: "AO VIVO 12:34 · 8.2 Mbps" or "CONECTANDO…". */
const LiveIndicator: React.FC<Props> = ({state, bitrateKbps}) => {
  const [elapsed, setElapsed] = React.useState(0);

  React.useEffect(() => {
    if (state !== 'live') {
      if (state === 'idle') {
        setElapsed(0);
      }
      return;
    }
    const timer = setInterval(() => setElapsed(prev => prev + 1), 1000);
    return () => clearInterval(timer);
  }, [state]);

  if (state === 'idle') {
    return null;
  }

  const live = state === 'live';
  const rate =
    live && bitrateKbps && bitrateKbps > 0
      ? ` · ${(bitrateKbps / 1000).toFixed(1)} Mbps`
      : '';

  return (
    <View style={[styles.container, !live && styles.connecting]}>
      <View style={[styles.dot, !live && styles.dotConnecting]} />
      <Text style={styles.text}>
        {live ? `AO VIVO ${formatTimer(elapsed)}${rate}` : 'CONECTANDO…'}
      </Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    position: 'absolute',
    top: 14,
    left: 16,
    zIndex: 999,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: 'rgba(0, 0, 0, 0.65)',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 14,
    borderWidth: 1,
    borderColor: 'rgba(255, 0, 0, 0.6)',
  },
  connecting: {borderColor: 'rgba(255, 193, 7, 0.6)'},
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#ff0000',
    marginRight: 6,
  },
  dotConnecting: {backgroundColor: '#ffc107'},
  text: {color: '#ffffff', fontSize: 12, fontWeight: '700', letterSpacing: 0.5},
});

export default LiveIndicator;
