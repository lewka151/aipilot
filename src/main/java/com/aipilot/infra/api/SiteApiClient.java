package com.aipilot.infra.api;

import com.aipilot.core.ChatUrls;
import com.aipilot.core.ProjectFolders;
import com.aipilot.core.model.FileEditProposal;
import com.aipilot.core.model.Project;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.SecureFiles;
import com.aipilot.infra.http.HttpClients;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** General site API helpers (prompt/history logging, run/step timeline). */
public final class SiteApiClient {
    private static final Logger log = LoggerFactory.getLogger(SiteApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private final AuthManager auth;
    private final AppPaths paths;

    public SiteApiClient(AppConfig config, AuthManager auth) {
        this(config, auth, AppPaths.resolve());
    }

    public SiteApiClient(AppConfig config, AuthManager auth, AppPaths paths) {
        this.config = config;
        this.auth = auth;
        this.paths = paths;
    }

    public static final class RunSession {
        private final long runId;
        private final Instant startedAt;
        private final AtomicInteger seq = new AtomicInteger(0);

        RunSession(long runId, Instant startedAt) {
            this.runId = runId;
            this.startedAt = startedAt;
        }

        public long runId() {
            return runId;
        }

        public Instant startedAt() {
            return startedAt;
        }

        public int nextSeq() {
            return seq.incrementAndGet();
        }
    }

    public RunSession startRun(String projectId, String goal, String mode) {
        return startRun(projectId, null, goal, mode, List.of());
    }

    public RunSession startRun(String projectId, String projectName, String goal, String mode,
                               List<com.aipilot.core.model.ProjectPath> aliases) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return null;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("project_name", projectName == null ? "" : projectName);
            body.put("goal", goal == null ? "" : goal);
            body.put("mode", mode == null ? "projects" : mode);
            if (aliases != null && !aliases.isEmpty()) {
                var arr = MAPPER.createArrayNode();
                for (var alias : aliases) {
                    if (alias == null || alias.path == null || alias.path.isBlank() || ".".equals(alias.path)) {
                        continue;
                    }
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("path", alias.path);
                    item.put("name", alias.name != null && !alias.name.isBlank() ? alias.name : alias.role);
                    arr.add(item);
                }
                body.set("aliases", arr);
            }
            JsonNode resp = postJson("/api/aipilot/runs", body, s);
            if (resp != null && resp.has("run_id")) {
                return new RunSession(resp.get("run_id").asLong(), Instant.now());
            }
        } catch (Exception e) {
            log.warn("start run failed: {}", safeMessage(e));
        }
        return null;
    }

    public boolean logStep(RunSession session, String provider, String phase, String prompt, String response,
                           long durationMs) {
        return logStep(session, provider, phase, prompt, response, durationMs, null);
    }

    public boolean logStep(RunSession session, String provider, String phase, String prompt, String response,
                           long durationMs, String mode) {
        if (session == null) {
            return false;
        }
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("seq", session.nextSeq());
            body.put("provider", provider == null ? "" : provider);
            body.put("phase", phase == null ? "" : phase);
            body.put("prompt", prompt == null ? "" : prompt);
            body.put("response", response == null ? "" : response);
            body.put("duration_ms", Math.max(0L, durationMs));
            body.put("mode", mode == null ? "" : mode);
            postJson("/api/aipilot/runs/" + session.runId() + "/steps", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log step failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean finishRun(RunSession session, String status, long totalDurationMs) {
        if (session == null) {
            return false;
        }
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("status", status == null ? "done" : status);
            body.put("duration_ms", Math.max(0L, totalDurationMs));
            patchJson("/api/aipilot/runs/" + session.runId(), body, s);
            return true;
        } catch (Exception e) {
            log.warn("finish run failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean logPrompt(String projectId, String provider, String prompt, String response) {
        return logPrompt(projectId, provider, prompt, response, 0L, 0L, 0, 0);
    }

    public boolean logPrompt(String projectId, String provider, String prompt, String response,
                             long elapsedMs, long stepMs, int promptChars, int responseChars) {
        return logPrompt(projectId, null, provider, prompt, response, elapsedMs, stepMs,
                promptChars, responseChars, null);
    }

    public boolean logPrompt(String projectId, String projectName, String provider, String prompt, String response,
                             long elapsedMs, long stepMs, int promptChars, int responseChars, String mode) {
        return logPrompt(projectId, projectName, provider, prompt, response, elapsedMs, stepMs,
                promptChars, responseChars, mode, null);
    }

    public boolean logPrompt(String projectId, String projectName, String provider, String prompt, String response,
                             long elapsedMs, long stepMs, int promptChars, int responseChars, String mode,
                             String phase) {
        Session s = auth.currentSession();
        if (s != null && s.userId() != null) {
            writeLocalAudit(s.userId(), projectId, provider, prompt, response);
        }
        try {
            if (s == null || !s.isAuthenticated()) {
                return false;
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("project_name", projectName == null ? "" : projectName);
            body.put("provider", provider == null ? "" : provider);
            body.put("prompt", prompt == null ? "" : prompt);
            body.put("response", response == null ? "" : response);
            body.put("elapsed_ms", Math.max(0L, elapsedMs));
            body.put("step_ms", Math.max(0L, stepMs));
            body.put("prompt_chars", Math.max(0, promptChars));
            body.put("response_chars", Math.max(0, responseChars));
            body.put("mode", mode == null ? "" : mode);
            body.put("phase", phase == null ? "" : phase);
            postJson("/api/aipilot/history", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log prompt failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean logChatMetrics(String projectId, String role, int chars, long elapsedMs, long stepMs) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("kind", role == null ? "user" : role);
            body.put("prompt_chars", Math.max(0, chars));
            body.put("elapsed_ms", Math.max(0L, elapsedMs));
            body.put("step_ms", Math.max(0L, stepMs));
            postJson("/api/aipilot/metrics", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log metrics failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean pingSync() {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("client", "aipilot-desktop");
            body.put("ts", Instant.now().toString());
            postJson("/api/aipilot/sync", body, s);
            return true;
        } catch (Exception e) {
            log.warn("sync ping failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean logBridgeStatus(String projectId, String provider, boolean ready, String detail) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("kind", "bridge_status");
            body.put("message", provider + " " + (ready ? "connected" : "waiting"));
            body.set("payload", MAPPER.valueToTree(Map.of(
                    "provider", provider == null ? "" : provider,
                    "ready", ready,
                    "detail", detail == null ? "" : detail
            )));
            postJson("/api/aipilot/events", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log bridge status failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean logBridgeActivity(String projectId, String provider, String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("kind", "bridge_activity");
            body.put("message", line);
            body.set("payload", MAPPER.valueToTree(Map.of(
                    "provider", provider == null ? "" : provider
            )));
            postJson("/api/aipilot/events", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log bridge activity failed: {}", safeMessage(e));
            return false;
        }
    }

    public boolean logEvent(String projectId, Long runId, String kind, String message, Map<String, ?> payload) {
        Session s = auth.currentSession();
        if (s != null && s.userId() != null) {
            writeLocalEvent(s.userId(), projectId, runId, kind, message, payload);
        }
        try {
            if (s == null || !s.isAuthenticated()) {
                return false;
            }
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            if (runId != null && runId > 0) {
                body.put("run_id", runId);
            }
            body.put("kind", kind == null ? "system" : kind);
            body.put("message", message == null ? "" : message);
            if (payload != null && !payload.isEmpty()) {
                body.set("payload", MAPPER.valueToTree(payload));
            }
            postJson("/api/aipilot/events", body, s);
            return true;
        } catch (Exception e) {
            log.warn("log event failed: {}", safeMessage(e));
            return false;
        }
    }

    public Map<String, String> fetchDocsBundle(String projectId) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return Map.of();
        }
        try {
            String q = URLEncoder.encode(projectId == null ? "" : projectId, StandardCharsets.UTF_8);
            JsonNode resp = getJson("/api/aipilot/docs/bundle?project_id=" + q, s);
            JsonNode docs = resp == null ? null : resp.get("docs");
            if (docs == null || !docs.isObject()) {
                return Map.of();
            }
            Map<String, String> out = new LinkedHashMap<>();
            docs.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText("")));
            return out;
        } catch (Exception e) {
            log.warn("docs bundle failed: {}", safeMessage(e));
            return Map.of();
        }
    }

    public boolean putDoc(String projectId, String kind, String content) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated() || kind == null || kind.isBlank()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("content", content == null ? "" : content);
            putJson("/api/aipilot/docs/" + URLEncoder.encode(kind, StandardCharsets.UTF_8), body, s);
            return true;
        } catch (Exception e) {
            log.warn("docs put failed: {}", safeMessage(e));
            return false;
        }
    }

    public String assemblePrompt(String projectId, String role, String text, String projectName, String chatTitle) {
        return assemblePrompt(projectId, role, text, projectName, chatTitle, null);
    }

    public String assemblePrompt(String projectId, String role, String text, String projectName, String chatTitle,
                                 Map<String, String> extras) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return null;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", projectId == null ? "" : projectId);
            body.put("role", role == null ? "" : role);
            body.put("text", text == null ? "" : text);
            if (projectName != null && !projectName.isBlank()) {
                body.put("project_name", projectName);
            }
            if (chatTitle != null && !chatTitle.isBlank()) {
                body.put("chat_title", chatTitle);
            }
            if (extras != null && !extras.isEmpty()) {
                ObjectNode extraNode = MAPPER.createObjectNode();
                extras.forEach((k, v) -> {
                    if (k != null && !k.isBlank() && v != null && !v.isBlank()) {
                        extraNode.put(k, v);
                    }
                });
                if (!extraNode.isEmpty()) {
                    body.set("extras", extraNode);
                }
            }
            JsonNode resp = postJson("/api/aipilot/docs/prompt", body, s);
            if (resp == null) {
                return null;
            }
            JsonNode prompt = resp.get("prompt");
            if (prompt == null || prompt.isNull()) {
                JsonNode data = resp.get("data");
                if (data != null) {
                    prompt = data.get("prompt");
                }
            }
            if (prompt == null || prompt.isNull()) {
                return null;
            }
            String value = prompt.asText("");
            return value.isBlank() ? null : value;
        } catch (Exception e) {
            log.warn("docs prompt failed: {}", safeMessage(e));
            return null;
        }
    }

    private JsonNode getJson(String path, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        applyDesktopHeaders(b, s);
        HttpResponse<String> resp = HttpClients.defaultClient(config)
                .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(ApiJson.httpError(resp.statusCode(), resp.body()));
        }
        return ApiJson.parse(resp.body());
    }

    private void putJson(String path, ObjectNode body, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyDesktopHeaders(b, s);
        HttpResponse<String> resp = HttpClients.defaultClient(config)
                .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(ApiJson.httpError(resp.statusCode(), resp.body()));
        }
    }

    private JsonNode postJson(String path, ObjectNode body, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyDesktopHeaders(b, s);
        HttpResponse<String> resp = HttpClients.defaultClient(config)
                .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException(ApiJson.httpError(resp.statusCode(), resp.body()));
        }
        return ApiJson.parse(resp.body());
    }

    private void patchJson(String path, ObjectNode body, Session s) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(DesktopAuthFlow.trimSlash(config.apiBaseUrl) + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        applyDesktopHeaders(b, s);
        HttpResponse<String> resp = HttpClients.defaultClient(config)
                .send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
    }

    private void applyDesktopHeaders(HttpRequest.Builder b, Session s) {
        if (s != null) {
            b.header("Authorization", "Bearer " + s.apiToken());
        }
        b.header("X-AIPilot-Client", "desktop");
        String device = config.deviceKey == null ? "" : config.deviceKey.trim();
        if (!device.isBlank()) {
            b.header("X-AIPilot-Device-Id", device);
        }
    }

    public record FilePatch(long id, String path, String content, String op, String toPath) {
        public FilePatch(long id, String path, String content) {
            this(id, path, content, "write", "");
        }
    }

    public record PendingCommand(long id, String projectId, Long runId, String kind, String path, String toPath,
                                 String content, String language, String code, String alias, String command) {
    }

    public record ChatNode(String title, String name) {
    }

    public record ProjectNode(String id, String name, List<ChatNode> chats) {
    }

    public boolean saveProjectChats(Project project) {
        return saveProject(project, false);
    }

    public boolean saveProject(Project project, boolean includeSecrets) {
        if (project == null || project.id == null || project.id.isBlank()) {
            return false;
        }
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("project_id", project.id);
            String title = project.displayName != null && !project.displayName.isBlank()
                    ? project.displayName
                    : (project.name == null ? project.id : project.name);
            body.put("project_name", title);
            body.put("name", title);
            if (ChatUrls.looksLikeId(project.qwenChatId)) {
                body.put("qwen_chat_id", project.qwenChatId);
            }
            if (ChatUrls.looksLikeId(project.deepseekChatId)) {
                body.put("deepseek_chat_id", project.deepseekChatId);
            }
            if (project.qwenChatTitle != null && !project.qwenChatTitle.isBlank()) {
                body.put("qwen_chat_title", project.qwenChatTitle);
            }
            if (project.deepseekChatTitle != null && !project.deepseekChatTitle.isBlank()) {
                body.put("deepseek_chat_title", project.deepseekChatTitle);
            }
            if (project.qwenProjectName != null && !project.qwenProjectName.isBlank()) {
                body.put("qwen_project_name", project.qwenProjectName);
            }
            var folders = MAPPER.createArrayNode();
            if (project.paths != null) {
                boolean firstAbs = true;
                for (var p : project.paths) {
                    if (p == null || p.path == null || p.path.isBlank() || ".".equals(p.path.trim())) {
                        continue;
                    }
                    String abs = p.path.trim();
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("path", abs);
                    String alias;
                    if (firstAbs) {
                        alias = ProjectFolders.MAIN_ALIAS_LABEL;
                        firstAbs = false;
                    } else if (p.name != null && !p.name.isBlank()
                            && !ProjectFolders.MAIN_ALIAS_LABEL.equalsIgnoreCase(p.name.trim())
                            && !"main".equalsIgnoreCase(p.name.trim())) {
                        alias = p.name.trim();
                    } else if (p.role != null && !p.role.isBlank()
                            && !"main".equalsIgnoreCase(p.role.trim())
                            && !"root".equalsIgnoreCase(p.role.trim())) {
                        alias = p.role.trim();
                    } else {
                        alias = Path.of(abs).getFileName() == null ? "extra" : Path.of(abs).getFileName().toString();
                    }
                    item.put("alias", alias);
                    item.put("name", alias);
                    item.put("ssh_alias", p.sshAlias == null ? "" : p.sshAlias.trim());
                    folders.add(item);
                }
            }
            body.set("folders", folders);
            var servers = MAPPER.createArrayNode();
            if (project.servers != null) {
                for (var srv : project.servers) {
                    if (srv == null || srv.aliasOrEmpty().isBlank()) {
                        continue;
                    }
                    ObjectNode item = MAPPER.createObjectNode();
                    item.put("alias", srv.aliasOrEmpty());
                    item.put("host", srv.host == null ? "" : srv.host.trim());
                    item.put("port", srv.port <= 0 ? 22 : srv.port);
                    item.put("login", srv.login == null ? "" : srv.login.trim());
                    boolean hasSecret = srv.hasPassword
                            || (srv.password != null && !srv.password.isBlank());
                    item.put("has_password", hasSecret);
                    if (includeSecrets && srv.password != null && !srv.password.isBlank()) {
                        item.put("password", srv.password);
                    }
                    servers.add(item);
                }
            }
            body.set("servers", servers);
            if (config.deviceKey != null && !config.deviceKey.isBlank()) {
                body.put("device_id", config.deviceKey.trim().toLowerCase());
            }
            postJson("/api/aipilot/projects", body, s);
            return true;
        } catch (Exception e) {
            log.warn("save project failed: {}", safeMessage(e));
            return false;
        }
    }

    public void hydrateSecrets(Project project) {
        if (project == null || project.id == null || project.servers == null || project.servers.isEmpty()) {
            return;
        }
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return;
        }
        try {
            String q = URLEncoder.encode(project.id, StandardCharsets.UTF_8);
            JsonNode resp = getJson("/api/aipilot/projects/" + q + "/secrets", s);
            JsonNode items = resp == null ? null : resp.get("servers");
            if (items == null || !items.isArray()) {
                return;
            }
            java.util.Map<String, String> byAlias = new java.util.HashMap<>();
            for (JsonNode n : items) {
                byAlias.put(n.path("alias").asText(""), n.path("password").asText(""));
            }
            for (var srv : project.servers) {
                if (srv == null) {
                    continue;
                }
                String pw = byAlias.get(srv.aliasOrEmpty());
                if (pw != null && !pw.isBlank()) {
                    srv.password = pw;
                    srv.hasPassword = true;
                }
            }
        } catch (Exception e) {
            log.warn("project secrets failed: {}", safeMessage(e));
        }
    }

    public List<ProjectNode> chatsTree() {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return List.of();
        }
        try {
            JsonNode resp = getJson("/api/aipilot/chats/tree", s);
            JsonNode projects = resp == null ? null : resp.get("projects");
            if (projects == null || !projects.isArray()) {
                return List.of();
            }
            List<ProjectNode> out = new ArrayList<>();
            for (JsonNode p : projects) {
                List<ChatNode> chats = new ArrayList<>();
                JsonNode arr = p.get("chats");
                if (arr != null && arr.isArray()) {
                    for (JsonNode c : arr) {
                        chats.add(new ChatNode(c.path("chat_title").asText(""), c.path("chat_name").asText("")));
                    }
                }
                out.add(new ProjectNode(p.path("project_id").asText(""), p.path("project_name").asText(""), chats));
            }
            return out;
        } catch (Exception e) {
            log.warn("chats tree failed: {}", safeMessage(e));
            return List.of();
        }
    }

    public List<Project> listProjects() {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return List.of();
        }
        try {
            JsonNode resp = getJson("/api/aipilot/projects", s);
            JsonNode items = resp == null ? null : resp.get("items");
            if (items == null || !items.isArray()) {
                return List.of();
            }
            List<Project> out = new ArrayList<>();
            for (JsonNode n : items) {
                Project p = projectFromNode(n);
                if (p != null && p.id != null && !p.id.isBlank()) {
                    out.add(p);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("list projects failed: {}", safeMessage(e));
            return List.of();
        }
    }

    public static Project projectFromNode(JsonNode n) {
        if (n == null || n.isMissingNode()) {
            return null;
        }
        Project p = new Project();
        p.id = n.path("project_id").asText("");
        p.name = p.id;
        p.displayName = n.path("project_name").asText(p.id);
        p.qwenChatId = ChatUrls.normalize(n.path("qwen_chat_id").asText(""));
        p.deepseekChatId = ChatUrls.normalize(n.path("deepseek_chat_id").asText(""));
        p.qwenChatTitle = n.path("qwen_chat_title").asText("");
        p.deepseekChatTitle = n.path("deepseek_chat_title").asText("");
        p.deviceId = n.path("device_id").asText("");
        JsonNode folders = n.get("folders");
        if (folders != null && folders.isArray()) {
            for (JsonNode f : folders) {
                com.aipilot.core.model.ProjectPath path = new com.aipilot.core.model.ProjectPath();
                path.path = f.path("path").asText("");
                path.name = f.path("alias").asText("");
                if (path.name == null || path.name.isBlank()) {
                    path.name = f.path("name").asText("");
                }
                path.role = path.name;
                path.sshAlias = f.path("ssh_alias").asText("");
                if (path.path != null && !path.path.isBlank()) {
                    p.paths.add(path);
                }
            }
        }
        JsonNode servers = n.get("servers");
        if (servers != null && servers.isArray()) {
            for (JsonNode s : servers) {
                com.aipilot.core.model.ProjectServer srv = new com.aipilot.core.model.ProjectServer(
                        s.path("alias").asText(""),
                        s.path("host").asText(""),
                        s.path("port").asInt(22),
                        s.path("login").asText(""));
                srv.hasPassword = s.path("has_password").asBoolean(false);
                p.servers.add(srv);
            }
        }
        for (var path : p.paths) {
            if (path == null || path.path == null || path.path.isBlank() || ".".equals(path.path)) {
                continue;
            }
            try {
                java.nio.file.Path folder = java.nio.file.Path.of(path.path);
                if (folder.isAbsolute()) {
                    p.root = folder.toAbsolutePath().normalize().toString();
                    break;
                }
            } catch (Exception ignored) {
            }
        }
        if (p.root == null || p.root.isBlank()) {
            p.root = ".";
        }
        return p;
    }

    public List<PendingCommand> pendingCommands() {
        return pendingCommands("");
    }

    public List<PendingCommand> pendingCommands(String projectId) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return List.of();
        }
        try {
            String path = "/api/aipilot/commands/pending";
            if (projectId != null && !projectId.isBlank()) {
                path += "?project_id=" + URLEncoder.encode(projectId, StandardCharsets.UTF_8);
            }
            JsonNode resp = getJson(path, s);
            JsonNode items = resp == null ? null : resp.get("items");
            if (items == null || !items.isArray()) {
                return List.of();
            }
            List<PendingCommand> out = new ArrayList<>();
            for (JsonNode n : items) {
                long id = n.path("id").asLong(0);
                if (id <= 0) {
                    continue;
                }
                long run = n.path("run_id").asLong(0);
                out.add(new PendingCommand(
                        id,
                        n.path("project_id").asText(projectId == null ? "" : projectId),
                        run > 0 ? run : null,
                        n.path("kind").asText(""),
                        n.path("path").asText(""),
                        n.path("to").asText(""),
                        n.path("content").asText(""),
                        n.path("language").asText(""),
                        n.path("code").asText(""),
                        n.path("alias").asText(""),
                        n.path("command").asText("")));
            }
            return out;
        } catch (Exception e) {
            log.warn("pending commands failed: {}", safeMessage(e));
            return List.of();
        }
    }

    public boolean ackCommand(long id, String status, String projectId, String path) {
        return ackCommand(id, status, projectId, path, null, null, null);
    }

    public boolean ackCommand(long id, String status, String projectId, String path,
                              String result, String error, Integer exitCode) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated() || id <= 0) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("status", status == null || status.isBlank() ? "applied" : status);
            if (projectId != null) {
                body.put("project_id", projectId);
            }
            if (path != null) {
                body.put("path", path);
            }
            if (result != null) {
                body.put("result", result);
            }
            if (error != null) {
                body.put("error", error);
            }
            if (exitCode != null) {
                body.put("exit_code", exitCode);
            }
            postJson("/api/aipilot/commands/" + id + "/ack", body, s);
            return true;
        } catch (Exception e) {
            log.warn("ack command failed: {}", safeMessage(e));
            return false;
        }
    }

    public List<FilePatch> pendingFiles(String projectId) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated() || projectId == null || projectId.isBlank()) {
            return List.of();
        }
        try {
            String q = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
            JsonNode resp = getJson("/api/aipilot/files/pending?project_id=" + q, s);
            JsonNode items = resp == null ? null : resp.get("items");
            if (items == null || !items.isArray()) {
                return List.of();
            }
            List<FilePatch> out = new ArrayList<>();
            for (JsonNode n : items) {
                long id = n.path("id").asLong(0);
                String path = n.path("path").asText("");
                String content = n.path("content").asText("");
                if (id > 0 && !path.isBlank()) {
                    out.add(new FilePatch(id, path, content,
                            n.path("op").asText("write"), n.path("to").asText("")));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("pending files failed: {}", safeMessage(e));
            return List.of();
        }
    }

    public boolean ackFilePatch(long id, String projectId, String path) {
        Session s = auth.currentSession();
        if (s == null || !s.isAuthenticated()) {
            return false;
        }
        try {
            ObjectNode body = MAPPER.createObjectNode();
            if (projectId != null) {
                body.put("project_id", projectId);
            }
            if (path != null) {
                body.put("path", path);
            }
            String url = id > 0
                    ? "/api/aipilot/files/" + id + "/ack"
                    : "/api/aipilot/files/0/ack";
            postJson(url, body, s);
            return true;
        } catch (Exception e) {
            log.warn("ack file patch failed: {}", safeMessage(e));
            return false;
        }
    }

    public void ackMatchingPatches(String projectId, List<FileEditProposal> applied) {
        if (applied == null) {
            return;
        }
        for (FileEditProposal edit : applied) {
            if (edit == null || edit.path() == null || edit.path().isBlank()) {
                continue;
            }
            ackFilePatch(0, projectId, edit.path());
        }
    }

    private void writeLocalEvent(Integer userId, String projectId, Long runId, String kind, String message,
                                 Map<String, ?> payload) {
        try {
            Path dir = paths.userEventsDir(userId);
            Files.createDirectories(dir);
            ObjectNode n = MAPPER.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("project_id", projectId == null ? "" : projectId);
            if (runId != null && runId > 0) {
                n.put("run_id", runId);
            }
            n.put("kind", kind == null ? "system" : kind);
            n.put("message", message == null ? "" : message);
            if (payload != null && !payload.isEmpty()) {
                n.set("payload", MAPPER.valueToTree(payload));
            }
            Path file = dir.resolve(Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID() + "-" + safeFilePart(kind) + ".json");
            writeAtomic(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n");
            SecureFiles.restrictToOwner(file);
        } catch (Exception e) {
            log.warn("local event write failed: {}", safeMessage(e));
        }
    }

    private void writeLocalAudit(Integer userId, String projectId, String provider, String prompt, String response) {
        try {
            Path dir = paths.userHistoryDir(userId);
            Files.createDirectories(dir);
            ObjectNode n = MAPPER.createObjectNode();
            n.put("ts", Instant.now().toString());
            n.put("project_id", projectId == null ? "" : projectId);
            n.put("provider", provider == null ? "" : provider);
            n.put("prompt", prompt == null ? "" : prompt);
            n.put("response", response == null ? "" : response);
            Path file = dir.resolve(Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID() + "-" + safeFilePart(provider) + ".json");
            writeAtomic(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(n) + "\n");
            SecureFiles.restrictToOwner(file);
        } catch (Exception e) {
            log.warn("local audit write failed: {}", safeMessage(e));
        }
    }

    private static void writeAtomic(Path target, String content) throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String safeFilePart(String value) {
        String normalized = value == null ? "x" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return normalized.isBlank() ? "x" : normalized;
    }

    private static String safeMessage(Exception e) {
        String message = e == null ? "" : e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
