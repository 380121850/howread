package mobi.librera.libgoogle;

import android.app.Activity;

/**
 * Per-channel purchase backend behind {@link BillingManager}'s static API.
 *
 * Exactly one concrete implementation is compiled into every variant through
 * the same-named {@code BillingDelegateFactory} in each channel source set
 * (same pattern as AdsProviderFactory / LibreraBuildConfig):
 *  - src/admobAds + src/noAds -> StubBillingDelegate (IAP not integrated:
 *    pro unlocks via the stub dialog / -PiapStub=true, fdroid never unlocks)
 *  - src/hmsAds               -> HuaweiBillingDelegate (HMS IAP, AppGallery)
 *
 * All pro-feature gating (AppsConfig.isProFeaturesEnabled /
 * AppsConfig.isShowAdsInApp) and the Pro card UI funnel through
 * BillingManager, so swapping the delegate changes the channel without
 * touching any call site.
 */
public interface BillingDelegate {

    /** Whether the pro unlock is active for this install. */
    boolean isProUnlocked();

    /** Display name of the unlock channel (shown on the Pro card). */
    String getChannelText(Activity a);

    /**
     * Opens the purchase flow for the pro unlock.
     *
     * @param onRefresh called after the unlock state may have changed
     */
    void launchPurchaseFlow(Activity a, Runnable onRefresh);

    /** Re-queries owned purchases from the store and refreshes the state. */
    void restorePurchases(Activity a, Runnable onRefresh);

    /** Opens store-side entitlement/order management (best effort). */
    void manageEntitlements(Activity a);

    /**
     * Fire-and-forget re-query of the store's owned purchases at app start:
     * picks up refund revocations and lost purchases without any UI. Must
     * never block or toast; a failed query keeps the cached state.
     */
    void syncOwnershipSilently(Activity a);
}
