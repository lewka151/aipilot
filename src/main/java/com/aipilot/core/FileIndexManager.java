package com.aipilot.core;

import com.aipilot.core.model.Project;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Scans project paths and maintains files.md + file_index.md. */
public final class FileIndexManager {
    private static final Pattern FILES_SECTION = Pattern.compile(
            "^##\\s+(.+)$", Pattern.MULTILINE);
    private static final String NO_DESC = "(описание не задано)";

    public String rescanPreservingDescriptions(Project project) throws Exception {
        Path root = project.rootPath();
        Path indexFile = root.resolve("file_index.md");
        Path filesMd = root.resolve("files.md");
        Map<String, String> existingIndex = parseIndex(indexFile);
        Map<String, String> existingFiles = parseFilesMd(filesMd);

        Map<String, String> next = new LinkedHashMap<>();
        scanTree(root, "", existingFiles, existingIndex, next);
        for (ProjectFolders.Root extra : ProjectFolders.extras(project)) {
            if (extra.path() == null || !Files.isDirectory(extra.path())) {
                continue;
            }
            scanTree(extra.path(), extra.prefix(), existingFiles, existingIndex, next);
        }

        String indexMd = writeIndex(indexFile, next);
        writeFilesMd(filesMd, next);
        if (project.filesMdPath == null || project.filesMdPath.isBlank()) {
            project.filesMdPath = filesMd.toAbsolutePath().toString();
        }
        return indexMd;
    }

    public static Path filesMdPath(Project project) {
        if (project.filesMdPath != null && !project.filesMdPath.isBlank()) {
            return Path.of(project.filesMdPath);
        }
        return project.rootPath().resolve("files.md");
    }

    private static String writeIndex(Path indexFile, Map<String, String> next) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Индекс файлов\n\n");
        sb.append("путь | краткое_описание\n");
        for (Map.Entry<String, String> e : next.entrySet()) {
            sb.append(e.getKey()).append(" | ").append(e.getValue()).append('\n');
        }
        String md = sb.toString();
        Files.writeString(indexFile, md, StandardCharsets.UTF_8);
        return md;
    }

    private static void writeFilesMd(Path filesMd, Map<String, String> next) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Файлы\n\n");
        sb.append("Каталог файлов проекта: путь и краткое назначение.\n\n");
        for (Map.Entry<String, String> e : next.entrySet()) {
            sb.append("## ").append(e.getKey()).append('\n');
            sb.append(e.getValue()).append("\n\n");
        }
        Files.writeString(filesMd, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void scanTree(Path scanRoot, String prefix, Map<String, String> existingFiles,
                                 Map<String, String> existingIndex, Map<String, String> next) {
        Path root = scanRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !shouldSkip(root, p))
                    .sorted()
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        String stored = prefix == null || prefix.isBlank() ? rel : prefix + "/" + rel;
                        String fromFiles = existingFiles.get(stored);
                        String desc = fromFiles != null && !fromFiles.isBlank()
                                ? fromFiles
                                : existingIndex.getOrDefault(stored, guessDescription(stored));
                        if (desc.isBlank()) {
                            desc = NO_DESC;
                        }
                        next.put(stored, desc);
                    });
        } catch (Exception ignored) {
        }
    }

    private static boolean shouldSkip(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (rel.startsWith(".git/") || rel.contains("/.git/")) {
            return true;
        }
        if (rel.startsWith("history/") || rel.startsWith("attachments/")) {
            return true;
        }
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".class") || name.endsWith(".jar") || name.equals("project.json");
    }

    private static Map<String, String> parseIndex(Path indexFile) {
        Map<String, String> map = new HashMap<>();
        if (!Files.isRegularFile(indexFile)) {
            return map;
        }
        try {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("path |") || t.startsWith("путь |")) {
                    continue;
                }
                int bar = t.indexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String path = t.substring(0, bar).trim();
                String desc = t.substring(bar + 1).trim();
                map.put(path, desc);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static Map<String, String> parseFilesMd(Path filesMd) {
        Map<String, String> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(filesMd)) {
            return map;
        }
        try {
            String text = Files.readString(filesMd, StandardCharsets.UTF_8);
            Matcher m = FILES_SECTION.matcher(text);
            int lastEnd = 0;
            String lastPath = null;
            while (m.find()) {
                if (lastPath != null) {
                    String body = text.substring(lastEnd, m.start()).trim();
                    map.put(lastPath, body);
                }
                lastPath = m.group(1).trim();
                lastEnd = m.end();
            }
            if (lastPath != null) {
                String body = text.substring(lastEnd).trim();
                map.put(lastPath, body);
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static String guessDescription(String rel) {
        if (rel.equals("readme.md")) {
            return "Динамический контекст проекта";
        }
        if (rel.equals("based.md")) {
            return "Базовая инструкция проекта";
        }
        if (rel.equals("chat_configs.md")) {
            return "Уникальные имена чатов Qwen и DeepSeek";
        }
        if (rel.equals("plan_instruction.md")) {
            return "Инструкция DeepSeek: детальный план STEP N";
        }
        if (rel.equals("plan_review_instruction.md")) {
            return "Инструкция DeepSeek: ревью плана или DONE";
        }
        if (rel.equals("ai_interaction_rules.md")) {
            return "Правила взаимодействия с ИИ";
        }
        if (rel.equals("deepseek_static.md")) {
            return "Статическая роль DeepSeek (подготовка промпта)";
        }
        if (rel.equals("qwen_static_readme.md")) {
            return "Статическая роль Qwen (исполнение)";
        }
        if (rel.equals("file_index.md")) {
            return "Таблица индекса файлов";
        }
        if (rel.equals("files.md")) {
            return "Каталог файлов с описаниями";
        }
        if (rel.startsWith("scripts/")) {
            return "Скрипт";
        }
        if (rel.startsWith("reports/")) {
            return "Отчёт";
        }
        return "";
    }
}
