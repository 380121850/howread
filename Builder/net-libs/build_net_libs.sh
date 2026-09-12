#!/bin/bash
# build_net_libs.sh — 交叉编译 openssl/libssh2/libsmb2 出 OHOS 双 ABI
#
# 用法（在编译服务器 50.111 上，须先 source ~/.bashrc 拿到 DEVECO 环境变量）:
#   bash Builder/net-libs/build_net_libs.sh          # 全量三库双 ABI
#   bash Builder/net-libs/build_net_libs.sh arm64-v8a|x86_64   # 只编一个 ABI
#
# 布局:
#   Builder/openssl        静态库 (-fPIC): libcrypto.a/libssl.a → $OUT/lib
#   Builder/libssh2        动态库 libssh2.so.1（链上面的静态 openssl）
#   Builder/libsmb2        动态库 libsmb2.so.1（内置加密，不依赖 OpenSSL）
#   产物安装到 $OUT（每个 ABI 一个 out 目录），随后:
#     - 复制到 prebuilt/harmony/net/<abi>/lib/ 留档（只留 4 个库文件）
#     - 复制 .so.1 到 harmony/entry/libs/<abi>/ 供 hvigor 打包
#
# 坑位备忘:
#   * .so 必须按 SONAME 命名（libssh2.so.1 / libsmb2.so.1），否则运行期加载失败;
#   * FindOpenSSL 受 ohos.toolchain 的 FIND_ROOT_PATH 限制: 需
#     -DCMAKE_PREFIX_PATH=$OUT 与 -DCMAKE_FIND_ROOT_PATH="<sysroot>;$OUT";
#   * openssl 3.0.17 的 Configure 不支持 no-docs 选项，不要加。
set -euo pipefail
REPO=${REPO:-/docker/opt/librera/LibreraReader}
BUILDER="$REPO/Builder"
DEVECO_HOME=${DEVECO_HOME:-/docker/opt/deveco-sdk/command-line-tools}
DEVECO_SDK_HOME=${DEVECO_SDK_HOME:-$DEVECO_HOME/sdk}
OHOS_SDK_HOME=${OHOS_SDK_HOME:-$DEVECO_SDK_HOME/default/openharmony}
LLVM=$OHOS_SDK_HOME/llvm/bin
TOOLCHAIN=$OHOS_SDK_HOME/native/build/cmake/ohos.toolchain.cmake
SYSROOT=$OHOS_SDK_HOME/native/sysroot
CMAKE=$DEVECO_SDK_HOME/build-tools/cmake/bin/cmake
NINJA=$DEVECO_SDK_HOME/build-tools/cmake/bin/ninja
PREBUILT=$REPO/prebuilt/harmony/net
ENTRY_LIBS=$REPO/harmony/entry/libs

ABIS=${1:-all}
[ "$ABIS" = all ] && ABIS="arm64-v8a x86_64"

build_openssl() {  # $1 = ABI
  local abi=$1 out=$BUILDER/build-net/$abi/openssl
  mkdir -p "$out"
  case $abi in
    arm64-v8a) local target=linux-aarch64 trip=aarch64-unknown-linux-ohos ;;
    x86_64)    local target=linux-x86_64  trip=x86_64-unknown-linux-ohos ;;
  esac
  if [ ! -f "$out/lib/libcrypto.a" ]; then
    (cd $BUILDER/openssl && \
      make distclean >/dev/null 2>&1 || true && \
      ./Configure $target shared=no-legacy \
        --prefix="$out" --libdir=lib \
        --cross-compile-prefix="$LLVM/$trip-" \
        -fPIC && \
      make -j"$(nproc)" && make install_sw)
  fi
}

build_libssh2() {  # $1 = ABI
  local abi=$1 out=$BUILDER/build-net/$abi
  rm -rf $BUILDER/libssh2/build-ohos-$abi
  cmake -S $BUILDER/libssh2 -B $BUILDER/libssh2/build-ohos-$abi -G Ninja \
    -DCMAKE_MAKE_PROGRAM=$NINJA \
    -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN -DOHOS_ARCH=$abi \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DBUILD_EXAMPLES=OFF \
    -DCMAKE_INSTALL_PREFIX=$out \
    -DCMAKE_PREFIX_PATH=$out/openssl \
    -DCMAKE_FIND_ROOT_PATH="$SYSROOT;$out/openssl" \
    -DOPENSSL_ROOT_DIR=$out/openssl \
    -DOPENSSL_USE_STATIC_LIBS=TRUE
  cmake --build $BUILDER/libssh2/build-ohos-$abi -j"$(nproc)"
  cmake --install $BUILDER/libssh2/build-ohos-$abi
}

build_libsmb2() {  # $1 = ABI
  local abi=$1 out=$BUILDER/build-net/$abi
  rm -rf $BUILDER/libsmb2/build-ohos-$abi
  cmake -S $BUILDER/libsmb2 -B $BUILDER/libsmb2/build-ohos-$abi -G Ninja \
    -DCMAKE_MAKE_PROGRAM=$NINJA \
    -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN -DOHOS_ARCH=$abi \
    -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
    -DCMAKE_INSTALL_PREFIX=$out
  cmake --build $BUILDER/libsmb2/build-ohos-$abi -j"$(nproc)"
  cmake --install $BUILDER/libsmb2/build-ohos-$abi
}

collect() {  # $1 = ABI — 只留 4 个库文件（解引用符号链接），按 SONAME 命名
  local abi=$1 out=$BUILDER/build-net/$abi dest=$PREBUILT/$abi/lib
  mkdir -p "$dest"
  for f in libcrypto.a libssl.a; do cp -f "$out/openssl/lib/$f" "$dest/"; done
  cp -fL "$out/lib/libssh2.so.1" "$dest/libssh2.so.1"
  cp -fL "$out/lib/libsmb2.so.1" "$dest/libsmb2.so.1"
  mkdir -p "$ENTRY_LIBS/$abi"
  cp -f "$dest/libssh2.so.1" "$dest/libsmb2.so.1" "$ENTRY_LIBS/$abi/"
}

for abi in $ABIS; do
  echo "======== $abi ========"
  build_openssl "$abi"
  build_libssh2 "$abi"
  build_libsmb2 "$abi"
  collect "$abi"
done

cat > $PREBUILT/VERSIONS.txt <<EOF
openssl 3.0.17 (static, linked into libssh2/libsmb2)
libssh2 1.11.1 (shared)
libsmb2 libsmb2-6.0 (shared, internal crypto)
toolchain: OHOS NDK llvm clang, ohos.toolchain.cmake
$(date)
EOF
echo "done. artifacts:"
find $PREBUILT -type f | sort
