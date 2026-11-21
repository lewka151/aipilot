package com.aipilot.ui;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Drag an undecorated stage by pressing and moving a component. */
public final class WindowDragHelper {
    private WindowDragHelper() {
    }

    public static void install(Stage stage, Node... nodes) {
        if (stage == null || nodes == null) {
            return;
        }
        final double[] grab = new double[2];
        final boolean[] dragging = {false};

        for (Node node : nodes) {
            if (node == null) {
                continue;
            }
            node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                if (!e.isPrimaryButtonDown() || e.getClickCount() >= 2) {
                    dragging[0] = false;
                    return;
                }
                // Let EdgeResizeHelper own the top/side rim of the title bar.
                if (node instanceof Region region && EdgeResizeHelper.inResizeZone(region, e.getX(), e.getY())) {
                    dragging[0] = false;
                    return;
                }
                // Local Y near top of window when title bar is the drag surface.
                if (e.getY() < EdgeResizeHelper.ZONE) {
                    dragging[0] = false;
                    return;
                }
                dragging[0] = true;
                grab[0] = e.getScreenX() - stage.getX();
                grab[1] = e.getScreenY() - stage.getY();
                e.consume();
            });
            node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> dragging[0] = false);
            node.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
                if (!dragging[0] || stage.isMaximized() || WindowMaximizeHelper.isCustomMaximized()) {
                    return;
                }
                stage.setX(e.getScreenX() - grab[0]);
                stage.setY(e.getScreenY() - grab[1]);
                e.consume();
            });
        }
    }
}
