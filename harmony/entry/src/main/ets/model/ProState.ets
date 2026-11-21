/**
 * 0.9.69: Pro entitlement store (Android BillingManager/AppSP.iapProUnlocked
 * parity). Device-local preferences ('librera_pro'), never synced or backed
 * up (Android AppTemp 口径): unlocked flag + purchase time + order id.
 *
 * Debug builds are stub-unlocked (Android pro-flavor StubBillingDelegate
 * hard-true parity): the flag is ignored unless a simulated refund flips
 * debugForceLocked, which makes the locked gating verifiable on the emulator.
 * Release builds run the real IAP Kit flow for the NONCONSUMABLE product
 * howread_pro_unlock (Android huawei-flavor HuaweiBillingDelegate parity),
 * including the app-start ownership re-sync that adopts purchases and
 * revokes on refunds (Android syncOwnershipSilently parity).
 *
 * 模拟器限制：IAP Kit 不支持模拟器 — debug 桩路径保证模拟器/CI 不触达任何
 * iap.* 调用；release 真机沙盒测试见上架准备报告。
 */
import preferences from '@ohos.data.preferences';
import common from '@ohos.app.ability.common';
import util from '@ohos.util';
import { BusinessError } from '@ohos.base';
import { iap } from '@kit.IAPKit';
import { IAP_PRODUCT_ID } from './ProChannel';
import { IS_DEBUG_BUILD } from './BuildInfo';
import { tlog } from './TestLog';

const PREF_NAME = 'librera_pro';
const KEY_STATE = 'pro';
/** Android StubBillingDelegate STUB_ORDER_ID parity. */
const STUB_ORDER_ID = 'IAP-STUB-2026-8888';
const IAP_PRODUCT_OWNED = 1001860051;
const IAP_USER_CANCELED = 1001860000;

export type ProPurchaseKind = 'purchased' | 'owned' | 'restored' | 'none' | 'cancel' | 'fail';

interface ProStoreState {
  unlocked: boolean;
  purchaseTime: number;
  orderId: string;
  debugForceLocked: boolean;
}

interface PurchasePayload {
  purchaseToken?: string;
  purchaseOrderId?: string;
  purchaseTime?: number;
}

let cached: ProStoreState = {
  unlocked: false,
  purchaseTime: 0,
  orderId: '',
  debugForceLocked: false
};

let prefInstance: preferences.Preferences | undefined = undefined;

async function getPrefs(ctx: common.Context): Promise<preferences.Preferences> {
  if (prefInstance === undefined) {
    prefInstance = await preferences.getPreferences(ctx, PREF_NAME);
  }
  return prefInstance;
}

async function saveState(ctx: common.Context): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    await prefs.put(KEY_STATE, JSON.stringify(cached));
    await prefs.flush();
  } catch (e) {
    console.error('[Pro] state save failed');
  }
}

function errCode(e: Object): number {
  try {
    const code = (e as BusinessError).code;
    return typeof code === 'number' ? code : -1;
  } catch (_e) {
    return -1;
  }
}

/** Decode the middle base64url segment of a JWS purchaseData string. */
function jwsPayloadJson(jws: string): string {
  try {
    const parts = jws.split('.');
    if (parts.length < 2) {
      return '';
    }
    let mid = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    while (mid.length % 4 !== 0) {
      mid += '=';
    }
    const bytes = new util.Base64Helper().decodeSync(mid);
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      out += String.fromCharCode(bytes[i]);
    }
    return out;
  } catch (e) {
    return '';
  }
}

function parsePayload(jws: string): PurchasePayload {
  const p: PurchasePayload = {};
  try {
    const json = jwsPayloadJson(jws);
    if (json.length === 0) {
      return p;
    }
    const r = JSON.parse(json) as PurchasePayload;
    p.purchaseToken = typeof r.purchaseToken === 'string' ? r.purchaseToken : undefined;
    p.purchaseOrderId = typeof r.purchaseOrderId === 'string' ? r.purchaseOrderId : undefined;
    p.purchaseTime = typeof r.purchaseTime === 'number' ? r.purchaseTime : undefined;
  } catch (e) {
    /* keep the empty payload */
  }
  return p;
}

/** Adopt one purchaseData entry: set the unlock + best-effort metadata, then
 * finishPurchase so no order is left UNFINISHED (repair parity). */
function adoptOne(ctx: common.UIAbilityContext, jws: string): void {
  const pl = parsePayload(jws);
  cached.unlocked = true;
  if (cached.purchaseTime === 0 && typeof pl.purchaseTime === 'number') {
    cached.purchaseTime = pl.purchaseTime;
  }
  if (cached.orderId.length === 0 && typeof pl.purchaseOrderId === 'string') {
    cached.orderId = pl.purchaseOrderId;
  }
  saveState(ctx);
  if (typeof pl.purchaseToken === 'string' && typeof pl.purchaseOrderId === 'string') {
    const fp: iap.FinishPurchaseParameter = {
      productType: iap.ProductType.NONCONSUMABLE,
      purchaseToken: pl.purchaseToken,
      purchaseOrderId: pl.purchaseOrderId
    };
    iap.finishPurchase(ctx, fp).then((): void => {
      tlog('pro_finish_ok');
    }).catch((e: Object): void => {
      /* PURCHASE_FINISHED etc. are harmless on non-consumables */
      tlog('pro_finish_skip', 'code=' + errCode(e));
    });
  }
}

/** queryPurchases(ALL) + adopt; revokes when the list is empty (refund
 * parity, Android applyOwnedResult). Returns whether the product is owned. */
async function queryAndAdopt(ctx: common.UIAbilityContext): Promise<boolean> {
  const qp: iap.QueryPurchasesParameter = {
    productType: iap.ProductType.NONCONSUMABLE,
    queryType: iap.PurchaseQueryType.ALL
  };
  const res = await iap.queryPurchases(ctx, qp);
  if (res.purchaseDataList.length === 0) {
    if (cached.unlocked) {
      cached.unlocked = false;
      cached.purchaseTime = 0;
      cached.orderId = '';
      await saveState(ctx);
      tlog('pro_sync_revoked');
    }
    return false;
  }
  for (let i = 0; i < res.purchaseDataList.length; i++) {
    adoptOne(ctx, res.purchaseDataList[i]);
  }
  return true;
}

/** Android syncOwnershipSilently parity: fire-and-forget, offline-safe
 * (failures keep the cached unlock flag). */
export function syncOwnershipSilently(ctx: common.UIAbilityContext): void {
  queryAndAdopt(ctx).then((owned: boolean): void => {
    tlog('pro_sync_done', 'owned=' + (owned ? '1' : '0'));
  }).catch((e: Object): void => {
    tlog('pro_sync_fail', 'code=' + errCode(e));
  });
}

export async function initProState(ctx: common.UIAbilityContext): Promise<void> {
  try {
    const prefs = await getPrefs(ctx);
    const json = await prefs.get(KEY_STATE, '') as string;
    if (json.length > 0) {
      try {
        const r = JSON.parse(json) as ProStoreState;
        cached = {
          unlocked: r.unlocked === true,
          purchaseTime: typeof r.purchaseTime === 'number' ? r.purchaseTime : 0,
          orderId: typeof r.orderId === 'string' ? r.orderId : '',
          debugForceLocked: r.debugForceLocked === true
        };
      } catch (e) {
        /* keep defaults */
      }
    }
    tlog('pro_state_loaded', 'unlocked=' + (isProUnlocked() ? '1' : '0') + ' dbg=' + (IS_DEBUG_BUILD ? '1' : '0'));
    if (!IS_DEBUG_BUILD) {
      syncOwnershipSilently(ctx);
    }
  } catch (e) {
    console.error('[Pro] init failed: ' + String(e));
  }
}

/** Android BillingManager.isProUnlocked parity (sync, cached). Debug builds
 * are stub-unlocked unless a simulated refund forced the locked state. */
export function isProUnlocked(): boolean {
  if (IS_DEBUG_BUILD) {
    return !cached.debugForceLocked;
  }
  return cached.unlocked;
}

export function isProFeaturesEnabled(): boolean {
  return isProUnlocked();
}

/** User-facing message for a locked feature (pro_toast_locked twin for
 * Error objects that surfaces show verbatim). */
export function gateMessage(): string {
  return '\u0050ro \u529F\u80FD\uFF0C\u8BF7\u5148\u5347\u7EA7 Pro';
}

/** Debug-only stub purchase (Android StubBillingDelegate stub dialog). */
export function stubPurchase(ctx: common.Context): void {
  if (!IS_DEBUG_BUILD) {
    return;
  }
  cached.unlocked = true;
  cached.debugForceLocked = false;
  if (cached.purchaseTime === 0) {
    cached.purchaseTime = Date.now();
  }
  if (cached.orderId.length === 0) {
    cached.orderId = STUB_ORDER_ID;
  }
  saveState(ctx);
  tlog('pro_purchase_stub');
}

/** Debug-only: lock the stub-unlocked state so the locked gating is
 * verifiable on the emulator (Android simulateRefund parity). */
export function simulateRefund(ctx: common.Context): void {
  if (!IS_DEBUG_BUILD) {
    return;
  }
  cached.unlocked = false;
  cached.debugForceLocked = true;
  saveState(ctx);
  tlog('pro_refund_sim');
}

export function restorePro(ctx: common.UIAbilityContext, onDone: (kind: ProPurchaseKind) => void): void {
  if (IS_DEBUG_BUILD) {
    stubPurchase(ctx);
    tlog('pro_restore_stub');
    onDone('restored');
    return;
  }
  queryAndAdopt(ctx).then((owned: boolean): void => {
    onDone(owned ? 'restored' : 'none');
  }).catch((e: Object): void => {
    tlog('pro_restore_fail', 'code=' + errCode(e));
    onDone('fail');
  });
}

/** Android HuaweiBillingDelegate.launchPurchaseFlow parity (release):
 * re-check ownership first (covers reinstall / lost cashier callback), else
 * start the IAP cashier; PRODUCT_OWNED maps to an ownership re-query. */
export function purchasePro(ctx: common.UIAbilityContext, onDone: (kind: ProPurchaseKind) => void): void {
  if (IS_DEBUG_BUILD) {
    stubPurchase(ctx);
    onDone('purchased');
    return;
  }
  queryAndAdopt(ctx).then((owned: boolean): void => {
    if (owned) {
      onDone('owned');
      return;
    }
    const param: iap.PurchaseParameter = {
      productId: IAP_PRODUCT_ID,
      productType: iap.ProductType.NONCONSUMABLE
    };
    iap.createPurchase(ctx, param).then((res: iap.CreatePurchaseResult): void => {
      adoptOne(ctx, res.purchaseData);
      tlog('pro_purchase_done', 'product=' + IAP_PRODUCT_ID);
      onDone('purchased');
    }).catch((e: Object): void => {
      const code = errCode(e);
      if (code === IAP_PRODUCT_OWNED) {
        queryAndAdopt(ctx).then((owned2: boolean): void => {
          onDone(owned2 ? 'owned' : 'fail');
        }).catch((_e2: Object): void => {
          onDone('fail');
        });
        return;
      }
      if (code === IAP_USER_CANCELED) {
        onDone('cancel');
        return;
      }
      tlog('pro_purchase_fail', 'code=' + code);
      onDone('fail');
    });
  }).catch((e: Object): void => {
    tlog('pro_purchase_fail', 'query code=' + errCode(e));
    onDone('fail');
  });
}

/** UI helpers for the Pro card info line. */
export function getChannelKind(): string {
  return IS_DEBUG_BUILD ? 'stub' : 'huawei';
}

export function getPurchaseTime(): number {
  return cached.purchaseTime;
}

export function getOrderIdSuffix(): string {
  if (cached.orderId.length > 4) {
    return cached.orderId.substring(cached.orderId.length - 4);
  }
  return cached.orderId;
}
