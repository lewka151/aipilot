package com.aipilot.ui;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Application version label for title bar. */
public final class AppVersion {
    private static final String FALLBACK = "0.1.1";

    private AppVersion() {
    }

    public static String read() {
        try (InputStream in = AppVersion.class.getResourceAsStream("/version.txt")) {
            if (in != null) {
                String v = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                if (!v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        Package pkg = AppVersion.class.getPackage();
        if (pkg != null) {
            String impl = pkg.getImplementationVersion();
            if (impl != null && !impl.isBlank()) {
                return impl;
            }
        }
        return FALLBACK;
    }
}
