package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Which projects this installation may execute. */
public final class DeviceBinding {
    private DeviceBinding() {
    }

    public static boolean boundHere(Project project, String deviceKey) {
        if (project == null || deviceKey == null || deviceKey.isBlank()) {
            return false;
        }
        String bound = project.deviceId == null ? "" : project.deviceId.trim();
        if (bound.isEmpty() || !deviceKey.equalsIgnoreCase(bound)) {
            return false;
        }
        return hasAbsoluteFolder(project);
    }

    public static List<Project> boundProjects(List<Project> projects, String deviceKey) {
        List<Project> out = new ArrayList<>();
        if (projects == null) {
            return out;
        }
        for (Project project : projects) {
            if (boundHere(project, deviceKey)) {
                out.add(project);
            }
        }
        return out;
    }

    public static boolean hasAbsoluteFolder(Project project) {
        if (project == null || project.paths == null) {
            return false;
        }
        for (ProjectPath path : project.paths) {
            if (path == null || path.path == null || path.path.isBlank() || ".".equals(path.path)) {
                continue;
            }
            try {
                if (Path.of(path.path).isAbsolute()) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
