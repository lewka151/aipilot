package com.aipilot.ui;

import com.aipilot.infra.config.AppConfig;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Persist and restore window bounds (Platforma151-style). */
public final class WindowBoundsHelper {
    private static final double MIN_W = 420;
    private static final double MIN_H = 480;

    private final Stage stage;
    private final AppConfig config;
    private final Runnable onSave;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aipilot-bounds");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending;

    public WindowBoundsHelper(Stage stage, AppConfig config, Runnable onSave) {
        this.stage = stage;
        this.config = config;
        this.onSave = onSave;
    }

    public void applySaved() {
        Rectangle2D bounds = screenFor(config.windowNormalX, config.windowNormalY);
        double w = pick(config.windowNormalWidth, config.windowWidth, 440);
        double h = pick(config.windowNormalHeight, config.windowHeight, 560);
        w = clamp(w, MIN_W, bounds.getWidth());
        h = clamp(h, MIN_H, bounds.getHeight());
        stage.setWidth(w);
        stage.setHeight(h);
        stage.setMinWidth(MIN_W);
        stage.setMinHeight(MIN_H);

        double x = config.windowNormalX >= 0 ? config.windowNormalX : config.windowX;
        double y = config.windowNormalY >= 0 ? config.windowNormalY : config.windowY;
        if (x >= 0 && y >= 0) {
            stage.setX(clamp(x, bounds.getMinX(), bounds.getMaxX() - w));
            stage.setY(clamp(y, bounds.getMinY(), bounds.getMaxY() - h));
        } else {
            stage.centerOnScreen();
        }
    }

    public void bindListeners() {
        Runnable debounced = this::scheduleSave;
        stage.widthProperty().addListener((o, a, b) -> debounced.run());
        stage.heightProperty().addListener((o, a, b) -> debounced.run());
        stage.xProperty().addListener((o, a, b) -> debounced.run());
        stage.yProperty().addListener((o, a, b) -> debounced.run());
    }

    public void copyToConfig() {
        config.windowMaximized = false;
        if (!WindowMaximizeHelper.isCustomMaximized()) {
            double w = stage.getWidth();
            double h = stage.getHeight();
            if (w >= MIN_W && h >= MIN_H) {
                config.windowWidth = w;
                config.windowHeight = h;
                config.windowNormalWidth = w;
                config.windowNormalHeight = h;
                config.windowX = stage.getX();
                config.windowY = stage.getY();
                config.windowNormalX = stage.getX();
                config.windowNormalY = stage.getY();
            }
        }
    }

    public void persistNow() {
        Platform.runLater(() -> {
            copyToConfig();
            if (onSave != null) {
                onSave.run();
            }
        });
    }

    private void scheduleSave() {
        if (pending != null) {
            pending.cancel(false);
        }
        pending = scheduler.schedule(this::persistNow, 300, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static double pick(double primary, double fallback, double defaultVal) {
        if (primary > 0) {
            return primary;
        }
        if (fallback > 0) {
            return fallback;
        }
        return defaultVal;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }

    private static Rectangle2D screenFor(double x, double y) {
        if (x >= 0 && y >= 0) {
            for (Screen s : Screen.getScreens()) {
                if (s.getVisualBounds().contains(x, y)) {
                    return s.getVisualBounds();
                }
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }
}
