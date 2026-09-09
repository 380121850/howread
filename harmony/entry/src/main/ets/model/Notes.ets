import preferences from '@ohos.data.preferences';
import { storeName } from './Profiles';

/**
 * AI notes store (Android parity 2026-09: AI 回答 / AI 简介 / AI 翻译的保存查看).
 * Notes are persisted via @ohos.data.preferences and displayed in the Reader
 * bookmark panel ("AI 笔记" section) — independent of PDF annotation notes.
 */

export interface AiNote {
  /** sandbox path of the book ('' for app-level notes) */
  path: string;
  /** book title for grouped display */
  title: string;
  /** page the note refers to (0 = whole book) */
  page: number;
  /** ISO timestamp (also the unique key) */
  date: string;
  /** kind: 'AI\u95EE\u7B54' | 'AI\u7B80\u4ECB' | 'AI\u7FFB\u8BD1' */
  kind: string;
  /** note body */
  text: string;
}

const PREF_NAME = 'librera_notes';
const KEY_NOTES = 'notes';
const MAX_NOTES = 500;

let prefInstance: preferences.Preferences | undefined = undefined;
let prefInstanceStore: string = '';

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  /* Round 6: profile-aware store name; re-open when the profile switches */
  const store: string = storeName(PREF_NAME);
  if (prefInstance === undefined || prefInstanceStore !== store) {
    prefInstanceStore = store;
    prefInstance = await preferences.getPreferences(ctx, store);
  }
  return prefInstance;
}

/** All notes, newest first; pass a path to filter to one book ('' = all). */
export async function getNotes(ctx: Context, path: string): Promise<AiNote[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_NOTES, '[]') as string;
    const raw: object[] = JSON.parse(json) as object[];
    const list: AiNote[] = [];
    for (let i = 0; i < raw.length; i++) {
      const n = raw[i] as AiNote;
      if (typeof n.text !== 'string' || n.text.length === 0) {
        continue;
      }
      if (path.length === 0 || n.path === path) {
        list.push(n);
      }
    }
    list.sort((a: AiNote, b: AiNote) => b.date.localeCompare(a.date));
    return list;
  } catch (e) {
    return [];
  }
}

export async function addNote(ctx: Context, note: AiNote): Promise<boolean> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_NOTES, '[]') as string;
    const list: AiNote[] = JSON.parse(json) as AiNote[];
    list.push(note);
    while (list.length > MAX_NOTES) {
      list.shift();
    }
    await prefs.put(KEY_NOTES, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Notes] added kind=${note.kind} path=${note.path}`);
    return true;
  } catch (e) {
    console.error('[Notes] addNote failed');
    return false;
  }
}

/** Remove by unique timestamp key. */
export async function removeNote(ctx: Context, date: string): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_NOTES, '[]') as string;
    const list: AiNote[] = JSON.parse(json) as AiNote[];
    const kept: AiNote[] = [];
    for (let i = 0; i < list.length; i++) {
      if (list[i].date !== date) {
        kept.push(list[i]);
      }
    }
    await prefs.put(KEY_NOTES, JSON.stringify(kept));
    await prefs.flush();
    console.info('[Notes] removed ' + date);
  } catch (e) {
    console.error('[Notes] removeNote failed');
  }
}
