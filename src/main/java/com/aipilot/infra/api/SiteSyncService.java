package com.aipilot.infra.api;

import com.aipilot.infra.auth.AuthManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.time.Instant;

/** Lightweight periodic sync of session metrics to site API. */
public final class SiteSyncService {
    private static final Logger log = LoggerFactory.getLogger(SiteSyncService.class);

    private final SiteApiClient api;
    private final Supplier<Boolean> authCheck;
    private final Runnable tickUi;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Instant lastSuccessAt;
    private volatile String lastError = "";
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aipilot-site-sync");
        t.setDaemon(true);
        return t;
    });

    public SiteSyncService(SiteApiClient api, AuthManager auth, Runnable tickUi) {
        this.api = api;
        this.authCheck = () -> {
            var s = auth.currentSession();
            return s != null && s.isAuthenticated();
        };
        this.tickUi = tickUi;
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler.scheduleAtFixedRate(() -> {
            syncNow();
        }, 5, 15, TimeUnit.SECONDS);
    }

    public boolean syncNow() {
        try {
            if (!authCheck.get()) {
                lastError = "not authenticated";
                return false;
            }
            boolean ok = api.pingSync();
            if (ok) {
                lastSuccessAt = Instant.now();
                lastError = "";
            } else {
                lastError = "sync endpoint rejected request";
            }
            if (tickUi != null) {
                javafx.application.Platform.runLater(tickUi);
            }
            return ok;
        } catch (Exception e) {
            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Site sync: {}", lastError);
            return false;
        }
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastError() {
        return lastError;
    }

    public boolean isStarted() {
        return started.get();
    }

    public void shutdown() {
        started.set(false);
        scheduler.shutdownNow();
    }
}
