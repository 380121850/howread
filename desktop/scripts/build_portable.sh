#!/bin/bash
# M6 portable package: dist + bundled Temurin JRE (runtime/) + native launcher
#   -> build/dist/HowRead-Pro-Win-v<ver>-x64.zip (unzip & run, no JDK needed).
# Prereqs on the build server (see tmp/scripts/m6_jre.sh & m6_launcher.sh):
#   /docker/opt/Workspace/HowRead/tmp/build/temurin21-jre-win-x64.zip
#   /docker/opt/Workspace/HowRead/tmp/build/HowReadPro.exe
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd) # desktop/
VER=$(cat "$ROOT/VERSION")
B=/docker/opt/Workspace/HowRead/tmp/build
DIST="$ROOT/build/dist"
STAGE_PARENT="$B/portable"
STAGE="$STAGE_PARENT/HowReadPro"
ZIPOUT="$DIST/HowRead-Pro-Win-v${VER}-x64.zip"

[ -f "$B/temurin21-jre-win-x64.zip" ] || { echo "missing JRE zip (run m6_jre.sh)"; exit 1; }
[ -f "$B/HowReadPro.exe" ] || { echo "missing launcher (run m6_launcher.sh)"; exit 1; }

cd "$ROOT" && ./gradlew :app:dist --console=plain -q
echo DIST_REBUILT

rm -rf "$STAGE_PARENT" && mkdir -p "$STAGE/engine"
cp "$B/HowReadPro.exe" "$STAGE/"
cp -r "$DIST/app" "$STAGE/app"
if [ -d "$DIST/tools" ]; then cp -r "$DIST/tools" "$STAGE/tools"; fi
cp "$ROOT/../prebuilt/windows/x86_64/mupdf_java64.dll" "$STAGE/engine/"

rm -rf "$B/jre-unpack" && mkdir -p "$B/jre-unpack"
unzip -q "$B/temurin21-jre-win-x64.zip" -d "$B/jre-unpack"
JD=$(ls -d "$B"/jre-unpack/*/ | head -1)
mv "$JD" "$STAGE/runtime"
rm -f "$STAGE/runtime/src.zip" 2>/dev/null || true


# HowRead desktop 2026-10-26: 发行包瘦身 v0.23.1（不碰源码/dev dist，仅此打包期生效）
# ① material-icons-extended 按需裁剪 ② 去他平台 skiko ③ sqlite-jdbc 只留 Win/x64 native ④ 删 JVM CDS 缓存
echo "---- slim stage ----"
rm -f "$STAGE"/app/skiko-awt-runtime-linux-*.jar "$STAGE"/app/skiko-awt-runtime-macos-*.jar
rm -f "$STAGE/runtime/bin/server/classes.jsa" "$STAGE/runtime/bin/server/classes_nocoops.jsa"
python3 - "$STAGE/app" <<'PYEOF'
import zipfile, glob, re, os, sys
app = sys.argv[1]
# 源码扫描：兼容 Icons.Filled.X / Icons.AutoMirrored.Filled.X / Icons.Default.X
# 注意：只取图标"名"（History 等），不比对变体——jar 内变体目录是小写(filled/...)，
# v0.23.1 首版按 (Filled,History) 全等匹配永不命中，23 个图标全被误裁（NoClassDefFoundError）。
names = set()
for root in glob.glob("/docker/opt/librera/LibreraReader/desktop/*/src/main/kotlin*"):
    for f in glob.glob(root + "/**/*.kt", recursive=True):
        txt = open(f, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r"Icons\.(?:AutoMirrored\.)?(?:Filled|Outlined|Rounded|Sharp|TwoTone|Default)\.([A-Za-z0-9_]+)", txt):
            names.add(m.group(1))
if len(names) < 5:
    sys.exit("icon scan found %d names - refusing to trim" % len(names))
print("icons used: %d" % len(names))
for jar in glob.glob(app + "/material-icons-extended-*.jar"):
    zin = zipfile.ZipFile(jar)
    keep = []
    for n in zin.namelist():
        m = re.match(r"androidx/compose/material/icons/[a-z0-9_]+/([A-Za-z0-9_]+)Kt\.class$", n)
        if m is None or m.group(1) in names or m.group(1).startswith("Icons") or m.group(1) == "AutoMirrored":
            keep.append(n)
    tmpf = jar + ".slim"
    with zipfile.ZipFile(tmpf, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in keep:
            zout.writestr(n, zin.read(n))
    zin.close()
    os.replace(tmpf, jar)
    print("icons slim: %s -> %d entries kept" % (os.path.basename(jar), len(keep)))

# sqlite-jdbc 只保留 Windows/x86_64 native（v0.57.1 体积优化；jar 内其余 ~14 平台 native 共 ~23MB 全部未用）
for jar in glob.glob(app + "/sqlite-jdbc-*.jar"):
    zin = zipfile.ZipFile(jar)
    keep = []
    drop = 0
    for n in zin.namelist():
        p = n.replace("\\", "/").split("/")
        if "native" in p:
            i = p.index("native")
            plat = p[i + 1] if i + 1 < len(p) else ""
            arch = p[i + 2] if i + 2 < len(p) else ""
            if plat != "Windows" or arch != "x86_64":
                drop += 1
                continue
        keep.append(n)
    tmpf = jar + ".slim"
    with zipfile.ZipFile(tmpf, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in keep:
            zout.writestr(n, zin.read(n))
    zin.close()
    os.replace(tmpf, jar)
    print("sqlite slim: %s -> %d entries kept (dropped %d native)" % (os.path.basename(jar), len(keep), drop))

PYEOF
echo SLIM_DONE

cd "$STAGE_PARENT"
rm -f "$ZIPOUT"
zip -qr9 "$ZIPOUT" HowReadPro
echo "---- zip summary ----"
ls -la "$ZIPOUT"
unzip -l "$ZIPOUT" | tail -2
echo PORTABLE_OK "$ZIPOUT"
