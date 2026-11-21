# DeepSeek userscript (1.9.30)

В Violentmonkey ставится **loader** `aipilot-deepseek.user.js`. Ядро и адаптер подгружаются с `/assets/js/aipilot/`. Версию loader не поднимать: навигация живёт в `deepseek-ui.js` / `bridge-core.js`.

## Вход

На `chat.deepseek.com` нажмите **Вход**. Cookie с чата на сайт не передаётся — вход только во вкладке сайта. Не должно быть `GET /api/aipilot/bridge/me` (такого маршрута нет) и `cookie bind: HTTP 401` с чата. Если видите это — loader старше 1.9.16, вставьте заново.

Аккаунт: `GET /api/auth/me`. Токен: `POST /api/aipilot/bridge/token` на origin сайта.

## Чат

| Действие | Правило |
|---|---|
| Переход | прямая ссылка `https://chat.deepseek.com/a/chat/s/{uuid}` (`location.assign`) |
| Id в БД | UUID из URL (`DEEPSEEK_CHAT_ID`). Title в `chatId` не класть |
| Нет id | новый чат → дождаться `/a/chat/s/{uuid}` → `postResult` |
| Тот же проект | один чат на проект (сайт и приложение). После 20 сообщений — новый чат, id на сайте обновляется |
| Чат удалён | код `CHAT_GONE` → клиент стирает id и создаёт новый |
| Expert | после загрузки URL снова включить Expert / Эксперт |
| Поиск title | fallback, если id пустой или assign дал 404/чужой тред |
| Поле | textarea Message DeepSeek |
| Отправка | primary circle рядом с полем; не кликать DeepThink; пустое поле ≠ Stop |
| Stop | только квадрат/подпись Stop, не Send-стрелка и не background-rect кнопки |
| Ответ | последний markdown ассистента **без** think/reasoning и без HTML-хрома страницы |

Пайплайн: DeepSeek готовит промпт, Qwen исполняет. Полный текст — `POST /jobs/{id}/result` (`text`/`response`/`result`, `chatId`) и шаги run. При сетевом сбое result повторяется 4 раза.
