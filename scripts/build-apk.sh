#!/usr/bin/env bash
# 不依赖 Gradle / Android Studio 的轻量构建脚本。
# 只需要 Android SDK 的 build-tools 35.0.0 与 platforms/android-35。
# 用法：ANDROID_SDK_ROOT=/opt/android-sdk bash scripts/build-apk.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
BUILD="$ROOT/build"
OUT="$ROOT/apk/TattooStencil-v1.0.0.apk"

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM"; do
  [ -e "$f" ] || { echo "缺少：$f  请先安装 Android SDK 组件"; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/apk"

echo "[1/8] 生成签名密钥（临时自签名，密码运行时随机生成，不入库）"
KEYSTORE="$BUILD/stencil.jks"
STORE_PASS="$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
keytool -genkeypair -v -keystore "$KEYSTORE" -alias stencil \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
  -dname "CN=Tattoo Stencil, OU=OpenSource, O=Stencil, L=CN, C=CN" > /dev/null 2>&1

echo "[2/8] 编译资源"
"$BT/aapt2" compile --dir app/src/main/res -o "$BUILD/compiled/res.zip"

echo "[3/8] 链接资源并生成 R.java"
"$BT/aapt2" link -o "$BUILD/app-unsigned.apk" \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  -R "$BUILD/compiled/res.zip" \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name 1.0.0

echo "[4/8] 编译 Java 源码"
find app/src/main/java "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac --release 8 -encoding UTF-8 -cp "$PLATFORM" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[5/8] 转换为 DEX"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 24 --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[6/8] 打包 DEX"
( cd "$BUILD/dex" && zip -q "$BUILD/app-unsigned.apk" classes.dex )

echo "[7/8] 对齐"
"$BT/zipalign" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "[8/8] 签名并校验"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$STORE_PASS" --v1-signing-enabled true --v2-signing-enabled true \
  --v4-signing-enabled false \
  --out "$OUT" "$BUILD/app-aligned.apk"
"$BT/apksigner" verify --verbose "$OUT" | head -12

echo
echo "构建完成：$OUT"
ls -lh "$OUT"
