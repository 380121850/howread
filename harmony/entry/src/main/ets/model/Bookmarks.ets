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
  /** total pages when the bookmark was made (-1 = legacy/unknown).
   * 0.9.23 (Android 10-09 parity): layout-era epoch - the absolute page is
   * a valid anchor only while the book keeps this page count. */
  pgs: number;
  /** 0.9.43: AI-note bookmarks round-trip android's flags untouched
   *  (AppBookmark isAiNote/aiAnswer parity; absent = normal bookmark). */
  isAiNote?: boolean;
  aiAnswer?: string;
}

const PREF_NAME = 'librera_bookmarks';
const KEY_BOOKMARKS = 'bookmarks';
const MAX_BOOKMARKS = 200;
/* ---- 0.9.39: bookmark deletion tombstones (Android WebDavSyncer dk parity) ----
 * A user deletion records bookName → creationKey(ms) → deleteTime(ms) so the
 * WebDAV interop sync can propagate it inside the per-book info "dk" map and
 * suppress the deleted entry from being union-merged back. Deletion records
 * older than 30 days expire (Android DK_MAX_AGE_MS); a record whose deletion
 * time is not newer than the creation key itself means the bookmark was
 * re-created and must survive. */
const KEY_DK = 'bookmarks_dk';

function baseNameOf(p: string): string {
  const s = p.replace(/\\/g, '/');
  const i = s.lastIndexOf('/');
  return i >= 0 ? s.substring(i + 1) : s;
}

export async function recordBookmarkTombstone(ctx: Context, bookName: string, keyMs: string): Promise<void> {
  try {
    if (bookName.length === 0 || keyMs.length === 0) {
      return;
    }
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_DK, '{}') as string;
    let dk: Record<string, Record<string, number>> = {};
    try {
      dk = JSON.parse(json) as Record<string, Record<string, number>>;
    } catch (e) {
      const fresh: Record<string, Record<string, number>> = {};
      dk = fresh;
    }
    let m = dk[bookName];
    if (m === undefined) {
      const fresh: Record<string, number> = {};
      dk[bookName] = fresh;
      m = fresh;
    }
    const prev = typeof m[keyMs] === 'number' ? m[keyMs] as number : 0;
    if (Date.now() > prev) {
      m[keyMs] = Date.now();
    }
    await prefs.put(KEY_DK, JSON.stringify(dk));
    await prefs.flush();
  } catch (e) {
    console.error('[Bookmarks] tombstone record failed');
  }
}

export async function loadBookmarkTombstones(ctx: Context): Promise<Record<string, Record<string, number>>> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_DK, '{}') as string;
    return JSON.parse(json) as Record<string, Record<string, number>>;
  } catch (e) {
    const empty: Record<string, Record<string, number>> = {};
    return empty;
  }
}

/** Drop expired records (Android DK_MAX_AGE_MS) and records superseded by a
 * re-creation (deletion time not newer than the creation key itself). */
export async function pruneBookmarkTombstones(ctx: Context, maxAgeMs: number): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_DK, '{}') as string;
    const dk = JSON.parse(json) as Record<string, Record<string, number>>;
    const names = Object.keys(dk);
    const out: Record<string, Record<string, number>> = {};
    let changed = false;
    for (let i = 0; i < names.length; i++) {
      const m = dk[names[i]];
      const keys = Object.keys(m);
      const kept: Record<string, number> = {};
      for (let j = 0; j < keys.length; j++) {
        const v = m[keys[j]];
        const created = Number(keys[j]);
        if (Date.now() - v > maxAgeMs) {
          changed = true;
          continue;
        }
        if (!Number.isNaN(created) && v <= created) {
          changed = true;
          continue;
        }
        kept[keys[j]] = v;
      }
      if (Object.keys(kept).length > 0) {
        out[names[i]] = kept;
      }
    }
    if (changed || Object.keys(out).length !== names.length) {
      await prefs.put(KEY_DK, JSON.stringify(out));
      await prefs.flush();
    }
  } catch (e) {
    console.error('[Bookmarks] tombstone prune failed');
  }
}


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
        const clean: Bookmark = {
          path: b.path,
          page: b.page,
          label: typeof b.label === 'string' ? b.label : '',
          date: typeof b.date === 'string' ? b.date : '',
          /* 0.9.23: legacy entries have no epoch - -1 keeps the absolute anchor */
          pgs: typeof b.pgs === 'number' ? b.pgs : -1
        };
        list.push(clean);
      }
    }
    /* newest first */
    list.sort((a: Bookmark, b: Bookmark) => b.date.localeCompare(a.date));
    return list;
  } catch (e) {
    return [];
  }
}

export async function addBookmark(ctx: Context, path: string, page: number, label: string, pgs: number = -1): Promise<boolean> {
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
      date: new Date().toISOString(),
      pgs: pgs
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
    /* 0.9.39: record deletion tombstones (dk) for the WebDAV interop sync —
     * only the entries removed by THIS call get a record. */
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === path && list[i].page === page) {
        const ms = Date.parse(list[i].date);
        if (!Number.isNaN(ms)) {
          await recordBookmarkTombstone(ctx, baseNameOf(path), String(ms));
        }
        break;
      }
    }
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
