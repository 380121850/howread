package com.foobnix.ads;

/**
 * Compiled only into the huawei flavor via app/src/hmsAds in app/build.gradle
 * (which pulls the Huawei Ads Kit from the Huawei Maven repo).
 */
public class AdsProviderFactory {

    public static AdsProvider get() {
        return new HuaweiAdsProvider();
    }
}
