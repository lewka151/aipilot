package com.aipilot.ui;

import com.aipilot.infra.config.AppConfig;
import com.aipilot.infra.config.AppPaths;
import com.aipilot.infra.http.HttpClients;
import com.aipilot.infra.update.LocalBuildInfo;
import com.aipilot.infra.update.UpdateService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Periodic update checks (every 5 h) like Platforma151. */
public final class AutoUpdateScheduler {
    private static final Logger log = LoggerFactory.getLogger(AutoUpdateScheduler.class);
    private static final long INTERVAL_MS = 5L * 60L * 60L * 1000L;
    private static final long STARTUP_DELAY_MS = 90_000L;

    private final AppPaths paths;
    private final AppConfig config;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private AppTray tray;
    private BooleanSupplier windowVisible = () -> true;
    private Consumer<String> activityLog;
    private Timer timer;

    public AutoUpdateScheduler(AppPaths paths, AppConfig config) {
        this.paths = paths;
        this.config = config;
    }

    public void setTray(AppTray tray) {
        this.tray = tray;
    }

    public void setWindowVisibleCheck(BooleanSupplier windowVisible) {
        this.windowVisible = windowVisible == null ? () -> true : windowVisible;
    }

    public void setActivityLog(Consumer<String> activityLog) {
        this.activityLog = activityLog;
    }

    public void start() {
        if (!config.autoUpdate) {
            return;
        }
        timer = new Timer("aipilot-auto-update", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                check(true);
            }
        }, STARTUP_DELAY_MS);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (config.autoUpdate) {
                    check(true);
                }
            }
        }, INTERVAL_MS, INTERVAL_MS);
    }

    public void shutdown() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    public void check(boolean silentIfUpToDate) {
        if (!checking.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                logActivity("проверка обновлений");
                var http = HttpClients.defaultClient(config);
                var rel = UpdateService.fetchRelease(http, config.apiBaseUrl);
                String localHash = LocalBuildInfo.readHash(paths);
                String localVersion = AppVersion.read();
                UpdateDecision decision = evaluate(rel, localHash, localVersion);
                if (!decision.needUpdate) {
                    if (shouldCacheRemoteHash(decision, localHash, localVersion, rel.version())
                            && rel.buildHash() != null && !rel.buildHash().isBlank()) {
                        LocalBuildInfo.writeHash(paths, rel.buildHash());
                    }
                    if (!silentIfUpToDate) {
                        notifyUser("AIPilot актуален", "Версия " + localVersion
                                + (localHash != null ? " · сборка " + localHash : "")
                                + "\n" + decision.reason, false);
                    } else {
                        log.debug("Update check: {}", decision.reason);
                    }
                    return;
                }
                String msg = "Доступна версия " + (rel.version() != null ? rel.version() : "?")
                        + (rel.buildHash() != null ? " (сборка " + rel.buildHash() + ")" : "")
                        + ".\n" + decision.reason;
                if (rel.installerUrl() != null) {
                    msg += "\nСкачайте установщик: " + rel.installerUrl();
                } else if (rel.windowsZipUrl() != null) {
                    msg += "\nСкачайте архив: " + rel.windowsZipUrl();
                }
                notifyUser("Вышла новая версия", msg, true);
            } catch (Exception e) {
                if (!silentIfUpToDate) {
                    notifyUser("Проверка обновлений", "Ошибка: " + e.getMessage(), false);
                } else {
                    log.debug("Silent update check failed: {}", e.getMessage());
                }
            } finally {
                config.lastUpdateCheckAt = Instant.now().toString();
                try {
                    config.save(paths.configFile());
                } catch (Exception ignored) {
                }
                checking.set(false);
            }
        }, "aipilot-update-check");
        t.setDaemon(true);
        t.start();
    }

    public record UpdateDecision(boolean needUpdate, String reason) {
    }

    public static boolean shouldCacheRemoteHash(
            UpdateDecision decision, String localHash, String localVersion, String remoteVersion) {
        if (decision.needUpdate) {
            return false;
        }
        if (localHash != null && !localHash.isBlank()) {
            return false;
        }
        if (remoteVersion != null && !remoteVersion.isBlank()
                && UpdateService.compareVersions(remoteVersion, localVersion) < 0) {
            return false;
        }
        return true;
    }

    public static UpdateDecision evaluate(UpdateService.ReleaseInfo rel, String localHash, String localVersion) {
        String remoteHash = rel.buildHash();
        String remoteVersion = rel.version();
        boolean bothHashesKnown = remoteHash != null && !remoteHash.isBlank()
                && localHash != null && !localHash.isBlank();
        boolean hashMismatch = bothHashesKnown && !remoteHash.equalsIgnoreCase(localHash);
        boolean remoteNewer = remoteVersion != null && !remoteVersion.isBlank()
                && UpdateService.isNewerVersion(remoteVersion, localVersion);
        boolean localNewer = remoteVersion != null && !remoteVersion.isBlank()
                && UpdateService.compareVersions(remoteVersion, localVersion) < 0;
        boolean sameVersion = remoteVersion != null && !remoteVersion.isBlank()
                && UpdateService.isSameVersion(remoteVersion, localVersion);

        if (remoteVersion == null || remoteVersion.isBlank()) {
            if (hashMismatch) {
                return new UpdateDecision(true, "другая сборка " + remoteHash);
            }
            if (localHash == null || localHash.isBlank()) {
                return new UpdateDecision(false, "базовая линия (hash неизвестен)");
            }
            return new UpdateDecision(false, "hash совпал или сервер без version");
        }
        if (localNewer) {
            return new UpdateDecision(false, "локальная v" + localVersion + " новее серверной v" + remoteVersion);
        }
        if (remoteNewer) {
            return new UpdateDecision(true, "сервер v" + remoteVersion + " > локальная v" + localVersion);
        }
        if (sameVersion && hashMismatch) {
            return new UpdateDecision(true, "та же v" + localVersion + ", другая сборка " + remoteHash);
        }
        return new UpdateDecision(false, "актуально v" + localVersion
                + (localHash != null ? " (" + localHash + ")" : ""));
    }

    private void logActivity(String message) {
        if (activityLog != null && message != null && !message.isBlank()) {
            activityLog.accept(message);
        }
    }

    private void notifyUser(String title, String message, boolean important) {
        boolean visible = windowVisible.getAsBoolean();
        if (!visible && tray != null && tray.isInstalled()) {
            tray.showMessage(title, message, important
                    ? AppTray.TrayMessageType.WARNING
                    : AppTray.TrayMessageType.INFO);
            return;
        }
        Platform.runLater(() -> {
            Alert alert = new Alert(important ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.show();
        });
    }
}
