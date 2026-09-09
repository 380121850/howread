import http from '@ohos.net.http';
import { loadProxy, proxyOpt } from './HttpUtil';

/**
 * Minimal OPDS catalog client (Phase 5): fetch an Atom/OPDS feed and extract
 * navigation (subsection) + acquisition (book) entries. Parsing is string
 * based (matching the existing WebDAV client style) — sufficient for the
 * well-formed feeds used by Gutenberg / Standard Ebooks / CBETA.
 */

export interface OpdsEntry {
  /** entry <title> */
  title: string;
  /** first meaningful <link> href */
  href: string;
  /** link type (e.g. application/atom+xml;profile=opds-catalog) */
  type: string;
  /** entry <summary>/<content> text */
  summary: string;
  /** true = sub-catalog (navigate), false = book acquisition */
  isSub: boolean;
}

/** Built-in catalog presets shown on the OPDS home (aligned with Android: Gutenberg + CBETA). */
export const OPDS_PRESETS: OpdsEntry[] = [
  {
    title: 'Project Gutenberg',
    href: 'https://www.gutenberg.org/opds/',
    type: 'application/atom+xml;profile=opds-catalog',
    summary: '5 万+ 公版电子书（英文为主）',
    isSub: true
  },
  {
    title: 'CBETA 电子佛典',
    href: 'https://www.cbeta.org/opds/',
    type: 'application/atom+xml;profile=opds-catalog',
    summary: '汉文佛典电子化（CBETA）',
    isSub: true
  }
];

function cleanTag(text: string): string {
  /* strip XML tags + decode common entities */
  let s = text.replace(/<[^>]*>/g, '').trim();
  s = s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#39;/g, "'");
  return s;
}

function extractTag(xml: string, tag: string): string {
  const start = xml.indexOf('<' + tag + '>');
  if (start < 0) {
    return '';
  }
  const end = xml.indexOf('</' + tag + '>', start);
  if (end < 0) {
    return '';
  }
  return xml.substring(start + tag.length + 2, end);
}

/** Resolve a possibly-relative OPDS link against the feed URL. */
function resolveHref(base: string, href: string): string {
  if (href.startsWith('http://') || href.startsWith('https://')) {
    return href;
  }
  if (href.startsWith('/')) {
    const slash = base.indexOf('/', base.indexOf('://') + 3);
    return slash > 0 ? base.substring(0, slash) + href : base + href;
  }
  const lastSlash = base.lastIndexOf('/');
  return lastSlash > base.indexOf('://') ? base.substring(0, lastSlash + 1) + href : base + href;
}

/** Fetch + parse an OPDS feed; returns entries (subs first). */
export async function fetchOpds(url: string, timeoutMs: number, ctx?: Context): Promise<OpdsEntry[]> {
  const req = http.createHttp();
  try {
    const proxy = proxyOpt(await loadProxy(ctx));
    const resp = await req.request(url, {
      method: http.RequestMethod.GET,
      header: {
        'User-Agent': 'Mozilla/5.0 (Linux; Android 12; HowRead) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/114.0.0.0 Mobile Safari/537.36',
        'Accept': 'application/atom+xml, application/xml, text/xml, */*'
      },
      connectTimeout: timeoutMs,
      readTimeout: timeoutMs,
      usingProxy: proxy
    });
    if (resp.responseCode !== 200) {
      console.warn(`[Opds] fetch ${url} -> ${resp.responseCode}`);
      return [];
    }
    const xml: string = String(resp.result);
    const subs: OpdsEntry[] = [];
    const books: OpdsEntry[] = [];
    let pos = 0;
    while (true) {
      const start = xml.indexOf('<entry', pos);
      if (start < 0) {
        break;
      }
      const end = xml.indexOf('</entry>', start);
      if (end < 0) {
        break;
      }
      const block = xml.substring(start, end + 8);
      pos = end + 8;
      const title = cleanTag(extractTag(block, 'title'));
      if (title.length === 0) {
        continue;
      }
      /* collect links */
      let href = '';
      let type = '';
      let isSub = false;
      let linkPos = 0;
      while (true) {
        const ls = block.indexOf('<link', linkPos);
        if (ls < 0) {
          break;
        }
        const le = block.indexOf('>', ls);
        if (le < 0) {
          break;
        }
        const linkTag = block.substring(ls, le + 1);
        linkPos = le + 1;
        const h = extractAttr(linkTag, 'href');
        const t = extractAttr(linkTag, 'type');
        const rel = extractAttr(linkTag, 'rel');
        if (h.length === 0) {
          continue;
        }
        if (href.length === 0) {
          href = h;
          type = t;
        }
        if (rel.indexOf('subsection') >= 0) {
          isSub = true;
          href = h;
          type = t;
        } else if (rel.indexOf('acquisition') >= 0 && !isSub) {
          href = h;
          type = t;
        }
      }
      if (href.length === 0) {
        continue;
      }
      const summary = cleanTag(extractTag(block, 'summary'));
      const entry: OpdsEntry = {
        title: title,
        href: resolveHref(url, href),
        type: type,
        summary: summary,
        isSub: isSub
      };
      if (isSub) {
        subs.push(entry);
      } else {
        books.push(entry);
      }
    }
    console.info(`[Opds] ${url}: ${subs.length} subs, ${books.length} books`);
    return subs.concat(books);
  } catch (e) {
    console.error(`[Opds] fetch failed: ${url}`);
    return [];
  } finally {
    req.destroy();
  }
}

function extractAttr(tag: string, name: string): string {
  const m = tag.match(new RegExp(name + '="([^"]*)"', 'i'));
  return m !== null && m[1] !== undefined ? m[1] : '';
}
