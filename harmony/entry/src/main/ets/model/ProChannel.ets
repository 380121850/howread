/**
 * 0.9.69: Pro channel configuration (Android android/signing/signing.properties
 * hw_* keys parity). The IAP product is a NONCONSUMABLE unlock (Android
 * hw_iapProductId default). Ad slot ids stay EMPTY by default: every entry is
 * guarded by isAdConfigured() so the whole Ads integration ships "wired but
 * dormant" (Android HuaweiAdsProvider empty-ID guard parity).
 *
 * 红线（Android signing.properties 同款）：
 *  - 正式投放：在 Petal Ads（鲸鸿动能）创建正式广告位后把三个 id 填在这里；
 *  - 真机调试：可临时填华为测试广告位（值见上架准备报告），上架包必须换正式位或清空。
 */
export const IAP_PRODUCT_ID: string = 'howread_pro_unlock';
export const AD_BANNER_ID: string = '';
export const AD_INTERSTITIAL_ID: string = '';
export const AD_REWARD_ID: string = '';
