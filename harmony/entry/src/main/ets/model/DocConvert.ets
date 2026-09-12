import fs from '@ohos.file.fs';
import zlib from '@ohos.zlib';
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

function wrapHtml(body: string): string {
  /* empty title: MuPDF's html handler renders the <title> text into the body */
  return '<html><head><meta charset="utf-8"><title></title></head><body>' + body + '</body></html>';
}

/* ------------------------------------------------------------------ */
/* DOCX: unzip with @ohos.zlib, scan word/document.xml for paragraphs  */
/* ------------------------------------------------------------------ */

/** Pull the text of one <w:t ...>...</w:t> run starting at tagOpen index. */
function scanDocxXml(xml: string): string {
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
  const workDir: string = ctx.cacheDir + '/converted/unzip_' + key;
  try {
    fs.mkdirSync(workDir, true);
  } catch (e) {
    /* already exists */
  }
  await zlib.decompressFile(src, workDir);
  const xmlPath: string = workDir + '/word/document.xml';
  const xml: string = readTextFile(xmlPath);
  /* find the document body (skip headers/footers references) */
  const bodyStart: number = xml.indexOf('<w:body>');
  const xmlBody: string = bodyStart >= 0 ? xml.substring(bodyStart) : xml;
  const paras: string = scanDocxXml(xmlBody);
  writeTextFile(outPath, wrapHtml(paras.length > 0 ? paras : '<p>(empty document)</p>'));
  /* best-effort cleanup of the unzipped tree */
  try {
    fs.unlinkSync(xmlPath);
    fs.rmdirSync(workDir + '/word');
    fs.rmdirSync(workDir + '/_rels');
    fs.rmdirSync(workDir + '/docProps');
    fs.rmdirSync(workDir);
  } catch (e) {
    /* cacheDir is system-managed; leftovers are harmless */
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
  const outPath: string = dir + '/' + key + '.html';
  try {
    if (fs.statSync(outPath).size > 0) {
      console.info(`[DocConvert] cache hit: ${outPath}`);
      return outPath;
    }
  } catch (e) {
    /* not cached yet */
  }
  if (ext === 'docx') {
    return await convertDocx(ctx, srcPath, key, outPath);
  }
  if (ext === 'rtf') {
    return convertRtf(readTextFile(srcPath), key, outPath);
  }
  throw new Error('unsupported ext: ' + ext);
}

/** True when the reader needs the conversion path for this extension. */
export function isConvertibleExt(ext: string): boolean {
  return ext === 'docx' || ext === 'rtf';
}
