#!/usr/bin/env bash
# build.sh - compile the MonolithicOS launcher into a signed APK with the plain SDK tools (no Gradle).
#   ./build.sh        builds build/MonolithicOS.apk (and the phone app it carries, if it isn't built yet)
# Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0).
# Signing: MONOLITHIC_KEYSTORE / MONOLITHIC_KEYPASS for releases; otherwise a local dev key (dev.jks).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"
VERSION="$(cat ../VERSION)"
VERSION_CODE="${VERSION_CODE:-$(date +%y%m%d%H)}"

# the phone app rides inside the launcher (served to phones at http://<tv>:8899/app)
[ -f res/raw/remote_app.apk ] || { (cd ../remote && ./build.sh) && cp ../remote/build/remote.apk res/raw/remote_app.apk; }

OUT=build
rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"
echo "resources..."
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml \
  --java "$OUT/gen" --min-sdk-version 28 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION" "$OUT/res.zip"
echo "java..."
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -nowarn -Xlint:-options -source 11 -target 11 -encoding UTF-8 \
  -classpath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"
echo "dex..."
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
echo "package..."
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="${MONOLITHIC_KEYSTORE:-$PWD/dev.jks}"
KP="${MONOLITHIC_KEYPASS:-monolithic-dev}"
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass "$KP" -keypass "$KP" -alias dev \
  -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=MonolithicOS dev build" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KP" --key-pass "pass:$KP" --out "$OUT/MonolithicOS.apk" "$OUT/aligned.apk"
echo "built $OUT/MonolithicOS.apk ($(du -h "$OUT/MonolithicOS.apk" | cut -f1 | tr -d ' '))"
