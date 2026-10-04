#!/usr/bin/env bash
# OpenFilm6K camera build — no-Gradle pipeline.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"

: "${ANDROID_SDK:=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}}"
: "${ANDROID_NDK:=$ANDROID_SDK/ndk/16.1.4479499}"
: "${BUILD_TOOLS:=30.0.3}"
: "${PLATFORM_JAR:=$ANDROID_SDK/platforms/android-28/android.jar}"
BT="$ANDROID_SDK/build-tools/$BUILD_TOOLS"
if [ -n "${JAVA_HOME:-}" ]; then JAVA="$JAVA_HOME/bin"; else JAVA="$(dirname "$(command -v javac)")"; fi
export PATH="$JAVA:$PATH"
AJ="$PLATFORM_JAR"

for f in "$AJ" "$BT/aapt" "$BT/zipalign" "$BT/apksigner" "$ANDROID_SDK/build-tools/36.0.0/lib/d8.jar" "$ANDROID_NDK/ndk-build"; do
  [ -e "$f" ] || { echo "missing: $f" >&2; exit 1; }
done

echo "[0/7] ndk-build (armeabi, android-14, GCC 4.9)"
"$ANDROID_NDK/ndk-build" NDK_PROJECT_PATH="$ROOT" APP_BUILD_SCRIPT="$ROOT/jni/Android.mk" \
  NDK_APPLICATION_MK="$ROOT/jni/Application.mk" NDK_LIBS_OUT="$ROOT/out/libs" \
  NDK_OUT="$ROOT/out/obj" -j"$(nproc 2>/dev/null || echo 4)"

# ---- build number: shown on screen so on-camera debugging can tell builds apart ----
BUILD_NUM=$(( $(cat "$ROOT/.build" 2>/dev/null || echo 0) + 1 ))
echo "$BUILD_NUM" > "$ROOT/.build"
echo "building #$BUILD_NUM"
mkdir -p out/buildres/values
cat > out/buildres/values/build.xml <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <add-resource name="build_number" type="integer"/>
    <integer name="build_number">$BUILD_NUM</integer>
</resources>
EOF

rm -rf out/gen out/classes out/dex out/apklib
mkdir -p out/gen out/classes out/dex out/apklib/lib/armeabi
cp -f out/libs/armeabi/libof6k.so out/apklib/lib/armeabi/

echo "[1/7] aapt R.java"
"$BT/aapt" package -f -m -J out/gen -M AndroidManifest.xml -S out/buildres -S res -I "$AJ"
echo "[2/7] javac"
"$JAVA/javac" -encoding UTF-8 --release 8 -Xlint:-options -cp "$AJ" -d out/classes \
  out/gen/com/openfilm6k/camera/R.java src/com/openfilm6k/camera/*.java
echo "[3/7] d8"
find out/classes -name '*.class' > out/classes.txt
"$JAVA/java" -cp "$ANDROID_SDK/build-tools/36.0.0/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 10 \
  --lib "$AJ" --output out/dex "@out/classes.txt"
echo "[4/7] aapt package + dex + native lib"
"$BT/aapt" package -f -M AndroidManifest.xml -S out/buildres -S res -I "$AJ" -F out/unaligned.apk
( cd out/dex   && "$BT/aapt" add ../unaligned.apk classes.dex )
( cd out/apklib && "$BT/aapt" add ../unaligned.apk lib/armeabi/libof6k.so )
( "$BT/aapt" add out/unaligned.apk assets/DSEG14.ttf )
echo "[5/7] zipalign"
"$BT/zipalign" -f 4 out/unaligned.apk out/aligned.apk

echo "[6/7] sign (v1 only)"
if [ -n "${ANDROID_KEYSTORE_B64:-}" ]; then
  KS="${RUNNER_TEMP:-$ROOT/out}/release.keystore"
  printf '%s' "$ANDROID_KEYSTORE_B64" | base64 -d > "$KS"
  KS_PASS="${ANDROID_KEYSTORE_PASSWORD:?}" KEY_PASS="${ANDROID_KEY_PASSWORD:?}" \
  "$BT/apksigner" sign --ks "$KS" --ks-key-alias "${ANDROID_KEY_ALIAS:?}" \
    --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false \
    --v3-signing-enabled false --out OpenFilm6K-Camera.apk out/aligned.apk
  rm -f "$KS"
else
  [ -e debug.keystore ] || "$JAVA/keytool" -genkeypair -keystore debug.keystore -alias openfilm6k \
    -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=OpenFilm6K"
  "$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --min-sdk-version 10 --v1-signing-enabled true --v2-signing-enabled false \
    --v3-signing-enabled false --out OpenFilm6K-Camera.apk out/aligned.apk
fi

echo "[7/7] verify"
"$BT/apksigner" verify --min-sdk-version 10 OpenFilm6K-Camera.apk
echo "BUILD OK: $ROOT/OpenFilm6K-Camera.apk"
