package com.aipilot.ui;

import com.aipilot.infra.config.AppConfig;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Maximize to screen work area without OS fullscreen (keeps taskbar visible). */
public final class WindowMaximizeHelper {
    private static volatile boolean customMaximized;

    private WindowMaximizeHelper() {
    }

    public static boolean isCustomMaximized() {
        return customMaximized;
    }

    public static void toggle(Stage stage, AppConfig config) {
        if (stage == null || config == null) {
            return;
        }
        if (customMaximized) {
            restore(stage, config);
        } else {
            applyWorkArea(stage, config);
        }
    }

    public static void applyWorkArea(Stage stage, AppConfig config) {
        if (stage == null || config == null) {
            return;
        }
        stage.setMaximized(false);
        if (!customMaximized) {
            saveNormalBounds(stage, config);
        }
        Rectangle2D vb = visualBoundsFor(stage);
        stage.setX(vb.getMinX());
        stage.setY(vb.getMinY());
        stage.setWidth(vb.getWidth());
        stage.setHeight(vb.getHeight());
        customMaximized = true;
        config.windowMaximized = true;
    }

    public static void restore(Stage stage, AppConfig config) {
        if (stage == null || config == null) {
            return;
        }
        stage.setMaximized(false);
        customMaximized = false;
        config.windowMaximized = false;
        double w = config.windowNormalWidth > 0 ? config.windowNormalWidth : config.windowWidth;
        double h = config.windowNormalHeight > 0 ? config.windowNormalHeight : config.windowHeight;
        if (w >= 880) {
            stage.setWidth(w);
        }
        if (h >= 520) {
            stage.setHeight(h);
        }
        if (config.windowNormalX >= 0) {
            stage.setX(config.windowNormalX);
        }
        if (config.windowNormalY >= 0) {
            stage.setY(config.windowNormalY);
        }
    }

    public static void applyFromConfig(Stage stage, AppConfig config) {
        if (stage == null || config == null) {
            return;
        }
        stage.setMaximized(false);
        if (config.windowMaximized) {
            applyWorkArea(stage, config);
        } else {
            customMaximized = false;
        }
    }

    static void saveNormalBounds(Stage stage, AppConfig config) {
        if (stage == null || config == null || customMaximized) {
            return;
        }
        double w = stage.getWidth();
        double h = stage.getHeight();
        if (w >= 880 && h >= 520) {
            config.windowNormalWidth = w;
            config.windowNormalHeight = h;
            config.windowWidth = w;
            config.windowHeight = h;
            config.windowNormalX = stage.getX();
            config.windowNormalY = stage.getY();
            config.windowX = stage.getX();
            config.windowY = stage.getY();
        }
    }

    private static Rectangle2D visualBoundsFor(Stage stage) {
        double cx = stage.getX() + stage.getWidth() / 2;
        double cy = stage.getY() + stage.getHeight() / 2;
        for (Screen s : Screen.getScreens()) {
            Rectangle2D vb = s.getVisualBounds();
            if (vb.contains(cx, cy)) {
                return vb;
            }
        }
        return Screen.getPrimary().getVisualBounds();
    }
}
