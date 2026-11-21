package com.aipilot.ui;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/** Rounds the undecorated window to the same 12px radius as site cards. */
public final class WindowClip {
    public static final double RADIUS = 12;
    public static final double TITLE_H = 42;
    public static final double BORDER = 1;

    private WindowClip() {
    }

    public static void install(Region root) {
        if (root == null) {
            return;
        }
        Rectangle clip = new Rectangle();
        clip.setArcWidth(RADIUS * 2);
        clip.setArcHeight(RADIUS * 2);
        root.setClip(clip);
        Runnable sync = () -> {
            clip.setWidth(Math.max(0, root.getWidth()));
            clip.setHeight(Math.max(0, root.getHeight()));
        };
        root.widthProperty().addListener((o, a, b) -> sync.run());
        root.heightProperty().addListener((o, a, b) -> sync.run());
        sync.run();
    }

    /** Quarter-ellipse matching the clip at the top-right of a chrome close hit-area. */
    public static void fillTopRight(GraphicsContext g, double w, double h) {
        if (g == null || w <= 0 || h <= 0) {
            return;
        }
        double r = Math.min(RADIUS, Math.min(w, h));
        g.beginPath();
        g.moveTo(0, 0);
        if (w > r) {
            g.lineTo(w - r, 0);
        }
        g.arcTo(w, 0, w, r, r);
        g.lineTo(w, h);
        g.lineTo(0, h);
        g.closePath();
        g.fill();
    }
}
