package com.aipilot.infra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Windows Startup folder shortcut to AIPilot.exe --tray. */
public final class WindowsAutostart {
    private static final Logger log = LoggerFactory.getLogger(WindowsAutostart.class);
    public static final String SHORTCUT_NAME = "AIPilot.lnk";

    private WindowsAutostart() {
    }

    public static Path defaultStartupDir() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isBlank()) {
            appdata = System.getProperty("user.home");
        }
        return Path.of(appdata, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    public static Path shortcutPath(Path startupDir) {
        Path dir = startupDir == null ? defaultStartupDir() : startupDir;
        return dir.resolve(SHORTCUT_NAME);
    }

    public static Path resolveLaunchTarget() {
        Optional<String> command = ProcessHandle.current().info().command();
        if (command.isPresent()) {
            Path current = Path.of(command.get()).toAbsolutePath().normalize();
            String name = current.getFileName() == null ? "" : current.getFileName().toString();
            if (name.equalsIgnoreCase("AIPilot.exe")) {
                return current;
            }
            Path sibling = current.getParent() == null ? null : current.getParent().resolve("AIPilot.exe");
            if (sibling != null && Files.isRegularFile(sibling)) {
                return sibling;
            }
        }
        Path dist = Path.of(System.getProperty("user.dir", ".")).resolve("dist")
                .resolve("AIPilot").resolve("AIPilot.exe");
        if (Files.isRegularFile(dist)) {
            return dist.toAbsolutePath().normalize();
        }
        return null;
    }

    public static boolean isEnabled(Path startupDir) {
        return Files.isRegularFile(shortcutPath(startupDir));
    }

    public static boolean apply(boolean enabled, Path startupDir) {
        return apply(enabled, startupDir, resolveLaunchTarget());
    }

    public static boolean apply(boolean enabled, Path startupDir, Path targetExe) {
        Path link = shortcutPath(startupDir);
        try {
            if (!enabled) {
                Files.deleteIfExists(link);
                return true;
            }
            if (targetExe == null || !Files.isRegularFile(targetExe)) {
                log.warn("Autostart skipped: AIPilot.exe not found");
                return false;
            }
            Files.createDirectories(link.getParent());
            String ps = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
                    + escapePs(link.toAbsolutePath().toString()) + "'); "
                    + "$s.TargetPath = '" + escapePs(targetExe.toAbsolutePath().toString()) + "'; "
                    + "$s.Arguments = '--tray'; "
                    + "$s.WorkingDirectory = '"
                    + escapePs(targetExe.getParent() == null
                    ? "" : targetExe.getParent().toAbsolutePath().toString()) + "'; "
                    + "$s.Save()";
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-STA", "-Command", ps)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.isRegularFile(link);
        } catch (Exception e) {
            log.warn("Autostart failed: {}", e.getMessage());
            return false;
        }
    }

    private static String escapePs(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
