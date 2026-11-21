package com.aipilot.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/** Square icon control matching site .ai-icon-btn glyphs. */
public final class IconButton extends Button {
    public enum Glyph {
        REFRESH, FOLDER
    }

    private static final Color FG = Color.web("#ffffff");
    private static final double SIZE = 32;
    private static final double ICON = 18;

    public IconButton(Glyph glyph) {
        setText("");
        getStyleClass().setAll("button", "button-secondary", "icon-btn", "ai-icon-btn");
        Canvas canvas = new Canvas(ICON, ICON);
        draw(canvas.getGraphicsContext2D(), glyph);
        setGraphic(canvas);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
    }

    public static IconButton refresh() {
        return new IconButton(Glyph.REFRESH);
    }

    public static IconButton folder() {
        return new IconButton(Glyph.FOLDER);
    }

    /**
     * Site refresh SVG:
     * M21 12a9 9 0 1 1-2.64-6.36 + M21 3v6h-6 (viewBox 0 0 24 24).
     */
    private static void draw(GraphicsContext g, Glyph glyph) {
        double s = ICON / 24.0;
        g.setStroke(FG);
        g.setFill(FG);
        g.setLineWidth(2.4 * s);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        if (glyph == Glyph.REFRESH) {
            double cx = 12 * s;
            double cy = 12 * s;
            double r = 9 * s;
            // Start east (0°), large clockwise arc to 45° → SVG large-arc+sweep.
            g.strokeArc(cx - r, cy - r, r * 2, r * 2, 0, -315, ArcType.OPEN);
            g.strokeLine(21 * s, 3 * s, 21 * s, 9 * s);
            g.strokeLine(21 * s, 9 * s, 15 * s, 9 * s);
        } else if (glyph == Glyph.FOLDER) {
            g.setLineWidth(1.8 * s);
            g.beginPath();
            g.moveTo(3 * s, 8 * s);
            g.lineTo(3 * s, 19 * s);
            g.lineTo(21 * s, 19 * s);
            g.lineTo(21 * s, 10 * s);
            g.lineTo(12 * s, 10 * s);
            g.lineTo(10 * s, 7 * s);
            g.lineTo(3 * s, 7 * s);
            g.closePath();
            g.stroke();
        }
    }
}
