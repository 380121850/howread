#!/bin/bash
# HowRead 桌面应用构建：gradle :app:dist → desktop/build/dist/{app,engine,run.*}
set -e
set -o pipefail
cd /docker/opt/librera/LibreraReader/desktop
./gradlew :app:dist --console=plain 2>&1 | tail -25
echo "===== tools ====="
mkdir -p build/dist/tools
cp -f ../prebuilt/windows/x86_64/tools/mobitool.exe build/dist/tools/ 2>/dev/null || echo "WARN: no windows mobitool"
cp -f ../prebuilt/linux/x86_64/tools/mobitool build/dist/tools/ 2>/dev/null || true
ls -la build/dist/tools/

echo "===== dist 产物 ====="
ls -la build/dist/ | head -10
ls build/dist/app/ | head -5
echo "... app jars: $(ls build/dist/app/ | wc -l)"
ls -la build/dist/engine/
echo APP_BUILD_DONE
