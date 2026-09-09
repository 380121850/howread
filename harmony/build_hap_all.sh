#!/bin/bash
# Build the HowRead HarmonyOS HAP per variant (default = HowRead / pro =
# HowRead Pro) and per hardware platform (arm64 / x86_64), in DEBUG and
# RELEASE modes. Artifacts (lowercase dirs, per user requirement):
#
#   dist/debug/HowRead-v<ver>-<abi>-hmos.hap         (HowRead, howreaddebug: AGC debug cert + debug profile)
#   dist/debug/HowRead-v<ver>-x86_64-hmos.hap        (HowRead, emulator)
#   dist/release/HowRead-v<ver>-<abi>-hmos.hap       (HowRead, howreadrelease: AGC release cert + release profile)
#   dist/debug/HowRead-Pro-v<ver>-<abi>-hmos.hap     (HowRead Pro, legacy pro debug profile)
#   dist/release/HowRead-Pro-v<ver>-<abi>-hmos.hap   (HowRead Pro, legacy pro release profile)
#
# - bundleName lives ONLY in AppScope/app.json5 (products cannot override it),
#   so the pro variant temporarily rewrites app.json5 (bundleName) and the
#   AppScope app_name strings, and restores them on exit.
# - Each bundle has its own provisioning profile (debug + release), generated
#   by signing/gen_signing.sh + signing/gen_release_profile.sh; the project
#   build-profile.json5 holds one signingConfig per bundle (liberadebug /
#   liberaprodebug) and one product per variant (default / pro).
# - Each build packages exactly ONE ABI: abiFilters is set to the target and
#   the other prebuilt dirs under entry/libs are temporarily moved aside
#   (hvigor packs every libs/<abi> dir regardless of abiFilters).
# - The ABI part of the artifact name is derived from the ABIs actually
#   packaged inside the HAP (unzip -l), never assumed.
# - All toggles are reverted on exit so IDE/debug workflows keep defaults.
#
# Usage (via SSH, after `source ~/.bashrc`):
#   bash /docker/opt/librera/LibreraReader/harmony/build_hap_all.sh          # HowRead
#   bash /docker/opt/librera/LibreraReader/harmony/build_hap_all.sh pro      # HowRead Pro
set -e

VARIANT="${1:-default}"
case "$VARIANT" in
    default) BUNDLE="com.leestudio.howread.reader.hmos";;
    pro)     BUNDLE="com.leestudio.howread.pro.reader.hmos";;
    *) echo "ERROR: unknown variant '$1' (use: default | pro)" >&2; exit 1;;
esac

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

VER=$(sed -n 's/.*"versionName": *"\([^"]*\)".*/\1/p' AppScope/app.json5)
if [ -z "$VER" ]; then
    echo "ERROR: cannot read versionName from AppScope/app.json5" >&2
    exit 1
fi

DIST="$ROOT/dist"
STASH="$ROOT/.libs-abistash"
NAME_TAG=""; [ "$VARIANT" = "pro" ] && NAME_TAG="-Pro"
NAME_BASE="HowRead${NAME_TAG}-v${VER}"
# the hap output path embeds the product name (entry/build/<product>/outputs/<product>/)
OUT_HAP="entry/build/${VARIANT}/outputs/${VARIANT}/entry-${VARIANT}-signed.hap"
ABIS_ALL="arm64-v8a x86_64"

# temporarily point AppScope at this variant's bundle + display name;
# backups go OUTSIDE AppScope (hvigor scans every file under it) and are
# restored by cleanup()
BAK="$ROOT/.variantbak"
swap_variant() {
    mkdir -p "$BAK"
    python3 - "$VARIANT" "$BUNDLE" "$BAK" <<'PYEOF'
import json, shutil, sys

variant, bundle, bak = sys.argv[1], sys.argv[2], sys.argv[3]
app_json5 = 'AppScope/app.json5'
shutil.copy(app_json5, bak + '/app.json5')
src = open(app_json5).read()
src = src.replace('com.leestudio.howread.reader.hmos', bundle)
open(app_json5, 'w').write(src)
print('  app.json5 bundleName ->', bundle)

# app-level label (AppScope) AND ability label (entry module) — the launcher
# displays the ability label, so both must be swapped per variant
names = {'base': ('HowRead', 'HowRead Pro'), 'zh_CN': ('好好读', '好好读 Pro')}
for locale, (plain, pro) in names.items():
    for res in (
        f'AppScope/resources/{locale}/element/string.json',
        f'entry/src/main/resources/{locale}/element/string.json',
    ):
        shutil.copy(res, bak + '/' + res.replace('/', '_'))
        d = json.load(open(res))
        for s in d['string']:
            if s['name'] == 'app_name':
                s['value'] = pro if variant == 'pro' else plain
            if s['name'] == 'EntryAbility_label':
                s['value'] = pro if variant == 'pro' else plain
        json.dump(d, open(res, 'w'), ensure_ascii=False, indent=2)
        vals = [s['value'] for s in d['string'] if s['name'] in ('app_name', 'EntryAbility_label')]
        print(' ', res, '->', vals)
PYEOF
}

restore_variant() {
    if [ -f "$BAK/app.json5" ]; then
        mv -f "$BAK/app.json5" AppScope/app.json5
    fi
    local f base
    for f in "$BAK"/*.json; do
        [ -f "$f" ] || continue
        base=$(basename "$f")
        case "$base" in
            app.json5) ;;
            string-base.json)  mv -f "$f" AppScope/resources/base/element/string.json;;
            string-zh_CN.json) mv -f "$f" AppScope/resources/zh_CN/element/string.json;;
            AppScope_resources_base_element_string.json)
                               mv -f "$f" AppScope/resources/base/element/string.json;;
            AppScope_resources_zh_CN_element_string.json)
                               mv -f "$f" AppScope/resources/zh_CN/element/string.json;;
            entry_src_main_resources_base_element_string.json)
                               mv -f "$f" entry/src/main/resources/base/element/string.json;;
            entry_src_main_resources_zh_CN_element_string.json)
                               mv -f "$f" entry/src/main/resources/zh_CN/element/string.json;;
        esac
    done
    rm -rf "$BAK"
    echo "  (variant swap restored)"
}

cleanup() {
    # restore any stashed prebuilt ABI dirs and default (debug, dual-ABI) config
    local d
    for d in "$STASH"/*/; do
        [ -d "$d" ] || continue
        mv "$d" "$ROOT/entry/libs/" && echo "  (restored entry/libs/$(basename "$d"))"
    done
    set_build_mode debug "$ABIS_ALL" || true
    restore_variant
}
trap cleanup EXIT

swap_variant

# toggle signing (project build-profile.json5, per-variant signingConfig)
# and packaged ABIs (entry/build-profile.json5). $1 = "debug" | "release",
# $2 = abi list string
#
# default variant: AGC-issued materials — howreaddebug / howreadrelease
# signingConfigs (AGC certs + howread-*-sign.p12 keystores + locally signed
# profiles embedding the AGC leaf certs; swap the profile files when the
# AGC-issued .p7b arrive). pro variant keeps the legacy liberapro profiles
# until its own AGC materials exist.
set_build_mode() {
    local mode="$1"
    local abis="$2"
    python3 - "$mode" "$abis" "$VARIANT" <<'PYEOF'
import json, sys

mode, abis, variant = sys.argv[1], sys.argv[2], sys.argv[3]

bp = 'build-profile.json5'
d = json.load(open(bp))
if variant == 'default':
    config = 'howreaddebug' if mode == 'debug' else 'howreadrelease'
    for p in d['app']['products']:
        if p['name'] == 'default':
            p['signingConfig'] = config
    print('  default product signingConfig ->', config)
else:
    config = 'howreadprodebug' if mode == 'debug' else 'howreadprorelease'
    for p in d['app']['products']:
        if p['name'] == 'pro':
            p['signingConfig'] = config
    print('  pro product signingConfig ->', config)
json.dump(d, open(bp, 'w'), indent=2)

ep = 'entry/build-profile.json5'
e = json.load(open(ep))
e['buildOption']['externalNativeOptions']['abiFilters'] = abis.split()
json.dump(e, open(ep, 'w'), indent=2)
print('  abiFilters ->', abis)
PYEOF
}

# list the ABIs actually packaged inside a HAP: arm64-v8a -> arm64
haps_abi_tag() {
    local hap="$1"
    local abis=""
    local a
    while read -r a; do
        [ "$a" = "arm64-v8a" ] && a="arm64"
        abis="${abis:+$abis-}$a"
    done < <(unzip -l "$hap" | grep -o 'libs/[^/]*/' | cut -d/ -f2 | sort -u)
    echo "$abis"
}

# build one artifact: $1 = mode (debug|release), $2 = single ABI
build_one() {
    local mode="$1"
    local abi="$2"
    local others=""
    local a
    for a in $ABIS_ALL; do
        [ "$a" = "$abi" ] || others="$others $a"
    done

    # Round 7: regenerate the build timestamp consumed by the About version pill
    cat > "$ROOT/entry/src/main/ets/model/BuildInfo.ets" <<EOF
/**
 * Round 7: compile-time build info (Android com.foobnix.pdf.info.BuildTime parity).
 * Auto-generated by build_hap_all.sh; do not edit by hand.
 */
export const BUILD_TIME: string = '$(date -u '+%Y-%m-%d %H:%M' | sed 's/$/ UTC/')';
EOF

    set_build_mode "$mode" "$abi"

    # stash prebuilt dirs of the other ABIs (hvigor packs all libs/<abi>/)
    for a in $others; do
        if [ -d "$ROOT/entry/libs/$a" ]; then
            mkdir -p "$STASH"
            mv "$ROOT/entry/libs/$a" "$STASH/"
        fi
    done

    hvigorw assembleHap --mode module -p product="$VARIANT" -p buildMode="$mode" --console=plain

    for a in $others; do
        if [ -d "$STASH/$a" ]; then
            mv "$STASH/$a" "$ROOT/entry/libs/"
        fi
    done

    local tag name dir
    tag=$(haps_abi_tag "$OUT_HAP")
    name="${NAME_BASE}-${tag}-hmos.hap"
    mkdir -p "$DIST/$mode"
    cp -f "$OUT_HAP" "$DIST/$mode/$name"
    echo "    -> dist/$mode/$name"
}

echo "=== variant: $VARIANT | bundle: $BUNDLE | version: $VER ==="
# clean only THIS variant's artifacts so default and pro can coexist in dist/
rm -f "$DIST"/debug/HowRead${NAME_TAG}-v*-hmos.hap "$DIST"/release/HowRead${NAME_TAG}-v*-hmos.hap 2>/dev/null || true
echo "=== [1/5] DEBUG builds (debug signing) ==="
build_one debug arm64-v8a
build_one debug x86_64

echo "=== [2/5] RELEASE builds (release signing) ==="
build_one release arm64-v8a
build_one release x86_64

echo "=== [3/5] restore default build config (debug signing, dual ABI) ==="
set_build_mode debug "$ABIS_ALL"

echo "=== [4/5] artifact summary ==="
ls -l "$DIST/debug/" "$DIST/release/"
echo "=== [5/5] sha256 ==="
sha256sum "$DIST/debug/"*.hap "$DIST/release/"*.hap

echo "=== DONE ==="
