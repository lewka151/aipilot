package com.aipilot.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Project {
    public String id;
    public String name;
    /** Human-readable title shown in the app and on /ai. */
    public String displayName;
    public String description;
    public List<ProjectPath> paths = new ArrayList<>();
    public List<ProjectServer> servers = new ArrayList<>();
    /** Site device key this project's folders are bound to; empty = unbound. */
    public String deviceId;
    /** Present only in the settings list: local copy exists, site row is gone. */
    @JsonIgnore
    public boolean orphan;
    public String qwenChatId;
    public String deepseekChatId;
    /** Display name of Qwen web project to select before prompts. */
    public String qwenProjectName;
    /** Qwen chat title to find/create in sidebar (first prompt). */
    public String qwenChatTitle;
    /** DeepSeek chat title to find/create in sidebar (first prompt). */
    public String deepseekChatTitle;

    public String qwenStaticReadmePath;
    public String deepseekStaticPath;
    public String projectDynamicReadmePath;
    public String fileIndexPath;
    public String filesMdPath;
    public String aiRulesPath;
    public String basedMdPath;

    public Map<String, Object> metadata = new HashMap<>();
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;

    /** Absolute root of the project folder (not serialized to project.json as relative). */
    @JsonIgnore
    public String root;

    @JsonIgnore
    public Path rootPath() {
        return Path.of(root != null ? root : ".");
    }

    @JsonIgnore
    public String dynamicReadmePath() {
        return projectDynamicReadmePath;
    }
}
