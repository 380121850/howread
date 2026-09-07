#!/bin/bash
# Generate a full self-signed HarmonyOS signing chain on Ubuntu.
# No DevEco IDE / Huawei cloud needed; produces material for hvigor CLI builds.
# Usage: bash gen_signing.sh
set -e

SIGN_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SIGN_DIR"

TOOL_JAR="/docker/opt/deveco-sdk/command-line-tools/sdk/default/openharmony/toolchains/lib/hap-sign-tool.jar"
SIGN() { java -jar "$TOOL_JAR" "$@"; }

# All private keys live in one p12; plain passwords are fine for hvigor CLI builds.
# Override via LIBRERA_SIGN_PW if you want a different keystore password.
STORE="librera-sign.p12"
PW="${LIBRERA_SIGN_PW:-LibreraDebug}"

SUBJ_ROOT='C=CN,O=Librera,OU=Librera Community,CN=Librera Root CA'
SUBJ_SUB_APP='C=CN,O=Librera,OU=Librera Community,CN=Librera Application CA'
SUBJ_SUB_PROFILE='C=CN,O=Librera,OU=Librera Community,CN=Librera Profile CA'
SUBJ_APP='C=CN,O=Librera,OU=Librera Community,CN=Librera App Debug'
SUBJ_PROFILE='C=CN,O=Librera,OU=Librera Community,CN=Librera Profile Sign'

echo "=== 0/5 clean previous material ==="
rm -f "$STORE" *.cer *.p7b leaf-cert.pem verify-result.json

echo "=== 1/5 keypairs (ECC NIST-P256) ==="
for alias in root-ca-key sub-app-ca-key app-sign-key sub-profile-ca-key profile-sign-key; do
    SIGN generate-keypair -keyAlias "$alias" -keyPwd "$PW" -keyAlg ECC -keySize NIST-P-256 \
        -keystoreFile "$STORE" -keystorePwd "$PW"
done

echo "=== 2/5 self-signed root CA ==="
SIGN generate-ca -keyAlias root-ca-key -keyPwd "$PW" -keyAlg ECC -keySize NIST-P-256 \
    -subject "$SUBJ_ROOT" -validity 3650 -signAlg SHA256withECDSA \
    -keystoreFile "$STORE" -keystorePwd "$PW" -outFile root-ca.cer

echo "=== 3/5 intermediate CAs (signed by root) ==="
SIGN generate-ca -keyAlias sub-app-ca-key -keyPwd "$PW" -keyAlg ECC -keySize NIST-P-256 \
    -issuer "$SUBJ_ROOT" -issuerKeyAlias root-ca-key -issuerKeyPwd "$PW" \
    -subject "$SUBJ_SUB_APP" -validity 3650 -signAlg SHA256withECDSA \
    -keystoreFile "$STORE" -keystorePwd "$PW" -outFile sub-app-ca.cer

SIGN generate-ca -keyAlias sub-profile-ca-key -keyPwd "$PW" -keyAlg ECC -keySize NIST-P-256 \
    -issuer "$SUBJ_ROOT" -issuerKeyAlias root-ca-key -issuerKeyPwd "$PW" \
    -subject "$SUBJ_SUB_PROFILE" -validity 3650 -signAlg SHA256withECDSA \
    -keystoreFile "$STORE" -keystorePwd "$PW" -outFile sub-profile-ca.cer

echo "=== 4/5 app + profile cert chains ==="
SIGN generate-app-cert -keyAlias app-sign-key -keyPwd "$PW" \
    -issuer "$SUBJ_SUB_APP" -issuerKeyAlias sub-app-ca-key -issuerKeyPwd "$PW" \
    -subject "$SUBJ_APP" -validity 3650 -signAlg SHA256withECDSA \
    -rootCaCertFile root-ca.cer -subCaCertFile sub-app-ca.cer \
    -keystoreFile "$STORE" -keystorePwd "$PW" \
    -outForm certChain -outFile app-sign-cert.cer

SIGN generate-profile-cert -keyAlias profile-sign-key -keyPwd "$PW" \
    -issuer "$SUBJ_SUB_PROFILE" -issuerKeyAlias sub-profile-ca-key -issuerKeyPwd "$PW" \
    -subject "$SUBJ_PROFILE" -validity 3650 -signAlg SHA256withECDSA \
    -rootCaCertFile root-ca.cer -subCaCertFile sub-profile-ca.cer \
    -keystoreFile "$STORE" -keystorePwd "$PW" \
    -outForm certChain -outFile provision-profile-cert.cer

echo "=== 5/5 debug profiles (HowRead reader + HowRead Pro) ==="
# The profile JSON must embed the leaf app cert (first PEM block of the chain);
# schema mirrors the DevEco IDE debug p7b dumped earlier. The profile is what
# binds the bundle-name, so each bundle gets its own profile; the app cert
# chain itself is shared.
awk '/-----BEGIN CERTIFICATE-----/{f=1} f{print} /-----END CERTIFICATE-----/&&f{exit}' \
    app-sign-cert.cer > leaf-cert.pem

python3 - <<'PYEOF'
import json, uuid

leaf = open('leaf-cert.pem').read()

def make_profile(bundle, app_identifier):
    return {
        "version-name": "1.0.0",
        "version-code": 1,
        "uuid": str(uuid.uuid4()),
        "type": "debug",
        "bundle-info": {
            "developer-id": "librera",
            "development-certificate": leaf,
            "bundle-name": bundle,
            "apl": "normal",
            "app-feature": "hos_normal_app",
            "app-identifier": app_identifier
        },
        "baseapp-info": {},
        "permissions": {},
        "debug-info": {
            # Pura 90 emulator UDID (from bm get --udid)
            "device-ids": ["454D55057494E0583836082DA663EEF634EF6A2718590C023D33A00000000000"],
            "device-id-type": "udid"
        },
        "acls": {"allowed-acls": []},
        "issuer": "librera"
    }

open('debug-profile.json', 'w').write(json.dumps(
    make_profile("com.leestudio.howread.reader.hmos", "6918739742083506800"), indent=2))
open('pro-profile.json', 'w').write(json.dumps(
    make_profile("com.leestudio.howread.pro.reader.hmos", "6918739742083506801"), indent=2))
print('debug-profile.json + pro-profile.json written')
PYEOF

SIGN sign-profile -mode localSign -keyAlias profile-sign-key -keyPwd "$PW" \
    -profileCertFile provision-profile-cert.cer -inFile debug-profile.json \
    -signAlg SHA256withECDSA -keystoreFile "$STORE" -keystorePwd "$PW" \
    -outFile librera-debug.p7b

SIGN sign-profile -mode localSign -keyAlias profile-sign-key -keyPwd "$PW" \
    -profileCertFile provision-profile-cert.cer -inFile pro-profile.json \
    -signAlg SHA256withECDSA -keystoreFile "$STORE" -keystorePwd "$PW" \
    -outFile librera-pro-debug.p7b

SIGN verify-profile -inFile librera-debug.p7b -outFile verify-result.json
python3 -c "
import json
r = json.load(open('verify-result.json'))
c = r['content']
print('verified:', r['verifiedPassed'])
print('type:', c.get('type'), '| bundle:', c['bundle-info'].get('bundle-name'))
"

SIGN verify-profile -inFile librera-pro-debug.p7b -outFile verify-pro-result.json
python3 -c "
import json
r = json.load(open('verify-pro-result.json'))
c = r['content']
print('verified:', r['verifiedPassed'])
print('type:', c.get('type'), '| bundle:', c['bundle-info'].get('bundle-name'))
"

echo ""
echo "=== 6/6 hvigor material dir + encrypted passwords ==="
node "$(dirname "$0")/make_material.js" "$PW"

python3 - <<'PYEOF'
import json

store_enc = open('enc_store.txt').read().strip()
key_enc = open('enc_key.txt').read().strip()
bp = '../build-profile.json5'
d = json.load(open(bp))
# only touch signingConfigs that use THIS script's keystore (librera-sign.p12);
# other families (e.g. the howread-*-sign.p12 material) keep their own
# separately-encrypted passwords
n = 0
for cfg in d['app']['signingConfigs']:
    if cfg.get('material', {}).get('storeFile', '').endswith('librera-sign.p12'):
        cfg['material']['storePassword'] = store_enc
        cfg['material']['keyPassword'] = key_enc
        n += 1
json.dump(d, open(bp, 'w'), indent=2)
print(f'build-profile.json5 passwords updated for {n} librera-material signingConfigs (encrypted, hvigor-compatible)')
PYEOF

echo ""
echo "=== DONE. Material for build-profile.json5: ==="
echo "  storeFile : ./signing/$STORE        (plain storePassword: $PW)"
echo "  certpath  : ./signing/app-sign-cert.cer"
echo "  profile   : ./signing/librera-debug.p7b"
echo "  keyAlias  : app-sign-key             (plain keyPassword: $PW)"
