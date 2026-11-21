package com.aipilot.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves OS-specific config / data / log directories for AIPilot. */
public final class AppPaths {
    private final Path appDir;
    private final Path projectsRoot;
    private final Path logDir;

    private AppPaths(Path appDir, Path projectsRoot, Path logDir) {
        this.appDir = appDir;
        this.projectsRoot = projectsRoot;
        this.logDir = logDir;
    }

    public static AppPaths resolve() {
        return resolve(null);
    }

    public static AppPaths resolve(Integer userId) {
        Path appDir;
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appdata = System.getenv("APPDATA");
            appDir = Path.of(appdata != null ? appdata : System.getProperty("user.home"), "AIPilot");
        } else {
            appDir = Path.of(System.getProperty("user.home"), ".config", "aipilot");
        }
        Path projects = userId != null && userId > 0
                ? appDir.resolve("users").resolve(String.valueOf(userId)).resolve("projects")
                : appDir.resolve("projects");
        Path logs = appDir.resolve("logs");
        try {
            Files.createDirectories(appDir);
            Files.createDirectories(projects);
            Files.createDirectories(logs);
        } catch (Exception ignored) {
        }
        return new AppPaths(appDir, projects, logs);
    }

    public AppPaths forUser(Integer userId) {
        if (userId == null || userId <= 0) {
            return this;
        }
        return resolve(userId);
    }

    public Path appDir() {
        return appDir;
    }

    public Path projectsRoot() {
        return projectsRoot;
    }

    public Path logDir() {
        return logDir;
    }

    public Path configFile() {
        return appDir.resolve("config.json");
    }

    public Path authFile() {
        return appDir.resolve("auth.json");
    }

    public Path legacyProjectsRoot() {
        return appDir.resolve("projects");
    }

    public Path userHistoryDir(Integer userId) {
        Path dir = appDir.resolve("users").resolve(String.valueOf(userId)).resolve("history");
        SecureFiles.restrictDirToOwner(dir);
        return dir;
    }

    public Path userEventsDir(Integer userId) {
        Path dir = appDir.resolve("users").resolve(String.valueOf(userId)).resolve("events");
        SecureFiles.restrictDirToOwner(dir);
        return dir;
    }

    public Path dataRoot() {
        return appDir;
    }

    public Path templatesDir() {
        return appDir.resolve("templates");
    }

    public Path userscriptsDir() {
        return appDir.resolve("userscripts");
    }
}
