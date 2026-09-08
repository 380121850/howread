import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
import util from '@ohos.util';
import { AiConfig, AiMessage, chatCompletion } from './AiClient';

/**
 * In-page bilingual translation (Android BilingualBuilder parity, round 4 D).
 * EPUB: unzip → inject <p class="aitran"> after every source paragraph →
 * re-zip → the Reader opens the rebuilt file (reflow re-layouts the whole
 * book). TXT: interleaved original/translation lines. Translations are
 * cached on disk keyed by a content hash, so re-opening skips network.
 * Only GET-free local AI calls via the OpenAI-compatible client.
 */

const BI_DIR = '/bilingual';
const BATCH = 5;
/** max chars of one paragraph sent to the model */
const PARA_LIMIT = 1200;
const SYS_PROMPT = '\u4F60\u662F\u4E13\u4E1A\u7684\u7FFB\u8BD1\u3002' +
  '\u628A\u7528\u6237\u7ED9\u7684\u6BCF\u4E00\u6BB5\u6587\u672C\u7FFB\u8BD1\u6210\u76EE\u6807\u8BED\u8A00\uFF0C' +
  '\u6309\u539F\u7F16\u53F7\u6BCF\u884C\u8F93\u51FA\u4E00\u6761\u8BD1\u6587\uFF0C\u4E0D\u8981\u89E3\u91CA\u3002';
const BI_CSS = '<style>p.aitran{color:#1565C0;opacity:0.92;margin-top:0.1em;}</style>';

export function isBilingualDoc(path: string): boolean {
  const p = path.toLowerCase();
  return p.endsWith('.epub') || p.endsWith('.txt') || p.endsWith('.html') || p.endsWith('.htm');
}

/** FNV-1a hex hash — stable cache key (md5 not required). */
function hashKey(text: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = (h * 0x01000193) >>> 0;
    /* JS bitwise keeps it 32-bit; mixing again for hex width */
  }
  return 'h' + h.toString(16) + '_' + text.length.toString(16);
}

function baseName(path: string): string {
  return path.replace(/\\/g, '/').split('/').pop() as string;
}

function workDir(ctx: Context): string {
  return ctx.cacheDir + BI_DIR;
}

function stripTags(s: string): string {
  let t = s.replace(/<[^>]*>/g, ' ');
  t = t.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'").replace(/&nbsp;/g, ' ');
  return t.replace(/\s+/g, ' ').trim();
}

async function loadCache(ctx: Context, bookKey: string): Promise<Record<string, string>> {
  try {
    const f = fs.openSync(workDir(ctx) + '/cache_' + bookKey + '.json', fs.OpenMode.READ_ONLY);
    const stat = fs.statSync(workDir(ctx) + '/cache_' + bookKey + '.json');
    const buf = new ArrayBuffer(stat.size);
    fs.readSync(f.fd, buf);
    fs.closeSync(f);
    const text = new util.TextDecoder('utf-8').decodeToString(new Uint8Array(buf));
    return JSON.parse(text) as Record<string, string>;
  } catch (e) {
    return {};
  }
}

async function saveCache(ctx: Context, bookKey: string, cache: Record<string, string>): Promise<void> {
  try {
    const target = workDir(ctx) + '/cache_' + bookKey + '.json';
    const f = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    fs.writeSync(f.fd, JSON.stringify(cache));
    fs.closeSync(f);
  } catch (e) {
    console.error('[Bilingual] cache save failed');
  }
}

/** Read a whole text file (utf-8). */
function readFile(path: string): string {
  const stat = fs.statSync(path);
  const f = fs.openSync(path, fs.OpenMode.READ_ONLY);
  const buf = new ArrayBuffer(stat.size);
  fs.readSync(f.fd, buf);
  fs.closeSync(f);
  return new util.TextDecoder('utf-8').decodeToString(new Uint8Array(buf));
}

function writeFile(path: string, text: string): void {
  const f = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  fs.writeSync(f.fd, text);
  fs.closeSync(f);
}

/** Translate a list of paragraphs using the cache; returns aligned translations. */
async function translateParas(cfg: AiConfig, dst: string, paras: string[],
  cache: Record<string, string>, onProg: (msg: string) => void): Promise<string[]> {
  const keys: string[] = [];
  for (let i = 0; i < paras.length; i++) {
    keys.push(hashKey(dst + '|' + paras[i]));
  }
  /* collect batches of untranslated paragraphs */
  let done = 0;
  for (let start = 0; start < paras.length; start += BATCH) {
    const idxs: number[] = [];
    for (let i = start; i < paras.length && i < start + BATCH; i++) {
      if (cache[keys[i]] === undefined || cache[keys[i]].length === 0) {
        idxs.push(i);
      }
    }
    if (idxs.length === 0) {
      done += Math.min(BATCH, paras.length - start);
      continue;
    }
    let user = `\u76EE\u6807\u8BED\u8A00\uFF1A${dst}\n`;
    for (let k = 0; k < idxs.length; k++) {
      let t = paras[idxs[k]];
      if (t.length > PARA_LIMIT) {
        t = t.substring(0, PARA_LIMIT);
      }
      user += (k + 1) + '. ' + t + '\n';
    }
    const msgs: AiMessage[] = [
      { role: 'system', content: SYS_PROMPT },
      { role: 'user', content: user }
    ];
    try {
      const resp: string = await chatCompletion(cfg, msgs, 2048);
      const lines = resp.split('\n');
      for (let k = 0; k < idxs.length; k++) {
        const want = '' + (k + 1);
        for (let li = 0; li < lines.length; li++) {
          const line = lines[li].trim();
          if (line.startsWith(want + '.') || line.startsWith(want + '\u3001') || line.startsWith(want + ')')) {
            cache[keys[idxs[k]]] = line.substring(want.length).replace(/^[\.\u3001\)]\s*/, '').trim();
            break;
          }
        }
      }
    } catch (e) {
      /* network/model failure: leave these paragraphs untranslated */
      console.error('[Bilingual] batch translate failed: ' + String(e));
    }
    done += idxs.length;
    onProg('\u5DF2\u7FFB\u8BD1 ' + done + ' / ' + paras.length + ' \u6BB5');
  }
  const out: string[] = [];
  for (let i = 0; i < paras.length; i++) {
    const v = cache[keys[i]];
    out.push(v === undefined ? '' : v);
  }
  return out;
}

/** .xhtml/.html/.htm files under dir (recursive). */
function listHtml(dir: string): string[] {
  const out: string[] = [];
  const walk = (d: string) => {
    let names: string[] = [];
    try {
      names = fs.listFileSync(d);
    } catch (e) {
      return;
    }
    for (let i = 0; i < names.length; i++) {
      const p = d + '/' + names[i];
      let isDir = false;
      try {
        isDir = fs.statSync(p).isDirectory();
      } catch (e) {
        continue;
      }
      if (isDir) {
        walk(p);
      } else {
        const low = names[i].toLowerCase();
        if (low.endsWith('.xhtml') || low.endsWith('.html') || low.endsWith('.htm')) {
          out.push(p);
        }
      }
    }
  };
  walk(dir);
  out.sort();
  return out;
}

function cleanDir(dir: string): void {
  try {
    if (fs.accessSync(dir)) {
      fs.rmdirSync(dir);
    }
  } catch (e) {
    /* best effort */
  }
}

interface ParaHit {
  start: number;
  end: number;
  text: string;
}

/** Locate every <p>…</p> close offset in an XHTML document (1:1 with </p> closes). */
function findParas(html: string): ParaHit[] {
  const hits: ParaHit[] = [];
  const re = /<p(\s[^>]*)?>([\s\S]*?)<\/p>/gi;
  let m = re.exec(html);
  while (m !== null) {
    const text = stripTags(m[2]);
    /* hit.end = offset just past the inner content, right before </p> */
    hits.push({ start: 0, end: m.index + m[0].length - 4, text: text });
    m = re.exec(html);
  }
  return hits;
}

/* ---- minimal store-only ZIP writer (controls entry order/paths for EPUB) ---- */

function buildCrcTable(): number[] {
  const t: number[] = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
    }
    t.push(c >>> 0);
  }
  return t;
}

const CRC_TABLE: number[] = buildCrcTable();

function crc32(data: Uint8Array): number {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < data.length; i++) {
    c = CRC_TABLE[(c ^ data[i]) & 0xFF] ^ (c >>> 8);
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function readBytes(path: string): Uint8Array {
  const stat = fs.statSync(path);
  const f = fs.openSync(path, fs.OpenMode.READ_ONLY);
  const buf = new ArrayBuffer(stat.size);
  fs.readSync(f.fd, buf);
  fs.closeSync(f);
  return new Uint8Array(buf);
}

function u16(arr: number[], v: number): void {
  arr.push(v & 0xFF, (v >>> 8) & 0xFF);
}

function u32(arr: number[], v: number): void {
  arr.push(v & 0xFF, (v >>> 8) & 0xFF, (v >>> 16) & 0xFF, (v >>> 24) & 0xFF);
}

function listAllFiles(dir: string): string[] {
  const out: string[] = [];
  const walk = (d: string) => {
    let names: string[] = [];
    try {
      names = fs.listFileSync(d);
    } catch (e) {
      return;
    }
    for (let i = 0; i < names.length; i++) {
      const p = d + '/' + names[i];
      let isDir = false;
      try {
        isDir = fs.statSync(p).isDirectory();
      } catch (e) {
        continue;
      }
      if (isDir) {
        walk(p);
      } else {
        out.push(p);
      }
    }
  };
  walk(dir);
  out.sort();
  return out;
}

/**
 * Zip the unpacked EPUB tree store-only (method 0). `mimetype` is written
 * first and META-INF/container.xml second so MuPDF accepts the archive.
 */
function zipDir(dir: string, outPath: string): void {
  const enc = new util.TextEncoder();
  const abs = listAllFiles(dir);
  const rels: string[] = [];
  for (let i = 0; i < abs.length; i++) {
    rels.push(abs[i].substring(dir.length + 1).replace(/\\/g, '/'));
  }
  /* mimetype first, container second, rest sorted */
  const order: number[] = [];
  for (let i = 0; i < rels.length; i++) {
    if (rels[i] === 'mimetype') {
      order.unshift(i);
    } else if (rels[i] === 'META-INF/container.xml') {
      order.splice(Math.min(order.length, 1), 0, i);
    } else {
      order.push(i);
    }
  }
  const locals: Uint8Array[] = [];
  const centrals: Uint8Array[] = [];
  let offset = 0;
  for (let k = 0; k < order.length; k++) {
    const idx = order[k];
    const data = readBytes(abs[idx]);
    const nb = enc.encode(rels[idx]);
    const crc = crc32(data);
    const lh: number[] = [];
    u32(lh, 0x04034b50);
    u16(lh, 20);
    u16(lh, 0);
    u16(lh, 0);
    u16(lh, 0);
    u16(lh, 0x2821);
    u32(lh, crc);
    u32(lh, data.length);
    u32(lh, data.length);
    u16(lh, nb.length);
    u16(lh, 0);
    locals.push(Uint8Array.from(lh));
    locals.push(nb);
    locals.push(data);
    const ch: number[] = [];
    u32(ch, 0x02014b50);
    u16(ch, 20);
    u16(ch, 20);
    u16(ch, 0);
    u16(ch, 0);
    u16(ch, 0);
    u16(ch, 0x2821);
    u32(ch, crc);
    u32(ch, data.length);
    u32(ch, data.length);
    u16(ch, nb.length);
    u16(ch, 0);
    u16(ch, 0);
    u16(ch, 0);
    u16(ch, 0);
    u32(ch, 0);
    u32(ch, offset);
    centrals.push(Uint8Array.from(ch));
    centrals.push(nb);
    offset += lh.length + nb.length + data.length;
  }
  const eocd: number[] = [];
  let centralSize = 0;
  for (let i = 0; i < centrals.length; i++) {
    centralSize += centrals[i].length;
  }
  u32(eocd, 0x06054b50);
  u16(eocd, 0);
  u16(eocd, 0);
  u16(eocd, order.length);
  u16(eocd, order.length);
  u32(eocd, centralSize);
  u32(eocd, offset);
  u16(eocd, 0);
  const total = offset + centralSize + eocd.length;
  const out = new Uint8Array(total);
  let pos = 0;
  for (let i = 0; i < locals.length; i++) {
    out.set(locals[i], pos);
    pos += locals[i].length;
  }
  for (let i = 0; i < centrals.length; i++) {
    out.set(centrals[i], pos);
    pos += centrals[i].length;
  }
  out.set(Uint8Array.from(eocd), pos);
  const f = fs.openSync(outPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  fs.writeSync(f.fd, out.buffer);
  fs.closeSync(f);
  console.info('[Bilingual] zip written: ' + outPath + ' (' + order.length + ' entries)');
}

/**
 * Build the bilingual EPUB. Returns the rebuilt file path; '' on failure.
 * Paragraphs keep the original document when translation is missing, so a
 * failed chapter never breaks the book.
 */
export async function buildBilingualEpub(ctx: Context, cfg: AiConfig, srcPath: string,
  dst: string, onProg: (msg: string) => void): Promise<string> {
  const dir = workDir(ctx);
  cleanDir(dir);
  const srcDir = dir + '/src';
  const outPath = dir + '/bi_' + baseName(srcPath);
  try {
    fs.mkdirSync(dir, true);
    fs.mkdirSync(srcDir, true);
    onProg('\u6B63\u5728\u89E3\u5305 EPUB\u2026');
    const zopt: zlib.Options = { level: 1 };
    await zlib.unzipFile(srcPath, srcDir, zopt);
    const files = listHtml(srcDir);
    if (files.length === 0) {
      onProg('\u672A\u627E\u5230\u53EF\u7FFB\u8BD1\u7684\u7AE0\u8282');
      return '';
    }
    const bookKey = hashKey(baseName(srcPath));
    const cache = await loadCache(ctx, bookKey);
    for (let i = 0; i < files.length; i++) {
      onProg(`\u7FFB\u8BD1\u7AE0\u8282 ${i + 1} / ${files.length}\u2026`);
      const html = readFile(files[i]);
      const hits = findParas(html);
      if (hits.length === 0) {
        continue;
      }
      const paras: string[] = [];
      for (let k = 0; k < hits.length; k++) {
        paras.push(hits[k].text);
      }
      const trans = await translateParas(cfg, dst, paras, cache, onProg);
      /* rebuild: insert translated <p> right after each source </p> */
      let out = '';
      let pos = 0;
      let hitIdx = 0;
      const re = /<\/p>/gi;
      let m = re.exec(html);
      while (m !== null) {
        const closeEnd = m.index + m[0].length;
        if (hitIdx < hits.length && hits[hitIdx].end <= m.index) {
          const t = trans[hitIdx];
          out += html.substring(pos, closeEnd);
          if (t !== undefined && t.length > 0) {
            out += '<p class="aitran">' + t + '</p>';
          }
          pos = closeEnd;
          hitIdx++;
        }
        m = re.exec(html);
      }
      out += html.substring(pos);
      /* inject the aitran css into <head> */
      const headIdx = out.toLowerCase().indexOf('</head>');
      if (headIdx >= 0) {
        out = out.substring(0, headIdx) + BI_CSS + out.substring(headIdx);
      } else {
        out = BI_CSS + out;
      }
      writeFile(files[i], out);
    }
    await saveCache(ctx, bookKey, cache);
    onProg('\u6B63\u5728\u91CD\u65B0\u6253\u5305\u2026');
    console.info('[Bilingual] zipping ' + srcDir + ' -> ' + outPath);
    zipDir(srcDir, outPath);
    onProg('\u5B8C\u6210');
    return outPath;
  } catch (e) {
    console.error('[Bilingual] epub pipeline failed: ' + String(e));
    onProg('\u5931\u8D25\uFF1A' + String(e));
    return '';
  }
}

/**
 * Build a bilingual TXT (original line + translated line). Returns the new
 * file path; '' on failure.
 */
export async function buildBilingualTxt(ctx: Context, cfg: AiConfig, srcPath: string,
  dst: string, onProg: (msg: string) => void): Promise<string> {
  try {
    const dir = workDir(ctx);
    fs.mkdirSync(dir, true);
    const text = readFile(srcPath);
    const paras: string[] = [];
    const lines = text.split('\n');
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].replace(/\r/g, '').trim();
      if (line.length > 0) {
        paras.push(line);
      }
    }
    if (paras.length === 0) {
      onProg('\u6587\u4EF6\u4E3A\u7A7A');
      return '';
    }
    const bookKey = hashKey(baseName(srcPath));
    const cache = await loadCache(ctx, bookKey);
    const trans = await translateParas(cfg, dst, paras, cache, onProg);
    await saveCache(ctx, bookKey, cache);
    const outLines: string[] = [];
    for (let i = 0; i < paras.length; i++) {
      outLines.push(paras[i]);
      if (trans[i].length > 0) {
        outLines.push(trans[i]);
      }
      outLines.push('');
    }
    const outPath = dir + '/bi_' + baseName(srcPath);
    writeFile(outPath, outLines.join('\n'));
    onProg('\u5B8C\u6210');
    return outPath;
  } catch (e) {
    console.error('[Bilingual] txt pipeline failed: ' + String(e));
    onProg('\u5931\u8D25\uFF1A' + String(e));
    return '';
  }
}

/** Build the bilingual variant of any supported document. */
export function buildBilingual(ctx: Context, cfg: AiConfig, srcPath: string,
  dst: string, onProg: (msg: string) => void): Promise<string> {
  const p = srcPath.toLowerCase();
  if (p.endsWith('.epub')) {
    return buildBilingualEpub(ctx, cfg, srcPath, dst, onProg);
  }
  return buildBilingualTxt(ctx, cfg, srcPath, dst, onProg);
}
