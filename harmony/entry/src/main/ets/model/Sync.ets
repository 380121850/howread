import http from '@ohos.net.http';
import preferences from '@ohos.data.preferences';
import util from '@ohos.util';
import { RecentBook, exportRecentBooks, saveRecentBook } from './ReadingProgress';
import { Bookmark, exportBookmarks, replaceBookmarks, loadBookmarkTombstones,
  pruneBookmarkTombstones } from './Bookmarks';
/* 0.9.44: AI notes join the per-book payload as android isAiNote bookmarks;
 * playlists publish under the android global/ layout */
import { AiNote, getNotes, replaceNotesForBook } from './Notes';
import { deletePlaylist, getAllPlaylists, getPlaylistItems, updatePlaylist,
  PlaylistMeta } from './Playlists';
import { loadProxy, proxyOpt } from './HttpUtil';
import { bookMd5 } from './BookHash';
import { OpdsServer, DavServer, SmbServer, SftpServer, LibFolder, getOpdsServers, getDavServers,
  getSmbServers, getSftpServers, getLibFolders, saveOpdsServer, saveDavServer, removeDavServer,
  saveSmbServer, removeSmbServer, saveSftpServer, removeSftpServer, saveLibFolders } from './Servers';
import { sha256Hex } from './RemoteBook';
import { exportAiPayload, mergeAiPayloads, importAiPayload } from './AiVendors';
import { ReaderSettings, loadSettings, saveSettings } from './Settings';
import { exportStatsSyncPayload, importStatsSyncPayload } from './ReadingStats';

/**
 * WebDAV interop sync (0.9.39, Android WebDavSyncer.java parity).
 *
 * Remote layout — the ANDROID layout, byte-compatible on the server:
 *   <root>/<remotePath>/books/<md5>.json  per-book info:
 *     { name, hash, t, progress:{p,pg,pgc,t}, bookmarks:{<msKey>:entry}, dk:{<msKey>:delMs} }
 *   Book identity = the book file's content MD5 (lowercase hex, BookHash), so
 *   the same physical book on both platforms maps to the same server file.
 *
 * Merge semantics (Android parity):
 *   - progress entry: whole-entry winner, policy = local/server/farther/newer-t
 *     (webdavSyncPolicy parity); unknown keys of the remote entry preserved.
 *   - bookmarks: union keyed by creation time (ms); deletions propagate both
 *     ways via the in-file "dk" tombstone map (30-day expiry, recreation wins).
 *   - publish only when the merged info semantically differs from the server
 *     copy (comparison ignores the display timestamp "t").
 *   - GET transport failure != 404: a failed GET never overwrites the server
 *     copy and never consumes tombstones (Android fetchText contract).
 *
 * Legacy HarmonyOS files (<remotePath>/<safe-name>.howread.json, pre-0.9.39)
 * are read once per book and converted into the android layout; they are never
 * written again and never deleted.
 */

export const SYNC_CONFLICTS: string[] = [
  '\u8F83\u65B0\u4F18\u5148\uFF08\u6309\u4FEE\u6539\u65F6\u95F4\uFF09',
  '\u672C\u5730\u4F18\u5148',
  '\u670D\u52A1\u5668\u4F18\u5148',
  '\u8BFB\u5F97\u66F4\u8FDC\u4F18\u5148'
];

const SYNC_POLICY_LOCAL = 'local';
const SYNC_POLICY_SERVER = 'server';
const SYNC_POLICY_FARTHER = 'farther';

export interface SyncConfig {
  enabled: boolean;
  url: string;
  user: string;
  password: string;
  trusted: boolean;
  /** remote directory below the server root; must match the ANDROID
   *  webdavSyncRemoteDir (default 'HowRead') for interop */
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
    remotePath: 'HowRead',
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
const KEY_LAST_HASHES = 'lastHashes';
const KEY_LEGACY_MIGRATED = 'legacyMigrated';
const PREF_LOG = 'librera_synclog';
const KEY_LOG = 'log';
const MAX_LOG = 100;
const PREF_DELBOOKS = 'librera_delbooks';
const KEY_DELBOOKS = 'delbooks';
const PREF_PENDING = 'librera_pendingbooks';
const KEY_PENDING = 'pending';
const DK_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;
const REMOTE_BOOKS = 'books';

let syncPref: preferences.Preferences | undefined = undefined;
let logPref: preferences.Preferences | undefined = undefined;
let delPref: preferences.Preferences | undefined = undefined;
let pendPref: preferences.Preferences | undefined = undefined;

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

async function getDelPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (delPref === undefined) {
    delPref = await preferences.getPreferences(ctx, PREF_DELBOOKS);
  }
  return delPref;
}

async function getPendPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (pendPref === undefined) {
    pendPref = await preferences.getPreferences(ctx, PREF_PENDING);
  }
  return pendPref;
}

async function getStringPref(prefs: preferences.Preferences, key: string, dflt: string): Promise<string> {
  try {
    return await prefs.get(key, dflt) as string;
  } catch (e) {
    return dflt;
  }
}

async function putStringPref(prefs: preferences.Preferences, key: string, value: string): Promise<void> {
  await prefs.put(key, value);
  await prefs.flush();
}

export async function loadSyncConfig(ctx: Context): Promise<SyncConfig> {
  const d = defaultSyncConfig();
  try {
    const prefs = await getConfigPrefs(ctx);
    const json = await getStringPref(prefs, KEY_CONFIG, '');
    if (json.length === 0) {
      return d;
    }
    const r = JSON.parse(json) as Record<string, Object>;
    let rp = typeof r['remotePath'] === 'string' ? r['remotePath'] as string : d.remotePath;
    /* pre-0.9.39 default was '/dav/Books'; the interop layout needs the
     * android webdavSyncRemoteDir value — migrate the never-changed default */
    if (rp === '/dav/Books') {
      rp = 'HowRead';
      console.info('[Sync] remotePath default migrated /dav/Books -> HowRead');
    }
    const c: SyncConfig = {
      enabled: r['enabled'] === true,
      url: typeof r['url'] === 'string' ? r['url'] as string : '',
      user: typeof r['user'] === 'string' ? r['user'] as string : '',
      password: typeof r['password'] === 'string' ? r['password'] as string : '',
      trusted: r['trusted'] === true,
      remotePath: rp.length > 0 ? rp : d.remotePath,
      conflict: typeof r['conflict'] === 'string' && (r['conflict'] as string).length > 0
        ? r['conflict'] as string : d.conflict,
      autoSync: r['autoSync'] === true,
      autoSyncMin: typeof r['autoSyncMin'] === 'number' && (r['autoSyncMin'] as number) > 0
        ? r['autoSyncMin'] as number : d.autoSyncMin,
      lastSyncTime: typeof r['lastSyncTime'] === 'string' ? r['lastSyncTime'] as string : '',
      lastUp: typeof r['lastUp'] === 'number' ? r['lastUp'] as number : 0,
      lastLinked: typeof r['lastLinked'] === 'number' ? r['lastLinked'] as number : 0,
      lastMs: typeof r['lastMs'] === 'number' ? r['lastMs'] as number : 0
    };
    return c;
  } catch (e) {
    return d;
  }
}

export async function saveSyncConfig(ctx: Context, c: SyncConfig): Promise<void> {
  try {
    const prefs = await getConfigPrefs(ctx);
    await putStringPref(prefs, KEY_CONFIG, JSON.stringify(c));
    console.info('[Sync] config saved: url=' + c.url);
  } catch (e) {
    console.error('[Sync] config save failed');
  }
}

export async function getSyncLog(ctx: Context): Promise<SyncLogEntry[]> {
  try {
    const prefs = await getLogPrefs(ctx);
    const json = await getStringPref(prefs, KEY_LOG, '[]');
    const list: SyncLogEntry[] = JSON.parse(json) as SyncLogEntry[];
    list.sort((a: SyncLogEntry, b: SyncLogEntry) => b.time.localeCompare(a.time));
    return list;
  } catch (e) {
    const none: SyncLogEntry[] = [];
    return none;
  }
}

async function appendSyncLog(ctx: Context, entry: SyncLogEntry): Promise<void> {
  try {
    const prefs = await getLogPrefs(ctx);
    const json = await getStringPref(prefs, KEY_LOG, '[]');
    const list: SyncLogEntry[] = JSON.parse(json) as SyncLogEntry[];
    list.push(entry);
    while (list.length > MAX_LOG) {
      list.shift();
    }
    await putStringPref(prefs, KEY_LOG, JSON.stringify(list));
  } catch (e) {
    console.error('[Sync] log append failed');
  }
}

/* ---------------- small helpers ---------------- */

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

function syncRootUrl(c: SyncConfig): string {
  const dir = c.remotePath.replace(/^\/+|\/+$/g, '');
  const base = c.url.replace(/\/+$/, '');
  if (dir.length === 0) {
    return base;
  }
  return base + '/' + dir;
}

function booksUrlOf(c: SyncConfig): string {
  return syncRootUrl(c) + '/' + REMOTE_BOOKS;
}

function globalUrlOf(c: SyncConfig): string {
  return syncRootUrl(c) + '/global';
}

function baseNameOf(p: string): string {
  const s = p.replace(/\\/g, '/');
  const i = s.lastIndexOf('/');
  return i >= 0 ? s.substring(i + 1) : s;
}

function clampPage(v: number, pages: number): number {
  let x = Math.round(v);
  if (x < 0) {
    x = 0;
  }
  if (pages > 0 && x > pages - 1) {
    x = pages - 1;
  }
  return x;
}

function recNum(r: Record<string, Object> | undefined, k: string, d: number): number {
  if (r === undefined) {
    return d;
  }
  const v = r[k];
  return typeof v === 'number' ? v as number : d;
}

function recStr(r: Record<string, Object> | undefined, k: string, d: string): string {
  if (r === undefined) {
    return d;
  }
  const v = r[k];
  return typeof v === 'string' ? v as string : d;
}

function asRec(v: Object | undefined): Record<string, Object> {
  if (v !== undefined && v !== null && typeof v === 'object') {
    return v as Record<string, Object>;
  }
  const e: Record<string, Object> = {};
  return e;
}

function cloneRecord(r: Record<string, Object>): Record<string, Object> {
  return JSON.parse(JSON.stringify(r)) as Record<string, Object>;
}

/** canonical JSON string with recursively sorted keys (order-insensitive compare) */
function canon(v: Object): string {
  if (Array.isArray(v)) {
    const arr = v as Object[];
    const parts: string[] = [];
    for (let i = 0; i < arr.length; i++) {
      parts.push(canon(arr[i]));
    }
    return '[' + parts.join(',') + ']';
  }
  if (v !== null && typeof v === 'object') {
    const rec = v as Record<string, Object>;
    const keys = Object.keys(rec).sort();
    const parts: string[] = [];
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      parts.push(JSON.stringify(k) + ':' + canon(rec[k]));
    }
    return '{' + parts.join(',') + '}';
  }
  return JSON.stringify(v);
}

function withoutT(r: Record<string, Object>): Record<string, Object> {
  const out: Record<string, Object> = {};
  const keys = Object.keys(r);
  for (let i = 0; i < keys.length; i++) {
    if (keys[i] !== 't') {
      out[keys[i]] = r[keys[i]];
    }
  }
  return out;
}

function dateMs(iso: string, dflt: number): number {
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? dflt : ms;
}

function isoOf(ms: number): string {
  return new Date(ms).toISOString();
}

/* ---------------- Dav layer ---------------- */

interface DavResp {
  code: number;
  body: string;
}

const GET_OK = 'ok';
const GET_MISSING = 'missing';
const GET_ERROR = 'error';

interface Get3 {
  kind: string;
  body: string;
}

function get3(kind: string, body: string): Get3 {
  const e: Get3 = { kind: kind, body: body };
  return e;
}

async function davExec(ctx: Context, c: SyncConfig, method: string, url: string,
  body?: string): Promise<DavResp | undefined> {
  const client = http.createHttp();
  try {
    const proxy = proxyOpt(await loadProxy(ctx));
    const header: Record<string, string> = {};
    const auth = basicAuth(c.user, c.password);
    if (auth.length > 0) {
      header['Authorization'] = auth;
    }
    if (body !== undefined) {
      header['Content-Type'] = method === 'PROPFIND'
        ? 'application/xml' : 'application/json; charset=utf-8';
    }
    if (method === 'PROPFIND') {
      header['Depth'] = '1';
    }
    /* Build the options through a Record and one JSON round-trip cast: custom
     * verbs (PROPFIND/MKCOL) are plain strings not present in the
     * http.RequestMethod enum, and ArkTS forbids the `as unknown as` cast. If
     * the stack rejects a custom verb the request throws -> davExec returns
     * undefined -> callers degrade to the GET/PUT/DELETE-only path. */
    const optRec: Record<string, Object> = {
      'method': method,
      'header': header,
      'connectTimeout': 10000,
      'readTimeout': 30000,
      'usingProxy': proxy
    };
    if (body !== undefined) {
      optRec['extraData'] = body;
    }
    const opts = JSON.parse(JSON.stringify(optRec)) as http.HttpRequestOptions;
    const resp = await client.request(url, opts);
    let text = '';
    try {
      text = resp.result as string;
    } catch (e) {
      text = '';
    }
    const out: DavResp = { code: resp.responseCode, body: text };
    return out;
  } catch (e) {
    return undefined;
  } finally {
    client.destroy();
  }
}

/** Android fetchText contract: 200 with a non-empty body → ok; 404 → missing;
 *  ANYTHING else (transport/auth/5xx/empty body) → error: the caller must not
 *  overwrite the server copy nor consume tombstones in this round. */
async function davGet3(ctx: Context, c: SyncConfig, url: string): Promise<Get3> {
  const r = await davExec(ctx, c, 'GET', url);
  if (r === undefined) {
    return get3(GET_ERROR, '');
  }
  if (r.code === 200 && r.body.trim().length > 0) {
    return get3(GET_OK, r.body);
  }
  if (r.code === 404) {
    return get3(GET_MISSING, '');
  }
  return get3(GET_ERROR, '');
}

async function davPut(ctx: Context, c: SyncConfig, url: string, body: string): Promise<boolean> {
  const r = await davExec(ctx, c, 'PUT', url, body);
  return r !== undefined && r.code >= 200 && r.code < 300;
}

async function davDelete(ctx: Context, c: SyncConfig, url: string): Promise<boolean> {
  const r = await davExec(ctx, c, 'DELETE', url);
  if (r === undefined) {
    return false;
  }
  return (r.code >= 200 && r.code < 300) || r.code === 404;
}

async function davMkcol(ctx: Context, c: SyncConfig, url: string): Promise<boolean> {
  const r = await davExec(ctx, c, 'MKCOL', url);
  if (r === undefined) {
    return false;
  }
  /* 301 = exists on apache mod_dav (no trailing slash); 405 = exists per RFC */
  return (r.code >= 200 && r.code < 300) || r.code === 301 || r.code === 405 || r.code === 409;
}

/** PROPFIND Depth:1 listing of a collection → plain file names, or null when
 *  the verb is unsupported / the request failed (degrade to hash-probe mode). */
async function davPropfind(ctx: Context, c: SyncConfig, url: string): Promise<string[] | null> {
  const r = await davExec(ctx, c, 'PROPFIND', url,
    '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>');
  if (r === undefined || r.code !== 207) {
    return null;
  }
  const out: string[] = [];
  const re = /<(?:[A-Za-z0-9]+:)?href>\s*([^<]+)\s*<\/(?:[A-Za-z0-9]+:)?href>/gi;
  let m: RegExpExecArray | null = re.exec(r.body);
  while (m !== null) {
    let href = m[1];
    try {
      href = decodeURIComponent(href);
    } catch (e) {
      /* keep raw */
    }
    if (!href.endsWith('/')) {
      const i = href.lastIndexOf('/');
      if (i >= 0 && i + 1 < href.length) {
        out.push(href.substring(i + 1));
      }
    }
    m = re.exec(r.body);
  }
  return out;
}

/** Probe the server: GET the remote dir (a WebDAV dir answers 200/207 or a redirect). */
export async function testSyncConnection(c: SyncConfig, ctx?: Context): Promise<string> {
  if (c.url.length === 0) {
    return '\u8BF7\u5148\u586B\u5199\u670D\u52A1\u5668\u5730\u5740';
  }
  const proxy = proxyOpt(await loadProxy(ctx));
  const client = http.createHttp();
  try {
    const header: Record<string, string> = {};
    const auth = basicAuth(c.user, c.password);
    if (auth.length > 0) {
      header['Authorization'] = auth;
    }
    const resp = await client.request(syncRootUrl(c), {
      method: http.RequestMethod.GET,
      header: header,
      connectTimeout: 10000,
      readTimeout: 15000,
      usingProxy: proxy
    });
    return '\u8FDE\u63A5\u6210\u529F\uFF0CHTTP ' + resp.responseCode;
  } catch (e) {
    return '\u8FDE\u63A5\u5931\u8D25\uFF1A' + String(e);
  } finally {
    client.destroy();
  }
}

/* ---------------- per-book info shape (android parity) ---------------- */

interface DelBookRec {
  name: string;
  hash: string;
  t: number;
}

function entryFromBookmark(bm: Bookmark, ms: number): Record<string, Object> {
  const pgc = bm.pgs > 0 ? bm.pgs : -1;
  const e: Record<string, Object> = {
    'path': bm.path,
    'text': bm.label,
    'p': pgc > 0 ? bm.page / pgc : 0,
    'pg': bm.page,
    'pgc': pgc,
    /* 0.9.44: android bookmarks serialize FIELD NAMES (AppBookmark via
     * Objects.loadFromJson) - the epoch key there is "pgs"; "pgc" stays
     * for pre-0.9.44 harmony readers */
    'pgs': pgc,
    't': ms
  };
  /* 0.9.43: AI-note bookmarks keep their flags on the wire (android
   * AppBookmark isAiNote/aiAnswer parity) instead of degrading to plain
   * bookmarks after one round-trip */
  if (bm.isAiNote === true) {
    e['isAiNote'] = true;
  }
  if (bm.aiAnswer !== undefined && bm.aiAnswer.length > 0) {
    e['aiAnswer'] = bm.aiAnswer;
  }
  return e;
}

function localProgressEntry(book: RecentBook, nowMs: number): Record<string, Object> {
  const pages = book.totalPages > 0 ? book.totalPages : -1;
  const e: Record<string, Object> = {
    'p': pages > 0 ? book.page / pages : 0,
    'pg': book.page,
    'pgc': pages,
    't': book.lastRead.length > 0 ? dateMs(book.lastRead, nowMs) : nowMs
  };
  return e;
}

/** adopt the remote progress entry into the local book (android
 *  mergeProgressEntry + AppBook.getPage rules). Returns true when changed. */
function adoptProgress(book: RecentBook, rp: Record<string, Object>): boolean {
  const rp2 = recNum(rp, 'p', 0);
  const rt = recNum(rp, 't', 0);
  const pg = recNum(rp, 'pg', -1);
  const pgc = recNum(rp, 'pgc', -1);
  let page = book.page;
  if (book.totalPages > 0) {
    if (pg >= 0 && pgc === book.totalPages && pg < book.totalPages) {
      page = pg;
    } else {
      page = clampPage(rp2 * book.totalPages, book.totalPages);
    }
  } else if (pg >= 0) {
    /* local layout epoch unknown yet: trust the absolute anchor */
    page = pg;
  }
  const lastRead = rt > 0 ? isoOf(rt) : book.lastRead;
  if (page === book.page && lastRead === book.lastRead) {
    return false;
  }
  book.page = page;
  book.lastRead = lastRead;
  return true;
}

function policyOf(c: SyncConfig): string {
  if (c.conflict === SYNC_CONFLICTS[1]) {
    return SYNC_POLICY_LOCAL;
  }
  if (c.conflict === SYNC_CONFLICTS[2]) {
    return SYNC_POLICY_SERVER;
  }
  if (c.conflict === SYNC_CONFLICTS[3]) {
    return SYNC_POLICY_FARTHER;
  }
  return 'newer';
}

function bookmarkOf(key: string, e: Record<string, Object>, path: string): Bookmark {
  const pg = recNum(e, 'pg', -1);
  /* 0.9.44: android carries the epoch total as "pgs" (field-name
   * serialization); "pgc" was this port's pre-0.9.44 spelling */
  let pgc = recNum(e, 'pgs', -1);
  if (pgc < 0) {
    pgc = recNum(e, 'pgc', -1);
  }
  const p = recNum(e, 'p', 0);
  const t = recNum(e, 't', 0);
  let page: number;
  if (pg >= 0) {
    page = pg;
  } else if (pgc > 0) {
    page = clampPage(p * pgc, pgc);
  } else {
    page = 0;
  }
  const bm: Bookmark = {
    path: path,
    page: page,
    label: recStr(e, 'text', ''),
    date: isoOf(t > 0 ? t : dateMs(key, Date.now())),
    pgs: pgc
  };
  if (e['isAiNote'] === true) {
    bm.isAiNote = true;
  }
  const ans = recStr(e, 'aiAnswer', '');
  if (ans.length > 0) {
    bm.aiAnswer = ans;
  }
  return bm;
}

interface PendRec {
  body: string;
  t: number;
}

/** load the legacy pre-0.9.39 file <root>/<safe-name>.howread.json as an info
 *  object; GET_MISSING when it does not exist, GET_ERROR when unreachable. */
async function loadLegacyInfo(ctx: Context, c: SyncConfig, book: RecentBook,
  hash: string): Promise<Get3> {
  const title = book.title.length > 0 ? book.title : baseNameOf(book.path);
  const safe = title.replace(/[^0-9A-Za-z\u4e00-\u9fa5._-]+/g, '_') + '.howread.json';
  const url = syncRootUrl(c) + '/' + safe;
  const g = await davGet3(ctx, c, url);
  if (g.kind === GET_ERROR) {
    return g; /* retried next round, not recorded as migrated */
  }
  if (g.kind === GET_MISSING) {
    return g;
  }
  let legacy: Record<string, Object>;
  try {
    legacy = JSON.parse(g.body) as Record<string, Object>;
  } catch (e) {
    return get3(GET_MISSING, '');
  }
  const page = typeof legacy['page'] === 'number' ? legacy['page'] as number : 0;
  const totalPages = typeof legacy['totalPages'] === 'number' ? legacy['totalPages'] as number : 0;
  const updatedAt = recStr(legacy, 'updatedAt', '');
  const rawBms = legacy['bookmarks'];
  const bmArr: Bookmark[] = Array.isArray(rawBms) ? rawBms as Bookmark[] : [];
  const info: Record<string, Object> = {
    'name': baseNameOf(book.path),
    'hash': hash,
    't': 0,
    'progress': {
      'p': totalPages > 0 ? page / totalPages : 0,
      'pg': page,
      'pgc': totalPages > 0 ? totalPages : -1,
      't': updatedAt.length > 0 ? dateMs(updatedAt, Date.now()) : Date.now()
    } as Record<string, Object>,
    'bookmarks': {} as Record<string, Object>
  };
  const bms = info['bookmarks'] as Record<string, Object>;
  let newest = recNum(info['progress'] as Record<string, Object>, 't', 0);
  for (let i = 0; i < bmArr.length; i++) {
    const bm = bmArr[i];
    if (bm === undefined || bm === null) {
      continue;
    }
    const ms = dateMs(bm.date, Date.now());
    if (ms > newest) {
      newest = ms;
    }
    bms[String(ms)] = entryFromBookmark(bm, ms);
  }
  info['t'] = newest;
  return get3(GET_OK, JSON.stringify(info));
}

/* ---------------- base snapshots (last merged state per global file) ---- */

const PREF_BASE = 'librera_syncbase';
let basePref: preferences.Preferences | undefined = undefined;

async function getBasePrefs(ctx: Context): Promise<preferences.Preferences> {
  if (basePref === undefined) {
    basePref = await preferences.getPreferences(ctx, PREF_BASE);
  }
  return basePref;
}

async function loadBaseText(ctx: Context, key: string): Promise<string> {
  const prefs = await getBasePrefs(ctx);
  return await getStringPref(prefs, key, '');
}

async function saveBaseText(ctx: Context, key: string, text: string): Promise<void> {
  const prefs = await getBasePrefs(ctx);
  await putStringPref(prefs, key, text);
}

function canonEqText(a: string, b: string): boolean {
  try {
    return canon(JSON.parse(a) as Object) === canon(JSON.parse(b) as Object);
  } catch (e) {
    return false;
  }
}

/* ---------------- deleted-books ledger ---------------- */

async function loadDelBooks(ctx: Context): Promise<DelBookRec[]> {
  try {
    const prefs = await getDelPrefs(ctx);
    const json = await getStringPref(prefs, KEY_DELBOOKS, '[]');
    return JSON.parse(json) as DelBookRec[];
  } catch (e) {
    const none: DelBookRec[] = [];
    return none;
  }
}

async function saveDelBooks(ctx: Context, list: DelBookRec[]): Promise<void> {
  const prefs = await getDelPrefs(ctx);
  await putStringPref(prefs, KEY_DELBOOKS, JSON.stringify(list));
}

async function loadRecPref(ctx: Context, key: string): Promise<Record<string, Object>> {
  const prefs = await getConfigPrefs(ctx);
  const json = await getStringPref(prefs, key, '{}');
  if (json.length === 0) {
    const e: Record<string, Object> = {};
    return e;
  }
  try {
    return JSON.parse(json) as Record<string, Object>;
  } catch (e) {
    const e2: Record<string, Object> = {};
    return e2;
  }
}

async function saveRecPref(ctx: Context, key: string, rec: Record<string, Object>): Promise<void> {
  const prefs = await getConfigPrefs(ctx);
  await putStringPref(prefs, key, JSON.stringify(rec));
}

/* ---------------- main sync pass ---------------- */

/**
 * One interop sync pass over all books with progress/bookmarks (android
 * books/<md5>.json layout). GET-then-merge per book; transport failures skip
 * the book without touching the server copy.
 */
export async function runSync(ctx: Context, cfg: SyncConfig): Promise<SyncLogEntry> {
  const start = Date.now();
  const entry: SyncLogEntry = { time: new Date().toISOString(), up: 0, linked: 0, ms: 0, details: [] };
  if (cfg.url.length === 0) {
    entry.details.push('\u672A\u914D\u7F6E\u670D\u52A1\u5668\u5730\u5740\uFF0C\u53D6\u6D88\u540C\u6B65');
    entry.ms = Date.now() - start;
    return entry;
  }
  const bUrl = booksUrlOf(cfg);
  const rootUrl = syncRootUrl(cfg);

  /* best-effort directory bootstrap (301/405 = already exists) */
  let dirsOk = true;
  if (!await davMkcol(ctx, cfg, rootUrl)) {
    dirsOk = false;
  }
  if (!await davMkcol(ctx, cfg, bUrl)) {
    dirsOk = false;
  }
  if (!dirsOk) {
    console.info('[Sync] MKCOL bootstrap incomplete (dirs may still exist)');
  }

  const books: RecentBook[] = await exportRecentBooks(ctx);
  const allBookmarks: Bookmark[] = await exportBookmarks(ctx);
  await pruneBookmarkTombstones(ctx, DK_MAX_AGE_MS);
  const tombstones = await loadBookmarkTombstones(ctx);

  /* content hashes for every shelf book (cached in BookHash) */
  const hashes: Record<string, string> = {};
  const bookByHash: Record<string, number> = {};
  for (let i = 0; i < books.length; i++) {
    const h = await bookMd5(ctx, books[i].path);
    hashes[books[i].path] = h;
    if (h.length > 0) {
      bookByHash[h] = i;
    }
  }

  /* books/ listing once per round (pending discovery + name-matched cleanup;
   * null = PROPFIND unavailable → hash-probe degrade, interop still works) */
  const listing = await davPropfind(ctx, cfg, bUrl);

  /* deletion detection: paths present last round but gone now */
  const prevHashes = await loadRecPref(ctx, KEY_LAST_HASHES);
  let delBooks = await loadDelBooks(ctx);
  const prevKeys = Object.keys(prevHashes);
  for (let i = 0; i < prevKeys.length; i++) {
    const p = prevKeys[i];
    if (hashes[p] === undefined) {
      const hv = typeof prevHashes[p] === 'string' ? prevHashes[p] as string : '';
      const rec: DelBookRec = { name: baseNameOf(p), hash: hv, t: Date.now() };
      let dup = false;
      for (let j = 0; j < delBooks.length; j++) {
        if (delBooks[j].hash === rec.hash && delBooks[j].name === rec.name) {
          dup = true;
          break;
        }
      }
      if (!dup && (rec.hash.length > 0 || rec.name.length > 0)) {
        delBooks.push(rec);
      }
    }
  }
  /* retry server-copy cleanup for ledger entries (2xx/404 drops the entry) */
  const keptDel: DelBookRec[] = [];
  for (let i = 0; i < delBooks.length; i++) {
    const d = delBooks[i];
    if (Date.now() - d.t > DK_MAX_AGE_MS) {
      continue; /* expired */
    }
    let done = false;
    if (d.hash.length === 32) {
      done = await davDelete(ctx, cfg, bUrl + '/' + d.hash + '.json');
    } else if (listing !== null) {
      /* no hash recorded: match by book name in the listing */
      for (let j = 0; j < listing.length; j++) {
        const n = listing[j];
        if (!n.endsWith('.json') || n.length < 37) {
          continue;
        }
        const g = await davGet3(ctx, cfg, bUrl + '/' + n);
        if (g.kind === GET_OK) {
          let inf: Record<string, Object> = {};
          try {
            inf = JSON.parse(g.body) as Record<string, Object>;
          } catch (e) {
            inf = {};
          }
          if (recStr(inf, 'name', '') === d.name) {
            done = await davDelete(ctx, cfg, bUrl + '/' + n);
            break;
          }
        }
      }
    }
    if (done) {
      entry.details.push('\u3010\u5220\u9664\u3011' + d.name + ' \u670D\u52A1\u5668\u526F\u672C\u5DF2\u6E05\u7406');
    } else {
      keptDel.push(d);
    }
  }
  delBooks = keptDel;
  await saveDelBooks(ctx, delBooks);

  /* legacy migration bookkeeping (per hash, one attempt) */
  const migrated = await loadRecPref(ctx, KEY_LEGACY_MIGRATED);

  const policy = policyOf(cfg);
  const mergedHashes: Record<string, boolean> = {};
  let netFails = 0;

  for (let i = 0; i < books.length; i++) {
    const book = books[i];
    const localBms: Bookmark[] = [];
    for (let j = 0; j < allBookmarks.length; j++) {
      if (allBookmarks[j].path === book.path) {
        localBms.push(allBookmarks[j]);
      }
    }
    /* 0.9.44: harmony AI notes (Notes store) join the per-book payload as
     * android isAiNote bookmarks (AppBookmark isAiNote/aiAnswer parity).
     * Legacy 0.9.43-era isAiNote entries in the bookmark store keep
     * exporting from there; a note whose creation key is already occupied
     * is skipped. */
    const aiBms: Bookmark[] = [];
    const aiByKey: Map<string, AiNote> = new Map();
    const noteKeys: Set<string> = new Set();
    const noteKeyList: string[] = [];
    const takenKeys: Set<string> = new Set();
    for (let j = 0; j < localBms.length; j++) {
      takenKeys.add(String(dateMs(localBms[j].date, Date.now())));
    }
    const bookNotes: AiNote[] = await getNotes(ctx, book.path);
    for (let j = 0; j < bookNotes.length; j++) {
      const note = bookNotes[j];
      const ms = Date.parse(note.date);
      if (Number.isNaN(ms)) {
        continue;
      }
      const key = String(ms);
      if (takenKeys.has(key)) {
        continue;
      }
      takenKeys.add(key);
      const aiBm: Bookmark = {
        path: book.path,
        page: note.page,
        label: note.kind,
        date: note.date,
        pgs: book.totalPages > 0 ? book.totalPages : -1,
        isAiNote: true,
        aiAnswer: note.text
      };
      aiBms.push(aiBm);
      aiByKey.set(key, note);
      noteKeys.add(key);
      noteKeyList.push(key);
    }
    const hasContent = book.page > 0 || localBms.length > 0 || aiBms.length > 0 || book.lastRead.length > 0;
    const hash = hashes[book.path];
    if (hash.length === 0) {
      if (hasContent && book.path.indexOf('remote://') === 0) {
        entry.details.push('\u3010\u8DF3\u8FC7\u3011' + book.title + ' \u8FDC\u7A0B\u4E66\u672A\u7F13\u5B58\uFF0C\u65E0\u6CD5\u8BA1\u7B97\u6307\u7EB9');
      }
      continue;
    }
    if (!hasContent) {
      continue; /* android parity: only books actually opened create server files */
    }
    entry.linked++;
    mergedHashes[hash] = true;
    const infoUrl = bUrl + '/' + hash + '.json';
    const bookName = baseNameOf(book.path);
    const localDk = asRec(tombstones[bookName]);

    let g = await davGet3(ctx, cfg, infoUrl);
    if (g.kind === GET_MISSING && migrated[hash] === undefined) {
      /* one-time legacy conversion: pre-0.9.39 <safe-name>.howread.json */
      const lg = await loadLegacyInfo(ctx, cfg, book, hash);
      if (lg.kind === GET_ERROR) {
        netFails++;
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u7F51\u7EDC\u9519\u8BEF\uFF0C\u672C\u8F6E\u8DF3\u8FC7');
        continue;
      }
      migrated[hash] = true;
      if (lg.kind === GET_OK) {
        g = lg;
        entry.details.push('\u3010\u8FC1\u79FB\u3011' + book.title + ' \u65E7\u683C\u5F0F\u6570\u636E\u5DF2\u5E76\u5165');
      }
      await saveRecPref(ctx, KEY_LEGACY_MIGRATED, migrated);
    }
    if (g.kind === GET_ERROR) {
      netFails++;
      entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u7F51\u7EDC\u9519\u8BEF\uFF0C\u672C\u8F6E\u8DF3\u8FC7');
      continue;
    }

    /* local side of the merge */
    const nowMs = Date.now();
    const localEntries: Record<string, Object> = {};
    const localByKey: Map<string, Bookmark> = new Map();
    const removedLocal: string[] = [];
    let localNewest = 0;
    for (let j = 0; j < localBms.length; j++) {
      const bm = localBms[j];
      const ms = dateMs(bm.date, nowMs);
      const key = String(ms);
      localEntries[key] = entryFromBookmark(bm, ms);
      localByKey.set(key, bm);
      if (ms > localNewest) {
        localNewest = ms;
      }
    }
    for (let j = 0; j < aiBms.length; j++) {
      const bm = aiBms[j];
      const ms = dateMs(bm.date, nowMs);
      const key = String(ms);
      if (localEntries[key] === undefined) {
        localEntries[key] = entryFromBookmark(bm, ms);
        if (ms > localNewest) {
          localNewest = ms;
        }
      }
    }
    const localEntry = localProgressEntry(book, nowMs);
    const localT = Math.max(recNum(localEntry, 't', 0), localNewest);

    if (g.kind === GET_MISSING) {
      /* seed upload — android parity: only hash-confirmed devices create files */
      const seed: Record<string, Object> = {
        'name': bookName,
        'hash': hash,
        't': localT,
        'progress': localEntry
      };
      if (Object.keys(localEntries).length > 0) {
        seed['bookmarks'] = localEntries;
      }
      if (await davPut(ctx, cfg, infoUrl, JSON.stringify(seed))) {
        entry.up++;
        entry.details.push('\u3010\u4E0A\u4F20\u3011' + book.title + ' \uFF08\u7B2C ' + (book.page + 1) + ' \u9875\uFF09');
      } else {
        netFails++;
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u4E0A\u4F20\u5931\u8D25');
      }
      continue;
    }

    /* merge against the server info */
    let info: Record<string, Object> = {};
    try {
      info = JSON.parse(g.body) as Record<string, Object>;
    } catch (e) {
      netFails++;
      entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u670D\u52A1\u5668\u6570\u636E\u65E0\u6CD5\u89E3\u6790');
      continue;
    }
    const remoteProgress = asRec(info['progress']);
    const remoteBms = asRec(info['bookmarks']);
    const remoteDk = asRec(info['dk']);

    /* dk down-application: a bookmark deleted on another device disappears here */
    const dkKeys = Object.keys(remoteDk);
    for (let j = 0; j < dkKeys.length; j++) {
      const dkKey = dkKeys[j];
      const dkTime = recNum(remoteDk, dkKey, 0);
      const created = Number(dkKey);
      if (!Number.isNaN(created) && (localByKey.has(dkKey) || aiByKey.has(dkKey)) && dkTime > created) {
        removedLocal.push(dkKey);
      }
    }

    /* bookmark union: local (minus remote deletions) + remote additions
     * (minus remote dk records and local deletion tombstones) */
    const union: Record<string, Object> = {};
    const localKeys = Object.keys(localEntries);
    let localCount = 0;
    for (let j = 0; j < localKeys.length; j++) {
      const k = localKeys[j];
      if (removedLocal.indexOf(k) >= 0) {
        continue;
      }
      union[k] = localEntries[k];
      localCount++;
    }
    const remoteKeys = Object.keys(remoteBms);
    for (let j = 0; j < remoteKeys.length; j++) {
      const k = remoteKeys[j];
      if (union[k] !== undefined) {
        continue;
      }
      const dkTime = recNum(remoteDk, k, 0);
      if (dkTime > 0 && dkTime > Number(k)) {
        continue;
      }
      const localDel = recNum(localDk, k, 0);
      if (localDel > 0 && localDel > Number(k)) {
        continue;
      }
      union[k] = remoteBms[k];
    }

    /* progress merge (policy dispatch; whole-entry winner like android) */
    const lp = recNum(localEntry, 'p', 0);
    const rp = recNum(remoteProgress, 'p', 0);
    const rt = recNum(remoteProgress, 't', 0);
    const localHas = book.page > 0 || book.lastRead.length > 0;
    const remoteHasProgress = Object.keys(remoteProgress).length > 0;
    let remoteWins = false;
    if (!localHas) {
      /* android mergeProgressEntry: no local entry yet → the remote one wins
       * under every policy (including 'local') */
      remoteWins = remoteHasProgress;
    } else if (policy === SYNC_POLICY_LOCAL) {
      remoteWins = false;
    } else if (policy === SYNC_POLICY_SERVER) {
      remoteWins = remoteHasProgress;
    } else if (policy === SYNC_POLICY_FARTHER) {
      remoteWins = remoteHasProgress && (rp > lp || (rp === lp && rt > localT));
    } else {
      remoteWins = remoteHasProgress && rt > localT;
    }
    let mergedProgress: Record<string, Object>;
    let conflicted = false;
    if (remoteWins) {
      mergedProgress = cloneRecord(remoteProgress);
      conflicted = localHas && policy !== SYNC_POLICY_SERVER;
    } else {
      /* local wins: keep unknown keys the remote entry may carry */
      mergedProgress = remoteHasProgress ? cloneRecord(remoteProgress) : ({} as Record<string, Object>);
      const leKeys = Object.keys(localEntry);
      for (let j = 0; j < leKeys.length; j++) {
        mergedProgress[leKeys[j]] = localEntry[leKeys[j]];
      }
    }

    /* dk publish: merge remote records with local tombstones (newer wins),
     * drop recreated (delMs <= key) and expired entries */
    const pubDk: Record<string, Object> = {};
    for (let j = 0; j < dkKeys.length; j++) {
      pubDk[dkKeys[j]] = remoteDk[dkKeys[j]];
    }
    const localDkKeys = Object.keys(localDk);
    for (let j = 0; j < localDkKeys.length; j++) {
      const k = localDkKeys[j];
      const v = recNum(localDk, k, 0);
      if (v > recNum(pubDk, k, 0)) {
        pubDk[k] = v;
      }
    }
    const pubKeys = Object.keys(pubDk);
    const keptPub: Record<string, Object> = {};
    for (let j = 0; j < pubKeys.length; j++) {
      const k = pubKeys[j];
      const v = recNum(pubDk, k, 0);
      const created = Number(k);
      if (Date.now() - v > DK_MAX_AGE_MS || (!Number.isNaN(created) && v <= created)) {
        continue;
      }
      keptPub[k] = pubDk[k];
    }

    /* publish only when the merged info semantically differs from the server
     * copy (comparison ignores the display timestamp) */
    const merged: Record<string, Object> = {
      'name': recStr(info, 'name', bookName),
      'hash': hash,
      'progress': mergedProgress
    };
    if (Object.keys(union).length > 0) {
      merged['bookmarks'] = union;
    }
    if (Object.keys(keptPub).length > 0) {
      merged['dk'] = keptPub;
    }
    const sameAsServer = canon(withoutT(merged)) === canon(withoutT(info));
    if (!sameAsServer) {
      merged['t'] = Date.now();
      if (await davPut(ctx, cfg, infoUrl, JSON.stringify(merged))) {
        entry.up++;
        if (conflicted) {
          const pageOut = recNum(mergedProgress, 'pg', book.page);
          entry.details.push('\u3010\u51B2\u7A81\u3011' + book.title + ' \u53CC\u65B9\u5747\u6709\u6539\u52A8\uFF0C\u6309' + cfg.conflict +
            '\uFF1A\u7B2C ' + (pageOut + 1) + ' \u9875');
        } else {
          entry.details.push('\u3010\u4E0A\u4F20\u3011' + book.title + ' \u5408\u5E76\u7ED3\u679C\u5DF2\u56DE\u5199');
        }
      } else {
        netFails++;
        entry.details.push('\u3010\u5931\u8D25\u3011' + book.title + ' \u56DE\u5199\u5931\u8D25');
      }
    }

    /* apply the merged state locally when it differs */
    let localChanged = false;
    if (remoteWins && adoptProgress(book, remoteProgress)) {
      await saveRecentBook(ctx, book);
      localChanged = true;
    }
    if (removedLocal.length > 0) {
      localChanged = true;
    }
    const unionKeys = Object.keys(union);
    /* 0.9.44: the bookmark-store diff ignores AI-note keys - they live in
     * the Notes store and are applied below */
    const bmUnionKeys: string[] = [];
    for (let j = 0; j < unionKeys.length; j++) {
      if (!noteKeys.has(unionKeys[j])) {
        bmUnionKeys.push(unionKeys[j]);
      }
    }
    let bmsDiffer = bmUnionKeys.length !== localCount;
    /* 0.9.44 fix: remote deletions arrive as dk records only - union drops
     * the key, so a union-based diff can never see them; without this the
     * deleted entry stayed in the local store as a ghost and kept being
     * re-published until the 30-day dk record expired */
    if (removedLocal.length > 0) {
      bmsDiffer = true;
    }
    if (!bmsDiffer) {
      for (let j = 0; j < bmUnionKeys.length; j++) {
        if (localByKey.get(bmUnionKeys[j]) === undefined) {
          bmsDiffer = true;
          break;
        }
      }
    }
    if (!bmsDiffer) {
      /* one-time migration: legacy isAiNote bookmarks move to the Notes
       * store so they surface in the AI notes panel */
      for (let j = 0; j < localBms.length; j++) {
        if (localBms[j].isAiNote === true) {
          bmsDiffer = true;
          break;
        }
      }
    }
    if (bmsDiffer) {
      const kept: Bookmark[] = [];
      const aiNotesNew: AiNote[] = [];
      for (let j = 0; j < allBookmarks.length; j++) {
        if (allBookmarks[j].path !== book.path) {
          kept.push(allBookmarks[j]);
        }
      }
      for (let j = 0; j < bmUnionKeys.length; j++) {
        const k = bmUnionKeys[j];
        const orig = localByKey.get(k);
        if (orig !== undefined) {
          if (orig.isAiNote === true) {
            aiNotesNew.push(toAiNote(orig, book));
          } else {
            kept.push(orig);
          }
        } else {
          const nb = bookmarkOf(k, asRec(union[k]), book.path);
          if (nb.isAiNote === true) {
            aiNotesNew.push(toAiNote(nb, book));
          } else {
            kept.push(nb);
          }
        }
      }
      await replaceBookmarks(ctx, kept);
      /* notes of this book = surviving local notes + imported/migrated
       * ones; note keys dropped from the union (remote dk) leave here */
      if (aiNotesNew.length > 0 || noteKeyList.length > 0) {
        const finalNotes: AiNote[] = [];
        for (let j = 0; j < noteKeyList.length; j++) {
          const k = noteKeyList[j];
          if (union[k] !== undefined) {
            const sn = aiByKey.get(k);
            if (sn !== undefined) {
              finalNotes.push(sn);
            }
          }
        }
        for (let j = 0; j < aiNotesNew.length; j++) {
          finalNotes.push(aiNotesNew[j]);
        }
        await replaceNotesForBook(ctx, book.path, finalNotes);
      }
      localChanged = true;
    }
    if (localChanged) {
      entry.details.push('\u3010\u5408\u5E76\u3011' + book.title + ' \u5DF2\u5E94\u7528\u5230\u672C\u5730\uFF08\u7B2C ' +
        (book.page + 1) + ' \u9875\uFF0C\u4E66\u7B7E ' + unionKeys.length + '\uFF09');
    } else if (sameAsServer) {
      entry.details.push('\u3010\u5DF2\u6700\u65B0\u3011' + book.title);
    }
  }

  /* pending association: server infos whose book is not on this shelf yet,
   * kept until the book file appears and is opened once (android pending
   * semantics). Needs the PROPFIND listing; silently skipped otherwise. */
  if (listing !== null && netFails === 0) {
    const pendPrefs = await getPendPrefs(ctx);
    const pendJson = await getStringPref(pendPrefs, KEY_PENDING, '{}');
    let pending: Record<string, Object> = {};
    try {
      pending = JSON.parse(pendJson) as Record<string, Object>;
    } catch (e) {
      pending = {};
    }
    for (let i = 0; i < listing.length; i++) {
      const n = listing[i];
      if (!n.endsWith('.json') || n.length < 37) {
        continue;
      }
      const h = n.substring(0, n.length - 5);
      if (mergedHashes[h] === true || bookByHash[h] !== undefined) {
        continue;
      }
      if (pending[h] === undefined) {
        const g = await davGet3(ctx, cfg, bUrl + '/' + n);
        if (g.kind === GET_OK) {
          const rec: PendRec = { body: g.body, t: Date.now() };
          pending[h] = JSON.stringify(rec);
        }
      }
    }
    const keptPend: Record<string, Object> = {};
    const pendKeys = Object.keys(pending);
    for (let i = 0; i < pendKeys.length; i++) {
      const h = pendKeys[i];
      const bookIdx = bookByHash[h];
      if (bookIdx === undefined) {
        let rec: PendRec | null = null;
        try {
          rec = JSON.parse(pending[h] as string) as PendRec;
        } catch (e) {
          rec = null;
        }
        if (rec !== null && Date.now() - rec.t <= DK_MAX_AGE_MS) {
          keptPend[h] = pending[h];
        }
        continue;
      }
      /* the book now exists locally: apply the stored info once */
      let inf: Record<string, Object> = {};
      try {
        const wrap = JSON.parse(pending[h] as string) as PendRec;
        inf = JSON.parse(wrap.body) as Record<string, Object>;
      } catch (e) {
        continue;
      }
      const book = books[bookIdx];
      const rp = asRec(inf['progress']);
      const tomb = asRec(tombstones[baseNameOf(book.path)]);
      if (Object.keys(rp).length > 0) {
        const rt0 = recNum(rp, 't', 0);
        const localHas = book.page > 0 || book.lastRead.length > 0;
        if (!localHas || rt0 > dateMs(book.lastRead, 0)) {
          if (adoptProgress(book, rp)) {
            await saveRecentBook(ctx, book);
            entry.details.push('\u3010\u5F85\u5173\u8054\u3011' + book.title + ' \u5DF2\u5173\u8054\u670D\u52A1\u5668\u8FDB\u5EA6\uFF08\u7B2C ' + (book.page + 1) + ' \u9875\uFF09');
          }
        }
      }
      const remoteBms = asRec(inf['bookmarks']);
      const remoteDk = asRec(inf['dk']);
      const addKeys = Object.keys(remoteBms);
      if (addKeys.length > 0) {
        const kept: Bookmark[] = [];
        for (let j = 0; j < allBookmarks.length; j++) {
          kept.push(allBookmarks[j]);
        }
        let added = 0;
        const pendNotes: AiNote[] = [];
        for (let j = 0; j < addKeys.length; j++) {
          const k = addKeys[j];
          const dkTime = recNum(remoteDk, k, 0);
          if (dkTime > 0 && dkTime > Number(k)) {
            continue;
          }
          const localDel = recNum(tomb, k, 0);
          if (localDel > 0 && localDel > Number(k)) {
            continue;
          }
          let exists = false;
          for (let j2 = 0; j2 < kept.length; j2++) {
            if (kept[j2].path === book.path && String(dateMs(kept[j2].date, -1)) === k) {
              exists = true;
              break;
            }
          }
          if (!exists) {
            const nb = bookmarkOf(k, asRec(remoteBms[k]), book.path);
            if (nb.isAiNote === true) {
              pendNotes.push(toAiNote(nb, book));
            } else {
              kept.push(nb);
              added++;
            }
          }
        }
        if (added > 0) {
          await replaceBookmarks(ctx, kept);
          entry.details.push('\u3010\u5F85\u5173\u8054\u3011' + book.title + ' \u5DF2\u5173\u8054\u670D\u52A1\u5668\u4E66\u7B7E ' + added + ' \u679A');
        }
        if (pendNotes.length > 0) {
          const pendExisting: AiNote[] = await getNotes(ctx, book.path);
          const pendDates: Record<string, boolean> = {};
          for (let j = 0; j < pendExisting.length; j++) {
            pendDates[pendExisting[j].date] = true;
          }
          let pendAdded = 0;
          for (let j = 0; j < pendNotes.length; j++) {
            if (pendDates[pendNotes[j].date] !== true) {
              pendExisting.push(pendNotes[j]);
              pendAdded++;
            }
          }
          if (pendAdded > 0) {
            await replaceNotesForBook(ctx, book.path, pendExisting);
            entry.details.push('\u3010\u5F85\u5173\u8054\u3011' + book.title + ' \u5DF2\u5173\u8054\u670D\u52A1\u5668AI\u7B14\u8BB0 ' + pendAdded + ' \u6761');
          }
        }
      }
    }
    await putStringPref(pendPrefs, KEY_PENDING, JSON.stringify(keptPend));
  }

  /* 0.9.40: server-list interop (app-NetworkSources.json, android parity) */
  try {
    await syncNetworkSources(ctx, cfg, entry);
  } catch (e) {
    entry.details.push('\u3010\u5931\u8D25\u3011\u670D\u52A1\u5668\u5217\u8868\u540C\u6B65\u5F02\u5E38');
  }

  /* 0.9.43: remaining global config faces (android syncThreeWayFile /
   * syncMergedObjectFile parity): app-State / app-CSS / app-Misc field-level
   * three-way, app-Stats max-merge, app-BookStates per-key merge. Failures
   * here are detail lines only - they never gate the pending-association
   * phase below. */
  await syncGlobalProfiles(ctx, cfg, entry);

  /* 0.9.41: recent/favorite lists (app-Recent.json / app-Favorite.json,
   * android syncMetaUnion parity — export-only on this side) */
  try {
    const recentArr: Object[] = [];
    const favArr: Object[] = [];
    for (let i = 0; i < books.length; i++) {
      const b = books[i];
      const t = dateMs(b.lastRead, 0);
      const meta: Record<string, Object> = {
        'name': baseNameOf(b.path),
        'path': b.path,
        'time': t
      };
      recentArr.push(meta);
      if (b.star === true) {
        const f: Record<string, Object> = { 'path': b.path, 'time': t };
        favArr.push(f);
      }
    }
    await syncMetaList(ctx, cfg, RECENT_FILE, JSON.stringify(recentArr), entry);
    await syncMetaList(ctx, cfg, FAVORITE_FILE, JSON.stringify(favArr), entry);
  } catch (e) {
    entry.details.push('\u3010\u5931\u8D25\u3011\u6700\u8FD1/\u6536\u85CF\u5217\u8868\u540C\u6B65\u5F02\u5E38');
  }

  await saveRecPref(ctx, KEY_LAST_HASHES, hashes);
  entry.ms = Date.now() - start;
  await appendSyncLog(ctx, entry);
  cfg.lastSyncTime = entry.time;
  cfg.lastUp = entry.up;
  cfg.lastLinked = entry.linked;
  cfg.lastMs = entry.ms;
  await saveSyncConfig(ctx, cfg);
  console.info('[Sync] pass done: linked=' + entry.linked + ' up=' + entry.up + ' ms=' + entry.ms);
  return entry;
}

/* ---------------- 0.9.41: recent/favorite lists (android syncMetaUnion) ----
 * app-Recent.json = SimpleMeta array {name, path, time}; app-Favorite.json =
 * {path, time}. Union keyed by the path string, the newer "time" wins;
 * deletions do NOT propagate (android tombstones are per-device). This side
 * is EXPORT-ONLY: android path values (e.g. internal-storage:/...) are
 * meaningless on HarmonyOS, so the merged list is written for the android
 * side and never imported into the harmony shelf. */
function metaUnion(l: string, r: string): string {
  try {
    const la = arrOf(JSON.parse(l) as Object);
    const ra = arrOf(JSON.parse(r) as Object);
    const map: Record<string, Record<string, Object>> = {};
    const collect = (arr: Object[] | undefined): void => {
      if (arr === undefined) {
        return;
      }
      for (let i = 0; i < arr.length; i++) {
        const it = arr[i];
        if (it === undefined || typeof it !== 'object' || Array.isArray(it)) {
          continue;
        }
        const rec = it as Record<string, Object>;
        const k = recStr(rec, 'path', '');
        if (k.length === 0) {
          continue;
        }
        const cur = map[k];
        if (cur === undefined || recNum(rec, 'time', 0) > recNum(cur, 'time', 0)) {
          map[k] = rec;
        }
      }
    };
    collect(la);
    collect(ra);
    const out: Object[] = [];
    const keys = Object.keys(map);
    for (let i = 0; i < keys.length; i++) {
      out.push(map[keys[i]]);
    }
    return JSON.stringify(out);
  } catch (e) {
    return r;
  }
}

async function syncMetaList(ctx: Context, cfg: SyncConfig, fileName: string,
  localText: string, entry: SyncLogEntry): Promise<void> {
  const url = globalUrlOf(cfg) + '/' + fileName;
  const g = await davGet3(ctx, cfg, url);
  if (g.kind === GET_ERROR) {
    return; /* android parity: an unreachable list is skipped, never overwritten */
  }
  const remoteText = g.kind === GET_OK ? g.body : '';
  const merged = metaUnion(localText, remoteText);
  if (g.kind === GET_OK && canonEqText(merged, g.body)) {
    return; /* converged */
  }
  if (await davPut(ctx, cfg, url, merged)) {
    entry.details.push('\u3010\u4E0A\u4F20\u3011' + fileName + ' \u5DF2\u66F4\u65B0');
  }
}

/* ---------------- AI profile sync (Android app-AI.json parity) ----
 * Merged, never whole-file, against a local base snapshot (android
 * syncMergedObjectFile semantics): GET → merge(local, remote, base) → PUT
 * when the merged result differs from the server copy → save base → import.
 * Keys never leave the device through OUR payloads; server keys are
 * preserved in the file and adopted locally (see AiVendors.ets). */
export async function syncAiProfiles(ctx: Context, c: SyncConfig): Promise<string> {
  try {
    const url = globalUrlOf(c) + '/app-AI.json';
    const g = await davGet3(ctx, c, url);
    if (g.kind === GET_ERROR) {
      return 'AI \u914D\u7F6E\u672C\u8F6E\u672A\u540C\u6B65\uFF08\u7F51\u7EDC\u4E0D\u53EF\u8FBE\uFF09';
    }
    const base = await loadBaseText(ctx, 'app-AI.json');
    const local = await exportAiPayload(ctx);
    const remoteText = g.kind === GET_OK ? g.body : '';
    const merged = mergeAiPayloads(local, remoteText, base);
    if (!(g.kind === GET_OK && canonEqText(merged, g.body))) {
      const ok = await davPut(ctx, c, url, merged);
      if (!ok) {
        return 'AI \u914D\u7F6E\u4E0A\u4F20\u5931\u8D25';
      }
    }
    await saveBaseText(ctx, 'app-AI.json', merged);
    await importAiPayload(ctx, merged);
    return 'AI \u914D\u7F6E\u5DF2\u540C\u6B65';
  } catch (e) {
    return 'AI \u914D\u7F6E\u540C\u6B65\u5931\u8D25\uFF1A' + String(e).substring(0, 40);
  }
}

/* ---------------- 0.9.40: server-list interop (app-NetworkSources.json) ----
 * Android ProfileStateIO.exportNetworkSources schema:
 *   { opds: [ "url,title" lines ], webdav: [ {url,title,startDir,login,password,trustAll} ],
 *     smb: [ {id,title,host,port,user,domain,share,keyPath,trustAll,startDir,password} ],
 *     sftp: [ ... + keyPass ], folders: [ paths ] }
 * Merge: opds/folders = string union (deletions do NOT propagate, android
 * parity); webdav = per-url three-way incl. the ghost rule; smb/sftp =
 * per-id three-way. Harmony ids are deterministic
 * sha256("type|host|port|extra")[:12] (android ids carry a nanoTime salt, so
 * cross-device dedupe by id was never possible there either). Import
 * reconciles the local store with the merged list (add/update/remove). */

const NS_FILE = 'app-NetworkSources.json';
const RECENT_FILE = 'app-Recent.json';
const FAVORITE_FILE = 'app-Favorite.json';

function arrOf(v: Object | undefined): Object[] | undefined {
  if (Array.isArray(v)) {
    return v as Object[];
  }
  return undefined;
}

function nsUnionStrings(la: Object[] | undefined, ra: Object[] | undefined): Object[] {
  const out: Object[] = [];
  const seen: Record<string, boolean> = {};
  const add = (arr: Object[] | undefined): void => {
    if (arr === undefined) {
      return;
    }
    for (let i = 0; i < arr.length; i++) {
      const it = arr[i];
      if (typeof it === 'string') {
        const s = it as string;
        if (s.length > 0 && seen[s] !== true) {
          seen[s] = true;
          out.push(s);
        }
      }
    }
  };
  add(la);
  add(ra);
  return out;
}

function nsItemMap(arr: Object[] | undefined, keyField: string): Record<string, Record<string, Object>> {
  const m: Record<string, Record<string, Object>> = {};
  if (arr === undefined) {
    return m;
  }
  for (let i = 0; i < arr.length; i++) {
    const it = arr[i];
    if (it === undefined || it === null || typeof it !== 'object' || Array.isArray(it)) {
      continue;
    }
    const rec = it as Record<string, Object>;
    let k = '';
    if (keyField === 'url') {
      k = recStr(rec, 'url', '').replace(/\/+$/, '');
      if (k.length > 0) {
        rec['url'] = k;
      }
    } else {
      k = recStr(rec, 'id', '');
    }
    if (k.length === 0) {
      continue;
    }
    m[k] = rec;
  }
  return m;
}

/** android mergeItemFields: a field the local side did not change since the
 * base adopts the remote value; a locally-changed field keeps its value. */
function nsMergeItemFields(l: Record<string, Object>, r: Record<string, Object>,
  b: Record<string, Object>): Record<string, Object> {
  const out: Record<string, Object> = {};
  const names: string[] = [];
  const seen: Record<string, boolean> = {};
  const collect = (m: Record<string, Object>): void => {
    const ks = Object.keys(m);
    for (let i = 0; i < ks.length; i++) {
      if (seen[ks[i]] !== true) {
        seen[ks[i]] = true;
        names.push(ks[i]);
      }
    }
  };
  collect(l);
  collect(r);
  collect(b);
  for (let i = 0; i < names.length; i++) {
    const k = names[i];
    const lh = l[k] !== undefined;
    const rh = r[k] !== undefined;
    const bh = b[k] !== undefined;
    const localChanged = lh !== bh || (lh && bh && String(l[k]) !== String(b[k]));
    const remoteChanged = rh !== bh || (rh && bh && String(r[k]) !== String(b[k]));
    if (!localChanged) {
      if (rh) {
        out[k] = r[k];
      } else if (lh) {
        out[k] = l[k];
      } else if (bh) {
        out[k] = b[k];
      }
    } else {
      if (lh) {
        out[k] = l[k];
      } else if (bh) {
        out[k] = b[k];
      } else if (rh) {
        out[k] = r[k];
      }
    }
  }
  return out;
}

/** android mergeWebDavItems shape, generalized to the key field: per-key
 * three-way with the ghost rule (locally absent + remote unchanged since
 * base → stays dropped). */
function nsMergeItems(la: Object[] | undefined, ra: Object[] | undefined,
  ba: Object[] | undefined, keyField: string): Object[] {
  const lm = nsItemMap(la, keyField);
  const rm = nsItemMap(ra, keyField);
  const bm = nsItemMap(ba, keyField);
  const keys: string[] = [];
  const seen: Record<string, boolean> = {};
  const collect = (m: Record<string, Record<string, Object>>): void => {
    const ks = Object.keys(m);
    for (let i = 0; i < ks.length; i++) {
      if (seen[ks[i]] !== true) {
        seen[ks[i]] = true;
        keys.push(ks[i]);
      }
    }
  };
  collect(lm);
  collect(rm);
  collect(bm);
  const out: Object[] = [];
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    const l = lm[k];
    const r = rm[k];
    const b = bm[k];
    let keep: Record<string, Object> | undefined;
    if (l === undefined) {
      if (b !== undefined && (r === undefined || canon(r) === canon(b))) {
        keep = undefined;
      } else {
        keep = r;
      }
    } else if (r === undefined || canon(r) === canon(l)) {
      keep = l;
    } else if (b === undefined || canon(r) === canon(b)) {
      keep = l;
    } else if (canon(l) === canon(b)) {
      keep = r;
    } else {
      keep = nsMergeItemFields(l, r, b);
    }
    if (keep !== undefined) {
      out.push(keep);
    }
  }
  return out;
}

function nsMerge(l: string, r: string, b: string): string {
  try {
    const lo = JSON.parse(l) as Record<string, Object>;
    const ro = JSON.parse(r) as Record<string, Object>;
    let bo: Record<string, Object> = {};
    if (b.length > 0) {
      try {
        bo = JSON.parse(b) as Record<string, Object>;
      } catch (e) {
        bo = {};
      }
    }
    const out: Record<string, Object> = {};
    out['opds'] = nsUnionStrings(arrOf(lo['opds']), arrOf(ro['opds']));
    out['webdav'] = nsMergeItems(arrOf(lo['webdav']), arrOf(ro['webdav']), arrOf(bo['webdav']), 'url');
    out['smb'] = nsMergeItems(arrOf(lo['smb']), arrOf(ro['smb']), arrOf(bo['smb']), 'id');
    out['sftp'] = nsMergeItems(arrOf(lo['sftp']), arrOf(ro['sftp']), arrOf(bo['sftp']), 'id');
    out['folders'] = nsUnionStrings(arrOf(lo['folders']), arrOf(ro['folders']));
    return JSON.stringify(out);
  } catch (e) {
    return r.length > 0 ? r : l;
  }
}

async function nsServerId(type: string, host: string, port: number, extra: string): Promise<string> {
  const hex = await sha256Hex(type + '|' + host + '|' + port + '|' + extra);
  return hex.substring(0, 12);
}

async function exportNetworkSources(ctx: Context): Promise<string> {
  const opds = await getOpdsServers(ctx);
  const dav = await getDavServers(ctx);
  const smb = await getSmbServers(ctx);
  const sftp = await getSftpServers(ctx);
  const folders = await getLibFolders(ctx);
  const opdsArr: Object[] = [];
  for (let i = 0; i < opds.length; i++) {
    if (opds[i].url.length > 0) {
      opdsArr.push(opds[i].url + ',' + opds[i].name);
    }
  }
  const davArr: Object[] = [];
  for (let i = 0; i < dav.length; i++) {
    const s = dav[i];
    const item: Record<string, Object> = {
      'url': s.url.replace(/\/+$/, ''),
      'title': s.name,
      'startDir': s.startPath,
      'login': s.user,
      'password': s.password,
      'trustAll': s.trusted
    };
    davArr.push(item);
  }
  const smbArr: Object[] = [];
  for (let i = 0; i < smb.length; i++) {
    const s = smb[i];
    const item: Record<string, Object> = {
      'id': await nsServerId('smb', s.host, s.port, s.share),
      'title': s.name,
      'host': s.host,
      'port': s.port,
      'user': s.user,
      'domain': s.domain,
      'share': s.share,
      'keyPath': '',
      'trustAll': false,
      'startDir': '',
      'password': s.password
    };
    smbArr.push(item);
  }
  const sftpArr: Object[] = [];
  for (let i = 0; i < sftp.length; i++) {
    const s = sftp[i];
    const item: Record<string, Object> = {
      'id': await nsServerId('sftp', s.host, s.port, ''),
      'title': s.name,
      'host': s.host,
      'port': s.port,
      'user': s.user,
      'domain': '',
      'share': '',
      'keyPath': s.keyPath,
      'trustAll': s.trustAll,
      'startDir': '',
      'password': s.password,
      'keyPass': s.keyPass
    };
    sftpArr.push(item);
  }
  const folderArr: Object[] = [];
  for (let i = 0; i < folders.length; i++) {
    if (folders[i].path.length > 0) {
      folderArr.push(folders[i].path);
    }
  }
  const root: Record<string, Object> = {
    'opds': opdsArr,
    'webdav': davArr,
    'smb': smbArr,
    'sftp': sftpArr,
    'folders': folderArr
  };
  return JSON.stringify(root);
}

async function importNetworkSources(ctx: Context, rootText: string): Promise<number> {
  const root = JSON.parse(rootText) as Record<string, Object>;
  let changes = 0;
  /* opds: union only (android unionStrings never removes) */
  const opds = await getOpdsServers(ctx);
  const seenUrl: Record<string, boolean> = {};
  for (let i = 0; i < opds.length; i++) {
    seenUrl[opds[i].url] = true;
  }
  const lines = arrOf(root['opds']);
  if (lines !== undefined) {
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (typeof line !== 'string') {
        continue;
      }
      const s = line as string;
      const c = s.indexOf(',');
      const url = c >= 0 ? s.substring(0, c) : s;
      if (url.length === 0 || seenUrl[url] === true) {
        continue;
      }
      const name = c >= 0 && s.length > c + 1 ? s.substring(c + 1) : url;
      const srv: OpdsServer = { url: url, name: name.length > 0 ? name : url };
      await saveOpdsServer(ctx, srv, url);
      seenUrl[url] = true;
      changes++;
    }
  }
  /* webdav: reconcile with the merged list (add / update / remove) */
  const dav = await getDavServers(ctx);
  const davMap = nsItemMap(arrOf(root['webdav']), 'url');
  const localDav: Record<string, string> = {};
  for (let i = 0; i < dav.length; i++) {
    localDav[dav[i].url.replace(/\/+$/, '')] = dav[i].url;
  }
  const davKeys = Object.keys(davMap);
  const davKeysSeen: Record<string, boolean> = {};
  for (let i = 0; i < davKeys.length; i++) {
    const k = davKeys[i];
    davKeysSeen[k] = true;
    const it = davMap[k];
    if (localDav[k] !== undefined && localDav[k] !== k) {
      await removeDavServer(ctx, localDav[k]); /* URL-normalized re-key */
    }
    const srv: DavServer = {
      url: k,
      name: recStr(it, 'title', k),
      user: recStr(it, 'login', ''),
      password: recStr(it, 'password', ''),
      trusted: it['trustAll'] === true,
      startPath: recStr(it, 'startDir', '')
    };
    await saveDavServer(ctx, srv, k);
  }
  for (let i = 0; i < dav.length; i++) {
    const k = dav[i].url.replace(/\/+$/, '');
    if (davKeysSeen[k] !== true) {
      await removeDavServer(ctx, dav[i].url);
      changes++;
    }
  }
  /* smb / sftp: reconcile by host (+ port); a merged entry whose host matches
   * an existing local server updates it instead of adding a twin */
  const smb = await getSmbServers(ctx);
  const smbItems = arrOf(root['smb']);
  const smbMap: Record<string, Record<string, Object>> = {};
  if (smbItems !== undefined) {
    for (let i = 0; i < smbItems.length; i++) {
      const it = smbItems[i];
      if (it === undefined || typeof it !== 'object' || Array.isArray(it)) {
        continue;
      }
      const rec = it as Record<string, Object>;
      const host = recStr(rec, 'host', '');
      if (host.length === 0) {
        continue;
      }
      const k = host + '|' + recNum(rec, 'port', 0);
      if (smbMap[k] === undefined) {
        smbMap[k] = rec;
      }
    }
  }
  const smbKeys = Object.keys(smbMap);
  const smbKeysSeen: Record<string, boolean> = {};
  for (let i = 0; i < smbKeys.length; i++) {
    const k = smbKeys[i];
    smbKeysSeen[k] = true;
    const it = smbMap[k];
    const host = recStr(it, 'host', '');
    const srv: SmbServer = {
      host: host,
      port: recNum(it, 'port', 445),
      share: recStr(it, 'share', ''),
      domain: recStr(it, 'domain', ''),
      name: recStr(it, 'title', host),
      user: recStr(it, 'user', ''),
      password: recStr(it, 'password', '')
    };
    await saveSmbServer(ctx, srv, host);
  }
  for (let i = 0; i < smb.length; i++) {
    const k = smb[i].host + '|' + smb[i].port;
    if (smbKeysSeen[k] !== true) {
      await removeSmbServer(ctx, smb[i].host);
      changes++;
    }
  }
  const sftp = await getSftpServers(ctx);
  const sftpItems = arrOf(root['sftp']);
  const sftpMap: Record<string, Record<string, Object>> = {};
  if (sftpItems !== undefined) {
    for (let i = 0; i < sftpItems.length; i++) {
      const it = sftpItems[i];
      if (it === undefined || typeof it !== 'object' || Array.isArray(it)) {
        continue;
      }
      const rec = it as Record<string, Object>;
      const host = recStr(rec, 'host', '');
      if (host.length === 0) {
        continue;
      }
      const k = host + '|' + recNum(rec, 'port', 22);
      if (sftpMap[k] === undefined) {
        sftpMap[k] = rec;
      }
    }
  }
  const sftpKeys = Object.keys(sftpMap);
  const sftpKeysSeen: Record<string, boolean> = {};
  for (let i = 0; i < sftpKeys.length; i++) {
    const k = sftpKeys[i];
    sftpKeysSeen[k] = true;
    const it = sftpMap[k];
    const host = recStr(it, 'host', '');
    let finger = '';
    for (let j = 0; j < sftp.length; j++) {
      if (sftp[j].host === host) {
        finger = sftp[j].fingerprint;
        break;
      }
    }
    const srv: SftpServer = {
      host: host,
      port: recNum(it, 'port', 22),
      name: recStr(it, 'title', host),
      user: recStr(it, 'user', ''),
      password: recStr(it, 'password', ''),
      keyPath: recStr(it, 'keyPath', ''),
      keyPass: recStr(it, 'keyPass', ''),
      trustAll: it['trustAll'] === true,
      fingerprint: finger
    };
    await saveSftpServer(ctx, srv, host);
  }
  for (let i = 0; i < sftp.length; i++) {
    const k = sftp[i].host + '|' + sftp[i].port;
    if (sftpKeysSeen[k] !== true) {
      await removeSftpServer(ctx, sftp[i].host);
      changes++;
    }
  }
  /* folders: union by path (android parity, no deletion propagation) */
  const lf = await getLibFolders(ctx);
  const seenPath: Record<string, boolean> = {};
  for (let i = 0; i < lf.length; i++) {
    seenPath[lf[i].path] = true;
  }
  const folderLines = arrOf(root['folders']);
  const lfOut: LibFolder[] = [];
  for (let i = 0; i < lf.length; i++) {
    lfOut.push(lf[i]);
  }
  if (folderLines !== undefined) {
    for (let i = 0; i < folderLines.length; i++) {
      const line = folderLines[i];
      if (typeof line !== 'string') {
        continue;
      }
      const p = line as string;
      if (p.length === 0 || seenPath[p] === true) {
        continue;
      }
      const f: LibFolder = { name: baseNameOf(p), path: p };
      lfOut.push(f);
      seenPath[p] = true;
      changes++;
    }
  }
  if (lfOut.length !== lf.length) {
    await saveLibFolders(ctx, lfOut);
  }
  return changes;
}

async function syncNetworkSources(ctx: Context, cfg: SyncConfig, entry: SyncLogEntry): Promise<void> {
  const url = globalUrlOf(cfg) + '/' + NS_FILE;
  const g = await davGet3(ctx, cfg, url);
  if (g.kind === GET_ERROR) {
    entry.details.push('\u3010\u5931\u8D25\u3011\u670D\u52A1\u5668\u5217\u8868\u672C\u8F6E\u672A\u540C\u6B65\uFF08\u7F51\u7EDC\u4E0D\u53EF\u8FBE\uFF09');
    return;
  }
  const localText = await exportNetworkSources(ctx);
  const base = await loadBaseText(ctx, NS_FILE);
  const remoteText = g.kind === GET_OK ? g.body : '';
  const merged = nsMerge(localText, remoteText, base);
  if (!(g.kind === GET_OK && canonEqText(merged, g.body))) {
    const ok = await davPut(ctx, cfg, url, merged);
    if (!ok) {
      entry.details.push('\u3010\u5931\u8D25\u3011\u670D\u52A1\u5668\u5217\u8868\u56DE\u5199\u5931\u8D25');
      return;
    }
  }
  await saveBaseText(ctx, NS_FILE, merged);
  const changes = await importNetworkSources(ctx, merged);
  if (changes > 0) {
    entry.details.push('\u3010\u540C\u6B65\u3011\u670D\u52A1\u5668\u5217\u8868\u5DF2\u66F4\u65B0 ' + changes + ' \u9879');
  }
}


/* ================ 0.9.43: field-level global config faces (android parity) ====
 * Android WebDavSyncer.syncThreeWayFile / syncMergedObjectFile parity for the
 * five remaining global faces:
 *   app-State.json / app-CSS.json / app-Misc.json - field-level three-way
 *     merge against the base snapshot: single-side change wins (including
 *     deletions), both-changed keeps local, arrays union, nested objects
 *     recurse; device-bound fields never leave the device (stripped from the
 *     upload, restored from the local copy); SYNC_CONFIG_FIELDS stay local on
 *     app-State.json first adoption; APPSP identity keys keep local inside
 *     app-Misc.json (android APPSP_IDENTITY_FIELDS parity).
 *   app-Stats.json - numeric max + per-key bucket max (android mergeStats,
 *     two-way, no base).
 *   app-BookStates.json - per-key three-way with deletion semantics (android
 *     mergeStatesMaxWins); Harmony keeps no override store, so this side is a
 *     merge conduit for the android devices.
 * Harmony mirrors each server file in prefs ('librera_syncmirror') and
 * overlays only the settings it implements, under android field names;
 * android-only fields pass through untouched so the file never loses data in
 * either direction. A mapped setting is published while it differs from the
 * Harmony factory default OR the mirror already carries it (an untouched
 * device can never publish defaults over another device's personalization -
 * android isDefaultConfig parity, per field; and a field once synced keeps
 * living in the file instead of rippling a deletion). */

const GS_STATE = 'app-State.json';
const GS_CSS = 'app-CSS.json';
const GS_MISC = 'app-Misc.json';
const GS_STATS = 'app-Stats.json';
const GS_BOOKSTATES = 'app-BookStates.json';
const GS_PLAYLISTS = 'app-Playlists.json';

/* android STATE_DEVICE_FIELDS / CSS_DEVICE_FIELDS verbatim (WebDavSyncer) */
const STATE_DEVICE_FIELDS: string[] = [
  'displayPath', 'installationDate', 'statusBarTextSizeAdv', 'statusBarTextSizeEasy',
  'progressLineHeight', 'tapzoneSize', 'coverBigSize', 'isReverseKeys', 'pageQuality',
  'isCutRTL', 'isRTLByDefault', 'selectingByLetters', 'fileToDelete', 'myAutoCompleteDb',
  'bgImageDayPath', 'bgImageNightPath', 'proxyEnable', 'proxyServer', 'proxyPort',
  'proxyUser', 'proxyPassword', 'selectedText', 'searchQuery', 'isAutoScroll',
  'hashCode', 'webdavLastSyncTime', 'webdavLastSyncInfo',
  'aiConfigs', 'aiConfigName', 'aiBaseUrl', 'aiModel', 'aiProtocol', 'aiMaxTokens',
  'aiThinking', 'allWebDavLinks', 'allSmbLinks', 'allSftpLinks'
];
const CSS_DEVICE_FIELDS: string[] = [
  'searchPathsJson', 'cachePath', 'downlodsPath', 'ttsSpeakPath', 'backupPath',
  'dictPath', 'fontFolder', 'dirLastPath', 'pathSAF', 'mp3BookPathJson',
  'syncDropboxPath', 'syncGdrivePath', 'syncOneDrivePath', 'searchPathsHiddenJson',
  'hashCode'
];
const APPS_IDENTITY_FIELDS: string[] = ['rootPath1', 'currentProfile', 'syncRootID',
  'lastBookPath', 'lastBookPage'];
const SYNC_CONFIG_FIELDS: string[] = ['webdavSyncEnabled', 'webdavSyncServer',
  'webdavSyncRemoteDir', 'webdavSyncIntervalMin'];

const PREF_MIRROR = 'librera_syncmirror';
let mirrorPref: preferences.Preferences | undefined = undefined;

async function getMirrorPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (mirrorPref === undefined) {
    mirrorPref = await preferences.getPreferences(ctx, PREF_MIRROR);
  }
  return mirrorPref;
}

async function loadMirrorText(ctx: Context, key: string): Promise<string> {
  const prefs = await getMirrorPrefs(ctx);
  return await getStringPref(prefs, key, '');
}

async function saveMirrorText(ctx: Context, key: string, text: string): Promise<void> {
  const prefs = await getMirrorPrefs(ctx);
  await putStringPref(prefs, key, text);
}

function isPlainObj(v: Object | undefined | null): boolean {
  return v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v);
}

/** android eq(): value compare for merged fields; a missing key never
 *  compares equal to a present one. */
function jsEq(a: Object | undefined, b: Object | undefined): boolean {
  if (a === undefined && b === undefined) {
    return true;
  }
  if (a === undefined || b === undefined) {
    return false;
  }
  return JSON.stringify(a) === JSON.stringify(b);
}

/** android isEmptyValue(): absent / blank / "[]" / "{}" strings / empty
 *  containers count as "nothing configured". */
function isEmptyValue(v: Object | undefined): boolean {
  if (v === undefined || v === null) {
    return true;
  }
  if (typeof v === 'string') {
    const sv = (v as string).trim();
    return sv.length === 0 || sv === '[]' || sv === '{}';
  }
  if (Array.isArray(v)) {
    return (v as Object[]).length === 0;
  }
  if (typeof v === 'object') {
    return Object.keys(v as Record<string, Object>).length === 0;
  }
  return false;
}

/** android unionArrays(): local order first, remote-only appended, element
 *  types preserved; the scalar side of a both-changed conflict joins as a
 *  value instead of being dropped. */
function unionArrays(lv: Object | undefined, rv: Object | undefined): Object[] {
  const out: Object[] = [];
  const seen: Record<string, boolean> = {};
  const addVal = (arr: Object | undefined): void => {
    if (arr === undefined) {
      return;
    }
    if (!Array.isArray(arr)) {
      if (!isEmptyValue(arr)) {
        const k = JSON.stringify(arr);
        if (seen[k] !== true) {
          seen[k] = true;
          out.push(arr);
        }
      }
      return;
    }
    const a = arr as Object[];
    for (let i = 0; i < a.length; i++) {
      const v = a[i];
      const k = JSON.stringify(v);
      if (seen[k] !== true) {
        seen[k] = true;
        out.push(v);
      }
    }
  };
  addVal(lv);
  addVal(rv);
  return out;
}

function keysUnion3(a: Record<string, Object>, b: Record<string, Object>,
  c?: Record<string, Object>): string[] {
  const keys: string[] = [];
  const seen: Record<string, boolean> = {};
  const collect = (m: Record<string, Object>): void => {
    const ks = Object.keys(m);
    for (let i = 0; i < ks.length; i++) {
      if (seen[ks[i]] !== true) {
        seen[ks[i]] = true;
        keys.push(ks[i]);
      }
    }
  };
  collect(a);
  collect(b);
  if (c !== undefined) {
    collect(c);
  }
  return keys;
}

/** android firstSyncMerge(): per field the side that HAS a value beats the
 *  empty side, both set - the server copy wins, lists union, nested objects
 *  recurse; keepLocal keys always keep the local value. */
function firstSyncMergeObj(local: Record<string, Object>, remote: Record<string, Object>,
  keepLocal: string[]): Record<string, Object> {
  const out: Record<string, Object> = {};
  const keys = keysUnion3(local, remote);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    if (keepLocal.indexOf(k) >= 0) {
      const lv0 = local[k];
      if (lv0 !== undefined) {
        out[k] = lv0;
      }
      continue;
    }
    const lb = local[k] !== undefined;
    const rb = remote[k] !== undefined;
    const lv = local[k];
    const rv = remote[k];
    const lEmpty = isEmptyValue(lv);
    const rEmpty = isEmptyValue(rv);
    if (lb && rb && isPlainObj(lv) && isPlainObj(rv)) {
      out[k] = firstSyncMergeObj(lv as Record<string, Object>, rv as Record<string, Object>,
        keepLocal);
      continue;
    }
    if (lEmpty && rEmpty) {
      continue;
    }
    if (lEmpty) {
      out[k] = rv;
    } else if (rEmpty) {
      out[k] = lv;
    } else if (Array.isArray(lv) || Array.isArray(rv)) {
      out[k] = unionArrays(lv, rv);
    } else {
      out[k] = rv;
    }
  }
  return out;
}

/** android merge3(): field-level three-way. Single-side change wins
 *  (including deletions); both-changed keeps local (arrays union); nested
 *  objects recurse; keepLocal keys always keep the local value; a key the
 *  remote dropped while the local still carries it stays (old-version guard). */
function merge3Obj(base: Record<string, Object>, local: Record<string, Object>,
  remote: Record<string, Object>, keepLocal: string[]): Record<string, Object> {
  const out: Record<string, Object> = {};
  const keys = keysUnion3(local, remote, base);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    if (keepLocal.indexOf(k) >= 0) {
      const lv0 = local[k];
      if (lv0 !== undefined) {
        out[k] = lv0;
      }
      continue;
    }
    const lb = local[k] !== undefined;
    const rb = remote[k] !== undefined;
    const bb = base[k] !== undefined;
    const lv = local[k];
    const rv = remote[k];
    const bv = base[k];
    const localChanged = lb !== bb || (lb && !jsEq(lv, bv));
    const remoteChanged = rb !== bb || (rb && !jsEq(rv, bv));
    if (bb && lb && rb && isPlainObj(bv) && isPlainObj(lv) && isPlainObj(rv)) {
      out[k] = merge3Obj(bv as Record<string, Object>, lv as Record<string, Object>,
        rv as Record<string, Object>, keepLocal);
      continue;
    }
    if (!bb && lb && rb && isPlainObj(lv) && isPlainObj(rv)) {
      /* added on both devices independently (no base entry): merge
       * structurally instead of dropping the whole remote object */
      out[k] = firstSyncMergeObj(lv as Record<string, Object>, rv as Record<string, Object>,
        keepLocal);
      continue;
    }
    if (localChanged && remoteChanged) {
      if (Array.isArray(lv) || Array.isArray(rv)) {
        out[k] = unionArrays(lv, rv);
      } else if (lb) {
        out[k] = lv;
      }
      /* lb == false: deleted locally AND changed remotely - stay deleted */
    } else if (remoteChanged) {
      if (rb) {
        out[k] = rv;
      } else if (lb) {
        out[k] = lv;
      }
    } else if (lb) {
      out[k] = lv;
    }
  }
  return out;
}

function withoutFields(obj: Record<string, Object>, fields: string[]): Record<string, Object> {
  const out: Record<string, Object> = {};
  const ks = Object.keys(obj);
  for (let i = 0; i < ks.length; i++) {
    if (fields.indexOf(ks[i]) < 0) {
      out[ks[i]] = obj[ks[i]];
    }
  }
  return out;
}

function keepLocalFields(target: Record<string, Object>, local: Record<string, Object>,
  fields: string[]): void {
  for (let i = 0; i < fields.length; i++) {
    const v = local[fields[i]];
    if (v !== undefined) {
      target[fields[i]] = v;
    }
  }
}

function parseRec(text: string): Record<string, Object> {
  try {
    const o = JSON.parse(text) as Object;
    if (isPlainObj(o)) {
      return o as Record<string, Object>;
    }
  } catch (e) {
    /* fall through to the empty record */
  }
  const e2: Record<string, Object> = {};
  return e2;
}

/** android webdavSyncPolicy token -> Harmony conflict label. */
function policyLabelOf(token: string): string {
  if (token === SYNC_POLICY_LOCAL) {
    return SYNC_CONFLICTS[1];
  }
  if (token === SYNC_POLICY_SERVER) {
    return SYNC_CONFLICTS[2];
  }
  if (token === SYNC_POLICY_FARTHER) {
    return SYNC_CONFLICTS[3];
  }
  return SYNC_CONFLICTS[0];
}

/** Generic field-level three-way sync for one global config file (android
 *  syncThreeWayFile parity): GET -> merge(mirror+overlay, remote, base) ->
 *  save mirror / PUT the merged copy / save base / import. A transport
 *  failure touches nothing (android parity); an empty server file reseeds
 *  from local. Device-bound fields are stripped from the upload and restored
 *  from the local copy. */
async function syncThreeWayGlobal(ctx: Context, cfg: SyncConfig, fileName: string,
  localFull: Record<string, Object>, deviceFields: string[], stateFile: boolean,
  entry: SyncLogEntry, applyImport: (merged: Record<string, Object>) => Promise<boolean>): Promise<void> {
  const url = globalUrlOf(cfg) + '/' + fileName;
  const g = await davGet3(ctx, cfg, url);
  if (g.kind === GET_ERROR) {
    entry.details.push('\u3010\u8DF3\u8FC7\u3011' + fileName +
      ' \u7F51\u7EDC\u4E0D\u53EF\u8FBE\uFF0C\u672C\u8F6E\u672A\u540C\u6B65');
    return;
  }
  const baseText = await loadBaseText(ctx, fileName);
  const mirrorText = await loadMirrorText(ctx, fileName);
  const remoteText = g.kind === GET_OK ? g.body : '';
  const remoteObj = parseRec(remoteText);
  const baseObj = parseRec(baseText);
  const localCmp = withoutFields(localFull, deviceFields);
  const remoteCmp = withoutFields(remoteObj, deviceFields);
  const baseCmp = withoutFields(baseObj, deviceFields);
  let mergedFull: Record<string, Object>;
  if (baseText.length === 0) {
    if (Object.keys(remoteObj).length === 0) {
      mergedFull = localFull; /* 404/empty server file: seed from local */
    } else {
      const mc = firstSyncMergeObj(localCmp, remoteCmp, APPS_IDENTITY_FIELDS);
      mergedFull = mc;
      keepLocalFields(mergedFull, localFull, deviceFields);
      if (stateFile) {
        /* the sync setup typed on this device survives the first adoption */
        keepLocalFields(mergedFull, localFull, SYNC_CONFIG_FIELDS);
      }
    }
  } else if (Object.keys(remoteObj).length === 0) {
    mergedFull = localFull; /* server file gone: reseed (android parity) */
  } else {
    const mc = merge3Obj(baseCmp, localCmp, remoteCmp, APPS_IDENTITY_FIELDS);
    mergedFull = mc;
    keepLocalFields(mergedFull, localFull, deviceFields);
  }
  const mergedText = JSON.stringify(mergedFull);
  if (!canonEqText(mergedText, mirrorText.length > 0 ? mirrorText : '{}')) {
    await saveMirrorText(ctx, fileName, mergedText);
  }
  const mergedCmp = withoutFields(mergedFull, deviceFields);
  let putFailed = false;
  if (!(g.kind === GET_OK && canonEqText(JSON.stringify(mergedCmp), remoteText))) {
    const ok = await davPut(ctx, cfg, url, JSON.stringify(mergedCmp));
    if (ok) {
      entry.details.push('\u3010\u4E0A\u4F20\u3011' + fileName + ' \u5DF2\u66F4\u65B0');
    } else {
      /* android parity: the base snapshot stays old so the merge retries
       * next round; the mirror still carries the merged copy */
      putFailed = true;
      entry.details.push('\u3010\u5931\u8D25\u3011' + fileName + ' \u56DE\u5199\u5931\u8D25');
    }
  }
  if (!putFailed) {
    await saveBaseText(ctx, fileName, mergedText);
  }
  const changed = await applyImport(mergedFull);
  if (changed) {
    entry.details.push('\u3010\u540C\u6B65\u3011' + fileName + ' \u5DF2\u5E94\u7528\u5230\u672C\u5730');
  }
}

/* ---- app-State.json: overlay + import (android AppState field names) ---- */

async function buildStateFull(ctx: Context, cfg: SyncConfig,
  st: ReaderSettings): Promise<Record<string, Object>> {
  const mirrorText = await loadMirrorText(ctx, GS_STATE);
  const full = parseRec(mirrorText);
  /* publish while personalized (value != factory default) or already carried
   * by the mirror (keeps the key alive once synced) */
  if (full['appTheme'] !== undefined || st.theme !== 0) {
    full['appTheme'] = st.theme;
  }
  if (full['tapPositionTop'] !== undefined || st.tabPositionTop !== false) {
    full['tapPositionTop'] = st.tabPositionTop;
  }
  if (full['fullScreenMode'] !== undefined || st.fullscreen !== false) {
    full['fullScreenMode'] = st.fullscreen ? 1 : 0;
  }
  if (full['isOpenLastBook'] !== undefined || st.openLastBook !== false) {
    full['isOpenLastBook'] = st.openLastBook;
  }
  if (full['isAlwaysOpenOnPage1'] !== undefined || st.alwaysPage1 !== false) {
    full['isAlwaysOpenOnPage1'] = st.alwaysPage1;
  }
  if (full['appLang'] !== undefined || st.appLang.length > 0) {
    full['appLang'] = st.appLang;
  }
  if (full['isUseVolumeKeys'] !== undefined || st.volumeKeyAction !== 1) {
    full['isUseVolumeKeys'] = st.volumeKeyAction !== 0;
  }
  if (full['orientation'] !== undefined || st.screenOrientation !== 0) {
    /* ActivityInfo: UNSPECIFIED=-1, LANDSCAPE=0, PORTRAIT=1 */
    full['orientation'] = st.screenOrientation === 1 ? 1 : (st.screenOrientation === 2 ? 0 : -1);
  }
  if (full['ttsSpeed'] !== undefined || st.ttsRate !== 1.0) {
    full['ttsSpeed'] = st.ttsRate;
  }
  if (full['ttsPitch'] !== undefined || st.ttsPitch !== 1.0) {
    full['ttsPitch'] = st.ttsPitch;
  }
  if (full['aiPdfTranslateMode'] !== undefined || st.pdfTransMode !== 0) {
    full['aiPdfTranslateMode'] = st.pdfTransMode;
  }
  if (full['remoteOnlineFirst'] !== undefined || st.remoteOnlineFirst !== true) {
    full['remoteOnlineFirst'] = st.remoteOnlineFirst;
  }
  if (full['remoteCacheMaxMB'] !== undefined || st.remoteCacheMaxMB !== 500) {
    full['remoteCacheMaxMB'] = st.remoteCacheMaxMB;
  }
  if (full['remotePrefetchWifiOnly'] !== undefined || st.remotePrefetchWifiOnly !== true) {
    full['remotePrefetchWifiOnly'] = st.remotePrefetchWifiOnly;
  }
  if (full['remoteWholeBookThresholdMB'] !== undefined || st.remoteWholeBookThresholdMB !== 20) {
    full['remoteWholeBookThresholdMB'] = st.remoteWholeBookThresholdMB;
  }
  if (full['remoteRetryCount'] !== undefined || st.remoteRetryCount !== 3) {
    full['remoteRetryCount'] = st.remoteRetryCount;
  }
  if (full['remoteRetryIntervalMs'] !== undefined || st.remoteRetryIntervalMs !== 100) {
    full['remoteRetryIntervalMs'] = st.remoteRetryIntervalMs;
  }
  if (full['remoteWindowBeforePct'] !== undefined || st.remoteWindowBeforePct !== 20) {
    full['remoteWindowBeforePct'] = st.remoteWindowBeforePct;
  }
  if (full['remoteWindowAfterPct'] !== undefined || st.remoteWindowAfterPct !== 30) {
    full['remoteWindowAfterPct'] = st.remoteWindowAfterPct;
  }
  /* webdavSyncPolicy roams (android: a reading preference, deliberately NOT
   * in SYNC_CONFIG_FIELDS); harmony default = SYNC_CONFLICTS[0] = 'newer' */
  if (full['webdavSyncPolicy'] !== undefined || cfg.conflict !== SYNC_CONFLICTS[0]) {
    full['webdavSyncPolicy'] = policyOf(cfg);
  }
  return full;
}

async function applyStateImport(ctx: Context, cfg: SyncConfig,
  merged: Record<string, Object>): Promise<boolean> {
  const st = await loadSettings(ctx);
  let changed = false;
  const appTheme = merged['appTheme'];
  if (typeof appTheme === 'number') {
    const v = appTheme as number;
    if (v >= 0 && v <= 3 && v !== st.theme) {
      st.theme = v;
      changed = true;
    }
  }
  const tpt = merged['tapPositionTop'];
  if (typeof tpt === 'boolean' && (tpt as boolean) !== st.tabPositionTop) {
    st.tabPositionTop = tpt as boolean;
    changed = true;
  }
  const fsm = merged['fullScreenMode'];
  if (typeof fsm === 'number' && ((fsm as number) !== 0) !== st.fullscreen) {
    st.fullscreen = (fsm as number) !== 0;
    changed = true;
  }
  const olb = merged['isOpenLastBook'];
  if (typeof olb === 'boolean' && (olb as boolean) !== st.openLastBook) {
    st.openLastBook = olb as boolean;
    changed = true;
  }
  const ap1 = merged['isAlwaysOpenOnPage1'];
  if (typeof ap1 === 'boolean' && (ap1 as boolean) !== st.alwaysPage1) {
    st.alwaysPage1 = ap1 as boolean;
    changed = true;
  }
  const lang = merged['appLang'];
  if (typeof lang === 'string') {
    /* 'my' is the android "follow system" sentinel; unknown codes ignored */
    const lv = (lang as string) === 'my' ? '' : lang as string;
    if ((lv.length === 0 || lv === 'zh' || lv === 'en') && lv !== st.appLang) {
      st.appLang = lv;
      changed = true;
    }
  }
  const uvk = merged['isUseVolumeKeys'];
  if (typeof uvk === 'boolean') {
    const want = (uvk as boolean) ? (st.volumeKeyAction === 0 ? 1 : st.volumeKeyAction) : 0;
    if (want !== st.volumeKeyAction) {
      st.volumeKeyAction = want;
      changed = true;
    }
  }
  const ori = merged['orientation'];
  if (typeof ori === 'number') {
    const ov = ori as number;
    const want = ov === -1 ? 0 : (ov === 1 ? 1 : (ov === 0 ? 2 : -1));
    if (want >= 0 && want !== st.screenOrientation) {
      st.screenOrientation = want;
      changed = true;
    }
  }
  const tsp = merged['ttsSpeed'];
  if (typeof tsp === 'number' && (tsp as number) > 0 && (tsp as number) !== st.ttsRate) {
    st.ttsRate = tsp as number;
    changed = true;
  }
  const tpi = merged['ttsPitch'];
  if (typeof tpi === 'number' && (tpi as number) > 0 && (tpi as number) !== st.ttsPitch) {
    st.ttsPitch = tpi as number;
    changed = true;
  }
  const ptm = merged['aiPdfTranslateMode'];
  if (typeof ptm === 'number') {
    const pv = ptm as number;
    if (pv >= 0 && pv <= 3 && pv !== st.pdfTransMode) {
      st.pdfTransMode = pv;
      changed = true;
    }
  }
  const rof = merged['remoteOnlineFirst'];
  if (typeof rof === 'boolean' && (rof as boolean) !== st.remoteOnlineFirst) {
    st.remoteOnlineFirst = rof as boolean;
    changed = true;
  }
  const rcm = merged['remoteCacheMaxMB'];
  if (typeof rcm === 'number' && (rcm as number) > 0 && (rcm as number) !== st.remoteCacheMaxMB) {
    st.remoteCacheMaxMB = rcm as number;
    changed = true;
  }
  const rpw = merged['remotePrefetchWifiOnly'];
  if (typeof rpw === 'boolean' && (rpw as boolean) !== st.remotePrefetchWifiOnly) {
    st.remotePrefetchWifiOnly = rpw as boolean;
    changed = true;
  }
  const rwt = merged['remoteWholeBookThresholdMB'];
  if (typeof rwt === 'number' && (rwt as number) >= 0 &&
    (rwt as number) !== st.remoteWholeBookThresholdMB) {
    st.remoteWholeBookThresholdMB = rwt as number;
    changed = true;
  }
  const rrc = merged['remoteRetryCount'];
  if (typeof rrc === 'number' && (rrc as number) >= 0 && (rrc as number) !== st.remoteRetryCount) {
    st.remoteRetryCount = rrc as number;
    changed = true;
  }
  const rri = merged['remoteRetryIntervalMs'];
  if (typeof rri === 'number' && (rri as number) >= 0 &&
    (rri as number) !== st.remoteRetryIntervalMs) {
    st.remoteRetryIntervalMs = rri as number;
    changed = true;
  }
  const rwb = merged['remoteWindowBeforePct'];
  if (typeof rwb === 'number' && (rwb as number) >= 0 &&
    (rwb as number) !== st.remoteWindowBeforePct) {
    st.remoteWindowBeforePct = rwb as number;
    changed = true;
  }
  const rwa = merged['remoteWindowAfterPct'];
  if (typeof rwa === 'number' && (rwa as number) >= 0 &&
    (rwa as number) !== st.remoteWindowAfterPct) {
    st.remoteWindowAfterPct = rwa as number;
    changed = true;
  }
  const pol = merged['webdavSyncPolicy'];
  if (typeof pol === 'string') {
    const label = policyLabelOf(pol as string);
    if (label !== cfg.conflict) {
      cfg.conflict = label; /* persisted by runSync's saveSyncConfig */
      changed = true;
    }
  }
  if (changed) {
    await saveSettings(ctx, st);
  }
  return changed;
}

/* ---- app-CSS.json: overlay + import (android BookCSS field names) ---- */

async function buildCssFull(ctx: Context,
  st: ReaderSettings): Promise<Record<string, Object>> {
  const mirrorText = await loadMirrorText(ctx, GS_CSS);
  const full = parseRec(mirrorText);
  /* BookCSS.fontSizeSp / appFontScale are the only BookCSS fields with a
   * same-scale Harmony counterpart (lineHeight12/textIndent/margins use
   * android-internal em() units and are NOT mapped) */
  if (full['fontSizeSp'] !== undefined || st.fontSize !== 16) {
    full['fontSizeSp'] = st.fontSize;
  }
  if (full['appFontScale'] !== undefined || st.appFontScale !== 1.0) {
    full['appFontScale'] = st.appFontScale;
  }
  return full;
}

async function applyCssImport(ctx: Context,
  merged: Record<string, Object>): Promise<boolean> {
  const st = await loadSettings(ctx);
  let changed = false;
  const fs = merged['fontSizeSp'];
  if (typeof fs === 'number' && (fs as number) > 0 && (fs as number) !== st.fontSize) {
    st.fontSize = fs as number;
    changed = true;
  }
  const afs = merged['appFontScale'];
  if (typeof afs === 'number' && (afs as number) > 0 && (afs as number) !== st.appFontScale) {
    st.appFontScale = afs as number;
    changed = true;
  }
  if (changed) {
    await saveSettings(ctx, st);
  }
  return changed;
}

/* ---- app-Misc.json: AppSP reading-mode section overlay + import ---- */

async function buildMiscFull(ctx: Context,
  st: ReaderSettings): Promise<Record<string, Object>> {
  const mirrorText = await loadMirrorText(ctx, GS_MISC);
  const full = parseRec(mirrorText);
  const spObj = full['AppSP'];
  const touched = st.scrollMode !== 0 || st.pageMode !== 0;
  if (isPlainObj(spObj) || touched) {
    let sp: Record<string, Object>;
    if (isPlainObj(spObj)) {
      sp = spObj as Record<string, Object>;
    } else {
      const e0: Record<string, Object> = {};
      sp = e0;
    }
    if (sp['readingMode'] !== undefined || touched) {
      /* android READING_MODE_SCROLL=1 / BOOK=2 / MUSICIAN=3 */
      if (st.scrollMode === 1) {
        sp['readingMode'] = 1;
        sp['isDouble'] = false;
      } else if (st.pageMode === 2) {
        sp['readingMode'] = 3;
        sp['isDouble'] = false;
      } else {
        sp['readingMode'] = 2;
        sp['isDouble'] = st.pageMode === 1;
      }
      full['AppSP'] = sp;
    }
  }
  return full;
}

async function applyMiscImport(ctx: Context,
  merged: Record<string, Object>): Promise<boolean> {
  const spObj = merged['AppSP'];
  if (!isPlainObj(spObj)) {
    return false;
  }
  const sp = spObj as Record<string, Object>;
  const rm = sp['readingMode'];
  if (typeof rm !== 'number') {
    return false;
  }
  const dbl = sp['isDouble'] === true;
  const st = await loadSettings(ctx);
  let changed = false;
  const mode = rm as number;
  if (mode === 1) {
    if (st.scrollMode !== 1) {
      st.scrollMode = 1;
      changed = true;
    }
  } else if (mode === 2) {
    if (st.scrollMode !== 0) {
      st.scrollMode = 0;
      changed = true;
    }
    const want = dbl ? 1 : 0;
    if (st.pageMode !== want) {
      st.pageMode = want;
      changed = true;
    }
  } else if (mode === 3) {
    if (st.scrollMode !== 0) {
      st.scrollMode = 0;
      changed = true;
    }
    if (st.pageMode !== 2) {
      st.pageMode = 2;
      changed = true;
    }
  }
  if (changed) {
    await saveSettings(ctx, st);
  }
  return changed;
}

/* ---- app-Stats.json (android mergeStats parity, two-way max) ---- */

async function syncStatsHarmony(ctx: Context, cfg: SyncConfig, entry: SyncLogEntry): Promise<void> {
  const url = globalUrlOf(cfg) + '/' + GS_STATS;
  const g = await davGet3(ctx, cfg, url);
  if (g.kind === GET_ERROR) {
    return; /* android parity: unreachable stats are skipped, never overwritten */
  }
  const localText = await exportStatsSyncPayload(ctx);
  const remoteText = g.kind === GET_OK ? g.body : '';
  if (remoteText.trim().length === 0) {
    await davPut(ctx, cfg, url, localText); /* seed */
    return;
  }
  const remoteObj = parseRec(remoteText);
  await importStatsSyncPayload(ctx, remoteObj); /* max-apply into the store */
  const mergedText = await exportStatsSyncPayload(ctx);
  if (!canonEqText(mergedText, remoteText)) {
    const ok = await davPut(ctx, cfg, url, mergedText);
    if (ok) {
      entry.details.push('\u3010\u540C\u6B65\u3011\u9605\u8BFB\u7EDF\u8BA1\u5DF2\u5408\u5E76');
    }
  }
}

/* ---- app-BookStates.json (android mergeStatesMaxWins parity) ---- */

/** android mergeStatesMaxWins: per-key three-way over int states with
 *  deletion semantics (a key removed on one side and unchanged remotely
 *  stays removed); both-changed keeps local; no base - the further-along
 *  value wins. */
function mergeStatesMaxWinsHarmony(local: Record<string, Object>,
  remote: Record<string, Object>, base?: Record<string, Object>): Record<string, Object> {
  if (base === undefined) {
    const out0: Record<string, Object> = {};
    const ks = Object.keys(local);
    for (let i = 0; i < ks.length; i++) {
      out0[ks[i]] = local[ks[i]];
    }
    const rks = Object.keys(remote);
    for (let i = 0; i < rks.length; i++) {
      const k = rks[i];
      const r = remote[k];
      if (typeof r !== 'number' || (r as number) < 0) {
        continue;
      }
      const l = out0[k];
      if (l === undefined || (typeof l === 'number' && (r as number) > (l as number))) {
        out0[k] = r;
      }
    }
    return out0;
  }
  const out: Record<string, Object> = {};
  const keys = keysUnion3(local, remote, base);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i];
    const lb = local[k] !== undefined;
    const rb = remote[k] !== undefined;
    const bb = base[k] !== undefined;
    const lv = lb ? local[k] as number : -1;
    const rv = rb ? remote[k] as number : -1;
    const bv = bb ? base[k] as number : -1;
    const localChanged = lb !== bb || (lb && lv !== bv);
    const remoteChanged = rb !== bb || (rb && rv !== bv);
    let keep = -1; /* -1 = omit the key (deletion) */
    if (!remoteChanged) {
      keep = lb ? lv : -1; /* local edit / deletion wins */
    } else if (!localChanged) {
      keep = rb ? rv : -1; /* adopt the remote edit / deletion */
    } else {
      keep = lb ? lv : (rb ? rv : -1); /* both changed: local wins */
    }
    if (keep >= 0) {
      out[k] = keep;
    }
  }
  return out;
}

async function syncBookStatesHarmony(ctx: Context, cfg: SyncConfig,
  entry: SyncLogEntry): Promise<void> {
  const url = globalUrlOf(cfg) + '/' + GS_BOOKSTATES;
  const g = await davGet3(ctx, cfg, url);
  if (g.kind === GET_ERROR) {
    return;
  }
  const mirrorText = await loadMirrorText(ctx, GS_BOOKSTATES);
  const localObj = mirrorText.length > 0 ? parseRec(mirrorText) : ({} as Record<string, Object>);
  const remoteText = g.kind === GET_OK ? g.body : '';
  if (remoteText.trim().length === 0) {
    if (Object.keys(localObj).length > 0) {
      await davPut(ctx, cfg, url, JSON.stringify(localObj)); /* seed */
    }
    return;
  }
  const remoteObj = parseRec(remoteText);
  const baseText = await loadBaseText(ctx, GS_BOOKSTATES);
  let baseObj: Record<string, Object> | undefined = undefined;
  if (baseText.length > 0) {
    baseObj = parseRec(baseText);
  }
  const merged = mergeStatesMaxWinsHarmony(localObj, remoteObj, baseObj);
  const mergedText = JSON.stringify(merged);
  if (!canonEqText(mergedText, mirrorText.length > 0 ? mirrorText : '{}')) {
    await saveMirrorText(ctx, GS_BOOKSTATES, mergedText);
  }
  let bsPutFailed = false;
  if (!canonEqText(mergedText, remoteText)) {
    const ok = await davPut(ctx, cfg, url, mergedText);
    if (!ok) {
      bsPutFailed = true; /* base stays old, merge retries next round */
    }
  }
  if (!bsPutFailed) {
    await saveBaseText(ctx, GS_BOOKSTATES, mergedText);
  }
}

/* ---- orchestrator ---- */

/** All five global config faces in one pass (android order: state, css,
 *  misc, stats, book states; AI + network sources + lists have their own
 *  dedicated merges elsewhere in runSync). */
/* ---------------- 0.9.44: AI notes -> per-book payload (android
 * AppBookmark isAiNote/aiAnswer parity) ---------------- */

function toAiNote(bm: Bookmark, book: RecentBook): AiNote {
  const note: AiNote = {
    path: book.path,
    title: book.title.length > 0 ? book.title : baseNameOf(book.path),
    page: bm.page,
    date: bm.date,
    kind: bm.label,
    text: bm.aiAnswer !== undefined ? bm.aiAnswer : ''
  };
  return note;
}

/* ---------------- 0.9.44: app-Playlists.json (android Playlists.java
 * parity, global/ layout) ----------------
 * Android keeps each playlist as <name>.playlist text lines under the
 * device profile dir but does not sync them; harmony publishes them under
 * the android global/ layout so harmony devices share one server copy and
 * a future android reader can adopt the same file. Android's virtual lists
 * (.recent/.favorites/.folder/.tags/.currentFolder) never participate. */

function isVirtualPlaylistName(name: string): boolean {
  return name.startsWith('.');
}

async function buildPlaylistsFull(ctx: Context): Promise<Record<string, Object>> {
  const full: Record<string, Object> = {};
  const metas: PlaylistMeta[] = getAllPlaylists(ctx);
  for (let i = 0; i < metas.length; i++) {
    const name = metas[i].name;
    if (isVirtualPlaylistName(name)) {
      continue;
    }
    full[name] = getPlaylistItems(ctx, name);
  }
  return full;
}

async function applyPlaylistsImport(ctx: Context, merged: Record<string, Object>): Promise<boolean> {
  let changed = false;
  const metas: PlaylistMeta[] = getAllPlaylists(ctx);
  for (let i = 0; i < metas.length; i++) {
    const name = metas[i].name;
    if (isVirtualPlaylistName(name)) {
      continue;
    }
    if (merged[name] === undefined) {
      deletePlaylist(ctx, name);
      changed = true;
    }
  }
  const names = Object.keys(merged);
  for (let i = 0; i < names.length; i++) {
    const name = names[i];
    if (isVirtualPlaylistName(name)) {
      continue;
    }
    const want = merged[name];
    if (!Array.isArray(want)) {
      continue;
    }
    const wantArr = want as Object[];
    const wantItems: string[] = [];
    for (let j = 0; j < wantArr.length; j++) {
      wantItems.push(String(wantArr[j]));
    }
    const cur: string[] = getPlaylistItems(ctx, name);
    if (JSON.stringify(cur) !== JSON.stringify(wantItems)) {
      updatePlaylist(ctx, name, wantItems);
      changed = true;
    }
  }
  return changed;
}

async function syncPlaylistsHarmony(ctx: Context, cfg: SyncConfig, entry: SyncLogEntry): Promise<void> {
  try {
    await syncThreeWayGlobal(ctx, cfg, GS_PLAYLISTS, await buildPlaylistsFull(ctx), [], false, entry,
      async (m: Record<string, Object>): Promise<boolean> => applyPlaylistsImport(ctx, m));
  } catch (e) {
    entry.details.push('\u3010\u5931\u8D25\u3011\u64AD\u653E\u5217\u8868\u540C\u6B65\u5F02\u5E38');
  }
}

async function syncGlobalProfiles(ctx: Context, cfg: SyncConfig, entry: SyncLogEntry): Promise<void> {
  try {
    const st = await loadSettings(ctx);
    await syncThreeWayGlobal(ctx, cfg, GS_STATE, await buildStateFull(ctx, cfg, st),
      STATE_DEVICE_FIELDS, true, entry,
      async (m: Record<string, Object>): Promise<boolean> => applyStateImport(ctx, cfg, m));
    await syncThreeWayGlobal(ctx, cfg, GS_CSS, await buildCssFull(ctx, st),
      CSS_DEVICE_FIELDS, false, entry,
      async (m: Record<string, Object>): Promise<boolean> => applyCssImport(ctx, m));
    await syncThreeWayGlobal(ctx, cfg, GS_MISC, await buildMiscFull(ctx, st),
      [], false, entry,
      async (m: Record<string, Object>): Promise<boolean> => applyMiscImport(ctx, m));
    await syncStatsHarmony(ctx, cfg, entry);
    await syncBookStatesHarmony(ctx, cfg, entry);
    await syncPlaylistsHarmony(ctx, cfg, entry);
  } catch (e) {
    entry.details.push('\u3010\u5931\u8D25\u3011\u5168\u5C40\u914D\u7F6E\u540C\u6B65\u5F02\u5E38');
  }
}