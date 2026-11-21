package com.aipilot.ui;

import com.aipilot.core.ChatUrls;
import com.aipilot.core.ProjectAliases;
import com.aipilot.core.ProjectFolders;
import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;
import com.aipilot.core.model.ProjectServer;
import com.aipilot.core.SshService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Folders + SSH editors shared by project dialog and /ai/configs-style settings. */
public final class ProjectAccessForm {
    private static final double ALIAS_W = 130;
    private static final double HOST_W = 120;
    private static final double PORT_W = 56;
    private static final double LOGIN_W = 90;
    private static final double PASS_W = 110;

    private ProjectAccessForm() {
    }

    public static Node buildCompact(Project project, Window owner, Consumer<Project> onSave) {
        return build(project, owner, onSave, null, true);
    }

    public static Node buildCompact(Project project, Window owner, Consumer<Project> onSave, Runnable onRestore) {
        return build(project, owner, onSave, onRestore, true);
    }

    public static Node build(Project project, Window owner, Consumer<Project> onSave) {
        return build(project, owner, onSave, null, false);
    }

    private static Node build(Project project, Window owner, Consumer<Project> onSave, Runnable onRestore,
                              boolean compact) {
        TextField displayName = field(ProjectAliases.displayName(project), "Отображаемое имя");
        TextArea description = new TextArea(project.description == null ? "" : project.description);
        description.setPromptText("Описание проекта");
        description.setPrefRowCount(3);
        ThemedContextMenu.installTextField(description);
        TextField qwenProject = field(project.qwenProjectName, "Имя проекта на странице Qwen");
        TextField qwenTitle = field(project.qwenChatTitle, "Заголовок чата Qwen");
        TextField dsTitle = field(project.deepseekChatTitle, "Заголовок чата DeepSeek");
        TextField qwenId = field(ChatUrls.normalize(project.qwenChatId), "UUID чата Qwen");
        qwenId.setEditable(false);
        TextField dsId = field(ChatUrls.normalize(project.deepseekChatId), "UUID чата DeepSeek");
        dsId.setEditable(false);
        Button copyQwen = leftButton("Копировать", "button-secondary");
        copyQwen.setOnAction(e -> copy(qwenId.getText()));
        Button copyDs = leftButton("Копировать", "button-secondary");
        copyDs.setOnAction(e -> copy(dsId.getText()));

        List<ProjectPath> folders = loadFolders(project);
        VBox folderList = new VBox(6);
        Runnable refreshFolders = () -> rebuildFolders(folderList, folders, owner, project);
        refreshFolders.run();
        Button addFolder = leftButton("Добавить папку", "button-secondary");
        addFolder.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Папка проекта");
            var dir = chooser.showDialog(owner);
            if (dir != null) {
                String abs = dir.toPath().toAbsolutePath().normalize().toString();
                ProjectPath row = new ProjectPath(abs, dir.getName());
                row.name = dir.getName();
                row.sshAlias = "";
                folders.add(row);
                refreshFolders.run();
            }
        });

        List<ProjectServer> servers = new ArrayList<>();
        if (project.servers != null) {
            for (ProjectServer s : project.servers) {
                if (s != null) {
                    servers.add(s);
                }
            }
        }
        VBox serverList = new VBox(6);
        Runnable refreshServers = () -> rebuildServers(serverList, servers);
        refreshServers.run();
        Button addServer = leftButton("Добавить SSH", "button-secondary");
        addServer.setOnAction(e -> {
            servers.add(new ProjectServer("prod", "", 22, ""));
            refreshServers.run();
        });

        Button save = leftButton("Сохранить доступы", "button-accent");
        save.setOnAction(e -> {
            project.displayName = displayName.getText() == null ? project.name : displayName.getText().trim();
            List<ProjectPath> paths = new ArrayList<>();
            paths.add(new ProjectPath(".", "root"));
            for (int i = 0; i < folders.size(); i++) {
                ProjectPath entry = folders.get(i);
                if (entry == null || entry.path == null || entry.path.isBlank()) {
                    continue;
                }
                entry.path = entry.path.trim();
                entry.sshAlias = "";
                if (i == 0) {
                    entry.name = ProjectFolders.MAIN_ALIAS_LABEL;
                    entry.role = "main";
                } else if (entry.name == null || entry.name.isBlank()) {
                    entry.name = Path.of(entry.path).getFileName() == null
                            ? "extra" : Path.of(entry.path).getFileName().toString();
                    entry.role = entry.name;
                }
                paths.add(entry);
            }
            project.paths = paths;
            project.servers = servers;
            project.description = description.getText() == null ? "" : description.getText().trim();
            project.qwenProjectName = qwenProject.getText() == null ? "" : qwenProject.getText().trim();
            project.qwenChatTitle = qwenTitle.getText() == null ? "" : qwenTitle.getText().trim();
            project.deepseekChatTitle = dsTitle.getText() == null ? "" : dsTitle.getText().trim();
            Path main = ProjectFolders.mainFolder(project);
            if (main != null) {
                project.root = main.toString();
            }
            if (onSave != null) {
                onSave.accept(project);
            }
        });

        Label foldersHead = new Label("Папки и серверы");
        foldersHead.getStyleClass().add("section-title");
        Label foldersHint = new Label(
                "Первая папка — главная: в ней команды и файлы приложения. Сохранение закрепляет проект за этим ПК.");
        foldersHint.getStyleClass().add("meta-label");
        foldersHint.setWrapText(true);

        VBox body = new VBox(10);
        body.getStyleClass().add("project-access-form");
        body.setAlignment(Pos.TOP_LEFT);
        body.setFillWidth(true);
        body.setPadding(new Insets(4, 0, 12, 0));
        body.getChildren().add(labeled("Отображаемое имя", displayName));
        if (project.orphan) {
            Label orphan = new Label("Проект есть на этом компьютере, но отсутствует на сайте.");
            orphan.getStyleClass().add("meta-label");
            orphan.setWrapText(true);
            Button restore = leftButton("Восстановить на сайте", "button-accent");
            restore.setOnAction(e -> {
                if (onRestore != null) {
                    onRestore.run();
                }
            });
            body.getChildren().addAll(orphan, restore);
        }
        if (!compact) {
            body.getChildren().addAll(
                    labeled("Описание", description),
                    labeled("Проект Qwen", qwenProject),
                    labeled("Заголовок чата Qwen", qwenTitle),
                    labeled("Заголовок чата DeepSeek", dsTitle),
                    labeled("ID чата Qwen", row(qwenId, copyQwen)),
                    labeled("ID чата DeepSeek", row(dsId, copyDs)));
        }
        body.getChildren().addAll(
                foldersHead, foldersHint,
                labeled("Папки", folderList), addFolder,
                labeled("SSH", serverList), addServer,
                save);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("settings-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return scroll;
    }

    private static List<ProjectPath> loadFolders(Project project) {
        List<ProjectPath> folders = new ArrayList<>();
        for (ProjectFolders.Root extra : ProjectFolders.extras(project)) {
            ProjectPath row = new ProjectPath(extra.path().toString(), extra.prefix());
            row.name = extra.prefix();
            row.sshAlias = "";
            folders.add(row);
        }
        if (folders.isEmpty()) {
            Path main = ProjectFolders.mainFolder(project);
            ProjectPath row = new ProjectPath(main == null ? "" : main.toString(),
                    ProjectFolders.defaultMainFolderName(project));
            row.name = ProjectFolders.defaultMainFolderName(project);
            row.role = "main";
            row.sshAlias = "";
            folders.add(row);
        }
        return folders;
    }

    private static void rebuildFolders(VBox folderList, List<ProjectPath> folders, Window owner, Project project) {
        folderList.getChildren().clear();
        GridPane head = folderGrid();
        head.add(colLabel("Алиас"), 0, 0);
        head.add(colLabel("Путь"), 1, 0);
        head.add(colLabel(""), 2, 0);
        folderList.getChildren().add(head);

        for (int i = 0; i < folders.size(); i++) {
            int idx = i;
            boolean main = idx == 0;
            ProjectPath entry = folders.get(i);
            entry.sshAlias = "";
            GridPane row = folderGrid();

            TextField alias = field(
                    main ? ProjectFolders.MAIN_ALIAS_LABEL
                            : (entry.name == null || entry.name.isBlank() ? entry.role : entry.name),
                    "alias");
            alias.setPrefWidth(ALIAS_W);
            alias.setMaxWidth(ALIAS_W);
            if (main) {
                alias.setEditable(false);
                alias.setText(ProjectFolders.MAIN_ALIAS_LABEL);
                alias.getStyleClass().add("field-readonly");
            } else {
                alias.textProperty().addListener((o, a, b) -> {
                    entry.name = b == null ? "" : b.trim();
                    entry.role = entry.name;
                });
            }

            TextField path = field(entry.path == null ? "" : entry.path, "путь к папке");
            path.getStyleClass().add("field-input");
            GridPane.setHgrow(path, Priority.ALWAYS);
            path.textProperty().addListener((o, a, b) -> entry.path = b == null ? "" : b);

            Button browse = leftButton("Обзор…", "button-secondary");
            browse.setOnAction(ev -> {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(main ? "Папка проекта" : "Папка");
                if (entry.path != null && !entry.path.isBlank()) {
                    try {
                        var current = Path.of(entry.path).toFile();
                        if (current.isDirectory()) {
                            chooser.setInitialDirectory(current);
                        }
                    } catch (Exception ignored) {
                    }
                }
                var dir = chooser.showDialog(owner);
                if (dir != null) {
                    String abs = dir.toPath().toAbsolutePath().normalize().toString();
                    entry.path = abs;
                    entry.sshAlias = "";
                    path.setText(abs);
                    if (!main && (entry.name == null || entry.name.isBlank())) {
                        entry.name = dir.getName();
                        entry.role = entry.name;
                        alias.setText(entry.name);
                    }
                }
            });
            Button remove = leftButton("Удалить", "button-secondary");
            remove.setDisable(main && folders.size() == 1);
            remove.setOnAction(ev -> {
                if (main && folders.size() == 1) {
                    return;
                }
                folders.remove(idx);
                if (folders.isEmpty()) {
                    ProjectPath row0 = new ProjectPath("", ProjectFolders.defaultMainFolderName(project));
                    row0.name = ProjectFolders.defaultMainFolderName(project);
                    row0.role = "main";
                    row0.sshAlias = "";
                    folders.add(row0);
                }
                rebuildFolders(folderList, folders, owner, project);
            });
            HBox actions = new HBox(6, browse, remove);
            actions.setAlignment(Pos.CENTER_LEFT);

            row.add(alias, 0, 0);
            row.add(path, 1, 0);
            row.add(actions, 2, 0);
            folderList.getChildren().add(row);
        }
    }

    private static GridPane folderGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(4);
        grid.setAlignment(Pos.CENTER_LEFT);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setPrefWidth(ALIAS_W);
        c0.setMinWidth(ALIAS_W);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(120);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setMinWidth(140);
        grid.getColumnConstraints().addAll(c0, c1, c2);
        return grid;
    }

    private static void rebuildServers(VBox serverList, List<ProjectServer> servers) {
        serverList.getChildren().clear();
        GridPane head = serverGrid();
        head.add(colLabel("Алиас"), 0, 0);
        head.add(colLabel("Хост"), 1, 0);
        head.add(colLabel("Порт"), 2, 0);
        head.add(colLabel("Логин"), 3, 0);
        head.add(colLabel("Пароль"), 4, 0);
        head.add(colLabel(""), 5, 0);
        serverList.getChildren().add(head);

        for (int i = 0; i < servers.size(); i++) {
            int idx = i;
            ProjectServer entry = servers.get(i);
            GridPane row = serverGrid();
            TextField alias = field(entry.aliasOrEmpty(), "alias");
            alias.setPrefWidth(ALIAS_W);
            TextField host = field(entry.host, "host");
            TextField port = field(String.valueOf(entry.port <= 0 ? 22 : entry.port), "22");
            port.setPrefWidth(PORT_W);
            port.setMaxWidth(PORT_W);
            TextField login = field(entry.login, "login");
            login.setPrefWidth(LOGIN_W);
            PasswordField password = new PasswordField();
            password.setPromptText(entry.hasPassword ? "задан · сменить" : "пароль");
            password.getStyleClass().add("field-input");
            ThemedContextMenu.installTextField(password);
            password.setPrefWidth(PASS_W);
            alias.textProperty().addListener((o, a, b) -> entry.alias = b == null ? "" : b.trim());
            host.textProperty().addListener((o, a, b) -> entry.host = b == null ? "" : b.trim());
            port.textProperty().addListener((o, a, b) -> {
                try {
                    entry.port = Integer.parseInt(b == null ? "22" : b.trim());
                } catch (NumberFormatException ignored) {
                    entry.port = 22;
                }
            });
            login.textProperty().addListener((o, a, b) -> entry.login = b == null ? "" : b.trim());
            password.textProperty().addListener((o, a, b) -> entry.password = b);
            Label pingResult = new Label("");
            pingResult.getStyleClass().add("meta-label");
            Button ping = leftButton("Проверить", "button-secondary");
            ping.setOnAction(ev -> {
                pingResult.setText("…");
                new Thread(() -> {
                    SshService.ExecResult result = SshService.ping(entry);
                    Platform.runLater(() -> pingResult.setText(
                            result.ok() ? "OK" : (result.error() == null || result.error().isBlank()
                                    ? "ошибка" : result.error())));
                }, "aipilot-ssh-ping").start();
            });
            Button remove = leftButton("Удалить", "button-secondary");
            remove.setOnAction(ev -> {
                servers.remove(idx);
                rebuildServers(serverList, servers);
            });
            HBox actions = new HBox(6, ping, pingResult, remove);
            actions.setAlignment(Pos.CENTER_LEFT);
            row.add(alias, 0, 0);
            row.add(host, 1, 0);
            row.add(port, 2, 0);
            row.add(login, 3, 0);
            row.add(password, 4, 0);
            row.add(actions, 5, 0);
            serverList.getChildren().add(row);
        }
        if (servers.isEmpty()) {
            Label empty = new Label("Нет SSH-серверов");
            empty.getStyleClass().add("meta-label");
            serverList.getChildren().add(empty);
        }
    }

    private static GridPane serverGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.setVgap(4);
        grid.setAlignment(Pos.CENTER_LEFT);
        ColumnConstraints c0 = new ColumnConstraints(ALIAS_W);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setMinWidth(HOST_W);
        ColumnConstraints c2 = new ColumnConstraints(PORT_W);
        ColumnConstraints c3 = new ColumnConstraints(LOGIN_W);
        ColumnConstraints c4 = new ColumnConstraints(PASS_W);
        ColumnConstraints c5 = new ColumnConstraints();
        c5.setMinWidth(160);
        grid.getColumnConstraints().addAll(c0, c1, c2, c3, c4, c5);
        return grid;
    }

    private static void copy(String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static TextField field(String value, String prompt) {
        TextField tf = new TextField(value == null ? "" : value);
        tf.setPromptText(prompt);
        tf.getStyleClass().add("field-input");
        ThemedContextMenu.installTextField(tf);
        return tf;
    }

    private static VBox labeled(String text, Node control) {
        Label l = label(text);
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        VBox box = new VBox(4, l, control);
        box.setAlignment(Pos.TOP_LEFT);
        box.setFillWidth(true);
        box.setPadding(Insets.EMPTY);
        box.getStyleClass().add("field-block");
        return box;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("field-label");
        l.setPadding(Insets.EMPTY);
        return l;
    }

    private static Label colLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("col-label");
        l.setPadding(Insets.EMPTY);
        return l;
    }

    private static Button leftButton(String text, String style) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        b.setMaxWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private static HBox row(Node... nodes) {
        HBox box = new HBox(8, nodes);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }
}
