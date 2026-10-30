#!/bin/bash
# Generic build for one app in this repo. Copy this file into the app's
# directory as build.sh, or run it from the app's directory.
#
#   cd apps/lock
#   SDK=~/Android/Sdk JAVA_HOME=~/jdk17 ./build.sh
#
# Requirements: JDK 17, Android SDK platform android-34, build-tools 34.0.0.
# Shizuku apps also need their client jars in libs/jars/ (see BUILDING.md).
# The signed APK is written next to this script. The signing keystore is a
# throwaway debug key, generated in build/ and never committed.
set -e
cd "$(dirname "$0")"
SDK="${SDK:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
JAVA_HOME="${JAVA_HOME:-$HOME/jdk17}"
export PATH="$JAVA_HOME/bin:$SDK/build-tools/34.0.0:$PATH"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
BT="$SDK/build-tools/34.0.0"

if [ -f build/debug.keystore ]; then cp build/debug.keystore /tmp/t2e.keystore; fi
rm -rf build
mkdir -p build/classes build/dexout
if [ -f /tmp/t2e.keystore ]; then cp /tmp/t2e.keystore build/debug.keystore; fi

# Resources, if the app has any.
if [ -d res ] && [ -n "$(find res -type f 2>/dev/null | head -1)" ]; then
  "$BT/aapt2" compile --dir res -o build/compiled.zip
  COMPILED="build/compiled.zip"
else
  COMPILED=""
fi

# Classpath from Shizuku jars, if present.
CP=""
if [ -d libs/jars ]; then
  CP=$(ls libs/jars/*.jar 2>/dev/null | paste -sd: -)
fi

find src -name '*.java' > build/sources.txt
if [ -n "$CP" ]; then
  javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$CP" -d build/classes @build/sources.txt
else
  javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d build/classes @build/sources.txt
fi

jar cf build/app.jar -C build/classes .
if [ -n "$CP" ]; then
  # d8 wants each jar as a separate input; split the colon list.
  echo "$CP" | tr ':' '\n' > build/jars.txt
  "$BT/d8" --lib "$ANDROID_JAR" --output build/dexout build/app.jar $(cat build/jars.txt)
else
  "$BT/d8" --lib "$ANDROID_JAR" --output build/dexout build/app.jar
fi

if [ -n "$COMPILED" ]; then
  "$BT/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" "$COMPILED" --auto-add-overlay
else
  "$BT/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$ANDROID_JAR" --auto-add-overlay
fi
cp build/dexout/classes.dex build/classes.dex
zip -j build/unsigned.apk build/classes.dex >/dev/null
rm build/classes.dex

if [ ! -f build/debug.keystore ]; then
  keytool -genkeypair -v -keystore build/debug.keystore -storepass android \
    -alias t2e -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Titan 2 Elite Tools, OU=dev, O=dev, C=US" >/dev/null 2>&1
fi
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
APK_NAME="$(basename "$PWD").apk"
"$BT/apksigner" sign --ks build/debug.keystore \
  --ks-pass pass:android --key-pass pass:android \
  --out "$APK_NAME" build/aligned.apk
"$BT/apksigner" verify "$APK_NAME"
ls -lh "$APK_NAME"
