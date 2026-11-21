package com.aipilot.infra.api;

/** Job dispatched to Qwen/DeepSeek userscript via site bridge API. */
public record BridgeJob(
        String id,
        String provider,
        String prompt,
        String projectName,
        String qwenProjectName,
        String chatTitle,
        String chatId,
        boolean expertMode,
        String action,
        String projectId
) {
    public BridgeJob(
            String id,
            String provider,
            String prompt,
            String projectName,
            String qwenProjectName,
            String chatTitle,
            boolean expertMode) {
        this(id, provider, prompt, projectName, qwenProjectName, chatTitle, "", expertMode, "prompt", "");
    }

    public BridgeJob(
            String id,
            String provider,
            String prompt,
            String projectName,
            String qwenProjectName,
            String chatTitle,
            boolean expertMode,
            String action) {
        this(id, provider, prompt, projectName, qwenProjectName, chatTitle, "", expertMode, action, "");
    }

    public BridgeJob(
            String id,
            String provider,
            String prompt,
            String projectName,
            String qwenProjectName,
            String chatTitle,
            String chatId,
            boolean expertMode) {
        this(id, provider, prompt, projectName, qwenProjectName, chatTitle, chatId, expertMode, "prompt", "");
    }

    public BridgeJob(
            String id,
            String provider,
            String prompt,
            String projectName,
            String qwenProjectName,
            String chatTitle,
            String chatId,
            boolean expertMode,
            String action) {
        this(id, provider, prompt, projectName, qwenProjectName, chatTitle, chatId, expertMode, action, "");
    }

    public String actionOrPrompt() {
        return action == null || action.isBlank() ? "prompt" : action;
    }

    public String chatIdOrEmpty() {
        return chatId == null ? "" : chatId.trim();
    }

    public String projectIdOrEmpty() {
        return projectId == null ? "" : projectId.trim();
    }
}
