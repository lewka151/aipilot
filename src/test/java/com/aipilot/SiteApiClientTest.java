package com.aipilot;

import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiteApiClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void writesExpectedRunHistoryMetricsEventsAndSyncContracts(@TempDir Path tmp) throws Exception {
        List<Request> requests = new ArrayList<>();
        int port = start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), body));
            }
            String response = exchange.getRequestURI().getPath().equals("/api/aipilot/runs")
                    ? "{\"run_id\":7}" : "{}";
            reply(exchange, 200, response);
        });
        SiteApiClient client = client(tmp, port);

        SiteApiClient.RunSession run = client.startRun("p1", "Проект 1", "goal", "plan", List.of());
        assertNotNull(run);
        assertTrue(client.logStep(run, "deepseek", "refine", "prompt", "answer", 12));
        assertTrue(client.finishRun(run, "done", 25));
        assertTrue(client.logPrompt("p1", "Проект 1", "qwen", "prompt", "answer", 1, 2, 6, 6, "plan"));
        assertTrue(client.logPrompt("p1", "Проект 1", "skeleton", "skel", "", 0, 0, 4, 0, "projects", "skeleton"));
        assertTrue(client.logChatMetrics("p1", "user", 10, 3, 2));
        assertTrue(client.logEvent("p1", run.runId(), "test", "message", Map.of("ok", true)));
        assertTrue(client.pingSync());

        assertRequest(requests, "POST", "/api/aipilot/runs", "\"project_name\":\"Проект 1\"");
        assertRequest(requests, "POST", "/api/aipilot/runs", "\"mode\":\"plan\"");
        assertRequest(requests, "POST", "/api/aipilot/runs", "\"goal\":\"goal\"");
        assertRequest(requests, "POST", "/api/aipilot/runs/7/steps", "\"phase\":\"refine\"");
        assertRequest(requests, "PATCH", "/api/aipilot/runs/7", "\"status\":\"done\"");
        assertRequest(requests, "POST", "/api/aipilot/history", "\"provider\":\"qwen\"");
        assertRequest(requests, "POST", "/api/aipilot/history", "\"project_name\":\"Проект 1\"");
        assertRequest(requests, "POST", "/api/aipilot/history", "\"mode\":\"plan\"");
        assertRequest(requests, "POST", "/api/aipilot/history", "\"phase\":\"skeleton\"");
        assertRequest(requests, "POST", "/api/aipilot/history", "\"provider\":\"skeleton\"");
        assertRequest(requests, "POST", "/api/aipilot/metrics", "\"prompt_chars\":10");
        assertRequest(requests, "POST", "/api/aipilot/events", "\"kind\":\"test\"");
        assertRequest(requests, "POST", "/api/aipilot/sync", "\"client\":\"aipilot-desktop\"");
    }

    @Test
    void assemblePromptPostsRoleAndReturnsPromptField(@TempDir Path tmp) throws Exception {
        int port = start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"role\":\"deepseek_refine\""));
            assertTrue(body.contains("\"text\":\"goal\""));
            reply(exchange, 200, "{\"prompt\":\"ASSEMBLED\"}");
        });
        SiteApiClient client = client(tmp, port);
        assertEquals("ASSEMBLED", client.assemblePrompt("p1", "deepseek_refine", "goal", "Demo", "QW title"));
    }

    @Test
    void assemblePromptPostsExtrasObject(@TempDir Path tmp) throws Exception {
        int port = start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("\"role\":\"qwen_step\""));
            assertTrue(body.contains("\"extras\""));
            assertTrue(body.contains("\"План\""));
            reply(exchange, 200, "{\"prompt\":\"WITH-EXTRAS\"}");
        });
        SiteApiClient client = client(tmp, port);
        assertEquals("WITH-EXTRAS", client.assemblePrompt(
                "p1", "qwen_step", "goal", "Demo", "QW title", Map.of("План", "STEP 1: x")));
    }

    @Test
    void reportsHttpAndJsonFailuresWithoutThrowing(@TempDir Path tmp) throws Exception {
        int port = start(exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/history")) {
                reply(exchange, 500, "{\"error\":\"failed\"}");
            } else {
                reply(exchange, 200, "not-json");
            }
        });
        SiteApiClient client = client(tmp, port);
        assertNull(client.startRun("p", "g", "projects"));
        assertFalse(client.logPrompt("p", "qwen", "p", "r"));
    }

    @Test
    void saveProjectOmitsPasswordAndSecretsRequireDesktopHeader(@TempDir Path tmp) throws Exception {
        List<Request> requests = new ArrayList<>();
        int port = start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String client = exchange.getRequestHeaders().getFirst("X-AIPilot-Client");
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        body + "|client=" + client));
            }
            if (exchange.getRequestURI().getPath().contains("/secrets")) {
                reply(exchange, 200, "{\"servers\":[{\"alias\":\"prod\",\"password\":\"hidden\"}]}");
                return;
            }
            reply(exchange, 200, "{\"ok\":true}");
        });
        SiteApiClient client = client(tmp, port);
        com.aipilot.core.model.Project p = new com.aipilot.core.model.Project();
        p.id = "p1";
        p.name = "p1";
        p.displayName = "Demo";
        com.aipilot.core.model.ProjectPath folder = new com.aipilot.core.model.ProjectPath("H:/demo", "main");
        folder.name = "Папка проекта";
        folder.role = "main";
        p.paths.add(folder);
        com.aipilot.core.model.ProjectServer srv = new com.aipilot.core.model.ProjectServer("prod", "h", 22, "u");
        srv.password = "super-secret";
        srv.hasPassword = true;
        p.servers.add(srv);
        assertTrue(client.saveProject(p, false));
        assertRequest(requests, "POST", "/api/aipilot/projects", "\"project_name\":\"Demo\"");
        assertRequest(requests, "POST", "/api/aipilot/projects", "\"alias\":\"Папка проекта\"");
        assertRequest(requests, "POST", "/api/aipilot/projects", "\"path\":\"H:/demo\"");
        assertRequest(requests, "POST", "/api/aipilot/projects", "\"alias\":\"prod\"");
        assertRequest(requests, "POST", "/api/aipilot/projects", "\"has_password\":true");
        assertTrue(requests.stream().noneMatch(r -> r.body.contains("super-secret")));
        assertTrue(requests.stream().noneMatch(r -> r.path.equals("/api/aipilot/projects") && r.body.contains("\"password\"")));

        client.hydrateSecrets(p);
        assertEquals("hidden", srv.password);
        assertTrue(requests.stream().anyMatch(r ->
                r.path.equals("/api/aipilot/projects/p1/secrets") && r.body.contains("client=desktop")));
    }

    @Test
    void pendingWithoutProjectIdAndAckSendsResultPayload(@TempDir Path tmp) throws Exception {
        List<Request> requests = new ArrayList<>();
        int port = start(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String device = exchange.getRequestHeaders().getFirst("X-AIPilot-Device-Id");
            synchronized (requests) {
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath()
                        + (exchange.getRequestURI().getQuery() == null ? "" : "?" + exchange.getRequestURI().getQuery()),
                        body + "|device=" + (device == null ? "" : device)));
            }
            if (exchange.getRequestURI().getPath().contains("/pending")) {
                reply(exchange, 200, "{\"items\":[{\"id\":9,\"project_id\":\"p1\",\"run_id\":3,"
                        + "\"kind\":\"run\",\"language\":\"python\",\"code\":\"print(1)\"}]}");
                return;
            }
            if (exchange.getRequestURI().getPath().contains("/projects")) {
                reply(exchange, 200, "{\"items\":[{\"project_id\":\"p1\",\"project_name\":\"Demo\","
                        + "\"device_id\":\"" + "a".repeat(64) + "\","
                        + "\"folders\":[{\"alias\":\"src\",\"path\":\"H:/work/src\",\"ssh_alias\":\"\"}],"
                        + "\"servers\":[{\"alias\":\"prod\",\"host\":\"h\",\"port\":22,\"login\":\"u\","
                        + "\"has_password\":true}]}]}");
                return;
            }
            reply(exchange, 200, "{}");
        });
        SiteApiClient client = client(tmp, port);
        var pending = client.pendingCommands();
        assertEquals(1, pending.size());
        assertEquals("p1", pending.getFirst().projectId());
        assertEquals(3L, pending.getFirst().runId());
        assertTrue(client.ackCommand(9, "applied", "p1", "", "ok", "", 0));
        assertRequest(requests, "GET", "/api/aipilot/commands/pending", "|device=" + "a".repeat(64));
        assertTrue(requests.stream().noneMatch(r -> r.path.contains("project_id=")));
        assertRequest(requests, "POST", "/api/aipilot/commands/9/ack", "\"result\":\"ok\"");
        assertRequest(requests, "POST", "/api/aipilot/commands/9/ack", "\"exit_code\":0");
        var projects = client.listProjects();
        assertEquals(1, projects.size());
        assertEquals("Demo", projects.getFirst().displayName);
        assertEquals("src", projects.getFirst().paths.getFirst().name);
        assertEquals("a".repeat(64), projects.getFirst().deviceId);
    }

    private SiteApiClient client(Path tmp, int port) throws Exception {
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        AppPaths paths = newPaths(app);
        AppConfig config = new AppConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + port;
        config.deviceKey = "a".repeat(64);
        AuthManager auth = new AuthManager(paths, config);
        Field field = AuthManager.class.getDeclaredField("session");
        field.setAccessible(true);
        field.set(auth, new Session("test-token", 42, "tester", false));
        return new SiteApiClient(config, auth, paths);
    }

    private int start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server.getAddress().getPort();
    }

    private static void assertRequest(List<Request> requests, String method, String path, String bodyPart) {
        assertTrue(requests.stream().anyMatch(request ->
                        method.equals(request.method) && path.equals(request.path)
                                && request.body.contains(bodyPart)),
                () -> "Missing " + method + " " + path + " containing " + bodyPart);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static AppPaths newPaths(Path app) throws Exception {
        var ctor = AppPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(app, app.resolve("projects"), app.resolve("logs"));
    }

    private record Request(String method, String path, String body) {
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
