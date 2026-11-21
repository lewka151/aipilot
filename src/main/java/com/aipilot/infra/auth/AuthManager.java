package com.aipilot.infra.auth;

import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.infra.config.SecureFiles;
import com.aipilot.infra.http.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class AuthManager {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path authFile;
    private final AppConfig config;
    private Session session;

    public AuthManager(AppPaths paths, AppConfig config) {
        this.authFile = paths.authFile();
        this.config = config;
        load();
    }

    public synchronized Session currentSession() {
        return session;
    }

    public synchronized void load() {
        if (!Files.isRegularFile(authFile)) {
            session = null;
            return;
        }
        try {
            JsonNode n = MAPPER.readTree(Files.readString(authFile, StandardCharsets.UTF_8));
            String token = text(n, "apiToken");
            Integer uid = n.has("userId") && !n.get("userId").isNull() ? n.get("userId").asInt() : null;
            String name = text(n, "userName");
            boolean admin = n.has("admin") && n.get("admin").asBoolean(false);
            session = new Session(token, uid, name, admin);
            if (session.isAuthenticated()) {
                refreshAdminFlag();
            }
        } catch (Exception e) {
            session = null;
        }
    }

    public void signInInteractive(String tokenLabel) throws Exception {
        String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
        if (base.isEmpty()) {
            throw new IllegalStateException("Укажите адрес сайта");
        }
        try (DesktopAuthFlow flow = new DesktopAuthFlow()) {
            String code = flow.startAndWait(base, 180);
            JsonNode data = exchange(base, code, tokenLabel, config);
            String token = data.get("api_token").asText();
            JsonNode u = data.get("user");
            int uid = u.get("id").asInt();
            String uname = u.has("name") && !u.get("name").isNull() ? u.get("name").asText("") : "";
            boolean admin = u.has("is_admin") && u.get("is_admin").asBoolean(false);
            save(token, uid, uname, admin);
            refreshAdminFlag();
        }
    }

    public synchronized void signOut() {
        String token = session != null ? session.apiToken() : null;
        String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
        if (token != null && !token.isBlank() && !base.isEmpty()) {
            try {
                HttpClient http = HttpClients.defaultClient(config);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/auth/desktop/revoke"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                        .build();
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
        session = null;
        try {
            Files.deleteIfExists(authFile);
        } catch (Exception ignored) {
        }
    }

    private void refreshAdminFlag() {
        Session s = session;
        if (s == null || !s.isAuthenticated()) {
            return;
        }
        try {
            String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
            HttpClient http = HttpClients.defaultClient(config);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/auth/me"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + s.apiToken())
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode body = MAPPER.readTree(resp.body());
            JsonNode data = body.has("data") ? body.get("data") : body;
            JsonNode user = data.has("user") ? data.get("user") : data;
            boolean admin = user.has("is_admin") && user.get("is_admin").asBoolean(false);
            String name = user.has("name") ? user.get("name").asText(s.userName()) : s.userName();
            Integer uid = user.has("id") ? user.get("id").asInt() : s.userId();
            save(s.apiToken(), uid, name, admin);
        } catch (Exception ignored) {
            // keep stored admin flag
        }
    }

    private synchronized void save(String token, Integer userId, String userName, boolean admin) throws Exception {
        session = new Session(token, userId, userName, admin);
        ObjectNode n = MAPPER.createObjectNode();
        n.put("apiToken", token);
        if (userId != null) {
            n.put("userId", userId);
        }
        n.put("userName", userName != null ? userName : "");
        n.put("admin", admin);
        Files.createDirectories(authFile.getParent());
        Files.writeString(authFile, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n",
                StandardCharsets.UTF_8);
        SecureFiles.restrictToOwner(authFile);
    }

    private static JsonNode exchange(String apiBaseUrl, String code, String label, AppConfig config) throws Exception {
        HttpClient http = HttpClients.defaultClient(config);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("code", code);
        payload.put("label", label != null && !label.isBlank() ? label : "aipilot");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/api/auth/desktop/exchange"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode body = MAPPER.readTree(resp.body());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300
                || !body.has("ok") || !body.get("ok").asBoolean()) {
            String err = body.has("error") ? body.get("error").asText() : ("HTTP " + resp.statusCode());
            throw new RuntimeException(err);
        }
        return body.has("data") ? body.get("data") : body;
    }

    private static String text(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText("") : "";
    }
}
