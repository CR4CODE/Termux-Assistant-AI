#!/data/data/com.termux/files/usr/bin/bash
# Termux Assistant AI — установщик
set -e

SELF="$(cd "$(dirname "$0")" && pwd)"
H="$HOME"

echo "═══════════════════════════════════════════════"
echo "  Termux Assistant AI — установка"
echo "═══════════════════════════════════════════════"
echo "Источник: $SELF"
echo "Дом:      $H"
echo

# 1. Каталоги
echo "[1/7] Создаю каталоги..."
mkdir -p "$H/bin" "$H/.aib"
mkdir -p "$H/projects/termux-ai-kit/a11y-apk/run"
mkdir -p "$H/projects/termux-ai-kit/ai-tasker-app/data"
mkdir -p "$H/projects/ai-sandbox"
mkdir -p /sdcard/ai-tasker/inbox /sdcard/ai-tasker/outbox
mkdir -p /sdcard/ai-tasker/done /sdcard/ai-tasker/logs

# 2. Скрипты
echo "[2/7] Копирую скрипты в ~/bin..."
cp "$SELF/bin/"* "$H/bin/" 2>/dev/null || true
chmod +x "$H/bin/"* 2>/dev/null || true

# 3. Промпты
echo "[3/7] Копирую промпты..."
[ -f "$SELF/prompts/auto-prompt.txt" ] && cp "$SELF/prompts/auto-prompt.txt" "$H/.aib/"
[ -f "$SELF/prompts/dev-prompt.txt" ]  && cp "$SELF/prompts/dev-prompt.txt"  "$H/.aib/"

# 4. Данные
echo "[4/7] Копирую данные (apps.json, urls.json)..."
[ -f "$SELF/data/apps.json" ] && cp "$SELF/data/apps.json" "$H/projects/termux-ai-kit/ai-tasker-app/data/"
[ -f "$SELF/data/urls.json" ] && cp "$SELF/data/urls.json" "$H/projects/termux-ai-kit/ai-tasker-app/data/"

# 5. receiver.py
echo "[5/7] Устанавливаю receiver.py..."
if [ -f "$SELF/bin/receiver.py" ]; then
    cp "$SELF/bin/receiver.py" "$H/projects/termux-ai-kit/a11y-apk/run/receiver.py"
    chmod +x "$H/projects/termux-ai-kit/a11y-apk/run/receiver.py"
fi

# 6. PATH
if ! grep -q 'HOME/bin' "$H/.bashrc" 2>/dev/null; then
    echo 'export PATH="$HOME/bin:$PATH"' >> "$H/.bashrc"
    echo "[6/7] PATH обновлён (~/bin добавлен)"
else
    echo "[6/7] PATH уже содержит ~/bin"
fi

# 7. APK
mkdir -p /sdcard/Download
if [ -f "$SELF/apk/ai-tasker.apk" ]; then
    cp "$SELF/apk/ai-tasker.apk" /sdcard/Download/ai-tasker.apk
    echo "[7/7] APK → /sdcard/Download/ai-tasker.apk"
fi

# Финал
echo
echo "═══════════════════════════════════════════════"
echo "  ГОТОВО. Дальше — по шагам:"
echo "═══════════════════════════════════════════════"
echo
echo "1. Установи Termux и Termux:API:"
if [ -f "$SELF/termux-apk/termux.apk" ] && [ -f "$SELF/termux-apk/termux-api.apk" ]; then
    # Копируем APK в Download для удобной установки
    cp "$SELF/termux-apk/termux.apk" /sdcard/Download/termux.apk 2>/dev/null || true
    cp "$SELF/termux-apk/termux-api.apk" /sdcard/Download/termux-api.apk 2>/dev/null || true
    echo "   APK лежат в /sdcard/Download/ — установи их через Файлы:"
    echo "     - termux.apk"
    echo "     - termux-api.apk"
    echo "     - ai-tasker.apk"
else
    echo "   Из F-Droid: com.termux и com.termux.api"
fi
echo
echo "2. Установи приложение:"
echo "   Открой Файлы → Download → ai-tasker.apk → Установить"
echo
echo "3. Разреши приложению всё что попросит"
echo
echo "4. Включи AI Bridge Service:"
echo "   Настройки → Специальные возможности → AI Bridge Service → ВКЛ"
echo
echo "5. Открой приложение → проверь статусы в Настройках"
echo "   Все должны быть ✓"
echo
echo "6. Включи 'Свободный режим' → появится зелёная кнопка ●"
echo
echo "Теперь: копируй код из DeepSeek → тапай ● → жми 'Отправить'"
echo
echo "Подробнее: README.md в этой же папке"
