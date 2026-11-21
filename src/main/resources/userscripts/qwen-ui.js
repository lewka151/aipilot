// Qwen Studio DOM adapter for AIPilot.
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
    input: 'textarea.message-input-textarea[placeholder="Спросить Qwen"], textarea[placeholder="Спросить Qwen"], textarea[placeholder="Ask Qwen"], textarea.message-input-textarea, .message-input-container textarea, .message-input textarea, .chat-message-input textarea',
    composer: '.message-input-container, .message-input',
    sendSlot: '.message-input-right-button-send, .message-input-right-button',
    sendCandidate: '[role="button"], button',
    assistant: '.chat-response-message .response-message-content.phase-answer, .chat-response-message .response-message-content, .response-message-content.phase-answer, .qwen-markdown, .message.assistant .message-content, .message.assistant .markdown-body, .markdown-body, [data-message-author-role="assistant"], .assistant-message, [class*="assistant-message"], .response-message-content',
    stop: 'button[aria-label*="Stop"], button[aria-label*="стоп"], button[aria-label*="Stop generating"], button[aria-label*="Остановить"], button[aria-label*="pause"], button[aria-label*="Pause"], .stop-generating, [class*="stop-generating"]',
    newChat: '[aria-label="Новый чат"].sidebar-entry-fixed-list-content, [aria-label="Новый чат"], [aria-label="New chat"]',
    search: 'input.chat-search-input[placeholder="Поиск в чатах"], input.chat-search-input',
    chatTitle: '.project-chat-item-title, .chat-item-drag-link-content-tip-text',
    chatLink: '.project-chat-item, a.chat-item-drag-link[aria-label="chat-item"], a.chat-item-drag-link',
    projectItem: '.project-item-text',
    projectHeader: '.project-item-header',
    projectRow: '.project-item, .project-item-header',
    projectChats: '.pct__chats, .project-chat-list, .pch__item, .pch',
    globalNewChatRoot: '.sidebar-entry-fixed-list, .sidebar-new-chat',
    advancedSettings: '.pas__toggle-text',
    projectInstruction: 'textarea[placeholder*="Что ИИ должен знать об этом проекте"], textarea[placeholder*="What should the AI know"]',
    projectInstructionsButton: 'button.pcht__action-btn[aria-label="Инструкции по проектам"], button[aria-label="Инструкции по проектам"], button[aria-label*="Project instruction"]',
    addFiles: '.qwen-chat-v2-btn-content',
    filesUpload: '#filesUpload',
    dialog: '.ant-modal, .ant-drawer, [role="dialog"]',
  };
  const THINK_SEL = '.ds-think-content, .thinking, [data-role="think"], .qwen-chat-thinking-status-card, .qwen-chat-thinking-tool-status-card-wraper, .qwen-chat-thinking-status-card-wraper';

  const CHAT_MAP_KEY = 'aipilot.qwen.chatMap';
  let lastSiteChatId = '';

  function findInput() {
    const pick = typeof U.firstVisibleMatch === 'function' ? U.firstVisibleMatch : U.firstMatch;
    const input = pick(SEL.input);
    if (!input || input.disabled) return null;
    const placeholder = input.getAttribute('placeholder') || '';
    if (/что ии должен знать|what should the ai know/i.test(placeholder)) return null;
    return input;
  }

  function isComposerChrome(element) {
    if (!element || !element.closest) return false;
    return Boolean(element.closest(
      '.message-input-container, .mode-select, .qwen-thinking-selector, .omb, .record-btn-container'
    ));
  }

  function isVoiceControl(element) {
    if (!element) return true;
    if (isComposerChrome(element) && element.closest && element.closest('.omb, .record-btn-container')) {
      return true;
    }
    const label = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).trim();
    return /голосовой режим|голосовой|voice|мышление|thinking/i.test(label)
      || /soundPlaying|mic/i.test(U.hrefOfUse(element));
  }

  function isSendControl(element) {
    if (!element || isVoiceControl(element) || U.isDisabled(element) || !U.isVisible(element)) return false;
    if (element.closest && element.closest('.mode-select, .qwen-thinking-selector, .omb, .record-btn-container')) {
      return false;
    }
    const label = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).trim();
    const href = U.hrefOfUse(element);
    if (/отправ|send/i.test(label) && !/голос|voice/i.test(label)) return true;
    if (/sendChat|send(?![A-Za-z]*Playing)/i.test(href) && !/soundPlaying|mic/i.test(href)) return true;
    return false;
  }

  function findSend() {
    const composer = U.qs(SEL.composer) || document;
    const uses = U.qsa('use', composer);
    for (let i = 0; i < uses.length; i++) {
      const href = uses[i].getAttribute('href')
        || uses[i].getAttribute('xlink:href')
        || '';
      if (!/sendChat|#qwpcicon-send|arrowUp|paper/i.test(href) || /soundPlaying|mic/i.test(href)) continue;
      const btn = uses[i].closest('button, [role="button"]');
      if (isSendControl(btn)) return btn;
    }
    const labeled = U.qsa('[aria-label], button, [role="button"]', composer).find(isSendControl);
    if (labeled) return labeled;
    const roots = [];
    U.qsa(SEL.sendSlot).forEach(node => roots.push(node));
    if (!roots.length) roots.push(composer);
    for (const root of roots) {
      for (const selector of SEL.sendCandidate.split(',')) {
        const candidates = U.qsa(selector.trim(), root);
        for (const candidate of candidates) {
          if (isSendControl(candidate)) return candidate;
        }
      }
    }
    return null;
  }

  async function sendPrompt(text) {
    const status = await U.sendPromptText({
      text: text,
      findInput: findInput,
      findSend: findSend,
      findStop: findStop,
      readAssistant: readAssistant,
      ctrlEnter: false,
      warn: 'Qwen send button not ready; Enter then Ctrl+Enter',
    });
    rememberChat(lastSiteChatId, currentQwenChatId());
    return status;
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

  function isThinkNode(element) {
    return !!(element && element.closest && element.closest(THINK_SEL));
  }

  function lastAnswerRoot() {
    const messages = U.qsa('.chat-response-message');
    for (let i = messages.length - 1; i >= 0; i--) {
      const answer = messages[i].querySelector(
        '.response-message-content.phase-answer, .response-message-content, .qwen-markdown'
      );
      if (answer && !isThinkNode(answer) && (answer.innerText || '').trim()) return answer;
    }
    const nodes = [];
    for (const selector of SEL.assistant.split(',')) {
      nodes.push(...U.qsa(selector.trim()));
    }
    const visible = nodes.filter(element => {
      if (isThinkNode(element)) return false;
      return U.isVisible(element) && (element.innerText || '').trim();
    });
    return visible.length ? visible[visible.length - 1] : null;
  }

  function readAssistant() {
    const root = lastAnswerRoot();
    if (!root) return '';
    const md = root.querySelector('.qwen-markdown') || root;
    return (md.innerText || '').trim();
  }

  function monacoApi() {
    try {
      if (global.monaco) return global.monaco;
    } catch (error) {
      // Isolated world.
    }
    try {
      if (typeof window !== 'undefined' && window.monaco) return window.monaco;
    } catch (error) {
      // Ignore.
    }
    return null;
  }

  function monacoValueFor(pre) {
    const api = monacoApi();
    if (!api || !api.editor) return '';
    try {
      if (typeof api.editor.getEditors === 'function') {
        const editors = api.editor.getEditors();
        for (let i = 0; i < editors.length; i++) {
          const editor = editors[i];
          const node = editor && editor.getDomNode && editor.getDomNode();
          if (node && pre.contains(node) && editor.getValue) {
            const value = editor.getValue();
            if (value) return value;
          }
        }
      }
      const hosts = pre.querySelectorAll('[data-uri]');
      let uri = '';
      for (let i = 0; i < hosts.length; i++) {
        uri = hosts[i].getAttribute('data-uri')
          || (hosts[i].dataset && hosts[i].dataset.uri)
          || '';
        if (uri) break;
      }
      if (!uri) {
        const host = pre.querySelector('.monaco-editor');
        uri = host && (host.getAttribute('data-uri') || (host.dataset && host.dataset.uri) || '');
      }
      if (uri && typeof api.editor.getModels === 'function') {
        const models = api.editor.getModels();
        for (let i = 0; i < models.length; i++) {
          const model = models[i];
          const modelUri = model && model.uri ? String(model.uri) : '';
          if (modelUri === uri || modelUri.indexOf(uri) !== -1) {
            const value = model.getValue();
            if (value) return value;
          }
        }
      }
    } catch (error) {
      // Monaco API is best-effort.
    }
    return '';
  }

  function reactPropsText(element) {
    if (!element) return '';
    let best = '';
    function consider(value) {
      if (typeof value === 'string') {
        if (value.length > best.length && (value.indexOf('<file') !== -1 || value.length > 80)) {
          best = value;
        }
        return;
      }
      if (value && typeof value === 'object') {
        if (typeof value.content === 'string') consider(value.content);
        if (typeof value.markdown === 'string') consider(value.markdown);
        if (typeof value.text === 'string') consider(value.text);
      }
    }
    function visit(fiber, depth) {
      if (!fiber || depth > 28) return;
      consider(fiber.memoizedProps || fiber.pendingProps);
      visit(fiber.child, depth + 1);
      visit(fiber.sibling, depth + 1);
    }
    const keys = Object.keys(element);
    for (let i = 0; i < keys.length; i++) {
      if (keys[i].indexOf('__reactFiber') === 0
        || keys[i].indexOf('__reactInternalInstance') === 0
        || keys[i].indexOf('__reactProps') === 0) {
        visit(element[keys[i]], 0);
      }
    }
    return best;
  }

  function markdownPlain(element) {
    if (!element) return '';
    const bits = [];
    const nodes = element.querySelectorAll('.qwen-markdown-text, .qwen-markdown-codespan');
    for (let i = 0; i < nodes.length; i++) {
      bits.push(nodes[i].textContent || '');
    }
    return bits.join('').replace(/\s+/g, ' ').trim()
      || (element.textContent || '').replace(/\s+/g, ' ').trim();
  }

  function tableToMarkdown(table) {
    if (!table) return '';
    const rows = [];
    const trs = table.querySelectorAll('tr');
    for (let i = 0; i < trs.length; i++) {
      const cells = trs[i].querySelectorAll('th, td');
      if (!cells.length) continue;
      const cols = [];
      for (let c = 0; c < cells.length; c++) cols.push(markdownPlain(cells[c]).replace(/\|/g, '\\|'));
      rows.push('| ' + cols.join(' | ') + ' |');
      if (i === 0) rows.push('| ' + cols.map(() => '---').join(' | ') + ' |');
    }
    return rows.join('\n');
  }

  function joinHarvested(map, total) {
    const nums = Object.keys(map).map(Number);
    const max = Math.max(total || 0, nums.length ? Math.max.apply(null, nums) : 0);
    const lines = [];
    for (let i = 1; i <= max; i++) {
      lines.push(map[i] != null ? map[i] : '');
    }
    return lines.join('\n').replace(/[ \t]+$/gm, '').replace(/\s+$/, '');
  }

  function grabMonacoViewport(pre, into) {
    const numbers = pre.querySelectorAll('.margin-view-overlays .line-numbers');
    const lines = pre.querySelectorAll('.view-lines .view-line');
    const count = Math.min(numbers.length, lines.length);
    for (let i = 0; i < count; i++) {
      const num = parseInt((numbers[i].textContent || '').trim(), 10);
      if (!Number.isFinite(num)) continue;
      into[num] = (lines[i].innerText || '').replace(/\u00a0/g, ' ');
    }
  }

  async function harvestMonaco(pre) {
    const collected = {};
    grabMonacoViewport(pre, collected);
    const viewLines = pre.querySelector('.view-lines');
    const scrollEl = pre.querySelector('.monaco-scrollable-element');
    const totalHeight = viewLines ? parseFloat(viewLines.style.height) || viewLines.scrollHeight : 0;
    const lineHeight = 20;
    const totalLines = Math.max(Object.keys(collected).length, Math.round(totalHeight / lineHeight) || 0);
    if (!scrollEl || Object.keys(collected).length >= totalLines) {
      return joinHarvested(collected, totalLines);
    }
    const viewport = scrollEl.clientHeight || 360;
    const step = Math.max(80, viewport - 48);
    const api = monacoApi();
    let editor = null;
    try {
      if (api && api.editor && typeof api.editor.getEditors === 'function') {
        editor = api.editor.getEditors().find(item => {
          const node = item && item.getDomNode && item.getDomNode();
          return node && pre.contains(node);
        }) || null;
      }
    } catch (error) {
      editor = null;
    }
    for (let top = 0; top <= totalHeight + viewport; top += step) {
      try {
        if (editor && editor.setScrollTop) editor.setScrollTop(top);
        else {
          scrollEl.scrollTop = top;
          const content = scrollEl.querySelector('.lines-content');
          if (content) content.style.transform = 'translate3d(0px, ' + (-top) + 'px, 0px)';
        }
      } catch (error) {
        scrollEl.scrollTop = top;
      }
      await U.sleep(35);
      grabMonacoViewport(pre, collected);
      if (Object.keys(collected).length >= totalLines) break;
    }
    try {
      if (editor && editor.setScrollTop) editor.setScrollTop(0);
    } catch (error) {
      // Ignore restore failures.
    }
    return joinHarvested(collected, totalLines);
  }

  function stripEditorChrome(text) {
    if (U && typeof U.stripEditorChrome === 'function') return U.stripEditorChrome(text);
    let s = String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    const lines = s.split('\n');
    const numIdx = lines.filter((ln) => /^\s*\d+\s*$/.test(ln));
    if (numIdx.length >= 5) s = lines.filter((ln) => !/^\s*\d+\s*$/.test(ln)).join('\n');
    s = s.replace(/<\s*file\s+path\s*=\s*"([^"]+)"\s*>/gi, '<file path="$1">');
    s = s.replace(/<\s*\/\s*file\s*>/gi, '</file>');
    s = s.replace(/(^|\n)(?:xml|json|javascript|html)\s*(?=\n\s*(?:<file\b|```))/gi, '$1');
    return s.trim();
  }

  async function readCodeBlock(pre) {
    const fromEditor = monacoValueFor(pre);
    if (fromEditor && fromEditor.length > 20) return fromEditor;
    const fromReact = reactPropsText(pre);
    if (fromReact && fromReact.length > 20) return fromReact;
    const harvested = await harvestMonaco(pre);
    if (harvested && harvested.length > 20) return harvested;
    return stripEditorChrome(pre.innerText || '');
  }

  function codeFenceLang(pre) {
    const header = pre.querySelector('.qwen-markdown-code-header > div');
    const lang = ((header && header.textContent) || '').trim().split(/\s+/)[0];
    return lang || '';
  }

  async function readMarkdownFull(root) {
    const md = (root && root.querySelector('.qwen-markdown')) || root;
    if (!md) return '';
    const whole = reactPropsText(root) || reactPropsText(md);
    if (whole && whole.indexOf('<file') !== -1 && whole.length > 200) return whole.trim();
    const parts = [];
    const children = md.children || [];
    for (let i = 0; i < children.length; i++) {
      const child = children[i];
      const cls = child.className || '';
      if (/\bqwen-markdown-space\b/.test(cls)) continue;
      if (/\bqwen-markdown-heading\b/.test(cls) || /^H[1-6]$/.test(child.tagName)) {
        const level = /^H[1-6]$/.test(child.tagName) ? Number(child.tagName.slice(1)) : 1;
        const title = markdownPlain(child);
        if (title) parts.push('#'.repeat(level) + ' ' + title);
        continue;
      }
      if (/\bqwen-markdown-list\b/.test(cls) || child.tagName === 'OL' || child.tagName === 'UL') {
        const items = child.querySelectorAll('.qwen-markdown-list-item, li');
        const ordered = child.tagName === 'OL';
        for (let n = 0; n < items.length; n++) {
          if (items[n].parentElement !== child) continue;
          const line = markdownPlain(items[n]);
          if (!line) continue;
          parts.push(ordered ? (n + 1) + '. ' + line : '- ' + line);
        }
        continue;
      }
      if (child.tagName === 'TABLE' || /\bqwen-markdown-table\b/.test(cls)) {
        const md = tableToMarkdown(child.tagName === 'TABLE' ? child : child.querySelector('table') || child);
        if (md) parts.push(md);
        continue;
      }
      if (child.tagName === 'BLOCKQUOTE' || /\bqwen-markdown-blockquote\b/.test(cls)) {
        const quote = markdownPlain(child);
        if (quote) parts.push(quote.split('\n').map((ln) => '> ' + ln).join('\n'));
        continue;
      }
      if (/\bqwen-markdown-code\b/.test(cls) || child.tagName === 'PRE') {
        const lang = codeFenceLang(child);
        const code = await readCodeBlock(child);
        if (code) {
          const body = code.replace(/\s+$/, '');
          if (/<file\s+path=/i.test(body) && (!lang || /^xml$/i.test(lang))) {
            parts.push(body);
          } else if (lang) {
            parts.push('```' + lang + '\n' + body + '\n```');
          } else {
            parts.push(body);
          }
        }
        continue;
      }
      if (/\bqwen-markdown-paragraph\b/.test(cls) || child.tagName === 'P') {
        const line = markdownPlain(child);
        if (line) parts.push(line);
      }
    }
    if (!parts.length) return (md.innerText || '').trim();
    return parts.join('\n\n').trim();
  }

  async function readAssistantFull() {
    const root = lastAnswerRoot();
    if (!root) return readAssistant();
    const message = root.closest('.chat-response-message') || root;
    const full = await readMarkdownFull(message);
    const light = readAssistant();
    if (full && full.length >= (light || '').length) return full;
    return light || full || '';
  }

  function isGlobalNewChat(element) {
    if (!element) return true;
    if (element.closest(SEL.globalNewChatRoot)) return true;
    if (element.classList && element.classList.contains('sidebar-entry-fixed-list-content')) return true;
    return false;
  }

  function projectRootFromName(name) {
    const hit = findProjectNode(name);
    if (!hit) return null;
    return hit.closest('.project-item') || hit.closest(SEL.projectRow) || hit.parentElement;
  }

  function chatScopes(projectRoot) {
    const scopes = [];
    if (projectRoot) scopes.push(projectRoot);
    for (const selector of SEL.projectChats.split(',')) {
      const node = U.qs(selector.trim());
      if (node && !scopes.includes(node)) scopes.push(node);
    }
    return scopes;
  }

  function gmGet(key, fallback) {
    try {
      if (typeof GM_getValue === 'function') return GM_getValue(key, fallback);
    } catch (error) {
      // Isolated world.
    }
    try {
      const raw = localStorage.getItem(key);
      return raw == null ? fallback : raw;
    } catch (error) {
      return fallback;
    }
  }

  function gmSet(key, value) {
    try {
      if (typeof GM_setValue === 'function') GM_setValue(key, value);
    } catch (error) {
      // Isolated world.
    }
    try {
      localStorage.setItem(key, value);
    } catch (error) {
      // Ignore quota.
    }
  }

  function chatMap() {
    try {
      const parsed = JSON.parse(gmGet(CHAT_MAP_KEY, '{}') || '{}');
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (error) {
      return {};
    }
  }

  function rememberChat(siteChatId, qwenId) {
    const site = String(siteChatId || '').trim();
    const qwen = String(qwenId || '').trim();
    if (!site || !qwen) return;
    const map = chatMap();
    if (map[site] === qwen) return;
    map[site] = qwen;
    gmSet(CHAT_MAP_KEY, JSON.stringify(map));
  }

  function mappedQwenId(siteChatId) {
    const site = String(siteChatId || '').trim();
    if (!site) return '';
    return String(chatMap()[site] || '').trim();
  }

  function looksLikeId(value) {
    const s = String(value || '').trim();
    if (s.length < 8 || s.length > 80) return false;
    if (/\s/.test(s)) return false;
    return /^[A-Za-z0-9_-]+$/.test(s);
  }

  function collectIdsFromProps(props, into) {
    if (!props || typeof props !== 'object') return;
    const keys = [
      'id', 'chatId', 'chat_id', 'sessionId', 'session_id',
      'conversationId', 'conversation_id', 'uid', 'key',
    ];
    for (let i = 0; i < keys.length; i++) {
      const value = props[keys[i]];
      if (typeof value === 'string' && looksLikeId(value) && value !== 'chat-item') {
        into.push(value);
      } else if (value && typeof value === 'object' && typeof value.id === 'string' && looksLikeId(value.id)) {
        into.push(value.id);
      }
    }
  }

  function reactIds(element) {
    const ids = [];
    if (!element) return ids;
    const keys = Object.keys(element);
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      if (key.indexOf('__reactFiber') !== 0
        && key.indexOf('__reactInternalInstance') !== 0
        && key.indexOf('__reactProps') !== 0) continue;
      let fiber = element[key];
      for (let depth = 0; fiber && depth < 14; depth++) {
        collectIdsFromProps(fiber.memoizedProps || fiber.pendingProps || fiber, ids);
        fiber = fiber.return || fiber._owner;
      }
    }
    return ids;
  }

  function chatItemIds(element) {
    const ids = [];
    if (!element) return ids;
    const nodes = [element];
    if (element.querySelectorAll) {
      nodes.push(...element.querySelectorAll('[data-id], [data-chat-id], [href], a'));
    }
    for (let n = 0; n < nodes.length; n++) {
      const node = nodes[n];
      if (!node || !node.getAttribute) continue;
      ['data-id', 'data-chat-id', 'data-chatid', 'data-key', 'id'].forEach(attr => {
        const value = node.getAttribute(attr);
        if (looksLikeId(value)) ids.push(value);
      });
      const href = node.getAttribute('href') || '';
      const match = String(href).match(/\/(?:c|chat|chats)\/([A-Za-z0-9_-]+)/i)
        || String(href).match(/[?&](?:id|chatId|chat_id)=([^&]+)/i);
      if (match && looksLikeId(match[1])) ids.push(decodeURIComponent(match[1]));
    }
    reactIds(element).forEach(id => ids.push(id));
    const uniq = [];
    ids.forEach(id => {
      const s = String(id).trim();
      if (s && uniq.indexOf(s) < 0) uniq.push(s);
    });
    return uniq;
  }

  function currentQwenChatId() {
    try {
      const search = new URLSearchParams(location.search || '');
      const keys = ['chatId', 'chat_id', 'chat', 'sessionId', 'conversationId'];
      for (let i = 0; i < keys.length; i++) {
        const value = search.get(keys[i]);
        if (looksLikeId(value) && String(location.pathname || '').indexOf('/p/' + value) < 0) {
          return value;
        }
      }
      const path = String(location.pathname || '');
      const fromPath = path.match(/\/(?:c|chat|chats)\/([A-Za-z0-9_-]+)/i);
      if (fromPath && looksLikeId(fromPath[1])) return fromPath[1];
      const hash = String(location.hash || '');
      const fromHash = hash.match(/\/(?:c|chat|chats)\/([A-Za-z0-9_-]+)/i);
      if (fromHash && looksLikeId(fromHash[1])) return fromHash[1];
    } catch (error) {
      // URL parse is best-effort.
    }
    const active = U.qsa('.project-chat-item, a.chat-item-drag-link').find(node => {
      const cls = String(node.className || '') + ' ' + String((node.parentElement && node.parentElement.className) || '');
      return /active|selected|current/i.test(cls);
    });
    const ids = active ? chatItemIds(active) : [];
    return ids[0] || '';
  }

  function idsMatch(got, want) {
    const target = String(want || '').trim().toLowerCase();
    if (!target) return false;
    const list = got || [];
    if (list.some(id => String(id || '').trim().toLowerCase() === target)) return true;
    if (target.length < 12) return false;
    return list.some(id => {
      const value = String(id || '').trim().toLowerCase();
      return value.length >= 12 && (value.indexOf(target) >= 0 || target.indexOf(value) >= 0);
    });
  }

  function chatItems(projectRoot) {
    const items = [];
    const seen = new Set();
    for (const scope of chatScopes(projectRoot)) {
      for (const selector of SEL.chatLink.split(',')) {
        U.qsa(selector.trim(), scope).forEach(node => {
          const item = node.closest('.project-chat-item') || node;
          if (seen.has(item)) return;
          seen.add(item);
          items.push(item);
        });
      }
    }
    return items;
  }

  function selectChatInProject(projectRoot, title) {
    if (!title) return 'SKIP';
    const want = String(title).trim();
    const mapped = mappedQwenId(want);
    const items = chatItems(projectRoot);
    const hit = items.find(item => {
      const ids = chatItemIds(item);
      const titleEl = item.querySelector
        ? item.querySelector('.project-chat-item-title, .chat-item-drag-link-content-tip-text')
        : null;
      const text = ((titleEl && titleEl.textContent) || item.textContent || '').trim();
      return idsMatch(ids, want)
        || (mapped && idsMatch(ids, mapped))
        || U.titlesMatch(text, want);
    });
    if (!hit) return 'NOT_FOUND';
    const titleEl = hit.querySelector
      ? hit.querySelector('.project-chat-item-title, .chat-item-drag-link-content-tip-text')
      : null;
    const link = titleEl
      || hit.closest('.project-chat-item')
      || hit.closest(SEL.chatLink)
      || hit.closest('a, [role="button"]')
      || hit;
    link.click();
    rememberChat(want, chatItemIds(hit)[0] || mapped);
    return 'OK';
  }

  function clickNewChatInProject(projectRoot) {
    const scopes = chatScopes(projectRoot);
    const pct = U.qs('.pct, .pcht, .pct__header, .pct__actions, .pcht__actions');
    if (pct && !scopes.includes(pct)) scopes.push(pct);
    for (const scope of scopes) {
      const buttons = U.qsa('[aria-label], button, [role="button"]', scope);
      const labeled = buttons.find(element => {
        if (!U.isVisible(element) || U.isDisabled(element) || isGlobalNewChat(element) || isComposerChrome(element)) {
          return false;
        }
        const label = (element.getAttribute('aria-label') || element.textContent || '').trim();
        return label.length <= 48 && /новый чат|new chat/i.test(label);
      });
      if (labeled) {
        labeled.click();
        return 'NEW';
      }
      const addUse = U.qsa('use', scope).find(node => {
        const href = node.getAttribute('href') || node.getAttribute('xlink:href') || '';
        if (!/#qwpcicon-add(?:Bold|1)?$/i.test(href)) return false;
        const btn = node.closest('button, [role="button"], [aria-label]') || node.parentElement;
        return btn && !isGlobalNewChat(btn) && !isComposerChrome(btn) && !U.isDisabled(btn);
      });
      if (addUse) {
        const btn = addUse.closest('button, [role="button"], [aria-label]') || addUse.parentElement;
        if (btn && !isGlobalNewChat(btn) && !isComposerChrome(btn) && !U.isDisabled(btn)) {
          btn.click();
          return 'NEW';
        }
      }
    }
    return 'NO_NEW';
  }

  function clickNewChat() {
    return 'NO_NEW';
  }

  function searchChats(query) {
    const input = U.firstMatch(SEL.search);
    if (!input || input.disabled) return 'NOT_FOUND';
    U.setNativeValue(input, query || '');
    return 'SEARCH';
  }

  function selectChatResult(title) {
    return selectChatInProject(U.qs('.pct__chats, .project-item, .pch'), title);
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

  async function waitCapturedChatId(prevId, timeoutMs) {
    const before = String(prevId || '');
    return U.waitFor(() => {
      const id = currentQwenChatId();
      if (looksLikeChatId(id) && id !== before) return id;
      return null;
    }, timeoutMs || 10000, 200);
  }

  async function openChatById(id) {
    const want = String(id || '').trim();
    if (!looksLikeChatId(want)) return 'SKIP';
    if (currentQwenChatId() === want && findInput()) return 'ALREADY';
    location.assign('https://chat.qwen.ai/c/' + encodeURIComponent(want));
    const input = await U.waitFor(() => {
      const got = currentQwenChatId();
      if (got && looksLikeChatId(got) && got !== want) return null;
      return findInput();
    }, 15000, 200);
    if (!input) {
      const body = String(document.body && document.body.innerText || '');
      if (/not found|404|doesn.?t exist|не найден/i.test(body)) return 'CHAT_GONE';
      return 'NO_INPUT';
    }
    const got = currentQwenChatId();
    if (got && looksLikeChatId(got) && got !== want) return 'CHAT_GONE';
    return 'OK';
  }

  async function startNewProjectChat(title, projectName) {
    const prev = currentQwenChatId();
    const created = clickNewChatInProject(projectRootFromName(projectName));
    if (created !== 'NEW') return created;
    const input = await U.waitFor(findInput, 8000, 150);
    const captured = await waitCapturedChatId(prev, 8000);
    rememberChat(title, captured || currentQwenChatId());
    return input ? 'NEW' : 'NO_INPUT';
  }

  async function ensureChat(title, projectName, chatId) {
    const want = String(title || '').trim();
    const id = looksLikeChatId(chatId) ? String(chatId).trim() : '';
    lastSiteChatId = id || want;
    if (id) {
      const opened = await openChatById(id);
      if (opened === 'OK' || opened === 'ALREADY') {
        rememberChat(want || id, currentQwenChatId() || id);
        return opened;
      }
      Core.activity('qwen', 'direct chat ' + id + ' -> ' + opened);
      if (opened !== 'CHAT_GONE' && opened !== 'NO_INPUT' && opened !== 'SKIP') {
        return opened;
      }
    }
    if (!projectName) {
      if (id) return 'CHAT_GONE';
      return findInput() ? 'SKIP' : 'NO_INPUT';
    }
    if (!id && want && selectChatInProject(projectRootFromName(projectName), want) === 'OK') {
      const input = await U.waitFor(findInput, 6000, 120);
      rememberChat(want, currentQwenChatId());
      return input ? 'OK' : 'NO_INPUT';
    }
    const started = await startNewProjectChat(want, projectName);
    if (started === 'NEW' || started === 'NO_INPUT') return started;
    if (id) return 'CHAT_GONE';
    if (findInput()) return 'PROJECT';
    return 'NO_PROJECT_CHAT';
  }

  function findProjectNode(name) {
    if (!name) return null;
    return U.qsa(SEL.projectItem).find(element => {
      const text = (element.textContent || '').trim();
      return text === name || text.includes(name);
    }) || null;
  }

  function selectProject(name) {
    if (!name) return 'SKIP';
    const hit = findProjectNode(name);
    if (!hit) return 'NOT_FOUND';
    (hit.closest(SEL.projectHeader) || hit.closest('.project-item') || hit).click();
    return 'OK';
  }

  function clickNewProject() {
    const hit = U.findByText(SEL.projectItem, /^(Новый проект|New project)$/i)
      || U.findByText('button, [role="button"], span, div, a', /^(Новый проект|New project)$/i);
    if (hit) {
      (hit.closest(SEL.projectHeader) || hit.closest('button, [role="button"], a') || hit).click();
      return 'NEW_PROJECT';
    }
    const labeled = U.qsa('button, [role="button"], [aria-label]').find(element => {
      if (!U.isVisible(element) || U.isDisabled(element) || isGlobalNewChat(element)) return false;
      const label = ((element.getAttribute('aria-label') || '') + ' ' + (element.textContent || '')).trim();
      return /новый проект|new project/i.test(label) && label.length <= 48;
    });
    if (labeled) {
      labeled.click();
      return 'NEW_PROJECT';
    }
    const addUse = U.qsa('use').find(node => {
      const href = node.getAttribute('href') || node.getAttribute('xlink:href') || '';
      if (!/#qwpcicon-add(?:Bold|1)?$/i.test(href)) return false;
      const btn = node.closest('button, [role="button"], [aria-label]') || node.parentElement;
      return btn && !isGlobalNewChat(btn) && !U.isDisabled(btn)
        && btn.closest('.project-item, .project-list, [class*="project"]');
    });
    if (addUse) {
      const btn = addUse.closest('button, [role="button"], [aria-label]') || addUse.parentElement;
      if (btn) {
        btn.click();
        return 'NEW_PROJECT';
      }
    }
    return 'NOT_FOUND';
  }

  function visibleDialogInput() {
    const dialog = U.firstMatch(SEL.dialog);
    if (!dialog) return null;
    return U.qsa('input:not([type="file"]):not([type="hidden"]), textarea', dialog).find(element => {
      return U.isVisible(element) && !element.disabled;
    }) || null;
  }

  function clickCreateProjectButton() {
    const black = U.qs('.qwen-chat-v2-btn-black');
    if (black && U.isVisible(black) && !U.isDisabled(black)) {
      black.click();
      return true;
    }
    return clickDialogButton(/Создать проект/i)
      || clickDialogButton(/^(Создать|Create|ОК|OK)$/i);
  }

  function clickDialogButton(matcher) {
    const dialog = U.firstMatch(SEL.dialog) || document;
    const hit = U.qsa('button, [role="button"]', dialog).find(element => {
      return U.isVisible(element) && !U.isDisabled(element)
        && matcher.test((element.textContent || '').trim());
    });
    if (!hit) return false;
    hit.click();
    return true;
  }

  async function ensureProject(name) {
    if (!name) return 'SKIP';
    if (selectProject(name) === 'OK') return 'ALREADY';
    const created = clickNewProject();
    if (created === 'NOT_FOUND') {
      Core.activity('qwen', 'new project control not found');
      return 'NOT_FOUND';
    }
    const input = await U.waitFor(visibleDialogInput, 2500, 100);
    if (input) {
      U.setNativeValue(input, name);
      await U.sleep(150);
    }
    clickCreateProjectButton();
    await U.sleep(500);
    const selected = await U.waitFor(() => {
      return selectProject(name) === 'OK' ? 'OK' : null;
    }, 2500, 150);
    return selected ? 'CREATED' : 'NEW_PROJECT';
  }

  async function deleteProject(name) {
    if (!name) return 'SKIP';
    const hit = findProjectNode(name);
    if (!hit) return 'NOT_FOUND';
    const row = hit.closest('.project-item') || hit.closest(SEL.projectHeader) || hit.parentElement;
    row.click();
    await U.sleep(200);
    const more = U.qsa('button, [role="button"], [aria-label]', row).find(element => {
      const label = (element.getAttribute('aria-label') || element.textContent || '').trim();
      return /ещё|меню|more|more actions|удалить|delete/i.test(label);
    });
    if (more) more.click();
    else {
      try {
        row.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, button: 2 }));
      } catch (error) {
        return 'NO_DELETE';
      }
    }
    await U.sleep(250);
    const item = U.findByText('button, [role="menuitem"], li, span, div', /^(Удалить|Delete)$/i);
    if (!item) return 'NO_DELETE';
    (item.closest('button, [role="menuitem"], a') || item).click();
    await U.sleep(250);
    clickDialogButton(/^(Удалить|Delete|ОК|OK|Подтвердить|Confirm)$/i);
    await U.sleep(400);
    return findProjectNode(name) ? 'DELETE_CLICKED' : 'DELETED';
  }

  function openAdvancedSettings() {
    const hit = U.findByText(
      SEL.advancedSettings,
      /^(Расширенные настройки|Advanced settings)$/i
    );
    if (!hit) return 'NOT_FOUND';
    hit.click();
    return 'OK';
  }

  function openProjectInstructions() {
    const button = U.firstMatch(SEL.projectInstructionsButton);
    if (!button || U.isDisabled(button)) return 'NOT_FOUND';
    button.click();
    return 'OK';
  }

  function fillProjectInstruction(text) {
    const input = U.firstMatch(SEL.projectInstruction);
    if (!input) return 'NO_INPUT';
    U.setNativeValue(input, text || '');
    return 'OK';
  }

  function clickAddFiles() {
    const label = U.findByText(SEL.addFiles, /^(Добавить файлы|Add files)$/i);
    if (label) {
      (label.closest('button, [role="button"], a') || label).click();
      return 'OK';
    }
    const input = U.firstMatch(SEL.filesUpload);
    if (!input) return 'NOT_FOUND';
    input.click();
    return 'OK';
  }

  async function prepare(job) {
    lastSiteChatId = looksLikeChatId(job && job.chatId)
      ? String(job.chatId).trim()
      : String((job && job.chatTitle) || '').trim();
    const projectName = String(job.qwenProjectName || job.projectName || '').trim();
    if (projectName) {
      const project = await ensureProject(projectName);
      Core.activity('qwen', 'ensure project ' + projectName + ' -> ' + project);
      if (project === 'NOT_FOUND') {
        Core.activity('qwen', 'project UI missing, send in current chat');
      } else {
        await U.sleep(300);
        const chat = await ensureChat(job.chatTitle, projectName, job.chatId);
        Core.activity('qwen', 'ensure chat "' + (job.chatTitle || job.chatId || '') + '" -> ' + chat);
        if (chat === 'CHAT_GONE' || chat === 'NO_PROJECT_CHAT' || chat === 'NO_INPUT') {
          throw new Error(chat);
        }
      }
    } else {
      Core.activity('qwen', 'no project name, send in current chat');
    }
    const input = await U.waitFor(findInput, 8000, 200);
    if (!input) {
      throw new Error('NO_INPUT');
    }
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
    const nodes = U.qsa(
      '[class*="user-message"], [class*="UserMessage"], [data-role="user"], .chat-message-user, .chat-question, [class*="question"]'
    );
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
    const messages = U.qsa('.chat-response-message');
    let best = '';
    for (const msg of messages) {
      try {
        if (!(userNode.compareDocumentPosition(msg) & Node.DOCUMENT_POSITION_FOLLOWING)) continue;
      } catch (_) { continue; }
      const answer = msg.querySelector(
        '.response-message-content.phase-answer, .response-message-content, .qwen-markdown'
      );
      if (!answer || isThinkNode(answer)) continue;
      const t = String((answer.querySelector('.qwen-markdown') || answer).innerText || '').trim();
      if (t && t.length >= best.length) best = t;
    }
    return best || readAssistant();
  }

  function homeUrl() {
    return 'https://chat.qwen.ai/';
  }

  const adapter = {
    id: 'qwen',
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
    searchChats,
    selectChatResult,
    ensureChat,
    openChatById,
    currentChatId: currentQwenChatId,
    selectProject,
    clickNewProject,
    ensureProject,
    deleteProject,
    openAdvancedSettings,
    openProjectInstructions,
    fillProjectInstruction,
    clickAddFiles,
  };
  global.AIPilotQwen = adapter;
  if (typeof window !== 'undefined') window.AIPilotQwen = adapter;
})(typeof unsafeWindow !== 'undefined' ? unsafeWindow : window);
