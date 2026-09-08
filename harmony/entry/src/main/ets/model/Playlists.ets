import fs from '@ohos.file.fs';
import util from '@ohos.util';

/**
 * Playlists (Android parity: Playlists.java — text-file backed ordered book
 * lists). Each playlist is one file <filesDir>/playlists/<name>.playlist with
 * one book path per line, so lists survive without a database.
 */

const DIR_NAME = 'playlists';
const EXT = '.playlist';

export interface PlaylistMeta {
  /** display name (without extension) */
  name: string;
  /** entry count */
  count: number;
}

function playlistsDir(ctx: Context): string {
  return ctx.filesDir + '/' + DIR_NAME;
}

function ensureDir(ctx: Context): void {
  const dir = playlistsDir(ctx);
  try {
    if (!fs.accessSync(dir)) {
      fs.mkdirSync(dir);
    }
  } catch (e) {
    /* dir already exists */
  }
}

function validName(name: string): string {
  const t = name.replace(/[\\/:*?"<>|]/g, '_').trim();
  return t.length > 0 && t.length <= 60 ? t : '';
}

function playlistPath(ctx: Context, name: string): string {
  return playlistsDir(ctx) + '/' + name + EXT;
}

/** All playlists with entry counts, sorted by name. */
export function getAllPlaylists(ctx: Context): PlaylistMeta[] {
  const out: PlaylistMeta[] = [];
  try {
    ensureDir(ctx);
    const names: string[] = fs.listFileSync(playlistsDir(ctx));
    for (let i = 0; i < names.length; i++) {
      const n = names[i];
      if (!n.endsWith(EXT)) {
        continue;
      }
      const name = n.substring(0, n.length - EXT.length);
      let count = 0;
      try {
        const stat = fs.statSync(playlistPath(ctx, name));
        count = stat.size > 0 ? 1 : 0; /* rough; real count read lazily */
      } catch (e) {
        count = 0;
      }
      out.push({ name: name, count: count });
    }
    out.sort((a: PlaylistMeta, b: PlaylistMeta) => a.name.localeCompare(b.name));
  } catch (e) {
    console.error('[Playlists] list failed');
  }
  return out;
}

/** Create an empty playlist. Returns '' on success or an error message. */
export function createPlaylist(ctx: Context, name: string): string {
  const n = validName(name);
  if (n.length === 0) {
    return '\u540D\u79F0\u65E0\u6548';
  }
  try {
    ensureDir(ctx);
    if (fs.accessSync(playlistPath(ctx, n))) {
      return '\u540C\u540D\u64AD\u653E\u5217\u5DF2\u5B58\u5728';
    }
    const f = fs.openSync(playlistPath(ctx, n), fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    fs.closeSync(f);
    console.info('[Playlists] created ' + n);
    return '';
  } catch (e) {
    return '\u521B\u5EFA\u5931\u8D25\uFF1A' + String(e);
  }
}

/** Delete a playlist file (books themselves are untouched). */
export function deletePlaylist(ctx: Context, name: string): void {
  try {
    fs.unlinkSync(playlistPath(ctx, name));
    console.info('[Playlists] deleted ' + name);
  } catch (e) {
    console.error('[Playlists] delete failed: ' + name);
  }
}

/** Read the ordered book paths of one playlist. */
export function getPlaylistItems(ctx: Context, name: string): string[] {
  const items: string[] = [];
  try {
    const f = fs.openSync(playlistPath(ctx, name), fs.OpenMode.READ_ONLY);
    const stat = fs.statSync(playlistPath(ctx, name));
    const buf = new ArrayBuffer(stat.size);
    fs.readSync(f.fd, buf);
    fs.closeSync(f);
    const text = new util.TextDecoder('utf-8').decodeToString(new Uint8Array(buf));
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].replace(/\r/g, '').trim();
      if (line.length > 0 && items.indexOf(line) < 0) {
        items.push(line);
      }
    }
  } catch (e) {
    console.error('[Playlists] read failed: ' + name);
  }
  return items;
}

/** Rewrite the whole playlist (order changes / removals). */
export function updatePlaylist(ctx: Context, name: string, items: string[]): void {
  try {
    ensureDir(ctx);
    const f = fs.openSync(playlistPath(ctx, name), fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    fs.writeSync(f.fd, items.join('\n'));
    fs.closeSync(f);
    console.info('[Playlists] updated ' + name + ' (' + items.length + ' items)');
  } catch (e) {
    console.error('[Playlists] write failed: ' + name);
  }
}

/** Append a book path if not present. Returns true when added. */
export function addToPlaylist(ctx: Context, name: string, bookPath: string): boolean {
  const items = getPlaylistItems(ctx, name);
  if (items.indexOf(bookPath) >= 0) {
    return false;
  }
  items.push(bookPath);
  updatePlaylist(ctx, name, items);
  return true;
}
