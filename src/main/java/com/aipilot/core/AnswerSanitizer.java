package com.aipilot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strip page-chrome HTML from an assistant answer without touching file bodies or run fences.
 */
public final class AnswerSanitizer {
    private static final Pattern XML_FENCE = Pattern.compile(
            "```xml\\s*([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_FENCE = Pattern.compile(
            "```(?:md|markdown|file)\\s+(?:path\\s*=\\s*)?[\"']?([\\w./\\\\-]+\\.[\\w]+)[\"']?\\s*\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RUN_FENCE = Pattern.compile(
            "```(?:run\\s+)?(python|py|powershell|pwsh|ps1)\\b[^\\r\\n]*\\r?\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_PLACEHOLDER = Pattern.compile("<file\\b[^>]*(?:/>|>[\\s\\S]*?</file>)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH_PLACEHOLDER = Pattern.compile(
            "<ssh\\s+alias=\"[^\"]+\">[\\s\\S]*?</ssh>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile(
            "</?(?:div|nav|header|footer|section|article|button|svg|path|span|a|ul|ol|li|p|h[1-6]|script|style|img|input|label|form|aside|main|hr|br|i|b|em|strong|table|thead|tbody|tr|td|th)(?:\\s[^>]*)?/?>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHROME_HINT = Pattern.compile(
            "(?i)class\\s*=|qwen-|ds-markdown|copy\\s+share|stop generating|aria-label");

    public record FileBlock(String path, String content, String op, String toPath) {
        public FileBlock(String path, String content) {
            this(path, content, FileOps.WRITE, "");
        }
    }

    public record RunBlock(String language, String code) {
    }

    public record Parsed(String narrative, List<FileBlock> files, List<RunBlock> runs,
                         List<FileOps.SshOp> ssh, String reassembled) {
        public Parsed(String narrative, List<FileBlock> files, List<RunBlock> runs, String reassembled) {
            this(narrative, files, runs, List.of(), reassembled);
        }
    }

    private AnswerSanitizer() {
    }

    public static String clean(String raw) {
        return parse(raw).reassembled();
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed("", List.of(), List.of(), List.of(), "");
        }
        List<FileBlock> files = new ArrayList<>();
        for (FileOps.FileOp op : FileOps.parseFiles(raw)) {
            files.add(new FileBlock(op.path(), op.content(), op.op(), op.toPath()));
        }
        List<FileOps.SshOp> ssh = FileOps.parseSsh(raw);
        List<RunBlock> runs = new ArrayList<>();
        String work = raw;

        Matcher xmlFence = XML_FENCE.matcher(work);
        StringBuffer fenced = new StringBuffer();
        while (xmlFence.find()) {
            xmlFence.appendReplacement(fenced, "\u0000");
        }
        xmlFence.appendTail(fenced);
        work = fenced.toString();

        work = FILE_PLACEHOLDER.matcher(work).replaceAll("\u0000");
        work = SSH_PLACEHOLDER.matcher(work).replaceAll("\u0000");

        Matcher md = MD_FENCE.matcher(work);
        StringBuffer mdBuf = new StringBuffer();
        while (md.find()) {
            files.add(new FileBlock(md.group(1).trim().replace('\\', '/'), trimFileBody(md.group(2))));
            md.appendReplacement(mdBuf, "\u0000");
        }
        md.appendTail(mdBuf);
        work = mdBuf.toString();

        Matcher run = RUN_FENCE.matcher(work);
        StringBuffer runBuf = new StringBuffer();
        while (run.find()) {
            runs.add(new RunBlock(normalizeLang(run.group(1)), trimFileBody(run.group(2))));
            run.appendReplacement(runBuf, "\u0000");
        }
        run.appendTail(runBuf);
        work = runBuf.toString();

        String narrative = stripChrome(work.replace("\u0000", "\n"));
        String reassembled = reassemble(narrative, files, runs, ssh);
        return new Parsed(narrative, List.copyOf(files), List.copyOf(runs), List.copyOf(ssh), reassembled);
    }

    public static String reassemble(String narrative, List<FileBlock> files, List<RunBlock> runs) {
        return reassemble(narrative, files, runs, List.of());
    }

    public static String reassemble(String narrative, List<FileBlock> files, List<RunBlock> runs,
                                    List<FileOps.SshOp> ssh) {
        StringBuilder sb = new StringBuilder();
        if (narrative != null && !narrative.isBlank()) {
            sb.append(narrative.strip());
        }
        if (files != null) {
            for (FileBlock file : files) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(renderFile(file));
            }
        }
        if (ssh != null) {
            for (FileOps.SshOp op : ssh) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("```xml\n<ssh alias=\"").append(op.alias()).append("\">\n")
                        .append(op.command() == null ? "" : op.command())
                        .append("\n</ssh>\n```");
            }
        }
        if (runs != null) {
            for (RunBlock block : runs) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append("```").append(block.language()).append('\n')
                        .append(block.code() == null ? "" : block.code())
                        .append("\n```");
            }
        }
        return sb.toString().strip();
    }

    private static String renderFile(FileBlock file) {
        String op = file.op() == null || file.op().isBlank() ? FileOps.WRITE : file.op();
        String path = file.path() == null ? "" : file.path();
        if (FileOps.DELETE.equals(op)) {
            return "```xml\n<file path=\"" + path + "\" op=\"delete\"/>\n```";
        }
        if (FileOps.RENAME.equals(op)) {
            return "```xml\n<file path=\"" + path + "\" op=\"rename\" to=\""
                    + (file.toPath() == null ? "" : file.toPath()) + "\"/>\n```";
        }
        return "```xml\n<file path=\"" + path + "\">\n"
                + (file.content() == null ? "" : file.content())
                + "\n</file>\n```";
    }

    private static String stripChrome(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        if (CHROME_HINT.matcher(s).find() || TAG.matcher(s).find()) {
            s = TAG.matcher(s).replaceAll(" ");
            s = s.replaceAll("(?is)<[^>]+>", " ");
        }
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    private static String trimFileBody(String body) {
        if (body == null) {
            return "";
        }
        String s = body;
        if (s.startsWith("\r\n")) {
            s = s.substring(2);
        } else if (s.startsWith("\n")) {
            s = s.substring(1);
        }
        return s.replaceAll("\\s+$", "");
    }

    private static String normalizeLang(String lang) {
        String s = lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT);
        if ("py".equals(s) || "python".equals(s)) {
            return "python";
        }
        if ("pwsh".equals(s) || "ps1".equals(s) || "powershell".equals(s)) {
            return "powershell";
        }
        return s;
    }
}
