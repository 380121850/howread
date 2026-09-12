/**
 * Round 8: Online cache reading (port of Android com.foobnix.remote).
 *
 *remote://<type>/<serverId>/<encodedFileUrl> unified URI scheme; random-access
 * sources per protocol — WebDAV (HTTP Range), SMB (libsmb2 via native
 * smbOpenAsync/smbReadAtAsync) and SFTP (libssh2 via native
 * sftpConnectAsync/sftpReadAtAsync); two-level block cache (memory LRU + disk
 * data.bin/blocks.bin/meta.json); RemoteSession block assembly with P2
 * prefetch and P3 whole-book filler; format tiering / DRM probes (MOBI head +
 * EPUB encryption.xml) / whole-book fetch (Android RemoteBookOpener) and the
 * remote books store (Android RemoteScanner) live here too.
 */
import http from '@ohos.net.http';
import fs from '@ohos.file.fs';
import util from '@ohos.util';
import cryptoFramework from '@ohos.security.cryptoFramework';
import preferences from '@ohos.data.preferences';
import {
  smbOpenAsync, smbReadAtAsync, smbCloseAsync, smbListAsync,
  sftpConnectAsync, sftpOpenAsync, sftpReadAtAsync, sftpListAsync, sftpHomeAsync, sftpCloseAsync
} from 'libmupdf_napi.so';
import { DavServer, getDavServers, SmbServer, getSmbServers, SftpServer, getSftpServers } from './Servers';
import { ReaderSettings, loadSettings } from './Settings';

const REMOTE_PREF_NAME = 'librera_remote';
const REMOTE_BOOKS_KEY = 'books';

export const REMOTE_PREFIX = 'remote://';

/* ---- URI scheme ---- */

export interface RemoteUriParts {
  type: string;
  serverId: string;
  fileUrl: string;
}

export function buildRemoteUri(type: string, serverId: string, fileUrl: string): string {
  return REMOTE_PREFIX + type + '/' + serverId + '/' + encodeURIComponent(fileUrl);
}

export function parseRemoteUri(uri: string): RemoteUriParts | null {
  if (!uri.startsWith(REMOTE_PREFIX)) {
    return null;
  }
  const rest: string = uri.substring(REMOTE_PREFIX.length);
  const slash1: number = rest.indexOf('/');
  if (slash1 < 0) {
    return null;
  }
  const slash2: number = rest.indexOf('/', slash1 + 1);
  if (slash2 < 0) {
    return null;
  }
  try {
    return {
      type: rest.substring(0, slash1),
      serverId: rest.substring(slash1 + 1, slash2),
      fileUrl: decodeURIComponent(rest.substring(slash2 + 1))
    };
  } catch (e) {
    return null;
  }
}

/** Resolve the WebDAV server credentials stored under the uri's serverId. */
export async function resolveWebDavServer(ctx: Context, serverId: string): Promise<DavServer | null> {
  const servers: DavServer[] = await getDavServers(ctx);
  for (let i = 0; i < servers.length; i++) {
    const id: string = await serverIdOf(servers[i].url);
    if (id === serverId) {
      return servers[i];
    }
  }
  return null;
}

/** Canonical host:port identity of an SMB server (serverId key). */
export function smbHostPort(host: string, port: number): string {
  return host + ':' + (port > 0 ? port : 445);
}

/** Canonical host:port identity of an SFTP server (serverId key). */
export function sftpHostPort(host: string, port: number): string {
  return host + ':' + (port > 0 ? port : 22);
}

/** Resolve the SMB server stored under the uri's serverId (key = host:port). */
export async function resolveSmbServer(ctx: Context, serverId: string): Promise<SmbServer | null> {
  const servers: SmbServer[] = await getSmbServers(ctx);
  for (let i = 0; i < servers.length; i++) {
    const id: string = await serverIdOf(smbHostPort(servers[i].host, servers[i].port));
    if (id === serverId) {
      return servers[i];
    }
  }
  return null;
}

/** Resolve the SFTP server stored under the uri's serverId (key = host:port). */
export async function resolveSftpServer(ctx: Context, serverId: string): Promise<SftpServer | null> {
  const servers: SftpServer[] = await getSftpServers(ctx);
  for (let i = 0; i < servers.length; i++) {
    const id: string = await serverIdOf(sftpHostPort(servers[i].host, servers[i].port));
    if (id === serverId) {
      return servers[i];
    }
  }
  return null;
}

export async function serverIdOf(serverUrl: string): Promise<string> {
  const hex: string = await sha256Hex(serverUrl);
  return hex.substring(0, 12);
}

export async function sha256Hex(input: string): Promise<string> {
  const md = cryptoFramework.createMd('SHA256');
  const enc = new util.TextEncoder();
  await md.update({ data: enc.encode(input) });
  const blob = await md.digest();
  const arr: Uint8Array = new Uint8Array(blob.data);
  let hex = '';
  for (let i = 0; i < arr.length; i++) {
    hex += arr[i].toString(16).padStart(2, '0');
  }
  return hex;
}

/* ---- Format tiers (Android RemoteBook.isDirectOpen / isHeavyFormat) ---- */

const DIRECT_OPEN_EXTS: string[] = ['pdf', 'epub', 'cbz', 'xps', 'oxps'];
const SIMPLE_FETCH_EXTS: string[] = ['txt', 'html', 'htm', 'fb2', 'rtf'];
/** heavy formats: whole-book download only (online parse cost too high) */
const HEAVY_EXTS: string[] = ['mobi', 'azw', 'azw3', 'prc', 'djvu', 'cbr', 'doc', 'docx'];
/** page-image formats use 1MB blocks (Android RemoteBookSession.isPageFormat) */
const PAGE_FORMAT_EXTS: string[] = ['pdf', 'cbz', 'xps', 'oxps', 'djvu'];

export function fileExtOf(name: string): string {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) {
    return '';
  }
  return name.substring(dot + 1).toLowerCase();
}

export function isDirectOpenExt(ext: string): boolean {
  return DIRECT_OPEN_EXTS.indexOf(ext) >= 0;
}

export function isSimpleFetchExt(ext: string): boolean {
  return SIMPLE_FETCH_EXTS.indexOf(ext) >= 0;
}

export function isHeavyExt(ext: string): boolean {
  return HEAVY_EXTS.indexOf(ext) >= 0;
}

export function isPageFormatExt(ext: string): boolean {
  return PAGE_FORMAT_EXTS.indexOf(ext) >= 0;
}

/* ---- cache settings snapshot (Android AppState.remote*) ---- */

export interface RemoteCacheSettings {
  onlineFirst: boolean;
  cacheMaxMB: number;
  prefetchWifiOnly: boolean;
  wholeBookThresholdMB: number;
  wholeBookOnMetered: boolean;
  retryCount: number;
  retryIntervalMs: number;
  cacheExpireDays: number;
}

export async function loadRemoteCacheSettings(ctx: Context): Promise<RemoteCacheSettings> {
  try {
    const s: ReaderSettings = await loadSettings(ctx);
    return {
      onlineFirst: s.remoteOnlineFirst,
      cacheMaxMB: s.remoteCacheMaxMB > 0 ? s.remoteCacheMaxMB : 500,
      prefetchWifiOnly: s.remotePrefetchWifiOnly,
      wholeBookThresholdMB: s.remoteWholeBookThresholdMB,
      wholeBookOnMetered: s.remoteWholeBookOnMetered,
      retryCount: s.remoteRetryCount,
      retryIntervalMs: s.remoteRetryIntervalMs,
      cacheExpireDays: s.remoteCacheExpireDays
    };
  } catch (e) {
    return {
      onlineFirst: true,
      cacheMaxMB: 500,
      prefetchWifiOnly: true,
      wholeBookThresholdMB: 20,
      wholeBookOnMetered: false,
      retryCount: 3,
      retryIntervalMs: 1000,
      cacheExpireDays: 30
    };
  }
}

/* ---- Random-access sources (Android RemoteDataSource) ---- */

export interface RemoteProbe {
  size: number;
  versionTag: string;
  supportsRange: boolean;
}

/** A protocol-agnostic random-access file source. */
export interface RemoteFileSource {
  probe(): Promise<RemoteProbe | null>;
  readAt(offset: number, len: number): Promise<ArrayBuffer>;
  /** release the underlying connection (no-op for WebDAV) */
  close(): Promise<void>;
}

function respHeader(header: Object, name: string): string {
  try {
    const h = header as Record<string, string>;
    const lower = name.toLowerCase();
    for (const k of Object.keys(h)) {
      if (k.toLowerCase() === lower) {
        return String(h[k]);
      }
    }
  } catch (e) { /* ignore */ }
  return '';
}

export class WebDavFileSource implements RemoteFileSource {
  private fileUrl: string;
  private user: string;
  private password: string;
  private retryCount: number;
  private retryIntervalMs: number;

  constructor(fileUrl: string, user: string, password: string, retryCount: number, retryIntervalMs: number) {
    this.fileUrl = fileUrl;
    this.user = user;
    this.password = password;
    this.retryCount = retryCount;
    this.retryIntervalMs = retryIntervalMs;
  }

  private authHeader(): string {
    if (this.user.length === 0 && this.password.length === 0) {
      return '';
    }
    const raw: string = this.user + ':' + this.password;
    const arr: number[] = [];
    for (let i = 0; i < raw.length; i++) {
      arr.push(raw.charCodeAt(i) & 0xff);
    }
    return 'Basic ' + new util.Base64Helper().encodeToStringSync(new Uint8Array(arr));
  }

  private sleep(ms: number): Promise<void> {
    return new Promise<void>((resolve: () => void) => {
      setTimeout(() => {
        resolve();
      }, ms);
    });
  }

  private async getRange(offset: number, len: number): Promise<ArrayBuffer> {
    const hdr: Record<string, string> = {
      'Range': `bytes=${offset}-${offset + len - 1}`
    };
    const auth: string = this.authHeader();
    if (auth.length > 0) {
      hdr['Authorization'] = auth;
    }
    const req = http.createHttp();
    try {
      const resp = await req.request(this.fileUrl, {
        method: http.RequestMethod.GET,
        header: hdr,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 15000,
        readTimeout: 30000
      });
      if (resp.responseCode === 206 || (resp.responseCode === 200 && offset === 0)) {
        return resp.result as ArrayBuffer;
      }
      throw new Error(`HTTP ${resp.responseCode}`);
    } finally {
      req.destroy();
    }
  }

  /** Probe size / versionTag / Range support (Android open(): Range bytes=0-0). */
  async probe(): Promise<RemoteProbe | null> {
    const hdr: Record<string, string> = { 'Range': 'bytes=0-0' };
    const auth: string = this.authHeader();
    if (auth.length > 0) {
      hdr['Authorization'] = auth;
    }
    const req = http.createHttp();
    try {
      const resp = await req.request(this.fileUrl, {
        method: http.RequestMethod.GET,
        header: hdr,
        connectTimeout: 15000,
        readTimeout: 30000
      });
      const code: number = resp.responseCode;
      if (code !== 206 && code !== 200) {
        return null;
      }
      let size = 0;
      let supportsRange = false;
      if (code === 206) {
        supportsRange = true;
        /* Content-Range: bytes 0-0/<total> */
        const cr: string = respHeader(resp.header, 'Content-Range');
        const slash: number = cr.lastIndexOf('/');
        if (slash >= 0 && cr.substring(slash + 1) !== '*') {
          size = parseInt(cr.substring(slash + 1), 10);
        }
      }
      if (size <= 0) {
        const cl: string = respHeader(resp.header, 'Content-Length');
        if (cl.length > 0) {
          size = parseInt(cl, 10);
        }
      }
      if (size <= 0) {
        return null; /* streaming needs a known size; caller falls back to download */
      }
      let versionTag: string = respHeader(resp.header, 'ETag');
      if (versionTag.length === 0) {
        const lm: string = respHeader(resp.header, 'Last-Modified');
        versionTag = lm.length > 0 ? lm : `size-${size}`;
      }
      return { size: size, versionTag: versionTag, supportsRange: supportsRange };
    } catch (e) {
      return null;
    } finally {
      req.destroy();
    }
  }

  /** Random read with retry + exponential backoff (Android RemoteRetry). */
  async readAt(offset: number, len: number): Promise<ArrayBuffer> {
    let lastErr: Error | undefined = undefined;
    const attempts: number = Math.max(1, this.retryCount);
    for (let attempt = 0; attempt < attempts; attempt++) {
      try {
        return await this.getRange(offset, len);
      } catch (e) {
        lastErr = e as Error;
        if (attempt + 1 < attempts && this.retryIntervalMs > 0) {
          await this.sleep(this.retryIntervalMs * Math.pow(2, attempt));
        }
      }
    }
    throw lastErr !== undefined ? lastErr : new Error('readAt failed');
  }

  async close(): Promise<void> {
  }
}

/* ---- SMB random-access source (Android SmbDataSource via libsmb2) ---- */

export class SmbFileSource implements RemoteFileSource {
  private server: string;
  private share: string;
  private path: string;
  private user: string;
  private domain: string;
  private password: string;
  private handle: number = 0; /* 0 = not open */
  private size: number = 0;

  constructor(server: string, share: string, path: string, user: string, domain: string, password: string) {
    this.server = server;
    this.share = share;
    this.path = path;
    this.user = user;
    this.domain = domain;
    this.password = password;
  }

  /** Open the file + stat (SMB needs no Range probe; random reads are native). */
  async probe(): Promise<RemoteProbe | null> {
    try {
      await this.close();
      const res: string = await smbOpenAsync(this.server, this.share, this.path, this.user, this.domain, this.password);
      const obj = JSON.parse(res) as Record<string, number>;
      this.handle = obj.handle;
      this.size = obj.size;
      if (this.size <= 0) {
        return null;
      }
      return { size: this.size, versionTag: `size-${this.size}-${obj.mtime}`, supportsRange: true };
    } catch (e) {
      this.handle = 0;
      throw e as Error; /* auth/network classification lives in the error code */
    }
  }

  async readAt(offset: number, len: number): Promise<ArrayBuffer> {
    if (this.handle === 0) {
      const p: RemoteProbe | null = await this.probe();
      if (p === null) {
        throw new Error('SMB_ERROR: cannot reopen file');
      }
    }
    try {
      return await smbReadAtAsync(this.handle, offset, len);
    } catch (e) {
      /* native already retried once on a fresh connection; last resort: full reopen */
      const p: RemoteProbe | null = await this.probe();
      if (p === null) {
        throw e as Error;
      }
      return await smbReadAtAsync(this.handle, offset, len);
    }
  }

  async close(): Promise<void> {
    if (this.handle !== 0) {
      const h: number = this.handle;
      this.handle = 0;
      try {
        await smbCloseAsync(h);
      } catch (e) { /* ignore */ }
    }
  }
}

/* ---- SFTP random-access source (Android SftpDataSource via libssh2) ---- */

export class SftpFileSource implements RemoteFileSource {
  private host: string;
  private port: number;
  private user: string;
  private password: string;
  private keyPath: string;
  private keyPass: string;
  private trustAll: boolean;
  private expectFp: string;
  /** TOFU: SHA-256 host key fingerprint from the most recent connect. */
  fingerprint: string = '';
  private handle: number = 0; /* 0 = not connected */
  private size: number = 0;

  constructor(host: string, port: number, user: string, password: string, keyPath: string,
    keyPass: string, trustAll: boolean, expectFp: string) {
    this.host = host;
    this.port = port > 0 ? port : 22;
    this.user = user;
    this.password = password;
    this.keyPath = keyPath;
    this.keyPass = keyPass;
    this.trustAll = trustAll;
    this.expectFp = expectFp;
  }

  /** Connect + open the file + stat. Updates `fingerprint` for TOFU storage. */
  async probe(): Promise<RemoteProbe | null> {
    await this.close();
    const res: string = await sftpConnectAsync(this.host, this.port, this.user, this.password,
      this.keyPath, this.keyPass, this.trustAll, this.expectFp);
    const conn = JSON.parse(res) as Record<string, ESObject>;
    this.handle = conn['handle'] as number;
    this.fingerprint = conn['fingerprint'] as string;
    const st: string = await sftpOpenAsync(this.handle, this.pathOf());
    const meta = JSON.parse(st) as Record<string, number>;
    this.size = meta.size;
    if (this.size <= 0) {
      return null;
    }
    return { size: this.size, versionTag: `size-${this.size}-${meta.mtime}`, supportsRange: true };
  }

  /** The file path is carried in the uri (serverId/host identity aside);
   * the caller re-creates the source per book, so openPath is set externally. */
  openPath: string = '';

  private pathOf(): string {
    return this.openPath.length > 0 ? this.openPath : '/';
  }

  async readAt(offset: number, len: number): Promise<ArrayBuffer> {
    if (this.handle === 0) {
      const p: RemoteProbe | null = await this.probe();
      if (p === null) {
        throw new Error('SFTP_ERROR: cannot reopen file');
      }
    }
    try {
      return await sftpReadAtAsync(this.handle, offset, len);
    } catch (e) {
      const p: RemoteProbe | null = await this.probe();
      if (p === null) {
        throw e as Error;
      }
      return await sftpReadAtAsync(this.handle, offset, len);
    }
  }

  async close(): Promise<void> {
    if (this.handle !== 0) {
      const h: number = this.handle;
      this.handle = 0;
      try {
        await sftpCloseAsync(h);
      } catch (e) { /* ignore */ }
    }
  }
}

/* ---- Directory listing over native clients (Android SmbClient/SftpClient) ---- */

function listJsonToEntries(json: string): DavListEntry[] {
  const raw = JSON.parse(json) as Array<Record<string, ESObject>>;
  const out: DavListEntry[] = [];
  for (let i = 0; i < raw.length; i++) {
    out.push({
      name: raw[i]['name'] as string,
      isDir: raw[i]['isDir'] as boolean,
      size: raw[i]['size'] as number
    });
  }
  out.sort((a: DavListEntry, b: DavListEntry): number => {
    if (a.isDir !== b.isDir) {
      return a.isDir ? -1 : 1;
    }
    return a.name.localeCompare(b.name);
  });
  return out;
}

/** List an SMB directory. Throws Error('SMB_AUTH: ..' | 'SMB_ERROR: ..'). */
export async function smbListDir(server: string, share: string, path: string, user: string,
  domain: string, password: string): Promise<DavListEntry[]> {
  const json: string = await smbListAsync(server, share, path, user, domain, password);
  return listJsonToEntries(json);
}

/** List an SFTP directory. Throws Error('SFTP_AUTH: ..' | 'SFTP_ERROR: ..'). */
export async function sftpListDir(host: string, port: number, user: string, password: string,
  keyPath: string, keyPass: string, trustAll: boolean, expectFp: string,
  path: string): Promise<DavListEntry[]> {
  const json: string = await sftpListAsync(host, port, user, password, keyPath, keyPass, trustAll, expectFp, path);
  return listJsonToEntries(json);
}

/** One-shot connect to read the host key fingerprint (TOFU persist), then close. */
export async function sftpProbeFingerprint(host: string, port: number, user: string, password: string,
  keyPath: string, keyPass: string, trustAll: boolean, expectFp: string): Promise<string> {
  const res: string = await sftpConnectAsync(host, port, user, password, keyPath, keyPass, trustAll, expectFp);
  const conn = JSON.parse(res) as Record<string, ESObject>;
  const fp: string = conn['fingerprint'] as string;
  const h: number = conn['handle'] as number;
  try {
    await sftpCloseAsync(h);
  } catch (e) { /* ignore */ }
  return fp;
}

/** Resolve the user's home directory (absolute path) via a one-shot connection. */
export async function sftpHomeOf(host: string, port: number, user: string, password: string,
  keyPath: string, keyPass: string, trustAll: boolean, expectFp: string): Promise<string> {
  const res: string = await sftpConnectAsync(host, port, user, password, keyPath, keyPass, trustAll, expectFp);
  const conn = JSON.parse(res) as Record<string, ESObject>;
  const h: number = conn['handle'] as number;
  try {
    return await sftpHomeAsync(h);
  } finally {
    try {
      await sftpCloseAsync(h);
    } catch (e) { /* ignore */ }
  }
}

/* ---- EPUB DRM pre-probe (zip central directory -> META-INF/encryption.xml) ---- */

function u16le(b: Uint8Array, off: number): number {
  return b[off] | (b[off + 1] << 8);
}

function u32le(b: Uint8Array, off: number): number {
  return (b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24)) >>> 0;
}

/**
 * Detect an encrypted EPUB by scanning the zip central directory (at the end
 * of the file — the tech doc's tail-first strategy) for META-INF/encryption.xml.
 * readAt(offset, len) must return up to len bytes; `size` is the remote file
 * size. Returns false on any parse failure (treat as unencrypted, like Android).
 */
export async function epubIsEncrypted(readAt: (offset: number, len: number) => Promise<ArrayBuffer>,
  size: number): Promise<boolean> {
  if (size < 64) {
    return false;
  }
  try {
    const tailLen: number = Math.min(65536, size);
    const tail: ArrayBuffer = await readAt(size - tailLen, tailLen);
    const b: Uint8Array = new Uint8Array(tail);
    /* locate the End Of Central Directory record (signature PK\5\6), scanning back */
    let eocd = -1;
    for (let i = b.length - 22; i >= 0; i--) {
      if (b[i] === 0x50 && b[i + 1] === 0x4b && b[i + 2] === 0x05 && b[i + 3] === 0x06) {
        eocd = i;
        break;
      }
    }
    if (eocd < 0) {
      return false;
    }
    const cdOffset: number = u32le(b, eocd + 16);
    const cdSize: number = u32le(b, eocd + 12);
    if (cdOffset <= 0 || cdOffset >= size || cdSize <= 0) {
      return false;
    }
    let cd: Uint8Array;
    if (cdOffset >= size - tailLen) {
      const base: number = size - tailLen;
      cd = b.subarray(cdOffset - base, Math.min(b.length, cdOffset - base + cdSize));
    } else {
      const want: number = Math.min(cdSize, 4 * 1024 * 1024);
      cd = new Uint8Array(await readAt(cdOffset, want));
    }
    /* walk central directory entries: sig PK\1\2, name at +46 */
    let p = 0;
    while (p + 46 <= cd.length) {
      if (!(cd[p] === 0x50 && cd[p + 1] === 0x4b && cd[p + 2] === 0x01 && cd[p + 3] === 0x02)) {
        break;
      }
      const nameLen: number = u16le(cd, p + 28);
      if (p + 46 + nameLen > cd.length) {
        break;
      }
      let name = '';
      for (let i = 0; i < nameLen; i++) {
        name += String.fromCharCode(cd[p + 46 + i]);
      }
      if (name.toLowerCase() === 'meta-inf/encryption.xml') {
        return true;
      }
      p += 46 + nameLen + u16le(cd, p + 30) + u16le(cd, p + 32) + u16le(cd, p + 34);
    }
    return false;
  } catch (e) {
    return false;
  }
}

/* ---- Two-level block cache (Android BlockCacheStore) ---- */

export interface RemoteBookMeta {
  size: number;
  versionTag: string;
  blockSize: number;
  fullyCached: boolean;
}

const MEM_BLOCK_LIMIT = 128;
const MEM_BYTE_LIMIT = 32 * 1024 * 1024;

export function remoteRoot(ctx: Context): string {
  return ctx.filesDir + '/Remote';
}

export function bookDirOf(ctx: Context, cacheKey: string): string {
  return remoteRoot(ctx) + '/' + cacheKey;
}

function sleepMs(ms: number): Promise<void> {
  return new Promise<void>((resolve: () => void) => {
    setTimeout(() => {
      resolve();
    }, ms);
  });
}

export class BlockCacheStore {
  private ctx: Context;
  private dir: string;
  meta: RemoteBookMeta;
  private bitmap: Uint8Array = new Uint8Array(0);
  private blockCount: number = 0;
  private mem: Map<number, Uint8Array> = new Map(); /* insertion order = LRU order */
  private memBytes = 0;
  private dataFd: number = -1;
  private inflight: Map<number, Promise<ArrayBuffer>> = new Map();

  constructor(ctx: Context, cacheKey: string) {
    this.ctx = ctx;
    this.dir = bookDirOf(ctx, cacheKey);
    this.meta = { size: 0, versionTag: '', blockSize: 256 * 1024, fullyCached: false };
  }

  /** Read meta.json; null when no cache exists yet. */
  static async readMeta(ctx: Context, cacheKey: string): Promise<RemoteBookMeta | null> {
    try {
      const path: string = bookDirOf(ctx, cacheKey) + '/meta.json';
      if (!fs.accessSync(path)) {
        return null;
      }
      const f = fs.openSync(path, fs.OpenMode.READ_ONLY);
      const stat = fs.statSync(path);
      const buf = new ArrayBuffer(stat.size);
      fs.readSync(f.fd, buf);
      fs.closeSync(f);
      const obj = JSON.parse(new util.TextDecoder('utf-8').decodeToString(new Uint8Array(buf))) as RemoteBookMeta;
      return obj;
    } catch (e) {
      return null;
    }
  }

  /** Load or (re)create the cache. Wipes the dir when version/blockSize changed. */
  async loadOrCreate(size: number, versionTag: string, blockSize: number): Promise<void> {
    const old = await BlockCacheStore.readMeta(this.ctx, this.dir.substring(this.dir.lastIndexOf('/') + 1));
    const incompatible = old !== null &&
      (old.versionTag !== versionTag || old.blockSize !== blockSize || old.size !== size);
    if (old === null || incompatible) {
      /* fresh / invalidated: rebuild the directory */
      try {
        if (fs.accessSync(this.dir)) {
          fs.rmdirSync(this.dir);
        }
      } catch (e) { /* ignore */ }
      this.meta = { size: size, versionTag: versionTag, blockSize: blockSize, fullyCached: false };
    } else {
      this.meta = old;
    }
    this.blockCount = Math.ceil(size / this.meta.blockSize);
    this.bitmap = new Uint8Array(this.blockCount);
    try {
      if (!fs.accessSync(this.dir)) {
        fs.mkdirSync(this.dir, true);
      }
    } catch (e) { /* exists */ }
    /* load existing bitmap */
    try {
      const bmpPath: string = this.dir + '/blocks.bin';
      if (fs.accessSync(bmpPath)) {
        const f = fs.openSync(bmpPath, fs.OpenMode.READ_ONLY);
        const stat = fs.statSync(bmpPath);
        const n: number = Math.min(stat.size, this.blockCount);
        if (n > 0) {
          const buf = new ArrayBuffer(n);
          fs.readSync(f.fd, buf);
          this.bitmap.set(new Uint8Array(buf).subarray(0, n), 0);
        }
        fs.closeSync(f);
      }
    } catch (e) { /* ignore */ }
    /* (re)write meta */
    await this.flushMeta();
  }

  async flushMeta(): Promise<void> {
    try {
      const json: string = JSON.stringify(this.meta);
      const f = fs.openSync(this.dir + '/meta.json',
        fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, json);
      fs.closeSync(f);
    } catch (e) { /* ignore */ }
  }

  private async persistBitmap(): Promise<void> {
    try {
      const f = fs.openSync(this.dir + '/blocks.bin',
        fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, this.bitmap.buffer);
      fs.closeSync(f);
    } catch (e) { /* ignore */ }
  }

  private async openDataFd(): Promise<number> {
    if (this.dataFd < 0) {
      this.dataFd = fs.openSync(this.dir + '/data.bin',
        fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE).fd;
    }
    return this.dataFd;
  }

  hasBlock(bi: number): boolean {
    return bi >= 0 && bi < this.blockCount && this.bitmap[bi] === 1;
  }

  private memGet(bi: number): Uint8Array | undefined {
    const v = this.mem.get(bi);
    if (v !== undefined) {
      /* touch: re-insert to move to MRU position */
      this.mem.delete(bi);
      this.mem.set(bi, v);
    }
    return v;
  }

  private memPut(bi: number, data: Uint8Array): void {
    if (this.mem.has(bi)) {
      this.memBytes -= this.mem.get(bi)!.byteLength;
      this.mem.delete(bi);
    }
    this.mem.set(bi, data);
    this.memBytes += data.byteLength;
    while (this.mem.size > MEM_BLOCK_LIMIT ||
      (this.memBytes > MEM_BYTE_LIMIT && this.mem.size > 1)) {
      const oldest: number | undefined = this.mem.keys().next().value;
      if (oldest === undefined) {
        break;
      }
      this.memBytes -= this.mem.get(oldest)!.byteLength;
      this.mem.delete(oldest);
    }
  }

  private async diskReadBlock(bi: number): Promise<ArrayBuffer | null> {
    if (!this.hasBlock(bi)) {
      return null;
    }
    try {
      const fd = await this.openDataFd();
      const bs = this.meta.blockSize;
      const off = bi * bs;
      const want: number = Math.min(bs, this.meta.size - off);
      const buf = new ArrayBuffer(want);
      fs.readSync(fd, buf, { offset: off });
      return buf;
    } catch (e) {
      return null;
    }
  }

  private async diskWriteBlock(bi: number, data: ArrayBuffer): Promise<void> {
    try {
      const fd = await this.openDataFd();
      fs.writeSync(fd, data, { offset: bi * this.meta.blockSize });
      if (bi < this.blockCount && this.bitmap[bi] !== 1) {
        this.bitmap[bi] = 1;
        await this.persistBitmap();
      }
    } catch (e) { /* ignore */ }
  }

  /** Assemble [offset, offset+len) from mem/disk/remote; caches missing blocks. */
  async readAt(offset: number, len: number,
    fetcher: (off: number, want: number) => Promise<ArrayBuffer>): Promise<ArrayBuffer> {
    const end: number = Math.min(offset + len, this.meta.size);
    const want: number = Math.max(0, end - offset);
    if (want === 0) {
      return new ArrayBuffer(0);
    }
    const out = new Uint8Array(want);
    const bs = this.meta.blockSize;
    const b0: number = Math.floor(offset / bs);
    const b1: number = Math.floor((end - 1) / bs);
    for (let bi = b0; bi <= b1; bi++) {
      const blk: ArrayBuffer = await this.ensureBlock(bi, fetcher);
      const from: number = Math.max(offset, bi * bs) - bi * bs;
      const to: number = Math.min(end, (bi + 1) * bs) - bi * bs;
      const dst: number = Math.max(0, bi * bs - offset);
      out.set(new Uint8Array(blk).subarray(from, to), dst);
    }
    return out.buffer;
  }

  /** One block from mem/disk; missing blocks go through `fetcher` once. */
  async ensureBlock(bi: number,
    fetcher: (off: number, want: number) => Promise<ArrayBuffer>): Promise<ArrayBuffer> {
    const mem = this.memGet(bi);
    if (mem !== undefined) {
      return mem.buffer.slice(mem.byteOffset, mem.byteOffset + mem.byteLength);
    }
    const existing = this.inflight.get(bi);
    if (existing !== undefined) {
      return existing;
    }
    const p = (async (): Promise<ArrayBuffer> => {
      let data: ArrayBuffer | null = await this.diskReadBlock(bi);
      if (data === null) {
        const bs = this.meta.blockSize;
        const off = bi * bs;
        const want: number = Math.max(1, Math.min(bs, this.meta.size - off));
        data = await fetcher(off, want);
        await this.diskWriteBlock(bi, data);
      }
      this.memPut(bi, new Uint8Array(data.slice(0)));
      this.inflight.delete(bi);
      return data;
    })();
    this.inflight.set(bi, p);
    return p;
  }

  /** P2: background prefetch of `depth` blocks starting at `from` (P3-yielding). */
  async prefetchAhead(from: number, depth: number,
    fetcher: (off: number, want: number) => Promise<ArrayBuffer>): Promise<void> {
    let done = 0;
    for (let bi = from; bi < this.blockCount && done < depth; bi++) {
      if (this.hasBlock(bi) || this.inflight.has(bi)) {
        continue;
      }
      done++;
      try {
        await this.ensureBlock(bi, fetcher);
      } catch (e) {
        return; /* prefetch failures are tolerated */
      }
    }
  }

  /** P3: sequential whole-book filler; yields to foreground reads. */
  async fillWholeBook(fetcher: (off: number, want: number) => Promise<ArrayBuffer>,
    onProgress: (percent: number) => void, isCancelled: () => boolean): Promise<void> {
    for (let bi = 0; bi < this.blockCount; bi++) {
      if (isCancelled()) {
        return;
      }
      if (this.hasBlock(bi)) {
        continue;
      }
      /* yield to foreground fetches (P0/P1 priority) */
      while (this.inflight.size > 0 && !isCancelled()) {
        await sleepMs(200);
      }
      if (isCancelled()) {
        return;
      }
      try {
        await this.ensureBlock(bi, fetcher);
      } catch (e) {
        return;
      }
      onProgress(Math.round((bi + 1) * 100 / this.blockCount));
    }
    this.meta.fullyCached = true;
    await this.flushMeta();
  }

  get cachedBlockCount(): number {
    let n = 0;
    for (let i = 0; i < this.blockCount; i++) {
      if (this.bitmap[i] === 1) {
        n++;
      }
    }
    return n;
  }

  get totalBlockCount(): number {
    return this.blockCount;
  }

  close(): void {
    if (this.dataFd >= 0) {
      try {
        fs.closeSync(this.dataFd);
      } catch (e) { /* ignore */ }
      this.dataFd = -1;
    }
  }
}

/** Cached percent of a book directory (0..100). */
export async function cachedPercent(ctx: Context, cacheKey: string): Promise<number> {
  const meta = await BlockCacheStore.readMeta(ctx, cacheKey);
  if (meta === null || meta.size <= 0) {
    return 0;
  }
  try {
    const dir: string = bookDirOf(ctx, cacheKey);
    const bmpPath: string = dir + '/blocks.bin';
    if (!fs.accessSync(bmpPath)) {
      return meta.fullyCached ? 100 : 0;
    }
    const f = fs.openSync(bmpPath, fs.OpenMode.READ_ONLY);
    const stat = fs.statSync(bmpPath);
    const buf = new ArrayBuffer(stat.size);
    fs.readSync(f.fd, buf);
    fs.closeSync(f);
    const arr = new Uint8Array(buf);
    const total: number = Math.ceil(meta.size / meta.blockSize);
    let n = 0;
    for (let i = 0; i < total && i < arr.length; i++) {
      if (arr[i] === 1) {
        n++;
      }
    }
    return Math.round(n * 100 / total);
  } catch (e) {
    return 0;
  }
}

export async function remoteCacheTotalBytes(ctx: Context): Promise<number> {
  let total = 0;
  try {
    const root: string = remoteRoot(ctx);
    if (!fs.accessSync(root)) {
      return 0;
    }
    const names: string[] = fs.listFileSync(root);
    for (let i = 0; i < names.length; i++) {
      total += await dirSizeRecursive(root + '/' + names[i]);
    }
  } catch (e) { /* ignore */ }
  return total;
}

async function dirSizeRecursive(path: string): Promise<number> {
  let total = 0;
  try {
    const stat = fs.statSync(path);
    if (stat.isDirectory()) {
      const names: string[] = fs.listFileSync(path);
      for (let i = 0; i < names.length; i++) {
        total += await dirSizeRecursive(path + '/' + names[i]);
      }
    } else {
      total += stat.size;
    }
  } catch (e) { /* ignore */ }
  return total;
}

export async function clearRemoteCacheBook(ctx: Context, cacheKey: string): Promise<void> {
  try {
    const dir: string = bookDirOf(ctx, cacheKey);
    if (fs.accessSync(dir)) {
      fs.rmdirSync(dir);
    }
  } catch (e) { /* ignore */ }
}

export async function clearRemoteCacheAll(ctx: Context): Promise<void> {
  try {
    const root: string = remoteRoot(ctx);
    if (fs.accessSync(root)) {
      fs.rmdirSync(root);
    }
  } catch (e) { /* ignore */ }
}

/* ---- RemoteSession: per-open book (Android RemoteBookSession) ---- */

export class RemoteSession {
  ctx: Context;
  uri: string;
  cacheKey: string;
  source: RemoteFileSource;
  settings: RemoteCacheSettings;
  store: BlockCacheStore;
  private closed: boolean = false;
  private prefetchBusy: boolean = false;
  private fillerBusy: boolean = false;
  private fillerGen: number = 0;
  onFillerProgress: ((percent: number) => void) | undefined = undefined;

  constructor(ctx: Context, uri: string, cacheKey: string, source: RemoteFileSource,
    settings: RemoteCacheSettings) {
    this.ctx = ctx;
    this.uri = uri;
    this.cacheKey = cacheKey;
    this.source = source;
    this.settings = settings;
    this.store = new BlockCacheStore(ctx, cacheKey);
  }

  /** Probe remote, (re)open the cache; returns the probe (throws when unreachable). */
  async open(): Promise<RemoteProbe> {
    const probe: RemoteProbe | null = await this.source.probe();
    if (probe === null) {
      throw new Error('remote probe failed');
    }
    const ext: string = fileExtOf(this.uri.substring(this.uri.lastIndexOf('/') + 1));
    const blockSize: number = isPageFormatExt(ext) ? 1024 * 1024 : 256 * 1024;
    await this.store.loadOrCreate(probe.size, probe.versionTag, blockSize);
    return probe;
  }

  get meta(): RemoteBookMeta {
    return this.store.meta;
  }

  /** Dispatcher entry for the native stream bridge. */
  async readAt(offset: number, len: number): Promise<ArrayBuffer> {
    return this.store.readAt(offset, len, (off: number, want: number): Promise<ArrayBuffer> => {
      return this.source.readAt(off, want);
    });
  }

  /** P2 format-aware prefetch after a foreground read. */
  prefetchAfter(lastBlock: number): void {
    if (this.closed || this.prefetchBusy) {
      return;
    }
    const ext: string = fileExtOf(this.uri.substring(this.uri.lastIndexOf('/') + 1));
    const depth: number = isPageFormatExt(ext) ? 32 : 3;
    this.prefetchBusy = true;
    this.store.prefetchAhead(lastBlock + 1, depth, (off: number, want: number): Promise<ArrayBuffer> => {
      return this.source.readAt(off, want);
    }).finally(() => {
      this.prefetchBusy = false;
    });
  }

  /** P3 whole-book filler (Android maybeStartFiller). */
  maybeStartFiller(): void {
    if (this.closed || this.fillerBusy || this.store.meta.fullyCached) {
      return;
    }
    const threshold: number = this.settings.wholeBookThresholdMB * 1024 * 1024;
    if (threshold <= 0 || this.store.meta.size > threshold) {
      return;
    }
    /* metered-network policy is not probed this round (emulator/VM only);
     * wholeBookOnMetered=false still fills on wifi-like networks */
    this.fillerBusy = true;
    const gen: number = ++this.fillerGen;
    const fetcher = (off: number, want: number): Promise<ArrayBuffer> => {
      return this.source.readAt(off, want);
    };
    this.store.fillWholeBook(fetcher, (percent: number): void => {
      if (this.onFillerProgress !== undefined) {
        this.onFillerProgress(percent);
      }
    }, (): boolean => {
      return this.closed || gen !== this.fillerGen;
    }).finally(() => {
      this.fillerBusy = false;
    });
  }

  stopFiller(): void {
    this.fillerGen++;
  }

  close(): void {
    this.closed = true;
    this.stopFiller();
    this.store.close();
    /* release SMB/SFTP connections (no-op for WebDAV) */
    try {
      this.source.close();
    } catch (e) { /* ignore */ }
  }
}

/* ---- whole-book fetch (Android fetchToCacheAndOpen) ---- */

/** Download the whole book through the block cache, then materialize a
 * local copy at filesDir/Remote/books/<cacheKey>.<ext>. Returns the path. */
export async function fetchWholeBookToCache(session: RemoteSession, ext: string,
  onProgress: (percent: number) => void): Promise<string> {
  const total: number = session.store.totalBlockCount;
  const fetcher = (off: number, want: number): Promise<ArrayBuffer> => {
    return session.source.readAt(off, want);
  };
  let lastReported = -1;
  for (let bi = 0; bi < total; bi++) {
    if (!session.store.hasBlock(bi)) {
      await session.store.ensureBlock(bi, fetcher);
    }
    const percent: number = Math.round((bi + 1) * 100 / total);
    if (percent !== lastReported) {
      lastReported = percent;
      onProgress(percent);
    }
  }
  session.store.meta.fullyCached = true;
  await session.store.flushMeta();
  return materializeBook(session, ext);
}

/** Assemble the local file from cached blocks. */
export async function materializeBook(session: RemoteSession, ext: string): Promise<string> {
  const booksDir: string = remoteRoot(session.ctx) + '/books';
  try {
    if (!fs.accessSync(booksDir)) {
      fs.mkdirSync(booksDir, true);
    }
  } catch (e) { /* exists */ }
  const path: string = `${booksDir}/${session.cacheKey}${ext.length > 0 ? '.' + ext : ''}`;
  const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  try {
    const bs = session.store.meta.blockSize;
    const total: number = session.store.totalBlockCount;
    for (let bi = 0; bi < total; bi++) {
      const blk: ArrayBuffer = await session.store.ensureBlock(bi, (off: number, want: number): Promise<ArrayBuffer> => {
        return session.source.readAt(off, want);
      });
      fs.writeSync(f.fd, blk, { offset: bi * bs });
    }
  } finally {
    fs.closeSync(f);
  }
  return path;
}

/* ---- MOBI DRM probe (Android MobiHead.isEncrypted) ---- */

/** PalmDB record0 -> PalmDOC header encryption field (offset +12). */
export function mobiIsEncrypted(head: ArrayBuffer): boolean {
  try {
    const u8 = new Uint8Array(head);
    if (u8.length < 134) {
      return false;
    }
    const be16 = (off: number): number => (u8[off] << 8) | u8[off + 1];
    const be32 = (off: number): number =>
      ((u8[off] << 24) | (u8[off + 1] << 16) | (u8[off + 2] << 8) | u8[off + 3]) >>> 0;
    const numRecords: number = be16(76);
    if (numRecords < 1) {
      return false;
    }
    const record0: number = be32(78) & 0x00FFFFFF;
    if (record0 === 0 || record0 + 16 > u8.length) {
      return false;
    }
    const compression: number = be16(record0);
    const encryption: number = be16(record0 + 12);
    if (compression === 0) {
      return false; /* parse failure -> treat as unencrypted, let the engine report */
    }
    return encryption !== 0;
  } catch (e) {
    return false;
  }
}

/* ---- WebDAV directory listing (Android SftpClient/SmbClient list + RemoteScanner walk) ---- */

export interface DavListEntry {
  name: string;
  isDir: boolean;
  size: number;
}

export function basicAuthHeader(user: string, password: string): string {
  if (user.length === 0 && password.length === 0) {
    return '';
  }
  const raw: string = user + ':' + password;
  const arr: number[] = [];
  for (let i = 0; i < raw.length; i++) {
    arr.push(raw.charCodeAt(i) & 0xff);
  }
  return 'Basic ' + new util.Base64Helper().encodeToStringSync(new Uint8Array(arr));
}

function xmlTagValue(xml: string, tag: string, from: number): string {
  /* tag like 'href' matches <href>…</href> and any namespaced form
   * (<D:href>, <lp1:getcontentlength>, …) */
  const namespaced: string = ':' + tag + '>';
  let start = -1;
  let tagLen = 0;
  let idx: number = xml.indexOf(namespaced, from);
  while (idx >= 0) {
    const lt: number = xml.lastIndexOf('<', idx);
    if (lt >= 0 && idx - lt <= 48) {
      start = lt;
      tagLen = idx + namespaced.length - lt;
      break;
    }
    idx = xml.indexOf(namespaced, idx + 1);
  }
  if (start < 0) {
    const bare: number = xml.indexOf('<' + tag + '>', from);
    if (bare < 0) {
      return '';
    }
    start = bare;
    tagLen = tag.length + 2;
  }
  const close: number = xml.indexOf('</', start + tagLen);
  if (close < 0) {
    return '';
  }
  return xml.substring(start + tagLen, close).trim();
}

/** PROPFIND Depth:1 listing with size/dir classification; null on failure. */
export async function webdavListDir(baseUrl: string, user: string, password: string): Promise<DavListEntry[] | null> {
  const hdr: Record<string, string> = { 'Depth': '1' };
  const auth: string = basicAuthHeader(user, password);
  if (auth.length > 0) {
    hdr['Authorization'] = auth;
  }
  const req = http.createHttp();
  try {
    const resp = await req.request(baseUrl, {
      method: 'PROPFIND' as ESObject as http.RequestMethod,
      header: hdr,
      connectTimeout: 15000,
      readTimeout: 30000
    });
    let xml: string = '';
    if (resp.responseCode === 207 || resp.responseCode === 200) {
      xml = String(resp.result);
    } else {
      return null;
    }
    const out: DavListEntry[] = [];
    let pos: number = 0;
    while (true) {
      /* <D:response …> usually carries xmlns attributes — match the prefix */
      const r1: number = xml.indexOf('<D:response', pos);
      const r2: number = xml.indexOf('<response', pos);
      let rs = -1;
      if (r1 >= 0 && r2 >= 0) {
        rs = Math.min(r1, r2);
      } else if (r1 >= 0) {
        rs = r1;
      } else if (r2 >= 0) {
        rs = r2;
      }
      if (rs < 0) {
        break;
      }
      const reO: string = xml.indexOf('</D:response>', rs) >= 0 ? '</D:response>' : '</response>';
      const re: number = xml.indexOf(reO, rs);
      const block: string = re > rs ? xml.substring(rs, re) : xml.substring(rs);
      pos = re > rs ? re + reO.length : rs + 1;
      const href: string = xmlTagValue(block, 'href', 0);
      if (href.length === 0) {
        continue;
      }
      /* skip the list dir itself */
      const base_path: string = baseUrl.replace(/^https?:\/\/[^/]+/i, '');
      const normHref: string = decodeURIComponent(href);
      const normBase: string = base_path.length === 0 ? '/' : base_path;
      if (normHref.replace(/\/+$/, '') === normBase.replace(/\/+$/, '')) {
        continue;
      }
      const isDir: boolean = block.indexOf(':collection') >= 0 || block.indexOf('<collection') >= 0 ||
        href.endsWith('/');
      let size = 0;
      const cl: string = xmlTagValue(block, 'getcontentlength', 0);
      if (cl.length > 0) {
        size = parseInt(cl, 10);
      }
      let name: string = normHref.replace(/\/+$/, '');
      const slash: number = name.lastIndexOf('/');
      name = slash >= 0 ? name.substring(slash + 1) : name;
      if (name.length === 0) {
        continue;
      }
      out.push({ name: decodeURIComponent(name), isDir: isDir, size: size });
    }
    return out;
  } catch (e) {
    return null;
  } finally {
    req.destroy();
  }
}

/* ---- Remote books store (Android RemoteScanner + bookshelf badge) ---- */

export interface RemoteBookRec {
  uri: string;
  title: string;
  size: number;
  ext: string;
  serverName: string;
  addedAt: string;
}

export async function getRemoteBooks(ctx: Context): Promise<RemoteBookRec[]> {
  try {
    const prefs = await preferences.getPreferences(ctx, REMOTE_PREF_NAME);
    const raw: string = await prefs.get(REMOTE_BOOKS_KEY, '[]') as string;
    return JSON.parse(raw) as RemoteBookRec[];
  } catch (e) {
    return [];
  }
}

export async function saveRemoteBooks(ctx: Context, list: RemoteBookRec[]): Promise<void> {
  const prefs = await preferences.getPreferences(ctx, REMOTE_PREF_NAME);
  await prefs.put(REMOTE_BOOKS_KEY, JSON.stringify(list));
  await prefs.flush();
}

export async function upsertRemoteBook(ctx: Context, rec: RemoteBookRec): Promise<void> {
  const list: RemoteBookRec[] = await getRemoteBooks(ctx);
  const idx: number = list.findIndex((b: RemoteBookRec) => b.uri === rec.uri);
  if (idx >= 0) {
    list[idx] = rec;
  } else {
    list.unshift(rec);
  }
  await saveRemoteBooks(ctx, list);
}

export async function removeRemoteBook(ctx: Context, uri: string): Promise<void> {
  const list: RemoteBookRec[] = await getRemoteBooks(ctx);
  const keep: RemoteBookRec[] = list.filter((b: RemoteBookRec) => b.uri !== uri);
  await saveRemoteBooks(ctx, keep);
  for (let i = 0; i < list.length; i++) {
    if (list[i].uri === uri) {
      const key: string = await sha256Hex(uri);
      await clearRemoteCacheBook(ctx, key);
      break;
    }
  }
}
