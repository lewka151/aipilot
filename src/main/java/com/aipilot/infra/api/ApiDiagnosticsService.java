package com.aipilot.infra.api;

import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.http.HttpClients;
import com.aipilot.infra.update.UpdateService;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Read-only checks for auth, bridge, releases and optional admin reads. */
public final class ApiDiagnosticsService {
    private final AppConfig config;
    private final AuthManager auth;

    public ApiDiagnosticsService(AppConfig config, AuthManager auth) {
        this.config = config;
        this.auth = auth;
    }

    public List<String> runReadOnly() {
        List<String> lines = new ArrayList<>();
        Session session = auth.currentSession();
        if (session == null || !session.isAuthenticated()) {
            lines.add("auth: SKIP — пользователь не авторизован");
        } else {
            lines.add(checkAuth(session));
            SiteBridgeClient bridge = new SiteBridgeClient(config, auth);
            addProvider(lines, bridge, "qwen");
            addProvider(lines, bridge, "deepseek");
            lines.add("bridge activity: qwen=" + bridge.activityFeed("qwen", 1).size()
                    + ", deepseek=" + bridge.activityFeed("deepseek", 1).size());
            if (session.isAdmin()) {
                AdminApiClient admin = new AdminApiClient(config, auth);
                lines.add(readAdmin("admin prompts", () -> admin.listPrompts(1, null)));
                lines.add(readAdmin("admin history", () -> admin.listHistory(1, null)));
            } else {
                lines.add("admin reads: SKIP — нет роли администратора");
            }
        }
        try {
            var release = UpdateService.fetchRelease(HttpClients.defaultClient(config), config.apiBaseUrl);
            lines.add("release: OK — " + (release.version() == null ? "version ?" : release.version()));
        } catch (Exception e) {
            lines.add("release: ERROR — " + safe(e));
        }
        return lines;
    }

    private String checkAuth(Session session) {
        try {
            String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/auth/me"))
                    .timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + session.apiToken())
                    .GET()
                    .build();
            HttpResponse<String> response = HttpClients.defaultClient(config)
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? "auth/me: OK — HTTP " + response.statusCode()
                    : "auth/me: ERROR — HTTP " + response.statusCode();
        } catch (Exception e) {
            return "auth/me: ERROR — " + safe(e);
        }
    }

    private static void addProvider(List<String> lines, SiteBridgeClient bridge, String provider) {
        SiteBridgeClient.ProviderStatus status = bridge.providerStatus(provider);
        lines.add("bridge " + provider + ": " + status.state().toUpperCase()
                + " — " + status.detail());
    }

    private static String readAdmin(String label, CheckedRead read) {
        try {
            read.run();
            return label + ": OK";
        } catch (Exception e) {
            return label + ": SKIP/ERROR — " + safe(e);
        }
    }

    private static String safe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface CheckedRead {
        void run() throws Exception;
    }
}
