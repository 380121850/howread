import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';
import util from '@ohos.util';
import { RecentBook, exportRecentBooks, saveRecentBook } from './ReadingProgress';
import { exportBookmarks, replaceBookmarks, Bookmark } from './Bookmarks';

/**
 * WebDAV sync (Android syncThreeWayFile parity, round 4 C):
 * per-book reading progress + bookmarks are mirrored to
 * <remotePath>/<safe-name>.howread.json. A local ".base" snapshot
 * (last merged state) enables field-level three-way merge: page changes
 * are taken from whichever side moved since base, bookmark deletions on
 * either side propagate (tombstone-style), additions union. Both sides
 * changed → conflict policy (本地优先 / 服务器优先 / 较新优先).
 * The HarmonyOS HTTP stack lacks WebDAV verbs, so only GET/PUT are used.
 */

export const SYNC_CONFLICTS: string[] = [
  '\u8F83\u65B0\u4F18\u5148\uFF08\u6309\u4FEE\u6539\u65F6\u95F4\uFF09',
  '\u672C\u5730\u4F18\u5148',
  '\u670D\u52A1\u5668\u4F18\u5148'
];

export interface SyncConfig {
  enabled: boolean;
  url: string;
  user: string;
  password: string;
  trusted: boolean;
  /** remote directory, e.g. /dav/Books */
  remotePath: string;
  /** one of SYNC_CONFLICTS */
  conflict: string;
  /** scheduled sync while the app is alive */
  autoSync: boolean;
  /** interval minutes (>= 1, default 5) */
  autoSyncMin: number;
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
    conflict: SYNC_CONFLICTS[0],
    autoSync: false,
    autoSyncMin: 5,
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
const PREF_BASE = 'librera_syncbase';

let syncPref: preferences.Preferences | undefined = undefined;
let logPref: preferences.Preferences | undefined = undefined;
let basePref: preferences.Preferences | undefined = undefined;

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

async function getBasePrefs(ctx: Context): Promise<preferences.Preferences> {
  if (basePref === undefined) {
    basePref = await preferences.getPreferences(ctx, PREF_BASE);
  }
  return basePref;
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
      autoSync: r.autoSync === true,
      autoSyncMin: typeof r.autoSyncMin === 'number' && r.autoSyncMin > 0 ? r.autoSyncMin : d.autoSyncMin,
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

/* ---- .base snapshots (last merged state per remote file) ---- */

async function loadBase(ctx: Context, key: string): Promise<SyncPayload | undefined> {
  try {
    const prefs = await getBasePrefs(ctx);
    const json: string = await prefs.get(key, '') as string;
    if (json.length === 0) {
      return undefined;
    }
    return JSON.parse(json) as SyncPayload;
  } catch (e) {
    return undefined;
  }
}

async function saveBase(ctx: Context, key: string, payload: SyncPayload): Promise<void> {
  try {
    const prefs = await getBasePrefs(ctx);
    await prefs.put(key, JSON.stringify(payload));
    await prefs.flush();
  } catch (e) {
    console.error('[Sync] base save failed');
  }
}

/* ---- three-way merge helpers (Android syncThreeWayFile parity) ---- */

function bmHas(list: Bookmark[], date: string): boolean {
  for (let i = 0; i < list.length; i++) {
    if (list[i].date === date) {
      return true;
    }
  }
  return false;
}

/**
 * Tombstone-style union: an entry survives if it exists on both sides, or
 * was added after base on either side; deletion on either side propagates.
 */
function mergeBookmarks(base: Bookmark[], local: Bookmark[], remote: Bookmark[]): Bookmark[] {
  const out: Bookmark[] = [];
  for (let i = 0; i < local.length; i++) {
    if (!bmHas(remote, local[i].date) && bmHas(base, local[i].date)) {
      continue; /* deleted on the server since base */
    }
    out.push(local[i]);
  }
  for (let i = 0; i < remote.length; i++) {
    if (bmHas(local, remote[i].date)) {
      continue;
    }
    if (bmHas(base, remote[i].date)) {
      continue; /* deleted locally since base */
    }
    out.push({ path: remote[i].path, page: remote[i].page, label: remote[i].label, date: remote[i].date });
  }
  return out;
}

function sameBms(a: Bookmark[], b: Bookmark[]): boolean {
  if (a.length !== b.length) {
    return false;
  }
  for (let i = 0; i < a.length; i++) {
    if (!bmHas(b, a[i].date)) {
      return false;
    }
  }
  return true;
}

function samePayload(a: SyncPayload, b: SyncPayload, bmsB: Bookmark[]): boolean {
  return a.page === b.page && a.updatedAt === b.updatedAt && sameBms(a.bookmarks, bmsB);
}

/** Probe the server: GET the remote dir (a WebDAV dir answers 200/207 or a redirect). */export async function testSyncConnection(c: SyncConfig): Promise<string> {
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
 * Three-way merge against the .base snapshot; conflict policy applies when
 * both sides changed the same field since base.
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
        await saveBase(ctx, fileName, local);
      } else {
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u4E0A\u4F20\u5931\u8D25');
      }
      continue;
    }

    const base = await loadBase(ctx, fileName);
    let merged: SyncPayload;
    let conflicted = false;
    if (base === undefined) {
      /* no snapshot yet: fall back to newer-wins on the whole payload */
      merged = local.updatedAt.localeCompare(remote.updatedAt) >= 0
        ? { title: local.title, page: local.page, totalPages: local.totalPages, updatedAt: local.updatedAt, bookmarks: mergeBookmarks(remote.bookmarks, localBms, remote.bookmarks) }
        : { title: remote.title, page: remote.page, totalPages: remote.totalPages, updatedAt: remote.updatedAt, bookmarks: mergeBookmarks(localBms, localBms, remote.bookmarks) };
    } else {
      const localPageChanged = base.page !== local.page || base.updatedAt !== local.updatedAt;
      const remotePageChanged = base.page !== remote.page || base.updatedAt !== remote.updatedAt;
      let page = local.page;
      let totalPages = local.totalPages;
      let updatedAt = local.updatedAt;
      if (localPageChanged && remotePageChanged) {
        conflicted = true;
        if (cfg.conflict === SYNC_CONFLICTS[1]) {
          /* 本地优先 */
        } else if (cfg.conflict === SYNC_CONFLICTS[2]) {
          page = remote.page;
          totalPages = remote.totalPages > 0 ? remote.totalPages : totalPages;
          updatedAt = remote.updatedAt;
        } else if (remote.updatedAt.localeCompare(local.updatedAt) > 0) {
          page = remote.page;
          totalPages = remote.totalPages > 0 ? remote.totalPages : totalPages;
          updatedAt = remote.updatedAt;
        }
      } else if (remotePageChanged) {
        page = remote.page;
        totalPages = remote.totalPages > 0 ? remote.totalPages : totalPages;
        updatedAt = remote.updatedAt;
      }
      merged = {
        title: local.title,
        page: page,
        totalPages: totalPages,
        updatedAt: updatedAt,
        bookmarks: mergeBookmarks(base.bookmarks, localBms, remote.bookmarks)
      };
    }

    const sameAsLocal = samePayload(local, merged, merged.bookmarks);
    const sameAsRemote = samePayload(remote, merged, merged.bookmarks);
    if (sameAsLocal && sameAsRemote) {
      entry.details.push('\u3010\u5DF2\u6700\u65B0\u3011' + book.title);
      if (base === undefined || !samePayload(base, merged, merged.bookmarks)) {
        await saveBase(ctx, fileName, merged);
      }
      continue;
    }
    if (conflicted) {
      entry.details.push('\u3010\u51B2\u7A81\u3011' + book.title + ' \u53CC\u65B9\u5747\u6709\u6539\u52A8\uFF0C\u6309' + cfg.conflict +
        '\uFF1A\u7B2C ' + (merged.page + 1) + ' \u9875');
    }
    /* apply merged state to local when it differs */
    if (!sameAsLocal) {
      book.page = merged.page;
      if (merged.totalPages > 0) {
        book.totalPages = merged.totalPages;
      }
      book.lastRead = merged.updatedAt;
      await saveRecentBook(ctx, book);
      const kept: Bookmark[] = [];
      for (let j = 0; j < allBookmarks.length; j++) {
        if (allBookmarks[j].path !== book.path) {
          kept.push(allBookmarks[j]);
        }
      }
      for (let j = 0; j < merged.bookmarks.length; j++) {
        const bm = merged.bookmarks[j];
        kept.push({ path: book.path, page: bm.page, label: bm.label, date: bm.date });
      }
      await replaceBookmarks(ctx, kept);
      entry.details.push('\u3010\u5408\u5E76\u3011' + book.title + ' \u5DF2\u5E94\u7528\u5230\u672C\u5730\uFF08\u7B2C ' +
        (merged.page + 1) + ' \u9875\uFF0C\u4E66\u7B7E ' + merged.bookmarks.length + '\uFF09');
    }
    /* write merged state back to the server when it differs */
    if (!sameAsRemote) {
      const ok = await davPut(cfg, fileName, JSON.stringify(merged));
      if (ok) {
        entry.up++;
        entry.details.push('\u3010\u4E0A\u4F20\u3011' + book.title + ' \u5408\u5E76\u7ED3\u679C\u5DF2\u56DE\u5199');
      } else {
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u56DE\u5199\u5931\u8D25');
      }
    }
    await saveBase(ctx, fileName, merged);
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
