package com.aipilot.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse and run python / PowerShell snippets sent by the models. Never auto-starts. */
public final class CodeRunService {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Pattern FENCE = Pattern.compile(
            "```(?:run\\s+)?(python|py|powershell|pwsh|ps1|cmd|shell|bash|sh|console)\\b[^\\r\\n]*\\r?\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGER = Pattern.compile(
            "(?i)(\\brm\\s+-rf\\s+/|\\bformat\\s+[a-z]:|\\bshutdown\\b|\\bmkfs\\b|"
                    + "Remove-Item\\s+-Recurse\\s+-Force\\s+[A-Z]:\\\\|"
                    + "Invoke-WebRequest\\s+http://|curl\\s+http://[^\\s]+\\|\\s*sh\\b|"
                    + "\\.\\.[\\\\/]|[A-Za-z]:\\\\|/etc/|/root/|"
                    + "\\bcd\\s+(\\.\\.|/|[A-Za-z]:)|\\bpushd\\s+(\\.\\.|/|[A-Za-z]:))");

    public record RunBlock(String language, String code) {
        public boolean python() {
            return "python".equals(language);
        }

        public boolean powershell() {
            return "powershell".equals(language);
        }

        public boolean shell() {
            return "cmd".equals(language) || "shell".equals(language) || "bash".equals(language);
        }
    }

    public record Result(int exitCode, String stdout, String stderr, boolean timedOut, String error) {
        public boolean ok() {
            return error == null && !timedOut && exitCode == 0;
        }
    }

    private CodeRunService() {
    }

    public static List<RunBlock> parse(String text) {
        List<RunBlock> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = FENCE.matcher(text);
        while (m.find()) {
            String lang = normalizeLang(m.group(1));
            String code = m.group(2) == null ? "" : m.group(2).strip();
            if (!code.isEmpty() && ("python".equals(lang) || "powershell".equals(lang) || "cmd".equals(lang)
                    || "shell".equals(lang) || "bash".equals(lang))) {
                out.add(new RunBlock(lang, code));
            }
        }
        return out;
    }

    public static String denyReason(String code) {
        if (code == null || code.isBlank()) {
            return "Пустой скрипт";
        }
        if (DANGER.matcher(code).find()) {
            return "Скрипт содержит опасную команду и не будет запущен";
        }
        return "";
    }

    public static Result run(RunBlock block, Path cwd) {
        return run(block, cwd, DEFAULT_TIMEOUT);
    }

    public static Result run(RunBlock block, Path cwd, Duration timeout) {
        if (block == null) {
            return new Result(-1, "", "", false, "Нет кода");
        }
        String denied = denyReason(block.code());
        if (!denied.isEmpty()) {
            return new Result(-1, "", "", false, denied);
        }
        Path work = cwd == null ? null : cwd.toAbsolutePath().normalize();
        if (work == null || !work.isAbsolute()) {
            return new Result(-1, "", "", false, "Нет главной папки проекта");
        }
        Duration cap = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        try {
            Files.createDirectories(work);
            if (block.python()) {
                return exec(pythonCommand(block.code()), work, cap);
            }
            if (block.powershell()) {
                Path script = work.resolve("reports").resolve("runs")
                        .resolve("run-" + System.currentTimeMillis() + ".ps1");
                Files.createDirectories(script.getParent());
                Files.writeString(script, block.code(), StandardCharsets.UTF_8);
                return exec(List.of(
                        "powershell.exe",
                        "-NoProfile",
                        "-ExecutionPolicy", "Bypass",
                        "-File", script.toString()
                ), work, cap);
            }
            if (block.shell()) {
                return exec(shellCommand(block.code()), work, cap);
            }
            return new Result(-1, "", "", false, "Язык не поддерживается: " + block.language());
        } catch (Exception e) {
            return new Result(-1, "", "", false, e.getMessage());
        }
    }

    private static List<String> shellCommand(String code) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return List.of("cmd.exe", "/c", code);
        }
        return List.of("sh", "-c", code);
    }

    private static List<String> pythonCommand(String code) {
        List<String> cmd = new ArrayList<>(findPython());
        cmd.add("-c");
        cmd.add(code);
        return cmd;
    }

    private static List<String> findPython() {
        List<List<String>> candidates = List.of(
                List.of("python"),
                List.of("py", "-3"),
                List.of("py")
        );
        for (List<String> candidate : candidates) {
            try {
                List<String> probe = new ArrayList<>(candidate);
                probe.add("-V");
                Process p = new ProcessBuilder(probe)
                        .redirectErrorStream(true)
                        .start();
                boolean ok = p.waitFor(4, TimeUnit.SECONDS);
                if (ok && p.exitValue() == 0) {
                    return candidate;
                }
                if (!ok) {
                    killTree(p);
                }
            } catch (Exception ignored) {
            }
        }
        return List.of("python");
    }

    private static Result exec(List<String> command, Path cwd, Duration timeout) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread tOut = pump(process.getInputStream(), stdout);
        Thread tErr = pump(process.getErrorStream(), stderr);
        tOut.start();
        tErr.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            killTree(process);
            tOut.join(500);
            tErr.join(500);
            return new Result(-1, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8),
                    true, "Таймаут " + timeout.toSeconds() + " с");
        }
        tOut.join(2000);
        tErr.join(2000);
        int code = process.exitValue();
        killTree(process);
        return new Result(code,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8),
                false,
                null);
    }

    private static void killTree(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (Exception ignored) {
        }
        process.destroyForcibly();
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream into) {
        return new Thread(() -> {
            try (in) {
                in.transferTo(into);
            } catch (Exception ignored) {
            }
        }, "code-run-pump");
    }

    private static String normalizeLang(String lang) {
        String s = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if ("py".equals(s) || "python".equals(s)) {
            return "python";
        }
        if ("pwsh".equals(s) || "ps1".equals(s) || "powershell".equals(s)) {
            return "powershell";
        }
        if ("cmd".equals(s) || "console".equals(s)) {
            return "cmd";
        }
        if ("sh".equals(s) || "bash".equals(s) || "shell".equals(s)) {
            return "shell";
        }
        return s;
    }
}
