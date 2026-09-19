package mobi.librera.libgoogle;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;
import com.huawei.hmf.tasks.Task;
import com.huawei.hms.iap.Iap;
import com.huawei.hms.iap.IapClient;
import com.huawei.hms.iap.entity.InAppPurchaseData;
import com.huawei.hms.iap.entity.OrderStatusCode;
import com.huawei.hms.iap.entity.OwnedPurchasesReq;
import com.huawei.hms.iap.entity.OwnedPurchasesResult;
import com.huawei.hms.iap.entity.StartIapActivityReq;
import com.huawei.hms.iap.entity.StartIapActivityResult;

/**
 * HMS IAP backend for the huawei flavor (AppGallery channel). Implements the
 * {@link BillingDelegate} contract so the whole pro-unlock flow — the Pro
 * card upgrade button, the gated features (AppsConfig.isProFeaturesEnabled)
 * and the ad suppression (AppsConfig.isShowAdsInApp) — works against
 * AppGallery instead of the stub.
 *
 * Unlock state: the device-local flag AppSP.iapProUnlocked, refreshed from
 * HMS IAP {@code obtainOwnedPurchases} (non-consumable, the configured
 * product id) on every purchase attempt / restore. Offline devices keep the
 * cached flag (queries fail → no change), so a purchased user is never
 * locked out by a missing network.
 *
 * Server-side purchase verification (Huawei's recommendation) is NOT
 * implemented yet — client-side state only, same reservation as the Play
 * channel stub. Product id: librera.HW_IAP_PRODUCT_ID meta-data (default
 * howread_pro_unlock), settable via hw_iapProductId in gradle.properties.
 */
public class HuaweiBillingDelegate implements BillingDelegate {

    private static final String TAG = "HW-IAP";

    /** Fallback IAP product id when hw_iapProductId is not configured. */
    private static final String DEFAULT_PRODUCT_ID = "howread_pro_unlock";

    /** The configured non-consumable product id for the pro unlock. */
    static String getProductId(Context c) {
        String id = Apps.getMetaData(c, "librera.HW_IAP_PRODUCT_ID");
        return id != null && id.trim().length() > 0 ? id.trim() : DEFAULT_PRODUCT_ID;
    }

    @Override
    public boolean isProUnlocked() {
        if (!AppsConfig.isProFlavor()) {
            return false;
        }
        // synchronous read of the cached flag; refreshed by syncOwnedPurchases
        return AppSP.get().iapProUnlocked;
    }

    @Override
    public String getChannelText(Activity a) {
        return a.getString(R.string.pro_channel_huawei);
    }

    @Override
    public void launchPurchaseFlow(final Activity a, final Runnable onRefresh) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (!AppsConfig.isProFlavor()) {
            return;
        }
        if (isProUnlocked()) {
            if (onRefresh != null) {
                onRefresh.run();
            }
            return;
        }
        // re-query first: covers reinstall and lost cashier callbacks — if the
        // product is already owned, unlock instead of charging again
        syncOwnedPurchases(a, () -> {
            if (AppSP.get().iapProUnlocked) {
                Toast.makeText(a, R.string.pro_toast_restored_real, Toast.LENGTH_SHORT).show();
                if (onRefresh != null) {
                    onRefresh.run();
                }
                return;
            }
            // hand the cashier round-trip to the transparent helper activity
            IapPurchaseActivity.launch(a, onRefresh);
        });
    }

    @Override
    public void restorePurchases(final Activity a, final Runnable onRefresh) {
        if (a == null) {
            return;
        }
        syncOwnedPurchases(a, () -> {
            Toast.makeText(a,
                    AppSP.get().iapProUnlocked ? R.string.pro_toast_restored_real : R.string.pro_toast_no_purchases,
                    Toast.LENGTH_SHORT).show();
            if (onRefresh != null) {
                onRefresh.run();
            }
        });
    }

    @Override
    public void manageEntitlements(Activity a) {
        if (a == null) {
            return;
        }
        try {
            StartIapActivityReq req = new StartIapActivityReq();
            req.setType(StartIapActivityReq.TYPE_PAYINFO_ACTIVITY); // order/payment page
            Task<StartIapActivityResult> task = Iap.getIapClient(a).startIapActivity(req);
            task.addOnSuccessListener(result -> {
                try {
                    if (result != null) {
                        result.startActivity(a);
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                }
            }).addOnFailureListener(e -> {
                LOG.d(TAG, "startIapActivity failed");
                LOG.e(e);
                Toast.makeText(a, R.string.pro_iap_unavailable, Toast.LENGTH_SHORT).show();
            });
        } catch (Throwable e) {
            LOG.e(e);
            Toast.makeText(a, R.string.pro_iap_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * App-start re-query (no UI): detects refunds while the app is closed —
     * if Huawei reports the product no longer owned, the cached unlock is
     * revoked here, so Pro falls back to locked on the next session. A failed
     * query (offline etc.) keeps the cached state.
     */
    @Override
    public void syncOwnershipSilently(final Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        syncOwnedPurchases(a, null);
    }

    /**
     * Queries owned non-consumable purchases and refreshes the cached unlock
     * state in AppSP. If a definitive query shows the product is no longer
     * owned (refund), the unlock is revoked. Runs the callback on the main
     * thread either way.
     */
    static void syncOwnedPurchases(final Activity a, final Runnable onDone) {
        try {
            OwnedPurchasesReq req = new OwnedPurchasesReq();
            req.setPriceType(IapClient.PriceType.IN_APP_NONCONSUMABLE);
            Task<OwnedPurchasesResult> task = Iap.getIapClient(a).obtainOwnedPurchases(req);
            task.addOnSuccessListener(result -> {
                try {
                    applyOwnedResult(a, result);
                } catch (Throwable e) {
                    LOG.e(e);
                }
                if (onDone != null) {
                    onDone.run();
                }
            }).addOnFailureListener(e -> {
                // transient failure (offline etc.) — keep the cached flag
                LOG.d(TAG, "obtainOwnedPurchases failed");
                LOG.e(e);
                if (onDone != null) {
                    onDone.run();
                }
            });
        } catch (Throwable e) {
            LOG.e(e);
            if (onDone != null) {
                onDone.run();
            }
        }
    }

    private static void applyOwnedResult(Activity a, OwnedPurchasesResult result) {
        String target = getProductId(a);
        boolean owned = false;
        long newest = 0;
        String orderId = "";
        if (result != null && result.getInAppPurchaseDataList() != null) {
            for (String json : result.getInAppPurchaseDataList()) {
                try {
                    InAppPurchaseData d = new InAppPurchaseData(json);
                    if (target.equals(d.getProductId())
                            && d.getPurchaseState() == InAppPurchaseData.PurchaseState.PURCHASED) {
                        owned = true;
                        if (d.getPurchaseTime() > newest) {
                            newest = d.getPurchaseTime();
                            orderId = d.getOrderID() == null ? "" : d.getOrderID();
                        }
                    }
                } catch (Throwable e) {
                    LOG.e(e);
                }
            }
        }
        if (owned) {
            AppSP.get().iapProUnlocked = true;
            if (AppSP.get().iapPurchaseTime <= 0) {
                AppSP.get().iapPurchaseTime = newest > 0 ? newest : System.currentTimeMillis();
            }
            if (AppSP.get().iapOrderId == null || AppSP.get().iapOrderId.length() == 0) {
                AppSP.get().iapOrderId = orderId;
            }
            AppProfile.save(a);
            LOG.d(TAG, "pro unlock: owned purchase found");
        } else if (AppSP.get().iapProUnlocked) {
            // definitive query says the unlock product is gone (refund)
            AppSP.get().iapProUnlocked = false;
            AppSP.get().iapPurchaseTime = 0;
            AppSP.get().iapOrderId = "";
            AppProfile.save(a);
            LOG.d(TAG, "pro unlock revoked: product no longer owned");
        }
    }
}
