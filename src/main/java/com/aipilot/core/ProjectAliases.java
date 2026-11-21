package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;

import java.util.ArrayList;
import java.util.List;

/** Parse and format "Name, path = alias, path = alias" project mappings. */
public final class ProjectAliases {
    private ProjectAliases() {
    }

    public static String displayName(Project project) {
        if (project == null) {
            return "";
        }
        if (project.displayName != null && !project.displayName.isBlank()) {
            return project.displayName.trim();
        }
        return project.name == null ? "" : project.name;
    }

    public static String format(Project project) {
        if (project == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(displayName(project));
        if (project.paths != null) {
            for (ProjectPath entry : project.paths) {
                if (entry == null || entry.path == null || entry.path.isBlank()) {
                    continue;
                }
                String path = entry.path.trim();
                if (".".equals(path) || "root".equalsIgnoreCase(entry.role)) {
                    continue;
                }
                String alias = entry.name != null && !entry.name.isBlank()
                        ? entry.name.trim()
                        : (entry.role == null || "root".equalsIgnoreCase(entry.role) ? path : entry.role.trim());
                sb.append(", ").append(path).append(" = ").append(alias);
            }
        }
        return sb.toString();
    }

    public static Parsed parse(String spec, String fallbackFolderName) {
        Parsed parsed = new Parsed();
        parsed.displayName = fallbackFolderName == null ? "" : fallbackFolderName.trim();
        parsed.paths = new ArrayList<>();
        parsed.paths.add(new ProjectPath(".", "root"));
        if (spec == null || spec.isBlank()) {
            return parsed;
        }
        String[] parts = spec.split(",");
        boolean first = true;
        for (String raw : parts) {
            String part = raw == null ? "" : raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            int eq = indexOfEquals(part);
            if (eq < 0) {
                if (first) {
                    parsed.displayName = part;
                }
                first = false;
                continue;
            }
            first = false;
            String path = part.substring(0, eq).trim();
            String alias = part.substring(eq + 1).trim();
            if (path.isEmpty()) {
                continue;
            }
            ProjectPath entry = new ProjectPath(path, alias.isBlank() ? path : alias);
            parsed.paths.add(entry);
        }
        if (parsed.displayName.isBlank() && fallbackFolderName != null) {
            parsed.displayName = fallbackFolderName;
        }
        return parsed;
    }

    private static int indexOfEquals(String part) {
        int spaced = part.indexOf(" = ");
        if (spaced >= 0) {
            return spaced + 1;
        }
        return part.indexOf('=');
    }

    public static final class Parsed {
        public String displayName = "";
        public List<ProjectPath> paths = new ArrayList<>();
    }
}
