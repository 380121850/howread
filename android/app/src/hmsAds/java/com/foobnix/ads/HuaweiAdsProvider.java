package com.foobnix.ads;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.LOG;
import com.foobnix.LibreraApp;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.ADS;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;
import com.huawei.hms.ads.AdListener;
import com.huawei.hms.ads.AdParam;
import com.huawei.hms.ads.BannerAdSize;
import com.huawei.hms.ads.HwAds;
import com.huawei.hms.ads.InterstitialAd;
import com.huawei.hms.ads.banner.BannerView;
import com.huawei.hms.ads.reward.Reward;
import com.huawei.hms.ads.reward.RewardAd;
import com.huawei.hms.ads.reward.RewardAdLoadListener;
import com.huawei.hms.ads.reward.RewardAdStatusListener;

/**
 * Huawei Ads Kit (Petal Ads) implementation for the huawei flavor
 * (AppGallery channel), where AdMob cannot serve: AppGallery devices have no
 * GMS. Compiled only into src/hmsAds; the main source tree keeps no ad-SDK
 * dependency (same contract as AdMobAdsProvider).
 *
 * WIRED but DORMANT by default: the hw_* ad-unit meta-data values are empty
 * placeholders (app/build.gradle huawei flavor), so {@link #isAdUnitConfigured}
 * fails and every load/show call below is a no-op — no ad is ever requested.
 * Ads activate as soon as real media-slot ids are injected
 * (hw_bannerId / hw_fullId / hw_rewardId in ~/.gradle/gradle.properties); for
 * development use Huawei's official test slots (banner testw6vs28auh,
 * interstitial testb4zuon2bty, rewarded testx9dtjwj8hp). No code change
 * needed to switch.
 */
public class HuaweiAdsProvider implements AdsProvider {

    private static final String TAG = "ADS1";

    private BannerView bannerView;
    private InterstitialAd interstitialAd;
    private RewardAd rewardedAd;

    /** True when the ad-unit meta-data holds a non-blank id (ads activated). */
    private static boolean isAdUnitConfigured(String adUnitId) {
        return adUnitId != null && adUnitId.trim().length() > 0;
    }

    @Override
    public void initialize(final Context context) {
        try {
            // light-weight config init; guarded so a missing HMS Core on
            // exotic devices can never crash the app start
            HwAds.init(context);
            LOG.d(TAG, "HwAds.init done");
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    // -------- banner --------

    @Override
    public void showBanner(final Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }
        try {
            FrameLayout adFrame1 = a.findViewById(R.id.adFrame1);
            FrameLayout adFrame2 = a.findViewById(R.id.adFrame2);
            boolean isTopBanner = false;
            final FrameLayout frame = isTopBanner ? adFrame1 : adFrame2;

            if (frame == null) {
                return;
            }
            adFrame1.removeAllViews();
            adFrame2.removeAllViews();
            onDestroyBanner();

            String adId = Apps.getMetaData(a, "librera.HW_BANNER_ID");
            if (!isAdUnitConfigured(adId)) {
                LOG.d(TAG, "Banner skipped: no ad unit id configured");
                return;
            }

            bannerView = new BannerView(a);
            bannerView.setAdId(adId);
            bannerView.setBannerAdSize(BannerAdSize.BANNER_SIZE_SMART);
            bannerView.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    try {
                        frame.setVisibility(View.VISIBLE);
                        LOG.d(TAG, "Banner loaded");
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }

                @Override
                public void onAdFailed(int errorCode) {
                    LOG.d(TAG, "Banner onAdFailed", errorCode);
                    try {
                        frame.setVisibility(View.GONE);
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }
            });

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;
            bannerView.setLayoutParams(params);

            frame.addView(bannerView);
            bannerView.loadAd(new AdParam.Builder().build());
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public void onPauseBanner() {
        try {
            if (bannerView != null) {
                bannerView.pause();
                LOG.d(TAG, "Banner pause");
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public void onResumeBanner(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            LOG.d(TAG, "RewardActivated");
            onDestroyBanner();
            return;
        }
        try {
            if (bannerView != null) {
                bannerView.resume();
                LOG.d(TAG, "Banner resume");
            } else if (AppsConfig.isShowAdsInApp(a)) {
                showBanner(a);
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public void onDestroyBanner() {
        try {
            if (bannerView != null) {
                bannerView.setVisibility(View.GONE);
                LOG.d(TAG, "Banner destroy");
                bannerView.destroy();
                bannerView = null;
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    // -------- interstitial --------

    @Override
    public void loadInterstitial(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (interstitialAd != null && ADS.secondsRemain(AppSP.get().interstitialLoadAdTime) < ADS.ADS_LIVE_SEC) {
            LOG.d(TAG, "loadInterstitial in cache", ADS.secondsRemain(AppSP.get().interstitialLoadAdTime));
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }
        try {
            String adId = Apps.getMetaData(LibreraApp.context, "librera.HW_FULLSCREEN_ID");
            if (!isAdUnitConfigured(adId)) {
                LOG.d(TAG, "Interstitial skipped: no ad unit id configured");
                return;
            }
            interstitialAd = new InterstitialAd(LibreraApp.context);
            interstitialAd.setAdId(adId);
            interstitialAd.setAdListener(new AdListener() {
                @Override
                public void onAdLoaded() {
                    LOG.d(TAG, "Interstitial loaded");
                    AppSP.get().interstitialLoadAdTime = System.currentTimeMillis();
                }

                @Override
                public void onAdFailed(int errorCode) {
                    LOG.d(TAG, "Interstitial onAdFailed", errorCode);
                    interstitialAd = null;
                }

                @Override
                public void onAdClosed() {
                    interstitialAd = null;
                }
            });
            interstitialAd.loadAd(new AdParam.Builder().build());
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public void showInterstitial(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }
        if (ADS.secondsRemain(AppSP.get().interstitialLoadAdTime) > ADS.ADS_LIVE_SEC * 2L) {
            interstitialAd = null;
            return;
        }
        if (ADS.secondsRemain(AppSP.get().interstitialAdShowTime) < ADS.INTERSTITIAL_DELAY_SEC) {
            return;
        }
        try {
            if (interstitialAd != null) {
                LOG.d(TAG, "showInterstitial");
                interstitialAd.show(a);
                AppSP.get().interstitialAdShowTime = System.currentTimeMillis();
                interstitialAd = null;
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    // -------- rewarded --------

    @Override
    public void loadRewardedAd(Activity a, final Runnable onRewardLoaded) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }
        if (rewardedAd != null && ADS.secondsRemain(AppSP.get().rewardedAdLoadedTime) < ADS.ADS_LIVE_SEC) {
            LOG.d(TAG, "loadRewardedAd in cache", ADS.secondsRemain(AppSP.get().rewardedAdLoadedTime));
            if (onRewardLoaded != null) {
                onRewardLoaded.run();
            }
            return;
        }
        try {
            String adId = Apps.getMetaData(LibreraApp.context, "librera.HW_REWARD");
            if (!isAdUnitConfigured(adId)) {
                LOG.d(TAG, "Rewarded skipped: no ad unit id configured");
                return;
            }
            LOG.d(TAG, "RewardedAd load started...");
            rewardedAd = new RewardAd(LibreraApp.context, adId);
            rewardedAd.loadAd(new AdParam.Builder().build(), new RewardAdLoadListener() {
                @Override
                public void onRewardedLoaded() {
                    LOG.d(TAG, "RewardedAd loaded");
                    AppSP.get().rewardedAdLoadedTime = System.currentTimeMillis();
                    if (onRewardLoaded != null) {
                        onRewardLoaded.run();
                    }
                }

                @Override
                public void onRewardAdFailedToLoad(int errorCode) {
                    LOG.d(TAG, "RewardedAd failed", errorCode);
                    rewardedAd = null;
                }
            });
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public boolean isRewardsLoaded() {
        try {
            return rewardedAd != null && rewardedAd.isLoaded();
        } catch (Throwable e) {
            LOG.e(e);
            return false;
        }
    }

    @Override
    public void showRewardedAd(Activity a, final RewardListener listener) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        try {
            if (rewardedAd != null) {
                LOG.d(TAG, "showRewardedAd");
                rewardedAd.show(a, new RewardAdStatusListener() {
                    @Override
                    public void onRewarded(Reward reward) {
                        if (listener != null) {
                            listener.onRewardEarned();
                        }
                    }
                });
                AppSP.get().rewardShowTime = System.currentTimeMillis();
                rewardedAd = null;
            } else {
                LOG.d(TAG, "showRewardedAd: no loaded ad, reward listener not called");
            }
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    // -------- consent (HMS consent SDK not linked; nothing to show) --------

    @Override
    public void requestConsent(Activity a) {
    }

    @Override
    public boolean isPrivacyOptionsRequired() {
        return false;
    }

    @Override
    public void showPrivacyOptions(Activity a) {
    }
}
