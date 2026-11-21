package com.aipilot.infra.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Shared site API JSON: `{ok,data}` envelopes and readable HTTP errors. */
public final class ApiJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiJson() {
    }

    public static JsonNode parse(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return MAPPER.readTree("{}");
        }
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            throw new IllegalStateException("Non-JSON: " + snippet(body));
        }
        return unwrap(MAPPER.readTree(trimmed));
    }

    public static JsonNode unwrap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        if (node.has("ok") && node.get("ok").isBoolean() && !node.get("ok").asBoolean()) {
            throw new IllegalStateException(message(node));
        }
        if (node.has("ok") && node.has("data") && !node.get("data").isNull()
                && !node.get("data").isMissingNode()) {
            return node.get("data");
        }
        return node;
    }

    public static String message(JsonNode node) {
        if (node == null) {
            return "API error";
        }
        if (node.has("error")) {
            JsonNode error = node.get("error");
            if (error.isTextual() && !error.asText().isBlank()) {
                return error.asText();
            }
            if (error.isObject()) {
                if (error.has("message") && error.get("message").isTextual()) {
                    return error.get("message").asText();
                }
                if (error.has("code") && error.get("code").isTextual()) {
                    return error.get("code").asText();
                }
            }
        }
        if (node.has("message") && node.get("message").isTextual()
                && !node.get("message").asText().isBlank()) {
            return node.get("message").asText();
        }
        return "API error";
    }

    public static String httpError(int status, String body) {
        String snip = snippet(body);
        return snip.isEmpty() ? "HTTP " + status : "HTTP " + status + ": " + snip;
    }

    public static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        if (text.length() > 240) {
            return text.substring(0, 240) + "…";
        }
        return text;
    }
}
