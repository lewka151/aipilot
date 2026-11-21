package com.aipilot.infra.api;

import java.util.Map;

/** Thin wrapper for run-scoped system events to the site API. */
public final class SystemTelemetry {
    private final SiteApiClient api;
    private volatile SiteApiClient.RunSession run;
    private volatile String projectId = "";

    public SystemTelemetry(SiteApiClient api) {
        this.api = api;
    }

    public void setRun(SiteApiClient.RunSession run) {
        this.run = run;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId == null ? "" : projectId;
    }

    public Long runId() {
        SiteApiClient.RunSession s = run;
        return s == null ? null : s.runId();
    }

    public void event(String kind, String message) {
        event(kind, message, null);
    }

    public void event(String kind, String message, Map<String, ?> payload) {
        if (api == null) {
            return;
        }
        api.logEvent(projectId, runId(), kind, message, payload);
    }
}
