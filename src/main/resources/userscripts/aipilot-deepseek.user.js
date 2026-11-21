// ==UserScript==
// @name         AIPilot — DeepSeek loader
// @namespace    https://aipilot.local/deepseek
// @version      1.9.30
// @description  DeepSeek bridge: live JS from the AIPilot server, bundled fallback if the site returns HTML
// @match        https://chat.deepseek.com/*
// @match        https://__API_CONNECT__/*
// @match        http://__API_CONNECT__/*
// @connect      __API_CONNECT__
// @connect      *
// @grant        GM_xmlhttpRequest
// @grant        GM.xmlHttpRequest
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_openInTab
// @grant        GM_addValueChangeListener
// @grant        GM_addElement
// @grant        GM.addElement
// @grant        unsafeWindow
// @run-at       document-idle
// ==/UserScript==

__VM_LOADER__
