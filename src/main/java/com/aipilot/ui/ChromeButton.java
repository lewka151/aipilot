package com.aipilot.ui;

import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

/** Title-bar chrome with painted glyph (platform151-style). */
public final class ChromeButton extends Region {
    public enum Kind {
        MINIMIZE, MAXIMIZE, CLOSE
    }

    private static final Color HOVER = Color.web("#232833");
    private static final Color CLOSE_HOVER = Color.web("#c44b4b");
    private static final Color FG = Color.web("#e6e4df");
    private static final Color FG_HOVER = Color.web("#a8976a");
    private static final double HEIGHT = WindowClip.TITLE_H + WindowClip.BORDER;
    private static final double CLOSE_W = 46 + WindowClip.BORDER;
    private static final double MIN_W = 40;

    private final Kind kind;
    private final Canvas canvas;
    private boolean hovered;
    private boolean maximized;

    public ChromeButton(Kind kind) {
        this.kind = kind;
        double w = kind == Kind.CLOSE ? CLOSE_W : MIN_W;
        setMinSize(w, HEIGHT);
        setPrefSize(w, HEIGHT);
        setMaxSize(w, HEIGHT);
        setPadding(Insets.EMPTY);
        setCursor(Cursor.DEFAULT);
        canvas = new Canvas(w, HEIGHT);
        getChildren().add(canvas);
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
        setOnMouseEntered(e -> {
            hovered = true;
            redraw();
        });
        setOnMouseExited(e -> {
            hovered = false;
            redraw();
        });
        redraw();
    }

    public void setMaximizedState(boolean maximized) {
        this.maximized = maximized;
        redraw();
    }

    private void redraw() {
        double w = getWidth() > 0 ? getWidth() : (kind == Kind.CLOSE ? CLOSE_W : MIN_W);
        double h = getHeight() > 0 ? getHeight() : HEIGHT;
        canvas.setWidth(w);
        canvas.setHeight(h);
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        if (hovered) {
            g.setFill(kind == Kind.CLOSE ? CLOSE_HOVER : HOVER);
            if (kind == Kind.CLOSE) {
                WindowClip.fillTopRight(g, w, h);
            } else {
                g.fillRect(0, 0, w, h);
            }
        }
        g.setStroke(hovered ? (kind == Kind.CLOSE ? Color.WHITE : FG_HOVER) : FG);
        g.setLineWidth(1.7);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        double cx = w / 2;
        double cy = h / 2;
        double s = 5;
        if (kind == Kind.MINIMIZE) {
            g.strokeLine(cx - s, cy, cx + s, cy);
        } else if (kind == Kind.MAXIMIZE) {
            g.strokeLine(cx - s, cy - s + 1, cx + s, cy - s + 1);
            g.strokeLine(cx + s, cy - s + 1, cx + s, cy + s + 1);
            g.strokeLine(cx + s, cy + s + 1, cx - s, cy + s + 1);
            g.strokeLine(cx - s, cy + s + 1, cx - s, cy - s + 1);
        } else {
            g.strokeLine(cx - s, cy - s, cx + s, cy + s);
            g.strokeLine(cx + s, cy - s, cx - s, cy + s);
        }
    }
}
