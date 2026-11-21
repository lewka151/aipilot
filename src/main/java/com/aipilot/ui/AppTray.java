package com.aipilot.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** AWT system tray with Platforma151-style themed context menu. */
public final class AppTray {
    private static final Logger log = LoggerFactory.getLogger(AppTray.class);

    private final SystemTray systemTray;
    private final TrayIcon trayIcon;
    private final ThemedPopupMenu popup;
    private boolean installed;

    private AppTray(SystemTray systemTray, TrayIcon trayIcon, ThemedPopupMenu popup) {
        this.systemTray = systemTray;
        this.trayIcon = trayIcon;
        this.popup = popup;
    }

    public static boolean isSupported() {
        try {
            return SystemTray.isSupported();
        } catch (Throwable t) {
            log.debug("SystemTray unavailable: {}", t.toString());
            return false;
        }
    }

    public static AppTray install(Runnable onOpen, Runnable onExit) {
        try {
            if (!isSupported()) {
                return null;
            }
            if (SwingUtilities.isEventDispatchThread()) {
                return installOnEdt(onOpen, onExit);
            }
            final AppTray[] created = new AppTray[1];
            final Throwable[] error = new Throwable[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    created[0] = installOnEdt(onOpen, onExit);
                } catch (Throwable e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
            return created[0];
        } catch (Throwable e) {
            log.warn("Tray install failed: {}", e.getMessage());
            return null;
        }
    }

    private static AppTray installOnEdt(Runnable onOpen, Runnable onExit) throws Exception {
        SystemTray tray = SystemTray.getSystemTray();
        ThemedPopupMenu menu = buildMenu(onOpen, onExit);
        TrayIcon icon = new TrayIcon(createIcon(), "AIPilot");
        icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                        if (menu.isVisible()) {
                            menu.dismiss();
                            return;
                        }
                        if (onOpen != null) {
                            onOpen.run();
                        }
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON3 || e.isPopupTrigger()) {
                        showTrayMenu(menu, e);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1 && menu.isVisible()) {
                        menu.dismiss();
                        return;
                    }
                    if (e.isPopupTrigger()) {
                        showTrayMenu(menu, e);
                    }
                }
            });

            tray.add(icon);
            AppTray appTray = new AppTray(tray, icon, menu);
            appTray.installed = true;
            log.info("System tray installed (themed menu)");
            return appTray;
    }

    private static void showTrayMenu(ThemedPopupMenu menu, MouseEvent e) {
        SwingUtilities.invokeLater(() -> {
            if (menu.isVisible()) {
                menu.dismiss();
                return;
            }
            menu.dismiss();
            int x;
            int y;
            try {
                x = e.getXOnScreen();
                y = e.getYOnScreen();
            } catch (Exception ex) {
                x = e.getX();
                y = e.getY();
            }
            menu.showAtScreen(x, y);
        });
    }

    private static ThemedPopupMenu buildMenu(Runnable onOpen, Runnable onExit) {
        ThemedPopupMenu menu = new ThemedPopupMenu();
        menu.addItem("Открыть", e -> {
            if (onOpen != null) {
                onOpen.run();
            }
        });
        menu.addMenuSeparator();
        menu.addItem("Выход", e -> {
            if (onExit != null) {
                onExit.run();
            }
        });
        return menu;
    }

    public boolean isInstalled() {
        return installed;
    }

    public void uninstall() {
        if (!installed) {
            return;
        }
        try {
            if (popup != null) {
                popup.dispose();
            }
            systemTray.remove(trayIcon);
        } catch (Exception ignored) {
        }
        installed = false;
    }

    public void showMessage(String title, String message, TrayMessageType type) {
        if (!installed || trayIcon == null) {
            return;
        }
        try {
            TrayIcon.MessageType awtType = switch (type) {
                case ERROR -> TrayIcon.MessageType.ERROR;
                case WARNING -> TrayIcon.MessageType.WARNING;
                default -> TrayIcon.MessageType.INFO;
            };
            trayIcon.displayMessage(title, message, awtType);
        } catch (Exception e) {
            log.debug("Tray message failed: {}", e.getMessage());
        }
    }

    public enum TrayMessageType {
        INFO, WARNING, ERROR
    }

    private static Image createIcon() {
        try (InputStream in = AppTray.class.getResourceAsStream("/icons/aipilot.png")) {
            if (in != null) {
                BufferedImage img = ImageIO.read(in);
                if (img != null) {
                    return img;
                }
            }
        } catch (Exception e) {
            log.debug("Tray icon load failed: {}", e.getMessage());
        }
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x0a, 0x0b, 0x0e));
        g.fillRoundRect(0, 0, 32, 32, 6, 6);
        g.setColor(new Color(0xa8, 0x97, 0x6a));
        g.drawRoundRect(1, 1, 29, 29, 5, 5);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Segoe UI", Font.BOLD, 11));
        g.drawString("AIP", 2, 22);
        g.dispose();
        return img;
    }
}
