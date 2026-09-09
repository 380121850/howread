import preferences from '@ohos.data.preferences';
import { storeName } from './Profiles';

/**
 * Reading progress persistence using @ohos.data.preferences.
 * Stores: recent book list (up to 20), per-book page position + timestamp,
 * star (favorite) flag, cover thumbnail path, and basic metadata.
 */

export interface RecentBook {
  /** sandbox path where the PDF copy lives */
  path: string;
  /** original file name for display */
  title: string;
  /** last read page index (0-based) */
  page: number;
  /** total pages at time of save */
  totalPages: number;
  /** ISO timestamp of last read */
  lastRead: string;
  /** favorite flag */
  star: boolean;
  /** cached cover PNG path in sandbox ('' if not generated yet) */
  coverPath: string;
  /** author from metadata, if any */
  author: string;
  /* Sprint N: tags + series/genre metadata */
  /** user-assigned tags */
  tags: string[];
  /** book series name (e.g. "Harry Potter") */
  series: string;
  /** genre/category (e.g. "Fantasy", "Sci-Fi") */
  genre: string;
  /* Phase 2: reading status 0=unread 1=reading 2=finished */
  status: number;
}

/** Derive a reading status from page progress (0 unread / 1 reading / 2 finished). */
function deriveStatus(page: number, totalPages: number): number {
  if (totalPages <= 0) {
    return 0;
  }
  if (page + 1 >= totalPages) {
    return 2;
  }
  if (page > 0) {
    return 1;
  }
  return 0;
}

const PREF_NAME = 'librera_reader';
const KEY_RECENT = 'recent_books';
const MAX_RECENT = 20;

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

export async function saveRecentBook(ctx: Context, book: RecentBook): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const list = await getRecentBooks(ctx);
    /* Phase 2: merge instead of blind replace — callers like the Reader only
       pass page/totalPages/lastRead, so preserve star/cover/author/tags/series/
       genre and keep status in sync with the progress. */
    let old: RecentBook | undefined = undefined;
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === book.path) {
        old = list[i];
        break;
      }
    }
    let status: number = typeof book.status === 'number' ? book.status : deriveStatus(book.page, book.totalPages);
    if (old !== undefined) {
      if (old.page === book.page) {
        /* same-page metadata update → keep the explicit status */
        status = old.status;
      } else {
        /* progress changed → re-derive */
        status = deriveStatus(book.page, book.totalPages);
      }
    }
    const merged: RecentBook = {
      path: book.path,
      title: book.title,
      page: book.page,
      totalPages: book.totalPages,
      lastRead: book.lastRead,
      star: old !== undefined ? old.star : book.star === true,
      coverPath: (old !== undefined && (book.coverPath === undefined || book.coverPath.length === 0)) ? old.coverPath : book.coverPath,
      author: (old !== undefined && (book.author === undefined || book.author.length === 0)) ? old.author : book.author,
      tags: (old !== undefined && (book.tags === undefined || book.tags.length === 0)) ? old.tags : book.tags,
      series: (old !== undefined && (book.series === undefined || book.series.length === 0)) ? old.series : book.series,
      genre: (old !== undefined && (book.genre === undefined || book.genre.length === 0)) ? old.genre : book.genre,
      status: status
    };
    /* Remove existing entry for same path, keep merged at front */
    const filtered: RecentBook[] = [];
    for (let i = 0; i < list.length; i++) {
      if (list[i].path !== book.path) {
        filtered.push(list[i]);
      }
    }
    filtered.unshift(merged);
    /* Cap at MAX_RECENT */
    const trimmed: RecentBook[] = [];
    for (let i = 0; i < filtered.length && i < MAX_RECENT; i++) {
      trimmed.push(filtered[i]);
    }
    await prefs.put(KEY_RECENT, JSON.stringify(trimmed));
    await prefs.flush();
    console.info(`[Progress] Saved recent: ${book.title} page=${book.page}/${book.totalPages} status=${status}`);
  } catch (e) {
    console.error('[Progress] saveRecentBook failed');
  }
}

/** Set the explicit reading status of one book (0 unread / 1 reading / 2 finished). */
export async function setBookStatus(ctx: Context, path: string, status: number): Promise<void> {
  try {
    const list = await getRecentBooks(ctx);
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === path) {
        list[i].status = status;
        break;
      }
    }
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] setBookStatus ${path} -> ${status}`);
  } catch (e) {
    console.error('[Progress] setBookStatus failed');
  }
}

/** Set the reading status of multiple books at once (batch selection). */
export async function setBooksStatus(ctx: Context, paths: string[], status: number): Promise<void> {
  try {
    const list = await getRecentBooks(ctx);
    for (let i = 0; i < list.length; i++) {
      for (let j = 0; j < paths.length; j++) {
        if (list[i].path === paths[j]) {
          list[i].status = status;
          break;
        }
      }
    }
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] setBooksStatus ${paths.length} books -> ${status}`);
  } catch (e) {
    console.error('[Progress] setBooksStatus failed');
  }
}

export async function getRecentBooks(ctx: Context): Promise<RecentBook[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_RECENT, '[]') as string;
    if (json.length === 0 || json === '[]') {
      return [];
    }
    const raw: object[] = JSON.parse(json) as object[];
    /* drop entries with a corrupted shape; backfill fields added later */
    const list: RecentBook[] = [];
    for (let i = 0; i < raw.length; i++) {
      const b = raw[i] as RecentBook;
      if (typeof b.path === 'string' && typeof b.title === 'string' && typeof b.page === 'number') {
        const clean: RecentBook = {
          path: b.path,
          title: b.title,
          page: typeof b.page === 'number' ? b.page : 0,
          totalPages: typeof b.totalPages === 'number' ? b.totalPages : 0,
          lastRead: typeof b.lastRead === 'string' ? b.lastRead : '',
          star: b.star === true,
          coverPath: typeof b.coverPath === 'string' ? b.coverPath : '',
          author: typeof b.author === 'string' ? b.author : '',
          tags: Array.isArray(b.tags) ? b.tags as string[] : [],
          series: typeof b.series === 'string' ? b.series : '',
          genre: typeof b.genre === 'string' ? b.genre : '',
          status: typeof b.status === 'number' ? b.status : deriveStatus(b.page, b.totalPages)
        };
        list.push(clean);
      }
    }
    return list;
  } catch (e) {
    console.error('[Progress] getRecentBooks failed');
    return [];
  }
}

export async function getRecentBooksStarred(ctx: Context): Promise<RecentBook[]> {
  const all = await getRecentBooks(ctx);
  const starred: RecentBook[] = [];
  for (let i = 0; i < all.length; i++) {
    if (all[i].star) {
      starred.push(all[i]);
    }
  }
  return starred;
}

/** Toggle the favorite flag for a book path; returns the new star value. */
export async function toggleStar(ctx: Context, path: string): Promise<boolean> {
  const list = await getRecentBooks(ctx);
  let newStar = false;
  for (let i = 0; i < list.length; i++) {
    if (list[i].path === path) {
      list[i].star = !list[i].star;
      newStar = list[i].star;
      break;
    }
  }
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] toggleStar ${path} -> ${newStar}`);
  } catch (e) {
    console.error('[Progress] toggleStar failed');
  }
  return newStar;
}

/** Update the cover thumbnail path for a book. */
export async function updateCover(ctx: Context, path: string, coverPath: string): Promise<void> {
  const list = await getRecentBooks(ctx);
  for (let i = 0; i < list.length; i++) {
    if (list[i].path === path) {
      list[i].coverPath = coverPath;
      break;
    }
  }
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_RECENT, JSON.stringify(list));
    await prefs.flush();
    console.info(`[Progress] updateCover ${path} -> ${coverPath}`);
  } catch (e) {
    console.error('[Progress] updateCover failed');
  }
}

export async function getPageForPath(ctx: Context, path: string): Promise<number> {
  try {
    const list = await getRecentBooks(ctx);
    for (let i = 0; i < list.length; i++) {
      if (list[i].path === path) {
        return list[i].page;
      }
    }
  } catch (e) {
    /* ignore */
  }
  return 0;
}

/** Full cleaned list, for backup export (sprint E). */
export async function exportRecentBooks(ctx: Context): Promise<RecentBook[]> {
  return await getRecentBooks(ctx);
}

/** Replace the whole recent list (backup restore, sprint E). */
export async function replaceRecentBooks(ctx: Context, list: RecentBook[]): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const trimmed: RecentBook[] = [];
    for (let i = 0; i < list.length && i < MAX_RECENT; i++) {
      trimmed.push(list[i]);
    }
    await prefs.put(KEY_RECENT, JSON.stringify(trimmed));
    await prefs.flush();
    console.info(`[Progress] Restored ${trimmed.length} recent books from backup`);
  } catch (e) {
    console.error('[Progress] replaceRecentBooks failed');
  }
}
