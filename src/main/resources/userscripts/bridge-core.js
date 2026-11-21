// Shared AIPilot userscript runtime. Provider-specific DOM code lives in
// qwen-ui.js and deepseek-ui.js.
(function (global) {
  'use strict';

  const VERSION = '1.9.30';
  const CONFIG = global.AIPILOT_CONFIG && typeof global.AIPILOT_CONFIG === 'object'
    ? global.AIPILOT_CONFIG
    : {};
  // Live /assets/js/aipilot/bridge-core.js keeps these names; typeof does not throw.
  // Builders replace them with JSON string literals before embedding.
  const BAKED_TOKEN = typeof __BRIDGE_TOKEN_JSON__ === 'undefined' ? '' : __BRIDGE_TOKEN_JSON__;
  const BAKED_API_BASE = typeof __API_BASE_JSON__ === 'undefined' ? '' : __API_BASE_JSON__;
  const TOKEN = CONFIG.token != null && String(CONFIG.token) !== ''
    ? String(CONFIG.token)
    : String(BAKED_TOKEN || '');
  const API_BASE = CONFIG.apiBase != null && String(CONFIG.apiBase) !== ''
    ? String(CONFIG.apiBase).replace(/\/+$/, '')
    : String(BAKED_API_BASE || '').replace(/\/+$/, '');
  const BASE = API_BASE + '/api/aipilot/bridge';
  const API_ORIGIN = String(BASE).replace(/\/api\/aipilot\/bridge\/?$/, '');
  const TOKEN_HEADER = 'X-AIPilot-Token';
  const TOKEN_KEY = 'aipilot.bridgeToken';
  const AUTH_KEY = 'aipilot.authOk';
  const USER_KEY = 'aipilot.userName';
  const AUTH_CHANNEL = 'aipilot-auth';
  const LOCK_TTL_MS = 12000;
  const EXCLUSIVE_TTL_MS = 20000;
  const GENERATION_TIMEOUT_MS = 280000;
  /** Hard cap per bridge job: then finish + force home navigation. */
  const JOB_HARD_TIMEOUT_MS = 5 * 60 * 1000;
  const PAGE_CONSOLE_MAX_LINES = 200;
  const POLL_BACKOFF_MS = [2000, 5000, 15000];
  let pageConsole = null;
  let tabControl = null;
  let authInButton = null;
  let authOutButton = null;
  let accountLabel = null;
  const authListeners = [];

  // --- storage ---

  function storageGet(key, fallback) {
    try {
      if (typeof GM_getValue === 'function') {
        const value = GM_getValue(key, fallback);
        if (value != null && value !== '') return value;
      }
    } catch (error) {
      // Tampermonkey storage is optional.
    }
    try {
      const value = localStorage.getItem(key);
      if (value != null && value !== '') return value;
    } catch (error) {
      // localStorage may be blocked.
    }
    return fallback;
  }

  function storageSet(key, value) {
    try {
      if (typeof GM_setValue === 'function') GM_setValue(key, String(value == null ? '' : value));
    } catch (error) {
      // Ignore GM storage failures.
    }
    try {
      localStorage.setItem(key, String(value == null ? '' : value));
    } catch (error) {
      // Ignore localStorage failures.
    }
  }

  function currentToken() {
    if (TOKEN) return TOKEN;
    return storageGet(TOKEN_KEY, '') || '';
  }

  function ensureToken() {
    if (TOKEN) {
      storageSet(TOKEN_KEY, TOKEN);
      return TOKEN;
    }
    let token = storageGet(TOKEN_KEY, '');
    if (!token) {
      token = 'us-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
      storageSet(TOKEN_KEY, token);
    }
    return token;
  }

  function isSitePage() {
    try {
      return Boolean(API_ORIGIN) && new URL(API_ORIGIN).host === location.host;
    } catch (error) {
      return false;
    }
  }

  function signedIn() {
    return storageGet(AUTH_KEY, '') === '1' && Boolean(currentToken());
  }

  function accountName() {
    return storageGet(USER_KEY, '') || '';
  }

  // --- errors ---

  function jsonSnippet(value) {
    if (value == null) return '';
    if (typeof value === 'string') {
      const text = value.replace(/\s+/g, ' ').trim();
      if (text === '[object Object]') return '';
      return text.length > 240 ? text.slice(0, 240) + '…' : text;
    }
    try {
      const text = JSON.stringify(value);
      if (!text || text === '{}' || text === 'null') return '';
      return text.length > 240 ? text.slice(0, 240) + '…' : text;
    } catch (error) {
      return '';
    }
  }

  function usableText(value) {
    const text = value == null ? '' : String(value).trim();
    if (!text || text === '[object Object]' || text === '[object Error]' || text === '[object Event]') {
      return '';
    }
    return text;
  }

  function apiErrorFromBody(body) {
    if (typeof body !== 'string' || !body.trim()) return '';
    try {
      const payload = JSON.parse(body);
      if (!payload || typeof payload !== 'object') return '';
      const err = payload.error;
      if (typeof err === 'string') return usableText(err);
      if (err && typeof err === 'object') {
        return usableText(err.message) || usableText(err.code) || '';
      }
      return usableText(payload.message) || '';
    } catch (error) {
      return '';
    }
  }

  function describeError(error) {
    if (error == null) return '';
    if (typeof error === 'string') return usableText(error);
    if (typeof error === 'number' || typeof error === 'boolean') return String(error);
    if (typeof error !== 'object') return usableText(error);
    const bits = [];
    if (typeof error.status === 'number') bits.push('HTTP ' + error.status);
    if (usableText(error.statusText)) bits.push(usableText(error.statusText));
    if (typeof error.readyState === 'number' && !error.status) bits.push('readyState ' + error.readyState);
    if (error.error && error.error !== error) {
      const nested = typeof error.error === 'string' ? usableText(error.error) : describeError(error.error);
      if (nested) bits.push(nested);
    }
    const body = error.responseText || error.response || error.body;
    if (typeof body === 'string' && usableText(body)) {
      const apiErr = apiErrorFromBody(body);
      bits.push(apiErr || jsonSnippet(body));
    } else if (body && typeof body === 'object') {
      const dumped = jsonSnippet(body);
      if (dumped) bits.push(dumped);
    }
    const message = usableText(error.message);
    if (message) bits.push(message);
    else if (error.message && typeof error.message === 'object') {
      const dumped = jsonSnippet(error.message);
      if (dumped) bits.push(dumped);
    }
    if (error.name && error.name !== 'Error' && error.name !== 'Object') bits.push(String(error.name));
    if (error.url) bits.push(String(error.url));
    if (error.type && error.type !== 'error') bits.push(String(error.type));
    if (bits.length) return bits.join(' · ');
    try {
      const keys = Object.keys(error);
      if (keys.length) {
        const dump = {};
        keys.slice(0, 8).forEach(key => {
          const value = error[key];
          dump[key] = value != null && typeof value === 'object' ? typeof value : value;
        });
        return jsonSnippet(dump);
      }
    } catch (ignored) {
      // Host objects may throw on Object.keys.
    }
    return jsonSnippet(error);
  }

  function formatError(error, extra) {
    const parts = [];
    if (extra) {
      const extraText = usableText(extra) || (typeof extra === 'object' ? jsonSnippet(extra) : '');
      if (extraText) parts.push(extraText);
    }
    const described = describeError(error);
    if (described) parts.push(described);
    const joined = parts.filter(Boolean).join(' · ').replace(/\[object \w+\]/g, '').replace(new RegExp('\\s*·\\s*', 'g'), ' · ').replace(/\s+/g, ' ').trim();
    return joined && joined !== '·' ? joined : 'unknown error';
  }

  function isAuthFailure(error) {
    const text = formatError(error);
    return /HTTP 401|HTTP 403|нужен вход|unauthorized|forbidden/i.test(text);
  }

  // --- http ---

  function parseUser(payload) {
    if (!payload || typeof payload !== 'object') return '';
    const root = payload.data && typeof payload.data === 'object' ? payload.data : payload;
    const user = root.user && typeof root.user === 'object' ? root.user : root;
    return String(user.name || user.userName || user.username || user.login
      || user.email || root.displayName || root.login || '').trim();
  }

  function parseData(response) {
    let payload = {};
    const raw = response == null
      ? ''
      : (response.responseText != null ? response.responseText : response.response);
    const text = raw == null ? '' : String(raw).trim();
    if (!text) return {};
    try {
      payload = JSON.parse(text);
    } catch (error) {
      throw new Error('Non-JSON ' + (response && response.status ? 'HTTP ' + response.status + ' ' : '')
        + jsonSnippet(text));
    }
    if (payload && payload.ok === false) {
      const err = payload.error;
      let msg = '';
      if (typeof err === 'string') msg = usableText(err);
      else if (err && typeof err === 'object') {
        msg = usableText(err.message) || usableText(err.code) || jsonSnippet(err);
      }
      if (!msg) msg = usableText(payload.message) || usableText(payload.detail) || jsonSnippet(payload);
      throw new Error(msg || 'API error');
    }
    if (payload && payload.ok === true && payload.data !== undefined) return payload.data;
    if (payload && payload.job && typeof payload.job === 'object') return payload.job;
    return payload;
  }

  function normalizeJob(raw) {
    if (!raw || typeof raw !== 'object') return null;
    const id = raw.id || raw.job_id || raw.jobId;
    const prompt = raw.prompt != null ? raw.prompt : raw.text;
    if (!id) return null;
    return {
      id: String(id),
      prompt: prompt == null ? '' : String(prompt),
      action: String(raw.action || raw.op || 'prompt'),
      projectName: raw.projectName || raw.project_name || '',
      qwenProjectName: raw.qwenProjectName || raw.qwen_project_name || '',
      chatTitle: raw.chatTitle || raw.chat_title || '',
      chatId: raw.chatId || raw.chat_id || '',
      chatHeader: raw.chatHeader || raw.chat_header || '',
      projectId: raw.projectId || raw.project_id || '',
      rotateChat: raw.rotateChat === true || raw.rotate_chat === true,
      expertMode: raw.expertMode !== false && raw.expert_mode !== false,
    };
  }

  function gmFunction() {
    if (typeof GM_xmlhttpRequest === 'function') return GM_xmlhttpRequest;
    if (typeof GM !== 'undefined' && typeof GM.xmlHttpRequest === 'function') {
      return GM.xmlHttpRequest.bind(GM);
    }
    return null;
  }

  function request(method, url, body, timeoutMs, options) {
    return new Promise((resolve, reject) => {
      const cookieAuth = Boolean(options && options.cookieAuth);
      const omitToken = Boolean(options && options.omitToken);
      const headers = { Accept: 'application/json' };
      if (body != null) headers['Content-Type'] = 'application/json';
      const token = currentToken();
      if (token && !omitToken) headers[TOKEN_HEADER] = token;
      const fail = error => reject(new Error(formatError(error, method + ' ' + url)));
      const sameOrigin = (() => {
        try {
          return new URL(url, location.href).origin === location.origin;
        } catch (error) {
          return false;
        }
      })();
      if (cookieAuth && sameOrigin) {
        const pageFetch = pageFetcher();
        if (pageFetch) {
          const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
          const timer = ctrl ? setTimeout(() => ctrl.abort(), timeoutMs || 10000) : null;
          pageFetch(url, {
            method,
            headers,
            body: body == null ? undefined : JSON.stringify(body),
            credentials: 'include',
            signal: ctrl ? ctrl.signal : undefined,
          }).then(async res => {
            const responseText = await res.text();
            resolve({ status: res.status, responseText });
          }).catch(error => {
            fail(error && error.name === 'AbortError' ? 'timeout' : error);
          }).finally(() => {
            if (timer) clearTimeout(timer);
          });
          return;
        }
      }
      if (!cookieAuth && typeof global.__AIPILOT_BRIDGE_HTTP__ === 'function') {
        global.__AIPILOT_BRIDGE_HTTP__({
          method: method,
          url: url,
          headers: headers,
          body: body,
          timeoutMs: timeoutMs || 10000,
        }).then(resolve).catch(fail);
        return;
      }
      const xhr = gmFunction();
      if (xhr) {
        xhr({
          method,
          url,
          headers,
          data: body == null ? undefined : JSON.stringify(body),
          timeout: timeoutMs || 10000,
          anonymous: false,
          withCredentials: cookieAuth,
          responseType: 'text',
          onload: response => resolve(response || {}),
          onerror: response => {
            if (response && (response.status || response.responseText != null || response.response != null)) {
              resolve(response);
              return;
            }
            fail(response);
          },
          ontimeout: () => fail('timeout'),
          onabort: () => fail('aborted'),
        });
        return;
      }
      if (typeof fetch !== 'function' && !pageFetcher()) {
        fail('GM_xmlhttpRequest missing');
        return;
      }
      const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
      const timer = ctrl ? setTimeout(() => ctrl.abort(), timeoutMs || 10000) : null;
      const fallbackFetch = pageFetcher() || fetch;
      fallbackFetch(url, {
        method,
        headers,
        body: body == null ? undefined : JSON.stringify(body),
        credentials: cookieAuth ? 'include' : 'omit',
        signal: ctrl ? ctrl.signal : undefined,
      }).then(async res => {
        const responseText = await res.text();
        resolve({ status: res.status, responseText });
      }).catch(error => {
        fail(error && error.name === 'AbortError' ? 'timeout' : error);
      }).finally(() => {
        if (timer) clearTimeout(timer);
      });
    });
  }

  async function requestOk(method, url, body, timeoutMs, options) {
    const response = await request(method, url, body, timeoutMs, options);
    const status = response && response.status != null ? response.status : 0;
    if (!response || status >= 400 || status === 0) {
      if (status === 401 || status === 403) {
        if (!(options && options.preserveAuth)) {
          storageSet(AUTH_KEY, '0');
          updateAuthButton();
        }
        throw new Error(formatError(response, 'HTTP ' + status + ' — нужен вход на сайт'));
      }
      throw new Error(formatError(response, (status ? 'HTTP ' + status : 'no response') + ' ' + method + ' ' + url));
    }
    return response;
  }

  function activity(provider, message) {
    log('info', message);
    const body = {
      provider,
      message: formatError(message || ''),
    };
    requestOk('POST', BASE + '/activity', body).catch(error => {
      log('warn', 'activity fail ' + formatError(error));
      requestOk('POST', BASE + '/activity', body).catch(retryError => {
        log('warn', 'activity retry fail ' + formatError(retryError));
      });
    });
  }

  // --- auth ---

  function onAuthenticated(fn) {
    if (typeof fn === 'function') authListeners.push(fn);
  }

  function notifyAuthenticated() {
    authListeners.slice().forEach(fn => {
      try {
        fn();
      } catch (error) {
        // Listener failures must not break login.
      }
    });
  }

  function openSiteLogin() {
    const url = API_ORIGIN + '/login?next=' + encodeURIComponent('/ai');
    log('info', 'open login ' + url);
    let opened = null;
    try {
      opened = pageWindow().open(url, 'aipilot-login');
    } catch (error) {
      opened = null;
    }
    if (!opened && typeof GM_openInTab === 'function') {
      GM_openInTab(url, { active: true, insert: true });
    }
  }

  function openSiteLogout() {
    const url = API_ORIGIN + '/logout';
    if (typeof GM_openInTab === 'function') {
      GM_openInTab(url, { active: true, insert: true });
      return;
    }
    try {
      pageWindow().open(url, '_blank');
    } catch (error) {
      window.open(url, '_blank');
    }
  }

  async function logoutAccount() {
    try {
      await request('POST', API_ORIGIN + '/api/auth/logout', {}, 8000, { cookieAuth: true });
    } catch (error) {
      log('warn', 'logout api: ' + formatError(error));
    }
    storageSet(AUTH_KEY, '0');
    storageSet(USER_KEY, '');
    updateAuthButton();
    log('ok', 'выход выполнен');
    if (pageConsole) pageConsole.setState('need login');
    if (isSitePage()) openSiteLogout();
  }

  function updateAuthButton() {
    const name = accountName();
    const inSystem = signedIn();
    const label = inSystem ? (name || 'аккаунт') : 'гость';
    if (accountLabel) {
      accountLabel.textContent = label;
      accountLabel.title = label;
      accountLabel.style.display = '';
    }
    if (authInButton) {
      authInButton.style.display = inSystem ? 'none' : '';
    }
    if (authOutButton) {
      authOutButton.style.display = inSystem ? '' : 'none';
    }
  }

  async function bindTokenFromCookie() {
    const token = ensureToken();
    const me = await requestOk('GET', API_ORIGIN + '/api/auth/me', null, 12000, {
      cookieAuth: true,
      omitToken: true,
    });
    const name = parseUser(parseData(me));
    if (!name) throw new Error('me without user');
    storageSet(USER_KEY, name);
    const bound = await requestOk('POST', BASE + '/token', { token }, 12000, { cookieAuth: true });
    const data = parseData(bound);
    const canonical = data && data.token ? String(data.token) : token;
    storageSet(TOKEN_KEY, canonical);
    storageSet(AUTH_KEY, '1');
    updateAuthButton();
    notifyAuthenticated();
    publishAuth(name);
    return name;
  }

  async function tryBindFromCookie(quiet) {
    if (!isSitePage()) return false;
    try {
      const name = await bindTokenFromCookie();
      log('ok', 'token bound · ' + name);
      if (pageConsole) pageConsole.setState('signed in');
      return true;
    } catch (error) {
      if (!quiet) log('warn', 'cookie bind: ' + formatError(error));
      return false;
    }
  }

  function publishAuth(name) {
    const payload = {
      channel: AUTH_CHANNEL,
      token: currentToken(),
      name: name || accountName() || '',
    };
    const targets = [];
    try {
      const page = pageWindow();
      if (page && page.opener && !page.opener.closed) targets.push(page.opener);
    } catch (error) {
      // Isolated world may not read opener.
    }
    try {
      if (window.opener && !window.opener.closed && targets.indexOf(window.opener) < 0) {
        targets.push(window.opener);
      }
    } catch (error) {
      // opener may be cross-origin or closed.
    }
    targets.forEach(target => {
      try {
        target.postMessage(payload, '*');
      } catch (error) {
        // Ignore blocked postMessage.
      }
    });
  }

  function applyRemoteAuth(payload) {
    if (!payload || payload.channel !== AUTH_CHANNEL) return false;
    if (payload.token) storageSet(TOKEN_KEY, payload.token);
    if (payload.name) storageSet(USER_KEY, payload.name);
    if (payload.token || payload.name) {
      storageSet(AUTH_KEY, '1');
      updateAuthButton();
      notifyAuthenticated();
      if (payload.name) log('ok', 'вход: ' + payload.name);
      return true;
    }
    return false;
  }

  function listenAuthChannel() {
    if (listenAuthChannel.bound) return;
    listenAuthChannel.bound = true;
    const onMessage = event => {
      const allowed = event.origin === API_ORIGIN || event.origin === location.origin;
      if (!allowed) return;
      applyRemoteAuth(event.data);
    };
    window.addEventListener('message', onMessage);
    try {
      const page = pageWindow();
      if (page && page !== window && typeof page.addEventListener === 'function') {
        page.addEventListener('message', onMessage);
      }
    } catch (error) {
      // Isolated world cannot always listen on the page window.
    }
  }

  async function refreshAccount(force) {
    if (!force && accountName() && signedIn()) {
      updateAuthButton();
      return true;
    }
    try {
      const response = await requestOk('GET', API_ORIGIN + '/api/auth/me', null, 10000, {
        cookieAuth: isSitePage(),
        omitToken: isSitePage(),
        preserveAuth: !isSitePage(),
      });
      const name = parseUser(parseData(response));
      if (!name) throw new Error('me without user');
      storageSet(USER_KEY, name);
      storageSet(AUTH_KEY, '1');
      updateAuthButton();
      notifyAuthenticated();
      return true;
    } catch (error) {
      if (!isSitePage() && signedIn() && accountName()) {
        updateAuthButton();
        return true;
      }
      if (isSitePage()) storageSet(AUTH_KEY, '0');
      if (force && !isAuthFailure(error) && isSitePage()) {
        log('warn', 'account ' + formatError(error));
      }
      updateAuthButton();
      return false;
    }
  }

  async function tryResumeAfterLogin() {
    if (signedIn() && accountName()) {
      updateAuthButton();
      notifyAuthenticated();
      return true;
    }
    if (isSitePage() && await tryBindFromCookie(true)) {
      return true;
    }
    return refreshAccount(true);
  }

  function watchLoginReturn() {
    if (watchLoginReturn.watching) return;
    watchLoginReturn.watching = true;
    const started = Date.now();
    const cap = 180000;
    let done = false;
    let waitingLogged = false;
    let inFlight = false;
    async function tick() {
      if (done || inFlight) return;
      inFlight = true;
      try {
        const ok = await tryResumeAfterLogin();
        if (ok) {
          done = true;
          watchLoginReturn.watching = false;
          if (accountName()) log('ok', 'вход: ' + accountName());
          return;
        }
        if (!waitingLogged && !isSitePage()) {
          waitingLogged = true;
          log('info', 'ожидание входа на сайте…');
        }
      } finally {
        inFlight = false;
      }
      if (!done && Date.now() - started >= cap) {
        done = true;
        watchLoginReturn.watching = false;
        if (!isSitePage()) log('warn', 'вход не получен — обновите чат после входа на сайте');
      }
    }
    const onFocus = () => { tick(); };
    const onVisible = () => {
      if (document.visibilityState === 'visible') tick();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisible);
    const poll = window.setInterval(() => {
      if (done) {
        window.clearInterval(poll);
        return;
      }
      tick();
    }, 1500);
    window.setTimeout(() => {
      window.clearInterval(poll);
      if (!done) tick();
    }, cap);
    tick();
  }

  // --- console ---

  let consoleHostEl = null;

  function pinConsoleHost(host) {
    if (!host) return;
    consoleHostEl = host;
    const root = document.documentElement;
    if (root && host.parentNode !== root) {
      root.appendChild(host);
    }
    if (pinConsoleHost.watching) return;
    pinConsoleHost.watching = true;
    try {
      const observer = new MutationObserver(() => {
        if (!consoleHostEl) return;
        const rootNode = document.documentElement;
        if (!rootNode) return;
        if (!document.getElementById('aipilot-console-host')
            || consoleHostEl.parentNode !== rootNode) {
          rootNode.appendChild(consoleHostEl);
        }
      });
      observer.observe(document.documentElement, { childList: true, subtree: true });
    } catch (error) {
      pinConsoleHost.watching = false;
    }
  }

  function createPageConsole(provider) {
    if (isSitePage()) return null;
    const existing = document.getElementById('aipilot-console-host');
    if (existing && existing.__aipilotConsole) {
      pageConsole = existing.__aipilotConsole;
      pinConsoleHost(existing);
      return pageConsole;
    }

    const host = document.createElement('div');
    host.id = 'aipilot-console-host';
    const shadow = host.attachShadow({ mode: 'open' });
    const style = document.createElement('style');
    style.textContent = [
      ':host{all:initial;position:fixed;top:0;left:0;right:0;z-index:2147483647;',
      'font-family:Cascadia Mono,Consolas,monospace;color:#e6e4df}',
      '.box{height:150px;width:100%;background:#0c0e12;border-bottom:1px solid #a8976a;',
      'box-sizing:border-box;display:flex;flex-direction:column;box-shadow:0 2px 8px #0008}',
      '.box.collapsed{height:30px}',
      '.head{display:flex;flex-direction:column;background:#161a21}',
      '.row{display:flex;align-items:center;gap:8px;height:30px;padding:0 8px;',
      'box-sizing:border-box}',
      '.title{font:600 12px Segoe UI,sans-serif;color:#d8c58f;white-space:nowrap}',
      '.state{font:11px Segoe UI,sans-serif;color:#8a8780;flex:1;overflow:hidden;',
      'text-overflow:ellipsis;white-space:nowrap}',
      '.account{font:600 11px Segoe UI,sans-serif;color:#d8c58f;max-width:140px;',
      'overflow:hidden;text-overflow:ellipsis;white-space:nowrap}',
      'button{border:0;border-radius:6px;background:#1c2129;color:#e6e4df;',
      'font:600 11px Segoe UI,sans-serif;padding:4px 10px;min-height:22px;cursor:pointer}',
      'button:hover{background:#232830}',
      'button:active{background:#191e27}',
      'button.primary{background:#a8976a;color:#14120e}',
      'button.primary:hover{background:#8f7f55}',
      'button.danger{background:#3a2222;color:#ff8a8a}',
      'button.danger:hover{background:#4a2a2a}',
      '.dot{width:9px;height:9px;border-radius:50%;background:#5c616a;flex-shrink:0}',
      '.dot.off{background:#4a4e56}',
      '.dot.ok{background:#86d993;animation:aipulse 2.2s ease-out infinite}',
      '.dot.run{background:#a8976a;animation:aipulse 1.1s ease-out infinite}',
      '.dot.exclusive{background:#d8c58f;animation:aipulse 2.2s ease-out infinite}',
      '.dot.away{background:#e8c26a}',
      '@keyframes aipulse{0%{box-shadow:0 0 0 0 rgba(168,151,106,.55)}',
      '70%{box-shadow:0 0 0 8px rgba(168,151,106,0)}100%{box-shadow:0 0 0 0 rgba(168,151,106,0)}}',
      '.jobs{padding:4px 10px;font:11px/1.35 Segoe UI,sans-serif;color:#d8c58f;',
      'border-bottom:1px solid #2a2e36;background:#12151a;max-height:72px;overflow:auto}',
      '.jobs b{color:#a8976a;font-weight:600}',
      '.lines{margin:0;padding:6px 10px;overflow:auto;white-space:pre-wrap;',
      'word-break:break-word;font:11px/1.35 Cascadia Mono,Consolas,monospace;flex:1}',
      '.collapsed .lines,.collapsed .jobs,.collapsed .row-actions{display:none}',
      '.info{color:#d6d9df}.ok{color:#86d993}.warn{color:#e8c26a}.error{color:#ff8a8a}',
      '.lines,.jobs{scrollbar-width:thin;scrollbar-color:#3a414c #12151a}',
      '.lines::-webkit-scrollbar,.jobs::-webkit-scrollbar{width:8px;height:8px}',
      '.lines::-webkit-scrollbar-track,.jobs::-webkit-scrollbar-track{background:#12151a;',
      'border-radius:6px}',
      '.lines::-webkit-scrollbar-thumb,.jobs::-webkit-scrollbar-thumb{background:#3a414c;',
      'border-radius:6px}',
      '.lines::-webkit-scrollbar-thumb:hover,.jobs::-webkit-scrollbar-thumb:hover{background:#4a5260}',
    ].join('');
    const box = document.createElement('section');
    box.className = 'box';
    const head = document.createElement('header');
    head.className = 'head';
    const titleRow = document.createElement('div');
    titleRow.className = 'row';
    const title = document.createElement('span');
    title.className = 'title';
    title.textContent = 'AIPilot · ' + provider;
    const workDot = document.createElement('span');
    workDot.className = 'dot off';
    workDot.title = 'индикатор работы';
    const state = document.createElement('span');
    state.className = 'state';
    state.textContent = 'starting';
    const account = document.createElement('span');
    account.className = 'account';
    account.textContent = 'гость';
    accountLabel = account;
    const authIn = document.createElement('button');
    authIn.type = 'button';
    authIn.className = 'primary';
    authIn.textContent = 'Вход';
    authInButton = authIn;
    const authOut = document.createElement('button');
    authOut.type = 'button';
    authOut.className = 'danger';
    authOut.textContent = 'Выход';
    authOutButton = authOut;
    const exclusiveBtn = document.createElement('button');
    exclusiveBtn.type = 'button';
    exclusiveBtn.textContent = 'Главная сессия';
    exclusiveBtn.title = 'Зажать: только эта вкладка поллит jobs';
    exclusiveBtn.setAttribute('aria-pressed', 'false');
    const powerBtn = document.createElement('button');
    powerBtn.type = 'button';
    powerBtn.className = 'danger';
    powerBtn.textContent = 'Выкл';
    powerBtn.title = 'Выключить скрипт на этой странице';
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.textContent = 'Свернуть';
    titleRow.append(title, workDot, state, exclusiveBtn, powerBtn, toggle);
    const actionRow = document.createElement('div');
    actionRow.className = 'row row-actions';
    const copy = document.createElement('button');
    copy.type = 'button';
    copy.textContent = 'Копировать';
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.textContent = 'Очистить';
    actionRow.append(copy, clear, authIn, account, authOut);
    if (provider === 'site') {
      workDot.style.display = 'none';
      exclusiveBtn.style.display = 'none';
      powerBtn.style.display = 'none';
    }
    head.append(titleRow, actionRow);
    const jobs = document.createElement('div');
    jobs.className = 'jobs';
    jobs.textContent = 'очередь: загрузка…';
    const lines = document.createElement('pre');
    lines.className = 'lines';
    box.append(head, jobs, lines);
    shadow.append(style, box);

    const spacer = document.createElement('div');
    spacer.id = 'aipilot-console-spacer';
    spacer.style.cssText = 'display:block;width:100%;height:150px;min-height:150px;'
      + 'margin:0;padding:0;border:0;pointer-events:none;';
    const mount = document.body || document.documentElement;
    if (mount) {
      if (mount.firstChild) mount.insertBefore(spacer, mount.firstChild);
      else mount.appendChild(spacer);
    }
    (document.documentElement || mount).appendChild(host);
    pinConsoleHost(host);

    const storageKey = 'aipilot.console.collapsed.' + provider;
    const entries = [];
    function applyCollapsed(collapsed) {
      box.classList.toggle('collapsed', collapsed);
      spacer.style.height = collapsed ? '30px' : '150px';
      spacer.style.minHeight = collapsed ? '30px' : '150px';
      toggle.textContent = collapsed ? 'Развернуть' : 'Свернуть';
      try {
        localStorage.setItem(storageKey, collapsed ? '1' : '0');
      } catch (error) {
        // The console still works when storage is disabled.
      }
    }
    let collapsed = false;
    try {
      collapsed = localStorage.getItem(storageKey) === '1';
    } catch (error) {
      collapsed = false;
    }
    applyCollapsed(collapsed);

    toggle.addEventListener('click', () => {
      applyCollapsed(!box.classList.contains('collapsed'));
    });
    clear.addEventListener('click', () => {
      entries.length = 0;
      lines.replaceChildren();
    });
    copy.addEventListener('click', async () => {
      const text = entries.map(entry => entry.text).join('\n');
      try {
        await navigator.clipboard.writeText(text);
        state.textContent = 'log copied';
      } catch (error) {
        state.textContent = 'copy unavailable';
      }
    });
    authIn.addEventListener('click', () => {
      openSiteLogin();
      watchLoginReturn();
    });
    authOut.addEventListener('click', () => {
      logoutAccount();
    });
    exclusiveBtn.addEventListener('click', () => {
      if (tabControl) tabControl.claimExclusive();
    });
    powerBtn.addEventListener('click', () => {
      if (tabControl) tabControl.togglePower();
    });
    updateAuthButton();

    const api = {
      write(kind, message) {
        const time = new Date().toLocaleTimeString();
        const normalized = ['ok', 'warn', 'error'].includes(kind) ? kind : 'info';
        const text = '[' + time + '] [' + normalized.toUpperCase() + '] ' + String(message || '');
        const line = document.createElement('div');
        line.className = normalized;
        line.textContent = text;
        entries.push({ text, line });
        lines.appendChild(line);
        while (entries.length > PAGE_CONSOLE_MAX_LINES) {
          const removed = entries.shift();
          removed.line.remove();
        }
        lines.scrollTop = lines.scrollHeight;
      },
      setState(value) {
        state.textContent = value || '';
      },
      setWork(mode) {
        const name = mode || 'off';
        workDot.className = 'dot ' + name;
        workDot.title = name === 'run'
          ? 'выполняется задание'
          : name === 'exclusive'
            ? 'единственная связь'
            : name === 'away'
              ? 'связь на другой вкладке'
              : name === 'ok'
                ? 'скрипт работает'
                : 'скрипт выключен';
      },
      setPower(on, exclusive, away) {
        powerBtn.textContent = on ? 'Выкл' : 'Вкл';
        powerBtn.className = on ? 'danger' : 'primary';
        exclusiveBtn.className = exclusive ? 'primary' : '';
        exclusiveBtn.textContent = 'Главная сессия';
        exclusiveBtn.setAttribute('aria-pressed', exclusive ? 'true' : 'false');
        exclusiveBtn.title = away
          ? 'Забрать главную сессию у другой вкладки'
          : exclusive
            ? 'Снять главную сессию'
            : 'Только эта вкладка выполняет JS-чат';
      },
      setJobs(items, extra) {
        const list = Array.isArray(items) ? items : [];
        const pending = list.filter(job => job.status === 'pending').length;
        const running = list.filter(job => job.status === 'in_flight').length;
        const prefix = 'очередь: ' + pending + ' в ожидании · ' + running + ' в работе';
        if (!list.length) {
          jobs.textContent = extra ? String(extra) : 'очередь пуста';
          return;
        }
        const line = list.slice(0, 8).map(job => {
          const id = String(job.id || '').slice(0, 8);
          return (job.status || '?') + ' #' + id;
        }).join(' · ');
        jobs.textContent = prefix + '\n' + line;
      },
    };
    host.__aipilotConsole = api;
    pageConsole = api;
    return api;
  }

  function log(kind, message) {
    const text = formatError(message);
    const method = kind === 'error' ? 'error' : kind === 'warn' ? 'warn' : 'log';
    console[method]('[AIPilot]', text);
    if (pageConsole) pageConsole.write(kind, text);
  }

  // --- utils ---

  function qs(selector, root) {
    return (root || document).querySelector(selector);
  }

  function qsa(selector, root) {
    return Array.from((root || document).querySelectorAll(selector));
  }

  function isVisible(element) {
    if (!element || element.getAttribute('aria-hidden') === 'true') return false;
    if (element.closest('[data-role="measure"], [aria-hidden="true"]')) return false;
    return element.offsetParent !== null
      || Boolean(element.getClientRects && element.getClientRects().length);
  }

  function isDisabled(element) {
    if (!element) return true;
    if (element.disabled) return true;
    if (element.getAttribute('aria-disabled') === 'true') return true;
    const cls = String((element.getAttribute && element.getAttribute('class')) || '');
    return /\bds-button--disabled\b/.test(cls);
  }

  function composerText(element) {
    if (!element) return '';
    return String(element.value || element.innerText || element.textContent || '');
  }

  function pageWindow() {
    return typeof unsafeWindow !== 'undefined' ? unsafeWindow : window;
  }

  function pageDocument() {
    const win = pageWindow();
    return (win && win.document) || document;
  }

  function pageCtor(name) {
    const win = pageWindow();
    if (win && typeof win[name] === 'function') return win[name];
    if (typeof window !== 'undefined' && typeof window[name] === 'function') return window[name];
    return null;
  }

  function pageEvent(type, init, ctorName) {
    const Ctor = pageCtor(ctorName || 'Event') || Event;
    try {
      return new Ctor(type, init || { bubbles: true, cancelable: true });
    } catch (error) {
      return new Event(type, init || { bubbles: true });
    }
  }

  function pageFetcher() {
    const page = pageWindow();
    if (page && typeof page.fetch === 'function') return page.fetch.bind(page);
    if (typeof fetch === 'function') return fetch;
    return null;
  }

  function nativeValueSetter(element) {
    const win = pageWindow();
    const proto = element.tagName === 'TEXTAREA'
      ? win.HTMLTextAreaElement.prototype
      : win.HTMLInputElement.prototype;
    const descriptor = Object.getOwnPropertyDescriptor(proto, 'value')
      || Object.getOwnPropertyDescriptor(element.constructor.prototype, 'value');
    return descriptor && descriptor.set;
  }

  function callReactInput(element) {
    if (!element) return;
    const keys = Object.keys(element);
    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      if (key.indexOf('__reactProps') !== 0 && key.indexOf('__reactEventHandlers') !== 0) continue;
      const props = element[key];
      if (!props) continue;
      const fake = {
        target: element,
        currentTarget: element,
        bubbles: true,
        persist: function persist() {},
      };
      try {
        if (typeof props.onChange === 'function') props.onChange(fake);
      } catch (error) {
        // React onChange is best-effort.
      }
      try {
        if (typeof props.onInput === 'function') props.onInput(fake);
      } catch (error) {
        // React onInput is best-effort.
      }
    }
  }

  function setNativeValue(element, value) {
    if (!element) return;
    const text = value == null ? '' : String(value);
    const doc = pageDocument();
    element.focus();
    try {
      element.select();
    } catch (error) {
      // Not all inputs support select().
    }
    const tracker = element._valueTracker;
    if (tracker && typeof tracker.setValue === 'function') {
      try {
        tracker.setValue(element.value != null ? element.value : '');
      } catch (error) {
        // React may not expose a tracker.
      }
    }
    if (element.isContentEditable) {
      element.textContent = text;
    } else {
      const setter = nativeValueSetter(element);
      if (setter) setter.call(element, text);
      else element.value = text;
    }
    if (!element.isContentEditable && (element.value || '') !== text) {
      try {
        doc.execCommand('selectAll', false, null);
      } catch (error) {
        // execCommand is best-effort.
      }
      try {
        doc.execCommand('insertText', false, text);
      } catch (error) {
        // insertText is best-effort.
      }
      if ((element.value || '') !== text) {
        const setter = nativeValueSetter(element);
        if (setter) setter.call(element, text);
        else element.value = text;
      }
    }
    try {
      element.dispatchEvent(pageEvent('input', {
        bubbles: true,
        cancelable: true,
        inputType: 'insertFromPaste',
        data: text,
      }, 'InputEvent'));
    } catch (error) {
      element.dispatchEvent(pageEvent('input', { bubbles: true }));
    }
    element.dispatchEvent(pageEvent('input', { bubbles: true }));
    element.dispatchEvent(pageEvent('change', { bubbles: true }));
    callReactInput(element);
  }

  function pressEnter(element, ctrlKey) {
    if (!element) return;
    const options = {
      key: 'Enter',
      code: 'Enter',
      keyCode: 13,
      which: 13,
      bubbles: true,
      cancelable: true,
      ctrlKey: Boolean(ctrlKey),
    };
    element.dispatchEvent(pageEvent('keydown', options, 'KeyboardEvent'));
    element.dispatchEvent(pageEvent('keypress', options, 'KeyboardEvent'));
    element.dispatchEvent(pageEvent('keyup', options, 'KeyboardEvent'));
  }

  function titlesMatch(left, right) {
    const a = String(left || '').trim().toLocaleLowerCase();
    const b = String(right || '').trim().toLocaleLowerCase();
    return Boolean(a && b && (a === b || a.includes(b) || b.includes(a)));
  }

  function firstMatch(selectors, root) {
    for (const selector of String(selectors || '').split(',')) {
      if (!selector.trim()) continue;
      const match = qs(selector.trim(), root);
      if (match) return match;
    }
    return null;
  }

  function firstVisibleMatch(selectors, root) {
    for (const selector of String(selectors || '').split(',')) {
      const trimmed = selector.trim();
      if (!trimmed) continue;
      const hit = qsa(trimmed, root).find(element => isVisible(element) && !isDisabled(element));
      if (hit) return hit;
    }
    return null;
  }

  function findByText(selector, matcher, root) {
    const regexp = matcher instanceof RegExp ? matcher : new RegExp(matcher, 'i');
    return qsa(selector, root).find(element => {
      return regexp.test((element.textContent || '').trim());
    }) || null;
  }

  function hrefOfUse(element) {
    const use = element && element.querySelector && element.querySelector('use');
    if (!use) return '';
    return use.getAttribute('href')
      || use.getAttribute('xlink:href')
      || use.getAttributeNS('http://www.w3.org/1999/xlink', 'href')
      || '';
  }

  function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  function waitFor(predicate, maxMs, intervalMs) {
    const startedAt = Date.now();
    const interval = intervalMs || 100;
    return new Promise(resolve => {
      (function tick() {
        let value = null;
        try {
          value = predicate();
        } catch (error) {
          console.debug('[AIPilot] waitFor predicate:', error);
        }
        if (value) {
          resolve(value);
          return;
        }
        if (Date.now() - startedAt >= maxMs) {
          resolve(null);
          return;
        }
        setTimeout(tick, interval);
      })();
    });
  }

  async function sendPromptText(options) {
    const findInput = options && options.findInput;
    const findSend = options && options.findSend;
    const findStop = options && options.findStop;
    const readAssistant = options && options.readAssistant;
    if (typeof findInput !== 'function' || typeof findSend !== 'function'
        || typeof findStop !== 'function' || typeof readAssistant !== 'function') {
      return 'NO_INPUT';
    }
    const input = findInput();
    if (!input) return 'NO_INPUT';
    const baseline = readAssistant();
    const text = options.text == null ? '' : String(options.text);
    const need = Math.min(8, text.length);
    for (let attempt = 0; attempt < 5; attempt++) {
      setNativeValue(input, text);
      await sleep(attempt === 0 ? 160 : 120);
      if (composerText(input).length >= need) break;
    }
    const filled = composerText(input).length >= need;
    if (!filled && text.trim()) {
      return 'NO_INPUT';
    }
    const send = await waitFor(() => {
      const live = findInput() || input;
      if (text.trim() && composerText(live).length < need) return null;
      const button = findSend();
      if (!button || isDisabled(button)) return null;
      return button;
    }, 6000, 80);
    let submitted = false;
    if (send) {
      send.click();
      submitted = true;
    } else {
      log('warn', options.warn || 'send button not ready');
      pressEnter(input, Boolean(options.ctrlEnter));
      submitted = true;
      await sleep(180);
      const retry = findSend();
      if (retry && !isDisabled(retry)) {
        retry.click();
      } else {
        pressEnter(input, !options.ctrlEnter);
      }
    }
    const generationStarted = () => {
      if (findStop()) return true;
      if (readAssistant() !== baseline) return true;
      const now = composerText(input).trim();
      if (filled && now.length < Math.min(8, text.trim().length)) return true;
      return filled && (input.value || '') === '' && !input.isContentEditable;
    };
    let started = await waitFor(generationStarted, 5000, 100);
    if (!started) {
      pressEnter(input, false);
      submitted = true;
      started = await waitFor(generationStarted, 2500, 100);
    }
    if (started || submitted) {
      if (!started) log('info', 'submit attempted; waiting for assistant');
      return 'SENT';
    }
    return 'NO_SUBMIT';
  }

  const registerState = new Map();
  const registerRetryAt = new Map();

  async function pingRegister(provider, online) {
    const ready = online !== false;
    if (ready) {
      const until = registerRetryAt.get(provider) || 0;
      if (Date.now() < until) return;
    }
    try {
      const response = await requestOk('POST', BASE + '/register', {
        provider,
        version: VERSION,
        token: currentToken(),
        ready,
        online: ready,
      });
      parseData(response);
      registerRetryAt.delete(provider);
      if (!ready) {
        registerState.delete(provider);
        return;
      }
      if (registerState.get(provider) !== 'ok') activity(provider, 'register ok');
      if (pageConsole && !tabControl) pageConsole.setState('connected');
      registerState.set(provider, 'ok');
      refreshAccount(false);
    } catch (error) {
      if (!ready) {
        registerState.delete(provider);
        return;
      }
      const authFail = isAuthFailure(error);
      const previous = registerState.get(provider);
      if (previous !== 'fail' && previous !== 'auth') {
        activity(provider, 'register fail ' + formatError(error));
      }
      if (pageConsole) pageConsole.setState(authFail ? 'need login' : 'API error');
      registerState.set(provider, authFail ? 'auth' : 'fail');
      registerRetryAt.set(provider, Date.now() + (authFail ? 8000 : 12000));
    }
  }

  async function postResult(id, provider, text, error, chatId) {
    const failed = Boolean(error);
    const payload = {
      id: id,
      job_id: id,
      provider,
      status: failed ? 'error' : 'done',
      text: text || '',
      response: text || '',
      result: text || '',
      error: error || '',
      chatId: chatId || '',
      chat_id: chatId || '',
    };
    let lastError = null;
    for (let attempt = 0; attempt < 4; attempt += 1) {
      try {
        await requestOk(
          'POST',
          BASE + '/jobs/' + encodeURIComponent(id) + '/result',
          payload
        );
        return;
      } catch (failure) {
        lastError = failure;
        log('warn', 'result retry ' + (attempt + 1) + ': ' + formatError(failure));
        await sleep(500 * (attempt + 1));
      }
    }
    activity(provider, 'result post failed ' + formatError(lastError));
    throw lastError || new Error('RESULT_POST_FAILED');
  }

  function createLeaderLease(provider, onChange) {
    const key = 'aipilot.leader.' + provider;
    const exclusiveKey = 'aipilot.exclusive.' + provider;
    const owner = 'tab-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9);
    let leader = false;
    let suppressed = false;

    function read() {
      try {
        return JSON.parse(localStorage.getItem(key) || 'null');
      } catch (error) {
        return null;
      }
    }

    function write() {
      try {
        localStorage.setItem(key, JSON.stringify({ owner, at: Date.now() }));
      } catch (error) {
        return false;
      }
      const record = read();
      return Boolean(record && record.owner === owner);
    }

    function expired(record) {
      return !record || !record.at || Date.now() - record.at > LOCK_TTL_MS;
    }

    function exclusiveRecord() {
      try {
        return JSON.parse(localStorage.getItem(exclusiveKey) || 'null');
      } catch (error) {
        return null;
      }
    }

    function exclusiveAlive(record) {
      return Boolean(record && record.at && Date.now() - record.at <= EXCLUSIVE_TTL_MS);
    }

    function refresh() {
      const wasLeader = leader;
      const exclusive = exclusiveRecord();
      if (suppressed) {
        leader = false;
      } else if (exclusiveAlive(exclusive) && exclusive.owner !== owner) {
        leader = false;
      } else if (exclusiveAlive(exclusive) && exclusive.owner === owner) {
        leader = write();
      } else {
        const record = read();
        if (record && record.owner === owner) {
          leader = write();
        } else if (expired(record)) {
          leader = write();
        } else {
          leader = false;
        }
      }
      if (leader !== wasLeader && typeof onChange === 'function') onChange(leader);
      return leader;
    }

    refresh();
    setInterval(refresh, 4000);
    window.addEventListener('storage', event => {
      if (event.key === key || event.key === exclusiveKey) refresh();
    });
    window.addEventListener('beforeunload', () => {
      try {
        const record = read();
        if (record && record.owner === owner) localStorage.removeItem(key);
      } catch (error) {
        // Ignore storage failures during page shutdown.
      }
    });

    try {
      const channel = new BroadcastChannel('aipilot-' + provider);
      channel.onmessage = event => {
        const data = event.data;
        if (data === 'need-leader' || (data && data.type === 'need-leader')) refresh();
        if (data && (data.type === 'exclusive-claim' || data.type === 'exclusive-release')) refresh();
      };
      if (!leader) channel.postMessage('need-leader');
    } catch (error) {
      // BroadcastChannel is optional; localStorage remains the source of truth.
    }

    return {
      owner,
      isLeader: () => !suppressed && leader && refresh(),
      hold() {
        suppressed = false;
        leader = write();
        if (typeof onChange === 'function') onChange(leader);
        return leader;
      },
      resume() {
        suppressed = false;
        return refresh();
      },
      release() {
        try {
          const record = read();
          if (record && record.owner === owner) localStorage.removeItem(key);
        } catch (error) {
          // Ignore storage failures while stepping down.
        }
        const was = leader;
        leader = false;
        suppressed = true;
        if (was && typeof onChange === 'function') onChange(false);
      },
    };
  }

  function createTabControl(provider, lease, sync) {
    const enabledKey = 'aipilot.enabled.' + provider;
    const exclusiveKey = 'aipilot.exclusive.' + provider;
    const wantKey = 'aipilot.exclusiveWant.' + provider;
    const owner = lease.owner;
    let wantExclusive = false;
    let lastAway = false;
    let lastWork = null;
    let channel = null;

    function enabled() {
      try {
        return sessionStorage.getItem(enabledKey) !== '0';
      } catch (error) {
        return true;
      }
    }

    function setEnabled(on) {
      try {
        sessionStorage.setItem(enabledKey, on ? '1' : '0');
      } catch (error) {
        // sessionStorage may be blocked.
      }
    }

    function wantStored() {
      try {
        return sessionStorage.getItem(wantKey) === '1';
      } catch (error) {
        return false;
      }
    }

    function setWantStored(on) {
      try {
        sessionStorage.setItem(wantKey, on ? '1' : '0');
      } catch (error) {
        // sessionStorage may be blocked.
      }
    }

    function readExclusive() {
      try {
        return JSON.parse(localStorage.getItem(exclusiveKey) || 'null');
      } catch (error) {
        return null;
      }
    }

    function exclusiveExpired(record) {
      return !record || !record.at || Date.now() - record.at > EXCLUSIVE_TTL_MS;
    }

    function writeExclusive() {
      try {
        localStorage.setItem(exclusiveKey, JSON.stringify({ owner, at: Date.now() }));
      } catch (error) {
        return false;
      }
      const record = readExclusive();
      return Boolean(record && record.owner === owner);
    }

    function isExclusive() {
      const record = readExclusive();
      return Boolean(record && record.owner === owner && !exclusiveExpired(record));
    }

    function otherExclusive() {
      const record = readExclusive();
      return Boolean(record && record.owner !== owner && !exclusiveExpired(record));
    }

    function canWork() {
      if (!enabled()) return false;
      if (otherExclusive()) return false;
      if (isExclusive() || wantExclusive) return true;
      return lease.isLeader();
    }

    function post(type) {
      try {
        if (channel) channel.postMessage({ type, owner });
      } catch (error) {
        // BroadcastChannel is optional.
      }
    }

    function applyWorkChange() {
      const working = canWork();
      if (working !== lastWork) {
        lastWork = working;
        if (enabled()) pingRegister(provider, true);
      }
      if (typeof sync === 'function') sync();
    }

    function noteAway() {
      const away = otherExclusive();
      if (away && !lastAway) log('warn', 'exclusive: other tab');
      lastAway = away;
    }

    function releaseExclusive(silent) {
      wantExclusive = false;
      setWantStored(false);
      try {
        const record = readExclusive();
        if (record && record.owner === owner) localStorage.removeItem(exclusiveKey);
      } catch (error) {
        // Ignore storage failures while releasing exclusive.
      }
      post('exclusive-release');
      if (!silent) log('warn', 'exclusive released');
      applyWorkChange();
    }

    function claimExclusive() {
      if (!enabled()) setEnabled(true);
      if (isExclusive() && wantExclusive) {
        releaseExclusive();
        lease.resume();
        applyWorkChange();
        return;
      }
      wantExclusive = true;
      setWantStored(true);
      writeExclusive();
      lease.hold();
      post('exclusive-claim');
      log('ok', 'exclusive: only this tab');
      applyWorkChange();
    }

    function togglePower() {
      const next = !enabled();
      setEnabled(next);
      if (!next) {
        if (wantExclusive || isExclusive()) releaseExclusive(true);
        lease.release();
        log('warn', 'script off');
      } else {
        lease.resume();
        if (wantStored()) {
          wantExclusive = true;
          writeExclusive();
          lease.hold();
          post('exclusive-claim');
        }
        log('ok', 'script on');
      }
      applyWorkChange();
    }

    function heartbeat() {
      if (!enabled()) return;
      if (wantExclusive && !otherExclusive()) {
        writeExclusive();
        lease.hold();
      }
    }

    try {
      channel = new BroadcastChannel('aipilot-' + provider);
      channel.onmessage = event => {
        const data = event.data;
        if (!data || data.owner === owner) return;
        if (data.type === 'exclusive-claim') {
          wantExclusive = false;
          setWantStored(false);
          noteAway();
          applyWorkChange();
          return;
        }
        if (data.type === 'exclusive-release') {
          noteAway();
          applyWorkChange();
        }
      };
    } catch (error) {
      // BroadcastChannel is optional; localStorage remains the source of truth.
    }

    wantExclusive = wantStored() && enabled();
    if (wantExclusive) {
      writeExclusive();
      lease.hold();
      post('exclusive-claim');
    }

    setInterval(heartbeat, 4000);
    window.addEventListener('storage', event => {
      if (event.key !== exclusiveKey) return;
      const record = readExclusive();
      if (record && record.owner !== owner && !exclusiveExpired(record)) {
        wantExclusive = false;
        setWantStored(false);
      }
      noteAway();
      applyWorkChange();
    });
    window.addEventListener('beforeunload', () => {
      if (!wantExclusive) {
        try {
          const record = readExclusive();
          if (record && record.owner === owner) localStorage.removeItem(exclusiveKey);
        } catch (error) {
          // Ignore storage failures during page shutdown.
        }
      }
    });

    noteAway();
    applyWorkChange();

    return {
      enabled,
      canWork,
      isExclusive,
      otherExclusive,
      claimExclusive,
      togglePower,
      notify: applyWorkChange,
    };
  }

  function waitComplete(adapter, baseline, maxMs) {
    const startedAt = Date.now();
    const deepseek = adapter && adapter.id === 'deepseek';
    const holdMs = deepseek ? 3500 : 2500;
    let sawStop = false;
    let lastText = baseline || '';
    let stableMs = 0;
    let grewOnce = false;
    return new Promise((resolve) => {
      (function tick() {
        const stop = adapter.findStop && adapter.findStop();
        let text = '';
        try {
          text = adapter.readAssistant ? String(adapter.readAssistant() || '') : '';
        } catch (_) {
          text = '';
        }
        if (stop) {
          sawStop = true;
          if (text && text !== lastText) grewOnce = true;
          lastText = text;
          stableMs = 0;
        } else if (sawStop) {
          if (text && text !== lastText) {
            lastText = text;
            stableMs = 0;
            grewOnce = true;
          } else if (text) {
            stableMs += 400;
            if (stableMs >= (deepseek ? 1600 : 800)) {
              resolve();
              return;
            }
          } else {
            // Stop gone but empty text: keep waiting a bit, then accept whatever harvest finds.
            stableMs += 400;
            if (stableMs >= (deepseek ? 6000 : 3000)) {
              resolve();
              return;
            }
          }
        } else if (text && text !== baseline) {
          grewOnce = true;
          if (text === lastText) {
            stableMs += 400;
            if (stableMs >= holdMs) {
              resolve();
              return;
            }
          } else {
            lastText = text;
            stableMs = 0;
          }
        } else if (grewOnce && lastText && !text) {
          // Brief empty read after we already saw content — keep lastText, don't reset forever.
          stableMs += 400;
          if (stableMs >= (deepseek ? 5000 : 3000)) {
            resolve();
            return;
          }
        } else {
          lastText = text || lastText;
          stableMs = 0;
        }
        if (Date.now() - startedAt >= maxMs) {
          resolve();
          return;
        }
        setTimeout(tick, 400);
      })();
    });
  }

  function readStable(adapter, maxMs) {
    const startedAt = Date.now();
    const deepseek = adapter && adapter.id === 'deepseek';
    const holdMs = deepseek ? 2500 : 1200;
    const cap = deepseek ? Math.max(maxMs || 0, 90000) : (maxMs || 20000);
    let last = '';
    let stableMs = 0;
    return new Promise(resolve => {
      (function tick() {
        const current = adapter.readAssistant();
        if (current && current === last) {
          stableMs += 300;
          if (stableMs >= holdMs) {
            resolve(current);
            return;
          }
        } else {
          last = current;
          stableMs = 0;
        }
        if (Date.now() - startedAt >= cap) {
          resolve(last);
          return;
        }
        setTimeout(tick, 300);
      })();
    });
  }

  async function harvestAnswer(adapter, light) {
    let full = '';
    if (adapter && typeof adapter.readAssistantFull === 'function') {
      try {
        full = String(await adapter.readAssistantFull() || '').trim();
      } catch (error) {
        // Light capture still used below.
      }
    }
    let lightText = String(light || '').trim()
      || (adapter && typeof adapter.readAssistant === 'function'
        ? String(adapter.readAssistant() || '').trim()
        : '');
    if ((!lightText || lightText.length < 40) && adapter && typeof adapter.readAnswerForPrompt === 'function') {
      try {
        const keyed = String(adapter.readAnswerForPrompt('') || '').trim();
        if (keyed && keyed.length > lightText.length) lightText = keyed;
      } catch (_) { /* ignore */ }
    }
    if (full && full.length >= lightText.length) return sanitizeHarvested(full);
    return sanitizeHarvested(lightText || full);
  }

  function stripEditorChrome(text) {
    const ORPHAN_LANG = 'xml|json|javascript|typescript|html|tsx|jsx|python|py|bash|sh|shell|css|md|markdown|text|plaintext';
    const CHROME_BTN = 'Копировать|Скачать|Copy(?:\\s+code)?|Download|Share|Поделиться|复制|下载';
    const chromeRe = new RegExp('^\\s*(?:' + CHROME_BTN + ')\\s*$', 'iu');
    const langRe = new RegExp('^\\s*(?:' + ORPHAN_LANG + ')\\s*$', 'i');
    let s = String(text || '').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    if (!s) return '';
    const lines = s.split('\n');
    const numIdx = [];
    for (let i = 0; i < lines.length; i++) {
      if (/^\s*\d+\s*$/.test(lines[i])) numIdx.push(i);
    }
    let sequential = false;
    if (numIdx.length >= 5) {
      let run = 1;
      for (let i = 1; i < numIdx.length; i++) {
        const a = parseInt(lines[numIdx[i - 1]], 10);
        const b = parseInt(lines[numIdx[i]], 10);
        if (b === a + 1) run += 1;
        else run = 1;
        if (run >= 5) {
          sequential = true;
          break;
        }
      }
    }
    if (numIdx.length >= 5 && (numIdx.length / Math.max(lines.length, 1) >= 0.3 || sequential)) {
      s = lines.filter((ln) => !/^\s*\d+\s*$/.test(ln)).join('\n');
    }
    s = s.replace(/<\s*file\s+path\s*=\s*"([^"]+)"\s*>/gi, '<file path="$1">');
    s = s.replace(/<\s*\/\s*file\s*>/gi, '</file>');
    const kept = [];
    const raw = s.split('\n');
    for (let i = 0; i < raw.length; i++) {
      const line = raw[i];
      const isBtn = chromeRe.test(line);
      const isLang = langRe.test(line);
      if (!isBtn && !isLang) {
        kept.push(line);
        continue;
      }
      let j = i;
      while (j < raw.length && (langRe.test(raw[j]) || chromeRe.test(raw[j]) || !String(raw[j]).trim())) j += 1;
      const next = j < raw.length ? raw[j] : '';
      const t = String(next).replace(/^\s+/, '');
      const codeStart = t !== '' && (t.startsWith('```') || t.startsWith('<file') || t.startsWith('{'));
      if (isBtn) continue;
      if (isLang && (!next || codeStart || chromeRe.test(next))) continue;
      kept.push(line);
    }
    s = kept.join('\n');
    s = s.replace(/\n{3,}/g, '\n\n');
    return s.trim();
  }

  function sanitizeHarvested(text) {
    let s = stripEditorChrome(text);
    if (!s) return '';
    const kept = [];
    s = s.replace(/```xml\s*([\s\S]*?)```/gi, (all) => {
      kept.push(all);
      return '\u0000' + (kept.length - 1) + '\u0000';
    });
    s = s.replace(/<file\s+path="[^"]+">[\s\S]*?<\/file>/gi, (all) => {
      kept.push(all);
      return '\u0000' + (kept.length - 1) + '\u0000';
    });
    s = s.replace(/```(?:run\s+)?(?:python|py|powershell|pwsh|ps1|bash|sh|shell|cmd|console|javascript|js|node|md|markdown|file)\b[\s\S]*?```/gi, (all) => {
      kept.push(all);
      return '\u0000' + (kept.length - 1) + '\u0000';
    });
    if (/class\s*=|qwen-|ds-markdown|copy\s+share|stop generating|<\s*(div|nav|button|svg)\b/i.test(s)) {
      s = s.replace(/<\/?(?:div|nav|header|footer|section|article|button|svg|path|span|a|ul|ol|li|p|h[1-6]|script|style|img|input|label|form|aside|main)(?:\s[^>]*)?\/?>/gi, ' ');
      s = s.replace(/<[^>]+>/g, ' ');
    }
    s = s.replace(/&nbsp;/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
    s = s.replace(/\u0000(\d+)\u0000/g, (_, i) => kept[Number(i)] || '');
    return s.trim();
  }

  function assertAdapter(adapter) {
    const required = ['id', 'findInput', 'sendPrompt', 'findStop', 'readAssistant'];
    const missing = required.filter(name => !adapter
      || (name === 'id' ? !adapter.id : typeof adapter[name] !== 'function'));
    if (missing.length) throw new Error('Invalid provider adapter: ' + missing.join(', '));
  }

  async function bootSiteAuth() {
    listenAuthChannel();
    const started = Date.now();
    const cap = 180000;
    async function tick() {
      try {
        const name = await bindTokenFromCookie();
        log('ok', 'token bound · ' + name);
        if (pageConsole) pageConsole.setState('signed in');
      } catch (error) {
        storageSet(AUTH_KEY, '0');
        storageSet(USER_KEY, '');
        if (Date.now() - started >= cap) {
          log('warn', 'cookie bind timeout');
          return;
        }
        setTimeout(tick, 1500);
      }
    }
    tick();
  }

  // --- poll ---

  function boot(adapter) {
    listenAuthChannel();
    assertAdapter(adapter);
    const provider = adapter.id;
    createPageConsole(provider);
    log('ok', 'start ai151');
    ensureToken();
    const lease = createLeaderLease(provider, () => {
      if (tabControl) tabControl.notify();
    });
    let busy = false;
    let pollPausedForAuth = false;
    let pollFailCount = 0;
    let lastPollError = '';
    let lastUiMode = '';

    function applyWorkerUi() {
      if (!pageConsole || !tabControl) return;
      const on = tabControl.enabled();
      const exclusive = tabControl.isExclusive();
      const away = tabControl.otherExclusive();
      const working = tabControl.canWork();
      if (typeof pageConsole.setPower === 'function') {
        pageConsole.setPower(on, exclusive, away);
      }
      if (busy && working) {
        pageConsole.setWork('run');
        pageConsole.setState('в работе');
      } else if (!on) {
        pageConsole.setWork('off');
        pageConsole.setState('выкл');
      } else if (registerState.get(provider) === 'auth') {
        pageConsole.setWork('away');
        pageConsole.setState('need login');
      } else if (away) {
        pageConsole.setWork('away');
        pageConsole.setState('откл · другая вкладка');
      } else if (exclusive && working) {
        pageConsole.setWork('exclusive');
        pageConsole.setState('связь');
      } else if (working) {
        pageConsole.setWork('ok');
        pageConsole.setState('connected');
      } else {
        pageConsole.setWork('off');
        pageConsole.setState('passive');
      }
      const mode = !on ? 'off' : away ? 'away' : exclusive ? 'exclusive' : working ? 'on' : 'passive';
      if (mode !== lastUiMode) {
        lastUiMode = mode;
        if (mode === 'passive') log('warn', 'passive tab');
        if (mode === 'on') log('ok', 'leader tab');
      }
    }

    tabControl = createTabControl(provider, lease, applyWorkerUi);
    applyWorkerUi();

    onAuthenticated(() => {
      pollPausedForAuth = false;
      pollFailCount = 0;
      lastPollError = '';
      registerState.delete(provider);
      registerRetryAt.delete(provider);
      applyWorkerUi();
      if (tabControl.enabled()) pingRegister(provider, true);
    });

    async function finish(job, text, error) {
      activity(provider, error
        ? 'error ' + error
        : 'done ' + String(text || '').length + ' chars');
      let chatId = '';
      try {
        if (!/CHAT_GONE/i.test(String(error || ''))
            && adapter && typeof adapter.currentChatId === 'function') {
          chatId = String(adapter.currentChatId() || '');
          if (!chatId) {
            for (let i = 0; i < 5 && !chatId; i += 1) {
              await sleep(400);
              chatId = String(adapter.currentChatId() || '');
            }
          }
        }
      } catch (ignored) {
        chatId = '';
      }
      try {
        await postResult(job.id, provider, text, error, chatId);
      } catch (failure) {
        activity(provider, 'result persist failed ' + formatError(failure));
      }
      busy = false;
      applyWorkerUi();
    }

    function providerHomeUrl(adapter) {
    if (adapter && typeof adapter.homeUrl === 'function') {
      try {
        const u = String(adapter.homeUrl() || '').trim();
        if (u) return u;
      } catch (_) { /* ignore */ }
    }
    const id = adapter && adapter.id;
    if (id === 'deepseek') return 'https://chat.deepseek.com/';
    if (id === 'qwen') return 'https://chat.qwen.ai/';
    return String(location.origin || '') + '/';
  }

  function forceHomeReload(adapter, reason) {
    const url = providerHomeUrl(adapter);
    try {
      activity(adapter && adapter.id, 'force home · ' + (reason || '') + ' → ' + url);
    } catch (_) { /* ignore */ }
    try {
      location.assign(url);
    } catch (_) {
      try { location.href = url; } catch (e2) { /* ignore */ }
    }
  }

  async function handleJob(job) {
      busy = true;
      applyWorkerUi();
      activity(provider, 'job received ' + job.id + ' ' + (job.action || 'prompt'));
      let sent = false;
      let baseline = '';
      const jobStartedAt = Date.now();
      const jobDeadline = jobStartedAt + JOB_HARD_TIMEOUT_MS;
      const timedOut = () => Date.now() >= jobDeadline;
      try {
        const action = job.action || 'prompt';
        if (action === 'stop') {
          const stopBtn = typeof adapter.findStop === 'function' ? adapter.findStop() : null;
          if (stopBtn && typeof stopBtn.click === 'function') stopBtn.click();
          await finish(job, 'stopped', '');
          return;
        }
        if (action === 'create_project' || action === 'delete_project') {
          if (typeof adapter.ensureProject !== 'function' && action === 'create_project') {
            await finish(job, '', 'NO_PROJECT_API');
            return;
          }
          if (typeof adapter.deleteProject !== 'function' && action === 'delete_project') {
            await finish(job, '', 'NO_PROJECT_API');
            return;
          }
          const name = job.qwenProjectName || job.projectName || '';
          const result = action === 'delete_project'
            ? await adapter.deleteProject(name)
            : await adapter.ensureProject(name);
          const ok = /^(OK|CREATED|ALREADY|DELETED|DELETE_CLICKED|NEW_PROJECT)$/.test(result);
          await finish(job, result, ok ? '' : (result || 'PROJECT_FAILED'));
          return;
        }
        if (typeof adapter.prepare === 'function') await adapter.prepare(job);
        const input = await waitFor(() => adapter.findInput(), 8000, 200);
        if (!input) {
          await finish(job, '', 'NO_INPUT');
          return;
        }
        baseline = adapter.readAssistant();
        const resume = action === 'match_or_prompt' || action === 'resume';
        let status = 'SENT';
        if (resume && typeof adapter.findMatchingUserPrompt === 'function'
            && adapter.findMatchingUserPrompt(job.prompt || '')) {
          activity(provider, 'resume: prompt matched in chat');
          const stopBtn = typeof adapter.findStop === 'function' ? adapter.findStop() : null;
          if (stopBtn) {
            await waitComplete(adapter, baseline, Math.min(GENERATION_TIMEOUT_MS, jobDeadline - Date.now()));
          }
          let answer = await readStable(adapter, adapter.id === 'deepseek' ? 90000 : 45000);
          if (!answer && typeof adapter.readAnswerForPrompt === 'function') {
            try {
              answer = String(adapter.readAnswerForPrompt(job.prompt || '') || '');
            } catch (_) { answer = ''; }
          }
          answer = await harvestAnswer(adapter, answer);
          if (answer) {
            await finish(job, answer, '');
            return;
          }
          activity(provider, 'resume: no answer, sending prompt');
        }
        if (timedOut()) {
          await finish(job, '', 'JOB_TIMEOUT');
          forceHomeReload(adapter, 'timeout before send');
          return;
        }
        status = await adapter.sendPrompt(job.prompt || '');
        sent = status !== 'NO_INPUT';
        if (status === 'NO_INPUT') {
          await finish(job, '', status);
          return;
        }
        if (status !== 'SENT') {
          activity(provider, 'submit status ' + status + '; waiting for assistant');
        } else {
          activity(provider, 'prompt sent');
        }
        let answer = '';
        let round = 0;
        const maxRounds = 8;
        while (!answer && round < maxRounds) {
          if (timedOut()) break;
          const slice = Math.max(5000, Math.min(GENERATION_TIMEOUT_MS, jobDeadline - Date.now()));
          await waitComplete(adapter, baseline, slice);
          answer = await readStable(adapter, adapter.id === 'deepseek' ? 90000 : 45000);
          if (!answer && typeof adapter.readAnswerForPrompt === 'function') {
            try {
              answer = String(adapter.readAnswerForPrompt(job.prompt || '') || '');
            } catch (_) { /* ignore */ }
          }
          if (answer) break;
          round += 1;
          activity(provider, 'waiting for assistant · round ' + round);
          await sleep(2000);
        }
        answer = await harvestAnswer(adapter, answer);
        if (!answer && typeof adapter.readAnswerForPrompt === 'function') {
          try {
            answer = sanitizeHarvested(String(adapter.readAnswerForPrompt(job.prompt || '') || ''));
          } catch (_) { /* ignore */ }
        }
        if (!answer && timedOut()) {
          const partial = await harvestAnswer(adapter, '');
          if (partial) {
            await finish(job, partial, '');
            forceHomeReload(adapter, 'timeout with partial');
            return;
          }
          await finish(job, '', 'JOB_TIMEOUT');
          forceHomeReload(adapter, 'timeout waiting answer');
          return;
        }
        if (!answer) {
          await finish(job, '', 'NO_ANSWER');
          return;
        }
        await finish(job, answer, '');
      } catch (error) {
        let partial = '';
        if (sent) {
          try {
            partial = await harvestAnswer(adapter, '');
          } catch (ignored) {
            try {
              partial = adapter.readAssistant();
            } catch (readError) {
              partial = '';
            }
          }
          if (partial && partial === baseline) partial = '';
        }
        if (partial) {
          activity(provider, 'job error with partial answer ' + formatError(error));
          await finish(job, partial, '');
        } else if (timedOut()) {
          await finish(job, '', 'JOB_TIMEOUT');
          forceHomeReload(adapter, 'timeout on error');
        } else {
          await finish(job, '', formatError(error));
        }
      }
    }

    async function refreshQueue() {
      if (!tabControl.canWork()) {
        if (pageConsole && typeof pageConsole.setJobs === 'function') {
          pageConsole.setJobs([], tabControl.enabled() ? 'очередь: другая вкладка' : 'очередь: выкл');
        }
        return;
      }
      try {
        const response = await requestOk(
          'GET',
          BASE + '/queue?provider=' + encodeURIComponent(provider) + '&limit=8',
          null,
          8000
        );
        const data = parseData(response);
        const items = Array.isArray(data) ? data : (data && data.items) || [];
        if (pageConsole && typeof pageConsole.setJobs === 'function') {
          pageConsole.setJobs(items);
        }
      } catch (error) {
        if (pageConsole && typeof pageConsole.setJobs === 'function') {
          pageConsole.setJobs([], 'очередь: ' + formatError(error));
        }
      }
    }

    async function poll() {
      if (!tabControl.canWork() || busy) {
        setTimeout(poll, 800);
        return;
      }
      if (pollPausedForAuth) {
        setTimeout(poll, 8000);
        return;
      }
      try {
        const response = await requestOk(
          'GET',
          BASE + '/jobs/poll?provider=' + encodeURIComponent(provider),
          null,
          20000
        );
        const data = parseData(response);
        const job = normalizeJob(data);
        pollFailCount = 0;
        lastPollError = '';
        if (job && job.id && (job.prompt != null || job.action !== 'prompt')) {
          log('ok', 'job from API ' + job.id);
          await handleJob(job);
        }
        setTimeout(poll, 800);
      } catch (error) {
        const message = formatError(error);
        if (isAuthFailure(error)) {
          if (!pollPausedForAuth) {
            pollPausedForAuth = true;
            log('warn', 'poll: ' + message);
            if (pageConsole) pageConsole.setState('need login');
            updateAuthButton();
          }
          setTimeout(poll, 8000);
          return;
        }
        pollFailCount += 1;
        const delay = POLL_BACKOFF_MS[Math.min(pollFailCount - 1, POLL_BACKOFF_MS.length - 1)];
        if (message !== lastPollError) {
          log('warn', 'poll failed; retry in ' + (delay / 1000) + 's: ' + message);
          lastPollError = message;
        }
        setTimeout(poll, delay);
      }
    }

    function announcePresence() {
      if (!tabControl.enabled()) return;
      if (!currentToken() && !signedIn()) return;
      pingRegister(provider, true);
    }

    setInterval(announcePresence, 10000);
    setInterval(refreshQueue, 4000);
    setTimeout(() => {
      announcePresence();
      if (tabControl.canWork()) {
        activity(provider, 'start ai151');
        activity(provider, 'worker start');
        refreshAccount(true).then(ok => {
          if (!ok && isSitePage()) tryBindFromCookie(true);
        });
      }
      refreshQueue();
      poll();
    }, 1000);
    if (typeof GM_addValueChangeListener === 'function') {
      GM_addValueChangeListener(TOKEN_KEY, () => {
        updateAuthButton();
        registerState.delete(provider);
        registerRetryAt.delete(provider);
        if (tabControl.enabled()) pingRegister(provider, true);
      });
      GM_addValueChangeListener(AUTH_KEY, () => {
        updateAuthButton();
        if (signedIn()) notifyAuthenticated();
      });
      GM_addValueChangeListener(USER_KEY, () => updateAuthButton());
    }
  }

  function start(adapter) {
    listenAuthChannel();
    if (isSitePage()) {
      bootSiteAuth();
      return;
    }
    if (adapter) {
      boot(adapter);
      return;
    }
    const host = (location.hostname || '').toLowerCase();
    const id = /deepseek/.test(host) ? 'deepseek' : /qwen/.test(host) ? 'qwen' : 'chat';
    createPageConsole(id);
    log('err', 'адаптер DOM не загрузился');
    if (pageConsole) {
      pageConsole.setState('нет адаптера');
      if (typeof pageConsole.write === 'function') {
        pageConsole.write('error', 'Адаптер ' + id + ' не загрузился. Обновите страницу или перепубликуйте JS.');
      }
    }
  }

  const core = {
    VERSION,
    BASE,
    activity,
    log,
    boot,
    start,
    isSitePage,
    formatError,
    utils: {
      qs,
      qsa,
      isVisible,
      isDisabled,
      setNativeValue,
      pressEnter,
      pageWindow,
      titlesMatch,
      firstMatch,
      firstVisibleMatch,
      findByText,
      hrefOfUse,
      sleep,
      waitFor,
      sendPromptText,
      stripEditorChrome,
    },
  };
  global.AIPilotCore = core;
  if (typeof window !== 'undefined') window.AIPilotCore = core;
})(typeof unsafeWindow !== 'undefined' ? unsafeWindow : window);
