package com.aipilot;

import com.aipilot.infra.api.BridgeJob;
import com.aipilot.infra.api.SiteBridgeClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SiteBridgeClientTest {
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reportsProviderReadyFromStatus(@TempDir Path tmp) throws Exception {
        int port = startServer("""
                {"qwen":{"ready":true},"deepseek":{"ready":false}}
                """, null);
        SiteBridgeClient client = client(tmp, port);
        assertTrue(client.isProviderReady("qwen"));
        assertFalse(client.isProviderReady("deepseek"));
    }

    @Test
    void unwrapsReadyFromOkDataEnvelope(@TempDir Path tmp) throws Exception {
        int port = startServer("""
                {"ok":true,"data":{"qwen":{"ready":true},"deepseek":{"ready":false}}}
                """, null);
        SiteBridgeClient client = client(tmp, port);
        assertTrue(client.isProviderReady("qwen"));
        assertFalse(client.isProviderReady("deepseek"));
    }

    @Test
    void publishScriptsPostsVersionedFiles(@TempDir Path tmp) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        int port = startServer(null, exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/scripts")) {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{\"ok\":true,\"data\":{\"url\":\"https://bridge.example.test/js/aipilot\"}}");
                return;
            }
            writeJson(exchange, 404, "{}");
        });
        SiteBridgeClient client = client(tmp, port);
        assertEquals("https://bridge.example.test/js/aipilot",
                client.publishScripts("1.9.8", "core-src", "qwen-src", "deep-src"));
        assertNotNull(body.get());
        assertTrue(body.get().contains("\"version\":\"1.9.8\""));
        assertTrue(body.get().contains("bridge-core.js"));
        assertTrue(body.get().contains("core-src"));
        assertTrue(body.get().contains("qwen-ui.js"));
    }

    @Test
    void submitAndWaitReturnsCompletedText(@TempDir Path tmp) throws Exception {
        String jobId = UUID.randomUUID().toString();
        AtomicInteger getCount = new AtomicInteger();
        AtomicReference<String> jobBody = new AtomicReference<>();
        int port = startServer(null, exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/jobs")) {
                jobBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{\"id\":\"" + jobId + "\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().contains("/jobs/" + jobId)) {
                if (getCount.getAndIncrement() == 0) {
                    writeJson(exchange, 200, "{\"status\":\"pending\"}");
                } else {
                    writeJson(exchange, 200, "{\"status\":\"done\",\"text\":\"answer42\"}");
                }
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not found\"}");
        });
        SiteBridgeClient client = client(tmp, port);
        BridgeJob job = new BridgeJob(jobId, "qwen", "hello", "folder", "QwenProj", "Chat A", false);
        assertEquals("answer42", client.submitAndWait(job, 5));
        assertNotNull(jobBody.get());
        assertTrue(jobBody.get().contains("\"action\":\"prompt\""));
        assertTrue(jobBody.get().contains("\"qwen_project_name\":\"QwenProj\""));
        assertTrue(jobBody.get().contains("\"chat_title\":\"Chat A\""));
        assertFalse(jobBody.get().contains("\"chat_id\""));
        assertFalse(jobBody.get().contains("\"chatId\""));
    }

    @Test
    void submitPostsChatIdAndProjectIdSeparatelyFromTitle(@TempDir Path tmp) throws Exception {
        String jobId = UUID.randomUUID().toString();
        AtomicReference<String> jobBody = new AtomicReference<>();
        int port = startServer(null, exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/jobs")) {
                jobBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{\"id\":\"" + jobId + "\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().contains("/jobs/" + jobId)) {
                writeJson(exchange, 200, """
                        {"status":"done","text":"ok","chatId":"58e84cf5-84c7-4bc7-8358-8b551b65e315"}
                        """);
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not found\"}");
        });
        SiteBridgeClient client = client(tmp, port);
        String chatId = "58e84cf5-84c7-4bc7-8358-8b551b65e315";
        BridgeJob job = new BridgeJob(
                jobId, "qwen", "hello", "folder", "QwenProj", "Chat A", chatId, false, "prompt", "proj-1");
        SiteBridgeClient.JobOutcome outcome = client.submitAndWaitResult(job, 5);
        assertEquals("ok", outcome.text());
        assertEquals(chatId, outcome.chatId());
        assertNotNull(jobBody.get());
        assertTrue(jobBody.get().contains("\"chat_title\":\"Chat A\""));
        assertTrue(jobBody.get().contains("\"chat_id\":\"" + chatId + "\""));
        assertTrue(jobBody.get().contains("\"project_id\":\"proj-1\""));
        assertFalse(jobBody.get().contains("\"chatId\":\"Chat A\""));
    }

    @Test
    void submitAndWaitAcceptsCompletedStatusAlias(@TempDir Path tmp) throws Exception {
        String jobId = UUID.randomUUID().toString();
        int port = startServer(null, exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/jobs")) {
                writeJson(exchange, 200, "{\"id\":\"" + jobId + "\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().contains("/jobs/" + jobId)) {
                writeJson(exchange, 200, "{\"status\":\"completed\",\"response\":\"from-alias\"}");
                return;
            }
            writeJson(exchange, 404, "{}");
        });
        SiteBridgeClient client = client(tmp, port);
        BridgeJob job = new BridgeJob(jobId, "deepseek", "hello", null, null, null, true);
        assertEquals("from-alias", client.submitAndWait(job, 5));
    }

    @Test
    void registersTokenAndParsesActivity(@TempDir Path tmp) throws Exception {
        AtomicReference<String> tokenBody = new AtomicReference<>();
        int port = startServer(null, exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/token")) {
                tokenBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                writeJson(exchange, 200, "{}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/activity")) {
                writeJson(exchange, 200, "{\"lines\":[\"one\",\"two\"]}");
                return;
            }
            writeJson(exchange, 404, "{}");
        });
        SiteBridgeClient client = client(tmp, port);
        client.registerToken();
        assertTrue(tokenBody.get().contains("bridge-token"));
        assertTrue(tokenBody.get().contains("\"device_id\":\"" + "b".repeat(64) + "\""));
        assertEquals(java.util.List.of("one", "two"), client.activityFeed("qwen", 5));
    }

    @Test
    void propagatesJobErrorAndRejectsInvalidProvider(@TempDir Path tmp) throws Exception {
        String jobId = UUID.randomUUID().toString();
        int port = startServer(null, exchange -> {
            if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/jobs")) {
                writeJson(exchange, 200, "{\"id\":\"" + jobId + "\"}");
                return;
            }
            if ("GET".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().contains("/jobs/" + jobId)) {
                writeJson(exchange, 200, "{\"status\":\"error\",\"error\":\"DOM_FAILED\"}");
                return;
            }
            writeJson(exchange, 404, "{}");
        });
        SiteBridgeClient client = client(tmp, port);
        BridgeJob failing = new BridgeJob(jobId, "deepseek", "hello", null, null, null, true);
        IOException error = assertThrows(IOException.class, () -> client.submitAndWait(failing, 2));
        assertEquals("DOM_FAILED", error.getMessage());
        BridgeJob invalid = new BridgeJob(jobId, "other", "hello", null, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> client.submitAndWait(invalid, 2));
    }

    private SiteBridgeClient client(Path tmp, int port) throws Exception {
        Path appDir = tmp.resolve("app");
        java.nio.file.Files.createDirectories(appDir);
        AppPaths paths = newAppPaths(appDir);
        AppConfig config = new AppConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + port;
        config.bridgeToken = "bridge-token";
        config.deviceKey = "b".repeat(64);
        AuthManager auth = new AuthManager(paths, config);
        setSession(auth, new Session("api-token", 1, "tester", false));
        return new SiteBridgeClient(config, auth);
    }

    private int startServer(String statusJson, Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/status")) {
                writeJson(exchange, 200, statusJson);
                return;
            }
            if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/scripts") && handler != null) {
                handler.handle(exchange);
                return;
            }
            if (handler != null) {
                handler.handle(exchange);
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"missing\"}");
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, int code, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static AppPaths newAppPaths(Path appDir) throws Exception {
        var ctor = AppPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(appDir, appDir.resolve("projects"), appDir.resolve("logs"));
    }

    private static void setSession(AuthManager auth, Session session) throws Exception {
        Field f = AuthManager.class.getDeclaredField("session");
        f.setAccessible(true);
        f.set(auth, session);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }
}
