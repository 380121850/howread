import fs from '@ohos.file.fs';
import { openDocument, pageCount, getText, closeDocument } from 'libmupdf_napi.so';
import { RecentBook } from './ReadingProgress';

/**
 * Round 6: library-level helpers (Android SearchCore / CheckDeletedBooksWorker parity):
 *  - scanDeviceBooks: best-effort scan of well-known device folders for book files
 *    (sandboxed app dirs always work; external /storage dirs are attempted and
 *    silently skipped when the platform denies access)
 *  - importPathToSandbox: copy a scanned native path into the app cache
 *  - searchLibraryText: full-text search across the library (per book: open,
 *    walk pages, match normalized text, collect excerpts with a progress callback)
 *  - findMissingBooks: library cleanup — paths whose files no longer exist
 */

/** File extensions treated as books during the device scan. */
const BOOK_EXTS: string[] = ['pdf', 'epub', 'txt', 'html', 'htm', 'mobi', 'fb2', 'docx', 'rtf', 'md', 'cbz'];

/** Round 7: scan-format families (Android AppState.support* -> ExtUtils.seachExts).
 *  The whitelist only filters scanning/import, never decoding ability. */
export const FORMAT_KEYS: string[] =
  ['pdf', 'epub', 'fb2', 'txtmd', 'mobi', 'djvu', 'xps', 'doc', 'rtf', 'odt', 'cbz', 'zip', 'html'];

const FORMAT_EXTS: Record<string, string[]> = {
  'pdf': ['pdf'],
  'epub': ['epub'],
  'fb2': ['fb2'],
  'txtmd': ['txt', 'md'],
  'mobi': ['mobi', 'azw', 'azw3', 'prc'],
  'djvu': ['djvu', 'djv'],
  'xps': ['xps', 'oxps'],
  'doc': ['doc', 'docx'],
  'rtf': ['rtf'],
  'odt': ['odt'],
  'cbz': ['cbz', 'cbr'],
  'zip': ['zip'],
  'html': ['html', 'htm']
};

/** Parse the persisted whitelist JSON; an empty string = no filter (all allowed). */
export function parseScanFormats(json: string): Record<string, boolean> {
  const r: Record<string, boolean> = {};
  if (json.length === 0) {
    return r;
  }
  try {
    const o: object = JSON.parse(json) as object;
    const m: Record<string, boolean> = o as Record<string, boolean>;
    const keys: string[] = Object.keys(o);
    for (let i = 0; i < keys.length; i++) {
      r[keys[i]] = m[keys[i]] === true;
    }
  } catch (e) {
    return {};
  }
  return r;
}

/** Is `ext` allowed under the whitelist? Empty map = everything allowed. */
export function isExtAllowed(ext: string, fmt: Record<string, boolean>): boolean {
  const keys: string[] = Object.keys(fmt);
  if (keys.length === 0) {
    return true;
  }
  const e: string = ext.toLowerCase();
  const fkeys: string[] = Object.keys(FORMAT_EXTS);
  for (let i = 0; i < fkeys.length; i++) {
    const exts: string[] = FORMAT_EXTS[fkeys[i]];
    if (exts.indexOf(e) >= 0) {
      return fmt[fkeys[i]] !== false;
    }
  }
  return true; /* unknown family: not filtered */
}

/** Per-book page cap for full-text search (keeps huge PDFs bounded). */
const SEARCH_PAGE_CAP: number = 300;

/** Excerpt length shown around a match. */
const EXCERPT_LEN: number = 48;

export interface ScanHit {
  path: string;
  name: string;
}

export interface LibSearchHit {
  path: string;
  title: string;
  page: number;    /* 0-based */
  excerpt: string;
}

function hasBookExt(name: string, fmt: Record<string, boolean>): boolean {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) {
    return false;
  }
  const ext: string = name.substring(dot + 1).toLowerCase();
  return BOOK_EXTS.indexOf(ext) >= 0 && isExtAllowed(ext, fmt);
}

function listBooksInDir(dir: string, out: ScanHit[], depth: number, maxDepth: number,
  fmt: Record<string, boolean>, skipNoMedia: boolean): void {
  if (depth > maxDepth) {
    return;
  }
  let names: string[] = [];
  try {
    names = fs.listFileSync(dir);
  } catch (e) {
    return; /* unreadable/forbidden dir — skip silently */
  }
  if (skipNoMedia && names.indexOf('.nomedia') >= 0) {
    return; /* Android semantics: folder marked with .nomedia is skipped */
  }
  for (let i = 0; i < names.length; i++) {
    const full: string = dir.endsWith('/') ? dir + names[i] : dir + '/' + names[i];
    let isDir = false;
    try {
      isDir = fs.statSync(full).isDirectory();
    } catch (e) {
      continue;
    }
    if (isDir) {
      listBooksInDir(full, out, depth + 1, maxDepth, fmt, skipNoMedia);
    } else if (hasBookExt(names[i], fmt)) {
      out.push({ path: full, name: names[i] });
    }
  }
}

/**
 * Scan well-known folders for book files. Directories that cannot be listed
 * (platform sandbox limits) are skipped; the result contains whatever was
 * reachable. External paths are attempted before the app sandbox ones.
 * `fmt` (Round 7) is the optional scan-format whitelist; `skipNoMedia`
 * skips folders marked with a .nomedia file.
 */
export function scanDeviceBooks(ctx: Context, maxDepth: number = 3,
  fmt: Record<string, boolean> = {}, skipNoMedia: boolean = true): ScanHit[] {
  const candidates: string[] = [
    '/storage/emulated/0/Download',
    '/storage/emulated/0/Documents',
    '/storage/emulated/0/Books',
    ctx.filesDir,
    ctx.cacheDir
  ];
  const out: ScanHit[] = [];
  for (let i = 0; i < candidates.length; i++) {
    listBooksInDir(candidates[i], out, 0, maxDepth, fmt, skipNoMedia);
  }
  return out;
}

/** Copy a scanned native-path book into the app cache; returns the sandbox path. */
export function importPathToSandbox(ctx: Context, srcPath: string): string {
  const name: string = srcPath.substring(srcPath.lastIndexOf('/') + 1);
  const target: string = ctx.cacheDir + '/' + name;
  try {
    fs.copyFileSync(srcPath, target);
    return target;
  } catch (e) {
    console.error('[LibrarySearch] copy failed: ' + srcPath);
    return '';
  }
}

function normalize(s: string): string {
  return s.toLowerCase().replace(/\s+/g, ' ');
}

/** Build a short excerpt around the first match inside `text`. */
function excerptFor(text: string, q: string): string {
  const idx: number = text.indexOf(q);
  if (idx < 0) {
    return text.substring(0, EXCERPT_LEN);
  }
  const start: number = Math.max(0, idx - EXCERPT_LEN / 2);
  let ex: string = text.substring(start, Math.min(text.length, start + EXCERPT_LEN));
  if (start > 0) {
    ex = '…' + ex;
  }
  if (start + EXCERPT_LEN < text.length) {
    ex = ex + '…';
  }
  return ex;
}

/**
 * Full-text search across the library. Books are opened one at a time so the
 * MuPDF handle count stays at 1; `onProgress(done, total)` updates the UI and
 * a `true` return from it cancels the run. Reflowable/binary failures are
 * skipped book-by-book so one bad file cannot abort the whole search.
 */
export async function searchLibraryText(
  books: RecentBook[],
  query: string,
  onProgress: (done: number, total: number) => boolean
): Promise<LibSearchHit[]> {
  const hits: LibSearchHit[] = [];
  const q: string = normalize(query);
  if (q.length === 0) {
    return hits;
  }
  for (let i = 0; i < books.length; i++) {
    if (!onProgress(i, books.length)) {
      break;
    }
    const book = books[i];
    let handle: ESObject | undefined = undefined;
    try {
      handle = openDocument(book.path);
      const total: number = pageCount(handle) as number;
      const cap: number = Math.min(total, SEARCH_PAGE_CAP);
      for (let p = 0; p < cap; p++) {
        let raw: string = '';
        try {
          raw = getText(handle, p, 1.0) as string;
        } catch (e) {
          break; /* page text unreadable — stop inside this book */
        }
        if (raw.length === 0) {
          continue;
        }
        if (normalize(raw).indexOf(q) >= 0) {
          hits.push({ path: book.path, title: book.title, page: p, excerpt: excerptFor(raw, query) });
          if (hits.length >= 200) {
            onProgress(books.length, books.length);
            return hits;
          }
        }
      }
    } catch (e) {
      /* unopenable book — skip */
    } finally {
      if (handle !== undefined) {
        try {
          closeDocument(handle);
        } catch (e2) {
          /* ignore */
        }
      }
    }
  }
  onProgress(books.length, books.length);
  return hits;
}

/** Library cleanup: return paths whose backing file has disappeared. */
export function findMissingBooks(books: RecentBook[]): string[] {
  const missing: string[] = [];
  for (let i = 0; i < books.length; i++) {
    try {
      if (!fs.accessSync(books[i].path)) {
        missing.push(books[i].path);
      }
    } catch (e) {
      missing.push(books[i].path);
    }
  }
  return missing;
}
