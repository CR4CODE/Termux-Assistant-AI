#!/data/data/com.termux/files/usr/bin/bash
# build.sh — полная сборка APK Termux Assistant AI
set -e
cd "$(dirname "$0")"

echo "=== [1/6] aapt2 compile ==="
rm -rf build/res.zip build/base.apk
aapt2 compile --dir res -o build/res.zip 2>&1

echo "=== [2/6] aapt2 link ==="
aapt2 link -o build/base.apk -I lib/android.jar \
  --manifest AndroidManifest.xml build/res.zip \
  --java build/gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 204 --version-name 2.4 2>&1 | grep -vE "^$" || true

echo "=== [3/6] javac ==="
rm -rf build/classes && mkdir -p build/classes
javac -source 8 -target 8 -bootclasspath lib/android.jar \
  -encoding UTF-8 -d build/classes \
  build/gen/com/termux/assistant/R.java \
  src/com/termux/assistant/*.java 2>&1 | grep -vE "warning:|obsolete|^Note:"
if [ ! -f build/classes/com/termux/assistant/MainActivity.class ]; then
  echo "❌ javac FAILED"
  exit 1
fi

echo "=== [4/6] d8 (dex) ==="
(cd build/classes && jar cf ../classes.jar com/)
d8 --min-api 24 --lib lib/android.jar --output build/dex build/classes.jar 2>&1

echo "=== [5/6] упаковка ==="
cd build
rm -rf repack && mkdir repack && cd repack
unzip -o ../base.apk >/dev/null 2>&1
cp ../dex/classes.dex .
cd ..
rm -f new.apk
cd repack
zip -X -0 ../new.apk AndroidManifest.xml >/dev/null
zip -X -0 ../new.apk resources.arsc >/dev/null
zip -X -9 ../new.apk -r res/ >/dev/null
zip -X -9 ../new.apk classes.dex >/dev/null
cd ..

echo "=== [6/6] подпись ==="
apksigner sign \
  --ks ../keys/ai-tasker.jks --ks-pass pass:assistant --key-pass pass:assistant \
  --out signed.apk new.apk 2>&1

if apksigner verify signed.apk 2>/dev/null; then
  DEST="/sdcard/Download/ai-tasker-build-$(date +%H%M%S).apk"
  cp signed.apk "$DEST"
  echo
  echo "✅ BUILD OK"
  echo "APK: $DEST"
  ls -la "$DEST"
else
  echo "❌ VERIFY FAILED"
  exit 1
fi
