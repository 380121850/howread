# MULTI_PLATFORM.md — HowRead 多平台代码结构方案（2026-09-02 已按平台目录化落地）

发布矩阵：**手机三端（安卓 / 鸿蒙 / iOS）+ 桌面（Windows/Linux）**，安卓按商店细分
（F-Droid / Google / 小米 / 华为…）。**2026-09-02 已完成平台目录化迁移**：仓库一级
目录 = `android/` `harmony/` `ios/` `desktop/`。**2026-09-09 安卓 flavor 缩减为 2 个**：
`pro`（旗舰/主渠道，广告+IAP 预留）/ `fdroid`（纯净），原主渠道 howread flavor（更早名
librera→google）已删除。

---

## 1. 平台 → 商店 划分（总览）

```
                     L0 共享原生引擎（仓库根共享层）
              C/C++：MuPDF 1.23.7 单一源码树 + djvu/hqx/lame/mobi…
              Builder/mupdf-1.23.7  →  prebuilt/ 各端预编译产物
                     ▲ 薄胶水层（每平台各写各的，只做绑定不写业务）
        ┌────────────┼──────────────┬──────────────┐
   android(JNI)  harmony(NAPI)     ios(预留)     desktop(预留)
    Builder/jni   mupdf_napi.cpp    Swift/C       JVM/C++
        └────────────┴──────────────┴──────────────┘
     业务逻辑 + UI 各端原生（鸿蒙 ArkTS 跑不了 Java/KMP，边界不强推统一）
        ┌────────────┴──────┐
   android 按商店细分        harmony=华为 / ios=苹果（唯一渠道）
   app/src/pro|fdroid|xiaomi|huawei + ads 源集
```

## 2. 代码目录（迁移后现状，2026-09-02）

```
LibreraReader/                          ← 单仓库（git 根）
│
├─ android/                             [平台] 安卓 Gradle 工程（原仓库根，2026-09-02 下沉）
│   ├─ settings.gradle.kts              含 :Builder → projectDir=../Builder
│   ├─ gradlew gradle/ app/ libDepFree/ libDepPro/ libReflow/
│   ├─ fastlane/                        Play 商店元数据（纯安卓）
│   ├─ howread.keystore keystore.pkcs12 local.properties
│   └─ app/src/
│       ├─ main/                        [广告/GMS 无关] 平台共享代码
│       ├─ pro/                         flavor=pro（旗舰/主渠道 Google Play/官网，广告+IAP）
│       ├─ fdroid/                      F-Droid（GMS-free、零广告、无 IAP）
│       ├─ xiaomi/  huawei/             预留渠道
│       └─ admobAds/ noAds/             AdMob 实现（仅 pro，休眠）/ 零广告实现（fdroid）
├─ harmony/                             [平台] 鸿蒙 NEXT 工程（AppScope+entry+NAPI）
├─ ios/                                 [平台·预留] 未来 iOS（SwiftUI + .xcframework）
├─ desktop/                             [平台·预留] 未来 Win/Linux（暂缓）
│
├─ Builder/                             [共享 L0] 引擎源码 + JNI 胶水（harmony 也引用）
├─ prebuilt/                            [共享 L0 产物] native/(安卓4ABI) harmony/(2ABI) gradle 离线种子
├─ scripts/                             [共享工具] 离线构建种子脚本
├─ store/                               [发布] 按平台/商店的上架清单（见 §4 矩阵）
├─ ci/                                  [发布] 构建/合规闸门（预留）
├─ docs/                                [官网] Jekyll 营销站（与代码文档隔离）
├─ README.md CHANGES.md LICENSE.txt logo.jpg
└─ MULTI_PLATFORM.md                    本文档
```

已删除（2026-09-02）：KMP 试验种子 `composeApp/ shared/ iosApp/`（settings 曾注释、
不进任何构建，方案弃用）、原 `platform/` 占位目录（ios/desktop 已提升为一级）。

已删除（2026-09-02，GMS/Drive 彻底解耦）：Google Drive 同步功能在**所有版本里都是
死的**（`GFile.java` 依赖的 `com.google.api.client.*`/`com.google.api.services.drive.*`/
`GoogleSignIn` 真类全部来自 main 里手写的 14 个 `com/google/**` 假类，`:appLibDrive`
模块被注释且不存在，`getLastSignedInAccount` 恒返回 null，UI 入口早已隐藏）。因此整体删除：
`GFile.java` + 14 个 `com/google/**` 假类 + `src/gmsStubs/`（5 个假类）+
`SynctornizatoinWorker` + `GDriveSycnEvent` + `GoogleDriveFragment2` + 12 个 main 调用点。
main 从此零 GMS、零 Google 类型。同时删除 5 个上游遗留 UI 版本 flavor
（pdf_classic/ebooka/pdf_v2/tts_reader/epub_reader）及其源集与专属图标。

### 广告代码分层（2026-09-02 落地）
- 接口（main，零依赖）：`com.foobnix.ads.AdsProvider` + `RewardListener`；
  `com.foobnix.pdf.info.ADS` 纯门面（策略计时在门面，SDK 调用全委托）。
- 实现按变体编译（同 FQCN `AdsProviderFactory`，组间互斥）：
  - `src/admobAds`（`AdMobAdsProvider` + manifest overlay）：仅挂 **pro**（唯一带广告
    flavor；2026-09-09 起广告单元 ID 默认留空 = 休眠，启用只需在
    `~/.gradle/gradle.properties` 配 `pro_admob*` 属性，代码零改动）；
  - `src/noAds`（`NoAdsProvider`）：挂 fdroid → **APK 无任何广告 SDK 代码**。
- 新增广告网络 = 新源集实现 AdsProvider + 按渠道挂载；每包只编一个 provider；
  广告位 ID 经 manifest placeholder 按 flavor 注入。
- 原 `libPro/`（GMS/UMP no-op 假类）已删除；GMS/Drive 假类（`com/google/**` + gmsStubs）
  已删除；main grep 断言无 `gms.ads`/`ump`/`com.google.api`/`com.google.android.gms` 引用。

## 3. 构建命令（Ubuntu server；禁止 Windows 侧构建）

```bash
# 安卓（gradle 根 = android/）。版本号统一来自 app/gradle.properties
# （2026-09-02 起各 flavor 同版本，fdroid 不再固定 9.4.21/7174）：
ssh ... "source ~/.bashrc && cd /docker/opt/librera/LibreraReader/android \
  && ./gradlew :app:assembleProDebug :app:assembleFdroidDebug"

# 鸿蒙：cd .../harmony && bash build_hap.sh（未变）
```
各渠道可同批构建；fdroid 若单独调用只是输出目录习惯，不影响版本号。

## 4. 商店 × 广告 SDK 矩阵

| 平台 | 商店 | 代码形态 | 广告方案 | 关键要求 |
|---|---|---|---|---|
| Android | F-Droid | flavor fdroid | 无（NoAdsProvider，APK 零广告类） | 全开源依赖；**零广告 + 零 GMS**（无 `com.google.android.gms`/`com.google.api`）；版本与主渠道统一；去 USE_BIOMETRIC；CI 闸门 |
| Android | Google Play/官网 | flavor pro（旗舰/主渠道） | AdMob（src/admobAds；pro_* 属性→manifest；单元 ID 留空=休眠） | DATA SAFETY/隐私页；UMP 内置；IAP 预留（BillingManager 接口） |
| Android | 小米 | [预留] src/xiaomi | 按需 AdMob 或穿山甲/优量汇（新源集） | 备案/隐私；加固后重签 |
| Android | 华为(安卓包) | [预留] src/huawei | 华为 Ads(HMS) 或先无广告 | 无 GMS；AGC 签名 |
| HarmonyOS | 华为(鸿蒙包) | harmony/ | 暂缓（AppGallery 变现远期） | AGC Profile；PORTING_PLAN 补功能 |
| iOS | 苹果 | [预留] ios/ | 远期评估 AdMob iOS | TestFlight/隐私标签 |
| Desktop | Win/Linux | [预留] desktop/ | 无 | 暂缓（JVM/Compose 或 C++/Qt） |

## 5. 各端现状与差距
- **Android**（android/）：app 模块 Java 文件；flavor 现为 2 个（pro 旗舰/主渠道，广告
  SDK 挂载但休眠 + IAP 接口预留 `mobi.librera.libgoogle.BillingManager`；fdroid 纯净
  GMS-free 零广告）+ 预留 xiaomi/huawei；原主渠道 howread flavor（更早名 librera→google）
  已于 2026-09-09 删除；5 个上游遗留 UI 版本
  （pdf_classic/ebooka/pdf_v2/tts_reader/epub_reader）与 Google Drive 同步（GMS）已于
  2026-09-02 删除；版本号：app/gradle.properties
  （appVersionNumberBase/Index、appCodeNumber）；各 flavor 同版本。
- **鸿蒙**（harmony/）：NEXT API 24；ArkTS 10 文件；NAPI 22 函数；缺口表见 PORTING_PLAN.md。
- **可移植逻辑盘点**：`com/foobnix/ext`（格式解析近零 Android 依赖）、model/dao2、
  opds、webdav 传输核心、AiClient —— JVM 桌面/服务端可整块复用；UI 系深度绑定安卓。

## 6. 构建与 CI 规划
- Ubuntu server（192.168.50.111, lee）手工 SSH；ci/ 沉淀：build_android.sh 模板、
  F-Droid 闸门 `scan_apk_ads.py`（Z:\opt\librera\bench\，对 fdroid 产物字节扫描，
  检出广告 SDK 即失败；主渠道包反向断言含 AdMob）。
- 密钥只在构建机：~/.gradle/gradle.properties（RELEASE_* → android/howread.keystore）、
  harmony/signing/；不入库。

## 7. 迁移历史（2026-09-02 已完成，勿再执行）
原"仓库根=安卓工程"已整体迁入 android/（gradle 根 = android/）。迁移要点备忘：
- app/build.gradle jniLibs 用 `${rootDir}/../prebuilt/native/...`（rootDir=android/）；
- settings 里 `:Builder` 模块 projectDir=../Builder（引擎在共享根）；
- Builder/prebuilt/scripts 必须留在根（harmony CMake/restore 脚本硬依赖）；
- 外部工具已同步：Z:\opt\librera\build_remote.sh、build-librera.ps1、BUILD-README.md、
  bench 图标/重品牌脚本、Z:\opt\zcode\AGENTS.md；
- keystore 移至 android/，服务器 ~/.gradle RELEASE_STORE_FILE 已指向新路径。

## 8. 明确不做
Flutter/KMP 大重写、CloudRail 复活、三端业务层强行统一。
