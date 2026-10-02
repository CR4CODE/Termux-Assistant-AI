# Termux Assistant AI

**Одна строка. Ты пишешь — она делает. Пока ты живёшь.**

Открытый AI-ассистент для Android: управляет приложениями, выполняет команды в Termux, читает экран и сам разрабатывает код. Без root. Без API-ключей. Без VPN.

## 🌐 Ссылки

- **Лендинг** — https://cr4code.github.io/Termux-Assistant-AI/
- **Скачать APK v2.2** — https://github.com/CR4CODE/Termux-Assistant-AI/releases/latest
- **Документация** — `docs/`
- **Сообщество ВК** — https://vk.ru/termuxai

## ✨ Что нового в v2.2

**WebView-архитектура + рабочий Dev-цикл.**

Главный экран — это теперь сам `chat.deepseek.com` внутри приложения. Никаких эмуляций кликов и a11y-скриншотов — прямой доступ к DOM через JS-мост.

- 🌐 **WebView с DeepSeek** — полноценный чат, но под нашим контролем
- 🔗 **JS-мост** (`TermuxInsertText`, `TermuxSend`, `TermuxReadLast`, …)
- 📥 **Вставка из буфера** одной кнопкой в поле DeepSeek
- 🚀 **Free-режим** — буфер/поле → inbox как `auto:` → результат обратно в поле
- 🛠 **Dev-режим** — выбор файла проекта → задача в DeepSeek → сборка APK
- 📊 **Живой прогресс сборки** — «APK готов» или «Сборка упала» с хвостом ошибки
- 🧹 Убран мёртвый код (`DeepSeekWebActivity`)

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
- 🚀 **Free** — из буфера/поля уходит `auto:команда` в inbox → Termux выполняет → результат сам вставляется обратно в поле DeepSeek
- 🛠 **Dev** — список `.java` файлов проекта → задача в DeepSeek → ответ сохраняется → сборка
- 🗑 — очистить поле ввода

**Меню ≡:** История задач, Инструкция, Настройки.

### Free-режим

```
[Буфер]  или  [Поле DeepSeek]
        ↓
   inbox:  auto:uptime
        ↓
   Termux выполняет
        ↓
   результат → в поле DeepSeek
```

### Dev-режим (полный цикл)

```
1. 🛠 Dev → показывается список src/*.java из /sdcard/ai-tasker/source/
2. Выбираешь файл → вводишь задачу
3. Приложение читает файл, формирует промпт, отправляет в DeepSeek
4. Ждёт ответа (с проверкой завершённости и стабильности)
5. Парсит ответ, сохраняет в /sdcard/ai-tasker/pending/
6. Диалог "Найдено: MainActivity.java (N симв.)" → "Применить и собрать"
7. inbox: apply_patches:
8. apply-patches копирует файл в проект → build.sh → APK
9. Приложение следит за outbox → "APK готов" с путём
```

### Умная маршрутизация (Free / auto:)

- `uptime`, `df -h`, `ls` → bash прямо в Termux (1 сек)
- «открой вк», «открой ютуб» → приложение через a11y-сервис (2 сек)
- «открой гитхаб», google.com → браузер (2 сек)
- «напиши скрипт…», «создай игру» → DeepSeek + автономное выполнение (30–60 сек)

## 🏗 Архитектура

```
Termux Assistant AI (APK)
  ├─ MainActivity (WebView: chat.deepseek.com)
  │    └─ JS-мост (TermuxSend, TermuxReadLast, …)
  ├─ SettingsActivity / HistoryActivity / DocsActivity
  ├─ OverlayService (плавающая кнопка)
  └─ QuickTileService (плитка в шторке)
              │
   /sdcard/ai-tasker/ (inbox / outbox / pending / source)
              ▼
Termux
  ├─ ai-tasker-daemon — слушает inbox
  ├─ apply-patches    — копирует pending в проект + build.sh
  ├─ export-source    — копирует src/ и res/ в /sdcard/ai-tasker/source/
  ├─ ai-router        — маршрутизация auto:
  └─ aib / grab / ai-cycle
```

## 📦 Установка

**Что нужно (вручную):**

1. [Termux](https://f-droid.org/packages/com.termux/) — F-Droid
2. [Termux:API](https://f-droid.org/packages/com.termux.api/) — F-Droid
3. [DeepSeek](https://www.deepseek.com/) — Play / сайт

**Установка ассистента:**

```bash
# 1. Клонировать репозиторий
git clone https://github.com/CR4CODE/termux-assistant-ai.git
cd termux-assistant-ai

# 2. Установить скрипты и промпты
bash release-utils/install.sh

# 3. Установить APK из Releases (скачать → установить вручную)
```

Подробнее — в `docs/INSTALL.md`.

## 🔨 Сборка из исходников

```bash
cd termux-assistant-ai
bash setup.sh
cd app
bash build.sh
# Результат: /sdcard/Download/ai-tasker-build-*.apk
```

Требования: `aapt2`, `d8`, `apksigner`, `openjdk-21`, `zip` в Termux.

## 📚 Документация

| Файл | Что внутри |
|------|-----------|
| `docs/INSTALL.md` | Пошаговая установка |
| `docs/USER_GUIDE.md` | Ежедневное использование |
| `docs/DEV_GUIDE.md` | Как улучшить приложение |
| `docs/TERMUX_COMMANDS.md` | Все команды Termux |
| `docs/ROADMAP.md` | Что сделано и что в планах |

## 🎯 Команды Termux (шпаргалка)

```bash
# Управление сервисом
aictl-up
aictl status
aib ping

# Роутер
ai-router "uptime"
ai-router "открой вк"
ai-router "открой гитхаб"

# Dev (ручной запуск)
~/bin/ai-tasker-daemon        # держать запущенным
apply-patches                 # применить pending и собрать
export-source                 # обновить /sdcard/ai-tasker/source/

# Автономная разработка
ai-dev "задача"
```

## 🛡 Безопасность

- Blacklist команд: `rm -rf /`, `mkfs`, `dd of=/dev`, `shutdown`, `reboot`, fork-бомба
- **Автобэкап** проекта перед любой правкой (`~/backups/auto-dev-*`)
- При падении сборки — автоматический откат из бэкапа
- Детектор зацикливания — 3 одинаковые итерации → автостоп
- Абсолютный таймаут задачи

## 📄 Лицензия

MIT — см. `LICENSE`.

## 👤 Автор

**CR4CODE** — https://github.com/CR4CODE
