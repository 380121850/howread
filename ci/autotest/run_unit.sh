#!/usr/bin/env bash
# HowRead 单元层(JVM) + 集成层(Robolectric) 测试 —— 在 Ubuntu 构建服务器执行
# 用法: bash run_unit.sh [flavor]   (默认 pro)
set -e
FLAVOR="${1:-pro}"
FLAVOR_CAP="$(echo "$FLAVOR" | sed 's/^\(.\)/\U\1/')"
cd /docker/opt/librera/LibreraReader/android
echo "===== JVM 单元层 + Robolectric 集成层 ($FLAVOR) ====="
./gradlew :app:test${FLAVOR_CAP}DebugUnitTest --console=plain 2>&1 | tail -60
echo "===== 报告位置 ====="
echo "/docker/opt/librera/LibreraReader/android/app/build/reports/tests/test${FLAVOR_CAP}DebugUnitTest/index.html"
