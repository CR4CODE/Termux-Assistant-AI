# Termux Assistant AI

**Одна строка. Ты пишешь — она делает. Пока ты живёшь.**

Открытый AI-ассистент для Android: управляет приложениями, выполняет команды в Termux, читает экран и сам разрабатывает код. Без root. Без API-ключей. Без VPN.

## 🌐 Ссылки

- **Лендинг** — https://cr4code.github.io/Termux-Assistant-AI/
- **Скачать APK v2.5** — https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest
- **Документация** — `docs/`
- **Сообщество ВК** — https://vk.ru/termuxai

## ⚠️ Важно понимать

Приложение — это **WebView-клиент**. Оно открывает DeepSeek внутри себя, а команды (Free, Dev, Релиз, ВК) отправляет по HTTP на локальный сервер `ai-tasker-server` (127.0.0.1:8767).

**Без Termux и запущенного сервера APK не работает.** Кнопки будут молча висеть. Это связка APK + Termux-окружение, а не «просто APK».

## ✨ Что нового в v2.8

- **Vibe-режим** — кнопка «🛠 Dev» заменена на «🆕 Vibe». Работа с проектами в `~/vibe/`: список, создание, дерево файлов, сборка.…
- Серверные команды `project_list:`, `project_new:`, `project_tree:`, `project_delete:`, `project_build:`.
- **A11y-мост**: `findFirstEditText` теперь ищет самый нижний широкий `EditText`, а не первый в дереве — раньше `SETTEXT` попадал…
- **A11y-мост**: `MAX_LINE_LEN` 4000 → 50000 — большие код-блоки от DeepSeek больше не обрезаются.
- **aib-auto**: динамический поиск поля ввода, кнопки «Отправить» и «Новый чат» через a11y-дерево вместо хардкод-координат.

Подробнее — в [CHANGELOG.md](CHANGELOG.md).


## 🚀 Возможности

### Ввод

| Способ | Пример | Скорость |
|--------|--------|----------|
| 🎤 Голос | «открой вк», «покажи uptime» | мгновенно |
| ⌨️ Текст | `uptime`, `df -h`, `ls ~/projects` | 1–2 сек |
| 🤖 AI-задача | «напиши скрипт X», «создай игру» | 30–60 сек |

### Главный экран — WebView

Внутри приложения открыт `chat.deepseek.com`. Работаешь с ним как обычно, но поверх — панель управления.

**Нижняя панель:**

- 📥 — вставить из буфера обмена в поле DeepSeek
- 🚀 **Free** — из буфера/поля уходит `auto:команда` → HTTP `/task` → Termux выполняет → результат сам вставляется обратно в поле DeepSeek
- 🛠 **Dev** — список `.java` файлов проекта → задача в DeepSeek → ответ сохраняется → сборка
- ⌨️ **Клавиатура** — блокировка/разблокировка
- 🗑 — очистить поле ввода

**Меню ≡:** История / Инструкция / Настройки / 📢 ВК-постинг / 🚀 Релиз.

### Free-режим

    [Буфер]  или  [Поле DeepSeek]
            ↓
       HTTP POST /task {task: "auto:uptime"}
            ↓
       ai-tasker-server → ai-router → bash
            ↓
       результат → в поле DeepSeek

### Dev-режим (полный цикл)

    1. 🛠 Dev → список src/*.java из /sdcard/ai-tasker/source/
    2. Выбираешь файл → вводишь задачу
    3. Приложение читает файл, формирует промпт, отправляет в DeepSeek
    4. Ждёт ответа (с проверкой завершённости и стабильности)
    5. Парсит ответ, сохраняет в /sdcard/ai-tasker/pending/
    6. Диалог "Найдено: MainActivity.java (N симв.)" → "Применить и собрать"
    7. HTTP: apply_patches: → ai-tasker-server
    8. apply-patches копирует файл в проект → build.sh → APK
    9. Приложение получает результат → "APK готов" с путём

### Умная маршрутизация (Free / auto:)

- `uptime`, `df -h`, `ls` → bash прямо в Termux (1 сек)
- «открой вк», «открой ютуб» → приложение через a11y-сервис (2 сек)
- «открой гитхаб», google.com → браузер (2 сек)
- «напиши скрипт…», «создай игру» → DeepSeek + автономное выполнение (30–60 сек)

## 🏗 Архитектура

    Termux Assistant AI (APK)
      ├─ MainActivity (WebView: chat.deepseek.com)
      │    └─ JS-мост (TermuxSend, TermuxReadLast, …)
      ├─ SettingsActivity / HistoryActivity / DocsActivity
      ├─ OverlayService (плавающая кнопка)
      └─ QuickTileService (плитка в шторке)
                  │
            HTTP 127.0.0.1:8767
                  ▼
    Termux
      ├─ ai-tasker-server — HTTP: POST /task, GET /health
      ├─ ai-router        — маршрутизация (bash / url / a11y / ai)
      ├─ apply-patches    — копирует pending в проект + build.sh
      ├─ export-source    — копирует src/ и res/ в /sdcard/ai-tasker/source/
      ├─ auto-release     — релиз одной командой
      ├─ vk-post          — постинг в ВК
      └─ ai-tasker-daemon — fallback: следит за /sdcard/ai-tasker/inbox

    Legacy (не используется приложением, см. scripts/legacy/):
      aib, aictl, receiver.py, ai-cycle, ai-dev, grab, aib-watchdog, logrotate-aib

## 📦 Установка

**Что нужно:**

1. [Termux](https://f-droid.org/packages/com.termux/) — F-Droid
2. [Termux:API](https://f-droid.org/packages/com.termux.api/) — F-Droid
3. APK из [Releases](https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest)

**Установка (в Termux):**

    git clone https://github.com/CR4CODE/Termux-Assistant-AI
    cd Termux-Assistant-AI
    bash release-utils/install.sh

Скрипт поставит скрипты, данные, симлинк, автозапуск и поднимет сервер. Дальше — установить APK из Releases и залогиниться в DeepSeek внутри приложения.

Подробнее — в `docs/INSTALL.md`.

## 🔨 Сборка из исходников

    cd Termux-Assistant-AI
    bash setup.sh
    cd app
    bash build.sh
    # Результат: /sdcard/Download/ai-tasker-build-*.apk

Требования: `aapt2`, `d8`, `apksigner`, `openjdk-21`, `zip` в Termux.

Релиз одной командой:

    ~/bin/auto-release 2.6

## 📚 Документация

| Файл | Что внутри |
|------|-----------|
| `docs/INSTALL.md` | Пошаговая установка |
| `docs/USER_GUIDE.md` | Ежедневное использование |
| `docs/DEV_GUIDE.md` | Как улучшить приложение |
| `docs/TERMUX_COMMANDS.md` | Все команды Termux |
| `docs/ROADMAP.md` | Что сделано и что в планах |

## 🎯 Команды Termux (шпаргалка)

    # Сервер и здоровье
    pgrep -af ai-tasker-server
    curl -s http://127.0.0.1:8767/health

    # Роутер
    ai-router "uptime"
    ai-router "открой вк"
    ai-router "открой гитхаб"

    # Dev (ручной запуск)
    apply-patches                 # применить pending и собрать
    export-source                 # обновить /sdcard/ai-tasker/source/

    # Релиз одной командой
    auto-release 2.6

    # ВК-постинг
    vk-post "текст поста"

    # Утилиты
    ai-run                        # выполнить команду из буфера, обрезать вывод
    tinfo                         # карточка состояния Termux

## 🛡 Безопасность

- **Blacklist команд** в `ai-router`: `rm -rf /`, `mkfs`, `dd of=/dev`, `shutdown`, `reboot`, fork-бомба
- **Whitelist** для shell-синтаксиса (`|`, `>`, `&&`, `;`, `$(...)`)
- HTTP-сервер слушает **только** `127.0.0.1` — наружу не торчит
- ВК-токен в `~/.config/ai-tasker/vk.json` с правами `chmod 600`
- Таймаут задачи на сервере: 300 сек (auto), 600 сек (release)

## 📄 Лицензия

MIT — см. `LICENSE`.

## 👤 Автор

**CR4CODE** — https://github.com/CR4CODE
