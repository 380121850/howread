import preferences from '@ohos.data.preferences';

/**
 * Reading statistics (Phase 3): total / today / month reading time and pages.
 * Backed by @ohos.data.preferences; the Reader reports 30s chunks + pages,
 * the Index dashboard renders 5 stat cards + a bar chart.
 */

export interface DayStat {
  /** YYYY-MM-DD */
  day: string;
  /** reading ms on that day */
  ms: number;
  /** pages flipped on that day */
  pages: number;
}

export interface MonthStat {
  /** YYYY-MM */
  month: string;
  /** reading ms in that month */
  ms: number;
}

export interface ReadingStatsData {
  /** total reading ms across all time */
  totalMs: number;
  /** today reading ms */
  todayMs: number;
  /** current month reading ms */
  monthMs: number;
  /** pages flipped today */
  todayPages: number;
  /** reading speed today: pages per minute */
  speedPpm: number;
}

interface StatsStore {
  totalMs: number;
  daily: DayStat[];
  monthly: MonthStat[];
}

const PREF_NAME = 'librera_stats';
const KEY_STATS = 'reading_stats';

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

function pad2(n: number): string {
  return n < 10 ? '0' + n : '' + n;
}

function todayKey(): string {
  const d = new Date();
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
}

function monthKey(): string {
  const d = new Date();
  return d.getFullYear() + '-' + pad2(d.getMonth() + 1);
}

function emptyStore(): StatsStore {
  return { totalMs: 0, daily: [], monthly: [] };
}

async function loadStore(ctx: Context): Promise<StatsStore> {
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_STATS, '') as string;
    if (json.length === 0) {
      return emptyStore();
    }
    const raw = JSON.parse(json) as StatsStore;
    const store: StatsStore = {
      totalMs: typeof raw.totalMs === 'number' ? raw.totalMs : 0,
      daily: Array.isArray(raw.daily) ? raw.daily as DayStat[] : [],
      monthly: Array.isArray(raw.monthly) ? raw.monthly as MonthStat[] : []
    };
    return store;
  } catch (e) {
    return emptyStore();
  }
}

async function saveStore(ctx: Context, store: StatsStore): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_STATS, JSON.stringify(store));
    await prefs.flush();
  } catch (e) {
    console.error('[Stats] save failed');
  }
}

/** Accumulate reading time (the Reader reports 30s chunks). */
export async function recordReadTime(ctx: Context, ms: number): Promise<void> {
  try {
    const store = await loadStore(ctx);
    store.totalMs += ms;
    const tk = todayKey();
    let found = false;
    for (let i = 0; i < store.daily.length; i++) {
      if (store.daily[i].day === tk) {
        store.daily[i].ms += ms;
        found = true;
        break;
      }
    }
    if (!found) {
      store.daily.push({ day: tk, ms: ms, pages: 0 });
    }
    const mk = monthKey();
    found = false;
    for (let i = 0; i < store.monthly.length; i++) {
      if (store.monthly[i].month === mk) {
        store.monthly[i].ms += ms;
        found = true;
        break;
      }
    }
    if (!found) {
      store.monthly.push({ month: mk, ms: ms });
    }
    await saveStore(ctx, store);
    console.info(`[Stats] record ${ms}ms, total=${store.totalMs}`);
  } catch (e) {
    console.error('[Stats] recordReadTime failed');
  }
}

/** Accumulate pages flipped today (the Reader reports on each page change). */
export async function recordPagesRead(ctx: Context, pages: number): Promise<void> {
  try {
    const store = await loadStore(ctx);
    const tk = todayKey();
    for (let i = 0; i < store.daily.length; i++) {
      if (store.daily[i].day === tk) {
        store.daily[i].pages += pages;
        break;
      }
    }
    await saveStore(ctx, store);
  } catch (e) {
    console.error('[Stats] recordPagesRead failed');
  }
}

/** Aggregate stats for the dashboard cards. */
export async function getReadingStats(ctx: Context): Promise<ReadingStatsData> {
  const store = await loadStore(ctx);
  const tk = todayKey();
  const mk = monthKey();
  let todayMs = 0;
  let todayPages = 0;
  let monthMs = 0;
  for (let i = 0; i < store.daily.length; i++) {
    if (store.daily[i].day === tk) {
      todayMs = store.daily[i].ms;
      todayPages = store.daily[i].pages;
      break;
    }
  }
  for (let i = 0; i < store.monthly.length; i++) {
    if (store.monthly[i].month === mk) {
      monthMs = store.monthly[i].ms;
      break;
    }
  }
  const minutes = todayMs / 60000;
  const speed = minutes > 0 ? Math.round(todayPages / minutes) : 0;
  return {
    totalMs: store.totalMs,
    todayMs: todayMs,
    monthMs: monthMs,
    todayPages: todayPages,
    speedPpm: speed
  };
}

/** Daily stats for the last N days (most recent last, missing days zero-filled). */
export async function getDailyStats(ctx: Context, days: number): Promise<DayStat[]> {
  const store = await loadStore(ctx);
  const result: DayStat[] = [];
  const now = new Date();
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth(), now.getDate() - i);
    const key = d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
    let ms = 0;
    let pages = 0;
    for (let j = 0; j < store.daily.length; j++) {
      if (store.daily[j].day === key) {
        ms = store.daily[j].ms;
        pages = store.daily[j].pages;
        break;
      }
    }
    result.push({ day: key, ms: ms, pages: pages });
  }
  return result;
}

/** Monthly stats for the last N months (most recent last, missing months zero-filled). */
export async function getMonthlyStats(ctx: Context, months: number): Promise<MonthStat[]> {
  const store = await loadStore(ctx);
  const result: MonthStat[] = [];
  const now = new Date();
  for (let i = months - 1; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
    const key = d.getFullYear() + '-' + pad2(d.getMonth() + 1);
    let ms = 0;
    for (let j = 0; j < store.monthly.length; j++) {
      if (store.monthly[j].month === key) {
        ms = store.monthly[j].ms;
        break;
      }
    }
    result.push({ month: key, ms: ms });
  }
  return result;
}
