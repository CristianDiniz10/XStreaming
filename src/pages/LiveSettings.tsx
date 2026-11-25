import React from 'react';
import {Alert, ScrollView, StyleSheet, View} from 'react-native';
import {
  Button,
  Card,
  SegmentedButtons,
  Switch,
  Text,
  TextInput,
} from 'react-native-paper';
import Slider from '@react-native-community/slider';
import {useTranslation} from 'react-i18next';
import {
  getLiveSettings,
  saveLiveSettings,
  LiveSettings as LiveSettingsType,
  YOUTUBE_RTMP,
  YOUTUBE_RTMPS,
} from '../store/liveStore';
import {getSettings} from '../store/settingStore';
import {useGamepadNavigation} from '../utils/useGamepadNavigation';

/**
 * "Live on YouTube" settings: stream key, server, microphone and volumes.
 * Video quality is NOT set here: the live uses the same picture the Xbox sends,
 * so it follows the resolution / bitrate chosen in the normal stream settings.
 */
function LiveSettings({navigation}) {
  const {t} = useTranslation();
  const [s, setS] = React.useState<LiveSettingsType>(getLiveSettings());
  const [showKey, setShowKey] = React.useState(false);

  useGamepadNavigation({
    onBack: () => navigation.goBack(),
  });

  const update = (patch: Partial<LiveSettingsType>) =>
    setS(prev => ({...prev, ...patch}));

  const serverChoice =
    s.server === YOUTUBE_RTMP
      ? 'rtmp'
      : s.server === YOUTUBE_RTMPS
      ? 'rtmps'
      : 'custom';

  const appSettings = getSettings();
  const resolution = appSettings.resolution;

  const handleSave = () => {
    const key = s.streamKey.trim();
    if (key && /\s/.test(key)) {
      Alert.alert(t('LiveKeyInvalid'));
      return;
    }
    saveLiveSettings({...s, streamKey: key, server: s.server.trim()});
    navigation.goBack();
  };

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.content}>
        <Card style={styles.card}>
          <Card.Content>
            <Text variant="titleMedium">{t('LiveHowTitle')}</Text>
            <Text style={styles.help}>{t('LiveHowText')}</Text>
          </Card.Content>
        </Card>

        <Text variant="titleMedium" style={styles.label}>
          {t('LiveStreamKey')}
        </Text>
        <TextInput
          mode="outlined"
          value={s.streamKey}
          onChangeText={v => update({streamKey: v})}
          secureTextEntry={!showKey}
          autoCapitalize="none"
          autoCorrect={false}
          placeholder="xxxx-xxxx-xxxx-xxxx-xxxx"
          right={
            <TextInput.Icon
              icon={showKey ? 'eye-off' : 'eye'}
              onPress={() => setShowKey(v => !v)}
            />
          }
        />
        <Text style={styles.help}>{t('LiveStreamKeyHelp')}</Text>

        <Text variant="titleMedium" style={styles.label}>
          {t('LiveServer')}
        </Text>
        <SegmentedButtons
          value={serverChoice}
          onValueChange={v => {
            if (v === 'rtmp') update({server: YOUTUBE_RTMP});
            else if (v === 'rtmps') update({server: YOUTUBE_RTMPS});
            else update({server: ''});
          }}
          buttons={[
            {value: 'rtmp', label: 'YouTube'},
            {value: 'rtmps', label: t('LiveServerSecure')},
            {value: 'custom', label: t('LiveServerCustom')},
          ]}
        />
        {serverChoice === 'custom' && (
          <TextInput
            mode="outlined"
            style={styles.gap}
            value={s.server}
            onChangeText={v => update({server: v})}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="rtmp://servidor/app"
          />
        )}

        <Text variant="titleMedium" style={styles.label}>
          {t('LiveQuality')}
        </Text>
        <Text style={styles.help}>
          {t('LiveQualityHelp', {resolution: `${resolution}p`})}
        </Text>

        <View style={styles.row}>
          <Text variant="titleMedium">{t('LiveMic')}</Text>
          <Switch
            value={s.micEnabled}
            onValueChange={v => update({micEnabled: v})}
          />
        </View>
        <Text style={styles.help}>{t('LiveMicHelp')}</Text>

        {s.micEnabled && (
          <>
            <Text style={styles.sliderLabel}>
              {t('LiveMicVolume')}: {s.micVolume}%
            </Text>
            <Slider
              minimumValue={0}
              maximumValue={200}
              step={10}
              value={s.micVolume}
              onSlidingComplete={v => update({micVolume: Math.round(v)})}
            />
          </>
        )}

        <Text style={styles.sliderLabel}>
          {t('LiveGameVolume')}: {s.gameVolume}%
        </Text>
        <Slider
          minimumValue={0}
          maximumValue={200}
          step={10}
          value={s.gameVolume}
          onSlidingComplete={v => update({gameVolume: Math.round(v)})}
        />

        <Text variant="titleMedium" style={styles.label}>
          {t('LiveAudioQuality')}
        </Text>
        <SegmentedButtons
          value={String(s.audioBitrate)}
          onValueChange={v => update({audioBitrate: Number(v)})}
          buttons={[
            {value: '96', label: '96 kbps'},
            {value: '128', label: '128 kbps'},
            {value: '192', label: '192 kbps'},
          ]}
        />
      </ScrollView>

      <View style={styles.buttonWrap}>
        <Button mode="contained" onPress={handleSave}>
          {t('Save')}
        </Button>
        <Button
          mode="outlined"
          style={styles.gap}
          onPress={() => navigation.goBack()}>
          {t('Back')}
        </Button>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1},
  content: {padding: 12, paddingBottom: 160},
  card: {marginBottom: 8},
  label: {marginTop: 18, marginBottom: 6},
  help: {opacity: 0.75, marginTop: 6, lineHeight: 19},
  row: {
    marginTop: 18,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  sliderLabel: {marginTop: 14},
  gap: {marginTop: 10},
  buttonWrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 20,
    paddingHorizontal: 10,
  },
});

export default LiveSettings;
