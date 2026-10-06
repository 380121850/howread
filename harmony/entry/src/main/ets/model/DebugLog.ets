import fs from '@ohos.file.fs';
import preferences from '@ohos.data.preferences';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { IS_DEBUG_BUILD } from './BuildInfo';

/**
 * 0.9.21: in-app debug log file (Android DebugLog parity, CHANGES 2026-10-04~06).
 * Captures ONLY event-level localization lines (tlog markers: open pipeline /
 * remote / long-press / translation) plus FATAL crash traces into
 * <filesDir>/debug-log.txt (2MB cap, auto-recreated). Deliberately tight:
 * chatty state/lifecycle logs stay in hilog only, keeping the file at a few KB
 * per repro session. The switch persists in its own preference store; debug
 * builds default ON, release builds default OFF (user override wins).
 */

const LOG_NAME: string = '/debug-log.txt';
const MAX_SIZE: number = 2 * 1024 * 1024;
const PREF_NAME: string = 'librera_debug';
const KEY_ENABLED: string = 'enabled';

let enabled: boolean = IS_DEBUG_BUILD;
let filesDirCache: string = '';

export function debugLogDefault(): boolean {
  return IS_DEBUG_BUILD;
}

/** Synchronous init (EntryAbility.onCreate): load the persisted switch. */
export function debugLogInit(ctx: Context): void {
  try {
    filesDirCache = ctx.filesDir;
    const prefs: preferences.Preferences = preferences.getPreferencesSync(ctx, { name: PREF_NAME });
    enabled = prefs.getSync(KEY_ENABLED, IS_DEBUG_BUILD) as boolean;
  } catch (e) {
    enabled = IS_DEBUG_BUILD;
  }
  hilog.info(0x0000, 'Librera', 'debugLog init: %{public}s', enabled ? 'on' : 'off');
}

export function debugLogIsOn(): boolean {
  return enabled;
}

export async function debugLogSetEnabled(ctx: Context, on: boolean): Promise<void> {
  enabled = on;
  try {
    const prefs: preferences.Preferences = preferences.getPreferencesSync(ctx, { name: PREF_NAME });
    await prefs.put(KEY_ENABLED, on);
    await prefs.flush();
  } catch (e) { /* keep the in-memory flag */ }
}

export function debugLogWrite(tag: string, line: string): void {
  if (!enabled || filesDirCache.length === 0) {
    return;
  }
  try {
    const path: string = filesDirCache + LOG_NAME;
    try {
      const st: fs.Stat = fs.statSync(path);
      if (st.size > MAX_SIZE) {
        fs.unlinkSync(path);
      }
    } catch (e) { /* first write */ }
    const stamp: string = new Date().toISOString();
    const f: fs.File = fs.openSync(path, fs.OpenMode.READ_WRITE | fs.OpenMode.CREATE | fs.OpenMode.APPEND);
    fs.writeSync(f.fd, stamp + ' [' + tag + '] ' + line + '\n');
    fs.closeSync(f);
  } catch (e) { /* never break the caller */ }
}

export function debugLogPath(ctx: Context): string {
  return ctx.filesDir + LOG_NAME;
}

export async function debugLogClear(ctx: Context): Promise<void> {
  try {
    fs.unlinkSync(ctx.filesDir + LOG_NAME);
  } catch (e) { /* not there yet */ }
}

export async function debugLogBytes(ctx: Context): Promise<number> {
  try {
    const st: fs.Stat = fs.statSync(ctx.filesDir + LOG_NAME);
    return st.size;
  } catch (e) {
    return 0;
  }
}
