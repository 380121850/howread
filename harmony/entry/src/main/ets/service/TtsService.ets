import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import notificationManager from '@ohos.notificationManager';
import wantAgent, { WantAgent } from '@ohos.app.ability.wantAgent';
import preferences from '@ohos.data.preferences';
import formProvider from '@ohos.app.form.formProvider';
import formBindingData from '@ohos.app.form.formBindingData';
import { hilog } from '@kit.PerformanceAnalysisKit';
import bundleManager from '@ohos.bundle.bundleManager';

/**
 * TTS background playback support (round 5, Android TTSService parity).
 * - Continuous task (AUDIO_PLAYBACK) keeps the app alive while reading aloud.
 * - An ongoing notification carries prev / pause-resume / next control buttons;
 *   each button routes to EntryAbility with want parameter `tts_cmd`, which the
 *   Reader picks up through AppStorage (key `ttsCmd`) on its poll tick.
 */

const NOTIF_ID = 10001;
const TAG = 'TtsService';
export const KEY_TTS_CMD = 'ttsCmd';

let cachedSelfBundle: string = '';
/** S1/R1 fix (0.9.45): the button WantAgents used to carry the hard-coded
 *  DEFAULT bundle name - under the pro variant (the only shipped bundle)
 *  they routed taps to a foreign/uninstalled package (build_hap_all.sh
 *  swaps app.json5 per variant but never .ets literals). Resolve the
 *  RUNNING bundle's own name once and reuse it (same API as the About
 *  version pill). */
function selfBundleName(): string {
  if (cachedSelfBundle.length > 0) {
    return cachedSelfBundle;
  }
  try {
    cachedSelfBundle = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT).name;
    hilog.info(0x0000, TAG, 'self bundle resolved: %{public}s', cachedSelfBundle);
  } catch (e) {
    hilog.warn(0x0000, TAG, 'self bundle resolve failed: %{public}s', String(e));
  }
  return cachedSelfBundle;
}

/* warm resolve at module load: the name is cached (and visible in hilog)
 * before any TTS notification path needs it */
selfBundleName();

/** Build a WantAgent that reopens EntryAbility with the given tts_cmd parameter. */
function makeButtonAgent(ctx: Context, cmd: string): Promise<WantAgent> {
  const info: wantAgent.WantAgentInfo = {
    wants: [{
      bundleName: selfBundleName(),
      abilityName: 'EntryAbility',
      parameters: { tts_cmd: cmd }
    }],
    operationType: wantAgent.OperationType.START_ABILITY,
    requestCode: 0
  };
  return wantAgent.getWantAgent(info);
}

/** Start the continuous background task for TTS playback. */
export async function startTtsBackgroundTask(ctx: Context): Promise<void> {
  try {
    const agent = await makeButtonAgent(ctx, 'open');
    await backgroundTaskManager.startBackgroundRunning(
      ctx, backgroundTaskManager.BackgroundMode.AUDIO_PLAYBACK, agent);
    hilog.info(0x0000, TAG, 'background task started');
  } catch (e) {
    hilog.warn(0x0000, TAG, 'startBackgroundRunning failed: %{public}s', String(e));
  }
}

/** Stop the continuous background task. */
export async function stopTtsBackgroundTask(ctx: Context): Promise<void> {
  try {
    await backgroundTaskManager.stopBackgroundRunning(ctx);
    hilog.info(0x0000, TAG, 'background task stopped');
  } catch (e) {
    hilog.warn(0x0000, TAG, 'stopBackgroundRunning failed: %{public}s', String(e));
  }
}

/** Show (or refresh) the ongoing notification with TTS status + control buttons. */
export async function showTtsNotification(ctx: Context, title: string, sentence: string, paused: boolean): Promise<void> {
  try {
    const prevAgent = await makeButtonAgent(ctx, 'prev');
    const toggleAgent = await makeButtonAgent(ctx, 'toggle');
    const nextAgent = await makeButtonAgent(ctx, 'next');
    const buttons: notificationManager.NotificationActionButton[] = [
      { title: '\u25C0\u4E0A\u4E00\u53E5', wantAgent: prevAgent },
      { title: paused ? '\u25B6\u7EE7\u7EED' : '\u23F8\u6682\u505C', wantAgent: toggleAgent },
      { title: '\u4E0B\u4E00\u53E5\u25B6', wantAgent: nextAgent }
    ];
    const text = sentence.substring(0, 50) + (sentence.length > 50 ? '\u2026' : '');
    const request: notificationManager.NotificationRequest = {
      id: NOTIF_ID,
      isOngoing: true,
      actionButtons: buttons,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_MULTILINE,
        multiLine: {
          title: (paused ? '\u23F8 ' : '\u25B6 ') + title + ' \u00B7 \u6717\u8BFB',
          text: text,
          longTitle: title,
          lines: [text],
          briefText: text
        }
      }
    };
    await notificationManager.publish(request);
  } catch (e) {
    hilog.warn(0x0000, TAG, 'publish failed: %{public}s', String(e));
  }
}

/** Cancel the TTS notification. */
export async function cancelTtsNotification(): Promise<void> {
  try {
    await notificationManager.cancel(NOTIF_ID);
  } catch (e) {
    /* ignore */
  }
}

const PREF_TTS_STATE = 'librera_ttsstate';
const PREF_FORM_MAP = 'librera_formmap';

/**
 * Round 6: publish the TTS state for the TtsCard desktop card. The state goes
 * to preferences (readable by the FormAbility process on its refresh tick) and
 * a push update is sent to every registered TtsCard form for near-real-time
 * refresh while the Reader is running.
 */
export async function syncTtsCardState(ctx: Context, title: string, sentence: string, playing: boolean): Promise<void> {
  try {
    const trimmed: string = sentence.length > 60 ? sentence.substring(0, 60) + '\u2026' : sentence;
    const state: Record<string, Object> = {
      'title': title,
      'sentence': trimmed,
      'playing': playing,
      'ts': Date.now()
    };
    const prefs = await preferences.getPreferences(ctx, PREF_TTS_STATE);
    await prefs.put('state', JSON.stringify(state));
    await prefs.flush();
    /* push to live TtsCards via the form-id map kept by FormAbility */
    const fmPrefs = await preferences.getPreferences(ctx, PREF_FORM_MAP);
    const raw: string = await fmPrefs.get('map', '{}') as string;
    const map: Record<string, string> = JSON.parse(raw) as Record<string, string>;
    const payload: Record<string, Object> = {
      'ttsTitle': title,
      'ttsSentence': trimmed,
      'playing': playing
    };
    const data = formBindingData.createFormBindingData(payload);
    for (const id of Object.keys(map)) {
      if (map[id] === 'TtsCard') {
        try {
          await formProvider.updateForm(id, data);
        } catch (e) {
          /* form may have been removed; ignore */
        }
      }
    }
  } catch (e) {
    hilog.warn(0x0000, TAG, 'syncTtsCardState failed: %{public}s', String(e));
  }
}
