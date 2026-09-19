package mobi.librera.libgoogle;

/**
 * Compiled into the src/noAds source set (fdroid flavor) via app/build.gradle.
 * fdroid has no IAP by design (F-Droid policy) — see {@link StubBillingDelegate}.
 */
public class BillingDelegateFactory {

    public static BillingDelegate get() {
        return new StubBillingDelegate();
    }
}
