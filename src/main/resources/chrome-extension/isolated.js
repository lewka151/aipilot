// Isolated-world proxy: privileged fetch, chrome.storage, openTab.
(function () {
  'use strict';

  const CHANNEL = 'aipilot-chrome';

  function reply(id, ok, payload) {
    window.postMessage({
      channel: CHANNEL,
      dir: 'to-page',
      id: id,
      ok: ok,
      payload: payload,
    }, '*');
  }

  async function doHttp(payload) {
    const ctrl = typeof AbortController === 'function' ? new AbortController() : null;
    const timer = ctrl ? setTimeout(function () { ctrl.abort(); }, payload.timeoutMs || 10000) : null;
    try {
      const res = await fetch(payload.url, {
        method: payload.method || 'GET',
        headers: payload.headers || {},
        body: payload.body == null ? undefined : JSON.stringify(payload.body),
        credentials: 'omit',
        signal: ctrl ? ctrl.signal : undefined,
      });
      const responseText = await res.text();
      return { status: res.status, responseText: responseText };
    } finally {
      if (timer) clearTimeout(timer);
    }
  }

  window.addEventListener('message', function (event) {
    if (event.source !== window || !event.data || event.data.channel !== CHANNEL || event.data.dir !== 'to-ext') {
      return;
    }
    const id = event.data.id;
    const op = event.data.op;
    const payload = event.data.payload || {};
    Promise.resolve().then(function () {
      if (op === 'http') return doHttp(payload);
      if (op === 'storageGet') {
        return new Promise(function (resolve) {
          if (payload.keys == null) chrome.storage.local.get(resolve);
          else chrome.storage.local.get(payload.keys, resolve);
        });
      }
      if (op === 'storageSet') {
        return new Promise(function (resolve, reject) {
          chrome.storage.local.set(payload.values || {}, function () {
            const err = chrome.runtime.lastError;
            if (err) reject(new Error(err.message));
            else resolve({});
          });
        });
      }
      if (op === 'openTab') {
        return new Promise(function (resolve) {
          chrome.runtime.sendMessage({ type: 'openTab', url: payload.url }, function () {
            resolve({});
          });
        });
      }
      throw new Error('unknown op ' + op);
    }).then(function (result) {
      reply(id, true, result);
    }).catch(function (error) {
      var message = (error && error.message && String(error.message) !== '[object Object]')
        ? String(error.message)
        : (typeof error === 'string' && error !== '[object Object]' ? error : 'request failed');
      reply(id, false, message);
    });
  });

  function pushInit() {
    chrome.storage.local.get(null, function (data) {
      window.postMessage({
        channel: CHANNEL,
        dir: 'to-page',
        op: 'init',
        payload: data || {},
      }, '*');
    });
  }

  chrome.storage.onChanged.addListener(function (changes, area) {
    if (area !== 'local') return;
    const payload = {};
    Object.keys(changes).forEach(function (key) {
      payload[key] = changes[key].newValue;
    });
    window.postMessage({
      channel: CHANNEL,
      dir: 'to-page',
      op: 'storageChanged',
      payload: payload,
    }, '*');
  });

  pushInit();
})();
