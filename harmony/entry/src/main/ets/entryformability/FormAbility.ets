import FormExtensionAbility from '@ohos.app.form.FormExtensionAbility';
import formBindingData from '@ohos.app.form.formBindingData';
import formInfo from '@ohos.app.form.formInfo';
import formProvider from '@ohos.app.form.formProvider';
import Want from '@ohos.app.ability.Want';
import preferences from '@ohos.data.preferences';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { getRecentBooks, getRecentBooksStarred, RecentBook } from '../model/ReadingProgress';

/**
 * Service cards (round 5 recent-books + round 6 TTS playback control).
 *  - RecentCard (2x2): top recent/starred books; row tap reopens the book via
 *    postCardAction → EntryAbility with parameter `cardOpen`.
 *  - TtsCard (2x4): current TTS title/sentence + prev/toggle/next buttons;
 *    button tap routes to EntryAbility with parameter `tts_cmd`.
 *
 * The FormAbility process is separate from the UI process, so form ids are
 * persisted in preferences (`librera_formmap`); the Reader pushes fresh TTS
 * state to `librera_ttsstate` and calls updateForm on the mapped TtsCards.
 */

const TAG = 'FormAbility';
const PREF_MAP = 'librera_formmap';
const PREF_TTS = 'librera_ttsstate';
const PREF_SETTINGS = 'librera_settings';

interface CardRow {
  title: string;
  progress: number;
  cover: string;
  path: string;
}

function toRows(books: RecentBook[], count: number): CardRow[] {
  const rows: CardRow[] = [];
  for (let i = 0; i < books.length && i < count; i++) {
    const b = books[i];
    rows.push({
      title: b.title.length > 14 ? b.title.substring(0, 14) + '…' : b.title,
      progress: b.totalPages > 0 ? Math.round((b.page + 1) * 100 / b.totalPages) : 0,
      cover: b.coverPath.length > 0 ? 'file://' + b.coverPath : '',
      path: b.path
    });
  }
  return rows;
}

/** Read a JSON preferences value; returns {} / fallback on any failure. */
async function readJsonPref(ctx: Context, prefName: string, key: string): Promise<Record<string, Object>> {
  try {
    const prefs = await preferences.getPreferences(ctx, prefName);
    const raw: string = await prefs.get(key, '') as string;
    if (raw.length === 0) {
      return {};
    }
    return JSON.parse(raw) as Record<string, Object>;
  } catch (e) {
    return {};
  }
}

async function loadFormMap(ctx: Context): Promise<Record<string, string>> {
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_MAP);
    const raw: string = await prefs.get('map', '{}') as string;
    return JSON.parse(raw) as Record<string, string>;
  } catch (e) {
    return {};
  }
}

async function saveFormMapEntry(ctx: Context, formId: string, name: string): Promise<void> {
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_MAP);
    const map = await loadFormMap(ctx);
    map[formId] = name;
    await prefs.put('map', JSON.stringify(map));
    await prefs.flush();
  } catch (e) {
    hilog.warn(0x0000, TAG, 'saveFormMapEntry failed');
  }
}

export default class FormAbility extends FormExtensionAbility {
  /** RecentCard payload honoring the cardSource/cardCount preferences. */
  private async buildRecentPayload(): Promise<formBindingData.FormBindingData> {
    let rows: CardRow[] = [];
    try {
      const settings = await readJsonPref(this.context, PREF_SETTINGS, 'settings');
      const source: number = typeof settings['cardSource'] === 'number' ? settings['cardSource'] as number : 0;
      const count: number = typeof settings['cardCount'] === 'number' ? settings['cardCount'] as number : 3;
      const books: RecentBook[] = source === 1
        ? await getRecentBooksStarred(this.context)
        : await getRecentBooks(this.context);
      rows = toRows(books, count > 0 ? count : 3);
    } catch (e) {
      hilog.warn(0x0000, TAG, 'load recent books failed: %{public}s', String(e));
    }
    const obj: Record<string, Object> = {
      'rows': rows as Object,
      'empty': rows.length === 0
    };
    return formBindingData.createFormBindingData(obj);
  }

  /** TtsCard payload from the TTS state the Reader publishes to preferences. */
  private async buildTtsPayload(): Promise<formBindingData.FormBindingData> {
    let title = '';
    let sentence = '';
    let playing = false;
    try {
      const state = await readJsonPref(this.context, PREF_TTS, 'state');
      if (typeof state['title'] === 'string') {
        title = state['title'] as string;
      }
      if (typeof state['sentence'] === 'string') {
        sentence = state['sentence'] as string;
      }
      if (typeof state['playing'] === 'boolean') {
        playing = state['playing'] as boolean;
      }
    } catch (e) {
      hilog.warn(0x0000, TAG, 'load tts state failed');
    }
    const obj: Record<string, Object> = {
      'ttsTitle': title,
      'ttsSentence': sentence,
      'playing': playing
    };
    return formBindingData.createFormBindingData(obj);
  }

  /** Build the right payload for a form based on its registered name. */
  private async buildPayloadFor(name: string): Promise<formBindingData.FormBindingData> {
    if (name === 'TtsCard') {
      return this.buildTtsPayload();
    }
    return this.buildRecentPayload();
  }

  onAddForm(want: Want): formBindingData.FormBindingData {
    hilog.info(0x0000, TAG, 'onAddForm');
    const params = want.parameters;
    const formId: string = params !== undefined ? (params[formInfo.FormParam.IDENTITY_KEY] as string) : '';
    const formName: string = params !== undefined && params[formInfo.FormParam.NAME_KEY] !== undefined
      ? (params[formInfo.FormParam.NAME_KEY] as string) : 'RecentCard';
    if (formId.length > 0) {
      saveFormMapEntry(this.context, formId, formName);
      /* fire-and-forget real-data refresh right after the add */
      this.onUpdateForm(formId);
    }
    /* synchronous empty payload first; async onUpdateForm refreshes with real data */
    const obj: Record<string, Object> = formName === 'TtsCard'
      ? { 'ttsTitle': '', 'ttsSentence': '', 'playing': false }
      : { 'rows': [] as Object, 'empty': true };
    return formBindingData.createFormBindingData(obj);
  }

  async onUpdateForm(formId: string): Promise<void> {
    hilog.info(0x0000, TAG, 'onUpdateForm %{public}s', formId);
    try {
      const map = await loadFormMap(this.context);
      const name: string = map[formId] !== undefined ? map[formId] : 'RecentCard';
      const payload = await this.buildPayloadFor(name);
      await formProvider.updateForm(formId, payload);
    } catch (e) {
      hilog.warn(0x0000, TAG, 'updateForm failed: %{public}s', String(e));
    }
  }

  onCastToNormalForm(formId: string): void {
    hilog.info(0x0000, TAG, 'onCastToNormalForm %{public}s', formId);
  }

  onRemoveForm(formId: string): void {
    hilog.info(0x0000, TAG, 'onRemoveForm %{public}s', formId);
    try {
      const prefs = preferences.getPreferencesSync(this.context, { name: PREF_MAP });
      const raw: string = prefs.getSync('map', '{}') as string;
      const map: Record<string, string> = JSON.parse(raw);
      if (map[formId] !== undefined) {
        /* rebuild the map without the removed form (arkts-no-delete) */
        const next: Record<string, string> = {};
        const keys: string[] = Object.keys(map);
        for (let i = 0; i < keys.length; i++) {
          if (keys[i] !== formId) {
            next[keys[i]] = map[keys[i]];
          }
        }
        prefs.putSync('map', JSON.stringify(next));
        prefs.flushSync();
      }
    } catch (e) {
      hilog.warn(0x0000, TAG, 'remove form mapping failed');
    }
  }
}
