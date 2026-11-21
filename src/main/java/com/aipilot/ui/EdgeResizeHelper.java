package com.aipilot.ui;

import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** 8px edge/corner resize for an undecorated stage (platform151-style). */
public final class EdgeResizeHelper {
    static final int ZONE = 8;
    /** Narrower east rim so the vertical scrollbar stays interactive. */
    static final int EAST_ZONE = 3;

    private EdgeResizeHelper() {
    }

    public static void install(Stage stage, Region host) {
        if (stage == null || host == null) {
            return;
        }
        final double[] startScreen = new double[2];
        final double[] startBounds = new double[4];
        final Cursor[] cursor = {Cursor.DEFAULT};
        final boolean[] resizing = {false};

        // Capture phase so title-bar drag and content don't steal N/S edges.
        host.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (resizing[0]) {
                return;
            }
            if (stage.isMaximized() || WindowMaximizeHelper.isCustomMaximized()) {
                if (cursor[0] != Cursor.DEFAULT) {
                    cursor[0] = Cursor.DEFAULT;
                    host.setCursor(Cursor.DEFAULT);
                }
                return;
            }
            Point2D local = toHost(host, e);
            Cursor next = cursorAt(host, local.getX(), local.getY());
            if (next != Cursor.DEFAULT) {
                cursor[0] = next;
                host.setCursor(next);
                e.consume();
            } else if (cursor[0] != Cursor.DEFAULT) {
                cursor[0] = Cursor.DEFAULT;
                host.setCursor(Cursor.DEFAULT);
            }
        });

        host.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (!resizing[0]) {
                cursor[0] = Cursor.DEFAULT;
                host.setCursor(Cursor.DEFAULT);
            }
        });

        host.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isPrimaryButtonDown()) {
                resizing[0] = false;
                return;
            }
            Point2D local = toHost(host, e);
            cursor[0] = cursorAt(host, local.getX(), local.getY());
            if (cursor[0] == Cursor.DEFAULT || stage.isMaximized() || WindowMaximizeHelper.isCustomMaximized()) {
                resizing[0] = false;
                return;
            }
            resizing[0] = true;
            startScreen[0] = e.getScreenX();
            startScreen[1] = e.getScreenY();
            startBounds[0] = stage.getX();
            startBounds[1] = stage.getY();
            startBounds[2] = stage.getWidth();
            startBounds[3] = stage.getHeight();
            e.consume();
        });

        host.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizing[0]) {
                resizing[0] = false;
                e.consume();
            }
        });

        host.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!resizing[0] || cursor[0] == Cursor.DEFAULT) {
                return;
            }
            double dx = e.getScreenX() - startScreen[0];
            double dy = e.getScreenY() - startScreen[1];
            double x = startBounds[0];
            double y = startBounds[1];
            double w = startBounds[2];
            double h = startBounds[3];
            double minW = Math.max(stage.getMinWidth(), 200);
            double minH = Math.max(stage.getMinHeight(), 200);

            boolean moveX = cursor[0] == Cursor.W_RESIZE || cursor[0] == Cursor.NW_RESIZE || cursor[0] == Cursor.SW_RESIZE;
            boolean moveY = cursor[0] == Cursor.N_RESIZE || cursor[0] == Cursor.NW_RESIZE || cursor[0] == Cursor.NE_RESIZE;
            boolean growX = cursor[0] == Cursor.E_RESIZE || cursor[0] == Cursor.NE_RESIZE || cursor[0] == Cursor.SE_RESIZE || moveX;
            boolean growY = cursor[0] == Cursor.S_RESIZE || cursor[0] == Cursor.SW_RESIZE || cursor[0] == Cursor.SE_RESIZE || moveY;

            if (moveX) {
                x += dx;
                w -= dx;
            } else if (growX) {
                w += dx;
            }
            if (moveY) {
                y += dy;
                h -= dy;
            } else if (growY) {
                h += dy;
            }
            if (w < minW) {
                if (moveX) {
                    x = startBounds[0] + startBounds[2] - minW;
                }
                w = minW;
            }
            if (h < minH) {
                if (moveY) {
                    y = startBounds[1] + startBounds[3] - minH;
                }
                h = minH;
            }
            stage.setX(x);
            stage.setY(y);
            stage.setWidth(w);
            stage.setHeight(h);
            e.consume();
        });
    }

    /** True when local coords are in the 8px resize rim (for WindowDragHelper). */
    public static boolean inResizeZone(Region host, double x, double y) {
        return host != null && cursorAt(host, x, y) != Cursor.DEFAULT;
    }

    private static Point2D toHost(Region host, MouseEvent e) {
        return host.sceneToLocal(e.getSceneX(), e.getSceneY());
    }

    private static Cursor cursorAt(Region host, double x, double y) {
        double w = host.getWidth();
        double h = host.getHeight();
        if (w <= 0 || h <= 0) {
            return Cursor.DEFAULT;
        }
        boolean north = y < ZONE;
        boolean south = y >= h - ZONE;
        boolean west = x < ZONE;
        boolean east = x >= w - EAST_ZONE;
        if (north && west) {
            return Cursor.NW_RESIZE;
        }
        if (north && east) {
            return Cursor.NE_RESIZE;
        }
        if (south && west) {
            return Cursor.SW_RESIZE;
        }
        if (south && east) {
            return Cursor.SE_RESIZE;
        }
        if (north) {
            return Cursor.N_RESIZE;
        }
        if (south) {
            return Cursor.S_RESIZE;
        }
        if (west) {
            return Cursor.W_RESIZE;
        }
        if (east) {
            return Cursor.E_RESIZE;
        }
        return Cursor.DEFAULT;
    }
}
