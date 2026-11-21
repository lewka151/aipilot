package com.aipilot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse file/ssh XML from model answers. Paths stay relative or alias/rel. */
public final class FileOps {
    public static final String WRITE = "write";
    public static final String DELETE = "delete";
    public static final String RENAME = "rename";

    private static final Pattern XML_FENCE = Pattern.compile(
            "```xml\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_TAG = Pattern.compile(
            "<file\\s+([^>]*?)(?:\\s*/>|>([\\s\\S]*?)</file>)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SSH_TAG = Pattern.compile(
            "<ssh\\s+alias=\"([^\"]+)\">([\\s\\S]*?)</ssh>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile(
            "(\\w+)\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    public record FileOp(String op, String path, String toPath, String content) {
        public boolean write() {
            return WRITE.equals(op);
        }

        public boolean delete() {
            return DELETE.equals(op);
        }

        public boolean rename() {
            return RENAME.equals(op);
        }
    }

    public record SshOp(String alias, String command) {
    }

    private FileOps() {
    }

    public static List<FileOp> parseFiles(String text) {
        List<FileOp> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher fence = XML_FENCE.matcher(text);
        StringBuilder blob = new StringBuilder();
        boolean any = false;
        while (fence.find()) {
            any = true;
            blob.append(fence.group(1)).append('\n');
        }
        String search = any ? blob.toString() : text;
        Matcher files = FILE_TAG.matcher(search);
        while (files.find()) {
            FileOp op = fromAttrs(files.group(1), files.group(2));
            if (op != null) {
                out.add(op);
            }
        }
        return out;
    }

    public static List<SshOp> parseSsh(String text) {
        List<SshOp> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = SSH_TAG.matcher(text);
        while (m.find()) {
            String alias = m.group(1) == null ? "" : m.group(1).trim();
            String cmd = trimBody(m.group(2));
            if (!alias.isBlank() && !cmd.isBlank()) {
                out.add(new SshOp(alias, cmd));
            }
        }
        return out;
    }

    public static boolean looksSafeRel(String rel) {
        String norm = rel == null ? "" : rel.trim().replace('\\', '/');
        if (norm.isBlank() || norm.contains("..")) {
            return false;
        }
        if (norm.startsWith("/") || norm.startsWith("~")) {
            return false;
        }
        return !(norm.length() >= 2 && Character.isLetter(norm.charAt(0)) && norm.charAt(1) == ':');
    }

    private static FileOp fromAttrs(String attrBlob, String body) {
        String path = "";
        String op = WRITE;
        String to = "";
        Matcher attrs = ATTR.matcher(attrBlob == null ? "" : attrBlob);
        while (attrs.find()) {
            String key = attrs.group(1).toLowerCase(Locale.ROOT);
            String val = attrs.group(2).trim().replace('\\', '/');
            switch (key) {
                case "path", "from" -> path = val;
                case "op" -> op = normalizeOp(val);
                case "to" -> to = val;
                default -> {
                }
            }
        }
        if (!looksSafeRel(path)) {
            return null;
        }
        if (RENAME.equals(op) && !looksSafeRel(to)) {
            return null;
        }
        String content = DELETE.equals(op) || RENAME.equals(op) ? "" : trimBody(body);
        return new FileOp(op, path, to, content);
    }

    private static String normalizeOp(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("delete") || s.equals("remove") || s.equals("unlink")) {
            return DELETE;
        }
        if (s.equals("rename") || s.equals("move") || s.equals("mv")) {
            return RENAME;
        }
        return WRITE;
    }

    private static String trimBody(String body) {
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
}
