package com.aipilot.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;

/** Platforma151 color tokens for Swing tray menu. */
public final class AppTheme {
    public static final Color PANEL = new Color(0x161a21);
    public static final Color ACCENT = new Color(0xa8976a);
    public static final Color TEXT = new Color(0xe6e4df);
    public static final Color BORDER = new Color(0x2a2e36);
    public static final Color BTN_HOVER_BG = new Color(0x232830);

    public static Font uiFont = pickFont(new String[]{"Segoe UI", "Tahoma", "Arial", Font.SANS_SERIF}, 13f);

    private AppTheme() {
    }

    private static Font pickFont(String[] candidates, float size) {
        var available = java.util.Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String name : candidates) {
            if (Font.SANS_SERIF.equals(name) || available.contains(name)) {
                return new Font(name, Font.PLAIN, Math.round(size));
            }
        }
        return new Font(Font.DIALOG, Font.PLAIN, Math.round(size));
    }
}
