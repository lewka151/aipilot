const apiBaseEl = document.getElementById('apiBase');
const tokenEl = document.getElementById('token');
const statusEl = document.getElementById('status');

function setStatus(text, isError) {
  statusEl.textContent = text || '';
  statusEl.className = 'status' + (isError ? ' error' : '');
}

function originPattern(url) {
  try {
    const parsed = new URL(url);
    return parsed.origin + '/*';
  } catch (error) {
    return '';
  }
}

chrome.storage.local.get(['apiBase', 'token'], function (data) {
  if (data.apiBase) apiBaseEl.value = data.apiBase;
  if (data.token) tokenEl.value = data.token;
  if (!data.apiBase) {
    fetch(chrome.runtime.getURL('config.json'))
      .then(function (res) { return res.json(); })
      .then(function (cfg) {
        if (!apiBaseEl.value && cfg.apiBase) apiBaseEl.value = cfg.apiBase;
        if (!tokenEl.value && cfg.token) tokenEl.value = cfg.token;
      })
      .catch(function () { /* ignore */ });
  }
});

document.getElementById('save').addEventListener('click', function () {
  const apiBase = (apiBaseEl.value || '').trim().replace(/\/+$/, '');
  const token = (tokenEl.value || '').trim();
  const pattern = originPattern(apiBase);
  const values = {
    apiBase: apiBase,
    token: token,
    'aipilot.bridgeToken': token,
  };
  function persist() {
    chrome.storage.local.set(values, function () {
      setStatus('Сохранено. Обновите вкладки Qwen и DeepSeek.');
    });
  }
  if (pattern && chrome.permissions && chrome.permissions.request) {
    chrome.permissions.request({ origins: [pattern] }, function () {
      persist();
    });
  } else {
    persist();
  }
});

document.getElementById('openQwen').addEventListener('click', function () {
  chrome.tabs.create({ url: 'https://chat.qwen.ai/', active: true });
});
document.getElementById('openDeepseek').addEventListener('click', function () {
  chrome.tabs.create({ url: 'https://chat.deepseek.com/', active: true });
});
