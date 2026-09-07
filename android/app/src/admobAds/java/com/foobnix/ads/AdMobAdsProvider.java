package com.foobnix.ads;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.Dips;
import com.foobnix.android.utils.LOG;
import com.foobnix.model.AppSP;
import com.foobnix.pdf.info.ADS;
import com.foobnix.pdf.info.AppsConfig;
import com.foobnix.pdf.info.R;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.OnUserEarnedRewardListener;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.initialization.InitializationStatus;
import com.google.android.gms.ads.initialization.OnInitializationCompleteListener;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardItem;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.ump.ConsentDebugSettings;
import com.google.android.ump.ConsentForm;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.FormError;
import com.google.android.ump.UserMessagingPlatform;

/**
 * Real AdMob implementation. Compiled only into the ad-enabled flavors
 * (src/admobAds source set), which are the ones that depend on
 * play-services-ads (+ user-messaging-platform) through libDepFree.
 *
 * Everything AdMob/UMP-specific that used to live in the shared code
 * (LibreraApp init, the UMP consent flow in MainTabs2, the privacy-options
 * entry in PrefFragment2, and the SDK calls inside ADS) moved here, so the
 * main source tree has no ad-SDK dependency.
 */
public class AdMobAdsProvider implements AdsProvider {

    private InterstitialAd interstitialAd;
    private RewardedAd rewardedAd;
    private AdView adView;

    @Override
    public void initialize(Context context) {
        try {
            LOG.d("ADS1", "MobileAds.initialize");
            // MobileAds.initialize pulls in Play Services classes; run it (and
            // setRequestConfiguration, which takes the same SDK lock) off the
            // main thread (the SDK is explicitly thread-safe for init).
            final RequestConfiguration configuration = AppsConfig.IS_TEST_DEVICE
                    ? new RequestConfiguration.Builder().setTestDeviceIds(AppsConfig.testDevices).build()
                    : null;
            AppsConfig.executorService.execute(() -> {
                try {
                    if (configuration != null) {
                        MobileAds.setRequestConfiguration(configuration);
                    }
                    MobileAds.initialize(context, new OnInitializationCompleteListener() {
                        @Override
                        public void onInitializationComplete(
                                @NonNull
                                InitializationStatus initializationStatus) {
                            LOG.d("ads-complete");
                        }
                    });
                } catch (Exception e) {
                    LOG.e(e);
                }
            });
        } catch (Exception e) {
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

            LOG.d("ADS1", "Banner-show top", isTopBanner);
            adView = new AdView(a);
            AdSize size;
            if (isTopBanner) {
                size = new java.util.Random().nextBoolean() ?
                        AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(a, Dips.screenWidthDP()) :
                        AdSize.LARGE_BANNER;
            } else {
                size = AdSize.getInlineAdaptiveBannerAdSize(Dips.screenWidthDP(), Dips.DP_25);
            }

            adView.setAdSize(size);

            String metaData = Apps.getMetaData(a, "librera.ADMOB_BANNER_ID");
            if (metaData == null) {
                return;
            }
            adView.setAdUnitId(metaData);

            adView.loadAd(getAdRequest(a));

            adView.setAdListener(new AdListener() {
                @Override
                public void onAdFailedToLoad(LoadAdError arg0) {
                    LOG.d("ADS1", "Banner LoadAdError", arg0);
                    try {
                        frame.setVisibility(View.GONE);
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }

                @Override
                public void onAdLoaded() {
                    try {
                        frame.setVisibility(View.VISIBLE);
                        LOG.d("ADS1", "Banner loaded");
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                }
            });

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER_HORIZONTAL;

            adView.setLayoutParams(params);

            frame.addView(adView);
        } catch (Throwable e) {
            LOG.e(e);
        }
    }

    @Override
    public void onPauseBanner() {
        if (adView != null) {
            adView.pause();
            LOG.d("ADS1", "Banner pause");
        }
    }

    @Override
    public void onResumeBanner(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            LOG.d("ADS1", "RewardActivated");
            onDestroyBanner();
            return;
        }

        if (adView != null) {
            adView.resume();
            LOG.d("ADS1", "Banner resume");
        } else {
            if (AppsConfig.isShowAdsInApp(a)) {
                showBanner(a);
            }
        }
    }

    @Override
    public void onDestroyBanner() {
        try {
            if (adView != null) {
                adView.setVisibility(View.GONE);
                LOG.d("ADS1", "Banner destroy");
                adView.destroy();
                adView = null;
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    // -------- interstitial --------

    @Override
    public void loadInterstitial(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            LOG.d("ADS1", "Interstitial destroyed");
            return;
        }
        if (interstitialAd != null && ADS.secondsRemain(AppSP.get().interstitialLoadAdTime) < ADS.ADS_LIVE_SEC) {
            LOG.d("ADS1", "loadInterstitial in cache", ADS.secondsRemain(AppSP.get().interstitialLoadAdTime));
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }

        LOG.d("ADS1", "Interstitial try show");

        try {
            LOG.d("ADS1", "Interstitial loading...");
            try {
                final float volume = Apps.isNight(a) ? 0.1f : 0.6f;
                // MobileAds.setAppVolume blocks on the SDK init lock. On a
                // fresh install the background MobileAds.initialize is itself
                // waiting for the (slow) first WebView init, so calling this
                // on the main thread deadlocks the UI (cold-start ANR).
                AppsConfig.executorService.execute(() -> {
                    try {
                        MobileAds.setAppVolume(volume);
                    } catch (Exception e) {
                        LOG.e(e);
                    }
                });
            } catch (Exception e) {
                LOG.e(e);
            }

            String adUnitId = Apps.getMetaData(LibreraApp.context, "librera.ADMOB_FULLSCREEN_ID");
            if (adUnitId == null) {
                return;
            }
            InterstitialAd.load(LibreraApp.context, adUnitId, getAdRequest(a), new InterstitialAdLoadCallback() {
                @Override
                public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                    super.onAdFailedToLoad(loadAdError);
                    LOG.d("ADS1", "Interstitial LoadAdError", loadAdError);
                    interstitialAd = null;
                }

                @Override
                public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                    super.onAdLoaded(interstitialAd);
                    LOG.d("ADS1", "Interstitial loaded");
                    AdMobAdsProvider.this.interstitialAd = interstitialAd;
                    AppSP.get().interstitialLoadAdTime = System.currentTimeMillis();
                }
            });
        } catch (Exception e) {
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
            LOG.d("ADS1", "showInterstitial interstitialLoadAdTime > ADS_LIVE_SEC");
            return;
        }

        if (ADS.secondsRemain(AppSP.get().interstitialAdShowTime) < ADS.INTERSTITIAL_DELAY_SEC) {
            LOG.d("ADS1", "showInterstitial delay timeout");
            return;
        }

        if (interstitialAd != null) {
            LOG.d("ADS1", "showInterstitial");
            interstitialAd.show(a);
            AppSP.get().interstitialAdShowTime = System.currentTimeMillis();
            interstitialAd = null;
        }
    }

    // -------- rewarded --------

    @Override
    public void loadRewardedAd(Activity a, Runnable onRewardLoaded) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (ADS.get().isRewardActivated()) {
            return;
        }
        if (rewardedAd != null && ADS.secondsRemain(AppSP.get().rewardedAdLoadedTime) < ADS.ADS_LIVE_SEC) {
            LOG.d("ADS1", "loadRewardedAd in cache", ADS.secondsRemain(AppSP.get().rewardedAdLoadedTime));
            if (onRewardLoaded != null) {
                onRewardLoaded.run();
            }
            return;
        }
        LOG.d("ADS1", "RewardedAd load started...");

        String adUnitId = Apps.getMetaData(LibreraApp.context, "librera.ADMOB_REWARD");
        if (adUnitId == null) {
            return;
        }
        RewardedAd.load(a, adUnitId, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override
            public void onAdLoaded(@NonNull RewardedAd rewardedAdLoaded) {
                rewardedAd = rewardedAdLoaded;
                AppSP.get().rewardedAdLoadedTime = System.currentTimeMillis();
                if (onRewardLoaded != null) {
                    onRewardLoaded.run();
                }
                LOG.d("ADS1", "RewardedAd loaded");
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                rewardedAd = null;
                LOG.d("ADS1", "RewardedAd failed", loadAdError);
            }
        });
    }

    @Override
    public boolean isRewardsLoaded() {
        return rewardedAd != null;
    }

    @Override
    public void showRewardedAd(Activity a, RewardListener listener) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        if (rewardedAd != null) {
            LOG.d("ADS1", "showRewardedAd");
            rewardedAd.show(a, new OnUserEarnedRewardListener() {
                @Override
                public void onUserEarnedReward(@NonNull RewardItem rewardItem) {
                    if (listener != null) {
                        listener.onRewardEarned();
                    }
                }
            });
            rewardedAd = null;
            AppSP.get().rewardShowTime = System.currentTimeMillis();
        }
    }

    private static AdRequest getAdRequest(Context a) {
        return new AdRequest.Builder().build();
    }

    // -------- consent (UMP) --------

    @Override
    public void requestConsent(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        try {
            ConsentRequestParameters params;

            if (AppsConfig.IS_TEST_DEVICE) {
                ConsentDebugSettings
                        debugSettings =
                        new ConsentDebugSettings.Builder(a).setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                                                          .addTestDeviceHashedId(ADS.getByTestID(a))
                                                          .build();

                params =
                        new ConsentRequestParameters.Builder().setConsentDebugSettings(debugSettings)
                                                              .setTagForUnderAgeOfConsent(false)
                                                              .build();
                LOG.d("TEST-ads-device true", ADS.getByTestID(a));
            } else {
                params = new ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build();
                LOG.d("TEST-ads-device false", ADS.getByTestID(a));
            }
            ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(a);

            consentInformation.requestConsentInfoUpdate(a, params, () -> {
                if (consentInformation.isConsentFormAvailable()) {
                    loadConsentForm(a, consentInformation);
                }
            }, formError -> {
                LOG.d("formError", formError.getErrorCode(), formError.getMessage());
            });
        } catch (Exception e) {
            LOG.e(e);
        }
    }

    /** Loads a consent form. Must be called on the main thread. */
    private void loadConsentForm(Activity a, ConsentInformation consentInformation) {
        UserMessagingPlatform.loadConsentForm(a, new UserMessagingPlatform.OnConsentFormLoadSuccessListener() {
            @Override
            public void onConsentFormLoadSuccess(ConsentForm consentForm) {
                if (consentInformation.getConsentStatus() == ConsentInformation.ConsentStatus.REQUIRED) {
                    consentForm.show(a, formError -> {
                        if (formError != null) {
                            LOG.d("formError", formError.getErrorCode(), formError.getMessage());
                        }
                    });
                }
            }
        }, new UserMessagingPlatform.OnConsentFormLoadFailureListener() {
            @Override
            public void onConsentFormLoadFailure(FormError formError) {
                LOG.d("formError", formError.getErrorCode(), formError.getMessage());
            }
        });
    }

    @Override
    public boolean isPrivacyOptionsRequired() {
        try {
            ConsentInformation consentInformation = UserMessagingPlatform.getConsentInformation(LibreraApp.context);
            return consentInformation.getPrivacyOptionsRequirementStatus() ==
                    ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
        } catch (Exception e) {
            LOG.e(e);
            return false;
        }
    }

    @Override
    public void showPrivacyOptions(Activity a) {
        if (a == null || a.isDestroyed() || a.isFinishing()) {
            return;
        }
        UserMessagingPlatform.showPrivacyOptionsForm(a,
                new ConsentForm.OnConsentFormDismissedListener() {
                    @Override
                    public void onConsentFormDismissed(@Nullable FormError formError) {
                        if (formError != null) {
                            Toast.makeText(a, formError.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }
}
