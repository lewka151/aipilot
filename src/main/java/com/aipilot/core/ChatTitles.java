package com.aipilot.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Unique sidebar titles so Violentmonkey can find the same chat later. */
public final class ChatTitles {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ChatTitles() {
    }

    public static String unique(String projectName, String provider) {
        String slug = slug(projectName);
        String tag = "deepseek".equalsIgnoreCase(provider) ? "DS" : "QW";
        String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 4);
        return "AIPilot · " + slug + " · " + tag + " · " + STAMP.format(LocalDateTime.now()) + "-" + rand;
    }

    public static String markdown(String qwenTitle, String deepseekTitle) {
        return markdown(qwenTitle, "", deepseekTitle, "");
    }

    public static String markdown(String qwenTitle, String qwenId, String deepseekTitle, String deepseekId) {
        String qwen = qwenTitle == null || qwenTitle.isBlank() ? "(будет задано приложением)" : qwenTitle;
        String deepseek = deepseekTitle == null || deepseekTitle.isBlank() ? "(будет задано приложением)" : deepseekTitle;
        String qid = qwenId == null || qwenId.isBlank() ? "(ещё нет)" : qwenId;
        String did = deepseekId == null || deepseekId.isBlank() ? "(ещё нет)" : deepseekId;
        return "# Имена чатов этого проекта\n"
                + "Ищи и используй строго эти заголовки, не создавай дубликаты.\n"
                + "Переход в браузере — по прямой ссылке с id, не по клику в сайдбаре.\n"
                + "- Qwen: " + qwen + "\n"
                + "- Qwen-id: " + qid + "\n"
                + "- DeepSeek: " + deepseek + "\n"
                + "- DeepSeek-id: " + did + "\n";
    }

    static String slug(String projectName) {
        String s = projectName == null ? "" : projectName.trim();
        if (s.isBlank()) {
            s = "project";
        }
        s = s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ");
        if (s.length() > 40) {
            s = s.substring(0, 40).trim();
        }
        return s;
    }
}
