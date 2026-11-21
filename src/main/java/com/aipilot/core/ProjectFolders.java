package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Extra folder roots registered on a project (alias → absolute path). */
public final class ProjectFolders {
    public static final String MAIN_ALIAS_LABEL = "Папка проекта";

    public record Root(String prefix, Path path, String sshAlias) {
        public Root(String prefix, Path path) {
            this(prefix, path, "");
        }

        public String sshAliasOrEmpty() {
            return sshAlias == null ? "" : sshAlias.trim();
        }
    }

    /** First extra (absolute) folder — cwd and local .aipilot config. */
    public static Path mainFolder(Project project) {
        List<Root> extras = extras(project);
        if (!extras.isEmpty() && extras.get(0).path() != null) {
            return extras.get(0).path().toAbsolutePath().normalize();
        }
        if (project != null && project.root != null && !project.root.isBlank() && !".".equals(project.root)) {
            try {
                Path root = project.rootPath().toAbsolutePath().normalize();
                if (root.isAbsolute()) {
                    return root;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static List<Root> extras(Project project) {
        List<Root> out = new ArrayList<>();
        if (project == null || project.paths == null) {
            return out;
        }
        Path projectRoot = project.root == null || project.root.isBlank()
                ? null
                : project.rootPath().toAbsolutePath().normalize();
        int absoluteIndex = 0;
        for (ProjectPath entry : project.paths) {
            if (entry == null || entry.path == null || entry.path.isBlank()) {
                continue;
            }
            String raw = entry.path.trim();
            if (".".equals(raw) || "root".equalsIgnoreCase(entry.role)) {
                continue;
            }
            Path folder = Path.of(raw);
            if (!folder.isAbsolute() && projectRoot != null) {
                folder = projectRoot.resolve(folder);
            }
            folder = folder.toAbsolutePath().normalize();
            boolean main = absoluteIndex == 0;
            absoluteIndex++;
            String prefix = prefixOf(entry, folder, main, project);
            if (prefix.isBlank()) {
                continue;
            }
            String ssh = entry.sshAlias == null ? "" : entry.sshAlias.trim();
            out.add(new Root(prefix, folder, ssh));
        }
        return out;
    }

    public static String prefixOf(ProjectPath entry, Path folder) {
        return prefixOf(entry, folder, false, null);
    }

    public static String prefixOf(ProjectPath entry, Path folder, boolean main, Project project) {
        if (main) {
            String fromProject = projectSlug(project);
            if (!fromProject.isBlank()) {
                return fromProject;
            }
        }
        String name = entry == null ? "" : (entry.name != null ? entry.name.trim() : "");
        if (name.isEmpty() && entry != null && entry.role != null) {
            name = entry.role.trim();
        }
        if (MAIN_ALIAS_LABEL.equalsIgnoreCase(name) || "main".equalsIgnoreCase(name)) {
            String fromProject = projectSlug(project);
            if (!fromProject.isBlank()) {
                return fromProject;
            }
            if (folder != null && folder.getFileName() != null) {
                return sanitizePrefix(folder.getFileName().toString());
            }
            return "main";
        }
        if (isValidPrefix(name) && !"root".equalsIgnoreCase(name)) {
            return name;
        }
        if (folder != null && folder.getFileName() != null) {
            String last = sanitizePrefix(folder.getFileName().toString());
            if (!last.isBlank()) {
                return last;
            }
        }
        return "extra";
    }

    /** Display / default folder name from project. */
    public static String defaultMainFolderName(Project project) {
        String slug = projectSlug(project);
        if (!slug.isBlank()) {
            return slug;
        }
        return "project";
    }

    private static String projectSlug(Project project) {
        if (project == null) {
            return "";
        }
        String name = project.displayName == null || project.displayName.isBlank()
                ? project.name : project.displayName;
        if (name == null || name.isBlank()) {
            name = project.id;
        }
        return sanitizePrefix(name == null ? "" : name);
    }

    /** Keep Unicode letters/digits; collapse other runs to '-'. */
    public static String sanitizePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean dash = false;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) || cp == '.' || cp == '_' ) {
                sb.appendCodePoint(cp);
                dash = false;
            } else if (cp == '-') {
                if (!dash && sb.length() > 0) {
                    sb.append('-');
                    dash = true;
                }
            } else if (!dash && sb.length() > 0) {
                sb.append('-');
                dash = true;
            }
        }
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static boolean isValidPrefix(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (!(Character.isLetterOrDigit(cp) || cp == '.' || cp == '_' || cp == '-')) {
                return false;
            }
        }
        return true;
    }
}
