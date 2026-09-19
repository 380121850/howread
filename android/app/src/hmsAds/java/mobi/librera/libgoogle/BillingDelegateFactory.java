package mobi.librera.libgoogle;

/**
 * Compiled only into the src/hmsAds source set (huawei flavor) via
 * app/build.gradle. Real HMS IAP backend — see {@link HuaweiBillingDelegate}.
 */
public class BillingDelegateFactory {

    public static BillingDelegate get() {
        return new HuaweiBillingDelegate();
    }
}
