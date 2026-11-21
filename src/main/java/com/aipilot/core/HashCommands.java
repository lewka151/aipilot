package com.aipilot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Hash commands from interface.md:
 * #edit_file(path)# body #delite_file(path)# #rename_file(from -&gt; to)#
 * #run_python(code)# #run_command(cmd)#
 */
public final class HashCommands {
    public record Result(List<FileOps.FileOp> files, List<CodeRunService.RunBlock> runs) {
    }

    private HashCommands() {
    }

    public static Result parse(String text) {
        List<FileOps.FileOp> files = new ArrayList<>();
        List<CodeRunService.RunBlock> runs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Result(files, runs);
        }
        String src = text;
        int i = 0;
        while (i < src.length()) {
            int hash = src.indexOf('#', i);
            if (hash < 0) {
                break;
            }
            ParsedTag tag = readTag(src, hash);
            if (tag == null) {
                i = hash + 1;
                continue;
            }
            switch (tag.name) {
                case "edit_file" -> {
                    if (FileOps.looksSafeRel(tag.arg)) {
                        String body = bodyUntilNext(src, tag.end);
                        files.add(new FileOps.FileOp(FileOps.WRITE, tag.arg.trim().replace('\\', '/'), "", body));
                    }
                }
                case "delite_file", "delete_file" -> {
                    if (FileOps.looksSafeRel(tag.arg)) {
                        files.add(new FileOps.FileOp(FileOps.DELETE, tag.arg.trim().replace('\\', '/'), "", ""));
                    }
                }
                case "rename_file" -> {
                    String[] parts = splitRename(tag.arg);
                    if (parts != null && FileOps.looksSafeRel(parts[0]) && FileOps.looksSafeRel(parts[1])) {
                        files.add(new FileOps.FileOp(FileOps.RENAME, parts[0], parts[1], ""));
                    }
                }
                case "run_python" -> {
                    if (!tag.arg.isBlank()) {
                        runs.add(new CodeRunService.RunBlock("python", tag.arg));
                    }
                }
                case "run_command" -> {
                    if (!tag.arg.isBlank()) {
                        runs.add(new CodeRunService.RunBlock("cmd", tag.arg));
                    }
                }
                default -> {
                }
            }
            i = tag.end;
        }
        return new Result(files, runs);
    }

    private static String[] splitRename(String arg) {
        String raw = arg == null ? "" : arg.trim();
        String[] byArrow = raw.split("\\s*->\\s*", 2);
        if (byArrow.length == 2) {
            return new String[]{byArrow[0].trim().replace('\\', '/'), byArrow[1].trim().replace('\\', '/')};
        }
        String[] byComma = raw.split("\\s*,\\s*", 2);
        if (byComma.length == 2) {
            return new String[]{byComma[0].trim().replace('\\', '/'), byComma[1].trim().replace('\\', '/')};
        }
        return null;
    }

    private static String bodyUntilNext(String src, int from) {
        int next = indexOfNextTag(src, from);
        String body = src.substring(from, next < 0 ? src.length() : next);
        return body.replaceFirst("^\\s+", "").replaceFirst("\\s+$", "");
    }

    private static int indexOfNextTag(String src, int from) {
        int i = from;
        while (i < src.length()) {
            int hash = src.indexOf('#', i);
            if (hash < 0) {
                return -1;
            }
            ParsedTag tag = readTag(src, hash);
            if (tag != null) {
                return hash;
            }
            i = hash + 1;
        }
        return -1;
    }

    private static ParsedTag readTag(String src, int hash) {
        if (hash < 0 || hash >= src.length() || src.charAt(hash) != '#') {
            return null;
        }
        int paren = src.indexOf('(', hash + 1);
        if (paren < 0) {
            return null;
        }
        String name = src.substring(hash + 1, paren).trim().toLowerCase(Locale.ROOT);
        if (!isCommand(name)) {
            return null;
        }
        int close = matchingClose(src, paren);
        if (close < 0 || close + 1 >= src.length() || src.charAt(close + 1) != '#') {
            return null;
        }
        String arg = src.substring(paren + 1, close);
        return new ParsedTag(name, arg, close + 2);
    }

    private static boolean isCommand(String name) {
        return "edit_file".equals(name)
                || "delite_file".equals(name)
                || "delete_file".equals(name)
                || "rename_file".equals(name)
                || "run_python".equals(name)
                || "run_command".equals(name);
    }

    private static int matchingClose(String src, int openParen) {
        int depth = 1;
        for (int i = openParen + 1; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private record ParsedTag(String name, String arg, int end) {
    }
}
