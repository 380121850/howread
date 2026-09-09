import preferences from '@ohos.data.preferences';
import { storeName } from './Profiles';

/**
 * Multi-bookmark manager persisted via @ohos.data.preferences.
 * A bookmark is {path, page, label, date}; up to 200 per document.
 */

export interface Bookmark {
  /** sandbox path of the document */
  path: string;
  /** 0-based page index */
  page: number;
  /** user label ('' = auto "第 N 页") */
  label: string;
  /** ISO timestamp */
  date: string;
}

const PREF_NAME = 'librera_bookmarks';
const KEY_BOOKMARKS = 'bookmarks';
const MAX_BOOKMARKS = 200;

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

export async function getBookmarks(ctx: Context, path: string): Promise<Bookmark[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_BOOKMARKS, '[]') as string;
    const raw: object[] = JSON.parse(json) as object[];
    const list: Bookmark[] = [];
    for (let i = 0; i < raw.length; i++) {
      const b = raw[i] as Bookmark;
      if (b.path === path && typeof b.page === 'number') {
        list.push(b);
      }
    }
    /* newest first */
    list.sort((a: Bookmark, b: Bookmark) => b.date.localeCompare(a.date));
    return list;
  } catch (e) {
    return [];
  }
}

export async function addBookmark(ctx: Context, path: string, page: number, label: string): Promise<boolean> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_BOOKMARKS, '[]') as string;
    const raw: object[] = JSON.parse(json) as object[];
    const list: Bookmark[] = raw as Bookmark[];
    /* avoid exact duplicates (same path+page) */
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === path && list[i].page === page) {
        return false;
      }
    }
    const bm: Bookmark = {
      path: path,
      page: page,
      label: label,
      date: new Date().toISOString()
    };
    list.push(bm);
    while (list.length > MAX_BOOKMARKS) {
      list.shift();
    }
    await prefs.put(KEY_BOOKMARKS, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Bookmarks] Added page ${page + 1} for ${path}`);
    return true;
  } catch (e) {
    console.error('[Bookmarks] addBookmark failed');
    return false;
  }
}

export async function removeBookmark(ctx: Context, path: string, page: number): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_BOOKMARKS, '[]') as string;
    const raw: object[] = JSON.parse(json) as object[];
    const list: Bookmark[] = raw as Bookmark[];
    const kept: Bookmark[] = [];
    for (let i = 0; i < list.length; i++) {
      if (!(list[i].path === path && list[i].page === page)) {
        kept.push(list[i]);
      }
    }
    await prefs.put(KEY_BOOKMARKS, JSON.stringify(kept));
    await prefs.flush();
    console.info(`[Bookmarks] Removed page ${page + 1}`);
  } catch (e) {
    console.error('[Bookmarks] removeBookmark failed');
  }
}

/** All bookmarks across documents, for backup export (sprint E). */
export async function exportBookmarks(ctx: Context): Promise<Bookmark[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_BOOKMARKS, '[]') as string;
    const raw: object[] = JSON.parse(json) as object[];
    const list: Bookmark[] = raw as Bookmark[];
    console.info(`[Bookmarks] Exported ${list.length} bookmarks`);
    return list;
  } catch (e) {
    return [];
  }
}

/** Replace the whole bookmark list (backup restore, sprint E). */
export async function replaceBookmarks(ctx: Context, list: Bookmark[]): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const trimmed: Bookmark[] = [];
    for (let i = 0; i < list.length && i < MAX_BOOKMARKS; i++) {
      trimmed.push(list[i]);
    }
    await prefs.put(KEY_BOOKMARKS, JSON.stringify(trimmed));
    await prefs.flush();
    console.info(`[Bookmarks] Restored ${trimmed.length} bookmarks from backup`);
  } catch (e) {
    console.error('[Bookmarks] replaceBookmarks failed');
  }
}
