package mobi.librera.libgoogle;

import android.app.Activity;

import com.foobnix.LibreraApp;

/**
 * Unified purchase entry point (IAP RESERVATION — interface level only).
 *
 * The pro flavor is the future ad+IAP flagship: buying the IAP unlocks the
 * pro features AND turns the ads off. No billing SDK is linked yet — when
 * the integration happens, implement the two methods below against
 * com.android.billingclient (billing 8.x is already declared in
 * gradle/libs.versions.toml) inside THIS class:
 *   1. isProUnlocked()  → query the Play billing purchase state for the
 *      "pro unlock" in-app product and cache it (persisted flag);
 *   2. launchPurchaseFlow() → start the billing flow for that product.
 * The fdroid flavor stays 100% billing-free with ZERO code change: it never
 * calls launchPurchaseFlow() and isProUnlocked() simply keeps returning
 * false there.
 */
public class BillingManager {

    public BillingManager(LibreraApp libreraApp) {
    }

    /**
     * Whether the pro unlock (IAP) has been purchased.
     *
     * TODO(IAP): replace with the real billing query once the Play billing
     * integration lands. Until then this is always false: the pro flavor
     * keeps ads dormant (no ad-unit ids) and no pro feature is IAP-gated yet.
     */
    public static boolean isProUnlocked() {
        return false;
    }

    /**
     * Opens the purchase flow for the pro unlock.
     *
     * TODO(IAP): implement with BillingClient.launchBillingFlow(). No-op
     * until the integration lands; callers (future "upgrade" UI) can invoke
     * this unconditionally in the pro flavor.
     */
    public static void launchPurchaseFlow(Activity activity) {
        // reserved: no billing SDK linked yet
    }
}
