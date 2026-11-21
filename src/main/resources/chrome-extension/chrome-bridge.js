// MAIN-world shims: HTTP via isolated content script, GM storage, openTab.
(function (global) {
  'use strict';

  const CHANNEL = 'aipilot-chrome';
  let seq = 0;
  const pending = {};
  const cache = {};
  const listeners = [];

  if (global.AIPILOT_CONFIG && typeof global.AIPILOT_CONFIG === 'object') {
    if (global.AIPILOT_CONFIG.token) {
      cache['aipilot.bridgeToken'] = String(global.AIPILOT_CONFIG.token);
      cache.token = String(global.AIPILOT_CONFIG.token);
    }
    if (global.AIPILOT_CONFIG.apiBase) {
      cache.apiBase = String(global.AIPILOT_CONFIG.apiBase);
    }
  }

  function applyStorage(data) {
    if (!data || typeof data !== 'object') return;
    Object.keys(data).forEach(function (key) {
      if (data[key] != null) cache[key] = data[key];
    });
    if (data.token && !data['aipilot.bridgeToken']) {
      cache['aipilot.bridgeToken'] = data.token;
    }
    if (data.apiBase && global.AIPILOT_CONFIG) {
      global.AIPILOT_CONFIG.apiBase = data.apiBase;
    }
    if ((data.token || data['aipilot.bridgeToken']) && global.AIPILOT_CONFIG) {
      global.AIPILOT_CONFIG.token = data.token || data['aipilot.bridgeToken'];
    }
  }

  window.addEventListener('message', function (event) {
    if (event.source !== window || !event.data || event.data.channel !== CHANNEL || event.data.dir !== 'to-page') {
      return;
    }
    if (event.data.op === 'init' || event.data.op === 'storageChanged') {
      applyStorage(event.data.payload);
      listeners.forEach(function (fn) {
        try { fn(event.data.payload || {}); } catch (e) { /* ignore */ }
      });
      return;
    }
    const waiter = pending[event.data.id];
    if (!waiter) return;
    delete pending[event.data.id];
    if (event.data.ok) waiter.resolve(event.data.payload);
    else {
      const payload = event.data.payload;
      let msg = '';
      if (typeof payload === 'string') msg = payload;
      else if (payload && typeof payload === 'object') {
        if (typeof payload.message === 'string') msg = payload.message;
        else if (typeof payload.error === 'string') msg = payload.error;
        else {
          try { msg = JSON.stringify(payload); } catch (e) { msg = ''; }
        }
      }
      if (!msg || msg === '[object Object]') msg = 'bridge error';
      waiter.reject(new Error(msg));
    }
  });

  function rpc(op, payload, timeoutMs) {
    return new Promise(function (resolve, reject) {
      const id = ++seq;
      const timer = setTimeout(function () {
        delete pending[id];
        reject(new Error('timeout'));
      }, timeoutMs || 15000);
      pending[id] = {
        resolve: function (value) { clearTimeout(timer); resolve(value); },
        reject: function (error) { clearTimeout(timer); reject(error); },
      };
      window.postMessage({
        channel: CHANNEL,
        dir: 'to-ext',
        id: id,
        op: op,
        payload: payload,
      }, '*');
    });
  }

  global.__AIPILOT_BRIDGE_HTTP__ = function (req) {
    return rpc('http', req, (req && req.timeoutMs ? req.timeoutMs : 10000) + 2000);
  };

  global.GM_getValue = function (key, fallback) {
    const value = cache[key];
    if (value == null || value === '') return fallback;
    return value;
  };

  global.GM_setValue = function (key, value) {
    cache[key] = value;
    const values = {};
    values[key] = value;
    if (key === 'aipilot.bridgeToken') values.token = value;
    rpc('storageSet', { values: values }).catch(function () { /* storage is best-effort */ });
  };

  global.GM_openInTab = function (url) {
    rpc('openTab', { url: url }).catch(function () {
      window.open(url, '_blank');
    });
  };

  global.GM_addValueChangeListener = function (key, fn) {
    listeners.push(function (changes) {
      if (Object.prototype.hasOwnProperty.call(changes, key)) {
        fn(key, cache[key], changes[key], true);
      }
    });
  };

  rpc('storageGet', { keys: null }).then(applyStorage).catch(function () { /* baked config remains */ });
})(typeof window !== 'undefined' ? window : this);
