#!/usr/bin/env bash
# Builds the Watch Face Format APK (resource-only) with aapt2 + apksigner.
set -uo pipefail
mkdir -p out ks
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
PLAT=$(ls -d "$ANDROID_HOME"/platforms/android-* | sort -V | tail -1)
VC=${GITHUB_RUN_NUMBER:-1}
echo "build-tools: $BT"; echo "platform: $PLAT"; echo "versionCode: $VC"

echo "=== WFF XSD validation ==="
if git clone -q --depth 1 https://github.com/google/watchface /tmp/wff; then
  find /tmp/wff -name "*.xsd" | sort | head -40
  for X in $(find /tmp/wff -name "watchface.xsd" | sort); do
    echo "--- $X"; xmllint --noout --schema "$X" tarcza/res/raw/watchface.xml 2>&1 | head -40
  done
else echo "clone failed"; fi

echo "=== aapt2 ==="
rm -rf /tmp/b && mkdir -p /tmp/b
"$BT/aapt2" compile --dir tarcza/res -o /tmp/b/res.zip || exit 1
"$BT/aapt2" link -o /tmp/b/unsigned.apk -I "$PLAT/android.jar" --manifest tarcza/AndroidManifest.xml \
  --min-sdk-version 33 --target-sdk-version 34 --version-code "$VC" --version-name "1.$VC" /tmp/b/res.zip || exit 1
"$BT/zipalign" -f 4 /tmp/b/unsigned.apk /tmp/b/aligned.apk || exit 1
if [ ! -f ks/key.jks ]; then
  keytool -genkeypair -keystore ks/key.jks -storepass android -keypass android -alias tarcza \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Tarcza" >/dev/null 2>&1
  echo "new signing key generated"
fi
"$BT/apksigner" sign --ks ks/key.jks --ks-pass pass:android --key-pass pass:android --out out/tarcza.apk /tmp/b/aligned.apk || exit 1
"$BT/apksigner" verify -v out/tarcza.apk | head -5
"$BT/aapt2" dump badging out/tarcza.apk | head -8
ls -l out/tarcza.apk
echo "BUILD OK"
