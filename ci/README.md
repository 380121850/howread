# ci/ — 构建与合规脚本（预留）

现状：安卓与鸿蒙都在 Ubuntu server 上手工 SSH 构建（AGENTS.md 有命令）。
本目录将来存放可重复执行的脚本与流水线模板，包括：

- `build_android.sh`：封装 gradle 双变体构建（pro / fdroid debug+release；2026-09-09
  flavor 缩减为 2 个，原主渠道 howread flavor 已删除）；
- `build_hap.sh` 引用：鸿蒙构建脚本在 `harmony/build_hap.sh`；
- `check_fdroid_no_ads.sh`：**F-Droid 合规闸门** —— 对 fdroid APK 运行
  `Z:\opt\librera\bench\scan_apk_ads.py`（dex/清单字节扫描），
  任何广告 SDK 标记（`com/google/android/gms/ads`、`com/google/android/ump`、
  `play-services-ads`、穿山甲/华为/腾讯等）即失败；主渠道包则反向断言含 AdMob 实现。
- 签名/密钥只从构建机环境或本地 keystore 读取，**绝不入库**（release:
  `~/.gradle/gradle.properties` 的 RELEASE_*；鸿蒙 `harmony/signing/`）。

将来接 CI（GitHub Actions 等）时按此目录约定组织 workflow，仍只在受控 runner
构建——不要在 Windows 侧构建（跨盘路径问题，见 AGENTS.md）。
