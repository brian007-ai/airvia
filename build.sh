#!/bin/bash
# Standalone build for Airvia (no Gradle). Same pipeline as the AirPlay2
# Probe / Outro manual builds: aapt2 link -> kotlinc -> d8 -> sign.
# Requires: JDK 17, Android SDK 34, kotlin-compiler-embeddable 2.1.0.
set -euo pipefail

export JAVA_HOME=/home/hatch/jdk/jdk-17.0.20.1+1
export PATH=$JAVA_HOME/bin:$PATH

PROJ=~/workspace/airvia
BUILD=$PROJ/build
OUT_APK=~/workspace/your_files/Airvia-1.0.0.apk
ANDROID_SDK=~/android-sdk
PLATFORM=$ANDROID_SDK/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_SDK/build-tools/34.0.0

K2_LIB=~/kotlinc/kotlin-2.1.0/kotlinc/lib
EMBED_JAR=~/kplugins/embed/kotlin-compiler-embeddable-2.1.0.jar
RUNCP="$EMBED_JAR:$K2_LIB/kotlin-stdlib.jar:$K2_LIB/kotlinx-coroutines-core-jvm.jar:$K2_LIB/trove4j.jar:$K2_LIB/annotations-13.0.jar:$K2_LIB/kotlin-reflect.jar:$K2_LIB/kotlin-script-runtime.jar"
KOTLINC="java -cp $RUNCP org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
STDLIB=$K2_LIB/kotlin-stdlib.jar

[ -f "$PLATFORM" ] || { echo "FATAL: android.jar missing at $PLATFORM"; exit 1; }
[ -f "$STDLIB" ] || { echo "FATAL: kotlin-stdlib missing at $STDLIB"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD"/{classes,dex,apk,r-src}

echo "=== Step 1: aapt2 link (manifest only) ==="
$BUILD_TOOLS/aapt2 link \
  -o "$BUILD/apk/base-unaligned.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --java "$BUILD/r-src" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "1.0.0"
echo "aapt2 link OK."

echo "=== Step 2: kotlinc ==="
SOURCES=$(find "$PROJ/src" -name "*.kt")
echo "Compiling $(echo "$SOURCES" | wc -l) Kotlin sources..."
if ! $KOTLINC $SOURCES \
  -cp "$PLATFORM:$STDLIB" \
  -d "$BUILD/classes" \
  -jvm-target 17 \
  -no-stdlib > "$BUILD/kotlinc.log" 2>&1; then
  echo "KOTLIN COMPILATION FAILED:"
  grep -E "error:|warning: " "$BUILD/kotlinc.log" | head -40
  echo "Full log: $BUILD/kotlinc.log"
  exit 1
fi
echo "Kotlin compilation OK."
{ grep -cE "warning: " "$BUILD/kotlinc.log" || true; } | xargs -I{} echo "warnings: {}"

echo "=== Step 3: javac (generated R.java) ==="
find "$BUILD/r-src" -name "*.java" > "$BUILD/r-java.txt"
if [ -s "$BUILD/r-java.txt" ]; then
  $JAVA_HOME/bin/javac -encoding UTF-8 -cp "$PLATFORM" -d "$BUILD/classes" @"$BUILD/r-java.txt"
  echo "R.java compiled."
else
  echo "No R.java generated (no resources) — skipping."
fi

echo "=== Step 4: d8 ==="
$JAVA_HOME/bin/jar cf "$BUILD/app-classes.jar" -C "$BUILD/classes" .
$BUILD_TOOLS/d8 \
  --min-api 29 \
  --lib "$PLATFORM" \
  --output "$BUILD/dex" \
  "$BUILD/app-classes.jar" "$STDLIB" > "$BUILD/d8.log" 2>&1 \
  || { echo "D8 FAILED:"; tail -30 "$BUILD/d8.log"; exit 1; }
echo "D8 OK: $(ls "$BUILD/dex"/*.dex | wc -l) dex file(s)."

echo "=== Step 5: add dex to APK ==="
cp "$BUILD/apk/base-unaligned.apk" "$BUILD/apk/unsigned.apk"
cd "$BUILD/dex"
for dex in *.dex; do
  $JAVA_HOME/bin/jar uf "$BUILD/apk/unsigned.apk" "$dex"
done
cd "$PROJ"

echo "=== Step 6: zipalign ==="
$BUILD_TOOLS/zipalign -f 4 "$BUILD/apk/unsigned.apk" "$BUILD/apk/aligned.apk"

echo "=== Step 7: sign (Outro debug keystore) ==="
KEYSTORE=~/workspace/jazzy/build-manual/debug.keystore
if [ ! -f "$KEYSTORE" ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10950 \
    -dname "CN=Android Debug,O=Android,C=US" 2>&1 | tail -2
fi
$BUILD_TOOLS/apksigner sign \
  --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/apk/Airvia-1.0.0.apk" \
  "$BUILD/apk/aligned.apk"

echo "=== Step 8: verify ==="
$BUILD_TOOLS/apksigner verify --print-certs "$BUILD/apk/Airvia-1.0.0.apk" | head -5
$BUILD_TOOLS/aapt dump badging "$BUILD/apk/Airvia-1.0.0.apk" | head -8

cp "$BUILD/apk/Airvia-1.0.0.apk" "$OUT_APK"
echo "=== Build complete ==="
ls -lh "$OUT_APK"
sha256sum "$OUT_APK"
