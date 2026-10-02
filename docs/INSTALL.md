# Установка Termux Assistant AI

Пошаговая инструкция от нуля до рабочего ассистента. ~20 минут.

## ⚠️ Важно понимать

**Приложение — это WebView-клиент.** Оно открывает DeepSeek внутри себя,
а команды (Free, Dev, Релиз, ВК) отправляет по HTTP на локальный сервер
`ai-tasker-server` (127.0.0.1:8767).

**Без Termux и запущенного сервера приложение работать не будет.** Кнопки
будут молча висеть. Это не «просто APK» — это связка APK + Termux-окружение.

## Требования

- Android 10+ (проверено на Android 14, Realme RMX3630 / ColorOS 14)
- Termux из F-Droid (НЕ из Google Play — там версия заброшена с 2020)
- ~500 МБ свободного места
- Интернет

## Шаг 1 — Termux из F-Droid

1. Открой https://f-droid.org/packages/com.termux/
2. Скачай APK
3. Установи (разреши установку из браузера)

## Шаг 2 — пакеты в Termux

Открой Termux, выполни по одной строке:

    pkg update && pkg upgrade -y

    pkg install -y aapt2 d8 apksigner openjdk-21 zip unzip git gh curl python termux-api

    termux-setup-storage

`termux-setup-storage` спросит доступ к памяти — **разреши**.

## Шаг 3 — клонирование репозитория

    cd ~

    git clone https://github.com/CR4CODE/Termux-Assistant-AI

    cd Termux-Assistant-AI

## Шаг 4 — установка скриптов

    bash release-utils/install.sh

Скрипт сделает всё сам:
- скопирует живые скрипты (`ai-tasker-server`, `ai-router`, `ai-tasker-daemon`,
  `aib-auto`, `apply-patches`, `export-source`, `auto-release`, `vk-post`,
  `ai-run`, `tinfo`) в `~/bin/`
- положит `apps.json` / `urls.json` в `~/projects/termux-ai-kit/ai-tasker-app/data/`
- создаст симлинк `~/projects/termux-assistant-ai` → папка репо
  (нужен для `auto-release`)
- подготовит `~/.config/ai-tasker/` и шаблон `vk.json.example`
- добавит `~/bin` в `PATH` (через `~/.bashrc`)
- пропишет автозапуск сервера и демона
- запустит `ai-tasker-server` и проверит `/health`

## Шаг 5 — проверка сервера

Закрой и открой Termux заново (чтобы подхватить `PATH` и автозапуск).
Затем в новой сессии:

    pgrep -af ai-tasker-server

Должна быть строка с `python3 .../ai-tasker-server`.

Проверь порт:

    curl -s http://127.0.0.1:8767/health

Ожидаемый ответ — `{"ok":true,...}` или `ok`.

Если сервер не поднялся:

    nohup ~/bin/ai-tasker-server > ~/ai-tasker-server.log 2>&1 &

    tail -30 ~/ai-tasker-server.log

## Шаг 6 — установка APK

1. Открой https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest
2. Скачай `ai-tasker-build-*.apk`
3. Установи (разреши установку из браузера/файлов)
4. При первом запуске разреши:
   - Уведомления
   - Микрофон (для голосового ввода)

## Шаг 7 — первый запуск

1. Открой приложение. Внутри — WebView с `chat.deepseek.com`.
2. **Залогинься в DeepSeek** (один раз, дальше сессия сохранится).
3. Внизу панель: `📥 Вставить` `🚀 Free` `🛠 Dev` `⌨️ Клавиатура` `🗑 Очистить`.
4. Проверка Free-режима:
   - Скопируй любую команду, например `uptime`
   - Тапни **🚀 Free** — команда уйдёт через сервер
   - Через 1–2 сек результат вернётся в поле DeepSeek
5. Проверка меню ≡:
   - **История** — список выполненных задач
   - **Настройки** — статус сервера, ВК-конфиг
   - **Инструкция** — этот гайд
   - **📢 ВК-постинг** — генерация постов
   - **🚀 Релиз** — публикация новой версии

## Шаг 8 — ВК-постинг (опционально)

Если хочешь публиковать посты о релизах в сообщество ВК:

1. Получи токен ВК с правами `wall`, `photos`, `manage`
   (см. https://dev.vk.com/ → «Создать приложение» → «Ключ доступа»)
2. Скопируй шаблон и заполни:

       cp ~/.config/ai-tasker/vk.json.example ~/.config/ai-tasker/vk.json
       nano ~/.config/ai-tasker/vk.json

   Формат:

       {
         "access_token": "vk1.a.ТВОЙ_ТОКЕН",
         "group_id": 123456789
       }

   `group_id` — числовой ID сообщества (без минуса).
3. Права:

       chmod 600 ~/.config/ai-tasker/vk.json

4. Проверь публикацию:

       ~/bin/vk-post "тестовый пост из терминала"

## Шаг 9 — сборка своей версии (опционально)

Если хочешь собирать APK сам:

    cd ~/Termux-Assistant-AI

    bash setup.sh

`setup.sh` скачает `android.jar` (~26 МБ) в `app/lib/` и сгенерирует
keystore в `app/keys/` (пароль по умолчанию: `assistant`).

Сборка:

    cd app && bash build.sh

APK появится в `/sdcard/Download/ai-tasker-build-*.apk`.

Релиз одной командой (bump версии → сборка → git push → tag → GitHub Release → ВК-пост):

    ~/bin/auto-release 2.6

## Частые проблемы

| Симптом | Причина / решение |
|---|---|
| Кнопки Free/Dev/Релиз не работают | Сервер не запущен. Проверь: `pgrep -af ai-tasker-server`, `curl 127.0.0.1:8767/health` |
| Termux не ставится | Скачивай F-Droid APK, не Play |
| `Connection refused` | Сервер упал. Смотри `~/ai-tasker-server.log` |
| Free отвечает «открываю DeepSeek» вместо выполнения | Многострочные команды с кавычками не проходят через Free. Разбивай на одну строку или используй Dev |
| `pkill -f ai-tasker-server` не работает | Ловушка: `pkill -f` матчит и свой же процесс. Юзай: `pgrep -f ai-tasker-server \| head -1 \| xargs -r kill` |
| Клавиатура перекрывает поле DeepSeek | Тапни **⌨️ Клавиатура** |
| ВК-пост не уходит | Проверь токен и `group_id` в `~/.config/ai-tasker/vk.json` |
| `auto-release` не находит репо | Проверь симлинк: `ls -la ~/projects/termux-assistant-ai` |

## Архитектура (для понимания)

    ┌─────────────────┐     HTTP 127.0.0.1:8767     ┌──────────────────┐
    │  APK (WebView)  │  ────────────────────────▶  │ ai-tasker-server │
    │  DeepSeek + JS  │  ◀────────────────────────  │   (Python)       │
    └─────────────────┘      JSON /task, /health    └────────┬─────────┘
                                                             │
                                                     subprocess
                                                             ▼
                                                    ┌──────────────────┐
                                                    │   ai-router      │
                                                    │ bash / a11y / url│
                                                    └──────────────────┘

    Fallback (для CLI):
    /sdcard/ai-tasker/inbox  →  ai-tasker-daemon  →  outbox

    Legacy (не используется приложением, см. scripts/legacy/):
    aib, aictl, receiver.py, ai-cycle, ai-dev, grab, aib-watchdog, logrotate-aib

## Что дальше

- **USER_GUIDE.md** — ежедневное использование (Free, Dev, голос, история)
- **TERMUX_COMMANDS.md** — все команды в Termux
- **DEV_GUIDE.md** — как устроен JS-мост, build.sh без Gradle, структура APK
