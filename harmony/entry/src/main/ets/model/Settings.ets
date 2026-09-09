import preferences from '@ohos.data.preferences';
import { storeName } from './Profiles';

/**
 * Global reader settings persisted via @ohos.data.preferences (sprint E).
 * Loaded by the Reader on open and edited from the Index settings tab and
 * the Reader settings panel; every change is saved immediately.
 */

/** Theme ids */
export const THEME_LIGHT: number = 0;
export const THEME_DARK: number = 1;
export const THEME_OLED: number = 2;
export const THEME_INK: number = 3;
export const THEME_NAMES: string[] = ['浅色', '深色', 'OLED', '墨'];

export interface ReaderSettings {
  /** 0=浅色 1=深色 2=OLED 3=墨 */
  theme: number;
  /** default render zoom (0.5..4) */
  zoom: number;
  /** 0=horizontal Swiper, 1=vertical List */
  scrollMode: number;
  /** 0=single 1=double 2=musician */
  pageMode: number;
  /** night invert (derived from theme for dark/oled) */
  nightMode: boolean;
  autoFlip: boolean;
  /** seconds between flips */
  autoFlipInterval: number;
  /** continuous auto-scroll in vertical scroll mode (A round 4) */
  autoScroll: boolean;
  /** auto-scroll speed, px per 50ms tick (2..10) */
  autoScrollSpeed: number;
  /** system brightness 1..255 */
  brightness: number;
  /** TTS rate 0.5..2.0 */
  ttsRate: number;
  /** TTS pitch 0.5..2.0 */
  ttsPitch: number;
  /* Sprint M1: voice + language */
  /** Selected TTS voice index (0 = auto/default) */
  ttsVoiceIndex: number;
  /** TTS language code ('' = auto, 'zh-CN', 'en-US', etc.) */
  ttsLang: string;
  /** RSVP speed in wpm 100..800 */
  rsvpWpm: number;
  /** base font size (em) for reflowable docs, points */
  fontSize: number;
  /** line-height multiplier for reflowable docs */
  lineHeight: number;
  /** body margin for reflowable docs, points */
  margin: number;
  /* ---- Sprint L: Full CSS typography ---- */
  /** First-line indent in em units (0-4), default 2 */
  textIndent: number;
  /** Drop cap on first paragraph letter */
  dropCapEnabled: boolean;
  /** Font family: 'default', 'serif', 'sans-serif', 'monospace' */
  fontFamily: string;

  /* ---- Sprint K: System integration ---- */
  /** App lock enabled (biometric/password) */
  appLockEnabled: boolean;
  /** App lock passcode (Phase 6; default 1234) */
  appLockPass: string;
  /** Reading reminder interval in minutes (0 = off, Phase 6) */
  readReminderMinutes: number;
  /** Blue-light filter overlay opacity 0..100 (Phase 7) */
  blueLightFilter: number;
  /** User background image path for the reader ('' = none, Phase 7) */
  bgImagePath: string;
  /** Proxy server host (empty = no proxy) */
  proxyHost: string;
  /** Proxy port */
  proxyPort: number;
  /** Proxy type: 0=none, 1=http, 2=socks5 */
  proxyType: number;
  /** Render quality: 0=fast(0.75x), 1=balanced(1.0x), 2=high(1.5x) */
  renderQuality: number;
  /** Page flip animation: 0=slide, 1=curl, 2=fade */
  flipAnimation: number;
  /** Main Tab bar at top instead of bottom (round 5, Android tapPositionTop) */
  tabPositionTop: boolean;
  /** Reader top bar clock+battery indicators (round 5) */
  showTopBarInfo: boolean;
  /** Try biometric unlock (fingerprint/face) before the passcode (round 5) */
  appLockBiometric: boolean;
  /* ---- Round 6 ---- */
  /** Preferred screen orientation: 0=auto, 1=portrait, 2=landscape */
  screenOrientation: number;
  /** Scan known device folders for books on startup */
  autoScanOnStart: boolean;
  /** Hide fully-read books from the library list */
  hideReadBooks: boolean;
  /** Cover-grid column count (2..4) */
  coverColumns: number;
  /** Desktop card content: 0=recent books, 1=starred books */
  cardSource: number;
  /** Desktop card entry count (1..3) */
  cardCount: number;
  /** In-app language: ''=system, 'zh', 'en' (restart to apply) */
  appLang: string;
}

const PREF_NAME = 'librera_settings';
const KEY_SETTINGS = 'settings';

let prefInstance: preferences.Preferences | undefined = undefined;
let prefInstanceStore: string = '';

async function getPrefs(ctx: Context): Promise<preferences.Preferences> {
  /* Round 6: profile-aware store name; re-open when the profile switches */
  const store: string = storeName(PREF_NAME);
  if (prefInstance === undefined || prefInstanceStore !== store) {
    prefInstanceStore = store;
    prefInstance = await preferences.getPreferences(ctx, store);
  }
  return prefInstance;
}

export function defaultSettings(): ReaderSettings {
  return {
    theme: 0,
    zoom: 1.5,
    scrollMode: 0,
    pageMode: 0,
    nightMode: false,
    autoFlip: false,
    autoFlipInterval: 3,
    autoScroll: false,
    autoScrollSpeed: 2,
    brightness: 128,
    ttsRate: 1.0,
    ttsPitch: 1.0,
    ttsVoiceIndex: 0,
    ttsLang: '',
    rsvpWpm: 300,
    fontSize: 16,
    lineHeight: 1.6,
    margin: 20,
    textIndent: 2,
    dropCapEnabled: false,
    fontFamily: 'default',
    appLockEnabled: false,
    appLockPass: '1234',
    readReminderMinutes: 0,
    blueLightFilter: 0,
    bgImagePath: '',
    proxyHost: '',
    proxyPort: 8080,
    proxyType: 0,
    renderQuality: 1,
    flipAnimation: 0,
    tabPositionTop: false,
    showTopBarInfo: false,
    appLockBiometric: false,
    screenOrientation: 0,
    autoScanOnStart: false,
    hideReadBooks: false,
    coverColumns: 3,
    cardSource: 0,
    cardCount: 3,
    appLang: ''
  };
}

export async function loadSettings(ctx: Context): Promise<ReaderSettings> {
  const d = defaultSettings();
  try {
    const prefs = await getPrefs(ctx);
    const json: string = await prefs.get(KEY_SETTINGS, '') as string;
    if (json.length === 0) {
      return d;
    }
    const raw: object = JSON.parse(json) as object;
    const r = raw as ReaderSettings;
    /* field-by-field merge with defaults so newer builds stay compatible */
    const s: ReaderSettings = {
      theme: typeof r.theme === 'number' ? r.theme : d.theme,
      zoom: typeof r.zoom === 'number' ? r.zoom : d.zoom,
      scrollMode: typeof r.scrollMode === 'number' ? r.scrollMode : d.scrollMode,
      pageMode: typeof r.pageMode === 'number' ? r.pageMode : d.pageMode,
      nightMode: r.nightMode === true,
      autoFlip: r.autoFlip === true,
      autoFlipInterval: typeof r.autoFlipInterval === 'number' ? r.autoFlipInterval : d.autoFlipInterval,
      autoScroll: r.autoScroll === true,
      autoScrollSpeed: typeof r.autoScrollSpeed === 'number' ? r.autoScrollSpeed : d.autoScrollSpeed,
      brightness: typeof r.brightness === 'number' ? r.brightness : d.brightness,
      ttsRate: typeof r.ttsRate === 'number' ? r.ttsRate : d.ttsRate,
      ttsPitch: typeof r.ttsPitch === 'number' ? r.ttsPitch : d.ttsPitch,
      ttsVoiceIndex: typeof r.ttsVoiceIndex === 'number' ? r.ttsVoiceIndex : d.ttsVoiceIndex,
      ttsLang: typeof r.ttsLang === 'string' ? r.ttsLang : d.ttsLang,
      rsvpWpm: typeof r.rsvpWpm === 'number' ? r.rsvpWpm : d.rsvpWpm,
      fontSize: typeof r.fontSize === 'number' ? r.fontSize : d.fontSize,
      lineHeight: typeof r.lineHeight === 'number' ? r.lineHeight : d.lineHeight,
      margin: typeof r.margin === 'number' ? r.margin : d.margin,
      textIndent: typeof r.textIndent === 'number' ? r.textIndent : d.textIndent,
      dropCapEnabled: r.dropCapEnabled === true,
      fontFamily: typeof r.fontFamily === 'string' ? r.fontFamily : d.fontFamily,
      appLockEnabled: r.appLockEnabled === true,
      appLockPass: typeof r.appLockPass === 'string' ? r.appLockPass : d.appLockPass,
      readReminderMinutes: typeof r.readReminderMinutes === 'number' ? r.readReminderMinutes : d.readReminderMinutes,
      blueLightFilter: typeof r.blueLightFilter === 'number' ? r.blueLightFilter : d.blueLightFilter,
      bgImagePath: typeof r.bgImagePath === 'string' ? r.bgImagePath : d.bgImagePath,
      proxyHost: typeof r.proxyHost === 'string' ? r.proxyHost : d.proxyHost,
      proxyPort: typeof r.proxyPort === 'number' ? r.proxyPort : d.proxyPort,
      proxyType: typeof r.proxyType === 'number' ? r.proxyType : d.proxyType,
      renderQuality: typeof r.renderQuality === 'number' ? r.renderQuality : d.renderQuality,
      flipAnimation: typeof r.flipAnimation === 'number' ? r.flipAnimation : d.flipAnimation,
      tabPositionTop: r.tabPositionTop === true,
      showTopBarInfo: r.showTopBarInfo === true,
      appLockBiometric: r.appLockBiometric === true,
      screenOrientation: typeof r.screenOrientation === 'number' ? r.screenOrientation : d.screenOrientation,
      autoScanOnStart: r.autoScanOnStart === true,
      hideReadBooks: r.hideReadBooks === true,
      coverColumns: typeof r.coverColumns === 'number' ? r.coverColumns : d.coverColumns,
      cardSource: typeof r.cardSource === 'number' ? r.cardSource : d.cardSource,
      cardCount: typeof r.cardCount === 'number' ? r.cardCount : d.cardCount,
      appLang: typeof r.appLang === 'string' ? r.appLang : d.appLang
    };
    return s;
  } catch (e) {
    return d;
  }
}

export async function saveSettings(ctx: Context, s: ReaderSettings): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_SETTINGS, JSON.stringify(s));
    await prefs.flush();
    console.info('[Settings] saved: theme=' + s.theme + ' zoom=' + s.zoom.toFixed(2) +
      ' mode=' + s.scrollMode + '/' + s.pageMode + ' font=' + s.fontSize +
      ' lh=' + s.lineHeight.toFixed(1) + ' m=' + s.margin);
  } catch (e) {
    console.error('[Settings] save failed');
  }
}

/** Build the user CSS for reflowable layout from font/line-height/margin settings. */
export function reflowCss(s: ReaderSettings): string {
  return 'body{margin:' + s.margin + 'pt;line-height:' + s.lineHeight.toFixed(2) + '}';
}
