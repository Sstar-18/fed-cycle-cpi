#!/bin/bash
set -e
SDK=${ANDROID_SDK:-/home/user/android-sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-34.0.0}
PLAT=$SDK/platforms/android-${PLATFORM:-35}/android.jar
JDK=${JDK_HOME:-/home/user/jdk17/bin}
ROOT=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$ROOT/../美联储周期.apk}
cd "$ROOT"
rm -rf build && mkdir -p build/gen build/classes build/dex

echo "== 1/6 资源 =="
$BT/aapt2 compile --dir res -o build/res.zip
echo "== 2/6 链接 =="
$BT/aapt2 link -o build/base.apk -I "$PLAT" --manifest AndroidManifest.xml \
  -R build/res.zip --java build/gen -A assets \
  --min-sdk-version 24 --target-sdk-version 30 \
  --version-code 9 --version-name 1.8 --auto-add-overlay
echo "== 3/6 Java =="
find src build/gen -name "*.java" > build/sources.txt
$JDK/javac -source 8 -target 8 -encoding UTF-8 -nowarn -classpath "$PLAT" -d build/classes @build/sources.txt
echo "== 4/6 dex =="
find build/classes -name "*.class" > build/classes.txt
$BT/d8 --release --min-api 24 --lib "$PLAT" --output build/dex @build/classes.txt
echo "== 5/6 打包 =="
cp build/base.apk build/unsigned.apk
cd build/dex && zip -q ../unsigned.apk classes.dex && cd "$ROOT"
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
echo "== 6/6 签名 =="
if [ ! -f debug.keystore ]; then
  $JDK/keytool -genkeypair -v -keystore debug.keystore -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Fed Terminal, OU=Dev, O=Dev, L=CN, S=CN, C=CN" > /dev/null 2>&1
fi
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" build/aligned.apk
$BT/apksigner verify --print-certs "$OUT" | head -3
ls -lh "$OUT"
