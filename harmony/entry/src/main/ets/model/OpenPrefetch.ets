import { openDocumentAsync, closeDocument, OpenAsyncResult } from 'libmupdf_napi.so';
import { tlog } from './TestLog';

/** A document pre-opened on a worker thread (0.9.34 P2-14). */
export interface OpenPrefetchEntry {
  path: string;
  handle: ESObject;
  isReflow: boolean;
  ts: number;
}

/**
 * 0.9.34 P2-14 (Android OpenPrefetch parity): pre-open a tapped book on a
 * worker while the Reader page is still starting up, so initReader can adopt
 * a ready handle instead of paying the open time in its own critical path.
 * Index starts it on tap; Reader takes (or discards) the entry. An
 * unconsumed entry is closed after AUTOCLOSE_MS so a tapped-but-never-opened
 * book does not keep a live mupdf document around.
 */
export class OpenPrefetch {
  private static AUTOCLOSE_MS: number = 30000;
  private static TTL_MS: number = 20000;
  private static entry: OpenPrefetchEntry | null = null;
  private static pendingPath: string = '';
  private static autoCloseId: number = -1;

  static start(path: string): void {
    if (OpenPrefetch.pendingPath === path) {
      return; /* already in flight */
    }
    if (OpenPrefetch.entry !== null && OpenPrefetch.entry.path === path
      && Date.now() - OpenPrefetch.entry.ts < OpenPrefetch.TTL_MS) {
      return; /* done and fresh */
    }
    OpenPrefetch.discardInternal();
    OpenPrefetch.pendingPath = path;
    openDocumentAsync(path).then((r: OpenAsyncResult) => {
      if (OpenPrefetch.pendingPath !== path) {
        /* superseded by a newer tap - close the stray handle, no leak */
        try {
          closeDocument(r.handle);
        } catch (_e2) { /* already gone */ }
        return;
      }
      OpenPrefetch.pendingPath = '';
      OpenPrefetch.entry = { path: path, handle: r.handle, isReflow: r.isReflow, ts: Date.now() };
      tlog('prefetch_open_done', 'len=' + path.length);
      if (OpenPrefetch.autoCloseId !== -1) {
        clearTimeout(OpenPrefetch.autoCloseId);
      }
      OpenPrefetch.autoCloseId = setTimeout(() => {
        OpenPrefetch.autoCloseId = -1;
        OpenPrefetch.discardInternal();
      }, OpenPrefetch.AUTOCLOSE_MS);
    }).catch((_e: Error) => {
      /* open failed: the reader's own sync open surfaces the real error */
      if (OpenPrefetch.pendingPath === path) {
        OpenPrefetch.pendingPath = '';
      }
    });
  }

  /** Consume the entry for `path` (null on miss/stale - caller falls back). */
  static take(path: string): OpenPrefetchEntry | null {
    const e: OpenPrefetchEntry | null = OpenPrefetch.entry;
    OpenPrefetch.entry = null;
    OpenPrefetch.pendingPath = '';
    if (OpenPrefetch.autoCloseId !== -1) {
      clearTimeout(OpenPrefetch.autoCloseId);
      OpenPrefetch.autoCloseId = -1;
    }
    if (e !== null && e.path === path && Date.now() - e.ts < OpenPrefetch.TTL_MS) {
      return e;
    }
    if (e !== null) {
      OpenPrefetch.closeHandle(e.handle);
    }
    return null;
  }

  static discard(): void {
    OpenPrefetch.discardInternal();
  }

  private static discardInternal(): void {
    const e: OpenPrefetchEntry | null = OpenPrefetch.entry;
    OpenPrefetch.entry = null;
    OpenPrefetch.pendingPath = '';
    if (OpenPrefetch.autoCloseId !== -1) {
      clearTimeout(OpenPrefetch.autoCloseId);
      OpenPrefetch.autoCloseId = -1;
    }
    if (e !== null) {
      OpenPrefetch.closeHandle(e.handle);
    }
  }

  private static closeHandle(handle: ESObject): void {
    try {
      closeDocument(handle);
    } catch (_e) { /* already gone */ }
  }
}
