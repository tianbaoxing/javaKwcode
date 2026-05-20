package com.kwcode.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SKILL.md自动提炼器
 * 从成功轨迹中自动生成SKILL.md草稿，用户审核后采纳
 * 不收集代码内容，只分析错误类型分布和修复策略统计
 * @origin Python: flywheel/skill_drafter.py
 */
public class SkillDrafter {

    private static final Logger log = LoggerFactory.getLogger(SkillDrafter.class);

    /** 生成草稿的最低成功轨迹数 */
    private static final int MIN_TRAJECTORIES_FOR_DRAFT = 30;

    /** 草稿保存路径 */
    private static final String DRAFT_PATH = ".kaiwu/skill_draft.md";

    /** 策略统计 */
    private final StrategyStats stats;

    /** 轨迹收集器 */
    private final TrajectoryCollector collector;

    /**
     * 构造函数
     * @param stats 策略统计实例
     * @param collector 轨迹收集器实例
     */
    public SkillDrafter(StrategyStats stats, TrajectoryCollector collector) {
        this.stats = stats;
        this.collector = collector;
    }

    /**
     * 判断是否积累了足够数据生成草稿
     * @return 是否满足生成条件
     */
    public boolean shouldGenerateDraft() {
        try {
            var recent = collector.loadRecent(500);
            long successCount = recent.stream()
                .filter(t -> t.success)
                .count();
            return successCount >= MIN_TRAJECTORIES_FOR_DRAFT;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 为指定专家类型生成SKILL.md草稿
     * 基于策略统计数据生成，不包含任何代码内容
     * @param expertType 专家类型
     * @return 草稿文本，不满足条件返回null
     */
    public String generateDraft(String expertType) {
        if (!shouldGenerateDraft()) {
            return null;
        }

        Map<String, Map<String, Object>> statsSummary = buildSummaryFromStats();
        if (statsSummary == null || statsSummary.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(expertType).append(" 自动提炼草稿\n");
        sb.append("> 基于 ").append(MIN_TRAJECTORIES_FOR_DRAFT).append("+ 次成功任务自动生成\n");
        sb.append("> 请审核后决定是否采纳到正式 SKILL.md\n\n");
        sb.append("## 高效策略总结\n\n");

        for (var entry : statsSummary.entrySet()) {
            String errorType = entry.getKey();
            Map<String, Object> info = entry.getValue();
            sb.append("### ").append(errorType).append(" 错误\n");
            sb.append("- 最优策略：").append(info.getOrDefault("best_sequence", "N/A")).append("\n");
            sb.append("- 成功率：").append(info.getOrDefault("best_success_rate", "N/A")).append("\n");
            sb.append("- 累计样本：").append(info.getOrDefault("total_attempts", 0)).append(" 次\n\n");
        }

        sb.append("## 建议\n\n");
        sb.append("以上数据来自你的实际使用统计。\n");
        sb.append("如果某个策略成功率持续偏低，考虑在 SKILL.md 里添加针对性指导。\n\n");
        sb.append("---\n");
        sb.append("运行 `kwcode skill accept` 将此草稿合并到正式 SKILL.md\n");
        sb.append("运行 `kwcode skill discard` 丢弃此草稿\n");

        return sb.toString();
    }

    /**
     * 保存草稿到项目目录
     * @param content 草稿内容
     * @param projectRoot 项目根目录
     */
    public void saveDraft(String content, String projectRoot) {
        Path draftPath = Path.of(projectRoot, DRAFT_PATH);
        try {
            Files.createDirectories(draftPath.getParent());
            Files.writeString(draftPath, content, StandardCharsets.UTF_8);
            log.info("[skill_drafter] SKILL.md草稿已生成: {}", draftPath);
        } catch (IOException e) {
            log.warn("[skill_drafter] 保存草稿失败: {}", e.getMessage());
        }
    }

    /**
     * 检查是否有待审核的草稿
     * @param projectRoot 项目根目录
     * @return 是否存在草稿
     */
    public boolean draftExists(String projectRoot) {
        return Files.exists(Path.of(projectRoot, DRAFT_PATH));
    }

    /**
     * 从StrategyStats构建摘要数据
     * @return 错误类型→策略信息映射
     */
    private Map<String, Map<String, Object>> buildSummaryFromStats() {
        Map<String, StrategyStats.StrategyRecord> allStats = stats.getAllStats();
        if (allStats == null || allStats.isEmpty()) return Map.of();

        // 按errorType分组
        Map<String, java.util.List<StrategyStats.StrategyRecord>> grouped = allStats.values().stream()
            .collect(Collectors.groupingBy(r -> r.errorType));

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (var entry : grouped.entrySet()) {
            String errorType = entry.getKey();
            var records = entry.getValue();

            // 找成功率最高的策略
            StrategyStats.StrategyRecord best = records.stream()
                .max((a, b) -> Double.compare(a.successRate(), b.successRate()))
                .orElse(null);

            int totalAttempts = records.stream().mapToInt(r -> r.total).sum();

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("best_sequence", best != null ? best.strategy : "N/A");
            info.put("best_success_rate", best != null ? String.format("%.1f%%", best.successRate() * 100) : "N/A");
            info.put("total_attempts", totalAttempts);
            result.put(errorType, info);
        }
        return result;
    }
}
