import fs from '@ohos.file.fs';
import picker from '@ohos.file.picker';
import { Bookmark } from './Bookmarks';
import { AiNote } from './Notes';

/**
 * Notes/bookmarks export (Android parity 2026-09-03: TXT/Markdown with a
 * 位置 line). The Reader bookmark panel exports the current book's
 * bookmarks + AI notes. Primary target is a user-picked location via
 * DocumentViewPicker; on failure it falls back to <filesDir>/export/.
 */

export interface ExportData {
  /** document title shown in the header */
  docTitle: string;
  bookmarks: Bookmark[];
  notes: AiNote[];
}

function fmtTime(iso: string): string {
  return iso.length >= 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
}

function posOf(page: number): string {
  return page > 0 ? '\u7B2C ' + (page + 1) + ' \u9875' : '\u5168\u4E66';
}

/** Plain-text export, one entry per block with an explicit 位置 line. */
export function buildExportText(data: ExportData, markdown: boolean): string {
  const head = (data.docTitle.length > 0 ? '\u300A' + data.docTitle + '\u300B' : '') +
    '\u9605\u8BFB\u7B14\u8BB0\u5BFC\u51FA';
  const lines: string[] = [];
  if (markdown) {
    lines.push('# ' + head);
    lines.push('');
    lines.push('- \u5BFC\u51FA\u65F6\u95F4\uFF1A' + fmtTime(new Date().toISOString()));
    lines.push('');
    lines.push('## \u4E66\u7B7E\uFF08' + data.bookmarks.length + '\uFF09');
    lines.push('');
    if (data.bookmarks.length === 0) {
      lines.push('- \u6682\u65E0');
    }
    for (let i = 0; i < data.bookmarks.length; i++) {
      const b = data.bookmarks[i];
      const label = b.label.length > 0 ? b.label : '\u4E66\u7B7E';
      lines.push((i + 1) + '. **' + posOf(b.page) + '** \u2014 ' + label +
        '\uFF08' + fmtTime(b.date) + '\uFF09');
    }
    lines.push('');
    lines.push('## AI \u7B14\u8BB0\uFF08' + data.notes.length + '\uFF09');
    lines.push('');
    if (data.notes.length === 0) {
      lines.push('- \u6682\u65E0');
    }
    for (let i = 0; i < data.notes.length; i++) {
      const n = data.notes[i];
      lines.push('- **[' + n.kind + '] ' + posOf(n.page) + '**\uFF08' + fmtTime(n.date) + '\uFF09');
      const body = n.text.split('\n');
      for (let j = 0; j < body.length; j++) {
        lines.push('  > ' + body[j]);
      }
    }
  } else {
    lines.push(head);
    lines.push('\u5BFC\u51FA\u65F6\u95F4\uFF1A' + fmtTime(new Date().toISOString()));
    lines.push('');
    lines.push('== \u4E66\u7B7E\uFF08' + data.bookmarks.length + '\uFF09==');
    if (data.bookmarks.length === 0) {
      lines.push('\uFF08\u6682\u65E0\uFF09');
    }
    for (let i = 0; i < data.bookmarks.length; i++) {
      const b = data.bookmarks[i];
      const label = b.label.length > 0 ? b.label : '\u4E66\u7B7E';
      lines.push('[' + (i + 1) + '] \u4F4D\u7F6E\uFF1A' + posOf(b.page) +
        '\u3000\u65F6\u95F4\uFF1A' + fmtTime(b.date));
      lines.push('    ' + label);
    }
    lines.push('');
    lines.push('== AI \u7B14\u8BB0\uFF08' + data.notes.length + '\uFF09==');
    if (data.notes.length === 0) {
      lines.push('\uFF08\u6682\u65E0\uFF09');
    }
    for (let i = 0; i < data.notes.length; i++) {
      const n = data.notes[i];
      lines.push('[' + n.kind + '] \u4F4D\u7F6E\uFF1A' + posOf(n.page) +
        '\u3000\u65F6\u95F4\uFF1A' + fmtTime(n.date));
      const body = n.text.split('\n');
      for (let j = 0; j < body.length; j++) {
        lines.push('    ' + body[j]);
      }
    }
  }
  lines.push('');
  return lines.join('\n');
}

function sanitizeName(title: string): string {
  let t = title.length > 0 ? title : 'notes';
  t = t.replace(/[^0-9A-Za-z\u4e00-\u9fa5._-]+/g, '_');
  return t.length > 40 ? t.substring(0, 40) : t;
}

/**
 * Export to a user-chosen file (DocumentViewPicker SAVE). Returns a toast
 * message; '' means the user cancelled. On picker failure falls back to
 * <filesDir>/export/<name>.
 */
export async function exportNotesFile(ctx: Context, data: ExportData, markdown: boolean): Promise<string> {
  const text = buildExportText(data, markdown);
  const name = sanitizeName(data.docTitle) + '-notes.' + (markdown ? 'md' : 'txt');
  try {
    const dv = new picker.DocumentViewPicker();
    const opts = new picker.DocumentSaveOptions();
    opts.newFileNames = [name];
    const res = await dv.save(opts);
    if (res !== undefined && res.length > 0) {
      const f = fs.openSync(res[0], fs.OpenMode.READ_WRITE | fs.OpenMode.TRUNC);
      fs.writeSync(f.fd, text);
      fs.closeSync(f);
      return '\u5DF2\u5BFC\u51FA\uFF1A' + res[0];
    }
    return '';
  } catch (e) {
    /* fall through to sandbox export */
  }
  try {
    const dir = ctx.filesDir + '/export';
    try {
      if (!fs.accessSync(dir)) {
        fs.mkdirSync(dir);
      }
    } catch (e2) {
      /* dir already exists */
    }
    const target = dir + '/' + name;
    const f2 = fs.openSync(target, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.TRUNC);
    fs.writeSync(f2.fd, text);
    fs.closeSync(f2);
    return '\u5DF2\u5BFC\u51FA\u5230\u5E94\u7528\u76EE\u5F55\uFF1A' + target;
  } catch (e3) {
    console.error('[Export] notes export failed');
    return '\u5BFC\u51FA\u5931\u8D25\uFF1A' + String(e3);
  }
}
