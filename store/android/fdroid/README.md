# F-Droid 渠道

- **构建**：`assembleFdroid*`（fdroid flavor，包名 `com.leestudio.howread.fd.reader`，
  版本与主渠道统一读 app/gradle.properties）。产物 `HowRead-Fdroid-v<ver>-<abi>.apk`
  （uni 同规则 `-uni`）。
- **广告**：零广告。代码上 fdroid 只编译 `app/src/noAds` 的 `NoAdsProvider`，
  依赖面不含任何广告 SDK，APK 无 GMS 广告命名空间（用 `scan_apk_ads.py` 闸门守住）。
- **GMS**：无。Google Drive 同步（GMS）已于 2026-09-02 从**所有版本**删除，
  main 不再含任何 `com.google.android.gms`/`com.google.api` 类型；fdroid 仅用
  `app/src/fdroid/java` 的 junrar/RefiewForm stub 替代非自由/付费依赖（BillingManager
  已于 2026-09-09 上移到 src/main 作统一接口级预留，fdroid 无计费调用）；无
  play-services 依赖。
- **pro 功能**：无 IAP、无 pro 高级功能（预留门控 `AppsConfig.isProFeaturesEnabled()`
  对 fdroid 恒为 false）。
- **权限**：fdroid Manifest 额外移除 `USE_BIOMETRIC`。
- **签名**：F-Droid 官方构建时用自己的密钥重签；本地测试用 debug 签名即可。
- **上架材料**：仓库根 `fastlane/`（fdroid 元数据）+ F-Droid 构建说明（README.md）。
- **开源合规**：F-Droid 要求全部依赖开源：本工程依赖清单见根 README 第三方列表；
  新依赖入库前必须核查许可证（避免 AGPL 传染面扩大）。
