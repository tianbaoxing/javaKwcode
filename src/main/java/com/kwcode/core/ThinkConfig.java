package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Think模式配置 - 推理模型的思考预算管理
 * <p>
 * 对齐Python原始实现：按 expert_type × difficulty 二维表配置think预算。
 * 推理模型（如DeepSeek-R1、O1）支持think模式，
 * 可以控制思考token预算。非推理模型忽略此配置。
 * </p>
 * <p>
 * 基于AdaptThink论文：在GSM8K/MATH500/AIME2024上，
 * 自适应选择Think/NoThink模式平均减少50%响应长度，同时提升准确率。
 * </p>
 * @origin Python: core.think_config
 */
public class ThinkConfig {

    private static final Logger log = LoggerFactory.getLogger(ThinkConfig.class);

    private boolean enabled;
    private int budgetTokens;
    private String mode;

    public static final String MODE_AUTO = "auto";
    public static final String MODE_ALWAYS = "always";
    public static final String MODE_NEVER = "never";

    /**
     * (expert_type, difficulty) → {think: bool, budget: int}
     * 对齐Python原始_THINK_TABLE二维配置
     * @origin Python: core.think_config._THINK_TABLE
     */
    private static final Map<String, Map<String, ThinkEntry>> THINK_TABLE = new LinkedHashMap<>();

    private static final ThinkEntry DEFAULT_ENTRY = new ThinkEntry(false, 0);

    static {
        Map<String, ThinkEntry> chat = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(false, 0),
            "hard", new ThinkEntry(false, 0)
        );
        THINK_TABLE.put("chat", chat);

        Map<String, ThinkEntry> codegen = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(true, 512),
            "hard", new ThinkEntry(true, 2048)
        );
        THINK_TABLE.put("codegen", codegen);

        Map<String, ThinkEntry> locatorRepair = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(true, 512),
            "hard", new ThinkEntry(true, 2048)
        );
        THINK_TABLE.put("locator_repair", locatorRepair);

        Map<String, ThinkEntry> refactor = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(true, 1024),
            "hard", new ThinkEntry(true, 4096)
        );
        THINK_TABLE.put("refactor", refactor);

        Map<String, ThinkEntry> doc = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(false, 0),
            "hard", new ThinkEntry(true, 1024)
        );
        THINK_TABLE.put("doc", doc);

        Map<String, ThinkEntry> office = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(false, 0),
            "hard", new ThinkEntry(true, 1024)
        );
        THINK_TABLE.put("office", office);

        Map<String, ThinkEntry> vision = Map.of(
            "easy", new ThinkEntry(false, 0),
            "medium", new ThinkEntry(true, 512),
            "hard", new ThinkEntry(true, 2048)
        );
        THINK_TABLE.put("vision", vision);
    }

    public ThinkConfig() {
        this.enabled = false;
        this.budgetTokens = 0;
        this.mode = MODE_AUTO;
    }

    public ThinkConfig(boolean enabled, int budgetTokens, String mode) {
        this.enabled = enabled;
        this.budgetTokens = budgetTokens;
        this.mode = mode != null ? mode : MODE_AUTO;
    }

    /**
     * 根据任务类型和难度返回think模式配置
     * <p>
     * 对齐Python原始实现：按 expert_type × difficulty 二维表查找，
     * 非推理模型始终关闭think。
     * </p>
     * @origin Python: core.think_config.get_think_config(expert_type, difficulty) -> dict
     * @param expertType 专家类型
     * @param difficulty 任务难度
     * @return ThinkConfig实例
     */
    public static ThinkConfig getThinkConfig(String expertType, String difficulty) {
        Map<String, ThinkEntry> expertTable = THINK_TABLE.getOrDefault(expertType, Map.of());
        ThinkEntry entry = expertTable.getOrDefault(difficulty, DEFAULT_ENTRY);
        log.debug("[think_config] getThinkConfig: expertType={} difficulty={} → think={} budget={}", expertType, difficulty, entry.think, entry.budget);
        return new ThinkConfig(entry.think, entry.budget, MODE_AUTO);
    }

    /**
     * 根据模型和难度自动配置think模式
     * <p>
     * 规则：
     * - 非推理模型：始终关闭
     * - easy任务：关闭（节省token）
     * - medium/hard + 推理模型：开启，按expert_type×difficulty分配预算
     * </p>
     * @origin Python: core.think_config.get_think_config(expert_type, difficulty)
     * @param modelName 模型名称
     * @param difficulty 任务难度
     * @return 配置后的ThinkConfig
     */
    public static ThinkConfig autoConfigure(String modelName, String difficulty) {
        return autoConfigure(modelName, "locator_repair", difficulty);
    }

    /**
     * 根据模型、专家类型和难度自动配置think模式
     * @origin Python: core.think_config.get_think_config(expert_type, difficulty)
     * @param modelName 模型名称
     * @param expertType 专家类型
     * @param difficulty 任务难度
     * @return 配置后的ThinkConfig
     */
    public static ThinkConfig autoConfigure(String modelName, String expertType, String difficulty) {
        boolean isReasoning = ModelCapability.isReasoningModel(modelName);

        if (!isReasoning) {
            log.info("[think_config] autoConfigure: model={} isReasoning=false → MODE_NEVER (expertType={} difficulty={})", modelName, expertType, difficulty);
            return new ThinkConfig(false, 0, MODE_NEVER);
        }

        ThinkConfig tc = getThinkConfig(expertType, difficulty);
        log.info("[think_config] autoConfigure: model={} isReasoning=true expertType={} difficulty={} → think={} budget={} mode={}",
                modelName, expertType, difficulty, tc.isEnabled(), tc.getBudgetTokens(), tc.getMode());
        return tc;
    }

    /**
     * 根据think配置调整max_tokens
     * <p>
     * - 非reasoning模型：不变
     * - reasoning模型 + think=False：保持原值
     * - reasoning模型 + think=True：max_tokens + budget
     * </p>
     * @origin Python: core.think_config.apply_think_to_max_tokens(max_tokens, think_config, is_reasoning_model)
     * @param maxTokens 原始max_tokens
     * @param isReasoningModel 是否为推理模型
     * @return 调整后的max_tokens
     */
    public int applyThinkToMaxTokens(int maxTokens, boolean isReasoningModel) {
        if (!isReasoningModel) {
            log.debug("[think_config] applyThinkToMaxTokens: non-reasoning, unchanged maxTokens={}", maxTokens);
            return maxTokens;
        }
        if (!enabled) {
            log.debug("[think_config] applyThinkToMaxTokens: reasoning but think=false, unchanged maxTokens={}", maxTokens);
            return maxTokens;
        }
        int adjusted = maxTokens + budgetTokens;
        log.info("[think_config] applyThinkToMaxTokens: reasoning+think=true, maxTokens={} + budget={} → {}", maxTokens, budgetTokens, adjusted);
        return adjusted;
    }

    /**
     * 从TaskContext的thinkConfig字段解析
     * @origin Python: core.think_config.ThinkConfig.from_context(ctx_dict) -> ThinkConfig
     * @param configMap 配置Map
     * @return ThinkConfig实例
     */
    @SuppressWarnings("unchecked")
    public static ThinkConfig fromMap(Map<String, Object> configMap) {
        if (configMap == null) return new ThinkConfig();

        boolean enabled = Boolean.TRUE.equals(configMap.getOrDefault("think", false));
        int budget = 0;
        if (configMap.get("budget") instanceof Number n) {
            budget = n.intValue();
        }
        String mode = (String) configMap.getOrDefault("mode", MODE_AUTO);

        return new ThinkConfig(enabled, budget, mode);
    }

    /**
     * 转换为Map（存入TaskContext）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("think", enabled);
        map.put("budget", budgetTokens);
        map.put("mode", mode);
        return map;
    }

    /**
     * 获取LLM调用时使用的think参数
     */
    public boolean shouldThink() {
        return switch (mode) {
            case MODE_ALWAYS -> true;
            case MODE_NEVER -> false;
            default -> enabled;
        };
    }

    public boolean isEnabled() { return enabled; }
    public int getBudgetTokens() { return budgetTokens; }
    public String getMode() { return mode; }

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setBudgetTokens(int budgetTokens) { this.budgetTokens = budgetTokens; }
    public void setMode(String mode) { this.mode = mode; }

    /**
     * Think配置条目
     * @origin Python: core.think_config._THINK_TABLE value
     */
    private record ThinkEntry(boolean think, int budget) {}
}
