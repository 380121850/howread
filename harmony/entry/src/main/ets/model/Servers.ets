import preferences from '@ohos.data.preferences';

/**
 * Persisted OPDS / WebDAV server lists (Android hr05/hr06 parity).
 * Stored via @ohos.data.preferences; seeded with the built-in OPDS presets
 * on first launch. The WebDAV sync config (Sync.ets) stays independent.
 */

export interface OpdsServer {
  url: string;
  name: string;
}

export interface DavServer {
  url: string;
  name: string;
  user: string;
  password: string;
  /** trust self-signed certificates (stored, informational) */
  trusted: boolean;
}

const PREF_NAME = 'librera_servers';
const KEY_OPDS = 'opds';
const KEY_DAV = 'dav';
const KEY_SEEDED = 'seeded';

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

async function readRaw(ctx: Context, key: string): Promise<string> {
  try {
    const prefs = await getPrefs(ctx);
    return await prefs.get(key, '[]') as string;
  } catch (e) {
    return '[]';
  }
}

async function writeOpds(ctx: Context, list: OpdsServer[]): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_OPDS, JSON.stringify(list));
    await prefs.flush();
  } catch (e) {
    console.error('[Servers] write failed: ' + KEY_OPDS);
  }
}

async function writeDav(ctx: Context, list: DavServer[]): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_DAV, JSON.stringify(list));
    await prefs.flush();
  } catch (e) {
    console.error('[Servers] write failed: ' + KEY_DAV);
  }
}

/** Seed the built-in OPDS presets on first launch. */
async function ensureSeeded(ctx: Context): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const seeded: boolean = await prefs.get(KEY_SEEDED, false) as boolean;
    if (seeded) {
      return;
    }
    const seeds: OpdsServer[] = [
      { url: 'https://www.gutenberg.org/opds/', name: 'Project Gutenberg' },
      { url: 'https://standardebooks.org/opds/', name: 'Standard Ebooks' },
      { url: 'https://www.cbeta.org/opds/', name: 'CBETA \u7535\u5B50\u4F5B\u5178' }
    ];
    await prefs.put(KEY_OPDS, JSON.stringify(seeds));
    await prefs.put(KEY_SEEDED, true);
    await prefs.flush();
    console.info('[Servers] seeded OPDS presets');
  } catch (e) {
    console.error('[Servers] seed failed');
  }
}

export async function getOpdsServers(ctx: Context): Promise<OpdsServer[]> {
  await ensureSeeded(ctx);
  const json: string = await readRaw(ctx, KEY_OPDS);
  const list: OpdsServer[] = JSON.parse(json) as OpdsServer[];
  list.sort((a: OpdsServer, b: OpdsServer) => a.name.localeCompare(b.name));
  return list;
}

export async function saveOpdsServer(ctx: Context, server: OpdsServer, originalUrl: string): Promise<void> {
  const json: string = await readRaw(ctx, KEY_OPDS);
  const list: OpdsServer[] = JSON.parse(json) as OpdsServer[];
  let replaced = false;
  for (let i = 0; i < list.length; i++) {
    if (list[i].url === originalUrl) {
      list[i] = server;
      replaced = true;
      break;
    }
  }
  if (!replaced) {
    for (let i = 0; i < list.length; i++) {
      if (list[i].url === server.url) {
        list[i] = server;
        replaced = true;
        break;
      }
    }
  }
  if (!replaced) {
    list.push(server);
  }
  await writeOpds(ctx, list);
  console.info('[Servers] opds saved: ' + server.name);
}

export async function removeOpdsServer(ctx: Context, url: string): Promise<void> {
  const json: string = await readRaw(ctx, KEY_OPDS);
  const list: OpdsServer[] = JSON.parse(json) as OpdsServer[];
  const kept: OpdsServer[] = [];
  for (let i = 0; i < list.length; i++) {
    if (list[i].url !== url) {
      kept.push(list[i]);
    }
  }
  await writeOpds(ctx, kept);
  console.info('[Servers] opds removed: ' + url);
}

export async function getDavServers(ctx: Context): Promise<DavServer[]> {
  const json: string = await readRaw(ctx, KEY_DAV);
  const list: DavServer[] = JSON.parse(json) as DavServer[];
  list.sort((a: DavServer, b: DavServer) => a.name.localeCompare(b.name));
  return list;
}

export async function saveDavServer(ctx: Context, server: DavServer, originalUrl: string): Promise<void> {
  const json: string = await readRaw(ctx, KEY_DAV);
  const list: DavServer[] = JSON.parse(json) as DavServer[];
  let replaced = false;
  for (let i = 0; i < list.length; i++) {
    if (list[i].url === originalUrl) {
      list[i] = server;
      replaced = true;
      break;
    }
  }
  if (!replaced) {
    list.push(server);
  }
  await writeDav(ctx, list);
  console.info('[Servers] dav saved: ' + server.name);
}

export async function removeDavServer(ctx: Context, url: string): Promise<void> {
  const json: string = await readRaw(ctx, KEY_DAV);
  const list: DavServer[] = JSON.parse(json) as DavServer[];
  const kept: DavServer[] = [];
  for (let i = 0; i < list.length; i++) {
    if (list[i].url !== url) {
      kept.push(list[i]);
    }
  }
  await writeDav(ctx, kept);
  console.info('[Servers] dav removed: ' + url);
}
