package com.aipilot.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;

/** Platforma151-style dark context menus (Russian labels for text fields). */
public final class ThemedContextMenu {
    private ThemedContextMenu() {
    }

    public static ContextMenu create(MenuItem... items) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("themed-context-menu");
        menu.getItems().addAll(items);
        return menu;
    }

    public static MenuItem item(String text, Runnable action) {
        MenuItem mi = new MenuItem(text);
        mi.setOnAction(e -> {
            if (action != null) {
                action.run();
            }
        });
        return mi;
    }

    public static SeparatorMenuItem separator() {
        return new SeparatorMenuItem();
    }

    /** Cut / Copy / Paste / Select all — Russian, no Undo/Redo. */
    public static void installTextField(TextInputControl field) {
        if (field == null) {
            return;
        }
        MenuItem cut = item("Вырезать", field::cut);
        MenuItem copy = item("Копировать", field::copy);
        MenuItem paste = item("Вставить", field::paste);
        MenuItem selectAll = item("Выделить всё", field::selectAll);
        ContextMenu menu = create(cut, copy, paste, separator(), selectAll);
        menu.setOnShowing(e -> {
            boolean hasSel = field.getSelection().getLength() > 0;
            boolean editable = field.isEditable();
            cut.setDisable(!hasSel || !editable);
            copy.setDisable(!hasSel);
            paste.setDisable(!editable);
            selectAll.setDisable(field.getLength() == 0);
        });
        field.setContextMenu(menu);
    }
}
