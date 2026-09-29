# Termux: команды системы

## Установка зависимостей (один раз)

Из F-Droid установить:
- Termux
- Termux:API

В Termux:

    pkg update && pkg upgrade
    pkg install python termux-api aapt2 d8 apksigner openjdk-21 zip unzip
    termux-setup-storage

## Ядро системы (в ~/bin/)

### aictl — управление

    aictl-up              # подъём после ребута Termux
    aictl status          # статус receiver
    aictl log 40          # последние строки лога
    aictl restart         # перезапуск receiver
    aictl stop            # остановить
    aictl cycle --delay 8 # один цикл
    aictl watch 10        # автоцикл
    aictl reset           # сбросить память выполненных блоков

### aib — управление сервисом APK

    aib ping                         # проверка
    aib whoami                       # версия сервиса
    aib dump                         # снять дерево экрана
    aib find "regex"                 # найти узлы
    aib tap "regex"                  # тап по тексту/desc
    aib click X Y                    # тап по координатам
    aib actclick "regex"             # ACTION_CLICK на узел
    aib settext "текст"              # вставить в EditText
    aib openapp com.vkontakte.android
    aib openurl "https://ya.ru"
    aib back                         # кнопка Назад
    aib home                         # Домой

### ai-router — маршрутизация задач

    ai-router "uptime"               # bash-команда
    ai-router "открой вк"            # приложение
    ai-router "открой гитхаб"        # URL
    ai-router "напиши скрипт X"      # DeepSeek

### ai-dev — автономная разработка APK

    ai-dev "добавь кнопку X"         # DeepSeek правит APK и пересобирает
    ai-dev "..." --max 40            # лимит итераций

### ai-tasker-daemon — следит за inbox

    # запуск
    nohup ~/bin/ai-tasker-daemon > /sdcard/ai-tasker/logs/daemon-console.log 2>&1 &

    # остановка
    kill $(cat ~/.ai-tasker.pid)

## Папки обмена

    /sdcard/ai-tasker/inbox/     # задачи для Termux
    /sdcard/ai-tasker/outbox/    # результаты
    /sdcard/ai-tasker/done/      # выполненные
    /sdcard/ai-tasker/logs/      # логи

## Тест-задача вручную

    echo "uptime" > /sdcard/ai-tasker/inbox/task-test.txt
    sleep 5
    cat /sdcard/ai-tasker/outbox/task-test.json

## Разработка APK

Проект: ~/projects/termux-ai-kit/ai-tasker-app/

    cd ~/projects/termux-ai-kit/ai-tasker-app
    bash build.sh
    # Результат: /sdcard/Download/ai-tasker-build-<время>.apk

## Проверка здоровья системы

    # 1. Termux:API работает?
    termux-toast "test"

    # 2. receiver жив?
    aictl status

    # 3. сервис APK жив?
    aib ping

    # 4. демон жив?
    cat ~/.ai-tasker.pid && ps -p $(cat ~/.ai-tasker.pid)

## Частые проблемы

| Симптом | Решение |
|---|---|
| `Connection refused` | Сервис APK не включён. Настройки → Специальные возможности → AI Bridge Service |
| `aictl status` пусто | `aictl-up` |
| Приложение показывает ✗ Accessibility | Настройки → Спец возможности → AI Bridge Service |
| Не работает голос | Настройки → Приложения → Tasker → Микрофон → Разрешить |
| DeepSeek не отвечает | Открыть DeepSeek вручную, закрыть рекламу |

## Ежедневный сценарий

    # Утром (после ребута)
    aictl-up

    # Работать с DeepSeek через приложение
    # ... (просто пользуйся Tasker)

    # Вечером (опционально)
    aictl stop

## Что НЕ требуется

- ❌ Wi-Fi / мобильная сеть (loopback работает всегда)
- ❌ Root
- ❌ ADB
- ❌ API-ключи DeepSeek
