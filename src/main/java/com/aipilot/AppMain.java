package com.aipilot;

import com.aipilot.core.AgentLoop;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.api.SiteBridgeClient;
import com.aipilot.infra.auth.AuthManager;
import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.ui.AiPilotFxApp;
import com.aipilot.ui.AppVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: compact JavaFX agent or headless poller (--headless / --service).
 */
public final class AppMain {
    private static final Logger log = LoggerFactory.getLogger(AppMain.class);

    private AppMain() {
    }

    public static void main(String[] args) {
        boolean headless = false;
        for (String a : args) {
            if ("--headless".equalsIgnoreCase(a) || "--service".equalsIgnoreCase(a)) {
                headless = true;
            }
        }

        AppPaths paths = AppPaths.resolve();
        System.setProperty("aipilot.log.dir", paths.logDir().toAbsolutePath().toString());
        if (!SingleInstance.tryAcquire(paths)) {
            String msg = "AIPilot уже запущен. Закройте другое окно или процесс aipilot и попробуйте снова.";
            log.error(msg);
            System.err.println(msg);
            try {
                javax.swing.JOptionPane.showMessageDialog(null, msg, "AIPilot", javax.swing.JOptionPane.WARNING_MESSAGE);
            } catch (Throwable ignored) {
            }
            System.exit(3);
            return;
        }

        AppConfig config = AppConfig.load(paths.configFile());
        config.headless = headless || config.headless;
        try {
            config.save(paths.configFile());
        } catch (Exception e) {
            log.warn("Could not persist config: {}", e.getMessage());
        }

        if (config.headless) {
            runHeadless(paths, config);
        } else {
            prepareDesktopToolkit();
            if (!DesktopLaunchSupport.javaFxAvailable()) {
                String hint = DesktopLaunchSupport.missingJavaFxMessage();
                log.error(hint);
                System.err.println(hint);
                System.exit(2);
                return;
            }
            try {
                AiPilotFxApp.launchUi(args);
            } catch (Throwable t) {
                log.error("AIPilot UI failed", t);
                System.err.println("AIPilot UI failed: " + t.getMessage());
                t.printStackTrace(System.err);
                System.exit(1);
            }
        }
    }

    private static void prepareDesktopToolkit() {
        DesktopLaunchSupport.applyBeforeJavaFx();
        System.setProperty("java.awt.headless", "false");
        if (DesktopLaunchSupport.isLinux()) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable t) {
            log.warn("AWT toolkit unavailable: {}", t.getMessage());
        }
    }

    private static void runHeadless(AppPaths paths, AppConfig config) {
        log.info("AIPilot starting in headless/service mode");
        AuthManager auth = new AuthManager(paths, config);
        AppPaths scoped = auth.currentSession() != null && auth.currentSession().userId() != null
                ? paths.forUser(auth.currentSession().userId())
                : paths;
        SiteApiClient api = new SiteApiClient(config, auth, scoped);
        SiteBridgeClient bridge = new SiteBridgeClient(config, auth);
        AgentLoop loop = new AgentLoop(config, auth, api, bridge, scoped,
                event -> log.info("{} {} {}", event.kind(), event.title(), event.detail()),
                state -> log.info("link {}", state),
                null);
        Runtime.getRuntime().addShutdownHook(new Thread(loop::stop, "aipilot-shutdown"));
        loop.start();
        log.info("AIPilot agent {} polling", AppVersion.read());
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loop.stop();
        }
    }
}
