# Как улучшать Termux Assistant AI

## Основной способ — через ai-dev

**ai-dev** — скрипт, который запускает DeepSeek для автономной разработки APK.

### Использование

    ai-dev "описание задачи"

Например:

    ai-dev "добавь в SettingsActivity кнопку Экспорт истории задач в файл"

### Что произойдёт

1. DeepSeek откроется в новом чате
2. Получит задачу + системный промпт разработки
3. Прочитает файлы проекта
4. Создаст патчи через Python
5. Применит их
6. Запустит `bash build.sh`
7. Если упало — исправит
8. Скажет ГОТОВО, APK окажется в /sdcard/Download/

### Тайминг

- Простая задача (кнопка, атрибут) — 5-10 минут
- Средняя (новый экран, логика) — 10-20 минут
- Сложная (несколько файлов) — 20-40 минут

### Лимиты

    ai-dev "задача" --max 30       # лимит итераций
    ai-dev "задача" --errors 3     # стоп после N ошибок

## Ручной способ — если ai-dev сломался

### Файлы проекта

    ~/projects/termux-ai-kit/ai-tasker-app/
    ├── AndroidManifest.xml
    ├── build.sh
    ├── src/com/termux/assistant/
    │   ├── MainActivity.java
    │   ├── SettingsActivity.java
    │   ├── TaskAdapter.java
    │   ├── TaskItem.java
    │   └── AiBridgeService.java
    ├── res/layout/
    ├── res/values/
    ├── res/xml/
    ├── keys/ai-tasker.jks
    └── docs/

### Сборка

    cd ~/projects/termux-ai-kit/ai-tasker-app
    bash build.sh

Результат: /sdcard/Download/ai-tasker-build-<время>.apk

### Правка вручную

1. Измени `.java` или `.xml` в редакторе
2. Проверь синтаксис (для Java — только сборкой)
3. `bash build.sh`
4. Установи APK из Download

## Что можно улучшить (идеи)

- Кнопка «Открыть папку /sdcard/ai-tasker"
- Импорт / экспорт истории задач
- Виджет на домашний экран
- Шаблоны задач
- Автозапуск после ребута (через Tasker/MacroDroid)
- Интеграция с системным шарингом (поделиться текстом → Tasker)

## Стиль кода

- **Java 8** (без лямбд — Android не поддерживает)
- **Material Design** (темы через ?attr/app*)
- **Без сторонних библиотек** — только Android SDK
- **Не использовать Compose** — только XML-layouts

## Правила работы с ai-dev

### Что включить в задачу

- Что менять (какой файл)
- Что должно получиться
- Как проверить

**Хороший пример:**

    ai-dev "добавь в MainActivity кнопку Очистить историю.
    Клик → удалить все файлы из /sdcard/ai-tasker/outbox/.
    Показать Toast Очищено. После правки — bash build.sh."

**Плохой пример:**

    ai-dev "улучши приложение"

### Правила для DeepSeek (в dev-prompt)

Уже прописаны в `~/.aib/dev-prompt.txt`:
- Обязательно менять код
- Бэкап через `cp file file.pre-edit`
- Патч через Python, не sed/awk
- Проверка через grep
- Сборка через bash build.sh
- ГОТОВО только после успешной сборки

## Откат изменений

### Из бэкапа ai-dev

Перед каждой задачей ai-dev делает автобэкап в ~/backups/auto-dev-<ts>/

Восстановить:

    cp -r ~/backups/auto-dev-<ts>/* ~/projects/termux-ai-kit/ai-tasker-app/

### Из .pre-edit файлов

DeepSeek создаёт .pre-edit перед правкой:

    cp src/com/termux/assistant/MainActivity.java.pre-edit \
       src/com/termux/assistant/MainActivity.java

## Требования к окружению (для разработки)

    pkg install python aapt2 d8 apksigner openjdk-21 zip unzip

Плюс `~/projects/termux-ai-kit/a11y-apk/lib/android.jar` — обязателен для компиляции.

## Проверка после установки

1. Приложение открывается
2. История видна
3. `uptime` работает за 1 сек
4. `открой вк` открывает ВК
5. Настройки → все три статуса ✓

## Что если что-то сломалось

    # 1. Восстановить из последнего бэкапа
    BK=$(ls -dt ~/backups/auto-dev-* | head -1)
    cp -r "$BK"/* ~/projects/termux-ai-kit/ai-tasker-app/

    # 2. Пересобрать
    bash build.sh

    # 3. Установить APK
