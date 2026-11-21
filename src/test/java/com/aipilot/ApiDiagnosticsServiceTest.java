package com.aipilot;

import com.aipilot.infra.api.ApiDiagnosticsService;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApiDiagnosticsServiceTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void skipsAuthWhenSignedOutAndChecksRelease(@TempDir Path tmp) throws Exception {
        int port = start(exchange -> {
            if (exchange.getRequestURI().getPath().contains("/client")) {
                reply(exchange, 200, "{\"version\":\"9.9.9\"}");
            } else {
                reply(exchange, 404, "{}");
            }
        });
        ApiDiagnosticsService service = service(tmp, port, null);
        List<String> lines = service.runReadOnly();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("auth: SKIP")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("release: OK")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("POST") || line.contains("PATCH")));
    }

    @Test
    void readsAuthMeAndBridgeStatusWhenSignedIn(@TempDir Path tmp) throws Exception {
        int port = start(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/api/auth/me")) {
                reply(exchange, 200, "{\"ok\":true}");
                return;
            }
            if (path.endsWith("/api/aipilot/bridge/status")) {
                reply(exchange, 200, "{\"qwen\":{\"ready\":true},\"deepseek\":{\"ready\":false}}");
                return;
            }
            if (path.contains("/api/aipilot/bridge/activity")) {
                reply(exchange, 200, "{\"lines\":[\"start ai151\"]}");
                return;
            }
            if (path.contains("/client")) {
                reply(exchange, 200, "{\"version\":\"1.2.3\"}");
                return;
            }
            reply(exchange, 404, "{}");
        });
        ApiDiagnosticsService service = service(tmp, port, new Session("tok", 7, "tester", false));
        List<String> lines = service.runReadOnly();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("auth/me: OK")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("bridge qwen") && line.contains("READY")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("admin reads: SKIP")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("release: OK")));
    }

    private ApiDiagnosticsService service(Path tmp, int port, Session session) throws Exception {
        Path app = tmp.resolve("app");
        Files.createDirectories(app);
        var ctor = AppPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        AppPaths paths = ctor.newInstance(app, app.resolve("projects"), app.resolve("logs"));
        AppConfig config = new AppConfig();
        config.apiBaseUrl = "http://127.0.0.1:" + port;
        AuthManager auth = new AuthManager(paths, config);
        if (session != null) {
            Field field = AuthManager.class.getDeclaredField("session");
            field.setAccessible(true);
            field.set(auth, session);
        }
        return new ApiDiagnosticsService(config, auth);
    }

    private int start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server.getAddress().getPort();
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
