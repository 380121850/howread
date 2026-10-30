#!/bin/bash
# HowRead 桌面引擎统一构建（CMake 路线）：build_engine_cmake.sh <linux|windows|all>
# 产物：prebuilt/linux/x86_64/{libmupdf_java64.so, libmupdf.jar}
#       prebuilt/windows/x86_64/{mupdf_java64.dll, libmupdf.jar}
# 用法（50.111）：bash desktop/scripts/build_engine_cmake.sh linux   # 或 windows / all
set -e
ROOT=/docker/opt/librera/LibreraReader
MUPDF=$ROOT/Builder/mupdf-1.23.7
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
TARGET=${1:-all}

build_jar() {
  echo "== [jar] 编译 fitz Java 绑定 → libmupdf.jar =="
  local JC=$ROOT/desktop/build/jar-classes
  rm -rf "$JC" && mkdir -p "$JC"
  javac -nowarn -source 1.7 -target 1.7 -d "$JC" $MUPDF/platform/java/src/com/artifex/mupdf/fitz/*.java 2>&1 | grep -v 'obsolete\|bootstrap' || true
  ( cd "$JC" && jar cf $ROOT/desktop/build/libmupdf.jar com )
  ls -la $ROOT/desktop/build/libmupdf.jar
}

build_target() {
  local plat=$1
  echo "== [$plat] cmake configure + build =="
  local BD=$ROOT/desktop/build/engine-$plat
  mkdir -p "$BD"
  if [ "$plat" = windows ]; then
    cmake -S $ROOT/desktop/engine -B "$BD" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ROOT/desktop/engine/mingw-toolchain.cmake \
      -DCMAKE_BUILD_TYPE=Release > /dev/null
  else
    cmake -S $ROOT/desktop/engine -B "$BD" -G Ninja -DCMAKE_BUILD_TYPE=Release > /dev/null
  fi
  cmake --build "$BD" -j 16 2>&1 | tail -5
  local OUT=$ROOT/prebuilt/$plat/x86_64
  mkdir -p "$OUT"
  if [ "$plat" = windows ]; then
    cp -f "$BD/mupdf_java64.dll" "$OUT/"
    cp -f $ROOT/desktop/build/libmupdf.jar "$OUT/"
    ls -la "$OUT"
  else
    cp -f "$BD/libmupdf_java64.so" "$OUT/"
    cp -f $ROOT/desktop/build/libmupdf.jar "$OUT/"
    ls -la "$OUT"
  fi
}

cd $ROOT
mkdir -p $ROOT/desktop/build
build_jar
case $TARGET in
  linux)   build_target linux ;;
  windows) build_target windows ;;
  all)     build_target linux; build_target windows ;;
  *) echo "usage: $0 <linux|windows|all>"; exit 1 ;;
esac
echo "ENGINE BUILD DONE ($TARGET)"
