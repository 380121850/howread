# Google Play / 官网主渠道（pro flavor，旗舰）

- **定位**：广告 + IAP 旗舰版（2026-09-09 起 flavor 缩减为 2 个，本渠道由 pro flavor 承担；
  原主渠道 howread flavor 已删除）。
- **构建**：`assemblePro*`（gradle 根 = android/，flavor 目录 app/src/pro，
  包名 `com.leestudio.howread.pro.reader`；版本号随 app/gradle.properties：
  appVersionNumberBase/Index、appCodeNumber）。产物 `HowRead-Pro-v<ver>-<abi>.apk`
  （uni 同规则 `-uni`）。fdroid 可同批或单独调用。
- **广告（预留休眠）**：AdMob 已挂载（app/src/admobAds + libDepFree：play-services-ads
  + UMP），但广告单元 ID 默认留空 → `AdMobAdsProvider` 空 ID 守卫使所有加载/展示为
  no-op，不请求任何广告。**启用** = 在 `~/.gradle/gradle.properties` 配
  `pro_admobAppId / pro_admobBannerId / pro_admobFullId / pro_admobRewardId`
  （旧 `howread_*`、`google_*` 键仍作为回落），零代码改动。
- **IAP（接口级预留）**：`mobi.librera.libgoogle.BillingManager`（src/main）提供
  `isProUnlocked()` / `launchPurchaseFlow()` no-op 入口；购买解锁 pro 功能并关闭广告。
  对接时在该类内接 billingclient（catalog 已声明 billing 8.3.0，未依赖）。
- **签名**：release 用 RELEASE_* 属性（android/howread.keystore, alias howread）。
- **商店元数据**：android/fastlane/（标题/描述/图标/截图）。
- **上架清单（Google Play）**：账号与 DATA SAFETY 表单、隐私政策 URL、
  内容分级、按 ABI 输出（arm64/arm/x86/x86_64/uni）。
- **合规**：UMP 同意流程内置（EEA）；面向儿童需另配 family policy。
