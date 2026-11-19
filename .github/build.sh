#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKM" ] || SDKM="$(command -v sdkmanager)"
yes | "$SDKM" "build-tools;35.0.0" > /dev/null || true

chmod +x gradlew
./gradlew :src:es:picazor:assembleDebug -x lint -x ktlintCheck

echo "$KEYSTORE_BASE64" | base64 -d > /tmp/ks.jks
APK=$(find src/es/picazor/build/outputs/apk/debug -name '*.apk' | head -n1)
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n1)
"$BT/zipalign" -f 4 "$APK" /tmp/a.apk
"$BT/apksigner" sign --ks /tmp/ks.jks --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASSWORD" \
  --out /tmp/picazor.apk /tmp/a.apk
