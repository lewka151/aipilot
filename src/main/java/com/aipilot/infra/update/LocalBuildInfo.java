package com.aipilot.infra.update;

import com.aipilot.infra.config.AppPaths;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Local AIPilot build hash (build-aipilot.json, build-hash.txt, or cached in app data). */
public final class LocalBuildInfo {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LocalBuildInfo() {
    }

    public static String readHash(AppPaths paths) {
        String cached = readTextFile(paths.dataRoot().resolve("local-build-hash.txt"));
        if (cached != null) {
            return cached;
        }
        String fromDataJson = hashFromJson(paths.dataRoot().resolve("build-aipilot.json"));
        if (fromDataJson != null) {
            return fromDataJson;
        }
        for (Path dir : installDirs()) {
            String fromTxt = readTextFile(dir.resolve("build-hash.txt"));
            if (fromTxt != null) {
                return fromTxt;
            }
            String fromAppTxt = readTextFile(dir.resolve("app").resolve("build-hash.txt"));
            if (fromAppTxt != null) {
                return fromAppTxt;
            }
            String fromJson = hashFromJson(dir.resolve("build-aipilot.json"));
            if (fromJson != null) {
                return fromJson;
            }
            String fromAppJson = hashFromJson(dir.resolve("app").resolve("build-aipilot.json"));
            if (fromAppJson != null) {
                return fromAppJson;
            }
        }
        try (InputStream in = LocalBuildInfo.class.getResourceAsStream("/build-aipilot.json")) {
            if (in != null) {
                JsonNode root = MAPPER.readTree(in);
                String h = text(root, "hash");
                if (h != null) {
                    return h;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void writeHash(AppPaths paths, String hash) {
        if (hash == null || hash.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(paths.dataRoot());
            Files.writeString(
                    paths.dataRoot().resolve("local-build-hash.txt"),
                    hash.trim() + "\n",
                    StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    static List<Path> installDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        addDir(dirs, Path.of(System.getProperty("user.dir", ".")));
        addDir(dirs, Path.of(""));
        addDir(dirs, Path.of("dist/AIPilot"));
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            Path exe = Path.of(appPath);
            if (exe.getParent() != null) {
                addDir(dirs, exe.getParent());
            }
        }
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path home = Path.of(javaHome);
            if (home.getParent() != null) {
                addDir(dirs, home.getParent());
            }
        }
        return new ArrayList<>(dirs);
    }

    private static void addDir(Set<Path> dirs, Path dir) {
        if (dir == null) {
            return;
        }
        try {
            dirs.add(dir.toAbsolutePath().normalize());
        } catch (Exception e) {
            dirs.add(dir);
        }
    }

    private static String readTextFile(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String h = Files.readString(file, StandardCharsets.UTF_8).trim();
            return h.isBlank() ? null : h;
        } catch (Exception e) {
            return null;
        }
    }

    private static String hashFromJson(Path json) {
        try {
            if (!Files.isRegularFile(json)) {
                return null;
            }
            JsonNode root = MAPPER.readTree(Files.readString(json, StandardCharsets.UTF_8));
            return text(root, "hash");
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode o, String key) {
        JsonNode n = o.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        String v = n.asText("").trim();
        return v.isEmpty() ? null : v;
    }
}
