#!/bin/bash
set -e
cd "$(dirname "$0")"
SDK="/c/Users/Mehfo/AppData/Local/Android/Sdk"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-35/android.jar"
APP_ID="${APP_ID:-com.example.android.apis}"
RENAME=""
[ "$APP_ID" != "app.fastlauncher" ] && RENAME="--rename-manifest-package $APP_ID"
rm -rf build && mkdir -p build/classes build/dex
VCODE=$(git rev-list --count HEAD 2>/dev/null || echo 1)
VNAME="1.$VCODE-$(git rev-parse --short HEAD 2>/dev/null || echo dev)$(git diff --quiet HEAD 2>/dev/null || echo +)"
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/app.unsigned.apk -I "$JAR" --manifest AndroidManifest.xml $RENAME -A assets \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VCODE" --version-name "$VNAME" build/res.zip
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -cp "$JAR" -d build/classes $(find src -name '*.java')
cmd //c "$(cygpath -w "$BT/d8.bat")" --lib "$(cygpath -w "$JAR")" --min-api 26 --release --output build/dex $(find build/classes -name '*.class' | sed 's#^#build/classes/#;s#build/classes/build/classes/#build/classes/#')
(cd build/dex && jar uf ../app.unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 build/app.unsigned.apk build/app.aligned.apk
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias dbg -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Fast Launcher" >/dev/null 2>&1
cmd //c "$(cygpath -w "$BT/apksigner.bat")" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out build/launcher.apk build/app.aligned.apk
echo BUILD OK; ls -la build/launcher.apk
