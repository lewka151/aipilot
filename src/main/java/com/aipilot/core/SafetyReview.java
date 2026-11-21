package com.aipilot.core;

import com.aipilot.core.model.Project;
import com.aipilot.core.model.SafetyVerdict;
import com.aipilot.infra.api.BridgeJob;
import com.aipilot.infra.api.SiteApiClient;
import com.aipilot.infra.api.SiteBridgeClient;
import com.aipilot.infra.config.AppConfig;

import java.util.UUID;

/** DeepSeek gate before python / PowerShell / SSH. No APPROVED → do not run. */
public final class SafetyReview {
    private SafetyReview() {
    }

    public static SafetyVerdict beforeExec(SiteApiClient api, SiteBridgeClient bridge, AppConfig config,
                                           Project project, String language, String code, String goal) {
        String denied = CodeRunService.denyReason(code);
        if (denied != null && !denied.isBlank()) {
            return new SafetyVerdict(false, denied);
        }
        if (bridge == null || project == null) {
            return new SafetyVerdict(false, "Нет DeepSeek для ревью");
        }
        if (!bridge.isProviderReady("deepseek")) {
            return new SafetyVerdict(false,
                    "Вкладка chat.deepseek.com со скриптом должна быть открыта");
        }
        String body = buildBody(language, code, goal, project);
        String prompt = null;
        if (api != null) {
            prompt = api.assemblePrompt(project.id, "safety_review", body,
                    ProjectAliases.displayName(project), project.deepseekChatTitle);
        }
        if (prompt == null || prompt.isBlank()) {
            prompt = "Проверь безопасность команды. Последняя строка ответа: APPROVED или DENY: причина.\n\n"
                    + body;
        }
        try {
            BridgeJob job = new BridgeJob(
                    UUID.randomUUID().toString(),
                    "deepseek",
                    prompt,
                    ProjectAliases.displayName(project),
                    project.qwenProjectName,
                    project.deepseekChatTitle,
                    project.deepseekChatId,
                    true,
                    "prompt",
                    project.id
            );
            long timeout = config == null ? 180 : Math.max(30, config.bridgeGenerationTimeoutSec);
            String answer = bridge.submitAndWait(job, timeout);
            return SafetyVerdict.parse(answer);
        } catch (Exception e) {
            return new SafetyVerdict(false, "DeepSeek недоступен: " + e.getMessage());
        }
    }

    private static String buildBody(String language, String code, String goal, Project project) {
        StringBuilder sb = new StringBuilder();
        sb.append("Язык: ").append(language == null ? "" : language).append('\n');
        if (goal != null && !goal.isBlank()) {
            sb.append("Цель: ").append(goal.strip()).append('\n');
        }
        if (project != null && project.root != null) {
            sb.append("Каталог: ").append(project.root).append('\n');
        }
        sb.append("Код или команда:\n").append(code == null ? "" : code);
        return sb.toString();
    }
}
