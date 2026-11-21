package com.aipilot.ui;

import com.aipilot.core.AgentLoop;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.api.SiteBridgeClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.auth.Session;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MainController {
    private static final Logger log = LoggerFactory.getLogger(MainController.class);

    @FXML private StackPane appRoot;
    @FXML private VBox appShell;
    @FXML private HBox titleBar;
    @FXML private Label titleVersionLabel;
    @FXML private Circle apiDot;
    @FXML private HBox titleChrome;
    @FXML private StackPane pageStack;
    @FXML private BorderPane mainPage;
    @FXML private BorderPane settingsPage;
    @FXML private StackPane agentLogHost;
    @FXML private StackPane settingsHost;
    @FXML private Button settingsButton;
    @FXML private Button backButton;
    @FXML private Button loginButton;
    @FXML private Button refreshButton;
    @FXML private Button detailToggle;
    @FXML private Label agentStatusLabel;
    @FXML private Label queueCountLabel;
    @FXML private Label doneCountLabel;

    private AiPilotFxApp app;
    private AppPaths paths;
    private AppConfig config;
    private AuthManager authManager;
    private SiteApiClient siteApi;
    private SiteBridgeClient bridge;
    private SettingsPanel settingsPanel;
    private AgentLogView logView;
    private AgentLoop agentLoop;

    void wireTitleBar(Stage stage) {
        if (titleVersionLabel != null) {
            titleVersionLabel.setText("v" + AppVersion.read());
        }
        if (titleChrome != null && stage != null) {
            titleChrome.setAlignment(Pos.TOP_RIGHT);
            ChromeButton minBtn = new ChromeButton(ChromeButton.Kind.MINIMIZE);
            ChromeButton closeBtn = new ChromeButton(ChromeButton.Kind.CLOSE);
            minBtn.setOnMouseClicked(e -> stage.setIconified(true));
            closeBtn.setOnMouseClicked(e -> {
                if (app != null) {
                    app.hideToTray();
                }
            });
            titleChrome.getChildren().setAll(minBtn, closeBtn);
            titleChrome.setTranslateX(WindowClip.BORDER);
            titleChrome.setTranslateY(-WindowClip.BORDER);
        }
        if (titleBar != null && stage != null) {
            WindowDragHelper.install(stage, titleBar);
        }
        if (appRoot != null) {
            StackPane.setAlignment(appShell, Pos.TOP_LEFT);
            if (appShell != null) {
                appShell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            WindowClip.install(appRoot);
        }
        if (appRoot != null && stage != null) {
            EdgeResizeHelper.install(stage, appRoot);
        } else if (appShell != null && stage != null) {
            EdgeResizeHelper.install(stage, appShell);
        }
        if (refreshButton != null) {
            IconButton icon = IconButton.refresh();
            refreshButton.setGraphic(icon.getGraphic());
            refreshButton.setText("");
            refreshButton.getStyleClass().setAll("button", "button-secondary", "icon-btn", "ai-icon-btn");
            refreshButton.setMinSize(32, 32);
            refreshButton.setPrefSize(32, 32);
            refreshButton.setMaxSize(32, 32);
        }
        syncDetailToggle();
    }

    public void init(AiPilotFxApp app, AppPaths paths, AppConfig config) {
        this.app = app;
        this.paths = paths;
        this.config = config;
        this.authManager = new AuthManager(paths, config);
        Session session = authManager.currentSession();
        if (session != null && session.userId() != null) {
            this.paths = paths.forUser(session.userId());
        }
        this.siteApi = new SiteApiClient(config, authManager, this.paths);
        this.bridge = new SiteBridgeClient(config, authManager);
        this.logView = new AgentLogView();
        if (agentLogHost != null) {
            Node logNode = logView.node();
            if (logNode instanceof javafx.scene.layout.Region region) {
                region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            agentLogHost.getChildren().setAll(logNode);
        }
        this.agentLoop = new AgentLoop(config, authManager, siteApi, bridge, this.paths,
                event -> logView.add(event),
                state -> Platform.runLater(() -> setLinkState(state)),
                snapshot -> Platform.runLater(() -> applyStats(snapshot)));
        Stage stage = app == null ? null : app.getStage();
        agentLoop.setWindowVisibleCheck(() -> stage != null && stage.isShowing());
        settingsPanel = new SettingsPanel(stage, this.paths, config, authManager,
                this::onSettingsSaved, this::status, this::onLogin, this::onLogout,
                () -> app == null ? null : app.getAutoUpdateScheduler());
        settingsPanel.setApi(siteApi);
        if (settingsHost != null) {
            var panel = settingsPanel.build();
            if (panel instanceof javafx.scene.layout.Region region) {
                region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            }
            settingsHost.getChildren().setAll(panel);
        }
        wireTitleBar(stage);
        refreshAuth();
        applyStats(AgentLoop.Stats.offline());
        if (config.settingsPageOpen) {
            showSettingsPage();
        } else {
            showMainPage();
        }
        agentLoop.start();
        log.info("Agent UI ready");
    }

    public void bindAutoUpdate(AutoUpdateScheduler scheduler) {
        if (scheduler == null || logView == null) {
            return;
        }
        scheduler.setActivityLog(msg -> logView.addDetail("Обновления", msg));
    }

    public void setUiVisible(boolean visible) {
        if (logView != null) {
            logView.setUiActive(visible);
        }
        if (agentLoop == null) {
            return;
        }
        Stage stage = app == null ? null : app.getStage();
        if (visible) {
            agentLoop.setWindowVisibleCheck(() -> stage != null && stage.isShowing());
        } else {
            agentLoop.setWindowVisibleCheck(() -> false);
        }
    }

    public void restoreLayout() {
        setUiVisible(true);
        refreshAuth();
        if (settingsPanel != null) {
            settingsPanel.refreshAccountRow();
        }
    }

    public void persistUiState() {
        if (config == null) {
            return;
        }
        config.settingsPageOpen = settingsPage != null && settingsPage.isVisible();
    }

    public boolean isBusy() {
        return agentLoop != null && agentLoop.busy();
    }

    public boolean isSendTaskRunning() {
        return isBusy();
    }

    public void shutdownResources() {
        persistUiState();
        if (agentLoop != null) {
            agentLoop.stop();
        }
    }

    @FXML
    private void onSettings() {
        showSettingsPage();
    }

    @FXML
    private void onBackFromSettings() {
        if (settingsPanel != null) {
            settingsPanel.save();
        }
        showMainPage();
    }

    @FXML
    private void onLogin() {
        new Thread(() -> {
            try {
                authManager.signInInteractive("aipilot");
                Platform.runLater(() -> {
                    refreshAuth();
                    if (settingsPanel != null) {
                        settingsPanel.refreshAccountRow();
                        settingsPanel.refreshProjects();
                    }
                });
            } catch (Exception e) {
                log.warn("login failed: {}", e.getMessage());
            }
        }, "aipilot-login").start();
    }

    @FXML
    private void onRefresh() {
        if (agentLoop != null) {
            agentLoop.refreshNow();
        }
    }

    @FXML
    private void onDetailToggle() {
        if (logView == null) {
            return;
        }
        logView.setVerbose(!logView.isVerbose());
        syncDetailToggle();
    }

    private void syncDetailToggle() {
        if (detailToggle == null || logView == null) {
            return;
        }
        detailToggle.setText(logView.isVerbose() ? "кратко" : "подробно");
    }

    private void onLogout() {
        authManager.signOut();
        refreshAuth();
        setLinkState(AgentLoop.LinkState.OFF);
        applyStats(AgentLoop.Stats.offline());
        if (settingsPanel != null) {
            settingsPanel.refreshAccountRow();
            settingsPanel.refreshProjects();
        }
    }

    private void onSettingsSaved() {
        persistUiState();
        if (app != null) {
            try {
                config.save(paths.configFile());
            } catch (Exception e) {
                log.warn("Не удалось сохранить настройки: {}", e.getMessage());
            }
        }
        refreshAuth();
    }

    private void showMainPage() {
        if (mainPage != null) {
            mainPage.setVisible(true);
            mainPage.setManaged(true);
        }
        if (settingsPage != null) {
            settingsPage.setVisible(false);
            settingsPage.setManaged(false);
        }
        config.settingsPageOpen = false;
    }

    private void showSettingsPage() {
        if (mainPage != null) {
            mainPage.setVisible(false);
            mainPage.setManaged(false);
        }
        if (settingsPage != null) {
            settingsPage.setVisible(true);
            settingsPage.setManaged(true);
        }
        config.settingsPageOpen = true;
        if (settingsPanel != null) {
            settingsPanel.refreshAccountRow();
        }
    }

    private void refreshAuth() {
        Session session = authManager == null ? null : authManager.currentSession();
        boolean in = session != null && session.isAuthenticated();
        if (loginButton != null) {
            loginButton.setText("Войти");
            loginButton.setVisible(!in);
            loginButton.setManaged(!in);
            loginButton.setOnAction(e -> onLogin());
        }
        if (!in) {
            setLinkState(AgentLoop.LinkState.OFF);
            applyStats(AgentLoop.Stats.offline());
        }
    }

    private void applyStats(AgentLoop.Stats snapshot) {
        AgentLoop.Stats value = snapshot == null ? AgentLoop.Stats.offline() : snapshot;
        if (agentStatusLabel != null) {
            agentStatusLabel.setText(value.status() == AgentLoop.AgentStatus.ONLINE ? "В сети" : "Не в сети");
        }
        if (queueCountLabel != null) {
            queueCountLabel.setText(value.queued() + " на очереди");
        }
        if (doneCountLabel != null) {
            doneCountLabel.setText(value.done() + " выполнено");
        }
    }

    private void setLinkState(AgentLoop.LinkState state) {
        if (apiDot == null) {
            return;
        }
        String color = switch (state) {
            case OK -> "#3dcc7a";
            case ERR -> "#c45c4a";
            case OFF -> "#5c616a";
        };
        apiDot.setStyle("-fx-fill: " + color + ";");
        apiDot.getStyleClass().removeAll("api-dot-ok", "api-dot-err", "api-dot-off");
        apiDot.getStyleClass().add(switch (state) {
            case OK -> "api-dot-ok";
            case ERR -> "api-dot-err";
            case OFF -> "api-dot-off";
        });
    }

    private void status(String text) {
        log.debug("settings: {}", text);
    }
}
