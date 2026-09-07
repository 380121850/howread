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

echo "=== 5/5 debug profile (bundle com.foobnix.pdf.reader) ==="
# The profile JSON must embed the leaf app cert (first PEM block of the chain);
# schema mirrors the DevEco IDE debug p7b dumped earlier.
awk '/-----BEGIN CERTIFICATE-----/{f=1} f{print} /-----END CERTIFICATE-----/&&f{exit}' \
    app-sign-cert.cer > leaf-cert.pem

python3 - <<'PYEOF'
import json, uuid

leaf = open('leaf-cert.pem').read()
profile = {
    "version-name": "1.0.0",
    "version-code": 1,
    "uuid": str(uuid.uuid4()),
    "type": "debug",
    "bundle-info": {
        "developer-id": "librera",
        "development-certificate": leaf,
        "bundle-name": "com.howread.reader",
        "apl": "normal",
        "app-feature": "hos_normal_app",
        "app-identifier": "6918739742083506800"
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
open('debug-profile.json', 'w').write(json.dumps(profile, indent=2))
print('debug-profile.json written')
PYEOF

SIGN sign-profile -mode localSign -keyAlias profile-sign-key -keyPwd "$PW" \
    -profileCertFile provision-profile-cert.cer -inFile debug-profile.json \
    -signAlg SHA256withECDSA -keystoreFile "$STORE" -keystorePwd "$PW" \
    -outFile librera-debug.p7b

SIGN verify-profile -inFile librera-debug.p7b -outFile verify-result.json
python3 -c "
import json
r = json.load(open('verify-result.json'))
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
m = d['app']['signingConfigs'][0]['material']
m['storePassword'] = store_enc
m['keyPassword'] = key_enc
json.dump(d, open(bp, 'w'), indent=2)
print('build-profile.json5 passwords updated (encrypted, hvigor-compatible)')
PYEOF

echo ""
echo "=== DONE. Material for build-profile.json5: ==="
echo "  storeFile : ./signing/$STORE        (plain storePassword: $PW)"
echo "  certpath  : ./signing/app-sign-cert.cer"
echo "  profile   : ./signing/librera-debug.p7b"
echo "  keyAlias  : app-sign-key             (plain keyPassword: $PW)"
