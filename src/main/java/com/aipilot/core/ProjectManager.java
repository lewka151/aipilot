package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.infra.config.UserDataMigration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public final class ProjectManager {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final AppPaths paths;
    private final AppConfig config;
    private volatile Integer userId;
    private static final List<String> ALLOWED_DOCS = List.of(
            "chat_configs.md",
            "plan_instruction.md",
            "plan_review_instruction.md",
            "based.md",
            "ai_interaction_rules.md",
            "deepseek_static.md",
            "qwen_static_readme.md",
            "readme.md",
            "files.md",
            "file_index.md"
    );

    public ProjectManager(AppPaths paths, AppConfig config) {
        this.paths = paths;
        this.config = config;
    }

    public void setUserId(Integer userId) {
        if (userId != null && userId > 0) {
            UserDataMigration.migrateLegacyProjectsIfNeeded(paths, userId);
        }
        this.userId = userId;
    }

    private Path projectsRoot() {
        return paths.forUser(userId).projectsRoot();
    }

    public List<String> listProjectNames() {
        List<String> names = new ArrayList<>();
        Path root = projectsRoot();
        if (!Files.isDirectory(root)) {
            return names;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && Files.isRegularFile(p.resolve("project.json"))) {
                    names.add(p.getFileName().toString());
                }
            }
        } catch (Exception ignored) {
        }
        names.sort(Comparator.naturalOrder());
        return names;
    }

    public String displayLabel(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return "";
        }
        String safe = sanitize(folderName);
        Path json = projectsRoot().resolve(safe).resolve("project.json");
        if (!Files.isRegularFile(json)) {
            json = paths.legacyProjectsRoot().resolve(safe).resolve("project.json");
        }
        try {
            if (Files.isRegularFile(json)) {
                Project p = mapper.readValue(Files.readString(json, StandardCharsets.UTF_8), Project.class);
                String label = ProjectAliases.displayName(p);
                if (label != null && !label.isBlank()) {
                    return label;
                }
            }
        } catch (Exception ignored) {
        }
        return folderName;
    }

    public Project create(String name, String description) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя проекта не может быть пустым");
        }
        String safe = sanitize(name);
        Path dir = projectsRoot().resolve(safe);
        if (Files.exists(dir)) {
            throw new IllegalStateException("Проект уже существует: " + safe);
        }
        try {
            Files.createDirectories(dir);
            for (String sub : List.of("history", "attachments", "reports", "reports/plan", "scripts")) {
                Files.createDirectories(dir.resolve(sub));
            }

            writeTemplate(dir.resolve("chat_configs.md"), "templates/chat_configs.md");
            writeTemplate(dir.resolve("plan_instruction.md"), "templates/plan_instruction.md");
            writeTemplate(dir.resolve("plan_review_instruction.md"), "templates/plan_review_instruction.md");
            writeTemplate(dir.resolve("based.md"), "templates/based.md");
            writeTemplate(dir.resolve("ai_interaction_rules.md"), "templates/ai_interaction_rules.md");
            String staticReadme = config.qwenStaticReadmeTemplate;
            if (staticReadme == null || staticReadme.isBlank()) {
                staticReadme = readResource("templates/qwen_static_readme.md");
            }
            Files.writeString(dir.resolve("qwen_static_readme.md"), staticReadme, StandardCharsets.UTF_8);
            writeTemplate(dir.resolve("deepseek_static.md"), "templates/deepseek_static.md");
            Files.writeString(dir.resolve("readme.md"), "# " + safe + "\n\nДинамический контекст проекта.\n", StandardCharsets.UTF_8);
            writeTemplate(dir.resolve("file_index.md"), "templates/file_index.md");
            writeTemplate(dir.resolve("files.md"), "templates/files.md");

            Project p = new Project();
            p.id = UUID.randomUUID().toString();
            p.name = safe;
            p.displayName = name.trim();
            p.description = description == null ? "" : description;
            p.root = dir.toAbsolutePath().toString();
            normalizePaths(p, dir);
            p.paths.add(new ProjectPath(".", "root"));
            p.createdAt = LocalDateTime.now();
            p.updatedAt = p.createdAt;
            save(p);
            new FileIndexManager().rescanPreservingDescriptions(p);
            return p;
        } catch (Exception e) {
            if (Files.exists(dir)) {
                deleteTree(dir);
            }
            throw e;
        }
    }

    public Project loadByName(String name) throws Exception {
        String safe = sanitize(name);
        Path dir = projectsRoot().resolve(safe);
        if (!Files.isRegularFile(dir.resolve("project.json"))) {
            Path legacy = paths.legacyProjectsRoot().resolve(safe);
            if (Files.isRegularFile(legacy.resolve("project.json"))) {
                dir = legacy;
            }
        }
        return load(dir);
    }

    public Project load(Path projectDir) throws Exception {
        Path dir = projectDir.toAbsolutePath().normalize();
        Path json = dir.resolve("project.json");
        Project p = mapper.readValue(Files.readString(json, StandardCharsets.UTF_8), Project.class);
        p.root = dir.toAbsolutePath().toString();
        if (p.name == null || p.name.isBlank()) {
            p.name = dir.getFileName().toString();
        }
        if (p.displayName == null || p.displayName.isBlank()) {
            p.displayName = p.name;
        }
        if (p.paths == null) {
            p.paths = new java.util.ArrayList<>();
        }
        if (p.servers == null) {
            p.servers = new java.util.ArrayList<>();
        }
        resolveRoot(p);
        ensureProjectFiles(p);
        return p;
    }

    public void save(Project p) throws Exception {
        if (p == null) {
            throw new IllegalArgumentException("Проект или корневая папка не заданы");
        }
        Path dir = resolveRoot(p);
        p.updatedAt = LocalDateTime.now();
        Files.createDirectories(dir);
        normalizePaths(p, dir);
        Path target = dir.resolve("project.json");
        writeAtomic(target, mapper.writeValueAsString(p) + "\n");
    }

    public Path resolveRoot(Project p) throws Exception {
        if (p == null) {
            throw new IllegalArgumentException("Проект не выбран");
        }
        String name = p.name == null || p.name.isBlank() ? null : sanitize(p.name);
        Path declared = p.root == null || p.root.isBlank() ? null : Path.of(p.root).toAbsolutePath().normalize();
        if (isProjectDir(declared)) {
            p.root = declared.toString();
            return declared;
        }
        if (name != null) {
            Path scoped = projectsRoot().resolve(name);
            if (isProjectDir(scoped)) {
                p.root = scoped.toAbsolutePath().toString();
                return scoped;
            }
            Path legacy = paths.legacyProjectsRoot().resolve(name);
            if (isProjectDir(legacy)) {
                p.root = legacy.toAbsolutePath().toString();
                return legacy;
            }
            Files.createDirectories(scoped);
            p.root = scoped.toAbsolutePath().toString();
            return scoped;
        }
        if (declared != null) {
            Files.createDirectories(declared);
            p.root = declared.toString();
            return declared;
        }
        throw new IllegalStateException("Не найден каталог проекта");
    }

    public void writeProjectFile(Project p, String fileName, String content) throws Exception {
        Path dir = resolveRoot(p);
        ensureProjectFiles(p);
        String safe = Path.of(fileName).getFileName().toString();
        if (!ALLOWED_DOCS.contains(safe)) {
            throw new IllegalArgumentException("Нельзя сохранить файл: " + safe);
        }
        writeAtomic(dir.resolve(safe), content == null ? "" : content);
        normalizePaths(p, dir);
    }

    private static boolean isProjectDir(Path dir) {
        return dir != null && Files.isDirectory(dir) && Files.isRegularFile(dir.resolve("project.json"));
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

    public void delete(Project project) throws Exception {
        if (project == null || project.root == null || project.root.isBlank()) {
            throw new IllegalArgumentException("Проект не выбран");
        }
        deleteDirectory(Path.of(project.root));
    }

    public void deleteByName(String name) throws Exception {
        deleteDirectory(projectsRoot().resolve(sanitize(name)));
    }

    public List<String> listReports(Project p) {
        List<String> out = new ArrayList<>();
        Path reports = p.rootPath().resolve("reports");
        if (!Files.isDirectory(reports)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(reports, 2)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".md"))
                    .map(f -> reports.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    public void ensureProjectFiles(Project p) throws Exception {
        if (p == null || p.root == null || p.root.isBlank()) {
            throw new IllegalArgumentException("Проект или корневая папка не заданы");
        }
        Path dir = resolveRoot(p);
        Files.createDirectories(dir);
        for (String sub : List.of("history", "attachments", "reports", "reports/plan", "scripts")) {
            Files.createDirectories(dir.resolve(sub));
        }
        writeIfMissing(dir.resolve("chat_configs.md"), "templates/chat_configs.md");
        writeIfMissing(dir.resolve("plan_instruction.md"), "templates/plan_instruction.md");
        writeIfMissing(dir.resolve("plan_review_instruction.md"), "templates/plan_review_instruction.md");
        writeIfMissing(dir.resolve("based.md"), "templates/based.md");
        writeIfMissing(dir.resolve("ai_interaction_rules.md"), "templates/ai_interaction_rules.md");
        writeIfMissing(dir.resolve("files.md"), "templates/files.md");
        if (!Files.isRegularFile(dir.resolve("qwen_static_readme.md"))) {
            String staticReadme = config.qwenStaticReadmeTemplate;
            if (staticReadme == null || staticReadme.isBlank()) {
                staticReadme = readResource("templates/qwen_static_readme.md");
            }
            Files.writeString(dir.resolve("qwen_static_readme.md"), staticReadme, StandardCharsets.UTF_8);
        }
        writeIfMissing(dir.resolve("deepseek_static.md"), "templates/deepseek_static.md");
        if (!Files.isRegularFile(dir.resolve("readme.md"))) {
            String name = p.name == null || p.name.isBlank() ? dir.getFileName().toString() : p.name;
            Files.writeString(dir.resolve("readme.md"), "# " + name + "\n\nДинамический контекст проекта.\n",
                    StandardCharsets.UTF_8);
        }
        writeIfMissing(dir.resolve("file_index.md"), "templates/file_index.md");
        normalizePaths(p, dir);
    }

    private static void normalizePaths(Project p, Path dir) {
        p.basedMdPath = dir.resolve("based.md").toAbsolutePath().toString();
        p.qwenStaticReadmePath = dir.resolve("qwen_static_readme.md").toAbsolutePath().toString();
        p.deepseekStaticPath = dir.resolve("deepseek_static.md").toAbsolutePath().toString();
        p.projectDynamicReadmePath = dir.resolve("readme.md").toAbsolutePath().toString();
        p.fileIndexPath = dir.resolve("file_index.md").toAbsolutePath().toString();
        p.filesMdPath = dir.resolve("files.md").toAbsolutePath().toString();
        p.aiRulesPath = dir.resolve("ai_interaction_rules.md").toAbsolutePath().toString();
    }

    private void writeIfMissing(Path target, String resource) throws Exception {
        if (!Files.isRegularFile(target)) {
            writeTemplate(target, resource);
        }
    }

    private static String sanitize(String name) {
        String s = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.isBlank() || ".".equals(s) || "..".equals(s)) {
            throw new IllegalArgumentException("Недопустимое имя проекта");
        }
        return s;
    }

    private void deleteDirectory(Path candidate) throws Exception {
        Path root = projectsRoot().toAbsolutePath().normalize();
        Path target = candidate.toAbsolutePath().normalize();
        if (target.equals(root) || !target.startsWith(root)) {
            throw new SecurityException("Удаление разрешено только внутри каталога проектов");
        }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("Нельзя удалить проект через символическую ссылку");
        }
        deleteTree(target);
    }

    private static void deleteTree(Path root) throws Exception {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc)
                    throws java.io.IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void writeTemplate(Path target, String resource) throws Exception {
        Files.writeString(target, readResource(resource), StandardCharsets.UTF_8);
    }

    private String readResource(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
