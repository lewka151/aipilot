package com.aipilot.infra.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {
    public static final String DEFAULT_API_BASE = "https://platforma151.chickenkiller.com";
    private static final String LEGACY_PUBLIC_IP_HTTP = "http://81.5.107.44";
    private static final String LEGACY_PUBLIC_IP_HTTPS = "https://81.5.107.44";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String apiBaseUrl = DEFAULT_API_BASE;
    public boolean headless = false;
    /** Stable project UUID. Older configs stored the folder name here. */
    public String activeProjectId = "";
    public String activeProjectName = "";
    public String mode = "projects"; // projects | plan | multichat | chat
    public int selfReflectionMaxRetries = 3;
    public int loopPollSeconds = 5;
    public double windowWidth = 440;
    public double windowHeight = 560;
    public double windowX = -1;
    public double windowY = -1;
    public double windowNormalWidth = 440;
    public double windowNormalHeight = 560;
    public double windowNormalX = -1;
    public double windowNormalY = -1;
    public boolean windowMaximized = false;
    public double splitMain = 0.24;
    public double splitRight = 0.74;
    public double splitCenter = 0.32;
    public double splitBrowsers = 0.5;
    public double splitRightMd = 0.5;
    /** Width of the projects column in px; survives maximize better than a fraction. */
    public double splitLeftPx = 260;
    /** True after inverting legacy splitCenter (chat-on-top 0.70 → providers-on-top). */
    public boolean splitLayoutV2;
    public boolean settingsPageOpen = false;
    public boolean autoUpdate = true;
    public boolean startHiddenInTray = false;
    public boolean autostartOnLogin = false;
    public boolean compactWindowMigrated = false;
    public boolean externalBrowser = true;
    public boolean autoOpenChatTabs = true;
    /** @deprecated retained only for compatibility with older config.json files. */
    @Deprecated
    public int bridgePort = 17845;
    public String bridgeToken = "";
    /** Per-install secret (64 hex chars). Binds projects and pending commands to this PC. */
    public String deviceKey = "";
    public int bridgeGenerationTimeoutSec = 300;
    public int bridgeConnectTimeoutSec = 30;
    public boolean trustAllTls = false;
    public String lastUpdateCheckAt = "";
    public int chatPageCheckMinutes = 5;
    public String qwenStaticReadmeTemplate = "";

    public static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "projects";
        }
        return switch (mode.toLowerCase()) {
            case "standard", "projects" -> "projects";
            case "iterative", "multichat" -> "multichat";
            case "plan", "chat" -> mode.toLowerCase();
            default -> "projects";
        };
    }

    public static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return DEFAULT_API_BASE;
        }
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (u.isEmpty()) {
            return DEFAULT_API_BASE;
        }
        if (u.equalsIgnoreCase(LEGACY_PUBLIC_IP_HTTP) || u.equalsIgnoreCase(LEGACY_PUBLIC_IP_HTTPS)) {
            return DEFAULT_API_BASE;
        }
        return u;
    }

    public static String apiBaseSchemePrefix(String url) {
        String t = url == null ? "" : url.trim().toLowerCase();
        if (t.startsWith("http://")) {
            return "http://";
        }
        return "https://";
    }

    public static String apiBaseHost(String url) {
        String t = url == null ? "" : url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        String lower = t.toLowerCase();
        if (lower.startsWith("https://")) {
            return t.substring(8);
        }
        if (lower.startsWith("http://")) {
            return t.substring(7);
        }
        return t;
    }

    public static String joinApiBase(String schemePrefix, String hostOrUrl) {
        String raw = hostOrUrl == null ? "" : hostOrUrl.trim();
        String scheme = raw.toLowerCase().startsWith("http://") || raw.toLowerCase().startsWith("https://")
                ? apiBaseSchemePrefix(raw)
                : (schemePrefix == null || schemePrefix.isBlank() ? "https://" : schemePrefix);
        if (!scheme.endsWith("://")) {
            scheme = scheme.contains("://") ? scheme : scheme + "://";
        }
        String host = apiBaseHost(raw);
        if (host.isBlank()) {
            return DEFAULT_API_BASE;
        }
        return trimSlash(scheme + host);
    }

    public static String newDeviceKey() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        return HexFormat.of().formatHex(raw);
    }

    public static boolean looksLikeDeviceKey(String key) {
        return key != null && key.matches("[0-9a-fA-F]{64}");
    }

    public static AppConfig load(Path configPath) {
        AppConfig cfg = new AppConfig();
        boolean fromFile = false;
        try {
            if (Files.isRegularFile(configPath)) {
                AppConfig parsed = MAPPER.readValue(Files.readString(configPath), AppConfig.class);
                if (parsed != null) {
                    cfg = parsed;
                    fromFile = true;
                }
            }
        } catch (Exception ignored) {
        }
        cfg.apiBaseUrl = trimSlash(cfg.apiBaseUrl);
        if (cfg.bridgeToken == null || cfg.bridgeToken.isBlank()) {
            cfg.bridgeToken = java.util.UUID.randomUUID().toString();
        }
        boolean generatedDevice = false;
        if (!looksLikeDeviceKey(cfg.deviceKey)) {
            cfg.deviceKey = newDeviceKey();
            generatedDevice = true;
        } else {
            cfg.deviceKey = cfg.deviceKey.trim().toLowerCase();
        }
        cfg.mode = normalizeMode(cfg.mode);
        if (cfg.windowNormalWidth <= 0 && cfg.windowWidth > 0) {
            cfg.windowNormalWidth = cfg.windowWidth;
        }
        if (cfg.windowNormalHeight <= 0 && cfg.windowHeight > 0) {
            cfg.windowNormalHeight = cfg.windowHeight;
        }
        if (cfg.windowNormalX < 0 && cfg.windowX >= 0) {
            cfg.windowNormalX = cfg.windowX;
        }
        if (cfg.windowNormalY < 0 && cfg.windowY >= 0) {
            cfg.windowNormalY = cfg.windowY;
        }
        if (cfg.splitLeftPx <= 80) {
            cfg.splitLeftPx = 260;
        }
        boolean migrated = false;
        if (!cfg.compactWindowMigrated) {
            cfg.windowWidth = 440;
            cfg.windowHeight = 560;
            cfg.windowNormalWidth = 440;
            cfg.windowNormalHeight = 560;
            cfg.windowMaximized = false;
            cfg.compactWindowMigrated = true;
            migrated = true;
        }
        if (!fromFile) {
            cfg.splitLayoutV2 = true;
            cfg.splitCenter = 0.32;
        } else if (!cfg.splitLayoutV2) {
            cfg.splitCenter = 1.0 - cfg.splitCenter;
            cfg.splitLayoutV2 = true;
            migrated = true;
        }
        if (migrated || generatedDevice) {
            try {
                cfg.save(configPath);
            } catch (Exception ignored) {
            }
        }
        return cfg;
    }

    public void save(Path configPath) throws Exception {
        Files.createDirectories(configPath.getParent());
        Files.writeString(configPath, MAPPER.writeValueAsString(this) + "\n");
    }
}
