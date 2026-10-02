#!/data/data/com.termux/files/usr/bin/bash
# release-utils/install.sh — установка Termux Assistant AI
# Копирует живые скрипты в ~/bin/, данные в проект, готовит конфиги,
# прописывает автозапуск сервера и демона в ~/.bashrc.
set -e

SELF="$(cd "$(dirname "$0")/.." && pwd)"
H="$HOME"
BIN="$H/bin"
CFG="$H/.config/ai-tasker"
PROJ="$H/projects/termux-ai-kit/ai-tasker-app"

echo "═══════════════════════════════════════════════"
echo "  Termux Assistant AI — установка"
echo "═══════════════════════════════════════════════"
echo "Источник: $SELF"
echo "Дом:      $H"
echo

# ─── 1. Каталоги ────────────────────────────────────────────────
echo "[1/7] Каталоги..."
mkdir -p "$BIN" "$CFG" "$PROJ/data"
mkdir -p "$H/projects"
mkdir -p /sdcard/ai-tasker/{inbox,outbox,done,pending,logs,source}

# ─── 2. Живые скрипты → ~/bin ───────────────────────────────────
echo "[2/7] Скрипты → ~/bin/ ..."
SCRIPTS=(
  ai-tasker-server
  ai-router
  ai-tasker-daemon
  aib-auto
  apply-patches
  export-source
  auto-release
  vk-post
  ai-run
  tinfo
)
for s in "${SCRIPTS[@]}"; do
  if [ -f "$SELF/scripts/$s" ]; then
    cp "$SELF/scripts/$s" "$BIN/$s"
    chmod +x "$BIN/$s"
    echo "  ✓ $s"
  else
    echo "  ⚠ нет в репо: scripts/$s"
  fi
done

# ─── 3. Данные → проект ─────────────────────────────────────────
echo "[3/7] Данные (apps.json, urls.json) → проект..."
for d in apps.json urls.json; do
  if [ -f "$SELF/data/$d" ]; then
    cp "$SELF/data/$d" "$PROJ/data/$d"
    echo "  ✓ $d"
  fi
done

# ─── 4. Симлинк на git-repo ─────────────────────────────────────
# auto-release ждёт ~/projects/termux-assistant-ai
echo "[4/7] Симлинк ~/projects/termux-assistant-ai ..."
LINK="$H/projects/termux-assistant-ai"
if [ -L "$LINK" ] || [ -e "$LINK" ]; then
  echo "  ⚠ уже существует: $LINK — пропускаю"
else
  ln -s "$SELF" "$LINK"
  echo "  ✓ $LINK → $SELF"
fi

# ─── 5. Конфиг ВК ───────────────────────────────────────────────
echo "[5/7] Конфиг ВК..."
if [ ! -f "$CFG/vk.json" ]; then
  if [ -f "$SELF/docs/vk.json.example" ]; then
    cp "$SELF/docs/vk.json.example" "$CFG/vk.json.example"
    echo "  ✓ шаблон: $CFG/vk.json.example"
    echo "    скопируй в vk.json и заполни токен"
  fi
else
  echo "  ⚠ $CFG/vk.json уже есть — не трогаю"
  chmod 600 "$CFG/vk.json" 2>/dev/null || true
fi

# ─── 6. PATH + автозапуск в ~/.bashrc ───────────────────────────
echo "[6/7] ~/.bashrc: PATH и автозапуск..."
BRC="$H/.bashrc"
touch "$BRC"

if ! grep -q 'HOME/bin' "$BRC"; then
  echo 'export PATH="$HOME/bin:$PATH"' >> "$BRC"
  echo "  ✓ PATH добавлен"
else
  echo "  • PATH уже есть"
fi

if ! grep -q 'ai-tasker-server' "$BRC"; then
  {
    echo ''
    echo '# Автозапуск Termux Assistant AI'
    echo 'pgrep -f ai-tasker-daemon >/dev/null || (nohup ~/bin/ai-tasker-daemon > ~/ai-tasker-daemon.log 2>&1 &)'
    echo 'pgrep -f ai-tasker-server >/dev/null || (nohup ~/bin/ai-tasker-server > ~/ai-tasker-server.log 2>&1 &)'
  } >> "$BRC"
  echo "  ✓ автозапуск добавлен"
else
  echo "  • автозапуск уже есть"
fi

# ─── 7. Первый запуск сервера (в текущей сессии) ────────────────
echo "[7/7] Запуск сервера..."
if pgrep -f ai-tasker-server >/dev/null; then
  echo "  • сервер уже работает"
else
  nohup "$BIN/ai-tasker-server" > "$H/ai-tasker-server.log" 2>&1 &
  sleep 1
  if curl -s --max-time 2 http://127.0.0.1:8767/health >/dev/null; then
    echo "  ✓ сервер ответил на /health"
  else
    echo "  ⚠ сервер запущен, но /health не ответил — проверь $H/ai-tasker-server.log"
  fi
fi

echo
echo "═══════════════════════════════════════════════"
echo "  ГОТОВО"
echo "═══════════════════════════════════════════════"
echo
echo "Дальше:"
echo "  1. Закрой и открой Termux заново (подхватить PATH и автозапуск)"
echo "  2. Проверь: pgrep -af ai-tasker-server"
echo "              curl -s http://127.0.0.1:8767/health"
echo "  3. Установи APK из Releases:"
echo "     https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest"
echo "  4. Открой приложение → залогинься в DeepSeek"
echo "  5. Проверь Free-режим (кнопка 🚀 внизу)"
echo
echo "ВК-постинг (опционально):"
echo "  cp $CFG/vk.json.example $CFG/vk.json"
echo "  nano $CFG/vk.json   # вставь токен"
echo "  chmod 600 $CFG/vk.json"
echo
echo "Сборка своей версии (опционально):"
echo "  cd $SELF && bash setup.sh && cd app && bash build.sh"
echo
