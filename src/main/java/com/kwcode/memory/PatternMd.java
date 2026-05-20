package com.kwcode.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * PATTERN.md记忆 - 高频任务模式（飞轮数据源）
 * <p>
 * Spec §7.2: 存储在 .kaiwu/PATTERN.md。
 * 内部使用JSON sidecar文件(.pattern_stats.json)存储结构化统计，
 * Markdown仅用于展示。同时管理REFLECTION.md反思记录。
 * </p>
 * @origin Python: memory.pattern_md
 */
public class PatternMd {

    private static final Logger log = LoggerFactory.getLogger(PatternMd.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATS_FILENAME = ".pattern_stats.json";

    /**
     * 更新模式统计（每次任务后调用，无论成功失败）
     * @origin Python: memory.pattern_md.update(project_root, ctx, success, elapsed)
     * @param projectRoot 项目根目录
     * @param ctx 任务上下文
     * @param success 是否成功
     * @param elapsed 耗时（秒）
     */
    public void update(String projectRoot, TaskContext ctx, boolean success, double elapsed) {
        String expertType = (String) ctx.gateResult.getOrDefault("expert_type", "unknown");
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        Map<String, Object> stats = loadStats(projectRoot);

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) stats.getOrDefault(expertType, new HashMap<>());
        entry.putIfAbsent("count", 0);
        entry.putIfAbsent("success", 0);
        entry.putIfAbsent("total_elapsed", 0.0);
        entry.putIfAbsent("last_trigger", "");
        entry.putIfAbsent("recent_failures", new ArrayList<String>());

        entry.put("count", ((Number) entry.get("count")).intValue() + 1);
        if (success) {
            entry.put("success", ((Number) entry.get("success")).intValue() + 1);
        } else {
            String errorDetail = "";
            if (ctx.verifierOutput != null) errorDetail = ctx.verifierOutput.errorDetail();
            String failureRecord = "[" + now + "] " + (errorDetail != null ? errorDetail.substring(0, Math.min(100, errorDetail.length())) : "");
            @SuppressWarnings("unchecked")
            List<String> failures = (List<String>) entry.get("recent_failures");
            failures.add(failureRecord);
            if (failures.size() > 10) entry.put("recent_failures", failures.subList(failures.size() - 10, failures.size()));
        }

        entry.put("total_elapsed", ((Number) entry.get("total_elapsed")).doubleValue() + elapsed);
        entry.put("last_trigger", now);

        stats.put(expertType, entry);
        saveStats(projectRoot, stats);
        rebuildMarkdown(projectRoot, stats);
        log.info("Updated PATTERN.md for {} (success={})", expertType, success);
    }

    /**
     * 获取结构化统计（供飞轮消费）
     * @origin Python: memory.pattern_md.get_pattern_stats(project_root) -> list[dict]
     */
    public List<Map<String, Object>> getPatternStats(String projectRoot) {
        Map<String, Object> stats = loadStats(projectRoot);
        List<Map<String, Object>> result = new ArrayList<>();
        for (var e : stats.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) e.getValue();
            int count = ((Number) data.getOrDefault("count", 0)).intValue();
            int success = ((Number) data.getOrDefault("success", 0)).intValue();
            double totalElapsed = ((Number) data.getOrDefault("total_elapsed", 0.0)).doubleValue();
            Map<String, Object> item = new HashMap<>();
            item.put("task_type", e.getKey());
            item.put("count", count);
            item.put("success_rate", count > 0 ? (double) success / count : 0.0);
            item.put("avg_elapsed", count > 0 ? totalElapsed / count : 0.0);
            item.put("last_trigger", data.getOrDefault("last_trigger", ""));
            result.add(item);
        }
        result.sort((a, b) -> ((Number) b.get("count")).intValue() - ((Number) a.get("count")).intValue());
        return result;
    }

    /**
     * 统计历史相似失败次数
     * @origin Python: memory.pattern_md.count_similar_failures(expert_type, keywords, project_root) -> int
     */
    public int countSimilarFailures(String expertType, List<String> keywords, String projectRoot) {
        Map<String, Object> stats = loadStats(projectRoot);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) stats.get(expertType);
        if (entry == null) return 0;
        @SuppressWarnings("unchecked")
        List<String> failures = (List<String>) entry.getOrDefault("recent_failures", List.of());
        int count = 0;
        for (String line : failures) {
            for (String kw : keywords) {
                if (kw.length() > 1 && line.contains(kw)) { count++; break; }
            }
        }
        return count;
    }

    /**
     * 保存反思记录到REFLECTION.md
     * @origin Python: memory.pattern_md.save_reflection(project_root, expert_type, task_summary, reflection, success)
     */
    public void saveReflection(String projectRoot, String expertType, String taskSummary,
                                String reflection, boolean success) {
        String section = success
            ? "## " + expertType + " 注意事项"
            : "## " + expertType + " 失败模式";
        String prefix = success ? "注意" : "根因";
        String date = LocalDateTime.now().toLocalDate().toString();
        String entry = "- [" + date + "] " + taskSummary.substring(0, Math.min(30, taskSummary.length()))
            + " → " + prefix + "：" + reflection.substring(0, Math.min(80, reflection.length())) + "\n";

        Path path = reflectionPath(projectRoot);
        String content = "";
        if (Files.exists(path)) {
            try { content = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { /* ignore */ }
        } else {
            content = "# KWCode Pattern Memory\n";
        }

        if (content.contains(section)) {
            content = content.replace(section + "\n", section + "\n" + entry);
        } else {
            content += "\n" + section + "\n" + entry;
        }

        content = trimReflectionSections(content, 20);
        ensureDir(projectRoot);
        try { Files.writeString(path, content, StandardCharsets.UTF_8); } catch (IOException e) { log.warn("Failed to write REFLECTION.md: {}", e.getMessage()); }
    }

    // ── 内部方法 ──

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadStats(String projectRoot) {
        Path path = statsPath(projectRoot);
        if (!Files.exists(path)) return new HashMap<>();
        try { return MAPPER.readValue(path.toFile(), Map.class); } catch (IOException e) { return new HashMap<>(); }
    }

    private void saveStats(String projectRoot, Map<String, Object> stats) {
        ensureDir(projectRoot);
        try { MAPPER.writerWithDefaultPrettyPrinter().writeValue(statsPath(projectRoot).toFile(), stats); }
        catch (IOException e) { log.warn("Failed to write pattern stats: {}", e.getMessage()); }
    }

    private void rebuildMarkdown(String projectRoot, Map<String, Object> stats) {
        ensureDir(projectRoot);
        List<String> lines = new ArrayList<>(List.of(
            "# 高频任务模式", "> 飞轮数据源，自动维护", "",
            "## 模式统计",
            "| 任务类型 | 次数 | 成功率 | 平均耗时 | 最近触发 |",
            "|---------|------|--------|---------|---------|"
        ));

        List<Map.Entry<String, Object>> sorted = stats.entrySet().stream()
            .sorted((a, b) -> {
                @SuppressWarnings("unchecked") Map<String, Object> da = (Map<String, Object>) a.getValue();
                @SuppressWarnings("unchecked") Map<String, Object> db = (Map<String, Object>) b.getValue();
                return ((Number) db.getOrDefault("count", 0)).intValue() - ((Number) da.getOrDefault("count", 0)).intValue();
            }).toList();

        for (var e : sorted) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) e.getValue();
            int count = ((Number) d.getOrDefault("count", 0)).intValue();
            int success = ((Number) d.getOrDefault("success", 0)).intValue();
            String rate = count > 0 ? (success * 100 / count) + "%" : "0%";
            double avgElapsed = count > 0 ? ((Number) d.getOrDefault("total_elapsed", 0.0)).doubleValue() / count : 0;
            lines.add(String.format("| %s | %d | %s | %.1fs | %s |", e.getKey(), count, rate, avgElapsed, d.getOrDefault("last_trigger", "N/A")));
        }

        lines.add(""); lines.add("## 候选专家触发");
        for (var e : sorted) {
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) e.getValue();
            int count = ((Number) d.getOrDefault("count", 0)).intValue();
            int success = ((Number) d.getOrDefault("success", 0)).intValue();
            if (count >= 5 && success == count) {
                double avg = ((Number) d.getOrDefault("total_elapsed", 0.0)).doubleValue() / count;
                lines.add("- " + e.getKey() + ": " + count + "次全部成功，平均" + String.format("%.1f", avg) + "s");
            }
        }

        try { Files.writeString(mdPath(projectRoot), String.join("\n", lines) + "\n", StandardCharsets.UTF_8); }
        catch (IOException e) { log.warn("Failed to write PATTERN.md: {}", e.getMessage()); }
    }

    private String trimReflectionSections(String content, int maxEntries) {
        String[] lines = content.split("\n");
        List<String> result = new ArrayList<>();
        int currentEntries = 0;
        for (String line : lines) {
            if (line.startsWith("## ")) { currentEntries = 0; result.add(line); }
            else if (line.startsWith("- [")) { currentEntries++; if (currentEntries <= maxEntries) result.add(line); }
            else result.add(line);
        }
        return String.join("\n", result);
    }

    private Path kaiwuDir(String projectRoot) { return Path.of(projectRoot, ".kaiwu"); }
    private Path mdPath(String projectRoot) { return kaiwuDir(projectRoot).resolve("PATTERN.md"); }
    private Path statsPath(String projectRoot) { return kaiwuDir(projectRoot).resolve(STATS_FILENAME); }
    private Path reflectionPath(String projectRoot) { return kaiwuDir(projectRoot).resolve("REFLECTION.md"); }
    private void ensureDir(String projectRoot) {
        try { Files.createDirectories(kaiwuDir(projectRoot)); } catch (IOException e) { /* ignore */ }
    }

    /**
     * 显示PATTERN.md内容
     * @origin Python: memory.pattern_md.show(project_root) -> str
     */
    public String show(String projectRoot) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) return "PATTERN.md not found. Will be created after first task.";
        try { return Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { return "Failed to read PATTERN.md: " + e.getMessage(); }
    }
}
