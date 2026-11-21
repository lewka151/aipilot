package com.aipilot.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectPath {
    public String path;
    public String name;
    public String role;
    /** Optional SSH server alias; file ops go over SFTP when set. */
    public String sshAlias;

    public ProjectPath() {
    }

    public ProjectPath(String path, String role) {
        this.path = path;
        this.role = role;
        this.name = role;
    }
}
