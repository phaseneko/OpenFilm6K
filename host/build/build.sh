#!/usr/bin/env bash
# OpenFilm6K host build — no-Gradle pipeline (aapt2 + javac + d8 + NDK clang).
set -euo pipefail
R="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_SDK:=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}}"
BT="$ANDROID_SDK/build-tools/30.0.3"
AJ="$ANDROID_SDK/platforms/android-28/android.jar"
NDK="$(ls -d "$ANDROID_SDK"/ndk/* 2>/dev/null | sort | tail -1)"
if [ -n "${JAVA_HOME:-}" ]; then J="$JAVA_HOME/bin"; elif [ -x "$HOME/toolchains/jdk17/bin/javac" ]; then J="$HOME/toolchains/jdk17/bin"; else J="$(dirname "$(command -v javac)")"; fi
export PATH="$J:$PATH"
cd "$R"; rm -rf out && mkdir -p out/gen out/classes out/dex
"$BT/aapt2" compile --dir res -o out/res.zip
mkdir -p out/pkgassets/films
cp -r films/pipelines films/luts out/pkgassets/films/
mkdir -p out/pkgassets/films/root
cp films/previews/* out/pkgassets/films/root/
( cd out/pkgassets && find films/root films/pipelines films/luts -type f | sort > films/index.txt )
"$BT/aapt2" link --java out/gen -o out/unaligned.apk --manifest AndroidManifest.xml -I "$AJ" --min-sdk-version 24 --target-sdk-version 27 -A out/pkgassets out/res.zip
find out/gen src -name "*.java" > out/srcs.txt
javac -encoding UTF-8 --release 8 -Xlint:-options -nowarn -cp "$AJ" -d out/classes @out/srcs.txt
find out/classes -name "*.class" > out/classes.txt
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" --output out/dex @out/classes.txt
( cd out/dex && "$BT/aapt" add ../unaligned.apk classes.dex >/dev/null )
# native engine (C++ core, same GLSL as Java path)
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++"
mkdir -p out/apklib/lib/arm64-v8a
"$CXX" -std=c++17 -O2 -shared -fPIC -static-libstdc++ -o out/apklib/lib/arm64-v8a/libof6k.so jni/of6k.cpp jni/turbo/libturbojpeg.a -lEGL -lGLESv3 -llog
test -s out/apklib/lib/arm64-v8a/libof6k.so || { echo "FATAL: libof6k.so not built"; exit 1; }
( cd out/apklib && "$BT/aapt" add ../unaligned.apk lib/arm64-v8a/libof6k.so >/dev/null )
# film assets ride in via aapt2 link -A above (aapt add entries are invisible to AssetManager)

"$BT/zipalign" -f 4 out/unaligned.apk out/aligned.apk
[ -e "$R/debug.keystore" ] || keytool -genkeypair -keystore "$R/debug.keystore" -alias openfilm6k -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname "CN=OpenFilm6K"
"$BT/apksigner" sign --ks "$R/debug.keystore" --ks-pass pass:android --key-pass pass:android --out "$R/OpenFilm6K-Host.apk" out/aligned.apk
echo "BUILD OK: $R/OpenFilm6K-Host.apk"
