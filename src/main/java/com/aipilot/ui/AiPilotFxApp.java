package com.aipilot.ui;

import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AiPilotFxApp extends Application {
    private static final Logger log = LoggerFactory.getLogger(AiPilotFxApp.class);

    private static AiPilotFxApp instance;
    private Stage stage;
    private MainController controller;
    private AppPaths paths;
    private AppConfig config;
    private WindowBoundsHelper boundsHelper;
    private AppTray tray;
    private AutoUpdateScheduler autoUpdateScheduler;

    public static void launchUi(String[] args) {
        launch(args);
    }

    public static AiPilotFxApp getInstance() {
        return instance;
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        instance = this;
        this.stage = primaryStage;
        Platform.setImplicitExit(false);

        paths = AppPaths.resolve();
        System.setProperty("aipilot.log.dir", paths.logDir().toAbsolutePath().toString());
        config = AppConfig.load(paths.configFile());
        try {
            config.save(paths.configFile());
        } catch (Exception e) {
            log.warn("Config save failed: {}", e.getMessage());
        }

        boolean startTray = config.startHiddenInTray;
        for (String a : getParameters().getRaw()) {
            if ("--tray".equalsIgnoreCase(a)) {
                startTray = true;
            }
        }

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.init(this, paths, config);

        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setResizable(true);
        var iconUrl = getClass().getResource("/icons/aipilot.png");
        if (iconUrl != null) {
            primaryStage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }

        Scene scene = new Scene(root);
        scene.setFill(javafx.scene.paint.Color.TRANSPARENT);
        var css = getClass().getResource("/css/app.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        primaryStage.setTitle("AIPilot");
        primaryStage.setScene(scene);

        boundsHelper = new WindowBoundsHelper(primaryStage, config, this::saveConfigQuiet);
        boundsHelper.applySaved();
        boundsHelper.bindListeners();

        primaryStage.setOnCloseRequest(e -> {
            e.consume();
            hideToTray();
        });

        tray = AppTray.install(this::restoreFromTray, this::requestExit);

        if (!startTray || tray == null || !tray.isInstalled()) {
            primaryStage.show();
            Platform.runLater(() -> {
                if (controller != null) {
                    controller.restoreLayout();
                }
            });
        } else {
            hideToTray();
        }

        autoUpdateScheduler = new AutoUpdateScheduler(paths, config);
        autoUpdateScheduler.setTray(tray);
        autoUpdateScheduler.setWindowVisibleCheck(() -> stage != null && stage.isShowing());
        if (controller != null) {
            controller.bindAutoUpdate(autoUpdateScheduler);
        }
        autoUpdateScheduler.start();

        log.info("AIPilot UI started (tray={})", tray != null && tray.isInstalled());
    }

    public void hideToTray() {
        if (controller != null) {
            controller.persistUiState();
            controller.setUiVisible(false);
        }
        if (boundsHelper != null) {
            boundsHelper.persistNow();
        }
        if (stage != null) {
            stage.hide();
        }
    }

    public void restoreFromTray() {
        Platform.runLater(() -> {
            if (stage != null) {
                stage.show();
                stage.setIconified(false);
                stage.toFront();
                if (controller != null) {
                    controller.restoreLayout();
                }
            }
        });
    }

    public void requestExit() {
        Platform.runLater(() -> {
            if (controller != null && controller.isBusy()) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("AIPilot");
                alert.setHeaderText("Выполняется задача");
                alert.setContentText("Агент ещё выполняет команду. Прервать и выйти?");
                alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                styleAlert(alert);
                alert.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.OK) {
                        exitFully();
                    }
                });
                return;
            }
            exitFully();
        });
    }

    public void exitFully() {
        if (controller != null) {
            controller.shutdownResources();
        }
        if (boundsHelper != null) {
            boundsHelper.copyToConfig();
            saveConfigQuiet();
            boundsHelper.shutdown();
        }
        if (tray != null) {
            tray.uninstall();
        }
        if (autoUpdateScheduler != null) {
            autoUpdateScheduler.shutdown();
        }
        Platform.exit();
        System.exit(0);
    }

    public Stage getStage() {
        return stage;
    }

    public AppConfig getConfig() {
        return config;
    }

    public AppPaths getPaths() {
        return paths;
    }

    public MainController getController() {
        return controller;
    }

    public AutoUpdateScheduler getAutoUpdateScheduler() {
        return autoUpdateScheduler;
    }

    private void saveConfigQuiet() {
        try {
            config.save(paths.configFile());
        } catch (Exception e) {
            log.warn("Config save failed: {}", e.getMessage());
        }
    }

    private static void styleAlert(Alert alert) {
        DialogPane pane = alert.getDialogPane();
        var css = AiPilotFxApp.class.getResource("/css/app.css");
        if (css != null) {
            pane.getStylesheets().add(css.toExternalForm());
        }
        var exitBtn = pane.lookupButton(ButtonType.OK);
        if (exitBtn instanceof Button button) {
            button.setText("Выйти");
        }
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdownResources();
        }
        if (boundsHelper != null) {
            boundsHelper.copyToConfig();
            saveConfigQuiet();
            boundsHelper.shutdown();
        }
    }
}
