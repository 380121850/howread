# net-libs — 鸿蒙 SMB/SFTP 在线阅读协议栈三方库

`harmony` 的 SMB/SFTP native 客户端（`entry/src/main/cpp/remote_net.cpp`）链接的三个第三方库，
源码不入仓库（与 `Builder/mupdf*` 惯例一致，见根 `.gitignore`），本目录保存可复现的下载与
交叉编译脚本。

| 库 | 版本 | 许可证 | 链接形态 |
|---|---|---|---|
| openssl | 3.0.17 (LTS) | Apache-2.0 | 静态库（libcrypto.a/libssl.a，-fPIC），只链入 libssh2 |
| libssh2 | 1.11.1 | BSD-3 | 动态 `libssh2.so.1`（SFTP） |
| libsmb2 | tag `libsmb2-6.0` | LGPL-2.1 | 动态 `libsmb2.so.1`（SMB，内置加密不依赖 OpenSSL） |

三个库均为**未改动的上游源码**（无补丁，构建 out-of-tree，产物在各自 `build-ohos-*` 目录）。
编译产物留档在 `prebuilt/harmony/net/<abi>/lib/`（双 ABI arm64-v8a / x86_64），
打包用副本同步 stage 到 `harmony/entry/libs/<abi>/`（该目录 gitignore）。

## 重取 + 重编（在编译服务器 50.111 上）

```bash
source ~/.bashrc
bash Builder/net-libs/fetch_net_libs.sh     # 下载源码到 Builder/{openssl,libssh2,libsmb2}
bash Builder/net-libs/build_net_libs.sh     # 双 ABI 全量编译 + 收集产物
# 或只编一个 ABI: bash Builder/net-libs/build_net_libs.sh arm64-v8a
```

编译完成后 `prebuilt/harmony/net/` 与 `harmony/entry/libs/<abi>/` 自动更新，
随后重编鸿蒙 HAP（native 改动需卸载重装才能生效）。

## 注意

- `.so.1` 必须按 SONAME 命名打包，否则运行期加载失败；
- CMake 引用的头文件在 `Builder/{libsmb2,libssh2}/include`（源码目录），
  所以换新机器要重编时必须先跑 fetch 脚本；
- openssl Configure **不支持** `no-docs`（3.0.17）；SDK 自带 cmake/ninja 在
  `$DEVECO_SDK_HOME/build-tools/cmake/bin/`。
