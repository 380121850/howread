import preferences from '@ohos.data.preferences';
import { AiConfig, PROTOCOL_OPENAI, loadAiConfig, saveAiConfig } from './AiClient';

/**
 * Named AI vendor profiles (Android AiVendors 2026-09-26 parity): the saved
 * list + an active-name pointer are the single source of truth. Requests
 * resolve through the pointer; the legacy single flat config is migrated
 * under the name "default" on first use. Deletion keeps a per-device
 * tombstone so a sync re-import cannot resurrect a deleted profile. Since
 * 0.9.43 the sync payload carries api keys (android ProfileStateIO.exportAi
 * parity: the file lives only in the user's own WebDAV sync directory, the
 * same trust boundary as the plaintext WebDAV/SMB/SFTP passwords).
 */

export interface AiVendorEntry {
  name: string;
  protocol: string;
  url: string;
  key: string;
  model: string;
  maxTokens: number;
  thinking: boolean;
}

const PREF_NAME = 'librera_ai';
const K_VENDORS = 'vendors';
const K_ACTIVE = 'aiActive';
const K_DELETED = 'aiDeleted';

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

function parseList(json: string): AiVendorEntry[] {
  try {
    return JSON.parse(json) as AiVendorEntry[];
  } catch (e) {
    return [];
  }
}

function parseDeleted(json: string): string[] {
  try {
    return JSON.parse(json) as string[];
  } catch (e) {
    return [];
  }
}

export async function listVendors(ctx: Context): Promise<AiVendorEntry[]> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(K_VENDORS, '[]') as string;
    return parseList(json);
  } catch (e) {
    return [];
  }
}

export async function activeVendorName(ctx: Context): Promise<string> {
  try {
    const prefs = await getPrefs(ctx);
    return await prefs.get(K_ACTIVE, '') as string;
  } catch (e) {
    return '';
  }
}

async function putString(ctx: Context, key: string, value: string): Promise<void> {
  const prefs = await getPrefs(ctx);
  await prefs.put(key, value);
  await prefs.flush();
}

/** Active entry, migrating the legacy single config to "default" once. */
export async function activeVendor(ctx: Context): Promise<AiVendorEntry | null> {
  const name = await activeVendorName(ctx);
  const list = await listVendors(ctx);
  for (let i = 0; i < list.length; i++) {
    if (list[i].name === name) {
      return list[i];
    }
  }
  /* legacy migration: a configured single flat config becomes "default" */
  const cfg = await loadAiConfig(ctx);
  if (cfg.url.length > 0 && cfg.model.length > 0) {
    const e: AiVendorEntry = {
      name: 'default', protocol: cfg.protocol, url: cfg.url, key: cfg.key,
      model: cfg.model, maxTokens: cfg.maxTokens, thinking: cfg.thinking
    };
    await saveVendor(ctx, e);
    return e;
  }
  return null;
}

/** Upsert by name, make it active, clear its tombstone, and mirror the
 * entry into the legacy single-config blob so every reader keeps working. */
export async function saveVendor(ctx: Context, e: AiVendorEntry): Promise<void> {
  const list = await listVendors(ctx);
  const out: AiVendorEntry[] = [];
  let replaced = false;
  for (let i = 0; i < list.length; i++) {
    if (list[i].name === e.name) {
      out.push(e);
      replaced = true;
    } else {
      out.push(list[i]);
    }
  }
  if (!replaced) {
    out.push(e);
  }
  await putString(ctx, K_VENDORS, JSON.stringify(out));
  await putString(ctx, K_ACTIVE, e.name);
  const del = parseDeleted(await rawDeleted(ctx));
  const kept: string[] = [];
  for (let i = 0; i < del.length; i++) {
    if (del[i] !== e.name) {
      kept.push(del[i]);
    }
  }
  await putString(ctx, K_DELETED, JSON.stringify(kept));
  const cfg: AiConfig = {
    provider: e.name, protocol: e.protocol, url: e.url, key: e.key,
    model: e.model, maxTokens: e.maxTokens, thinking: e.thinking
  };
  await saveAiConfig(ctx, cfg);
}

async function rawDeleted(ctx: Context): Promise<string> {
  try {
    const prefs = await getPrefs(ctx);
    return await prefs.get(K_DELETED, '[]') as string;
  } catch (e) {
    return '[]';
  }
}

/** Delete a profile and tombstone the name on this device. Deleting the
 * active profile also clears the effective config's pointer. */
export async function deleteVendor(ctx: Context, name: string): Promise<void> {
  const list = await listVendors(ctx);
  const out: AiVendorEntry[] = [];
  for (let i = 0; i < list.length; i++) {
    if (list[i].name !== name) {
      out.push(list[i]);
    }
  }
  await putString(ctx, K_VENDORS, JSON.stringify(out));
  const del = parseDeleted(await rawDeleted(ctx));
  if (del.indexOf(name) < 0) {
    del.push(name);
  }
  await putString(ctx, K_DELETED, JSON.stringify(del));
  if ((await activeVendorName(ctx)) === name) {
    await putString(ctx, K_ACTIVE, '');
  }
  console.info('[AiVendors] deleted profile: ' + name);
}

/* ---- 0.9.40: android app-AI.json schema parity (ProfileStateIO.exportAi) ----
 * File shape: { apiKey, active, vendors: { name: { name, protocol, baseUrl,
 * model, maxTokens, thinking, apiKey? } } } — note the URL field is named
 * baseUrl on the wire (android AiVendors.java:127 parity) and vendors is an
 * OBJECT keyed by vendor name, not an array.
 * Key policy (0.9.43, android exportAi parity): vendor apiKeys AND the
 * active vendor's key are authored into the payload exactly like android
 * ("same policy as the plaintext network-password sync"); the merge still
 * preserves server-side key material verbatim and import ADOPTS an incoming
 * key into the local store so a restored vendor works immediately. */

function stable(v: Object): string {
  if (Array.isArray(v)) {
    const arr = v as Object[];
    const parts: string[] = [];
    for (let i = 0; i < arr.length; i++) {
      parts.push(stable(arr[i]));
    }
    return '[' + parts.join(',') + ']';
  }
  if (v !== null && typeof v === 'object') {
    const rec = v as Record<string, Object>;
    const keys = Object.keys(rec).sort();
    const parts: string[] = [];
    for (let i = 0; i < keys.length; i++) {
      const k = keys[i];
      parts.push(JSON.stringify(k) + ':' + stable(rec[k]));
    }
    return '{' + parts.join(',') + '}';
  }
  return JSON.stringify(v);
}

function vendStr(v: Record<string, Object>, k: string, d: string): string {
  const x = v[k];
  return typeof x === 'string' ? x as string : d;
}

/** Record<string,Object> index access yields Object in ArkTS — narrow it. */
function vendRec(v: Object | undefined): Record<string, Object> | undefined {
  if (v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v)) {
    return v as Record<string, Object>;
  }
  return undefined;
}

function vendorMapOf(v: Object | undefined): Record<string, Object> {
  if (v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v)) {
    return v as Record<string, Object>;
  }
  const e: Record<string, Object> = {};
  return e;
}

/** Android-schema sync payload: vendors keyed by name, baseUrl field,
 * api keys included (android ProfileStateIO.exportAi parity: the top-level
 * key mirrors the active vendor, every vendor entry carries its own). */
export async function exportAiPayload(ctx: Context): Promise<string> {
  const list = await listVendors(ctx);
  const activeName = await activeVendorName(ctx);
  const vendors: Record<string, Object> = {};
  let activeKey = '';
  for (let i = 0; i < list.length; i++) {
    const e = list[i];
    const v: Record<string, Object> = {
      'name': e.name, 'protocol': e.protocol, 'baseUrl': e.url,
      'model': e.model, 'maxTokens': e.maxTokens, 'thinking': e.thinking
    };
    if (e.key.length > 0) {
      v['apiKey'] = e.key;
    }
    if (e.name === activeName) {
      activeKey = e.key;
    }
    vendors[e.name] = v;
  }
  const o: Record<string, Object> = {
    'apiKey': activeKey,
    'active': activeName,
    'vendors': vendors
  };
  return JSON.stringify(o);
}

/** Android mergeAi parity: three-way per vendor name against the base
 * snapshot (single-side change wins, both-changed keeps local, deletions
 * propagate); no base → the historical two-way rule (local kept, a differing
 * remote copy wins). The top-level apiKey and every server-side vendor key
 * are preserved verbatim. */
export function mergeAiPayloads(local: string, remote: string, base?: string): string {
  try {
    const l = JSON.parse(local) as Record<string, Object>;
    const r = JSON.parse(remote) as Record<string, Object>;
    const lv = vendorMapOf(l['vendors']);
    const rv = vendorMapOf(r['vendors']);
    let bv: Record<string, Object> | null = null;
    if (base !== undefined && base.length > 0) {
      try {
        bv = vendorMapOf((JSON.parse(base) as Record<string, Object>)['vendors']);
      } catch (e) {
        bv = null;
      }
    }
    /* top-level apiKey: local empty → remote; both set → server wins */
    const lk = vendStr(l, 'apiKey', '');
    const rk = vendStr(r, 'apiKey', '');
    const outKey = rk.length > 0 ? rk : lk;
    const la = vendStr(l, 'active', '');
    const ra = vendStr(r, 'active', '');
    const outActive = ra.length > 0 ? ra : la;
    const names: string[] = [];
    const seen: Record<string, boolean> = {};
    const collect = (m: Record<string, Object>): void => {
      const ks = Object.keys(m);
      for (let i = 0; i < ks.length; i++) {
        if (seen[ks[i]] !== true) {
          seen[ks[i]] = true;
          names.push(ks[i]);
        }
      }
    };
    collect(lv);
    collect(rv);
    if (bv !== null) {
      collect(bv);
    }
    const vendors: Record<string, Object> = {};
    for (let i = 0; i < names.length; i++) {
      const name = names[i];
      const lo = vendRec(lv[name]);
      const ro = vendRec(rv[name]);
      const bo = bv !== null ? vendRec(bv[name]) : undefined;
      let keep: Record<string, Object> | undefined;
      if (bv === null) {
        if (ro !== undefined && (lo === undefined || stable(lo) !== stable(ro))) {
          keep = ro;
        } else {
          keep = lo;
        }
      } else {
        const localChanged = (lo === undefined) !== (bo === undefined) ||
          (lo !== undefined && bo !== undefined && stable(lo) !== stable(bo));
        const remoteChanged = (ro === undefined) !== (bo === undefined) ||
          (ro !== undefined && bo !== undefined && stable(ro) !== stable(bo));
        if (!remoteChanged) {
          keep = lo;            /* local edit / deletion wins */
        } else if (!localChanged) {
          keep = ro;            /* adopt the remote edit / deletion */
        } else {
          keep = lo;            /* both changed: local wins */
        }
      }
      if (keep === undefined) {
        continue;
      }
      /* key preservation: a copy authored here carries no key; if the remote
       * copy has one, carry it so android keeps its working key */
      if (keep !== ro && ro !== undefined) {
        const rk2 = vendStr(ro, 'apiKey', '');
        if (rk2.length > 0 && vendStr(keep, 'apiKey', '').length === 0) {
          const fixed = JSON.parse(JSON.stringify(keep)) as Record<string, Object>;
          fixed['apiKey'] = rk2;
          keep = fixed;
        }
      }
      vendors[name] = keep;
    }
    const o: Record<string, Object> = {
      'apiKey': outKey,
      'active': outActive,
      'vendors': vendors
    };
    return JSON.stringify(o);
  } catch (e) {
    return local;
  }
}

/** Import a merged android-schema payload: per-name upsert, skip this
 * device's tombstones, adopt an incoming apiKey into the local store (keep
 * the local key when the incoming entry is keyless), legacy top-level apiKey
 * backfills the keyless active entry, adopt the remote active when unset. */
export async function importAiPayload(ctx: Context, json: string): Promise<void> {
  try {
    const o = JSON.parse(json) as Record<string, Object>;
    const rv = vendorMapOf(o['vendors']);
    const del = parseDeleted(await rawDeleted(ctx));
    const list = await listVendors(ctx);
    const names = Object.keys(rv);
    for (let i = 0; i < names.length; i++) {
      const name = names[i];
      if (name.length === 0 || del.indexOf(name) >= 0) {
        continue;
      }
      const v = rv[name] as Record<string, Object>;
      let cur: AiVendorEntry | null = null;
      for (let k = 0; k < list.length; k++) {
        if (list[k].name === name) {
          cur = list[k];
          break;
        }
      }
      const proto = vendStr(v, 'protocol', '');
      const incomingKey = vendStr(v, 'apiKey', '');
      const e: AiVendorEntry = {
        name: name,
        protocol: proto.length > 0 ? proto : PROTOCOL_OPENAI,
        url: vendStr(v, 'baseUrl', ''),
        key: incomingKey.length > 0 ? incomingKey : (cur !== null ? cur.key : ''),
        model: vendStr(v, 'model', ''),
        maxTokens: typeof v['maxTokens'] === 'number' && (v['maxTokens'] as number) > 0
          ? v['maxTokens'] as number : 4096,
        thinking: v['thinking'] === true
      };
      let replaced = false;
      for (let k = 0; k < list.length; k++) {
        if (list[k].name === e.name) {
          list[k] = e;
          replaced = true;
          break;
        }
      }
      if (!replaced) {
        list.push(e);
      }
    }
    await putString(ctx, K_VENDORS, JSON.stringify(list));
    /* legacy top-level apiKey backfills the keyless active entry */
    const fileKey = vendStr(o, 'apiKey', '');
    if (fileKey.length > 0) {
      const activeName = await activeVendorName(ctx);
      const list2 = await listVendors(ctx);
      for (let k = 0; k < list2.length; k++) {
        if (list2[k].name === activeName && list2[k].key.length === 0) {
          list2[k].key = fileKey;
          await putString(ctx, K_VENDORS, JSON.stringify(list2));
          break;
        }
      }
    }
    const active = vendStr(o, 'active', '');
    if (active.length > 0 && del.indexOf(active) < 0 && (await activeVendorName(ctx)).length === 0) {
      await putString(ctx, K_ACTIVE, active);
    }
    console.info('[AiVendors] imported payload: ' + names.length + ' vendors');
  } catch (e) {
    console.error('[AiVendors] import failed');
  }
}
