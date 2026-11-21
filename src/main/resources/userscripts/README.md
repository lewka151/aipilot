# AIPilot userscripts (Violentmonkey)

**Единственное место правок:** этот каталог
`html/assets/js/aipilot/` (проект server151).

Кнопки копирования на `/ai/configs` собирают скрипт отсюда.
Loader на chat.qwen.ai / chat.deepseek.com качает отсюда же:
`/assets/js/aipilot/bridge-core.js`, `qwen-ui.js`, `deepseek-ui.js`.

## Деплой

```
python H:/projects/server151/deploy.py --yes
```

Деплой кладёт только `html/` (без `downloads/`). После смены версии вставьте оба loader заново с `/ai/configs`.

## Установка

1. [Violentmonkey](https://violentmonkey.github.io/).
2. Войдите на сайт.
3. `/ai/configs` → **Violentmonkey · Qwen** и **Violentmonkey · DeepSeek**.
4. Дашборд Violentmonkey → «+» → Новый скрипт → вставьте целиком. Нужны два скрипта.
5. Откройте `chat.qwen.ai` и `chat.deepseek.com`. Слева сверху — консоль.

**1.9.18**: Qwen не рвёт job из‑за `NO_PROJECT`; полный ответ (Monaco)
читается после генерации. Loader берёт JS с `/assets/js/aipilot/`.

## Раздача

- `/assets/js/aipilot/{file}` — канон (статический файл)
- `GET /api/aipilot/bridge/scripts/{file}`
- запас: `/js/aipilot/` и `/static/aipilot/` (алиасы на этот же каталог)
