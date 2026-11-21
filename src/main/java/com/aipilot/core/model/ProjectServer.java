package com.aipilot.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectServer {
    public String alias;
    public String host;
    public int port = 22;
    public String login;
    public boolean hasPassword;

    /** Never persisted to project.json; filled from GET /projects/{id}/secrets. */
    @JsonIgnore
    public String password;

    public ProjectServer() {
    }

    public ProjectServer(String alias, String host, int port, String login) {
        this.alias = alias;
        this.host = host;
        this.port = port <= 0 ? 22 : port;
        this.login = login;
    }

    public String aliasOrEmpty() {
        return alias == null ? "" : alias.trim();
    }
}
