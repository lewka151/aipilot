package com.aipilot.ui;

import com.aipilot.core.LocalProjectStore;
import com.aipilot.core.ProjectFolders;
import com.aipilot.core.model.Project;
import com.aipilot.core.model.ProjectPath;
import com.aipilot.infra.api.ApiDiagnosticsService;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.DesktopAuthFlow;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.infra.config.WindowsAutostart;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Server / account / app settings plus project folders and SSH from the site API. */
public final class SettingsPanel {
    private final Window owner;
    private final AppPaths paths;
    private final AppConfig config;
    private final AuthManager auth;
    private final Runnable onSaved;
    private final Consumer<String> statusSink;
    private final Runnable onLogin;
    private final Runnable onLogout;
    private final Supplier<AutoUpdateScheduler> updates;
    private SiteApiClient api;

    private TextField serverHostField;
    private Label schemePrefix;
    private CheckBox autoUpdate;
    private CheckBox startTray;
    private CheckBox autostartLogin;
    private Label accountNameLabel;
    private Button accountLoginButton;
    private Button accountLogoutButton;
    private Label apiResult;
    private Label updateResult;
    private FlowPane projectChips;
    private VBox projectEditor;
    private String selectedProjectId;
    private final List<Project> listedProjects = new ArrayList<>();
    private boolean autostartApplied;

    public SettingsPanel(Window owner, AppPaths paths, AppConfig config, AuthManager auth, Runnable onSaved,
                         Consumer<String> statusSink, Runnable onLogin, Runnable onLogout,
                         Supplier<AutoUpdateScheduler> updates) {
        this.owner = owner;
        this.paths = paths;
        this.config = config;
        this.auth = auth;
        this.onSaved = onSaved;
        this.statusSink = statusSink;
        this.onLogin = onLogin;
        this.onLogout = onLogout;
        this.updates = updates;
    }

    public void setApi(SiteApiClient api) {
        this.api = api;
    }

    public Parent build() {
        Label addrTitle = new Label("Адрес");
        addrTitle.getStyleClass().add("section-title");
        schemePrefix = new Label(AppConfig.apiBaseSchemePrefix(config.apiBaseUrl));
        schemePrefix.getStyleClass().add("url-prefix");
        serverHostField = new TextField(AppConfig.apiBaseHost(config.apiBaseUrl));
        serverHostField.setPromptText("host.example.com");
        serverHostField.getStyleClass().add("field-input");
        ThemedContextMenu.installTextField(serverHostField);
        HBox.setHgrow(serverHostField, Priority.ALWAYS);
        HBox addrRow = new HBox(8, addrTitle, schemePrefix, serverHostField);
        addrRow.getStyleClass().add("url-row");
        addrRow.setAlignment(Pos.CENTER_LEFT);
        Button checkApi = leftButton("Проверить API", "button-secondary");
        apiResult = new Label("");
        apiResult.getStyleClass().add("meta-label");
        apiResult.setWrapText(true);
        checkApi.setOnAction(e -> checkApi());
        VBox server = block("Сервер", addrRow, checkApi, apiResult);

        accountNameLabel = new Label("Не авторизован");
        accountNameLabel.getStyleClass().add("meta-label");
        accountLoginButton = leftButton("Войти", "button-accent");
        accountLoginButton.setOnAction(e -> {
            if (onLogin != null) {
                onLogin.run();
            }
        });
        accountLogoutButton = leftButton("Выйти", "button-secondary");
        accountLogoutButton.setOnAction(e -> {
            if (onLogout != null) {
                onLogout.run();
            }
        });
        HBox accountBtns = new HBox(8, accountLoginButton, accountLogoutButton);
        accountBtns.setAlignment(Pos.CENTER_LEFT);
        VBox account = block("Аккаунт", accountNameLabel, accountBtns);

        autoUpdate = new CheckBox("Автообновление");
        autoUpdate.setSelected(config.autoUpdate);
        startTray = new CheckBox("Запускать свёрнутым в трей");
        startTray.setSelected(config.startHiddenInTray);
        autostartApplied = config.autostartOnLogin || WindowsAutostart.isEnabled(null);
        autostartLogin = new CheckBox("Запускать при входе в Windows");
        autostartLogin.setSelected(autostartApplied);
        Button checkUpdates = leftButton("Проверить обновления", "button-secondary");
        updateResult = new Label("Версия " + AppVersion.read());
        updateResult.getStyleClass().add("meta-label");
        updateResult.setWrapText(true);
        checkUpdates.setOnAction(e -> {
            AutoUpdateScheduler scheduler = updates == null ? null : updates.get();
            if (scheduler != null) {
                scheduler.check(false);
            }
        });
        Button logs = leftButton("Открыть папку логов", "button-secondary");
        logs.setOnAction(e -> openLogs());
        Label version = new Label("Сборка " + AppVersion.read());
        version.getStyleClass().add("meta-label");
        VBox appBlock = block("Приложение", autoUpdate, startTray, autostartLogin,
                checkUpdates, updateResult, logs, version);

        Hyperlink siteLink = new Hyperlink();
        siteLink.getStyleClass().add("settings-link");
        siteLink.setWrapText(true);
        refreshSiteLink(siteLink);
        Label about = new Label("Данное приложение является точкой доступа для AIPilot, больше информации вы получите в разделе");
        about.setWrapText(true);
        about.getStyleClass().add("meta-label");
        VBox aboutBox = new VBox(4, about, siteLink);
        aboutBox.getStyleClass().add("settings-block");

        IconButton reloadProjects = IconButton.refresh();
        reloadProjects.setOnAction(e -> refreshProjects());
        projectChips = new FlowPane(8, 8);
        projectChips.setPrefWrapLength(360);
        HBox projectRow = new HBox(8, reloadProjects, projectChips);
        HBox.setHgrow(projectChips, Priority.ALWAYS);
        projectEditor = new VBox(8);
        VBox.setVgrow(projectEditor, Priority.ALWAYS);
        Label bindHint = new Label("Сохранение папок закрепляет проект за этим компьютером.");
        bindHint.getStyleClass().add("meta-label");
        bindHint.setWrapText(true);
        VBox projects = block("Проекты", bindHint, projectRow, projectEditor);
        VBox.setVgrow(projects, Priority.ALWAYS);
        projects.setMinHeight(280);

        Button save = leftButton("Сохранить", "button-accent");
        save.setOnAction(e -> save());

        VBox body = new VBox(12, projects, server, appBlock, account, aboutBox, save);
        body.setPadding(new Insets(8, 10, 16, 8));
        body.getStyleClass().add("settings-body");
        body.setAlignment(Pos.TOP_LEFT);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("settings-scroll");
        scroll.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        refreshAccountRow();
        refreshProjects();
        return scroll;
    }

    public void save() {
        if (serverHostField != null) {
            String prefix = schemePrefix == null ? "https://" : schemePrefix.getText();
            config.apiBaseUrl = AppConfig.joinApiBase(prefix, serverHostField.getText());
            if (schemePrefix != null) {
                schemePrefix.setText(AppConfig.apiBaseSchemePrefix(config.apiBaseUrl));
                serverHostField.setText(AppConfig.apiBaseHost(config.apiBaseUrl));
            }
        }
        if (autoUpdate != null) {
            config.autoUpdate = autoUpdate.isSelected();
        }
        if (startTray != null) {
            config.startHiddenInTray = startTray.isSelected();
        }
        if (autostartLogin != null) {
            boolean want = autostartLogin.isSelected();
            config.autostartOnLogin = want;
            if (want != autostartApplied) {
                WindowsAutostart.apply(want, WindowsAutostart.defaultStartupDir());
                autostartApplied = want;
            }
        }
        if (onSaved != null) {
            onSaved.run();
        }
    }

    public void refreshAccountRow() {
        Session session = auth == null ? null : auth.currentSession();
        boolean in = session != null && session.isAuthenticated();
        if (accountNameLabel != null) {
            accountNameLabel.setText(in
                    ? (session.userName() == null || session.userName().isBlank()
                    ? "Пользователь #" + session.userId() : session.userName())
                    : "Не авторизован");
        }
        if (accountLoginButton != null) {
            accountLoginButton.setVisible(!in);
            accountLoginButton.setManaged(!in);
        }
        if (accountLogoutButton != null) {
            accountLogoutButton.setVisible(in);
            accountLogoutButton.setManaged(in);
        }
    }

    public void refreshProjects() {
        if (projectChips == null || projectEditor == null) {
            return;
        }
        Session session = auth == null ? null : auth.currentSession();
        if (session == null || !session.isAuthenticated()) {
            listedProjects.clear();
            projectChips.getChildren().clear();
            projectEditor.getChildren().setAll(meta("Войдите, чтобы видеть проекты"));
            return;
        }
        Thread t = new Thread(() -> {
            Map<String, Project> merged = new LinkedHashMap<>();
            if (api != null) {
                for (Project project : api.listProjects()) {
                    if (project != null && project.id != null && !project.id.isBlank()) {
                        merged.put(project.id, project);
                    }
                }
            }
            for (Project local : LocalProjectStore.list(paths, session.userId())) {
                if (local == null || local.id == null || local.id.isBlank()) {
                    continue;
                }
                Project site = merged.get(local.id);
                if (site == null) {
                    local.orphan = true;
                    merged.put(local.id, local);
                } else {
                    LocalProjectStore.mergeLocalAccess(site, local);
                }
            }
            List<Project> snapshot = new ArrayList<>(merged.values());
            Platform.runLater(() -> applyProjects(snapshot));
        }, "aipilot-projects");
        t.setDaemon(true);
        t.start();
    }

    private void applyProjects(List<Project> snapshot) {
        listedProjects.clear();
        listedProjects.addAll(snapshot);
        if (listedProjects.isEmpty()) {
            projectChips.getChildren().clear();
            projectEditor.getChildren().setAll(meta("Нет проектов"));
            return;
        }
        if (selectedProjectId == null || listedProjects.stream().noneMatch(p -> selectedProjectId.equals(p.id))) {
            selectedProjectId = listedProjects.getFirst().id;
        }
        paintChips();
        showSelectedEditor();
    }

    private void paintChips() {
        projectChips.getChildren().clear();
        for (Project project : listedProjects) {
            Button chip = new Button(chipLabel(project));
            chip.getStyleClass().add("project-chip");
            chip.getStyleClass().add("button-secondary");
            if (project.orphan) {
                chip.getStyleClass().add("project-chip-orphan");
            }
            if (project.id.equals(selectedProjectId)) {
                chip.getStyleClass().add("project-chip-active");
            }
            chip.setOnAction(e -> selectProject(project.id));
            projectChips.getChildren().add(chip);
        }
    }

    private void selectProject(String id) {
        selectedProjectId = id;
        paintChips();
        showSelectedEditor();
    }

    private void showSelectedEditor() {
        Project selected = listedProjects.stream()
                .filter(p -> p.id.equals(selectedProjectId))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            return;
        }
        if (api != null && !selected.orphan) {
            Project target = selected;
            Thread t = new Thread(() -> {
                api.hydrateSecrets(target);
                Platform.runLater(() -> projectEditor.getChildren().setAll(
                        ProjectAccessForm.buildCompact(target, owner, this::saveProject, () -> restoreProject(target))));
            }, "aipilot-project-secrets");
            t.setDaemon(true);
            t.start();
            return;
        }
        projectEditor.getChildren().setAll(
                ProjectAccessForm.buildCompact(selected, owner, this::saveProject, () -> restoreProject(selected)));
    }

    private void saveProject(Project project) {
        if (project == null) {
            return;
        }
        Session session = auth == null ? null : auth.currentSession();
        if (config.deviceKey != null && !config.deviceKey.isBlank()) {
            project.deviceId = config.deviceKey.trim().toLowerCase();
        }
        if (project.paths != null) {
            for (ProjectPath path : project.paths) {
                if (path != null) {
                    path.sshAlias = "";
                }
            }
        }
        Path main = ProjectFolders.mainFolder(project);
        if (main != null) {
            project.root = main.toString();
        }
        boolean localOk = false;
        try {
            LocalProjectStore.save(paths, session == null ? null : session.userId(), project);
            localOk = true;
        } catch (Exception e) {
            status("Локально не сохранилось: " + e.getMessage());
        }
        boolean siteOk = false;
        if (api != null && !project.orphan) {
            siteOk = api.saveProject(project, true);
            if (!siteOk) {
                status("На сайт не отправилось");
            }
        }
        if (localOk && (project.orphan || siteOk)) {
            status(siteOk ? "Проект сохранён локально и на сайте" : "Проект сохранён локально");
        }
        for (int i = 0; i < listedProjects.size(); i++) {
            if (project.id != null && project.id.equals(listedProjects.get(i).id)) {
                listedProjects.set(i, project);
                break;
            }
        }
        refreshProjects();
    }

    private void restoreProject(Project project) {
        if (api == null || project == null) {
            return;
        }
        project.orphan = false;
        boolean ok = api.saveProject(project, true);
        if (ok) {
            Session session = auth == null ? null : auth.currentSession();
            try {
                LocalProjectStore.save(paths, session == null ? null : session.userId(), project);
            } catch (Exception ignored) {
            }
        }
        refreshProjects();
    }

    private void checkApi() {
        String prefix = schemePrefix == null ? "https://" : schemePrefix.getText();
        config.apiBaseUrl = AppConfig.joinApiBase(prefix, serverHostField.getText());
        if (schemePrefix != null) {
            schemePrefix.setText(AppConfig.apiBaseSchemePrefix(config.apiBaseUrl));
            serverHostField.setText(AppConfig.apiBaseHost(config.apiBaseUrl));
        }
        ApiDiagnosticsService diag = new ApiDiagnosticsService(config, auth);
        List<String> lines = diag.runReadOnly();
        apiResult.setText(String.join("\n", lines));
    }

    private void openLogs() {
        try {
            Path dir = paths.logDir();
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshSiteLink(Hyperlink link) {
        String url = DesktopAuthFlow.trimSlash(config.apiBaseUrl) + "/ai";
        link.setText(url);
        link.setOnAction(e -> {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void status(String text) {
        if (statusSink != null) {
            statusSink.accept(text);
        }
    }

    private static VBox block(String title, javafx.scene.Node... children) {
        Label head = new Label(title);
        head.getStyleClass().add("section-title");
        VBox box = new VBox(8);
        box.getStyleClass().add("settings-block");
        box.setAlignment(Pos.TOP_LEFT);
        box.getChildren().add(head);
        box.getChildren().addAll(children);
        return box;
    }

    private static Button leftButton(String text, String style) {
        Button b = new Button(text);
        b.getStyleClass().add(style);
        b.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return b;
    }

    private static Label meta(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("meta-label");
        l.setWrapText(true);
        return l;
    }

    private String chipLabel(Project project) {
        String name = project.displayName == null || project.displayName.isBlank()
                ? project.name : project.displayName;
        if (name == null || name.isBlank()) {
            name = project.id;
        }
        String bound = project.deviceId == null ? "" : project.deviceId.trim();
        if (project.orphan) {
            return name;
        }
        if (!bound.isEmpty() && config.deviceKey != null && config.deviceKey.equalsIgnoreCase(bound)) {
            return name;
        }
        if (!bound.isEmpty()) {
            return name + " · другой ПК";
        }
        return name;
    }
}






