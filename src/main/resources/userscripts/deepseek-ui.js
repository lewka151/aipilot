// DeepSeek DOM adapter for AIPilot.
(function (global) {
  'use strict';

  const Core = global.AIPilotCore;
  if (!Core || !Core.utils || typeof Core.utils.sendPromptText !== 'function') {
    try {
      const box = document.createElement('div');
      box.id = 'aipilot-missing-core';
      box.style.cssText = 'position:fixed;top:0;left:0;right:0;z-index:2147483647;padding:10px 14px;'
        + 'background:#0c0e12;color:#e6e4df;border-bottom:2px solid #a8976a;font:13px Segoe UI,sans-serif';
      box.textContent = 'AIPilot: нет ядра. Loader Violentmonkey должен скачать bridge-core.js с сервера.';
      (document.documentElement || document.body).appendChild(box);
    } catch (error) {
      // Overlay is best-effort.
    }
    return;
  }
  const U = Core.utils;
  const SEL = {
    input: 'textarea[placeholder="Message DeepSeek"], textarea[placeholder="Сообщение DeepSeek"], textarea.ds-scroll-area, textarea.ds-textarea__textarea, textarea[class*="ds-textarea"], div[contenteditable="true"][class*="ds-"], [contenteditable="true"][role="textbox"]',
    send: '.ds-button.ds-button--primary.ds-button--filled.ds-button--circle:not(.ds-button--disabled), div[role="button"].ds-button--primary.ds-button--filled.ds-button--circle:not(.ds-button--disabled), .ds-icon-button, div.ds-icon-button, [class*="ds-icon-button"], button[aria-label="Send"], button[aria-label="Отправить"], button[aria-label="Send message"]',
    stop: 'button[aria-label*="Stop"], button[aria-label*="стоп"], button[aria-label*="Остановить"], button[aria-label*="pause"], button[aria-label*="Pause"], .stop-generating, [class*="stop-generating"]',
    assistant: [
      '.ds-assistant-message-main-content',
      'div.ds-markdown.ds-assistant-message-main-content',
      '[class*="ds-assistant-message"] .ds-markdown',
      '[class*="AssistantMessage"] .ds-markdown',
      '[class*="assistant-message"] .markdown',
      '[data-testid*="assistant"] .ds-markdown',
      '[data-message-author-role="assistant"]',
      '[data-role="assistant"]',
      '.ds-markdown',
      'div.ds-markdown',
      '.markdown-body',
      '[class*="message-content"] .ds-markdown',
    ].join(', '),
    user: '.ds-user-message, [class*="user-message"], [class*="UserMessage"], [data-role="user"], [data-message-author-role="user"]',
    messageRow: '[class*="ds-message"], [class*="Message"], [class*="chat-message"], [data-testid*="message"]',
    think: '.ds-think-content, .ds-think, details[class*="think"], [class*="reasoning-content"], [class*="think-content"]',
    search: 'input[role="searchbox"][placeholder="Search chat content..."], input[role="searchbox"]',
    searchResult: '[id^="conversation-search-result-"][role="option"], [id^="conversation-search-result-"][role="option"]',
  };
  const HOME_URL = 'https://chat.deepseek.com/';

  function findInput() {
    const input = U.firstMatch(SEL.input);
    if (!input || input.disabled) return null;
    if (input.getAttribute('role') === 'searchbox') return null;
    const placeholder = input.getAttribute('placeholder') || '';
    if (/search chat content|поиск/i.test(placeholder) && !/deepseek/i.test(placeholder)) return null;
    return input;
  }

  function isDeepThink(button) {
    const text = ((button.getAttribute('aria-label') || '') + ' ' + (button.getAttribute('title') || '') + ' ' + (button.textContent || '')).toLowerCase();
    return /deep.?think|deepthink|глубокое.?мышлен/.test(text);
  }

  function usableSend(button) {
    if (!button || U.isDisabled(button) || !U.isVisible(button)) return false;
    if ((button.className || '').includes('ds-button--disabled')) return false;
    if (button.classList.contains('ds-toggle-button') || button.closest('.ds-toggle-button')) return false;
    if (isDeepThink(button)) return false;
    return true;
  }

  function findSend() {
    const buttons = typeof U.qsa === 'function' ? U.qsa(SEL.send) : [];
    const list = buttons.length ? buttons : (U.firstMatch(SEL.send) ? [U.firstMatch(SEL.send)] : []);
    const found = list.find(usableSend);
    if (found) return found;
    const input = findInput();
    const root = input && (input.closest('form, [class*="composer"], [class*="chat-input"]') || input.parentElement);
    if (!root || typeof U.qsa !== 'function') return null;
    return U.qsa('.ds-icon-button, [class*="ds-icon-button"], .ds-button--primary', root).find(usableSend) || null;
  }

  async function sendPrompt(text) {
    return U.sendPromptText({
      text: text,
      findInput: findInput,
      findSend: findSend,
      findStop: findStop,
      readAssistant: readAssistant,
      ctrlEnter: false,
      warn: 'DeepSeek send button not ready; using Enter fallback',
    });
  }

  function findStop() {
    const hit = U.firstMatch(SEL.stop);
    if (hit) return hit;
    return U.qsa('button, [role="button"]').find(element => {
      if (!U.isVisible(element) || U.isDisabled(element)) return false;
      const label = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).toLowerCase();
      return /stop|pause|остановить|стоп/.test(label);
    }) || null;
  }

  function stripThink(text) {
    return String(text || '')
      .replace(/<(?:think|reasoning)[\s\S]*?<\/(?:think|reasoning)>/gi, '')
      .replace(/^\s*(thinking|размышл[^\n]*)\n[\s\S]*?\n\n/i, '')
      .trim();
  }

  function isThinkOnly(element) {
    if (!element) return true;
    try {
      return !!(element.matches && element.matches(SEL.think))
        || !!(element.closest && element.closest(SEL.think));
    } catch (_) {
      return false;
    }
  }

  function nodeText(element) {
    if (!element) return '';
    const copy = element.querySelector
      && element.querySelector('[class*="copy"], [aria-label*="Copy"], [aria-label*="Копир"]');
    const root = element.querySelector
      ? (element.querySelector('.ds-markdown, .markdown-body, [class*="markdown"]') || element)
      : element;
    return stripThink(String(root.innerText || root.textContent || '').trim());
  }

  function collectAssistantNodes() {
    const seen = new Set();
    const list = [];
    for (const selector of SEL.assistant.split(',')) {
      U.qsa(selector.trim()).forEach((el) => {
        if (!el || seen.has(el)) return;
        seen.add(el);
        list.push(el);
      });
    }
    return list;
  }

  function lastUserNode() {
    const users = U.qsa(SEL.user).filter((el) => U.isVisible(el) && (el.innerText || '').trim());
    return users.length ? users[users.length - 1] : null;
  }

  function nodesAfter(ref) {
    if (!ref) return collectAssistantNodes();
    const all = collectAssistantNodes();
    return all.filter((el) => {
      try {
        return !!(ref.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING);
      } catch (_) {
        return true;
      }
    });
  }

  function pickBestAssistant(preferAfterUser) {
    const after = preferAfterUser ? nodesAfter(lastUserNode()) : collectAssistantNodes();
    const candidates = after.length ? after : collectAssistantNodes();
    const scored = [];
    for (const el of candidates) {
      if (!U.isVisible(el)) continue;
      const thinkOnly = isThinkOnly(el);
      let text = nodeText(el);
      if (!text && thinkOnly) {
        text = stripThink(String(el.innerText || '').trim());
      }
      if (!text) continue;
      const score = text.length
        + (thinkOnly ? -50 : 200)
        + ((el.className || '').includes('assistant-message-main') ? 80 : 0)
        + ((el.className || '').includes('ds-markdown') ? 40 : 0);
      scored.push({ el, text, score, thinkOnly });
    }
    scored.sort((a, b) => a.score - b.score);
    return scored.length ? scored[scored.length - 1] : null;
  }

  function reactPropsText(element) {
    if (!element) return '';
    let best = '';
    function consider(value, depth) {
      if (depth > 6) return;
      if (typeof value === 'string') {
        const t = stripThink(value);
        if (t.length > best.length && t.length > 40) best = t;
        return;
      }
      if (!value || typeof value !== 'object') return;
      if (typeof value.content === 'string') consider(value.content, depth + 1);
      if (typeof value.markdown === 'string') consider(value.markdown, depth + 1);
      if (typeof value.text === 'string') consider(value.text, depth + 1);
      if (typeof value.message === 'string') consider(value.message, depth + 1);
      if (Array.isArray(value)) {
        for (let i = 0; i < Math.min(value.length, 40); i++) consider(value[i], depth + 1);
      }
    }
    try {
      const keys = Object.keys(element);
      for (let i = 0; i < keys.length; i++) {
        if (!/^__react/.test(keys[i]) && keys[i] !== '_reactProps') continue;
        consider(element[keys[i]], 0);
      }
    } catch (_) { /* ignore */ }
    return best;
  }

  function harvestFromPageScripts() {
    const needles = [
      /"content"\s*:\s*"((?:\\.|[^"\\]){80,})"/g,
      /"markdown"\s*:\s*"((?:\\.|[^"\\]){80,})"/g,
      /"parts"\s*:\s*\[\s*"((?:\\.|[^"\\]){80,})"/g,
    ];
    const blobs = [];
    try {
      const next = document.getElementById('__NEXT_DATA__');
      if (next && next.textContent) blobs.push(next.textContent);
    } catch (_) { /* ignore */ }
    try {
      U.qsa('script[type="application/json"], script#__NEXT_DATA__').forEach((s) => {
        const t = s.textContent || '';
        if (t.length > 200 && t.length < 2e6) blobs.push(t);
      });
    } catch (_) { /* ignore */ }
    let best = '';
    for (const blob of blobs) {
      for (const re of needles) {
        re.lastIndex = 0;
        let m;
        while ((m = re.exec(blob))) {
          let raw = m[1];
          try {
            raw = JSON.parse('"' + raw + '"');
          } catch (_) {
            raw = raw.replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\\\/g, '\\');
          }
          const t = stripThink(String(raw || '').trim());
          if (t.length > best.length
            && !/chat\.deepseek|Message DeepSeek|__NEXT_DATA__/i.test(t)
            && (t.indexOf('```') >= 0 || t.indexOf('<file') >= 0 || t.length > 160)) {
            best = t;
          }
        }
      }
    }
    return best;
  }

  function readAssistant() {
    const hit = pickBestAssistant(true);
    if (hit && hit.text) return hit.text;
    const any = pickBestAssistant(false);
    if (any && any.text) return any.text;
    const rows = U.qsa(SEL.messageRow).filter((el) => U.isVisible(el));
    for (let i = rows.length - 1; i >= 0; i--) {
      const t = nodeText(rows[i]);
      if (t && t.length > 20) return t;
    }
    return '';
  }

  async function readAssistantFull() {
    const light = readAssistant();
    let best = light;
    const hit = pickBestAssistant(true) || pickBestAssistant(false);
    if (hit && hit.el) {
      const react = reactPropsText(hit.el) || reactPropsText(hit.el.parentElement);
      if (react && react.length > (best || '').length) best = react;
      const row = hit.el.closest(SEL.messageRow) || hit.el.parentElement;
      if (row) {
        const rowText = nodeText(row);
        if (rowText && rowText.length > (best || '').length) best = rowText;
      }
    }
    if ((!best || best.length < 80)) {
      const fromScript = harvestFromPageScripts();
      if (fromScript && fromScript.length > (best || '').length) best = fromScript;
    }
    return stripThink(best || light || '');
  }

  function normalizeMatchText(text) {
    return String(text || '')
      .replace(/^CHAT_HEADER:.*\n+/i, '')
      .replace(/\s+/g, ' ')
      .trim()
      .slice(0, 900);
  }

  function findMatchingUserPrompt(prompt) {
    const want = normalizeMatchText(prompt);
    if (!want) return false;
    return !!findUserPromptNode(want);
  }

  function findUserPromptNode(want) {
    const nodes = U.qsa(SEL.user);
    for (const node of nodes) {
      const t = normalizeMatchText(node.innerText || node.textContent || '');
      if (!t) continue;
      if (t === want || (want.length > 48 && t.indexOf(want.slice(0, 160)) >= 0)
        || (t.length > 48 && want.indexOf(t.slice(0, 160)) >= 0)) {
        return node;
      }
    }
    return null;
  }

  function readAnswerForPrompt(prompt) {
    const want = normalizeMatchText(prompt);
    if (!want) return readAssistant();
    const userNode = findUserPromptNode(want);
    if (!userNode) return readAssistant();
    const after = [];
    collectAssistantNodes().forEach((el) => {
      try {
        if (userNode.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING) after.push(el);
      } catch (_) { /* ignore */ }
    });
    let best = '';
    for (const el of after) {
      if (!U.isVisible(el)) continue;
      const t = nodeText(el) || (isThinkOnly(el) ? stripThink(el.innerText || '') : '');
      if (t && t.length >= best.length) best = t;
    }
    if (best) return best;
    const siblings = [];
    let walk = userNode.nextElementSibling;
    let guard = 0;
    while (walk && guard < 12) {
      siblings.push(walk);
      walk = walk.nextElementSibling;
      guard += 1;
    }
    const parent = userNode.parentElement;
    if (parent) {
      U.qsa(SEL.assistant, parent).forEach((el) => siblings.push(el));
    }
    for (const el of siblings) {
      const t = nodeText(el);
      if (t && t.length >= best.length) best = t;
    }
    return best || readAssistant();
  }

  function homeUrl() {
    return HOME_URL;
  }

  function visibleTextSpan(text) {
    return U.qsa('span').find(element => {
      return (element.textContent || '').trim() === text && U.isVisible(element);
    }) || null;
  }

  function clickNewChat() {
    const label = visibleTextSpan('New chat') || visibleTextSpan('Новый чат');
    if (!label) return 'NO_NEW';
    const target = label.closest('[tabindex], [role="button"], button, a') || label.parentElement;
    if (!target || U.isDisabled(target)) return 'NO_NEW';
    target.click();
    return 'NEW';
  }

  function findSearchInput() {
    const input = U.firstMatch(SEL.search);
    return input && !input.disabled ? input : null;
  }

  function findSearchButton() {
    const labelled = Array.from(document.querySelectorAll(
      '[aria-label*="Search"], [aria-label*="search"], [aria-label*="Поиск"]'
    )).find(element => U.isVisible(element) && !U.isDisabled(element));
    if (labelled) return labelled;

    const newChatLabel = visibleTextSpan('New chat') || visibleTextSpan('Новый чат');
    const newChat = newChatLabel
      && (newChatLabel.closest('[tabindex], [role="button"], button, a') || newChatLabel.parentElement);
    const sidebar = (newChat && newChat.parentElement)
      || U.qs('aside, nav, [class*="sidebar"]')
      || document;
    return Array.from(sidebar.querySelectorAll(
      '[role="button"], button, [tabindex="0"]'
    )).find(element => {
      if (element === newChat || (newChatLabel && element.contains(newChatLabel))) return false;
      if (!element.querySelector('.ds-button__background')) return false;
      if (element.classList.contains('ds-button--primary')) return false;
      return U.isVisible(element) && !U.isDisabled(element);
    }) || null;
  }

  async function openSearch() {
    if (findSearchInput()) return 'ALREADY';
    const button = findSearchButton();
    if (!button) {
      Core.log('warn', 'DeepSeek search button not found');
      return 'NOT_FOUND';
    }
    button.click();
    const input = await U.waitFor(findSearchInput, 1500, 100);
    if (!input) Core.log('warn', 'DeepSeek search input did not open');
    return input ? 'OK' : 'NOT_FOUND';
  }

  async function searchChats(query) {
    const opened = await openSearch();
    if (opened === 'NOT_FOUND') return 'NOT_FOUND';
    const input = findSearchInput();
    if (!input) return 'NOT_FOUND';
    U.setNativeValue(input, query || '');
    return 'SEARCH';
  }

  async function closeSearch() {
    if (!findSearchInput()) return;
    const options = {
      key: 'Escape',
      code: 'Escape',
      keyCode: 27,
      which: 27,
      bubbles: true,
      cancelable: true,
    };
    const input = findSearchInput();
    input.dispatchEvent(new KeyboardEvent('keydown', options));
    document.dispatchEvent(new KeyboardEvent('keydown', options));
    await U.sleep(200);
  }

  function resultTitle(result) {
    const lines = String(result && result.innerText || '')
      .split(/\r?\n/)
      .map(line => line.trim())
      .filter(Boolean);
    return lines.length ? lines[0] : '';
  }

  function selectChatResult(title) {
    if (!title) return 'SKIP';
    const hit = U.qsa(SEL.searchResult).find(result => {
      return U.titlesMatch(resultTitle(result), title);
    });
    if (!hit) return 'NOT_FOUND';
    hit.click();
    return 'OK';
  }

  function currentChatMatches(title) {
    return U.titlesMatch(document.title, title);
  }

  function looksLikeChatId(value) {
    const s = String(value || '').trim();
    if (!s || /\s/.test(s) || s.indexOf('.') >= 0) return false;
    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s)) return true;
    return s.length >= 16 && s.length <= 80 && /^[A-Za-z0-9_-]+$/.test(s);
  }

  function currentDeepSeekChatId() {
    try {
      const path = String(location.pathname || '');
      const m = path.match(/\/a\/chat\/s\/([A-Za-z0-9_-]+)/)
        || path.match(/\/(?:chat|chats|s)\/([A-Za-z0-9_-]+)/);
      if (m && looksLikeChatId(m[1])) return m[1];
      const hash = String(location.hash || '');
      const hm = hash.match(/\/a\/chat\/s\/([A-Za-z0-9_-]+)/)
        || hash.match(/\/(?:chat|chats|s)\/([A-Za-z0-9_-]+)/);
      if (hm && looksLikeChatId(hm[1])) return hm[1];
    } catch (error) {
      // URL parse is best-effort.
    }
    return '';
  }

  async function waitCapturedChatId(prevId, timeoutMs) {
    const before = String(prevId || '');
    return U.waitFor(() => {
      const id = currentDeepSeekChatId();
      if (looksLikeChatId(id) && id !== before) return id;
      return null;
    }, timeoutMs || 10000, 200);
  }

  async function openChatById(id) {
    const want = String(id || '').trim();
    if (!looksLikeChatId(want)) return 'SKIP';
    if (currentDeepSeekChatId() === want && findInput()) return 'ALREADY';
    location.assign('https://chat.deepseek.com/a/chat/s/' + encodeURIComponent(want));
    const input = await U.waitFor(() => {
      const got = currentDeepSeekChatId();
      if (got && looksLikeChatId(got) && got !== want) return null;
      return findInput();
    }, 15000, 200);
    if (!input) return 'CHAT_GONE';
    const got = currentDeepSeekChatId();
    if (got && looksLikeChatId(got) && got !== want) return 'CHAT_GONE';
    ensureExpertMode();
    return 'OK';
  }

  async function setChatTitle(title) {
    const want = String(title || '').trim();
    if (!want) return 'SKIP';
    try {
      const heading = U.qs('h1, [class*="title"] [contenteditable="true"], input[aria-label*="title" i]');
      if (heading && heading.getAttribute && heading.getAttribute('contenteditable') === 'true') {
        heading.focus();
        heading.textContent = want;
        heading.dispatchEvent(new Event('input', { bubbles: true }));
        heading.dispatchEvent(new Event('blur', { bubbles: true }));
        return 'OK';
      }
      if (heading && heading.tagName === 'INPUT') {
        heading.value = want;
        heading.dispatchEvent(new Event('input', { bubbles: true }));
        heading.dispatchEvent(new Event('change', { bubbles: true }));
        return 'OK';
      }
    } catch (error) {
      // title rename is best-effort
    }
    return 'SKIP';
  }

  async function startNewChat() {
    const prev = currentDeepSeekChatId();
    const created = clickNewChat();
    if (created !== 'NEW') return created;
    await U.waitFor(findInput, 8000, 150);
    await waitCapturedChatId(prev, 10000);
    ensureExpertMode();
    return findInput() ? 'NEW' : 'NO_INPUT';
  }

  async function ensureChat(title, chatId, rotate) {
    const id = looksLikeChatId(chatId) ? String(chatId).trim() : '';
    if (id && !rotate) {
      const opened = await openChatById(id);
      if (opened === 'OK' || opened === 'ALREADY') return opened;
      Core.activity('deepseek', 'direct chat ' + id + ' -> ' + opened);
    }
    const started = await startNewChat();
    if (title) await setChatTitle(title);
    if (started === 'NEW' || started === 'NO_INPUT') return started;
    if (id && !rotate) return 'CHAT_GONE';
    if (findInput()) return 'HOME';
    return started === 'NO_NEW' ? 'NO_NEW' : (id ? 'CHAT_GONE' : 'NO_NEW');
  }

  function ensureExpertMode() {
    const span = visibleTextSpan('Expert') || visibleTextSpan('Эксперт');
    if (span) {
      const radio = span.closest('[role="radio"], [data-model-type="expert"]') || span;
      if (radio.getAttribute('aria-checked') === 'true') return 'ALREADY';
      span.click();
      return 'ENABLED';
    }
    const radio = U.qs('div[data-model-type="expert"][role="radio"]');
    if (!radio) return 'NOT_FOUND';
    if (radio.getAttribute('aria-checked') === 'true') return 'ALREADY';
    radio.click();
    return 'ENABLED';
  }

  async function prepare(job) {
    const rotate = job && (job.rotateChat === true || job.rotate_chat === true);
    const header = String((job && (job.chatHeader || job.chat_header || job.chatTitle)) || '').trim();
    const chat = await ensureChat(header || job.chatTitle, job.chatId, rotate);
    Core.activity('deepseek', 'ensure chat "' + (header || job.chatTitle || job.chatId || '') + '" -> ' + chat
      + (rotate ? ' rotate' : ''));
    if (chat === 'CHAT_GONE') throw new Error('CHAT_GONE');
    if (job.expertMode !== false) {
      const expert = ensureExpertMode();
      Core.activity('deepseek', 'expert span -> ' + expert);
      if (expert === 'ENABLED') await U.sleep(300);
    }
  }

  const adapter = {
    id: 'deepseek',
    homeUrl,
    findInput,
    sendPrompt,
    findStop,
    readAssistant,
    readAssistantFull,
    readAnswerForPrompt,
    prepare,
    findMatchingUserPrompt,
    clickNewChat,
    openSearch,
    searchChats,
    closeSearch,
    selectChatResult,
    ensureChat,
    openChatById,
    currentChatId: currentDeepSeekChatId,
    ensureExpertMode,
  };
  global.AIPilotDeepSeek = adapter;
  if (typeof window !== 'undefined') window.AIPilotDeepSeek = adapter;
})(typeof unsafeWindow !== 'undefined' ? unsafeWindow : window);
