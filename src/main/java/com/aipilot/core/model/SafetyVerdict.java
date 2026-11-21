package com.aipilot.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SafetyVerdict(boolean safe, String reason) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[\\s\\S]*?\\}");

    public static SafetyVerdict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new SafetyVerdict(false, "Empty safety response");
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            int end = text.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                text = text.substring(nl + 1, end).trim();
            }
        }
        SafetyVerdict line = parseLastLine(text);
        if (line != null) {
            return line;
        }
        try {
            JsonNode n = MAPPER.readTree(text);
            return fromNode(n);
        } catch (Exception ignored) {
        }
        Matcher m = JSON_OBJECT.matcher(text);
        if (m.find()) {
            try {
                return fromNode(MAPPER.readTree(m.group()));
            } catch (Exception ignored) {
            }
        }
        return new SafetyVerdict(false, "Нет APPROVED: " + abbreviate(raw));
    }

    private static SafetyVerdict parseLastLine(String text) {
        String[] lines = text.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isBlank()) {
                continue;
            }
            if ("APPROVED".equalsIgnoreCase(line)) {
                return new SafetyVerdict(true, "APPROVED");
            }
            if (line.toUpperCase(Locale.ROOT).startsWith("DENY")) {
                String reason = line.length() > 4 && line.charAt(4) == ':'
                        ? line.substring(5).trim()
                        : line;
                if (reason.isBlank()) {
                    reason = "DENY";
                }
                return new SafetyVerdict(false, reason);
            }
            break;
        }
        return null;
    }

    private static SafetyVerdict fromNode(JsonNode n) {
        boolean safe = n.has("safe") && n.get("safe").asBoolean(false);
        String reason = n.has("reason") ? n.get("reason").asText("") : "";
        return new SafetyVerdict(safe, reason);
    }

    private static String abbreviate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 117) + "...";
    }
}
