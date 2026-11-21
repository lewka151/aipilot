const PROVIDER = '__PROVIDER_ID__';
const ADAPTER = '__ADAPTER_GLOBAL__';
const API_BASE = String(__API_BASE_JSON__).replace(/\/+$/, '');
const TOKEN = String(__BRIDGE_TOKEN_JSON__ || '');
const CHANNEL = 'aipilot-vm';
const CORE_FILE = 'bridge-core.js';
const UI_FILE = PROVIDER === 'deepseek' ? 'deepseek-ui.js' : 'qwen-ui.js';
const SCRIPT_VERSION = '1.9.30';
const EMBEDDED_CORE = __EMBEDDED_CORE_JSON__;
const EMBEDDED_UI = __EMBEDDED_UI_JSON__;

function gmXhr() {
  if (typeof GM_xmlhttpRequest === 'function') return GM_xmlhttpRequest;
  if (typeof GM !== 'undefined' && typeof GM.xmlHttpRequest === 'function') {
    return GM.xmlHttpRequest.bind(GM);
  }
  return null;
}

function formatHttpError(error, url) {
  if (error == null) return 'network ' + url;
  if (typeof error === 'string' && error !== '[object Object]') return error;
  const message = error && error.message && String(error.message) !== '[object Object]'
    ? String(error.message)
    : '';
  const status = error && error.status;
  const body = error && (error.responseText || error.response);
  const parts = [];
  if (status) parts.push('HTTP ' + status);
  if (message) parts.push(message);
  if (url && message.indexOf(String(url)) < 0) parts.push(String(url));
  if (typeof body === 'string' && body.trim() && body !== '[object Object]') {
    parts.push(body.replace(/\s+/g, ' ').trim().slice(0, 180));
  }
  return parts.join(' · ') || 'network ' + (url || '');
}

function headerValue(res, name) {
  const raw = String((res && res.responseHeaders) || '');
  const match = raw.match(new RegExp('^' + name + '\\s*:\\s*(.*)$', 'im'));
  if (match) return match[1].split(';')[0].trim();
  const headers = res && res.headers;
  if (headers && typeof headers === 'object' && !Array.isArray(headers)) {
    return String(headers[name] || headers[name.toLowerCase()] || '').split(';')[0].trim();
  }
  return '';
}

function http(method, url, headers, body, timeoutMs) {
  return new Promise((resolve, reject) => {
    const xhr = gmXhr();
    if (!xhr) {
      reject(new Error('GM_xmlhttpRequest missing'));
      return;
    }
    xhr({
      method: method || 'GET',
      url: url,
      headers: headers || {},
      data: body == null ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
      timeout: timeoutMs || 20000,
      anonymous: false,
      responseType: 'text',
      onload: resolve,
      onerror: res => {
        if (res && (res.status || res.responseText != null)) resolve(res);
        else reject(new Error('network ' + url));
      },
      ontimeout: () => reject(new Error('timeout ' + url)),
    });
  });
}

function isHtmlText(text, contentType) {
  const type = String(contentType || '').toLowerCase();
  const raw = String(text || '').replace(/^\uFEFF/, '').trim();
  if (!raw) return false;
  if (raw.charAt(0) === '<' || /^<!doctype/i.test(raw)) return true;
  return type.indexOf('text/html') >= 0;
}

function looksLikeJs(source, file) {
  const src = String(source || '');
  if (!src.trim() || src.trim().charAt(0) === '<') return false;
  if (file === CORE_FILE) return src.indexOf('AIPilotCore') >= 0;
  if (file === UI_FILE) return src.indexOf(ADAPTER) >= 0;
  return src.indexOf('AIPilot') >= 0;
}

function unwrapScript(text, url) {
  const raw = String(text || '').replace(/^\uFEFF/, '').trim();
  if (!raw) throw new Error('empty script ' + url);
  if (raw.charAt(0) === '<') throw new Error('HTML instead of JS ' + url);
  if (raw.charAt(0) === '{' || raw.charAt(0) === '[') {
    let json;
    try {
      json = JSON.parse(raw);
    } catch (error) {
      throw new Error('JSON parse failed ' + url);
    }
    if (json && json.ok === false) {
      throw new Error(String(json.error || json.message || 'api error') + ' ' + url);
    }
    const src = (json && json.data && (json.data.source || json.data.js || json.data.code))
      || json.source || json.js || json.code || json.script;
    if (!src) throw new Error('JSON without source ' + url);
    const out = String(src).replace(/^\uFEFF/, '').trim();
    if (out.charAt(0) === '<') throw new Error('HTML instead of JS ' + url);
    return out;
  }
  return raw;
}

function scriptUrls(file) {
  return [
    API_BASE + '/assets/js/aipilot/' + file + '?v=' + SCRIPT_VERSION,
    API_BASE + '/api/aipilot/bridge/scripts/' + file + '?v=' + SCRIPT_VERSION,
    API_BASE + '/js/aipilot/' + file + '?v=' + SCRIPT_VERSION,
    API_BASE + '/static/aipilot/' + file + '?v=' + SCRIPT_VERSION,
  ];
}

function embeddedSource(file) {
  const src = file === CORE_FILE ? EMBEDDED_CORE : (file === UI_FILE ? EMBEDDED_UI : '');
  return looksLikeJs(src, file) ? String(src) : '';
}

function acceptHeaders() {
  const headers = {
    Accept: 'application/javascript, text/javascript, text/plain, application/json',
  };
  if (TOKEN) headers['X-AIPilot-Token'] = TOKEN;
  return headers;
}

async function loadFile(file) {
  const cacheKey = 'aipilot.script.' + file;
  let lastError = null;
  for (const url of scriptUrls(file)) {
    try {
      const res = await http('GET', url, acceptHeaders(), null, 20000);
      if (!res || res.status >= 400) {
        lastError = new Error('HTTP ' + (res && res.status) + ' ' + url);
        continue;
      }
      const body = res.responseText != null ? res.responseText : res.response;
      if (typeof body !== 'string') {
        lastError = new Error('non-text response ' + url);
        continue;
      }
      const type = headerValue(res, 'Content-Type');
      if (isHtmlText(body, type)) {
        lastError = new Error('HTML instead of JS ' + url);
        continue;
      }
      const src = unwrapScript(body, url);
      if (!looksLikeJs(src, file)) {
        lastError = new Error('not javascript ' + url);
        continue;
      }
      if (typeof GM_setValue === 'function') GM_setValue(cacheKey, src);
      return src;
    } catch (error) {
      lastError = error;
    }
  }
  if (typeof GM_getValue === 'function') {
    const cached = GM_getValue(cacheKey, '');
    if (looksLikeJs(cached, file) && !isHtmlText(cached, '')) return cached;
  }
  const bundled = embeddedSource(file);
  if (bundled) {
    if (lastError) {
      console.warn('[AIPilot] remote ' + file + ' skipped, using bundled copy:', formatHttpError(lastError, ''));
    }
    return bundled;
  }
  throw lastError || new Error('failed to load ' + file);
}

function gmGlobal() {
  return typeof globalThis !== 'undefined' ? globalThis : window;
}

function applyConfig() {
  const cfg = { apiBase: API_BASE, token: TOKEN, provider: PROVIDER };
  const g = gmGlobal();
  g.AIPILOT_CONFIG = Object.assign({}, g.AIPILOT_CONFIG || {}, cfg);
  try {
    if (typeof unsafeWindow !== 'undefined' && unsafeWindow) {
      unsafeWindow.AIPILOT_CONFIG = Object.assign({}, unsafeWindow.AIPILOT_CONFIG || {}, cfg);
    }
  } catch (error) {
    // Isolated world cannot always write unsafeWindow.
  }
}

function publish(name, value) {
  if (value == null) return;
  gmGlobal()[name] = value;
  try {
    if (typeof unsafeWindow !== 'undefined' && unsafeWindow) unsafeWindow[name] = value;
  } catch (error) {
    // Isolated world.
  }
}

function lookup(name) {
  const g = gmGlobal();
  if (g[name]) return g[name];
  try {
    if (typeof unsafeWindow !== 'undefined' && unsafeWindow && unsafeWindow[name]) {
      return unsafeWindow[name];
    }
  } catch (error) {
    // Isolated world.
  }
  return null;
}

function runInGm(source, label) {
  const code = String(source || '');
  if (!code) throw new Error('empty ' + label);
  if (isHtmlText(code, '')) throw new Error('HTML instead of JS ' + label);
  // Builder replaces JSON placeholder tokens site-wide. Pass baked values
  // as Function parameters whose names are assembled from pieces.
  const apiName = '__API' + '_BASE_JSON__';
  const tokenName = '__BRIDGE' + '_TOKEN_JSON__';
  const page = typeof unsafeWindow !== 'undefined' && unsafeWindow ? unsafeWindow : window;
  const runner = new Function(
    apiName,
    tokenName,
    'GM_xmlhttpRequest',
    'GM_getValue',
    'GM_setValue',
    'GM_addValueChangeListener',
    'GM_openInTab',
    'unsafeWindow',
    code
  );
  runner(
    API_BASE,
    TOKEN == null ? '' : String(TOKEN),
    gmXhr(),
    typeof GM_getValue === 'function' ? GM_getValue : undefined,
    typeof GM_setValue === 'function' ? GM_setValue : undefined,
    typeof GM_addValueChangeListener === 'function' ? GM_addValueChangeListener : undefined,
    typeof GM_openInTab === 'function' ? GM_openInTab : undefined,
    page
  );
}

function showBanner(text) {
  try {
    const box = document.createElement('div');
    box.id = 'aipilot-loader-banner';
    box.style.cssText = 'position:fixed;top:8px;left:8px;z-index:2147483647;padding:8px 12px;'
      + 'background:#0c0e12;color:#e6e4df;border:1px solid #a8976a;border-radius:8px;'
      + 'font:12px Segoe UI,sans-serif;max-width:50vw';
    box.textContent = text;
    document.documentElement.appendChild(box);
  } catch (error) {
    console.warn('[AIPilot]', text);
  }
}

async function boot() {
  applyConfig();
  try {
    const coreSrc = await loadFile(CORE_FILE);
    runInGm(coreSrc, CORE_FILE);
    const core = lookup('AIPilotCore');
    if (!core || typeof core.start !== 'function') {
      showBanner('AIPilot: ядро не выполнилось в Violentmonkey');
      return;
    }
    publish('AIPilotCore', core);
    if (location.host === new URL(API_BASE).host) {
      core.start(null);
      return;
    }
    const uiSrc = await loadFile(UI_FILE);
    runInGm(uiSrc, UI_FILE);
    const adapter = lookup(ADAPTER);
    if (!adapter) {
      showBanner('AIPilot: адаптер ' + ADAPTER + ' не загрузился');
      core.start(null);
      return;
    }
    publish(ADAPTER, adapter);
    core.start(adapter);
  } catch (error) {
    const message = 'AIPilot loader: ' + formatHttpError(error, API_BASE);
    console.error('[AIPilot]', message);
    showBanner(message);
  }
}

boot();
