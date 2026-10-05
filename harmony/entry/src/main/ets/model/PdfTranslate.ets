import fs from '@ohos.file.fs';
import { AiConfig, AiMessage, chatCompletion } from './AiClient';
import { TextLine } from 'libmupdf_napi.so';

/**
 * 0.9.20: AI translation for fixed-layout PDF pages (Android 10-01 route C +
 * 10-02 four modes parity). Paragraphs are merged from the per-line text rects
 * the engine already exposes (getTextRects); translation runs over a rolling
 * window (current page +1/+3) with 3-way parallel per-paragraph requests and a
 * per-page disk cache, so re-entering a page fills instantly. The Reader draws
 * the results either in a comparison panel, as in-place badges, or as text
 * blocks laid over the page image (bilingual reflow / replace).
 */

/** normalized rect (0..1 of the full page) */
export interface PdfRect {
  x0: number;
  y0: number;
  x1: number;
  y1: number;
}

/** one source paragraph merged from text lines of a single page */
export interface PdfPara {
  page: number;
  idx: number;
  text: string;
  rect: PdfRect;
}

/** overlay item handed down to PageRenderer (translated paragraphs only) */
export interface BiOverlay {
  idx: number;
  x0: number;
  y0: number;
  x1: number;
  y1: number;
  src: string;
  dst: string;
}

export const PDF_TRANS_PANEL: number = 0;
export const PDF_TRANS_FLOAT: number = 1;
export const PDF_TRANS_REFLOW: number = 2;
export const PDF_TRANS_REPLACE: number = 3;

export interface PdfTransCallbacks {
  onPara: (page: number, idx: number, dst: string) => void;
  onFail: (page: number, idx: number, msg: string) => void;
}

const GAP_FACTOR: number = 0.7;
const INDENT_PAGE: number = 0.03;
const CONCURRENCY: number = 3;

const SYS_PROMPT: string = '\u4F60\u662F\u4E13\u4E1A\u7684\u7FFB\u8BD1\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF0C\u4E0D\u8981\u89E3\u91CA\u3002';

/** FNV-1a hex hash — stable cache key (same scheme as Bilingual.ets). */
function fnvHex(s: string): string {
  let h: number = 0x811c9dc5;
  for (let i: number = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = (h * 0x01000193) >>> 0;
  }
  return 'k' + h.toString(16) + '_' + s.length.toString(16);
}

function isAlnum(c: string): boolean {
  const cc: number = c.charCodeAt(0);
  return (cc >= 48 && cc <= 57) || (cc >= 65 && cc <= 90) || (cc >= 97 && cc <= 122);
}

function cleanTranslation(s: string): string {
  let t: string = s.replace(/\r/g, '');
  t = t.replace(/^\s*[\u201c\u201d"]+/, '').replace(/[\u201c\u201d"]+\s*$/, '');
  t = t.replace(/\*\*/g, '');
  return t.trim();
}

/**
 * Merge per-line text (NAPI getTextRects, reading order) into paragraphs.
 * A new paragraph starts on a vertical gap, an indent or a dedent; standalone
 * page numbers at the extreme top/bottom are dropped.
 */
export function buildPdfParagraphs(page: number, lines: TextLine[]): PdfPara[] {
  const paras: PdfPara[] = [];
  const cur: TextLine[] = [];
  const flush = (): void => {
    if (cur.length === 0) {
      return;
    }
    let text: string = '';
    let x0: number = 2;
    let y0: number = 2;
    let x1: number = -1;
    let y1: number = -1;
    for (let i: number = 0; i < cur.length; i++) {
      const l: TextLine = cur[i];
      if (i > 0 && text.length > 0 && l.text.length > 0
        && isAlnum(text.charAt(text.length - 1)) && isAlnum(l.text.charAt(0))) {
        text += ' ';
      }
      text += l.text;
      if (l.x0 < x0) { x0 = l.x0; }
      if (l.y0 < y0) { y0 = l.y0; }
      if (l.x1 > x1) { x1 = l.x1; }
      if (l.y1 > y1) { y1 = l.y1; }
    }
    paras.push({ page: page, idx: paras.length, text: text.trim(), rect: { x0: x0, y0: y0, x1: x1, y1: y1 } });
    cur.splice(0, cur.length);
  };
  let firstX: number = -1;
  let prevH: number = 0.02;
  for (let i: number = 0; i < lines.length; i++) {
    const l: TextLine = lines[i];
    if (l === null || l === undefined || l.text === undefined || l.text === null) {
      continue;
    }
    const txt: string = l.text.trim();
    if (txt.length === 0) {
      flush();
      firstX = -1;
      continue;
    }
    const h: number = l.y1 - l.y0;
    /* standalone page-number-ish line at the page edge → noise, drop */
    if (txt.length <= 4 && /^[0-9ivxlc]+$/i.test(txt) && (l.y1 < 0.06 || l.y0 > 0.94)) {
      continue;
    }
    if (h <= 0) {
      continue;
    }
    if (cur.length === 0) {
      cur.push(l);
      firstX = l.x0;
      prevH = h;
      continue;
    }
    const prev: TextLine = cur[cur.length - 1];
    const gap: number = l.y0 - prev.y1;
    const indent: number = l.x0 - firstX;
    const dedent: number = firstX - l.x0;
    if (gap > GAP_FACTOR * prevH || indent > INDENT_PAGE || dedent > INDENT_PAGE) {
      flush();
      cur.push(l);
      firstX = l.x0;
    } else {
      cur.push(l);
      prevH = (prevH + h) / 2;
    }
  }
  flush();
  return paras;
}

/** Rolling-window PDF translation session (cancel + cache + 3-way parallel). */
export class PdfTransSession {
  private cfg: AiConfig;
  private srcLang: string;
  private dstLang: string;
  private dir: string;
  private bookKey: string;
  private gen: number = 0;
  private results: Map<string, string> = new Map();
  private loaded: Set<number> = new Set();
  private chain: Promise<void> = Promise.resolve();
  private cb: PdfTransCallbacks;

  constructor(cfg: AiConfig, srcLang: string, dstLang: string, ctx: Context, bookPath: string, cb: PdfTransCallbacks) {
    this.cfg = cfg;
    this.srcLang = srcLang;
    this.dstLang = dstLang;
    this.cb = cb;
    this.dir = ctx.cacheDir + '/pdftrans';
    /* fs.accessSync RETURNS a boolean here (does not throw) — mkdir
     * unconditionally; recursive mkdir is a no-op when it exists. */
    try {
      fs.mkdirSync(this.dir, true);
    } catch (e) {
      /* already exists */
    }
    this.bookKey = fnvHex(bookPath);
  }

  private k(page: number, idx: number): string {
    return page + '#' + idx;
  }

  /** translation for a paragraph ('' if not available yet) */
  cached(page: number, idx: number): string {
    const v: string | undefined = this.results.get(this.k(page, idx));
    return v === undefined ? '' : v;
  }

  /** make sure this page's disk cache is in the results map (idempotent) */
  loadCache(page: number): void {
    if (this.loaded.has(page)) {
      return;
    }
    this.loaded.add(page);
    try {
      const p: string = this.dir + '/' + this.bookKey + '_p' + page + '.json';
      let cached: boolean = false;
      try {
        fs.accessSync(p);
        cached = true;
      } catch (e) { /* not cached yet */ }
      if (cached) {
        const txt: string = fs.readTextSync(p);
        const obj: Record<string, string> = JSON.parse(txt) as Record<string, string>;
        const keys: string[] = Object.keys(obj);
        for (let i: number = 0; i < keys.length; i++) {
          this.results.set(keys[i], obj[keys[i]]);
        }
      }
    } catch (e) { /* no cache yet */ }
  }

  /** queue a page; requests run serialized per page, 3-way parallel inside */
  enqueuePage(page: number, paras: PdfPara[]): void {
    const g: number = this.gen;
    this.chain = this.chain.then(async (): Promise<void> => {
      if (g !== this.gen) {
        return;
      }
      this.loadCache(page);
      const todo: PdfPara[] = [];
      for (let i: number = 0; i < paras.length; i++) {
        const p: PdfPara = paras[i];
        if (p.text.length > 0 && p.text.length <= 2400 && !this.results.has(this.k(page, p.idx))) {
          todo.push(p);
        }
      }
      /* refresh ping: cached rows may have filled without any network call */
      this.cb.onPara(page, -1, '');
      if (todo.length === 0) {
        return;
      }
      let next: number = 0;
      const worker = async (): Promise<void> => {
        while (g === this.gen) {
          const my: number = next++;
          if (my >= todo.length) {
            break;
          }
          const it: PdfPara = todo[my];
          const key: string = this.k(page, it.idx);
          try {
            const out: string = await chatCompletion(this.cfg, this.msgs(it.text), 2048);
            if (g !== this.gen) {
              return;
            }
            const clean: string = cleanTranslation(out);
            this.results.set(key, clean);
            this.cb.onPara(page, it.idx, clean);
          } catch (e) {
            if (g === this.gen) {
              this.cb.onFail(page, it.idx, e instanceof Error ? e.message : String(e));
            }
          }
        }
      };
      const ws: Promise<void>[] = [];
      for (let w: number = 0; w < CONCURRENCY; w++) {
        ws.push(worker());
      }
      await Promise.all(ws);
      this.saveCache(page);
    });
  }

  private msgs(text: string): AiMessage[] {
    const from: string = this.srcLang;
    const ask: string = from.length > 0
      ? `\u8BF7\u628A\u4E0B\u9762\u7684\u6587\u672C\u4ECE${from}\u7FFB\u8BD1\u6210${this.dstLang}\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF1A\n\n` + text
      : `\u8BF7\u628A\u4E0B\u9762\u7684\u6587\u672C\u7FFB\u8BD1\u6210${this.dstLang}\uFF0C\u53EA\u8F93\u51FA\u8BD1\u6587\uFF1A\n\n` + text;
    return [
      { role: 'system', content: SYS_PROMPT },
      { role: 'user', content: ask }
    ];
  }

  private saveCache(page: number): void {
    try {
      const prefix: string = page + '#';
      const obj: Record<string, string> = {};
      this.results.forEach((v: string, key: string) => {
        if (key.startsWith(prefix)) {
          obj[key] = v;
        }
      });
      const p: string = this.dir + '/' + this.bookKey + '_p' + page + '.json';
      const f: fs.File = fs.openSync(p, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, JSON.stringify(obj));
      fs.closeSync(f);
    } catch (e) {
      console.error('[PdfTranslate] saveCache failed p' + page + ': ' + String(e));
    }
  }

  cancel(): void {
    this.gen++;
    this.chain = Promise.resolve();
  }
}
