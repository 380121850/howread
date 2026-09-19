package mobi.librera.libgoogle;

/**
 * Compiled into the src/admobAds source set (pro flavor) via app/build.gradle.
 * IAP is not integrated on the Google channel yet — see {@link StubBillingDelegate}.
 */
public class BillingDelegateFactory {

    public static BillingDelegate get() {
        return new StubBillingDelegate();
    }
}
