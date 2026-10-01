Вот полный README одним текстом. Выдели всё и скопируй — от первой строки до последней.

---

Termux Assistant AI

Одна строка. Ты пишешь — она делает. Пока ты живёшь.

Открытый AI-ассистент для Android: управляет приложениями, выполняет команды в Termux, читает экран и сам разрабатывает код. Без root. Без API-ключей. Без VPN.

🌐 Ссылки

· Лендинг проекта — https://cr4code.github.io/Termux-Assistant-AI/
· Скачать APK v2.0 — https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest
· Документация — docs/
· Сообщество ВК — https://vk.ru/termuxai

✨ Что нового в v2.0

Полный редизайн интерфейса — стиль синхронизирован с лендингом.

· 🖤 Новая палитра: тёплый чёрный #121214 + зелёный акцент #22C55E
· ☀️ Полноценная светлая тема
· 🔤 Шрифты Syne + JetBrains Mono
· 💬 Главный экран — чат-стиль, как в ChatGPT/Claude
· 🖥 Live-лог Termux прямо на главном — видно, что делает демон в реальном времени
· 🔀 Переключатель режимов — AI / Dev / Shell одной кнопкой
· 🔍 История с поиском по задачам и выводу
· 🎯 Иконка приложения — зелёная точка на чёрном фоне

🚀 Возможности

Три режима ввода

Способ Пример Скорость
🎤 Голос «открой вк», «покажи uptime» мгновенно
⌨️ Текст uptime, df -h, ls ~/projects 1–2 сек
🤖 AI-задача «напиши скрипт X», «создай игру» 30–60 сек

Три режима работы

Переключаются кнопкой слева от микрофона:

· 🤖 AI — задача идёт в DeepSeek, роутер сам решает, как выполнить
· 🛠 Dev — автономная разработка APK через ai-dev
· ▶ Shell — прямое выполнение shell-команды в Termux

Умная маршрутизация

· uptime, df -h, ls → bash прямо в Termux (1 сек)
· «открой вк», «открой ютуб» → приложение через a11y-сервис (2 сек)
· «открой гитхаб», google.com → браузер (2 сек)
· «напиши скрипт…», «создай игру» → DeepSeek + автономное выполнение (30–60 сек)

Свободный режим

Плавающая кнопка поверх всех приложений:

1. Пишешь задачу в DeepSeek
2. DeepSeek отвечает кодом в блоке
3. Копируешь блок
4. Тапаешь зелёную ●
5. Кнопка → жёлтая → выполняет → результат сам вставляется в DeepSeek
6. Ты жмёшь «Отправить» сам

Интерфейс

· 💬 Чат-стиль главного экрана
· 🖥 Live-лог Termux с иконками событий
· 📋 История задач с поиском и удалением
· 🎨 3 темы — светлая / тёмная / системная
· 📖 Встроенная документация (6 документов)
· ✨ Wizard первого запуска
· 🔔 Уведомления о завершении

🏗 Архитектура

```
Termux Assistant AI (APK)
  • UI: голос, ввод, история, темы
  • AiBridgeService (Accessibility)
  • OverlayService (плавающая кнопка)
  • QuickTileService (плитка в шторке)
              │
   /sdcard/ai-tasker/ + socket 127.0.0.1:8766
              ▼
Termux
  • ai-tasker-daemon (слушает inbox)
  • ai-router (маршрутизация)
  • aib-auto / ai-dev (DeepSeek)
  • aib / grab / ai-cycle (утилиты)
```

📦 Установка

Что нужно (вручную)

1. Termux — https://f-droid.org/packages/com.termux/
2. Termux:API — https://f-droid.org/packages/com.termux.api/
3. DeepSeek — Google Play

Установка ассистента

```bash
# 1. Клонировать репозиторий
git clone https://github.com/CR4CODE/termux-assistant-ai.git
cd termux-assistant-ai

# 2. Установить скрипты и промпты
bash release-utils/install.sh

# 3. Установить APK (из Releases репозитория)
# Скачай → установи вручную

# 4. В приложении: Настройки → AI Bridge Service → включить
```

Подробнее — в docs/INSTALL.md

🔨 Сборка из исходников

```bash
cd termux-assistant-ai
bash setup.sh
cd app
bash build.sh
# Результат: /sdcard/Download/ai-tasker-build-*.apk
```

Требования: aapt2, d8, apksigner, openjdk-21, zip в Termux.

📚 Документация

Файл Что внутри
docs/INSTALL.md Пошаговая установка
docs/USER_GUIDE.md Ежедневное использование
docs/DEV_GUIDE.md Как улучшить приложение
docs/TERMUX_COMMANDS.md Все команды Termux
docs/ROADMAP.md Что сделано и что в планах

🎯 Команды Termux (шпаргалка)

```bash
# Управление сервисом
aictl-up
aictl status
aib ping
aib openurl "https://ya.ru"
aib openapp com.vkontakte.android

# Роутер
ai-router "uptime"
ai-router "открой вк"
ai-router "открой гитхаб"

# Автономная разработка
ai-dev "задача"
```

🛡 Безопасность

· Blacklist команд — rm -rf /, mkfs, dd of=/dev, shutdown, reboot, fork-бомба
· Автобэкап проекта перед любой правкой кода
· Детектор зацикливания — 3 одинаковые итерации → автостоп
· Абсолютный таймаут — максимум 30 минут на задачу
· Детектор провала — если ничего не сделал → status: failed

📄 Лицензия

MIT — см. LICENSE

👤 Автор

CR4CODE — https://github.com/CR4CODE

---
