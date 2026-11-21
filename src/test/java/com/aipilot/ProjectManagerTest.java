package com.aipilot;

import com.aipilot.core.ProjectManager;
import com.aipilot.core.model.Project;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectManagerTest {
    @TempDir
    Path tmp;

    @Test
    void createsLoadsSavesListsAndDeletesProject() throws Exception {
        ProjectManager manager = manager();
        Project created = manager.create("Тестовый проект", "initial");

        assertTrue(Files.isRegularFile(created.rootPath().resolve("project.json")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("chat_configs.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("plan_instruction.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("plan_review_instruction.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("based.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("ai_interaction_rules.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("qwen_static_readme.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("deepseek_static.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("readme.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("files.md")));
        assertTrue(Files.isRegularFile(created.rootPath().resolve("file_index.md")));
        assertEquals(1, manager.listProjectNames().size());

        created.description = "updated";
        created.qwenProjectName = "Qwen project";
        manager.save(created);
        Project loaded = manager.load(created.rootPath());
        assertEquals("updated", loaded.description);
        assertEquals("Qwen project", loaded.qwenProjectName);
        assertEquals("Тестовый проект", loaded.displayName);
        assertEquals("Тестовый проект", manager.displayLabel(loaded.name));

        manager.delete(loaded);
        assertFalse(Files.exists(loaded.rootPath()));
        assertTrue(manager.listProjectNames().isEmpty());
    }

    @Test
    void remapsStaleAbsoluteMarkdownPathsAndCreatesMissingBased() throws Exception {
        ProjectManager manager = manager();
        Project created = manager.create("my-project", "");
        Path stale = tmp.resolve("legacy").resolve("my-project").resolve("based.md");
        Files.deleteIfExists(created.rootPath().resolve("based.md"));
        Path json = created.rootPath().resolve("project.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode node = (ObjectNode) mapper.readTree(Files.readString(json));
        node.put("basedMdPath", stale.toAbsolutePath().toString());
        Files.writeString(json, mapper.writeValueAsString(node) + "\n");

        Project loaded = manager.load(created.rootPath());
        Path expected = created.rootPath().resolve("based.md");
        assertEquals(expected.toAbsolutePath().toString(), loaded.basedMdPath);
        assertTrue(Files.isRegularFile(expected));
        Files.writeString(expected, "updated based");
        loaded.basedMdPath = stale.toString();
        manager.save(loaded);
        Project saved = manager.load(created.rootPath());
        assertEquals(expected.toAbsolutePath().toString(), saved.basedMdPath);
        assertEquals("updated based", Files.readString(expected));
    }

    @Test
    void relocatesMissingRootAndWritesBasedMarkdown() throws Exception {
        ProjectManager manager = manager();
        Project created = manager.create("ai-project2", "");
        Path real = created.rootPath();
        created.root = tmp.resolve("missing").resolve("ai-project2").toString();
        manager.writeProjectFile(created, "based.md", "hello from relocate");
        assertEquals(real.toAbsolutePath().normalize().toString(),
                Path.of(created.root).toAbsolutePath().normalize().toString());
        assertEquals("hello from relocate", Files.readString(real.resolve("based.md")));
    }

    @Test
    void rejectsBlankDuplicateAndOutsideDelete() throws Exception {
        ProjectManager manager = manager();
        assertThrows(IllegalArgumentException.class, () -> manager.create("  ", ""));
        Project project = manager.create("demo", "");
        assertThrows(IllegalStateException.class, () -> manager.create("demo", ""));

        Project outside = new Project();
        outside.root = tmp.resolve("outside").toString();
        assertThrows(SecurityException.class, () -> manager.delete(outside));
        assertTrue(Files.exists(project.rootPath()));
    }

    @Test
    void savesDisplayNameAndAliasPaths() throws Exception {
        ProjectManager manager = manager();
        Project created = manager.create("Проект 1", "");
        var parsed = com.aipilot.core.ProjectAliases.parse(
                "Проект 1, h:/1/s = проект s, C:/Games/Saves/f = проект П", created.name);
        created.displayName = parsed.displayName;
        created.paths = parsed.paths;
        manager.save(created);
        Project loaded = manager.load(created.rootPath());
        assertEquals("Проект 1", loaded.displayName);
        assertEquals("Проект 1", manager.displayLabel(loaded.name));
        assertTrue(loaded.paths.stream().anyMatch(p -> "h:/1/s".equals(p.path) && "проект s".equals(p.name)));
        assertTrue(loaded.paths.stream().anyMatch(p -> "C:/Games/Saves/f".equals(p.path) && "проект П".equals(p.name)));
    }

    private ProjectManager manager() throws Exception {
        Path app = tmp.resolve("app");
        Path projects = app.resolve("projects");
        Path logs = app.resolve("logs");
        Files.createDirectories(projects);
        Files.createDirectories(logs);
        var ctor = AppPaths.class.getDeclaredConstructor(Path.class, Path.class, Path.class);
        ctor.setAccessible(true);
        AppPaths paths = ctor.newInstance(app, projects, logs);
        return new ProjectManager(paths, new AppConfig());
    }
}
