#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-/usr/local/lib/android/sdk}"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
[ -x "$SDKM" ] || SDKM="$(command -v sdkmanager)"
yes | "$SDKM" "build-tools;35.0.0" > /dev/null || true

VER="${VERSION#v}"
CODE="${GITHUB_RUN_NUMBER}"

F=src/es/picazor/build.gradle.kts
sed -i -E "s/versionCode = [0-9]+/versionCode = $CODE/" "$F"
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$VER\"/" "$F"

chmod +x gradlew
./gradlew :src:es:picazor:assembleRelease -x lint

echo "$KEYSTORE_BASE64" | base64 -d > /tmp/ks.jks
APK=$(find src/es/picazor/build/outputs/apk/release -name '*.apk' | head -n1)
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n1)
"$BT/zipalign" -f 4 "$APK" /tmp/a.apk
"$BT/apksigner" sign --ks /tmp/ks.jks --ks-pass "pass:$KEYSTORE_PASSWORD" \
  --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASSWORD" \
  --out "/tmp/picazor-v$VER.apk" /tmp/a.apk

mkdir -p /tmp/site
cp "/tmp/picazor-v$VER.apk" /tmp/site/
printf '[{"name":"Picazor","pkg":"eu.kanade.tachiyomi.extension.es.picazor","apk":"picazor-v%s.apk","lang":"es","code":%s,"version":"%s","nsfw":1,"sources":[{"name":"Picazor","lang":"es","id":"1086350406","baseUrl":"https://picazor.com"}]}]' "$VER" "$CODE" "$VER" > /tmp/site/index.min.json
cp /tmp/site/index.min.json /tmp/site/index.json

cd /tmp/site
git init -q
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git checkout -qb gh-pages
git add -A
git commit -qm "Picazor v$VER"
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
git push -f origin gh-pages
