chrome.runtime.onInstalled.addListener(function () {
  seedConfig();
});

chrome.runtime.onStartup.addListener(function () {
  seedConfig();
});

function seedConfig() {
  fetch(chrome.runtime.getURL('config.json'))
    .then(function (res) { return res.json(); })
    .then(function (cfg) {
      chrome.storage.local.get(['apiBase', 'token'], function (cur) {
        const patch = {};
        if (!cur.apiBase && cfg.apiBase) patch.apiBase = cfg.apiBase;
        if (!cur.token && cfg.token) patch.token = cfg.token;
        if (cfg.token && !cur.token) patch['aipilot.bridgeToken'] = cfg.token;
        if (Object.keys(patch).length) chrome.storage.local.set(patch);
      });
    })
    .catch(function () { /* config.json is written on export */ });
}

chrome.runtime.onMessage.addListener(function (msg, sender, sendResponse) {
  if (msg && msg.type === 'openTab' && msg.url) {
    chrome.tabs.create({ url: msg.url, active: true });
    sendResponse({ ok: true });
    return;
  }
  if (msg && msg.type === 'getConfig') {
    chrome.storage.local.get(['apiBase', 'token'], sendResponse);
    return true;
  }
});
