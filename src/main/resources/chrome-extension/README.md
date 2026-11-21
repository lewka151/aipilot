# Расширение Chrome AIPilot

Плагин сам вставляет JS поверх `chat.qwen.ai` и `chat.deepseek.com`.
Tampermonkey не нужен.

## Установка

1. Войдите в приложение AIPilot.
2. Настройки → **Папка расширения Chrome** (или «Экспорт в папку»).
3. Откройте `chrome://extensions`, включите **Режим разработчика**.
4. **Загрузить распакованное расширение** и укажите папку `chrome-extension`
   (обычно `%APPDATA%\AIPilot\chrome-extension`).
5. Откройте Qwen и DeepSeek. Вверху страницы появится консоль AIPilot.

Если смените сервер или token в приложении, снова нажмите экспорт и
на `chrome://extensions` нажмите обновление у AIPilot. Либо введите данные
в popup расширения и обновите вкладки чатов.

## Как это работает

- Isolated content script ходит в site bridge API (`host_permissions`).
- MAIN-world скрипты (`bridge-core.js` + `qwen-ui.js` / `deepseek-ui.js`)
  управляют DOM чата так же, как userscript.
- На домене API регистрируется token для poll заданий.
