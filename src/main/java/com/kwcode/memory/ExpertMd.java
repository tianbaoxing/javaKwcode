package com.kwcode.memory;

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
 * EXPERT.md记忆 - 成功的专家调用记录
 * <p>
 * Spec §7.2: 存储在 .kaiwu/EXPERT.md。
 * 只记录通过verifier的成功调用（doc/office等无verifier的也算成功）。
 * 最多保留100条记录。
 * </p>
 * @origin Python: memory.expert_md
 */
public class ExpertMd {

    private static final Logger log = LoggerFactory.getLogger(ExpertMd.class);
    private static final int MAX_RECORDS = 100;

    private static final String TEMPLATE = """
# 专家调用记录
> 自动维护

| 时间 | 专家 | 任务类型 | 涉及文件 | 流水线 | 耗时 |
|------|------|---------|---------|--------|------|
""";

    /**
     * 加载最近的专家记录（最多20条）
     * @origin Python: memory.expert_md.load(project_root) -> str
     */
    public String load(String projectRoot) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) return "";
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            List<String> recordLines = Arrays.stream(content.split("\n"))
                .filter(l -> l.startsWith("|") && !l.contains("时间") && !l.contains("---"))
                .toList();
            if (recordLines.isEmpty()) return "";
            if (recordLines.size() > 20) recordLines = recordLines.subList(recordLines.size() - 20, recordLines.size());
            return "最近专家调用：\n" + String.join("\n", recordLines) + "\n";
        } catch (IOException e) {
            log.warn("Failed to read EXPERT.md: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 追加一条成功的专家调用记录
     * @origin Python: memory.expert_md.save(project_root, ctx, elapsed)
     */
    public void save(String projectRoot, TaskContext ctx, double elapsed) {
        if (ctx.verifierOutput == null || !ctx.verifierOutput.passed()) {
            String expertType = (String) ctx.gateResult.getOrDefault("expert_type", "unknown");
            if (!List.of("doc", "office").contains(expertType)) return;
        }

        ensureDir(projectRoot);
        Path path = mdPath(projectRoot);

        if (!Files.exists(path)) {
            try { Files.writeString(path, TEMPLATE, StandardCharsets.UTF_8); } catch (IOException e) { return; }
        }

        String content;
        try { content = Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { return; }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String expertType = (String) ctx.gateResult.getOrDefault("expert_type", "unknown");

        List<String> files = new ArrayList<>();
        if (ctx.locatorOutput != null) files = ctx.locatorOutput.relevantFiles();
        else if (ctx.generatorOutput != null) files = ctx.generatorOutput.patches().stream().map(TaskContext.Patch::file).toList();
        String filesStr = files.isEmpty() ? "N/A" : String.join(", ", files.subList(0, Math.min(3, files.size())));

        // 简化：用expert_type作为流水线名称
        String seqStr = expertType;
        String elapsedStr = elapsed > 0 ? String.format("%.1fs", elapsed) : "N/A";
        String newRecord = "| " + now + " | " + seqStr + " | " + expertType + " | " + filesStr + " | " + seqStr + " | " + elapsedStr + " |";

        String separator = "|------|------|---------|---------|--------|------|";
        if (content.contains(separator)) {
            String[] parts = content.split(separator, 2);
            List<String> recordLines = new ArrayList<>(Arrays.stream(parts[1].strip().split("\n"))
                .filter(l -> l.startsWith("|") && !l.contains("时间") && !l.contains("---"))
                .toList());
            if (recordLines.size() >= MAX_RECORDS) recordLines = recordLines.subList(recordLines.size() - MAX_RECORDS + 1, recordLines.size());
            recordLines.add(newRecord);
            content = parts[0] + separator + "\n" + String.join("\n", recordLines) + "\n";
        } else {
            content += "\n" + newRecord + "\n";
        }

        try { Files.writeString(path, content, StandardCharsets.UTF_8); } catch (IOException e) { log.warn("Failed to write EXPERT.md: {}", e.getMessage()); }
    }

    /**
     * 显示EXPERT.md内容
     * @origin Python: memory.expert_md.show(project_root) -> str
     */
    public String show(String projectRoot) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) return "EXPERT.md not found.";
        try { return Files.readString(path, StandardCharsets.UTF_8); } catch (IOException e) { return "Failed to read EXPERT.md: " + e.getMessage(); }
    }

    private Path kaiwuDir(String projectRoot) { return Path.of(projectRoot, ".kaiwu"); }
    private Path mdPath(String projectRoot) { return kaiwuDir(projectRoot).resolve("EXPERT.md"); }
    private void ensureDir(String projectRoot) {
        try { Files.createDirectories(kaiwuDir(projectRoot)); } catch (IOException e) { /* ignore */ }
    }
}
