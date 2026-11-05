#!/bin/bash
set -e
# 路径全部基于脚本自身位置，工程可复制到任意目录编译。
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# Android SDK 可用环境变量覆盖，默认沿用本机路径。
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
BT=$ANDROID_HOME/build-tools/33.0.2
PLAT=$ANDROID_HOME/platforms/android-33
JAR=$PLAT/android.jar
PROJ=$SCRIPT_DIR/app
OUT=$SCRIPT_DIR/build
KS=$SCRIPT_DIR/yys.keystore
PKG=com.gesture.helper
VER=23
VNAME=1.22.0

if [ ! -d "$BT" ] || [ ! -d "$PLAT" ]; then
  echo "找不到 Android SDK（build-tools/33.0.2 或 platforms/android-33）。"
  echo "请设置 ANDROID_HOME 指向你的 SDK 根目录，例如："
  echo "  ANDROID_HOME=/path/to/android-sdk bash build.sh"
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/obj" "$OUT/gen"

echo "==> 1/6 aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$OUT/res.zip"

echo "==> 2/6 aapt2 link"
"$BT/aapt2" link -I "$JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  -o "$OUT/app.unsigned.apk" \
  --java "$OUT/gen" \
  -A "$PROJ/assets" \
  --min-sdk-version 26 --target-sdk-version 33 \
  --version-code "$VER" --version-name "$VNAME" \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "==> 3/6 javac"
find "$PROJ/src" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
javac --release 11 -nowarn -encoding UTF-8 \
  -classpath "$JAR" \
  -d "$OUT/obj" \
  @"$OUT/sources.txt"

echo "==> 4/6 d8"
mkdir -p "$OUT/dex"
find "$OUT/obj" -name "*.class" > "$OUT/classes.txt"
"$BT/d8" --release --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> 5/6 zipalign"
cp "$OUT/app.unsigned.apk" "$OUT/work.apk"
cd "$OUT/dex" && zip -q -X "$OUT/work.apk" classes.dex
"$BT/zipalign" -f -p 4 "$OUT/work.apk" "$OUT/app.aligned.apk"

echo "==> 6/6 sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias yys \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass yys123456 -keypass yys123456 \
    -dname "CN=YYS Assistant, OU=Dev, O=YYS, L=Beijing, ST=Beijing, C=CN" > /dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-key-alias yys \
  --ks-pass pass:yys123456 --key-pass pass:yys123456 \
  --out "$OUT/app.signed.apk" "$OUT/app.aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/app.signed.apk" | head -6
ls -lh "$OUT/app.signed.apk"
