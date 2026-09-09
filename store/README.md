# store/ — 商店发布物与渠道资料

按「平台 → 商店」组织各渠道上架所需的**非代码发布物与要求清单**。
代码层面的渠道差异见各平台目录（安卓在 `app/src/<渠道>/`，渠道→flavor 映射见
根目录 MULTI_PLATFORM.md）。

```
store/
├── android/
│   ├── fdroid/    F-Droid（无广告、无 GMS、无 IAP，见其构建规范）
│   ├── google/    Google Play / 官网主渠道（对应 app/src/pro flavor，广告+IAP 旗舰）
│   ├── xiaomi/    小米应用商店（预留）
│   └── huawei/    华为 AppGallery 安卓包（预留）
├── harmony/
│   └── huawei/    华为 AppGallery 鸿蒙包（AppScope 素材/上架材料）
└── ios/
    └── apple/     苹果 App Store（预留）
```

已有资产：`android/fastlane/`（2026-09-02 随安卓工程迁入）存放 Google Play 商店元数据
（截图/描述模板），本目录 README 约定各渠道所需清单；签名与密钥不放在本目录（见各子 README）。
