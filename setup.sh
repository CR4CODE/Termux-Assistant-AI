#!/data/data/com.termux/files/usr/bin/bash
# setup.sh — подготовка окружения для сборки APK
set -e
cd "$(dirname "$0")"

echo "=== Termux Assistant AI — setup ==="

echo "[1/3] Проверяю зависимости..."
need=("aapt2" "d8" "apksigner" "javac" "zip")
miss=()
for c in "${need[@]}"; do
    command -v "$c" >/dev/null 2>&1 || miss+=("$c")
done

if [ ${#miss[@]} -gt 0 ]; then
    echo "  Не хватает: ${miss[*]}"
    echo "  pkg install aapt2 d8 apksigner openjdk-21 zip"
    exit 1
fi
echo "  ✓ ok"

echo "[2/3] android.jar..."
if [ ! -f app/lib/android.jar ]; then
    echo "  скачиваю (~26 МБ)..."
    mkdir -p app/lib
    curl -fL --connect-timeout 20 -o app/lib/android.jar \
      https://github.com/Sable/android-platforms/raw/master/android-34/android.jar
fi
ls -lh app/lib/android.jar

echo "[3/3] keystore..."
if [ ! -f app/keys/ai-tasker.jks ]; then
    mkdir -p app/keys
    keytool -genkeypair -keystore app/keys/ai-tasker.jks \
      -storepass assistant -keypass assistant \
      -alias assistant -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Termux Assistant AI, O=Termux, C=RU"
fi
ls -lh app/keys/ai-tasker.jks

echo
echo "=== ГОТОВО ==="
echo "Сборка: cd app && bash build.sh"
