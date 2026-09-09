import preferences from '@ohos.data.preferences';

/**
 * Round 6: multi-profile support (Android AppProfile parity, minimal invasion).
 *
 * A profile is a named storage scope. Every profile-aware model derives its
 * preferences store name (or playlist directory) from `storeName(base)`;
 * the default profile keeps the ORIGINAL store names, so existing user data
 * is the default profile with zero migration. Non-default profiles append
 * `_<profile>` to the store names and start empty.
 *
 * The active profile lives in the global (non-suffixed) `librera_profiles`
 * preferences and is mirrored into the module-level `currentProfile` for fast
 * synchronous lookups. `initProfiles` must be awaited once at app start.
 */

const PREF_NAME = 'librera_profiles';
const KEY_CURRENT = 'current';
const KEY_LIST = 'list';
const SEP = '_';

export const DEFAULT_PROFILE = 'default';

let currentProfile: string = DEFAULT_PROFILE;
let initialized: boolean = false;

/** Load the persisted active profile (call once at app start). */
export async function initProfiles(ctx: Context): Promise<void> {
  if (initialized) {
    return;
  }
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    const saved: string = await prefs.get(KEY_CURRENT, DEFAULT_PROFILE) as string;
    if (saved.length > 0) {
      currentProfile = saved;
    }
  } catch (e) {
    /* fall back to the default profile */
  }
  initialized = true;
}

/** Active profile name. */
export function getCurrentProfile(): string {
  return currentProfile;
}

/** Store name for `base` under the active profile (default keeps base). */
export function storeName(base: string): string {
  return currentProfile === DEFAULT_PROFILE ? base : base + SEP + currentProfile;
}

/** List all profile names (the default is always first). */
export async function listProfiles(ctx: Context): Promise<string[]> {
  const out: string[] = [DEFAULT_PROFILE];
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    const raw: string = await prefs.get(KEY_LIST, '[]') as string;
    const arr: string[] = JSON.parse(raw) as string[];
    for (let i = 0; i < arr.length; i++) {
      if (arr[i] !== DEFAULT_PROFILE && out.indexOf(arr[i]) < 0) {
        out.push(arr[i]);
      }
    }
  } catch (e) {
    /* just the default */
  }
  return out;
}

function validProfileName(name: string): string {
  const t: string = name.replace(/[^0-9A-Za-z\u4e00-\u9fa5_-]/g, '').trim();
  return t.length > 0 && t.length <= 24 ? t : '';
}

/** Create a new (empty) profile. Returns false on invalid/duplicate names. */
export async function createProfile(ctx: Context, name: string): Promise<boolean> {
  const n: string = validProfileName(name);
  if (n.length === 0) {
    return false;
  }
  const all: string[] = await listProfiles(ctx);
  if (all.indexOf(n) >= 0) {
    return false;
  }
  all.push(n);
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    await prefs.put(KEY_LIST, JSON.stringify(all));
    await prefs.flush();
    return true;
  } catch (e) {
    return false;
  }
}

/** Switch the active profile; callers must reload settings + library after. */
export async function switchProfile(ctx: Context, name: string): Promise<boolean> {
  const all: string[] = await listProfiles(ctx);
  if (all.indexOf(name) < 0) {
    return false;
  }
  currentProfile = name;
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    await prefs.put(KEY_CURRENT, name);
    await prefs.flush();
  } catch (e) {
    /* in-memory switch still applies for this session */
  }
  return true;
}

/** Delete a non-default profile (its data becomes unreachable, not merged). */
export async function deleteProfile(ctx: Context, name: string): Promise<boolean> {
  if (name === DEFAULT_PROFILE) {
    return false;
  }
  const all: string[] = await listProfiles(ctx);
  const idx: number = all.indexOf(name);
  if (idx < 0) {
    return false;
  }
  all.splice(idx, 1);
  try {
    const prefs = await preferences.getPreferences(ctx, PREF_NAME);
    await prefs.put(KEY_LIST, JSON.stringify(all));
    if (currentProfile === name) {
      currentProfile = DEFAULT_PROFILE;
      await prefs.put(KEY_CURRENT, DEFAULT_PROFILE);
    }
    await prefs.flush();
    return true;
  } catch (e) {
    return false;
  }
}
