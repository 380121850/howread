package mobi.librera.libgoogle;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.foobnix.LibreraApp;
import com.foobnix.ai.IapStub;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Unified purchase entry point. All purchase/unlock state funnels through
 * the static methods below; the channel implementation sits behind
 * {@link BillingDelegate}, obtained from the per-source-set
 * {@code BillingDelegateFactory} (same pattern as AdsProviderFactory):
 *  - src/admobAds + src/noAds -> StubBillingDelegate (IAP not integrated yet)
 *  - src/hmsAds               -> HuaweiBillingDelegate (HMS IAP, AppGallery)
 *
 * All pro-feature gating (AppsConfig.isProFeaturesEnabled /
 * isShowAdsInApp) and the Pro card UI call exactly these statics, so a
 * delegate swap changes the channel without touching any call site.
 */
public class BillingManager {

    /** Stub purchase order id — shown when a -PiapStub=true build has no
     *  runtime stub purchase recorded yet (kept in sync with
     *  StubBillingDelegate.STUB_ORDER_ID). */
    private static final String STUB_ORDER_ID = "IAP-STUB-2026-8888";

    public BillingManager(LibreraApp libreraApp) {
    }

    private static BillingDelegate delegate() {
        return BillingDelegateFactory.get();
    }

    /** Whether the pro unlock (IAP) has been purchased (channel-specific). */
    public static boolean isProUnlocked() {
        return delegate().isProUnlocked();
    }

    /** Display name of the unlock channel (stub: local key; huawei: AppGallery). */
    public static String getChannelText(Activity a) {
        return delegate().getChannelText(a);
    }

    /** Purchase time shown on the Pro card, "" when not stub-purchased. */
    public static String getPurchaseTimeText(Activity a) {
        long t = AppSP.get().iapPurchaseTime;
        if (t <= 0 && IapStub.UNLOCKED) {
            // -PiapStub=true build without a runtime stub purchase yet:
            // fall back to the build-time stub purchase moment
            t = IapStub.STUB_PURCHASE_TIME;
        }
        if (t <= 0) {
            return "";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(t));
    }

    /** Last 4 characters of the (stub) order id, "" when not stub-purchased. */
    public static String getOrderIdSuffix() {
        String id = AppSP.get().iapOrderId;
        if ((id == null || id.length() == 0) && IapStub.UNLOCKED) {
            id = STUB_ORDER_ID;
        }
        if (id == null || id.length() == 0) {
            return "";
        }
        return id.length() <= 4 ? id : id.substring(id.length() - 4);
    }

    /**
     * Opens the purchase flow for the pro unlock.
     *
     * @param onRefresh called after the state may have changed (UI refresh)
     */
    public static void launchPurchaseFlow(Activity a, Runnable onRefresh) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        delegate().launchPurchaseFlow(a, onRefresh);
    }

    /** Re-queries owned purchases from the store and refreshes the state. */
    public static void restorePurchases(Activity a, Runnable onRefresh) {
        if (a == null) {
            return;
        }
        delegate().restorePurchases(a, onRefresh);
    }

    /** Opens store-side entitlement/order management (best effort). */
    public static void manageEntitlements(Activity a) {
        if (a == null) {
            return;
        }
        delegate().manageEntitlements(a);
    }

    /**
     * Fire-and-forget ownership re-query at app start (channel-specific):
     * picks up refunds and lost purchases without UI. No-op on stub channels.
     */
    public static void syncOwnershipSilently(Activity a) {
        if (a == null) {
            return;
        }
        delegate().syncOwnershipSilently(a);
    }

    /**
     * Dev tool (all channels): clears the device-local unlock flag so the
     * Pro card falls back to "升级 Pro". Long-press "Pro 已激活" on the
     * settings card.
     */
    public static void simulateRefund(Activity a, Runnable onRefresh) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle(R.string.pro_refund_title)
                .setMessage(R.string.pro_refund_msg)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    AppSP.get().iapProUnlocked = false;
                    AppSP.get().iapPurchaseTime = 0;
                    AppSP.get().iapOrderId = "";
                    AppProfile.save(a);
                    Toast.makeText(a, R.string.pro_toast_refunded, Toast.LENGTH_SHORT).show();
                    if (onRefresh != null) {
                        onRefresh.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
