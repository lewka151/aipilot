package com.aipilot.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** Move legacy shared projects/ into users/{id}/projects on first login. */
public final class UserDataMigration {
    private static final Logger log = LoggerFactory.getLogger(UserDataMigration.class);

    private UserDataMigration() {
    }

    public static void migrateLegacyProjectsIfNeeded(AppPaths base, int userId) {
        Path legacy = base.legacyProjectsRoot();
        Path targetRoot = base.forUser(userId).projectsRoot();
        if (!Files.isDirectory(legacy)) {
            return;
        }
        try {
            boolean targetHasProjects;
            try (Stream<Path> s = Files.list(targetRoot)) {
                targetHasProjects = s.anyMatch(p -> Files.isDirectory(p) && Files.isRegularFile(p.resolve("project.json")));
            }
            if (targetHasProjects) {
                return;
            }
            try (Stream<Path> s = Files.list(legacy)) {
                long legacyCount = s.filter(p -> Files.isDirectory(p) && Files.isRegularFile(p.resolve("project.json"))).count();
                if (legacyCount == 0) {
                    return;
                }
            }
            Files.createDirectories(targetRoot);
            try (Stream<Path> s = Files.list(legacy)) {
                for (Path p : s.toList()) {
                    if (!Files.isDirectory(p) || !Files.isRegularFile(p.resolve("project.json"))) {
                        continue;
                    }
                    Path dest = targetRoot.resolve(p.getFileName());
                    if (Files.exists(dest)) {
                        continue;
                    }
                    Files.move(p, dest, StandardCopyOption.ATOMIC_MOVE);
                    log.info("Migrated project {} -> users/{}/projects/", p.getFileName(), userId);
                }
            }
        } catch (Exception e) {
            log.warn("Legacy project migration failed: {}", e.getMessage());
        }
    }
}
