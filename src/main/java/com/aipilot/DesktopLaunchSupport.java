package com.aipilot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop JavaFX launch: Linux needs GTK3/X11 before AWT; Windows needs FX on
 * the module path (fat JAR no longer shades JavaFX).
 */
public final class DesktopLaunchSupport {
    private DesktopLaunchSupport() {
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static Map<String, String> linuxProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("jdk.gtk.version", "3");
        properties.put("prism.lcdtext", "false");
        properties.put("prism.order", "es2,sw");
        properties.put("awt.toolkit.name", "XToolkit");
        properties.put("java.awt.headless", "false");
        return properties;
    }

    public static Map<String, String> windowsProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("file.encoding", "UTF-8");
        properties.put("prism.lcdtext", "false");
        properties.put("java.awt.headless", "false");
        return properties;
    }

    public static Map<String, String> desktopProperties() {
        if (isLinux()) {
            return linuxProperties();
        }
        if (isWindows()) {
            return windowsProperties();
        }
        return Map.of("java.awt.headless", "false");
    }

    public static void applyBeforeJavaFx() {
        for (Map.Entry<String, String> entry : desktopProperties().entrySet()) {
            if (System.getProperty(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    public static boolean javaFxOnModulePath() {
        return ModuleLayer.boot().findModule("javafx.graphics").isPresent();
    }

    public static boolean javaFxClassesPresent() {
        try {
            Class.forName("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean javaFxAvailable() {
        return javaFxOnModulePath() || javaFxClassesPresent();
    }

    public static String missingJavaFxMessage() {
        if (isWindows()) {
            return "JavaFX is missing. On Windows run run.bat or AIPilot.exe — not java -jar aipilot.jar.";
        }
        if (isLinux()) {
            return "JavaFX is not on the module path. On Ubuntu run ./run.sh, ./gradlew run, "
                    + "or the installed launcher (aipilot) — not java -jar.";
        }
        return "JavaFX is missing. Use the Gradle run task or the packaged app — not java -jar.";
    }
}
