package com.aipilot.infra.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/** Restrictive file permissions for secrets (auth.json, history). */
public final class SecureFiles {
    private static final Set<PosixFilePermission> OWNER_RW = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private SecureFiles() {
    }

    public static void restrictToOwner(Path file) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Path abs = file.toAbsolutePath();
                ProcessBuilder pb = new ProcessBuilder(
                        "icacls", abs.toString(), "/inheritance:r", "/grant:r",
                        System.getenv("USERNAME") + ":F");
                pb.redirectErrorStream(true);
                pb.start().waitFor();
            } else if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(file, OWNER_RW);
            }
        } catch (Exception ignored) {
        }
    }

    public static void restrictDirToOwner(Path dir) {
        try {
            Files.createDirectories(dir);
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win") && Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                Files.setPosixFilePermissions(dir, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            }
        } catch (Exception ignored) {
        }
    }
}
