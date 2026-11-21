package com.aipilot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Direct chat URLs and UUID detection for Qwen / DeepSeek. Never treat a title as an id. */
public final class ChatUrls {
    public static final String QWEN_BASE = "https://chat.qwen.ai/c/";
    public static final String DEEPSEEK_BASE = "https://chat.deepseek.com/a/chat/s/";

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{16,80}$");
    private static final Pattern QWEN_PATH = Pattern.compile("/c/([A-Za-z0-9_-]+)");
    private static final Pattern DEEPSEEK_PATH = Pattern.compile("/a/chat/s/([A-Za-z0-9_-]+)");

    private ChatUrls() {
    }

    public static boolean looksLikeId(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('.') >= 0) {
            return false;
        }
        if (UUID.matcher(s).matches()) {
            return true;
        }
        return TOKEN.matcher(s).matches();
    }

    public static String normalize(String value) {
        String s = value == null ? "" : value.trim();
        return looksLikeId(s) ? s : "";
    }

    public static String parseQwen(String urlOrPath) {
        return firstGroup(QWEN_PATH, urlOrPath);
    }

    public static String parseDeepSeek(String urlOrPath) {
        return firstGroup(DEEPSEEK_PATH, urlOrPath);
    }

    public static String parse(String provider, String urlOrPath) {
        if ("deepseek".equalsIgnoreCase(provider)) {
            return parseDeepSeek(urlOrPath);
        }
        return parseQwen(urlOrPath);
    }

    public static String qwenUrl(String id) {
        String n = normalize(id);
        return n.isEmpty() ? "" : QWEN_BASE + n;
    }

    public static String deepSeekUrl(String id) {
        String n = normalize(id);
        return n.isEmpty() ? "" : DEEPSEEK_BASE + n;
    }

    public static String url(String provider, String id) {
        if ("deepseek".equalsIgnoreCase(provider)) {
            return deepSeekUrl(id);
        }
        return qwenUrl(id);
    }

    private static String firstGroup(Pattern pattern, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Matcher m = pattern.matcher(raw);
        if (m.find() && looksLikeId(m.group(1))) {
            return m.group(1);
        }
        String trimmed = raw.trim();
        return looksLikeId(trimmed) ? trimmed : "";
    }

    public static String providerLabel(String provider) {
        if (provider == null) {
            return "";
        }
        return provider.toLowerCase(Locale.ROOT);
    }
}
