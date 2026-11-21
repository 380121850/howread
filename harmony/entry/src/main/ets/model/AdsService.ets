/**
 * 0.9.69: Ads facade (Android ADS.java + HuaweiAdsProvider parity).
 *
 * The three slot ids live in ProChannel; EMPTY ids keep the integration
 * "wired but dormant" (Android HuaweiAdsProvider empty-ID guard parity):
 * every entry returns before any Ads Kit call. canIUse + try/catch wrap the
 * SDK surface because the emulator has no ads service (loadAd fails with
 * 21800003) — the dormant default keeps debug builds emulator-safe.
 *
 * Ad types (HarmonyOS Ads Kit): banner = AutoAdComponent on the shelf
 * (Android MainTabs2 adFrame parity), interstitial = adType 12 shown after
 * the reader closes (Android AdsFragmentActivity.closeActivity parity),
 * reward = adType 7 from the reader book menu with the 2h ad-free window
 * (Android showRewardVideo / ADS.isRewardActivated parity). The reward /
 * close events arrive through the system common-event channel published by
 * the ads service — the subscriber MUST be registered before showAd.
 */
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import commonEventManager from '@ohos.commonEventManager';
import display from '@ohos.display';
import { BusinessError } from '@ohos.base';
import { abilityAccessCtrl } from '@kit.AbilityKit';
import { advertising, identifier } from '@kit.AdsKit';
import { AD_BANNER_ID, AD_INTERSTITIAL_ID, AD_REWARD_ID } from './ProChannel';
import { isProUnlocked } from './ProState';
import { tlog } from './TestLog';

/* Android ADS.java policy constants parity */
const INTERSTITIAL_DELAY_SEC = 300;
const REWARD_WINDOW_MS = 2 * 60 * 60 * 1000;

const REWARD_EVENT = 'com.huawei.hms.pps.action.PPS_REWARD_STATUS_CHANGED';
const REWARD_PUBLISHER = 'com.huawei.hms.adsservice';
const KEY_REWARD_STATUS = 'reward_ad_status';
const OAID_ZERO = '00000000-0000-0000-0000-000000000000';

const PREF_NAME = 'librera_ads';
const KEY_REWARD_TIME = 'rewardShowTime';
const KEY_LAST_INTER = 'interstitialShowTime';

let prefInstance: preferences.Preferences | undefined = undefined;
let rewardShowTime: number = 0;
let lastInterstitialShow: number = 0;
let interstitialLoading: boolean = false;
let rewardSubscriber: commonEventManager.CommonEventSubscriber | undefined = undefined;
let consentAsked: boolean = false;

async function getPrefs(ctx: common.Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

async function saveTimes(ctx: common.Context): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_REWARD_TIME, rewardShowTime);
    await prefs.put(KEY_LAST_INTER, lastInterstitialShow);
    await prefs.flush();
  } catch (e) {
    /* memory-only fallback */
  }
}

/** App-start init: load the persisted ad timestamps (fire-and-forget). */
export async function initAds(ctx: common.Context): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    rewardShowTime = Number(await prefs.get(KEY_REWARD_TIME, 0));
    lastInterstitialShow = Number(await prefs.get(KEY_LAST_INTER, 0));
  } catch (e) {
    /* memory-only fallback */
  }
  ensureAdConsent(ctx as common.UIAbilityContext);
}

export function isAdConfigured(id: string): boolean {
  return id.length > 0;
}

export function adsSupported(): boolean {
  try {
    return canIUse('SystemCapability.Advertising.Ads');
  } catch (_e) {
    return false;
  }
}

/** Android AppsConfig.isShowAdsInApp parity: any slot configured, device
 * supported, and Pro not unlocked. Empty ids -> false everywhere (dormant). */
export function isShowAdsInApp(): boolean {
  return adsSupported()
    && (isAdConfigured(AD_BANNER_ID) || isAdConfigured(AD_INTERSTITIAL_ID) || isAdConfigured(AD_REWARD_ID))
    && !isProUnlocked();
}

/** 2h ad-free window active (Android ADS.isRewardActivated parity). */
export function isRewardActive(): boolean {
  return rewardShowTime > 0 && Date.now() - rewardShowTime < REWARD_WINDOW_MS;
}

export function rewardAvailable(): boolean {
  return adsSupported() && isAdConfigured(AD_REWARD_ID);
}

/** Banner slot visibility (Index shelf): dormant when the id is empty or
 * Pro is unlocked or the 2h reward window is active. */
export function bannerVisible(): boolean {
  return adsSupported() && isAdConfigured(AD_BANNER_ID) && !isProUnlocked() && !isRewardActive();
}

function screenW(): number {
  try {
    return display.getDefaultDisplaySync().width;
  } catch (_e) {
    return 720;
  }
}

function screenH(): number {
  try {
    return display.getDefaultDisplaySync().height;
  } catch (_e) {
    return 1280;
  }
}

/** Banner request params (adType 8); size = full width, ~1:6 ratio. */
export function bannerParams(): advertising.AdRequestParams {
  const w = screenW();
  const param: advertising.AdRequestParams = {
    adId: AD_BANNER_ID,
    adType: 8,
    adWidth: w,
    adHeight: Math.max(76, Math.round(w / 6))
  };
  return param;
}

export function loadOptions(): advertising.AdOptions {
  const opt: advertising.AdOptions = {};
  return opt;
}

export function displayOpts(): advertising.AdDisplayOptions {
  const opt: advertising.AdDisplayOptions = { mute: true };
  return opt;
}

export function bannerListener(): advertising.AdInteractionListener {
  const l: advertising.AdInteractionListener = {
    onStatusChanged: (status: string, ad: advertising.Advertisement, data: string): void => {
      tlog('ads_banner_event', status);
    }
  };
  return l;
}

/** Runtime OAID consent — requested only when ads are actually configured
 * (dormant builds never bother the user). */
export function ensureAdConsent(ctx: common.UIAbilityContext): void {
  if (consentAsked || !isShowAdsInApp()) {
    return;
  }
  consentAsked = true;
  try {
    const atManager = abilityAccessCtrl.createAtManager();
    atManager.requestPermissionsFromUser(ctx, ['ohos.permission.APP_TRACKING_CONSENT']).then((): void => {
      identifier.getOAID().then((oaid: string): void => {
        tlog('ads_oaid_ready', 'zero=' + (oaid === OAID_ZERO ? '1' : '0'));
      }).catch((_e: Object): void => {
        tlog('ads_oaid_fail');
      });
    }).catch((_e: Object): void => {
      tlog('ads_consent_fail');
    });
  } catch (_e) {
    tlog('ads_consent_fail');
  }
}

function unregisterRewardReceiver(): void {
  const sub = rewardSubscriber;
  rewardSubscriber = undefined;
  if (sub !== undefined) {
    try {
      commonEventManager.unsubscribe(sub, (_err: BusinessError): void => {
      });
    } catch (_e) {
      /* already gone */
    }
  }
}

function registerRewardReceiver(onEarned: () => void, ctx: common.Context): void {
  unregisterRewardReceiver();
  const info: commonEventManager.CommonEventSubscribeInfo = {
    events: [REWARD_EVENT],
    publisherBundleName: REWARD_PUBLISHER
  };
  commonEventManager.createSubscriber(info).then((sub: commonEventManager.CommonEventSubscriber): void => {
    rewardSubscriber = sub;
    commonEventManager.subscribe(sub, (_err: BusinessError, data: commonEventManager.CommonEventData): void => {
      let status = '';
      try {
        const params = data.parameters as Record<string, string>;
        status = params[KEY_REWARD_STATUS];
      } catch (_e) {
        return;
      }
      if (status === 'onAdReward') {
        rewardShowTime = Date.now();
        saveTimes(ctx);
        tlog('ads_reward_earned');
        onEarned();
      } else if (status === 'onAdClose') {
        unregisterRewardReceiver();
        tlog('ads_reward_closed');
      }
    });
  }).catch((_e: Object): void => {
    tlog('ads_reward_fail', 'sub');
  });
}

/** Rewarded ad (adType 7): subscribe the reward event channel BEFORE showAd,
 * grant the 2h ad-free window on onAdReward (Android showRewardedAd /
 * rewardShowTime parity). */
export function showRewardAd(ctx: common.UIAbilityContext, onEarned: () => void, onUnavailable: () => void): void {
  if (!rewardAvailable() || isProUnlocked()) {
    onUnavailable();
    return;
  }
  try {
    registerRewardReceiver(onEarned, ctx);
    const param: advertising.AdRequestParams = {
      adId: AD_REWARD_ID,
      adType: 7,
      adWidth: screenW(),
      adHeight: screenH()
    };
    const loader = new advertising.AdLoader(ctx);
    const listener: advertising.AdLoadListener = {
      onAdLoadSuccess: (ads: Array<advertising.Advertisement>): void => {
        if (ads.length === 0) {
          unregisterRewardReceiver();
          onUnavailable();
          return;
        }
        try {
          advertising.showAd(ads[0], displayOpts(), ctx);
          tlog('ads_reward_shown');
        } catch (e) {
          unregisterRewardReceiver();
          onUnavailable();
        }
      },
      onAdLoadFailure: (code: number, errorMsg: string): void => {
        unregisterRewardReceiver();
        tlog('ads_reward_fail', 'load=' + code);
        onUnavailable();
      }
    };
    loader.loadAd(param, loadOptions(), listener);
  } catch (e) {
    unregisterRewardReceiver();
    onUnavailable();
  }
}

/** Interstitial after the reader closes (Android closeActivity parity):
 * frequency-capped (300s) + suppressed during the 2h reward window. */
export function maybeShowInterstitial(ctx: common.UIAbilityContext): void {
  if (!adsSupported() || !isAdConfigured(AD_INTERSTITIAL_ID) || isProUnlocked() || isRewardActive()) {
    return;
  }
  if (interstitialLoading || Date.now() - lastInterstitialShow < INTERSTITIAL_DELAY_SEC * 1000) {
    return;
  }
  interstitialLoading = true;
  const param: advertising.AdRequestParams = {
    adId: AD_INTERSTITIAL_ID,
    adType: 12,
    adWidth: screenW(),
    adHeight: screenH()
  };
  try {
    const loader = new advertising.AdLoader(ctx);
    const listener: advertising.AdLoadListener = {
      onAdLoadSuccess: (ads: Array<advertising.Advertisement>): void => {
        interstitialLoading = false;
        if (ads.length === 0) {
          return;
        }
        try {
          advertising.showAd(ads[0], displayOpts(), ctx);
          lastInterstitialShow = Date.now();
          saveTimes(ctx);
          tlog('ads_interstitial_shown');
        } catch (e) {
          tlog('ads_interstitial_fail', 'show');
        }
      },
      onAdLoadFailure: (code: number, errorMsg: string): void => {
        interstitialLoading = false;
        tlog('ads_interstitial_fail', 'load=' + code);
      }
    };
    loader.loadAd(param, loadOptions(), listener);
  } catch (e) {
    interstitialLoading = false;
    tlog('ads_interstitial_fail', 'catch');
  }
}
