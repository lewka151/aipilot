package com.aipilot.infra.api;

import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.http.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/** Site151 bridge hub: app and userscripts exchange jobs via HTTP API. */
public final class SiteBridgeClient {
    private static final Logger log = LoggerFactory.getLogger(SiteBridgeClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AppConfig config;
    private final AuthManager auth;
    private final HttpClient http;

    public SiteBridgeClient(AppConfig config, AuthManager auth) {
        this.config = config;
        this.auth = auth;
        this.http = HttpClients.defaultClient(config);
    }

    public void registerToken() throws Exception {
        Session s = requireSession();
        ObjectNode body = MAPPER.createObjectNode();
        String token = config.bridgeToken == null ? "" : config.bridgeToken;
        body.put("token", token);
        if (config.deviceKey != null && !config.deviceKey.isBlank()) {
            body.put("device_id", config.deviceKey.trim().toLowerCase());
        }
        postJson("/api/aipilot/bridge/token", body, s, token);
    }

    public void registerPresence(String provider, String version, boolean ready) throws Exception {
        if (config.bridgeToken == null || config.bridgeToken.isBlank()) {
            config.bridgeToken = java.util.UUID.randomUUID().toString();
        }
        try {
            registerToken();
        } catch (Exception e) {
            log.debug("bridge token bind: {}", e.getMessage());
        }
        Session s = requireSession();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("provider", provider == null ? "" : provider);
        body.put("version", version == null ? "" : version);
        body.put("ready", ready);
        postJson("/api/aipilot/bridge/register", body, s, config.bridgeToken);
    }

    public String publishScripts(String version, String core, String qwenUi, String deepseekUi)
            throws Exception {
        Session s = requireSession();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("version", version == null ? "" : version);
        ObjectNode files = body.putObject("files");
        files.put("bridge-core.js", core == null ? "" : core);
        files.put("qwen-ui.js", qwenUi == null ? "" : qwenUi);
        files.put("deepseek-ui.js", deepseekUi == null ? "" : deepseekUi);
        JsonNode resp = postJson("/api/aipilot/bridge/scripts", body, s, config.bridgeToken);
        if (resp != null && resp.has("url")) {
            return resp.get("url").asText();
        }
        if (resp != null && resp.has("base")) {
            return resp.get("base").asText();
        }
        return "ok";
    }

    public record ProviderStatus(boolean ready, String state, String detail) {
    }

    public ProviderStatus providerStatus(String provider) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return new ProviderStatus(false, "unauthorized", "Требуется вход");
        }
        try {
            JsonNode resp = getJson("/api/aipilot/bridge/status", s);
            JsonNode p = resp.get(provider);
            if (p == null || !p.has("ready")) {
                return new ProviderStatus(false, "disconnected", "Provider отсутствует в status");
            }
            boolean ready = p.get("ready").asBoolean(false);
            return new ProviderStatus(ready, ready ? "ready" : "disconnected",
                    ready ? "Userscript подключён" : "Нет активного userscript");
        } catch (Exception e) {
            log.warn("bridge status failed for {}: {}", provider, e.getMessage());
            return new ProviderStatus(false, "error", e.getMessage());
        }
    }

    public boolean isProviderReady(String provider) {
        return providerStatus(provider).ready();
    }

    public List<String> activityFeed(String provider, int max) {
        try {
            Session s = auth.currentSession();
            if (s == null || !s.isAuthenticated()) {
                return List.of();
            }
            String path = "/api/aipilot/bridge/activity?provider=" + encode(provider) + "&limit=" + max;
            JsonNode resp = getJson(path, s);
            if (resp == null || !resp.has("lines")) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode line : resp.get("lines")) {
                out.add(line.asText());
            }
            return out;
        } catch (Exception e) {
            log.warn("bridge activity failed for {}: {}", provider, e.getMessage());
            return List.of();
        }
    }

    public record JobOutcome(String text, String chatId) {
        public String textOrEmpty() {
            return text == null ? "" : text;
        }
    }

    public String submitAndWait(BridgeJob job, long timeoutSec) throws Exception {
        return submitAndWaitResult(job, timeoutSec).textOrEmpty();
    }

    public JobOutcome submitAndWaitResult(BridgeJob job, long timeoutSec) throws Exception {
        if (job == null || job.id() == null || job.id().isBlank()
                || job.provider() == null || job.provider().isBlank()
                || job.prompt() == null) {
            throw new IllegalArgumentException("Некорректный bridge job");
        }
        if (!"qwen".equals(job.provider()) && !"deepseek".equals(job.provider())) {
            throw new IllegalArgumentException("Неизвестный provider: " + job.provider());
        }
        if (timeoutSec <= 0) {
            throw new IllegalArgumentException("Timeout должен быть положительным");
        }
        Session s = requireSession();
        ObjectNode body = MAPPER.createObjectNode();
        body.put("id", job.id());
        body.put("provider", job.provider());
        body.put("prompt", job.prompt() == null ? "" : job.prompt());
        if (job.qwenProjectName() != null) {
            body.put("qwenProjectName", job.qwenProjectName());
            body.put("qwen_project_name", job.qwenProjectName());
        }
        if (job.chatTitle() != null) {
            body.put("chatTitle", job.chatTitle());
            body.put("chat_title", job.chatTitle());
        }
        if (job.chatIdOrEmpty() != null && !job.chatIdOrEmpty().isBlank()) {
            body.put("chatId", job.chatIdOrEmpty());
            body.put("chat_id", job.chatIdOrEmpty());
        }
        if (job.projectIdOrEmpty() != null && !job.projectIdOrEmpty().isBlank()) {
            body.put("projectId", job.projectIdOrEmpty());
            body.put("project_id", job.projectIdOrEmpty());
        }
        if (job.projectName() != null) {
            body.put("projectName", job.projectName());
            body.put("project_name", job.projectName());
        }
        body.put("expertMode", job.expertMode());
        body.put("expert_mode", job.expertMode());
        body.put("action", job.actionOrPrompt());
        JsonNode created = postJson("/api/aipilot/bridge/jobs", body, s, null);
        if (created == null || !created.has("id") || created.get("id").asText("").isBlank()) {
            throw new IllegalStateException("Bridge job create failed");
        }
        String jobId = created.get("id").asText();
        long started = System.nanoTime();
        long softDeadline = started + Duration.ofSeconds(timeoutSec).toNanos();
        long hardDeadline = started + Duration.ofHours(2).toNanos();
        while (System.nanoTime() < hardDeadline) {
            JsonNode st = getJson("/api/aipilot/bridge/jobs/" + encode(jobId) + "?wait=25", s);
            if (st != null) {
                String status = text(st, "status");
                if (isDoneStatus(status)) {
                    String answer = firstText(st, "text", "response", "result");
                    String chatId = firstText(st, "chatId", "chat_id");
                    if (answer != null && !answer.isBlank()) {
                        return new JobOutcome(answer, chatId == null ? "" : chatId);
                    }
                    if (System.nanoTime() >= softDeadline) {
                        return new JobOutcome(answer == null ? "" : answer, chatId == null ? "" : chatId);
                    }
                } else if (isErrorStatus(status)) {
                    String err = firstText(st, "error", "message");
                    throw new java.io.IOException(err == null ? "bridge error" : err);
                } else if (status != null && !status.isBlank()
                        && !isPendingStatus(status)) {
                    log.warn("Unknown bridge job status {} for {}", status, jobId);
                }
                if (System.nanoTime() >= softDeadline && !isPendingStatus(status) && !isDoneStatus(status)) {
                    break;
                }
            } else if (System.nanoTime() >= softDeadline) {
                break;
            }
            Thread.sleep(300);
        }
        throw new TimeoutException("Site bridge timeout for " + job.provider());
    }

    private Session requireSession() {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            throw new IllegalStateException("Требуется вход на сайт для bridge API");
        }
        return s;
    }

    private JsonNode getJson(String path, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(35))
                .header("Accept", "application/json")
                .GET();
        applyDesktopHeaders(b, s, null);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(ApiJson.httpError(resp.statusCode(), resp.body()));
        }
        return ApiJson.parse(resp.body());
    }

    private JsonNode postJson(String path, ObjectNode body, Session s, String bridgeToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyDesktopHeaders(b, s, bridgeToken);
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException(ApiJson.httpError(resp.statusCode(), resp.body()));
        }
        return ApiJson.parse(resp.body());
    }

    private void applyDesktopHeaders(HttpRequest.Builder b, Session s, String bridgeToken) {
        if (s != null) {
            b.header("Authorization", "Bearer " + s.apiToken());
        }
        b.header("X-AIPilot-Client", "desktop");
        String device = config.deviceKey == null ? "" : config.deviceKey.trim();
        if (!device.isBlank()) {
            b.header("X-AIPilot-Device-Id", device);
        }
        if (bridgeToken != null && !bridgeToken.isBlank()) {
            b.header("X-AIPilot-Token", bridgeToken);
        }
    }

    private static String text(JsonNode n, String key) {
        if (n == null) {
            return null;
        }
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String firstText(JsonNode n, String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(n, key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return text(n, keys.length == 0 ? "text" : keys[0]);
    }

    private static boolean isDoneStatus(String status) {
        return "done".equals(status) || "completed".equals(status) || "ok".equals(status);
    }

    private static boolean isErrorStatus(String status) {
        return "error".equals(status) || "failed".equals(status);
    }

    private static boolean isPendingStatus(String status) {
        return "pending".equals(status)
                || "processing".equals(status)
                || "in_flight".equals(status)
                || "running".equals(status)
                || "in_progress".equals(status);
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
