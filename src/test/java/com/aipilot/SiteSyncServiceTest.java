package com.aipilot;

import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.api.SiteSyncService;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SiteSyncServiceTest {
    @Test
    void startsOnceAndRecoversAfterTemporaryFailure(@TempDir Path tmp) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int status = calls.getAndIncrement() == 0 ? 503 : 200;
            byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path app = tmp.resolve("app");
            Files.createDirectories(app);
            AppPaths paths = paths(app);
            AppConfig config = new AppConfig();
            config.apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            AuthManager auth = new AuthManager(paths, config);
            Field field = AuthManager.class.getDeclaredField("session");
            field.setAccessible(true);
            field.set(auth, new Session("token", 1, "tester", false));
            SiteSyncService sync = new SiteSyncService(new SiteApiClient(config, auth, paths), auth, null);

            sync.start();
            sync.start();
            assertTrue(sync.isStarted());
            assertFalse(sync.syncNow());
            assertFalse(sync.lastError().isBlank());
            assertTrue(sync.syncNow());
            assertNotNull(sync.lastSuccessAt());
            assertEquals("", sync.lastError());
            sync.shutdown();
            assertFalse(sync.isStarted());
        } finally {
            server.stop(0);
        }
    }

    private static AppPaths paths(Path app) throws Exception {
        var ctor = AppPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(app, app.resolve("projects"), app.resolve("logs"));
    }
}
