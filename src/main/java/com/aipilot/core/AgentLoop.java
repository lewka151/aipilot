package com.aipilot.core;

import com.aipilot.core.model.FileEditProposal;
import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectServer;
import com.aipilot.core.model.SafetyVerdict;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.api.SiteBridgeClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.ui.AppVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Polls site commands, sandboxes paths, reviews run/ssh via DeepSeek, acks results. */
public final class AgentLoop {
    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final int TRAY_POLL_SECONDS = 20;

    public enum Kind {
        START, CONNECT, QUEUE, RUN, DONE, DETAIL, ERROR
    }

    public enum LinkState {
        OFF, OK, ERR
    }

    public enum AgentStatus {
        OFFLINE, ONLINE
    }

    public record Event(Kind kind, String title, String detail, String href,
                        String relPath, String localPath, int linesAdded, int linesRemoved) {
        public Event(Kind kind, String title, String detail, String href) {
            this(kind, title, detail, href, null, null, 0, 0);
        }

        public boolean hasFile() {
            return (relPath != null && !relPath.isBlank()) || (localPath != null && !localPath.isBlank());
        }
    }

    public record Stats(AgentStatus status, int queued, int done) {
        public static Stats offline() {
            return new Stats(AgentStatus.OFFLINE, 0, 0);
        }
    }

    public static AgentStatus statusOf(LinkState link) {
        return link == LinkState.OK ? AgentStatus.ONLINE : AgentStatus.OFFLINE;
    }

    private final AppConfig config;
    private final AuthManager auth;
    private final SiteApiClient api;
    private final SiteBridgeClient bridge;
    private final AppPaths paths;
    private final Consumer<Event> events;
    private final Consumer<LinkState> link;
    private final Consumer<Stats> stats;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aipilot-agent");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean tickLock = new AtomicBoolean(false);
    private volatile boolean executing;
    private volatile LinkState lastLink = LinkState.OFF;
    private volatile boolean started;
    private volatile int lastQueued;
    private volatile int doneCount;
    private volatile AgentStatus publishedStatus;
    private volatile int publishedQueued = -1;
    private volatile int publishedDone = -1;
    private volatile long lastTickAt;
    private volatile boolean forceTick;
    private BooleanSupplier windowVisible = () -> true;

    public AgentLoop(AppConfig config, AuthManager auth, SiteApiClient api, SiteBridgeClient bridge,
                     Consumer<Event> events, Consumer<LinkState> link) {
        this(config, auth, api, bridge, null, events, link, null);
    }

    public AgentLoop(AppConfig config, AuthManager auth, SiteApiClient api, SiteBridgeClient bridge,
                     Consumer<Event> events, Consumer<LinkState> link, Consumer<Stats> stats) {
        this(config, auth, api, bridge, null, events, link, stats);
    }

    public AgentLoop(AppConfig config, AuthManager auth, SiteApiClient api, SiteBridgeClient bridge,
                     AppPaths paths, Consumer<Event> events, Consumer<LinkState> link, Consumer<Stats> stats) {
        this.config = config;
        this.auth = auth;
        this.api = api;
        this.bridge = bridge;
        this.paths = paths;
        this.events = events;
        this.link = link;
        this.stats = stats;
    }

    public void setWindowVisibleCheck(BooleanSupplier windowVisible) {
        this.windowVisible = windowVisible == null ? () -> true : windowVisible;
    }

    public void start() {
        if (started) {
            return;
        }
        started = true;
        scheduler.scheduleAtFixedRate(this::tick, 1, 3, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::presence, 2, 10, TimeUnit.SECONDS);
        publishStats();
    }

    public void stop() {
        started = false;
        scheduler.shutdownNow();
    }

    public boolean busy() {
        return executing || tickLock.get();
    }

    public void refreshNow() {
        forceTick = true;
        scheduler.execute(this::tick);
    }

    private void tick() {
        if (!tickLock.compareAndSet(false, true)) {
            return;
        }
        boolean visible = windowVisible.getAsBoolean();
        long gapMs = (visible ? Math.max(3, config.loopPollSeconds) : TRAY_POLL_SECONDS) * 1000L;
        long now = System.currentTimeMillis();
        if (!forceTick && lastTickAt > 0 && now - lastTickAt < gapMs) {
            tickLock.set(false);
            return;
        }
        forceTick = false;
        lastTickAt = now;
        try {
            emit(Kind.DETAIL, "Сеть", "Получение заданий", null);
            Session session = auth.currentSession();
            if (session == null || !session.isAuthenticated()) {
                setQueued(0);
                setLink(LinkState.OFF);
                return;
            }
            boolean ping = api.pingSync();
            setLink(ping ? LinkState.OK : LinkState.ERR);
            if (!ping) {
                setQueued(0);
                return;
            }
            List<SiteApiClient.PendingCommand> pending = api.pendingCommands();
            setQueued(pending.size());
            if (pending.isEmpty()) {
                return;
            }
            emit(Kind.DETAIL, "Проект", "Синхронизация проекта", aiUrl(""));
            Map<String, Project> projects = index(loadBoundProjects(session));
            List<SiteApiClient.PendingCommand> commands = new ArrayList<>();
            for (SiteApiClient.PendingCommand cmd : pending) {
                if (cmd != null && cmd.projectId() != null && projects.containsKey(cmd.projectId())) {
                    commands.add(cmd);
                }
            }
            setQueued(commands.size());
            if (commands.isEmpty()) {
                return;
            }
            emit(Kind.QUEUE, "Очередь", commands.size() + " команд", aiUrl(""));
            executing = true;
            for (int i = 0; i < commands.size(); i++) {
                handle(commands.get(i), projects);
                setQueued(commands.size() - i - 1);
            }
        } catch (Exception e) {
            setLink(LinkState.ERR);
            log.warn("agent tick failed: {}", e.getMessage());
        } finally {
            executing = false;
            tickLock.set(false);
            publishStats();
        }
    }

    private void presence() {
        Session session = auth.currentSession();
        if (session == null || !session.isAuthenticated()) {
            return;
        }
        try {
            bridge.registerPresence("aipilot", AppVersion.read(), true);
        } catch (Exception e) {
            log.debug("aipilot presence: {}", e.getMessage());
        }
    }

    private void handle(SiteApiClient.PendingCommand cmd, Map<String, Project> projects) {
        Project project = projects.get(cmd.projectId());
        if (project == null || project.id == null || project.id.isBlank()) {
            log.debug("skip unbound command {} project {}", cmd.id(), cmd.projectId());
            return;
        }
        String kind = cmd.kind() == null ? "" : cmd.kind().trim().toLowerCase();
        try {
            switch (kind) {
                case "file_write", "file_delete", "file_rename" -> applyFile(project, cmd, kind);
                case "run" -> applyRun(project, cmd);
                case "ssh_exec" -> applySsh(project, cmd);
                default -> deny(cmd, project, "Неизвестный kind: " + kind);
            }
        } catch (Exception e) {
            deny(cmd, project, safe(e.getMessage()));
        }
    }

    private void applyFile(Project project, SiteApiClient.PendingCommand cmd, String kind) throws Exception {
        String path = cmd.path();
        if (!FileOps.looksSafeRel(path)) {
            deny(cmd, project, "Путь вне песочницы");
            return;
        }
        if ("file_rename".equals(kind) && !FileOps.looksSafeRel(cmd.toPath())) {
            deny(cmd, project, "Новый путь вне песочницы");
            return;
        }
        String op = switch (kind) {
            case "file_delete" -> FileOps.DELETE;
            case "file_rename" -> FileOps.RENAME;
            default -> FileOps.WRITE;
        };
        String verb = switch (kind) {
            case "file_delete" -> "Удаление файла";
            case "file_rename" -> "Переименование файла";
            default -> "Редактирование файла";
        };
        Path root = project.rootPath();
        var extras = ProjectFolders.extras(project);
        String oldText = "";
        Path local = null;
        try {
            local = CodeEditorService.resolveTarget(root, extras, path);
            if (Files.isRegularFile(local)) {
                oldText = Files.readString(local, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        String neu = "file_delete".equals(kind) ? "" : (cmd.content() == null ? "" : cmd.content());
        int[] diff = lineDiff(oldText, neu, op);
        String href = projectUrl(project, cmd);
        String localStr = local == null ? null : local.toAbsolutePath().normalize().toString();
        emitFile(Kind.DETAIL, "Файл", verb + " " + path, href, path, localStr, diff[0], diff[1]);
        emitFile(Kind.RUN, "Файл", verb, href, path, localStr, diff[0], diff[1]);
        FileEditProposal proposal = new FileEditProposal(path, oldText, neu, op, cmd.toPath());
        new CodeEditorService().apply(proposal, project);
        if ("file_rename".equals(kind) && cmd.toPath() != null && !cmd.toPath().isBlank()) {
            try {
                local = CodeEditorService.resolveTarget(root, extras, cmd.toPath());
                localStr = local.toAbsolutePath().normalize().toString();
                path = cmd.toPath();
            } catch (Exception ignored) {
            }
        }
        ackFile(cmd, project, "applied", "ok", "", 0, "Файл", verb, path, localStr, diff[0], diff[1]);
    }

    private void ackFile(SiteApiClient.PendingCommand cmd, Project project, String status,
                         String result, String error, int exitCode, String doneTitle, String doneDetail,
                         String relPath, String localPath, int added, int removed) {
        emit(Kind.DETAIL, "Сеть", "Отправка задания на проверку", project == null ? aiUrl("") : projectUrl(project, cmd));
        String projectId = project == null ? cmd.projectId() : project.id;
        api.ackCommand(cmd.id(), status, projectId, cmd.path(), result, error, exitCode);
        if ("applied".equals(status)) {
            doneCount++;
            emitFile(Kind.DONE, doneTitle, doneDetail,
                    project == null ? aiUrl("") : projectUrl(project, cmd),
                    relPath, localPath, added, removed);
            publishStats();
        }
    }

    private void emitFile(Kind kind, String title, String detail, String href,
                          String relPath, String localPath, int added, int removed) {
        if (events != null) {
            events.accept(new Event(kind, title, detail, href, relPath, localPath, added, removed));
        }
    }

    /** @return {linesAdded, linesRemoved} */
    static int[] lineDiff(String oldText, String newText, String op) {
        if (FileOps.DELETE.equals(op)) {
            int n = lineCount(oldText);
            return new int[]{0, n};
        }
        if (FileOps.RENAME.equals(op)) {
            return new int[]{0, 0};
        }
        String[] a = splitLines(oldText);
        String[] b = splitLines(newText);
        int lcs = lcsLength(a, b);
        return new int[]{Math.max(0, b.length - lcs), Math.max(0, a.length - lcs)};
    }

    private static int lineCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return splitLines(text).length;
    }

    private static String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.split("\n", -1);
    }

    private static int lcsLength(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        if (n == 0 || m == 0) {
            return 0;
        }
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (a[i - 1].equals(b[j - 1])) {
                    cur[j] = prev[j - 1] + 1;
                } else {
                    cur[j] = Math.max(prev[j], cur[j - 1]);
                }
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
            java.util.Arrays.fill(cur, 0);
        }
        return prev[m];
    }

    private void applyRun(Project project, SiteApiClient.PendingCommand cmd) {
        String language = cmd.language() == null ? "" : cmd.language();
        String code = cmd.code() == null ? "" : cmd.code();
        emit(Kind.DETAIL, "Проверка", "Отправка кода на проверку", projectUrl(project, cmd));
        SafetyVerdict verdict = SafetyReview.beforeExec(api, bridge, config, project, language, code, "");
        if (!verdict.safe()) {
            emit(Kind.DETAIL, "Проверка", "Отказ: " + verdict.reason(), projectUrl(project, cmd));
            deny(cmd, project, verdict.reason());
            return;
        }
        emit(Kind.DETAIL, "Проверка", "Код разрешён", projectUrl(project, cmd));
        Path cwd = ProjectFolders.mainFolder(project);
        if (cwd == null) {
            deny(cmd, project, "Не задана главная папка проекта");
            return;
        }
        emit(Kind.DETAIL, "Консоль", "Выполнение задания (" + language + ")", projectUrl(project, cmd));
        emit(Kind.RUN, "Консоль", language, projectUrl(project, cmd));
        CodeRunService.RunBlock block = new CodeRunService.RunBlock(language, code);
        CodeRunService.Result result = CodeRunService.run(block, cwd);
        String out = clip(join(result.stdout(), result.stderr()));
        String err = result.error() == null ? "" : result.error();
        String status = result.ok() ? "applied" : "denied";
        ack(cmd, project, status, out, err.isBlank() ? (result.timedOut() ? "timeout" : "") : err, result.exitCode(),
                "Консоль", language);
        if (!result.ok()) {
            emit(Kind.ERROR, "Консоль", err.isBlank() ? out : err, projectUrl(project, cmd));
        }
    }

    private void applySsh(Project project, SiteApiClient.PendingCommand cmd) {
        String alias = cmd.alias() == null ? "" : cmd.alias().trim();
        ProjectServer server = findServer(project, alias);
        if (server == null) {
            deny(cmd, project, "SSH-алиас «" + alias + "» не задан в проекте");
            return;
        }
        api.hydrateSecrets(project);
        server = findServer(project, alias);
        String command = cmd.command() == null ? "" : cmd.command();
        emit(Kind.DETAIL, "Проверка", "Отправка команды на проверку", projectUrl(project, cmd));
        SafetyVerdict verdict = SafetyReview.beforeExec(api, bridge, config, project, "ssh " + alias, command, "");
        if (!verdict.safe()) {
            emit(Kind.DETAIL, "Проверка", "Отказ: " + verdict.reason(), projectUrl(project, cmd));
            deny(cmd, project, verdict.reason());
            return;
        }
        emit(Kind.DETAIL, "SSH", "Выполнение задания (" + alias + ")", projectUrl(project, cmd));
        emit(Kind.RUN, "SSH", alias, projectUrl(project, cmd));
        SshService.ExecResult result = SshService.exec(server, command);
        String out = clip(join(result.stdout(), result.stderr()));
        String err = result.error() == null ? "" : result.error();
        String status = result.ok() ? "applied" : "denied";
        ack(cmd, project, status, out, err, result.exitCode(), "SSH", alias);
        if (!result.ok()) {
            emit(Kind.ERROR, "SSH", err.isBlank() ? out : err, projectUrl(project, cmd));
        }
    }

    private void deny(SiteApiClient.PendingCommand cmd, Project project, String reason) {
        emit(Kind.ERROR, "Отказ", reason, project == null ? aiUrl("") : projectUrl(project, cmd));
        ack(cmd, project, "denied", "", reason, -1, "Отказ", reason);
    }

    private void ack(SiteApiClient.PendingCommand cmd, Project project, String status,
                     String result, String error, int exitCode, String doneTitle, String doneDetail) {
        emit(Kind.DETAIL, "Сеть", "Отправка задания на проверку", project == null ? aiUrl("") : projectUrl(project, cmd));
        String projectId = project == null ? cmd.projectId() : project.id;
        api.ackCommand(cmd.id(), status, projectId, cmd.path(), result, error, exitCode);
        if ("applied".equals(status)) {
            doneCount++;
            emit(Kind.DONE, doneTitle, doneDetail, project == null ? aiUrl("") : projectUrl(project, cmd));
            publishStats();
        }
    }

    private List<Project> loadBoundProjects(Session session) {
        List<Project> site = api.listProjects();
        Map<String, Project> byId = new HashMap<>();
        for (Project project : site) {
            if (project != null && project.id != null && !project.id.isBlank()) {
                byId.put(project.id, project);
            }
        }
        Integer userId = session == null ? null : session.userId();
        for (Project local : LocalProjectStore.list(paths, userId)) {
            if (local == null || local.id == null || local.id.isBlank()) {
                continue;
            }
            Project siteProject = byId.get(local.id);
            if (siteProject == null) {
                byId.put(local.id, local);
            } else {
                LocalProjectStore.mergeLocalAccess(siteProject, local);
            }
        }
        return DeviceBinding.boundProjects(new ArrayList<>(byId.values()), config.deviceKey);
    }

    private Map<String, Project> index(List<Project> projects) {
        Map<String, Project> out = new HashMap<>();
        if (projects == null) {
            return out;
        }
        for (Project project : projects) {
            if (project != null && project.id != null && !project.id.isBlank()) {
                out.put(project.id, project);
            }
        }
        return out;
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

    private void emit(Kind kind, String title, String detail, String href) {
        if (events != null) {
            events.accept(new Event(kind, title, detail, href));
        }
    }

    private void setQueued(int queued) {
        lastQueued = Math.max(0, queued);
        publishStats();
    }

    private void setLink(LinkState state) {
        LinkState next = state == null ? LinkState.OFF : state;
        if (next == lastLink) {
            return;
        }
        lastLink = next;
        if (link != null) {
            link.accept(lastLink);
        }
        publishStats();
    }

    private void publishStats() {
        AgentStatus value = statusOf(lastLink);
        if (value == publishedStatus && lastQueued == publishedQueued && doneCount == publishedDone) {
            return;
        }
        publishedStatus = value;
        publishedQueued = lastQueued;
        publishedDone = doneCount;
        if (stats != null) {
            stats.accept(new Stats(value, lastQueued, doneCount));
        }
    }

    private String aiUrl(String suffix) {
        String base = DesktopAuthFlow.trimSlash(config.apiBaseUrl);
        return base + "/ai" + (suffix == null ? "" : suffix);
    }

    private String projectUrl(Project project, SiteApiClient.PendingCommand cmd) {
        if (cmd != null && cmd.runId() != null && cmd.runId() > 0) {
            return aiUrl("/run/" + cmd.runId());
        }
        if (project != null && project.id != null && !project.id.isBlank()) {
            return aiUrl("?project_id=" + project.id);
        }
        return aiUrl("");
    }

    private static String join(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        return left + "\n" + right;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 8000 ? text : text.substring(0, 8000);
    }

    private static String safe(String message) {
        return message == null || message.isBlank() ? "ошибка" : message;
    }
}
