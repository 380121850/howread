import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';
import { tlog } from '../model/TestLog';
import hiAppEvent from '@ohos.hiviewdfx.hiAppEvent';
import { writeCrashLog } from '../model/CrashLog';

const TAG: string = 'Librera';
export const KEY_PENDING_URI: string = 'pendingOpenUri';

export function stashWantUri(want: Want): void {
  const uri: string | undefined = want.uri;
  if (uri !== undefined && uri !== null && uri.length > 0) {
    AppStorage.setOrCreate(KEY_PENDING_URI, uri);
    hilog.info(0x0000, TAG, 'File association open: %{public}s', uri);
  }
  /* Round 5: TTS notification control buttons carry a `tts_cmd` parameter;
   * the Reader consumes it on its poll tick via AppStorage (key `ttsCmd`). */
  const params = want.parameters as Record<string, Object> | undefined;
  const cmd: string | undefined = params !== undefined && params !== null
    ? params['tts_cmd'] as string : undefined;
  if (cmd !== undefined && cmd.length > 0) {
    AppStorage.setOrCreate('ttsCmd', cmd + '#' + Date.now().toString());
    hilog.info(0x0000, TAG, 'TTS cmd from notification: %{public}s', cmd);
  }
  /* Round 5: service card tap → open the book (consumed by Index.onPageShow) */
  const cardOpen: string | undefined = params !== undefined && params !== null
    ? params['cardOpen'] as string : undefined;
  if (cardOpen !== undefined && cardOpen.length > 0) {
    AppStorage.setOrCreate('cardOpenPath', cardOpen);
    hilog.info(0x0000, TAG, 'Card open request: %{public}s', cardOpen);
  }
  /* Round 5: share-receive of plain text (Android SendReceive parity) */
  const text: string | undefined = params !== undefined && params !== null
    ? params['text'] as string : undefined;
  if (text !== undefined && text !== null && text.length > 0) {
    AppStorage.setOrCreate('sharedText', text);
    hilog.info(0x0000, TAG, 'Shared text received (%{public}d chars)', text.length);
  }
  /* ci/autotest-hmos seed hook: aa start --ps hrSeedUrl <url>; Index.consumeSeedUrl downloads it */
  const seedUrl: string | undefined = params !== undefined && params !== null
    ? params['hrSeedUrl'] as string : undefined;
  if (seedUrl !== undefined && seedUrl.length > 0) {
    AppStorage.setOrCreate('hrSeedUrl', seedUrl);
    hilog.info(0x0000, TAG, 'CI seed url: %{public}s', seedUrl);
  }
}

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onCreate');
    tlog('ability_onCreate');
    this.registerCrashHooks();
    stashWantUri(want);
  }

  /* 0.9.20: local crash trace file (Android crash.txt parity). Native and JS
   * hard crashes are captured by the system faultlogger and flushed to
   * crash.txt on the next launch, surviving system log rotation. */
  private registerCrashHooks(): void {
    try {
      hiAppEvent.addWatcher({
        name: 'howread_crash_watcher',
        appEventFilters: [{ domain: hiAppEvent.domain.OS, names: [hiAppEvent.event.APP_CRASH] }],
        onReceive: (domain: string, groups: hiAppEvent.AppEventGroup[]): void => {
          for (let g: number = 0; g < groups.length; g++) {
            const infos: hiAppEvent.AppEventInfo[] = groups[g].appEventInfos;
            for (let i: number = 0; i < infos.length; i++) {
              const ev: hiAppEvent.AppEventInfo = infos[i];
              let detail: string = 'domain=' + domain + ' name=' + ev.name + '\n';
              try {
                detail += JSON.stringify(ev.params);
              } catch (e) { /* keep the basic fields */ }
              writeCrashLog(this.context.filesDir, 'CRASH', detail);
            }
          }
        }
      });
      hilog.info(0x0000, TAG, 'crash watcher registered');
    } catch (e) {
      hilog.warn(0x0000, TAG, 'hiAppEvent watcher failed: %{public}s', String(e));
    }
  }

  onNewWant(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onNewWant');
    stashWantUri(want);
  }

  onDestroy(): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onWindowStageCreate');
    /* Android parity: brand-blue status bar merged with the toolbar */
    windowStage.getMainWindow((err, win) => {
      if (err.code) {
        return;
      }
      win.setWindowSystemBarProperties({ statusBarColor: '#3949AB', statusBarContentColor: '#FFFFFF' });
    });
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'Failed to load content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(0x0000, TAG, 'Succeeded in loading content.');
      tlog('content_loaded');
    });
  }

  onWindowStageDestroy(): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onWindowStageDestroy');
  }

  onForeground(): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onForeground');
  }

  onBackground(): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onBackground');
  }
}
