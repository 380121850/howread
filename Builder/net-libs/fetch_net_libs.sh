#!/bin/bash
# fetch_net_libs.sh — 下载鸿蒙 SMB/SFTP 协议栈三方库源码到 Builder/
#
# 用法（在编译服务器 50.111 上）:
#   bash Builder/net-libs/fetch_net_libs.sh
#
# 版本（与 prebuilt/harmony/net/VERSIONS.txt 对应）:
#   openssl  3.0.17 (LTS)   https://www.openssl.org/source/          Apache-2.0
#   libssh2  1.11.1         https://www.libssh2.org/                 BSD-3
#   libsmb2  tag libsmb2-6.0 https://github.com/smb2-client/libsmb2  LGPL-2.1（动态链接 .so.1）
#
# 说明:
#   - openssl / libssh2 取官方发布包，解压为普通源码目录（不带 .git）;
#   - libsmb2 用 git clone 固定 tag（其 .git 保留无妨，源码目录已 gitignore）;
#   - 已存在的源码目录跳过，不覆盖。
set -euo pipefail
REPO=${REPO:-/docker/opt/librera/LibreraReader}
BUILDER="$REPO/Builder"
cd "$BUILDER"

# ---------- openssl 3.0.17 ----------
if [ ! -d openssl ]; then
  curl -fLO https://www.openssl.org/source/openssl-3.0.17.tar.gz
  tar xzf openssl-3.0.17.tar.gz
  mv openssl-3.0.17 openssl
  rm openssl-3.0.17.tar.gz
fi

# ---------- libssh2 1.11.1 ----------
if [ ! -d libssh2 ]; then
  curl -fLO https://www.libssh2.org/download/libssh2-1.11.1.tar.gz
  tar xzf libssh2-1.11.1.tar.gz
  mv libssh2-1.11.1 libssh2
  rm libssh2-1.11.1.tar.gz
fi

# ---------- libsmb2 (tag libsmb2-6.0) ----------
if [ ! -d libsmb2 ]; then
  git clone --branch libsmb2-6.0 --depth 1 https://github.com/smb2-client/libsmb2.git libsmb2
fi

echo "fetch done:"
du -sh openssl libssh2 libsmb2 2>/dev/null
