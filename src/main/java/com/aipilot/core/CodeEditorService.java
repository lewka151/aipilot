package com.aipilot.core;

import com.aipilot.core.model.FileEditProposal;
import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CodeEditorService {
    private static final Pattern MD_FENCE = Pattern.compile(
            "```(?:md|markdown|file)\\s+(?:path\\s*=\\s*)?[\"']?([\\w./\\\\-]+\\.md)[\"']?\\s*\\n([\\s\\S]*?)```",
            Pattern.CASE_INSENSITIVE);

    public List<FileEditProposal> parseProposals(String assistantMessage, Path projectRoot) {
        return parseProposals(assistantMessage, projectRoot, List.of());
    }

    public List<FileEditProposal> parseProposals(String assistantMessage, Project project) {
        Path root = project == null ? Path.of(".") : project.rootPath();
        return parseProposals(assistantMessage, root, ProjectFolders.extras(project));
    }

    public List<FileEditProposal> parseProposals(String assistantMessage, Path projectRoot,
                                                 List<ProjectFolders.Root> extras) {
        List<FileEditProposal> out = new ArrayList<>();
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return out;
        }
        for (FileOps.FileOp op : FileOps.parseFiles(assistantMessage)) {
            addProposal(out, projectRoot, extras, op.path(), op.content(), op.op(), op.toPath());
        }
        var hash = HashCommands.parse(assistantMessage);
        for (FileOps.FileOp op : hash.files()) {
            addProposal(out, projectRoot, extras, op.path(), op.content(), op.op(), op.toPath());
        }
        Matcher markdown = MD_FENCE.matcher(assistantMessage);
        while (markdown.find()) {
            addProposal(out, projectRoot, extras, markdown.group(1), markdown.group(2), FileOps.WRITE, "");
        }
        return out;
    }

    public void apply(FileEditProposal proposal, Path projectRoot) throws Exception {
        apply(proposal, projectRoot, List.of(), null);
    }

    public void apply(FileEditProposal proposal, Project project) throws Exception {
        Path root = project == null ? Path.of(".") : project.rootPath();
        apply(proposal, root, ProjectFolders.extras(project), project);
    }

    public void apply(FileEditProposal proposal, Path projectRoot, List<ProjectFolders.Root> extras)
            throws Exception {
        apply(proposal, projectRoot, extras, null);
    }

    public void apply(FileEditProposal proposal, Path projectRoot, List<ProjectFolders.Root> extras, Project project)
            throws Exception {
        if (proposal == null || proposal.path() == null || proposal.path().isBlank()) {
            return;
        }
        ProjectFolders.Root extra = matchingExtra(extras, proposal.path());
        if (extra != null && !extra.sshAliasOrEmpty().isBlank() && project != null) {
            applyRemote(proposal, extra, project);
            return;
        }
        if (proposal.delete()) {
            Path target = safeTarget(projectRoot, extras, proposal.path());
            Files.deleteIfExists(target);
            return;
        }
        if (proposal.rename()) {
            Path from = safeTarget(projectRoot, extras, proposal.path());
            Path to = safeTarget(projectRoot, extras, proposal.toPath());
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        Path target = safeTarget(projectRoot, extras, proposal.path());
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, proposal.newContent() == null ? "" : proposal.newContent(), StandardCharsets.UTF_8);
    }

    public static boolean isMarkdownPath(String rel) {
        if (rel == null || rel.isBlank()) {
            return false;
        }
        String name = Path.of(rel.replace('\\', '/')).getFileName().toString().toLowerCase();
        return name.endsWith(".md");
    }

    private void applyRemote(FileEditProposal proposal, ProjectFolders.Root extra, Project project) throws Exception {
        ProjectServer server = findServer(project, extra.sshAliasOrEmpty());
        if (server == null) {
            throw new IllegalStateException("Нет SSH «" + extra.sshAliasOrEmpty() + "»");
        }
        String from = remotePath(extra, proposal.path());
        if (proposal.delete()) {
            SshService.sftpDelete(server, from);
            return;
        }
        if (proposal.rename()) {
            SshService.sftpRename(server, from, remotePath(extra, proposal.toPath()));
            return;
        }
        SshService.sftpWrite(server, from, proposal.newContent() == null ? "" : proposal.newContent());
    }

    private static ProjectServer findServer(Project project, String alias) {
        if (project == null || project.servers == null || alias == null || alias.isBlank()) {
            return null;
        }
        for (ProjectServer server : project.servers) {
            if (server != null && alias.equalsIgnoreCase(server.aliasOrEmpty())) {
                return server;
            }
        }
        return null;
    }

    private static String remotePath(ProjectFolders.Root extra, String rel) {
        String norm = rel == null ? "" : rel.trim().replace('\\', '/');
        String prefix = extra.prefix();
        String rest = norm.equals(prefix) ? "" : (norm.startsWith(prefix + "/") ? norm.substring(prefix.length() + 1) : norm);
        String root = extra.path() == null ? "" : extra.path().toString().replace('\\', '/');
        if (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (rest.isBlank()) {
            return root;
        }
        return root + "/" + rest;
    }

    private static ProjectFolders.Root matchingExtra(List<ProjectFolders.Root> extras, String rel) {
        String norm = rel == null ? "" : rel.trim().replace('\\', '/');
        if (extras == null) {
            return null;
        }
        for (ProjectFolders.Root extra : extras) {
            if (extra == null || extra.prefix() == null) {
                continue;
            }
            String prefix = extra.prefix();
            if (norm.equals(prefix) || norm.startsWith(prefix + "/")) {
                return extra;
            }
        }
        return null;
    }

    private void addProposal(List<FileEditProposal> out, Path projectRoot, List<ProjectFolders.Root> extras,
                             String rawPath, String rawContent, String op, String toPath) {
        String rel = rawPath == null ? "" : rawPath.trim().replace('\\', '/');
        if (rel.isBlank() || !FileOps.looksSafeRel(rel)) {
            return;
        }
        if ("rename".equals(op) && !FileOps.looksSafeRel(toPath)) {
            return;
        }
        String neu = rawContent == null ? "" : rawContent;
        if (neu.startsWith("\n")) {
            neu = neu.substring(1);
        }
        try {
            if (!"delete".equals(op) && !"rename".equals(op)) {
                safeTarget(projectRoot, extras, rel);
            } else {
                safeTarget(projectRoot, extras, rel);
                if ("rename".equals(op)) {
                    safeTarget(projectRoot, extras, toPath);
                }
            }
        } catch (Exception e) {
            return;
        }
        String old = "";
        if ("write".equals(op) || op == null || op.isBlank()) {
            try {
                Path target = safeTarget(projectRoot, extras, rel);
                if (Files.isRegularFile(target)) {
                    old = Files.readString(target, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {
            }
        }
        out.add(new FileEditProposal(rel, old, neu, op == null || op.isBlank() ? FileOps.WRITE : op,
                toPath == null ? "" : toPath.trim().replace('\\', '/')));
    }

    public static Path resolveTarget(Path projectRoot, List<ProjectFolders.Root> extras, String rel) {
        return safeTarget(projectRoot, extras, rel);
    }

    static Path safeTarget(Path projectRoot, List<ProjectFolders.Root> extras, String rel) {
        String norm = rel == null ? "" : rel.trim().replace('\\', '/');
        if (norm.isBlank() || norm.contains("..") || looksAbsolute(norm)) {
            throw new SecurityException("Path escapes project root: " + rel);
        }
        List<ProjectFolders.Root> extraList = extras == null ? List.of() : extras;
        for (ProjectFolders.Root extra : extraList) {
            if (extra == null || extra.prefix() == null || extra.path() == null) {
                continue;
            }
            String prefix = extra.prefix();
            if (norm.equals(prefix) || norm.startsWith(prefix + "/")) {
                String rest = norm.equals(prefix) ? "" : norm.substring(prefix.length() + 1);
                Path root = extra.path().toAbsolutePath().normalize();
                Path target = root.resolve(rest).normalize();
                if (!contained(root, target)) {
                    throw new SecurityException("Path escapes extra root: " + rel);
                }
                return target;
            }
        }
        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = root.resolve(norm).normalize();
        if (!contained(root, target)) {
            throw new SecurityException("Path escapes project root: " + rel);
        }
        return target;
    }

    private static boolean contained(Path root, Path target) {
        Path r = root.toAbsolutePath().normalize();
        Path t = target.toAbsolutePath().normalize();
        if (!t.startsWith(r)) {
            return false;
        }
        try {
            Path realRoot = Files.exists(r) ? r.toRealPath() : r;
            Path probe = t;
            if (Files.exists(t)) {
                probe = t.toRealPath();
            } else if (t.getParent() != null && Files.exists(t.getParent())) {
                probe = t.getParent().toRealPath().resolve(t.getFileName());
            }
            return probe.startsWith(realRoot);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean looksAbsolute(String rel) {
        if (rel.startsWith("/") || rel.startsWith("~")) {
            return true;
        }
        return rel.length() >= 2 && Character.isLetter(rel.charAt(0)) && rel.charAt(1) == ':';
    }
}
