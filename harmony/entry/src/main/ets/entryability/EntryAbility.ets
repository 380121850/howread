import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { window } from '@kit.ArkUI';

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
}

export default class EntryAbility extends UIAbility {
  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(0x0000, TAG, '%{public}s', 'Ability onCreate');
    stashWantUri(want);
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
    windowStage.loadContent('pages/Index', (err) => {
      if (err.code) {
        hilog.error(0x0000, TAG, 'Failed to load content. Cause: %{public}s', JSON.stringify(err));
        return;
      }
      hilog.info(0x0000, TAG, 'Succeeded in loading content.');
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
