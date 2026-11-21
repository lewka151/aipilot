package com.aipilot.infra.api;

import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.http.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class AdminApiClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private final AuthManager auth;

    public AdminApiClient(AppConfig config, AuthManager auth) {
        this.config = config;
        this.auth = auth;
    }

    public JsonNode listPrompts(int page, String projectId) throws Exception {
        String q = "page=" + page;
        if (projectId != null && !projectId.isBlank()) {
            q += "&project_id=" + URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        }
        return get("/api/admin/prompts?" + q);
    }

    public JsonNode listHistory(int page, String projectId) throws Exception {
        String q = "page=" + page;
        if (projectId != null && !projectId.isBlank()) {
            q += "&project_id=" + URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        }
        return get("/api/admin/history?" + q);
    }

    public String triggerCleanup() throws Exception {
        JsonNode n = post("/api/admin/cleanup", MAPPER.createObjectNode());
        return n.has("message") ? n.get("message").asText() : n.toString();
    }

    public String fileSync(String projectId, String kind, String content) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("project_id", projectId);
        body.put("kind", kind);
        body.put("content", content);
        JsonNode n = post("/api/admin/file-sync", body);
        return n.has("message") ? n.get("message").asText() : n.toString();
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = authorized(path).GET().build();
        return send(req);
    }

    private JsonNode post(String path, JsonNode body) throws Exception {
        HttpRequest req = authorized(path)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(req);
    }

    private HttpRequest.Builder authorized(String path) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            throw new IllegalStateException("Требуется авторизация");
        }
        if (!s.isAdmin()) {
            throw new IllegalStateException("Требуется роль администратора");
        }
        String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
        return HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + s.apiToken());
    }

    private JsonNode send(HttpRequest req) throws Exception {
        HttpClient http = HttpClients.defaultClient(config);
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = MAPPER.readTree(resp.body().isBlank() ? "{}" : resp.body());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String err = body.has("error") ? body.get("error").asText() : ("HTTP " + resp.statusCode());
            throw new RuntimeException(err);
        }
        return body.has("data") ? body.get("data") : body;
    }
}
