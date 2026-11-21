package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;
import com.aipilot.core.model.ProjectServer;
import com.aipilot.infra.config.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persist project access in the main folder and a per-user index. */
public final class LocalProjectStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private LocalProjectStore() {
    }

    public static Path configFile(Project project) {
        Path main = ProjectFolders.mainFolder(project);
        if (main == null) {
            return null;
        }
        return main.resolve(".aipilot").resolve("project.json");
    }

    public static void save(AppPaths paths, Integer userId, Project project) throws Exception {
        if (project == null) {
            return;
        }
        Path main = ProjectFolders.mainFolder(project);
        if (main != null) {
            project.root = main.toString();
            Path cfg = main.resolve(".aipilot").resolve("project.json");
            writeAtomic(cfg, MAPPER.writeValueAsString(project) + "\n");
        }
        if (paths == null || project.id == null || project.id.isBlank()) {
            return;
        }
        Path index = indexDir(paths, userId).resolve(safeId(project.id)).resolve("project.json");
        writeAtomic(index, MAPPER.writeValueAsString(project) + "\n");
    }

    public static List<Project> list(AppPaths paths, Integer userId) {
        Map<String, Project> byId = new LinkedHashMap<>();
        addFromRoot(byId, indexDir(paths, userId));
        if (paths != null) {
            addFromRoot(byId, paths.forUser(userId).projectsRoot());
        }
        return new ArrayList<>(byId.values());
    }

    private static void addFromRoot(Map<String, Project> byId, Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                Path json = dir.resolve("project.json");
                if (!Files.isRegularFile(json)) {
                    json = dir.resolve(".aipilot").resolve("project.json");
                }
                if (!Files.isRegularFile(json)) {
                    continue;
                }
                try {
                    Project project = MAPPER.readValue(Files.readString(json, StandardCharsets.UTF_8), Project.class);
                    if (project.id == null || project.id.isBlank()) {
                        project.id = dir.getFileName().toString();
                    }
                    Path mainCfg = configFile(project);
                    if (mainCfg != null && Files.isRegularFile(mainCfg)
                            && !mainCfg.toAbsolutePath().equals(json.toAbsolutePath())) {
                        try {
                            project = MAPPER.readValue(Files.readString(mainCfg, StandardCharsets.UTF_8), Project.class);
                        } catch (Exception ignored) {
                        }
                    }
                    Path main = ProjectFolders.mainFolder(project);
                    if (main != null) {
                        project.root = main.toString();
                    }
                    byId.putIfAbsent(project.id, project);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Overlay local absolute folders / servers onto a site project for this PC. */
    public static void mergeLocalAccess(Project site, Project local) {
        if (site == null || local == null) {
            return;
        }
        if (DeviceBinding.hasAbsoluteFolder(local) && local.paths != null) {
            site.paths = copyPaths(local.paths);
        }
        if (local.servers != null && !local.servers.isEmpty() && DeviceBinding.hasAbsoluteFolder(local)) {
            site.servers = copyServers(local.servers);
        }
        if (local.displayName != null && !local.displayName.isBlank()
                && (site.displayName == null || site.displayName.isBlank() || site.displayName.equals(site.id))) {
            site.displayName = local.displayName;
        }
        if (local.deviceId != null && !local.deviceId.isBlank()) {
            site.deviceId = local.deviceId;
        }
        Path main = ProjectFolders.mainFolder(site);
        if (main != null) {
            site.root = main.toString();
        }
    }

    private static List<ProjectPath> copyPaths(List<ProjectPath> source) {
        List<ProjectPath> out = new ArrayList<>();
        for (ProjectPath p : source) {
            if (p == null) {
                continue;
            }
            ProjectPath copy = new ProjectPath();
            copy.path = p.path;
            copy.name = p.name;
            copy.role = p.role;
            copy.sshAlias = p.sshAlias;
            out.add(copy);
        }
        return out;
    }

    private static List<ProjectServer> copyServers(List<ProjectServer> source) {
        List<ProjectServer> out = new ArrayList<>();
        for (ProjectServer s : source) {
            if (s == null) {
                continue;
            }
            ProjectServer copy = new ProjectServer(s.aliasOrEmpty(), s.host, s.port, s.login);
            copy.password = s.password;
            copy.hasPassword = s.hasPassword || (s.password != null && !s.password.isBlank());
            out.add(copy);
        }
        return out;
    }

    private static Path indexDir(AppPaths paths, Integer userId) {
        return paths.forUser(userId).projectsRoot();
    }

    private static String safeId(String id) {
        String s = id == null ? "" : id.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        return s.isBlank() ? UUID.randomUUID().toString() : s;
    }

    private static void writeAtomic(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temp, content == null ? "" : content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
