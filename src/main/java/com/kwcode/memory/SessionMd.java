package com.kwcode.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 会话连续性 - 会话结束时自动生成SESSION.md摘要，下次启动自动加载
 * <p>
 * 设计：会话结束时把本次完成的任务摘要写入 .kaiwu/SESSION.md，
 * 下次启动时自动读取，注入到首次Gate调用的memory_context。
 * 文件限制50行，超出时保留最近的条目。
 * </p>
 * @origin Python: memory.session_md
 */
public class SessionMd {

    private static final Logger log = LoggerFactory.getLogger(SessionMd.class);
    private static final int MAX_LINES = 50;

    /**
     * 加载上次会话摘要（启动时调用）
     * @origin Python: memory.session_md.load_session(project_root) -> str
     * @param projectRoot 项目根目录
     * @return 摘要文本或空字符串
     */
    public String loadSession(String projectRoot) {
        Path path = sessionPath(projectRoot);
        if (!Files.isRegularFile(path)) return "";
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8).strip();
            if (!content.isEmpty()) log.info("[session] Loaded session context ({} chars)", content.length());
            return content;
        } catch (IOException e) {
            log.warn("[session] Failed to load SESSION.md: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 会话结束时保存摘要
     * @origin Python: memory.session_md.save_session(project_root, tasks_completed)
     * @param projectRoot 项目根目录
     * @param tasksCompleted 已完成的任务列表，每项包含input/success/files/elapsed
     */
    public void saveSession(String projectRoot, List<SessionTask> tasksCompleted) {
        if (tasksCompleted == null || tasksCompleted.isEmpty()) return;

        Path path = sessionPath(projectRoot);
        try { Files.createDirectories(path.getParent()); } catch (IOException e) { return; }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        List<String> newEntries = new ArrayList<>();
        newEntries.add("## 会话 " + now + " (" + tasksCompleted.size() + " 个任务)\n");

        for (int i = Math.max(0, tasksCompleted.size() - 10); i < tasksCompleted.size(); i++) {
            SessionTask task = tasksCompleted.get(i);
            String status = task.success ? "OK" : "FAIL";
            String inputText = task.input != null ? task.input.substring(0, Math.min(50, task.input.length())) : "";
            String line = "- [" + status + "] " + inputText;
            if (task.files != null && !task.files.isEmpty()) {
                line += " → " + String.join(", ", task.files.subList(0, Math.min(3, task.files.size())));
            }
            if (task.elapsed > 0) line += String.format(" (%.0fs)", task.elapsed);
            newEntries.add(line);
        }
        newEntries.add("");

        // 读取现有内容
        String existing = "";
        if (Files.isRegularFile(path)) {
            try { existing = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { /* ignore */ }
        }

        // 新内容在前
        String combined = String.join("\n", newEntries) + "\n" + existing;
        List<String> lines = Arrays.asList(combined.split("\n"));
        if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);

        try {
            Files.writeString(path, String.join("\n", lines), StandardCharsets.UTF_8);
            log.info("[session] Saved session summary ({} tasks)", tasksCompleted.size());
        } catch (IOException e) {
            log.warn("[session] Failed to save SESSION.md: {}", e.getMessage());
        }
    }

    /**
     * 会话任务记录
     */
    public record SessionTask(String input, boolean success, List<String> files, double elapsed) {}

    private Path sessionPath(String projectRoot) {
        return Path.of(projectRoot, ".kaiwu", "SESSION.md");
    }
}
