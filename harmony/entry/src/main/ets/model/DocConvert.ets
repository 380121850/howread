import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
import { zipDir } from './Bilingual';
import util from '@ohos.util';

/**
 * Round 9 batch 2: convert .docx/.rtf documents to plain HTML so the MuPDF
 * html handler can open them (Android opens these formats natively; the
 * HarmonyOS MuPDF build has no rtf/docx handler). The converted file lives
 * under <cacheDir>/converted/<name>_<size>_<mtime>.html — deterministic per
 * source content, so repeated opens reuse the cache and reading progress
 * keeps its key.
 */

function htmlEscape(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

function decodeXmlEntities(s: string): string {
  return s
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, '\'')
    .replace(/&#(\d+);/g, (m: string, p1: string): string => String.fromCharCode(parseInt(p1, 10)))
    .replace(/&amp;/g, '&');
}

function cacheKey(path: string): string {
  try {
    const st = fs.statSync(path);
    const base = path.substring(path.lastIndexOf('/') + 1).replace(/\.[^.]+$/, '');
    const safe = base.replace(/[^A-Za-z0-9_-]/g, '_').substring(0, 40);
    return `${safe}_${st.size}_${Math.floor(st.mtime)}`;
  } catch (e) {
    return `doc_${Date.now()}`;
  }
}

function readTextFile(path: string): string {
  const file = fs.openSync(path, fs.OpenMode.READ_ONLY);
  try {
    const st = fs.statSync(path);
    const buf = new ArrayBuffer(st.size);
    fs.readSync(file.fd, buf);
    const dec = util.TextDecoder.create('utf-8');
    return dec.decodeToString(new Uint8Array(buf));
  } finally {
    fs.closeSync(file);
  }
}

function writeTextFile(path: string, content: string): void {
  const file = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  try {
    fs.writeSync(file.fd, content);
  } finally {
    fs.closeSync(file);
  }
}

/** 0.9.27 P1-7: first attribute value of `name` inside an xml tag blob. */
function extractAttr(s: string, name: string): string {
  const k: string = name + '="';
  const i: number = s.indexOf(k);
  if (i < 0) {
    return '';
  }
  const j: number = s.indexOf('"', i + k.length);
  return j > i ? s.substring(i + k.length, j) : '';
}

/** 0.9.27 P1-7: docx relationship map Id -> package-relative Target. */
function parseDocxRels(xml: string): Record<string, string> {
  const map: Record<string, string> = {};
  const re = /<Relationship\b[^>]*>/g;
  let m = re.exec(xml);
  while (m !== null) {
    const tag: string = m[0];
    const id: string = extractAttr(tag, 'Id');
    const target: string = extractAttr(tag, 'Target');
    if (id.length > 0 && target.length > 0) {
      map[id] = target;
    }
    m = re.exec(xml);
  }
  return map;
}

function wrapHtml(body: string): string {
  /* empty title: MuPDF's html handler renders the <title> text into the body */
  return '<html><head><meta charset="utf-8"><title></title></head><body>' + body + '</body></html>';
}

/* ------------------------------------------------------------------ */
/* DOCX: unzip with @ohos.zlib, scan word/document.xml for paragraphs  */
/* ------------------------------------------------------------------ */

/** Scan document.xml paragraphs; 0.9.27 P1-7: inline images for
 * <w:drawing>/<w:pict> via the rels map (img src relative to index.html). */
function scanDocxXml(xml: string, rels: Record<string, string>): string {
  let out: string = '';
  let bold: number = 0;
  let italic: number = 0;
  let i: number = 0;
  const n: number = xml.length;
  while (i < n) {
    const lt: number = xml.indexOf('<', i);
    if (lt < 0) {
      break;
    }
    const gt: number = xml.indexOf('>', lt);
    if (gt < 0) {
      break;
    }
    const tag: string = xml.substring(lt + 1, gt);
    /* plain text between tags belongs to the current <w:t> only if the
     * previous tag opened one — handled below via textStart/textEnd scan */
    if (tag === 'w:p' || tag.startsWith('w:p ') || tag.startsWith('w:p>')) {
      /* paragraph start */
      out += '<p>';
    } else if (tag === 'w:p/') {
      /* self-closed empty paragraph */
      out += '<p></p>\n';
    } else if (tag === '/w:p') {
      out += '</p>\n';
    } else if (tag === 'w:b' || tag.startsWith('w:b ') || tag.startsWith('w:b/')) {
      if (!tag.endsWith('/')) {
        bold++;
      }
    } else if (tag === '/w:b') {
      if (bold > 0) {
        bold--;
      }
    } else if (tag === 'w:i' || tag.startsWith('w:i ') || tag.startsWith('w:i/')) {
      if (!tag.endsWith('/')) {
        italic++;
      }
    } else if (tag === '/w:i') {
      if (italic > 0) {
        italic--;
      }
    } else if (tag === 'w:br' || tag.startsWith('w:br/') || tag.startsWith('w:br ')) {
      out += '<br/>';
    } else if (tag === 'w:tab' || tag.startsWith('w:tab/') || tag.startsWith('w:tab ')) {
      out += ' ';
    } else if (tag.startsWith('w:drawing') || tag.startsWith('w:pict')) {
      if (!tag.endsWith('/')) {
        const closeTag: string = tag.startsWith('w:drawing') ? '</w:drawing>' : '</w:pict>';
        const close: number = xml.indexOf(closeTag, gt);
        if (close > 0) {
          const seg: string = xml.substring(lt, close + closeTag.length);
          let rid: string = extractAttr(seg, 'r:embed');
          if (rid.length === 0) {
            rid = extractAttr(seg, 'r:id');
          }
          const target: string = rid.length > 0 ? (rels[rid] ?? '') : '';
          if (target.length > 0) {
            /* rels targets are relative to word/ (or absolute in-package) */
            const srcRef: string = target.startsWith('/') ? 'unzip' + target : 'unzip/word/' + target;
            out += '<img src="' + htmlEscape(srcRef) + '">';
          }
          i = close + closeTag.length;
          continue;
        }
      }
    } else if (tag.startsWith('w:t') && !tag.startsWith('w:tbl') && !tag.startsWith('w:tc')) {
      /* find the matching close and take the raw text */
      const close: number = xml.indexOf('</w:t>', gt);
      if (close < 0) {
        break;
      }
      const raw: string = xml.substring(gt + 1, close);
      if (raw.length > 0) {
        let style: string = '';
        if (bold > 0) {
          style += 'b';
        }
        if (italic > 0) {
          style += 'i';
        }
        if (style === 'b') {
          out += '<b>';
        } else if (style === 'i') {
          out += '<i>';
        } else if (style === 'bi') {
          out += '<b><i>';
        }
        out += htmlEscape(decodeXmlEntities(raw));
        if (style === 'b') {
          out += '</b>';
        } else if (style === 'i') {
          out += '</i>';
        } else if (style === 'bi') {
          out += '</i></b>';
        }
      }
      i = close + 6; /* skip past '</w:t>' */
      continue;
    }
    i = gt + 1;
  }
  return out;
}

async function convertDocx(ctx: Context, src: string, key: string, outPath: string): Promise<string> {
  /* 0.9.31 P1-7: the unzip tree IS the media store, mirrored into the EPUB
   * container's OEBPS/ - index.html references images inside it relatively,
   * so it must survive the conversion. */
  const workDir: string = ctx.cacheDir + '/converted/' + key + '/OEBPS/unzip';
  try {
    fs.mkdirSync(workDir, true);
  } catch (e) {
    /* already exists */
  }
  try {
    await zlib.decompressFile(src, workDir);
  } catch (e) {
    throw new Error('unzip: ' + (e instanceof Error ? e.message : String(e)));
  }
  const xmlPath: string = workDir + '/word/document.xml';
  const xml: string = readTextFile(xmlPath);
  let rels: Record<string, string> = {};
  try {
    rels = parseDocxRels(readTextFile(workDir + '/word/_rels/document.xml.rels'));
  } catch (e) { /* no rels: images will be dropped by scan */ }
  /* find the document body (skip headers/footers references) */
  const bodyStart: number = xml.indexOf('<w:body>');
  const xmlBody: string = bodyStart >= 0 ? xml.substring(bodyStart) : xml;
  const paras: string = scanDocxXml(xmlBody, rels);
  try {
    writeTextFile(outPath, wrapHtml(paras.length > 0 ? paras : '<p>(empty document)</p>'));
  } catch (e) {
    throw new Error('write html: ' + (e instanceof Error ? e.message : String(e)));
  }
  return outPath;
}

/* ------------------------------------------------------------------ */
/* RTF: group/control-word state machine → HTML                        */
/* ------------------------------------------------------------------ */

interface RtfState {
  bold: number;
  italic: number;
}

function convertRtf(rtf: string, key: string, outPath: string): string {
  let out: string = '';
  let para: string = '';
  const stack: Array<RtfState> = [];
  const cur: RtfState = { bold: 0, italic: 0 };
  let skipChars: number = 1; /* \ucN fallback bytes after \uN */
  let i: number = 0;
  const n: number = rtf.length;
  let ignoreDepth: number = -1; /* >0: inside a destination group to drop */

  const flushPara = (): void => {
    if (para.length > 0) {
      out += '<p>' + para + '</p>\n';
      para = '';
    }
  };
  const openStyle = (): void => {
    const b: boolean = cur.bold > 0;
    const it: boolean = cur.italic > 0;
    if (b && it) {
      para += '<b><i>';
    } else if (b) {
      para += '<b>';
    } else if (it) {
      para += '<i>';
    }
  };
  const closeStyle = (): void => {
    const b: boolean = cur.bold > 0;
    const it: boolean = cur.italic > 0;
    if (b && it) {
      para += '</i></b>';
    } else if (b) {
      para += '</b>';
    } else if (it) {
      para += '</i>';
    }
  };

  while (i < n) {
    const ch: string = rtf.charAt(i);
    if (ignoreDepth >= 0) {
      /* dropping a destination group (\fonttbl, \info, \*, …) */
      if (ch === '{') {
        ignoreDepth++;
      } else if (ch === '}') {
        ignoreDepth--;
      }
      i++;
      continue;
    }
    if (ch === '{') {
      const st: RtfState = { bold: cur.bold, italic: cur.italic };
      stack.push(st);
      i++;
      continue;
    }
    if (ch === '}') {
      const st: RtfState | undefined = stack.pop();
      if (st !== undefined) {
        cur.bold = st.bold;
        cur.italic = st.italic;
      }
      i++;
      continue;
    }
    if (ch === '\\') {
      const next: string = i + 1 < n ? rtf.charAt(i + 1) : '';
      if (next === '\\' || next === '{' || next === '}') {
        para += htmlEscape(next);
        i += 2;
        continue;
      }
      if (next === '~') {
        para += ' '; /* non-breaking space */
        i += 2;
        continue;
      }
      if (next === '\'') {
        /* \'hh hex byte (latin-1) */
        const hex: string = rtf.substr(i + 2, 2);
        const code: number = parseInt(hex, 16);
        if (!isNaN(code)) {
          para += htmlEscape(String.fromCharCode(code));
        }
        i += 4;
        continue;
      }
      if (next === 'u' || next === 'U') {
        /* \uN decimal UTF-16 code unit (can be negative) */
        let j: number = i + 2;
        let numStr: string = '';
        if (j < n && rtf.charAt(j) === '-') {
          numStr += '-';
          j++;
        }
        while (j < n && rtf.charAt(j) >= '0' && rtf.charAt(j) <= '9') {
          numStr += rtf.charAt(j);
          j++;
        }
        let code: number = parseInt(numStr, 10);
        if (!isNaN(code)) {
          if (code < 0) {
            code = code + 65536;
          }
          openStyle();
          para += htmlEscape(String.fromCharCode(code));
          closeStyle();
        }
        /* skip the \ucN fallback characters that follow */
        let remain: number = skipChars;
        while (remain > 0 && j < n) {
          const c2: string = rtf.charAt(j);
          if (c2 === '\\') {
            /* skip a control word/skip-able escape */
            if (j + 1 < n && rtf.charAt(j + 1) === '\'') {
              j += 4;
            } else {
              j += 2;
            }
          } else {
            j++;
          }
          remain--;
        }
        i = j;
        continue;
      }
      /* generic control word: \letters[numeric][ ] */
      let j: number = i + 1;
      let word: string = '';
      while (j < n && rtf.charAt(j) >= 'a' && rtf.charAt(j) <= 'z') {
        word += rtf.charAt(j);
        j++;
      }
      let numStr: string = '';
      if (j < n && (rtf.charAt(j) === '-' || (rtf.charAt(j) >= '0' && rtf.charAt(j) <= '9'))) {
        if (rtf.charAt(j) === '-') {
          numStr += '-';
          j++;
        }
        while (j < n && rtf.charAt(j) >= '0' && rtf.charAt(j) <= '9') {
          numStr += rtf.charAt(j);
          j++;
        }
      }
      if (j < n && rtf.charAt(j) === ' ') {
        j++; /* single delimiter space */
      }
      if (word === 'par' || word === 'line' || word === 'sect' || word === 'page') {
        flushPara();
      } else if (word === 'tab') {
        para += ' ';
      } else if (word === 'b') {
        cur.bold = numStr === '0' ? 0 : cur.bold + 1;
      } else if (word === 'i') {
        cur.italic = numStr === '0' ? 0 : cur.italic + 1;
      } else if (word === 'uc') {
        const v: number = parseInt(numStr, 10);
        skipChars = isNaN(v) ? 1 : v;
      } else if (word === 'fonttbl' || word === 'colortbl' || word === 'stylesheet'
        || word === 'info' || word === 'pict' || word === 'object' || word === 'header'
        || word === 'footer' || word === 'listtable' || word === 'listoverridetable'
        || word === 'generator' || word === 'themedata' || word === 'colorschememapping'
        || word === 'latentstyles' || word === 'datafield' || word === 'rsidtbl') {
        /* destination group: drop everything up to the matching brace */
        ignoreDepth = 0;
        i = j;
        continue;
      }
      i = j;
      continue;
    }
    /* plain character */
    if (ch === '\r' || ch === '\n') {
      i++;
      continue;
    }
    para += htmlEscape(ch);
    i++;
  }
  flushPara();
  writeTextFile(outPath, wrapHtml(out.length > 0 ? out : '<p>(empty document)</p>'));
  return outPath;
}

/* ------------------------------------------------------------------ */

/**
 * Convert a .docx/.rtf document to cached HTML and return the HTML path.
 * Repeated calls with the same unchanged source hit the cache directly.
 * Throws on failure (caller shows a toast). .doc is not supported here.
 */
export async function convertToHtml(ctx: Context, srcPath: string, ext: string): Promise<string> {
  const key: string = cacheKey(srcPath);
  const dir: string = ctx.cacheDir + '/converted';
  try {
    fs.mkdirSync(dir, true);
  } catch (e) {
    /* exists */
  }
  /* 0.9.31 P1-7: the html engine resolves <img> only inside a zip archive
   * (fz_parse_html5 takes an fz_archive) - the product ships as a minimal
   * EPUB container mirroring OEBPS/ (index.html + media tree). The old
   * flat/side-file caches are simply never hit again. */
  const outDir: string = dir + '/' + key;
  const oebps: string = outDir + '/OEBPS';
  const outPath: string = outDir + '/book.epub';
  try {
    if (fs.statSync(outPath).size > 0) {
      console.info(`[DocConvert] cache hit: ${outPath}`);
      return outPath;
    }
  } catch (e) {
    /* not cached yet */
  }
  try {
    fs.rmdirSync(outDir);
  } catch (e) { /* fresh rebuild below */ }
  fs.mkdirSync(outDir, true);
  fs.mkdirSync(oebps, true);
  const htmlPath: string = oebps + '/index.html';
  if (ext === 'docx') {
    await convertDocx(ctx, srcPath, key, htmlPath);
  } else if (ext === 'rtf') {
    convertRtf(readTextFile(srcPath), key, htmlPath);
  } else if (ext === 'odt') {
    await convertOdt(ctx, srcPath, key, htmlPath);
  } else {
    throw new Error('unsupported ext: ' + ext);
  }
  /* container scaffolding (zipDir writes mimetype first) */
  writeTextFile(outDir + '/mimetype', 'application/epub+zip');
  fs.mkdirSync(outDir + '/META-INF', true);
  writeTextFile(outDir + '/META-INF/container.xml',
    '<?xml version="1.0"?>\n<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">' +
    '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>' +
    '</rootfiles></container>');
  writeTextFile(oebps + '/content.opf',
    '<?xml version="1.0"?>\n<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">' +
    '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">' +
    '<dc:identifier id="uid">urn:howread:docconv-' + key + '</dc:identifier>' +
    '<dc:title>doc</dc:title>' +
    '<meta property="dcterms:modified">2020-01-01T00:00:00Z</meta></metadata>' +
    '<manifest><item id="c" href="index.html" media-type="application/xhtml+xml"/></manifest>' +
    '<spine><itemref idref="c"/></spine></package>');
  zipDir(outDir, outPath);
  console.info(`[DocConvert] epub container written: ${outPath}`);
  return outPath;
}

/**
 * ODT -> HTML (Android OdtContext parity): the content.xml inside the zip
 * carries the body as <text:p> paragraphs; unzip, extract and wrap them.
 */
async function convertOdt(ctx: Context, src: string, key: string, outPath: string): Promise<string> {
  /* 0.9.31 P1-7: unzip into OEBPS - content.xml and Pictures/ sit beside
   * index.html so <img src="Pictures/.."> resolves; the tree stays. */
  const dir: string = outPath.substring(0, outPath.lastIndexOf('/'));
  try {
    fs.rmdirSync(dir);
  } catch (e) {
    /* fresh */
  }
  fs.mkdirSync(dir, true);
  const zopt: zlib.Options = { level: 1 };
  await zlib.unzipFile(src, dir, zopt);
  const xml = readTextFile(dir + '/content.xml');
  let body = '';
  const re = /<text:p[^>]*>([\s\S]*?)<\/text:p>|<text:p[^>]*\/>/gi;
  let m = re.exec(xml);
  while (m !== null) {
    let seg = m[1] === undefined ? '' : m[1];
    seg = seg.replace(/<text:span[^>]*>/g, '').replace(/<\/text:span>/g, '')
      .replace(/<text:s\/>/g, ' ').replace(/<text:tab\/>/g, '\t');
    /* keep images: park <img> tags behind placeholders so the generic
     * tag-strip below cannot eat them */
    const imgs: string[] = [];
    seg = seg.replace(/<draw:image[^>]*xlink:href="([^"]+)"[^>]*\/>?/g,
      (mm: string, href: string): string => {
        imgs.push('<img src="' + href.replace(/^\//, '') + '">');
        return '\u0001' + (imgs.length - 1) + '\u0001';
      });
    const text = seg.replace(/<[^>]*>/g, '').trim();
    if (text.length > 0 || imgs.length > 0) {
      let line: string = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      line = line.replace(/\u0001(\d+)\u0001/g, (mm2: string, idx: string): string => {
        return imgs[parseInt(idx)] ?? '';
      });
      body += '<p>' + line + '</p>\n';
    }
    m = re.exec(xml);
  }
  if (body.length === 0) {
    throw new Error('odt: no paragraphs found');
  }
  const html = '<html><head><meta charset="utf-8"></head><body>\n' + body + '</body></html>';
  const f = fs.openSync(outPath, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
  fs.writeSync(f.fd, html);
  fs.closeSync(f);
  console.info(`[DocConvert] odt html written: ${outPath}`);
  return outPath;
}

/** True when the reader needs the conversion path for this extension. */
export function isConvertibleExt(ext: string): boolean {
  return ext === 'docx' || ext === 'rtf' || ext === 'odt';
}
