#!/usr/bin/env bash
# Compila o APK sem Gradle: aapt2 + javac + d8 + apksigner.
# Requer o Android SDK com platforms;android-35 e build-tools;35.0.1.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/35.0.1"
JAR="$SDK/platforms/android-35/android.jar"
VERSION_NAME="0.1.0"
VERSION_CODE="1"
OUT=build

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" \
    --manifest AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version 29 --target-sdk-version 34 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    "$OUT/res.zip"

javac --release 8 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" \
    $(find src "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 29 --lib "$JAR" --output "$OUT/dex" \
    $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Chave de depuração fixa, para que as próximas versões instalem por cima da anterior.
if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=HRV Monitor Debug" >/dev/null
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/hrv-monitor.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/hrv-monitor.apk"

echo "APK: $(pwd)/$OUT/hrv-monitor.apk"
