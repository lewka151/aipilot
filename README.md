# AIPilot — манифест приложения

AIPilot — компактный JavaFX-агент компьютера: диск, консоль, SSH и очередь
команд сайта. Чат Qwen/DeepSeek и userscripts живут на `/ai` и во вкладках
браузера, не внутри этого окна.

## Компоненты

```text
JavaFX UI
  ├─ AgentLoop: pending → песочница → DeepSeek safety → run/ssh/files
  ├─ SiteApiClient: projects, commands, ack, docs/prompt
  └─ SiteBridgeClient: DeepSeek job + presence provider=aipilot
          ↕ HTTPS Platforma151
/ai + Violentmonkey на chat.qwen.ai / chat.deepseek.com
```

Backend и MySQL не входят в этот репозиторий. Desktop работает с ними только
через HTTP API. Упоминания таблиц ниже описывают внешний контракт, а не прямой
доступ приложения к базе.

## Требования и команды

- JDK 21 (JavaFX 21);
- Gradle wrapper.

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\PhpStorm 2025.2.4\jbr"
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat run
.\gradlew.bat packageApp
```

Stylus выполняет CSS и не запускает `.user.js`. Скрипты AIPilot ставятся
в Violentmonkey.

## Linux (сборка и запуск)

Сборку app-image нужно делать **на Linux** (jpackage не кросс-компилирует).

```bash
chmod +x gradlew run.sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew test
./gradlew packageLinuxApp
python3 tools/build_linux_package.py
# установка из tar:
# tar -xzf dist/aipilot-linux.tar.gz && cd aipilot-linux && ./install.sh
./tools/install-linux.sh
aipilot
```

`./run.sh` запускает UI через Gradle. На Ubuntu 26.04 (Wayland) нужны GTK3
(`libgtk-3-0t64`) и XWayland; лаунчер задаёт `GDK_BACKEND=x11`. GUI не стартует
из `java -jar` — нужен app-image или `./gradlew run`. Headless:
`java -jar aipilot.jar --headless`.

## Локальные данные

Windows:

```text
%APPDATA%\AIPilot\
  config.json
  auth.json
  userscripts\
  chrome-extension\
  users\{userId}\
    projects\
    history\
    events\
```

`auth.json` содержит Bearer session и должен быть доступен только владельцу.
`config.json` содержит URL API, bridge token, режим и состояние окна.

## Структура проекта

Конвейер DeepSeek → Qwen и docs API описаны в [`SYSTEM.md`](SYSTEM.md).

```text
project.json
ai_interaction_rules.md   # kind ai_rules, протокол платформы
based.md                  # kind based, роли моделей
deepseek_static.md        # kind deepseek_static
qwen_static_readme.md     # kind qwen_static
readme.md
files.md
file_index.md
```

Источник markdown — `GET/PUT /api/aipilot/docs`. Локальные файлы — кэш. Сборка промпта: правила → based → статика DeepSeek → статика Qwen → readme → files → file_index → цель режима.

Все файлы доступны в правом редакторе приложения. Перед отправкой выбранный
документ и `project.json` сохраняются.

## Маршрутизация чата

- **Проекты:** skeleton → DeepSeek refine → Qwen execute.
- **Чат:** skeleton → DeepSeek refine → Qwen chat.
- **План:** skeleton → DeepSeek refine → Qwen plan.
- **Мультичат:** DeepSeek refine → Qwen draft → DeepSeek critique → Qwen final.

Skeleton, refine, draft и critique сохраняются в `history`. Итог режима
сохраняется в `reports`.

## Site API

Desktop использует:

- `POST /api/aipilot/runs`;
- `POST /api/aipilot/runs/{id}/steps`;
- `PATCH /api/aipilot/runs/{id}`;
- `POST /api/aipilot/history`;
- `POST /api/aipilot/metrics`;
- `POST /api/aipilot/events`;
- `POST /api/aipilot/sync`;
- `/api/aipilot/bridge/*` для token/status/activity/jobs.

Внешний сервер хранит соответствующие записи в runs/steps/history/events/
metrics и bridge-таблицах. Корректность методов, JSON и ошибок проверяется
локальными mock HTTP-тестами. Кнопка **Проверить API** выполняет только
read-only live-проверки: `/api/auth/me`, bridge status/activity, release и
admin reads при наличии роли.

Сбой telemetry API не должен уничтожать локальный результат AI. History/events
дублируются локально; причина HTTP-сбоя пишется в приложение и logback.

## Violentmonkey

1. Войти в AIPilot.
2. Настройки → **Скопировать JS Qwen / DeepSeek** (тонкий loader).
3. Вставить в Violentmonkey один раз. Не используйте Stylus.
4. Экспорт публикует JS на сервер (`POST /api/aipilot/bridge/scripts`).
   Loader качает актуальные `bridge-core.js` и адаптер с сервера.
5. Открыть `chat.qwen.ai` и `chat.deepseek.com`.

Слева сверху — консоль AIPilot: **Вход / Выход**, **Активная сессия**, имя аккаунта, очередь jobs.

Одна вкладка каждого provider становится leader и выполняет polling. Остальные
вкладки остаются passive и не должны повторно забирать jobs.

Создание и удаление проекта в приложении синхронизирует проект Qwen,
если вкладка чата открыта.

## Проекты

- создание формирует полный набор каталогов и markdown и создаёт проект в Qwen;
- настройки сохраняют description, Qwen project name и chat title;
- удаление требует ввода точного имени, чистит папку и удаляет проект в Qwen;
- UUID проекта хранится отдельно от имени папки;
- runtime chat title/IDs сохраняются после pipeline.

## Диагностика

1. В web-консоли есть `start ai151`.
2. Статус provider в приложении — `подключён`.
3. **Настройки → Проверить API** показывает `auth/me`, bridge и release.
4. При `NO_INPUT` проверить placeholder поля чата.
5. При `NO_SUBMIT` проверить активную кнопку Send и web-консоль DOM.
6. При `unauthorized` повторить вход и заново вставить скрипты Violentmonkey
   с актуальным bridge token.
7. При telemetry error проверить local `users\{id}\history` и `events`.

## Безопасность

- токены не выводятся в логи;
- API URL/token JSON-экранируются при сборке расширения и userscript;
- project delete защищён от выхода из projectsRoot и symlink;
- live diagnostics не выполняет POST/PATCH/DELETE;
- `trustAllTls` допустим только для dev/LAN.

## Ручной E2E-чеклист

1. Войти в AIPilot — кнопка «Войти» скрывается, статус API показывает сессию.
2. Обе chat-страницы показывают консоль и `start ai151`; на консоли есть вход
   на Platforma151; после входа poll читает jobs из API.
3. Один leader на provider; DeepSeek refine → Qwen final.
4. Второй prompt переиспользует chat title проекта.
5. Смена проекта переключает содержимое чата; ошибка красная, «Готово» зелёное.
6. Create / settings / delete project; сохранение based.md и остальных md,
   history и report.
