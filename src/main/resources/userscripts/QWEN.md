# Qwen userscript (1.9.30)

В Violentmonkey ставится **loader** `aipilot-qwen.user.js`. Ядро и адаптер подгружаются с `/assets/js/aipilot/`. Правки только там. Версию loader не поднимать: навигация живёт в `qwen-ui.js` / `bridge-core.js`.

## Вход

На `chat.qwen.ai` нажмите **Вход**. Откроется `/login` сайта (`window.open`, чтобы остался `opener`). После логина вкладка сайта биндит cookie (same-origin `GET /api/auth/me` → `POST /api/aipilot/bridge/token`) и шлёт `postMessage` канал `aipilot-auth`. Cookie с чата на сайт не уходит (SameSite=Lax) — это нормально, не ошибка.

Нужны оба скрипта одной версии. После 1.9.18 вставьте loader заново с `/ai/configs`.

## Чат

| Действие | Правило |
|---|---|
| Переход | прямая ссылка `https://chat.qwen.ai/c/{uuid}` (`location.assign`), не клик в сайдбаре |
| Id в БД | UUID из URL (`QWEN_CHAT_ID`). Title в `chatId` не класть |
| Нет id | новый чат внутри проекта Qwen → дождаться `/c/{uuid}` в адресной строке → `rememberChat` / `postResult` |
| Чат удалён | код `CHAT_GONE` → клиент стирает id и создаёт новый |
| Поле | textarea «Спросить Qwen» / «Ask Qwen» |
| Отправка | кнопка send в слоте композера; запас — Ctrl+Enter, затем Enter |
| Поиск title | fallback, если id пустой или assign дал 404/чужой тред |
| Ответ | последний markdown ассистента без HTML-хрома страницы; полный текст в job result |

Проекты Qwen: выбрать / создать по названиям в сайдбаре. Если проекта нет — промпт уходит в уже открытый чат.
