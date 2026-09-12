#!/bin/bash
# Restore harmony/entry/libs/<abi>/libmupdf.so from the vendored prebuilt cache
# so the HAP can be built fully offline (no MuPDF cross-compile needed).
# To rebuild MuPDF from source instead: harmony/native/build_mupdf_harmony.sh
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

for abi in arm64-v8a x86_64; do
    src="$ROOT/prebuilt/harmony/mupdf-1.23.7/$abi/libmupdf.so"
    dst="$ROOT/harmony/entry/libs/$abi/libmupdf.so"
    if [ ! -f "$src" ]; then
        echo "missing $src" >&2
        exit 1
    fi
    mkdir -p "$(dirname "$dst")"
    cp -f "$src" "$dst"
    echo "restored $dst ($(stat -c%s "$dst") bytes)"
    # network protocol clients (SFTP/SMB); rebuild from source via
    # Builder/*/build-ohos-* + tmp script, see prebuilt/harmony/net/VERSIONS.txt
    for so in libssh2.so.1 libsmb2.so.1; do
        net="$ROOT/prebuilt/harmony/net/$abi/$so"
        if [ -f "$net" ]; then
            cp -f "$net" "$ROOT/harmony/entry/libs/$abi/$so"
            echo "restored $ROOT/harmony/entry/libs/$abi/$so"
        else
            echo "warning: missing $net (run the net-libs cross build)" >&2
        fi
    done
done
