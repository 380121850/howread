import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';
import util from '@ohos.util';
import { RecentBook, exportRecentBooks, saveRecentBook } from './ReadingProgress';
import { exportBookmarks, replaceBookmarks, Bookmark } from './Bookmarks';

/**
 * Basic WebDAV sync (Android hr09/hr09b parity, basic edition):
 * per-book reading progress + bookmarks are mirrored to
 * <remotePath>/<safe-name>.howread.json; "newer wins" by updatedAt.
 * No PROPFIND/tombstones/3-way merge — the HarmonyOS HTTP stack
 * lacks WebDAV verbs, so only GET/PUT are used.
 */

export interface SyncConfig {
  enabled: boolean;
  url: string;
  user: string;
  password: string;
  trusted: boolean;
  /** remote directory, e.g. /dav/Books */
  remotePath: string;
  /** fixed: 'newer' (较新优先) */
  conflict: string;
  lastSyncTime: string;
  lastUp: number;
  lastLinked: number;
  lastMs: number;
}

export interface SyncLogEntry {
  /** ISO timestamp */
  time: string;
  /** uploaded book count */
  up: number;
  /** linked books examined */
  linked: number;
  /** total duration ms */
  ms: number;
  /** per-book detail lines */
  details: string[];
}

export function defaultSyncConfig(): SyncConfig {
  const c: SyncConfig = {
    enabled: false,
    url: '',
    user: '',
    password: '',
    trusted: false,
    remotePath: '/dav/Books',
    conflict: '\u8F83\u65B0\u4F18\u5148\uFF08\u6309\u4FEE\u6539\u65F6\u95F4\uFF09',
    lastSyncTime: '',
    lastUp: 0,
    lastLinked: 0,
    lastMs: 0
  };
  return c;
}

const PREF_SYNC = 'librera_sync';
const KEY_CONFIG = 'config';
const PREF_LOG = 'librera_synclog';
const KEY_LOG = 'log';
const MAX_LOG = 100;

let syncPref: preferences.Preferences | undefined = undefined;
let logPref: preferences.Preferences | undefined = undefined;

async function getConfigPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (syncPref === undefined) {
    syncPref = await preferences.getPreferences(ctx, PREF_SYNC);
  }
  return syncPref;
}

async function getLogPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (logPref === undefined) {
    logPref = await preferences.getPreferences(ctx, PREF_LOG);
  }
  return logPref;
}

export async function loadSyncConfig(ctx: Context): Promise<SyncConfig> {
  const d = defaultSyncConfig();
  try {
    const prefs = await getConfigPrefs(ctx);
    const json: string = await prefs.get(KEY_CONFIG, '') as string;
    if (json.length === 0) {
      return d;
    }
    const r = JSON.parse(json) as SyncConfig;
    const c: SyncConfig = {
      enabled: r.enabled === true,
      url: typeof r.url === 'string' ? r.url : '',
      user: typeof r.user === 'string' ? r.user : '',
      password: typeof r.password === 'string' ? r.password : '',
      trusted: r.trusted === true,
      remotePath: typeof r.remotePath === 'string' && r.remotePath.length > 0 ? r.remotePath : d.remotePath,
      conflict: typeof r.conflict === 'string' && r.conflict.length > 0 ? r.conflict : d.conflict,
      lastSyncTime: typeof r.lastSyncTime === 'string' ? r.lastSyncTime : '',
      lastUp: typeof r.lastUp === 'number' ? r.lastUp : 0,
      lastLinked: typeof r.lastLinked === 'number' ? r.lastLinked : 0,
      lastMs: typeof r.lastMs === 'number' ? r.lastMs : 0
    };
    return c;
  } catch (e) {
    return d;
  }
}

export async function saveSyncConfig(ctx: Context, c: SyncConfig): Promise<void> {
  try {
    const prefs = await getConfigPrefs(ctx);
    await prefs.put(KEY_CONFIG, JSON.stringify(c));
    await prefs.flush();
    console.info('[Sync] config saved: url=' + c.url);
  } catch (e) {
    console.error('[Sync] config save failed');
  }
}

export async function getSyncLog(ctx: Context): Promise<SyncLogEntry[]> {
  try {
    const prefs = await getLogPrefs(ctx);
    const json: string = await prefs.get(KEY_LOG, '[]') as string;
    const list: SyncLogEntry[] = JSON.parse(json) as SyncLogEntry[];
    list.sort((a: SyncLogEntry, b: SyncLogEntry) => b.time.localeCompare(a.time));
    return list;
  } catch (e) {
    return [];
  }
}

async function appendSyncLog(ctx: Context, entry: SyncLogEntry): Promise<void> {
  try {
    const prefs = await getLogPrefs(ctx);
    const json: string = await prefs.get(KEY_LOG, '[]') as string;
    const list: SyncLogEntry[] = JSON.parse(json) as SyncLogEntry[];
    list.push(entry);
    while (list.length > MAX_LOG) {
      list.shift();
    }
    await prefs.put(KEY_LOG, JSON.stringify(list));
    await prefs.flush();
  } catch (e) {
    console.error('[Sync] log append failed');
  }
}

function basicAuth(user: string, password: string): string {
  if (user.length === 0) {
    return '';
  }
  const raw = user + ':' + password;
  const arr: number[] = [];
  for (let i = 0; i < raw.length; i++) {
    arr.push(raw.charCodeAt(i) & 0xff);
  }
  return 'Basic ' + new util.Base64Helper().encodeToStringSync(new Uint8Array(arr));
}

function davHeaders(c: SyncConfig, withType: boolean): Record<string, string> {
  const h: Record<string, string> = {};
  if (withType) {
    h['Content-Type'] = 'application/json; charset=utf-8';
  }
  const auth = basicAuth(c.user, c.password);
  if (auth.length > 0) {
    h['Authorization'] = auth;
  }
  return h;
}

function joinUrl(c: SyncConfig, fileName: string): string {
  let base = c.url.replace(/\/+$/, '');
  const dir = c.remotePath.replace(/^\/+|\/+$/g, '');
  if (dir.length > 0) {
    base = base + '/' + dir;
  }
  return base + '/' + fileName;
}

/** Filesystem-safe remote name for a book. */
function safeName(book: RecentBook): string {
  let name = book.title.length > 0 ? book.title : book.path.replace(/\\/g, '/').split('/').pop() as string;
  name = name.replace(/[^0-9A-Za-z\u4e00-\u9fa5._-]+/g, '_');
  return name + '.howread.json';
}

interface SyncPayload {
  title: string;
  page: number;
  totalPages: number;
  updatedAt: string;
  bookmarks: Bookmark[];
}

async function davGet(c: SyncConfig, fileName: string): Promise<string | undefined> {
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(c, fileName), {
      method: http.RequestMethod.GET,
      header: davHeaders(c, false),
      connectTimeout: 10000,
      readTimeout: 20000
    });
    if (resp.responseCode === 200) {
      return resp.result as string;
    }
    return undefined;
  } catch (e) {
    return undefined;
  } finally {
    client.destroy();
  }
}

async function davPut(c: SyncConfig, fileName: string, body: string): Promise<boolean> {
  const client = http.createHttp();
  try {
    const resp = await client.request(joinUrl(c, fileName), {
      method: http.RequestMethod.PUT,
      header: davHeaders(c, true),
      extraData: body,
      connectTimeout: 10000,
      readTimeout: 30000
    });
    return resp.responseCode >= 200 && resp.responseCode < 300;
  } catch (e) {
    return false;
  } finally {
    client.destroy();
  }
}

/** Probe the server: GET the remote dir (a WebDAV dir answers 200/207 or a redirect). */
export async function testSyncConnection(c: SyncConfig): Promise<string> {
  if (c.url.length === 0) {
    return '\u8BF7\u5148\u586B\u5199\u670D\u52A1\u5668\u5730\u5740';
  }
  let base = c.url.replace(/\/+$/, '');
  const dir = c.remotePath.replace(/^\/+|\/+$/g, '');
  if (dir.length > 0) {
    base = base + '/' + dir;
  }
  const client = http.createHttp();
  try {
    const resp = await client.request(base, {
      method: http.RequestMethod.GET,
      header: davHeaders(c, false),
      connectTimeout: 10000,
      readTimeout: 15000
    });
    return '\u8FDE\u63A5\u6210\u529F\uFF0CHTTP ' + resp.responseCode;
  } catch (e) {
    return '\u8FDE\u63A5\u5931\u8D25\uFF1A' + String(e);
  } finally {
    client.destroy();
  }
}

/**
 * One sync pass over all books with progress/bookmarks.
 * Server payload wins when its updatedAt is newer (较新优先).
 */
export async function runSync(ctx: Context, cfg: SyncConfig): Promise<SyncLogEntry> {
  const start = Date.now();
  const entry: SyncLogEntry = { time: new Date().toISOString(), up: 0, linked: 0, ms: 0, details: [] };
  if (cfg.url.length === 0) {
    entry.details.push('\u672A\u914D\u7F6E\u670D\u52A1\u5668\u5730\u5740\uFF0C\u53D6\u6D88\u540C\u6B65');
    entry.ms = Date.now() - start;
    return entry;
  }
  const books: RecentBook[] = await exportRecentBooks(ctx);
  const allBookmarks: Bookmark[] = await exportBookmarks(ctx);
  for (let i = 0; i < books.length; i++) {
    const book = books[i];
    const localBms: Bookmark[] = [];
    for (let j = 0; j < allBookmarks.length; j++) {
      if (allBookmarks[j].path === book.path) {
        localBms.push(allBookmarks[j]);
      }
    }
    const local: SyncPayload = {
      title: book.title,
      page: book.page,
      totalPages: book.totalPages,
      updatedAt: book.lastRead,
      bookmarks: localBms
    };
    /* skip books without any progress and without bookmarks */
    if (book.page <= 0 && localBms.length === 0 && book.lastRead.length === 0) {
      continue;
    }
    entry.linked++;
    const fileName = safeName(book);
    let remote: SyncPayload | undefined;
    try {
      const raw = await davGet(cfg, fileName);
      if (raw !== undefined && raw.length > 0) {
        remote = JSON.parse(raw) as SyncPayload;
      }
    } catch (e) {
      remote = undefined;
    }
    if (remote === undefined) {
      const ok = await davPut(cfg, fileName, JSON.stringify(local));
      if (ok) {
        entry.up++;
        entry.details.push('\u3010\u4E0A\u4F20\u3011' + book.title + ' \uFF08\u7B2C ' + (book.page + 1) + ' \u9875\uFF09');
      } else {
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u4E0A\u4F20\u5931\u8D25');
      }
      continue;
    }
    const localNewer = local.updatedAt.localeCompare(remote.updatedAt) >= 0;
    if (localNewer) {
      const same = local.page === remote.page && localBms.length === remote.bookmarks.length;
      if (same) {
        entry.details.push('\u3010\u5DF2\u6700\u65B0\u3011' + book.title);
        continue;
      }
      const ok = await davPut(cfg, fileName, JSON.stringify(local));
      if (ok) {
        entry.up++;
        entry.details.push('\u3010\u4E0A\u4F20\u3011' + book.title + ' \u672C\u5730\u8F83\u65B0\uFF08\u7B2C ' +
          (local.page + 1) + ' \u2190 ' + (remote.page + 1) + ' \u9875\uFF09');
      } else {
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u4E0A\u4F20\u5931\u8D25');
      }
    } else {
      /* server is newer: apply progress + merge bookmarks */
      book.page = remote.page;
      if (remote.totalPages > 0) {
        book.totalPages = remote.totalPages;
      }
      await saveRecentBook(ctx, book);
      const kept: Bookmark[] = [];
      for (let j = 0; j < allBookmarks.length; j++) {
        if (allBookmarks[j].path !== book.path) {
          kept.push(allBookmarks[j]);
        }
      }
      for (let j = 0; j < remote.bookmarks.length; j++) {
        const bm: Bookmark = remote.bookmarks[j];
        kept.push({ path: book.path, page: bm.page, label: bm.label, date: bm.date });
      }
      await replaceBookmarks(ctx, kept);
      entry.details.push('\u3010\u4E0B\u8F7D\u3011' + book.title + ' \u670D\u52A1\u5668\u8F83\u65B0\uFF08\u7B2C ' +
        (remote.page + 1) + ' \u2190 ' + (local.page + 1) + ' \u9875\uFF09\uFF0C\u5408\u5165\u4E66\u7B7E ' + remote.bookmarks.length);
    }
  }
  entry.ms = Date.now() - start;
  await appendSyncLog(ctx, entry);
  cfg.lastSyncTime = entry.time;
  cfg.lastUp = entry.up;
  cfg.lastLinked = entry.linked;
  cfg.lastMs = entry.ms;
  await saveSyncConfig(ctx, cfg);
  console.info(`[Sync] pass done: linked=${entry.linked} up=${entry.up} ms=${entry.ms}`);
  return entry;
}
