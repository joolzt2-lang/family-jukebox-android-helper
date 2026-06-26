#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

APP_ID="com.joolz.familyjukeboxhelper"
BUILD_DIR="build"

ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
AAPT2="$ANDROID_HOME/build-tools/35.0.0/aapt2"
D8="$ANDROID_HOME/build-tools/35.0.0/d8"
APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/res" "$BUILD_DIR/dex" "$BUILD_DIR/apk"

echo "Compiling resources..."
"$AAPT2" compile \
  --dir app/src/main/res \
  -o "$BUILD_DIR/res/resources.zip"

echo "Linking APK..."
"$AAPT2" link \
  -o "$BUILD_DIR/apk/unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest app/src/main/AndroidManifest.xml \
  --min-sdk-version 23 \
  --target-sdk-version 35 \
  "$BUILD_DIR/res/resources.zip"

echo "Compiling Java..."
javac \
  -encoding UTF-8 \
  -source 17 \
  -target 17 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD_DIR/classes" \
  $(find app/src/main/java -name '*.java' | sort)

echo "Converting Java classes to Android DEX..."
"$D8" \
  --classpath "$ANDROID_JAR" \
  --min-api 23 \
  --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name '*.class' | sort)

echo "Adding DEX to APK..."
cp "$BUILD_DIR/apk/unsigned.apk" "$BUILD_DIR/apk/unsigned-with-dex.apk"
jar uf "$BUILD_DIR/apk/unsigned-with-dex.apk" -C "$BUILD_DIR/dex" classes.dex

mkdir -p "$HOME/.android"

if [ ! -f "$HOME/.android/debug.keystore" ]; then
  echo "Creating debug keystore..."
  keytool -genkeypair \
    -keystore "$HOME/.android/debug.keystore" \
    -storepass android \
    -alias androiddebugkey \
    -keypass android \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

echo "Signing APK..."
"$APKSIGNER" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$BUILD_DIR/family-jukebox-helper-debug.apk" \
  "$BUILD_DIR/apk/unsigned-with-dex.apk"

echo "Installing APK..."
adb install -r "$BUILD_DIR/family-jukebox-helper-debug.apk"

echo "Granting Bluetooth permission if Android allows it..."
adb shell pm grant "$APP_ID" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true

echo "Launching app..."
adb shell am start -n "$APP_ID/.MainActivity"

echo
echo "Installed and launched: $APP_ID"
