import preferences from '@ohos.data.preferences';
import { AiConfig, PROTOCOL_OPENAI, loadAiConfig, saveAiConfig } from './AiClient';

/**
 * Named AI vendor profiles (Android AiVendors 2026-09-26 parity): the saved
 * list + an active-name pointer are the single source of truth. Requests
 * resolve through the pointer; the legacy single flat config is migrated
 * under the name "default" on first use. Keys stay on-device: the sync
 * payload (exportAiPayload) strips them, deletion keeps a per-device
 * tombstone so a sync re-import cannot resurrect a deleted profile.
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

/** Sync payload: profiles WITHOUT keys + the active name (Android
 * exportAi parity - the key never leaves the device through sync). */
export async function exportAiPayload(ctx: Context): Promise<string> {
  const list = await listVendors(ctx);
  const vendors: Record<string, Object>[] = [];
  for (let i = 0; i < list.length; i++) {
    const e = list[i];
    const v: Record<string, Object> = {
      'name': e.name, 'protocol': e.protocol, 'url': e.url,
      'model': e.model, 'maxTokens': e.maxTokens, 'thinking': e.thinking
    };
    vendors.push(v);
  }
  const o: Record<string, Object> = {
    'active': await activeVendorName(ctx),
    'vendors': vendors
  };
  return JSON.stringify(o);
}

/** Two-way merge of stripped sync payloads (Android mergeAi no-base
 * fallback): local-only names survive, remote overwrites any name whose
 * content differs, keys are never part of the payload. */
export function mergeAiPayloads(local: string, remote: string): string {
  try {
    const l = JSON.parse(local) as Record<string, Object>;
    const r = JSON.parse(remote) as Record<string, Object>;
    const lv = (l['vendors'] as AiVendorEntry[]) ?? [];
    const rv = (r['vendors'] as AiVendorEntry[]) ?? [];
    const out: AiVendorEntry[] = [];
    const seen: Record<string, boolean> = {};
    for (let i = 0; i < rv.length; i++) {
      seen[rv[i].name] = true;
      out.push(rv[i]);
    }
    for (let i = 0; i < lv.length; i++) {
      if (!seen[lv[i].name]) {
        out.push(lv[i]);
      }
    }
    const active: string = typeof r['active'] === 'string' && (r['active'] as string).length > 0
      ? r['active'] as string : (typeof l['active'] === 'string' ? l['active'] as string : '');
    const o: Record<string, Object> = { 'active': active, 'vendors': out };
    return JSON.stringify(o);
  } catch (e) {
    return local;
  }
}

/** Import a (merged) payload: per-name upsert, skip this device's
 * tombstones, preserve the local key when the incoming entry is keyless
 * (payloads never carry keys), adopt the remote active when unset. */
export async function importAiPayload(ctx: Context, json: string): Promise<void> {
  try {
    const o = JSON.parse(json) as Record<string, Object>;
    const rv = (o['vendors'] as AiVendorEntry[]) ?? [];
    const del = parseDeleted(await rawDeleted(ctx));
    const list = await listVendors(ctx);
    for (let i = 0; i < rv.length; i++) {
      const v = rv[i];
      if (typeof v.name !== 'string' || v.name.length === 0 || del.indexOf(v.name) >= 0) {
        continue;
      }
      let cur: AiVendorEntry | null = null;
      for (let k = 0; k < list.length; k++) {
        if (list[k].name === v.name) {
          cur = list[k];
          break;
        }
      }
      const e: AiVendorEntry = {
        name: v.name,
        protocol: typeof v.protocol === 'string' && v.protocol.length > 0 ? v.protocol : PROTOCOL_OPENAI,
        url: typeof v.url === 'string' ? v.url : '',
        key: cur !== null ? cur.key : '',
        model: typeof v.model === 'string' ? v.model : '',
        maxTokens: typeof v.maxTokens === 'number' && v.maxTokens > 0 ? v.maxTokens : 4096,
        thinking: v.thinking === true
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
    const active = typeof o['active'] === 'string' ? o['active'] as string : '';
    if (active.length > 0 && del.indexOf(active) < 0 && (await activeVendorName(ctx)).length === 0) {
      await putString(ctx, K_ACTIVE, active);
    }
    console.info('[AiVendors] imported payload: ' + rv.length + ' vendors');
  } catch (e) {
    console.error('[AiVendors] import failed');
  }
}
