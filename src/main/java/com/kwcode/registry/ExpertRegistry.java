package com.kwcode.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 专家注册表 - 内存+磁盘的专家管理
 * <p>
 * 从builtin_experts/和~/.kaiwu/experts/加载专家定义。
 * 关键词匹配是纯字符串操作，不调LLM——毫秒级响应。
 * 支持生命周期过滤（new/mature/declining/archived）。
 * </p>
 * @origin Python: registry.expert_registry.ExpertRegistry
 */
public class ExpertRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExpertRegistry.class);

    /** 生命周期 → 置信度惩罚值（越高越难触发） */
    private static final Map<String, Double> LIFECYCLE_PENALTY = Map.of(
        "new", 0.1,
        "mature", 0.0,
        "declining", 0.2
        // archived: null → skip entirely
    );

    /** 专家名 → 专家定义 */
    private final Map<String, Map<String, Object>> experts = new LinkedHashMap<>();

    /**
     * 匹配用户输入到注册的专家
     * <p>
     * 置信度使用饱和公式：1 - 0.5^matched_count。
     * 1个匹配=0.50，2个=0.75，3个=0.875，4+=0.93+。
     * 多个匹配取最高置信度，相同取最高成功率。
     * </p>
     * @origin Python: registry.expert_registry.ExpertRegistry.match(user_input) -> Optional[dict]
     * @param userInput 用户输入文本
     * @return 匹配结果，包含name/confidence/expert，未匹配返回null
     */
    public MatchResult match(String userInput) {
        String inputLower = userInput.toLowerCase();
        MatchResult best = null;

        for (var entry : experts.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> expert = entry.getValue();

            String lifecycle = (String) expert.getOrDefault("lifecycle", "new");
            if ("archived".equals(lifecycle)) continue;
            double penalty = LIFECYCLE_PENALTY.getOrDefault(lifecycle, 0.0);

            @SuppressWarnings("unchecked")
            List<String> keywords = (List<String>) expert.get("trigger_keywords");
            if (keywords == null) continue;

            long matched = keywords.stream()
                .filter(kw -> inputLower.contains(kw.toLowerCase()))
                .count();
            if (matched == 0) continue;

            // 饱和置信度：1 - 0.5^matched
            double confidence = 1.0 - Math.pow(0.5, matched);
            double threshold = Math.min(1.0,
                ((Number) expert.getOrDefault("trigger_min_confidence", 0.75)).doubleValue() + penalty);

            if (confidence < threshold) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> perf = (Map<String, Object>) expert.getOrDefault("performance", Map.of());
            double successRate = ((Number) perf.getOrDefault("success_rate", 0.0)).doubleValue();

            if (best == null || confidence > best.confidence() ||
                (confidence == best.confidence() && successRate > best.successRate())) {
                best = new MatchResult(name, confidence, expert, successRate);
            }
        }

        return best;
    }

    /**
     * 注册一个新专家
     * @origin Python: registry.expert_registry.ExpertRegistry.register(expert_def)
     */
    public void register(Map<String, Object> expertDef) {
        String name = (String) expertDef.get("name");
        if (name == null) throw new IllegalArgumentException("Expert definition must have 'name'");
        experts.put(name, expertDef);
    }

    /**
     * 更新专家性能统计
     * @origin Python: registry.expert_registry.ExpertRegistry.update_stats(expert_name, success, latency)
     */
    @SuppressWarnings("unchecked")
    public void updateStats(String expertName, boolean success, double latency) {
        Map<String, Object> expert = experts.get(expertName);
        if (expert == null) return;

        Map<String, Object> perf = (Map<String, Object>) expert.computeIfAbsent("performance", k -> {
            Map<String, Object> p = new HashMap<>();
            p.put("success_rate", 0.0);
            p.put("avg_latency_s", 0.0);
            p.put("task_count", 0);
            return p;
        });

        int count = ((Number) perf.getOrDefault("task_count", 0)).intValue();
        double sr = ((Number) perf.getOrDefault("success_rate", 0.0)).doubleValue();
        double al = ((Number) perf.getOrDefault("avg_latency_s", 0.0)).doubleValue();

        perf.put("success_rate", (sr * count + (success ? 1.0 : 0.0)) / (count + 1));
        perf.put("avg_latency_s", (al * count + latency) / (count + 1));
        perf.put("task_count", count + 1);
    }

    /**
     * 列出所有注册的专家
     * @origin Python: registry.expert_registry.ExpertRegistry.list_experts(expert_type) -> list[dict]
     */
    public List<Map<String, Object>> listExperts(String expertType) {
        return experts.values().stream()
            .filter(e -> expertType == null || expertType.equals(e.get("type")))
            .toList();
    }

    /**
     * 按名称获取专家
     * @origin Python: registry.expert_registry.ExpertRegistry.get(name) -> Optional[dict]
     */
    public Map<String, Object> get(String name) { return experts.get(name); }

    /**
     * 获取专家的Level 2指令（渐进式披露）
     * @origin Python: registry.expert_registry.ExpertRegistry.get_instructions(name) -> str
     */
    public String getInstructions(String name) {
        Map<String, Object> expert = experts.get(name);
        if (expert == null) return "";
        if ("skill".equals(expert.get("_format"))) {
            return (String) expert.getOrDefault("instructions", "");
        }
        return (String) expert.getOrDefault("system_prompt", "");
    }

    /**
     * 获取专家的Level 3脚本（渐进式披露）
     * @origin Python: registry.expert_registry.ExpertRegistry.get_scripts(name) -> list[dict]
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getScripts(String name) {
        Map<String, Object> expert = experts.get(name);
        if (expert == null) return List.of();
        return (List<Map<String, Object>>) expert.getOrDefault("scripts", List.of());
    }

    /**
     * 移除专家
     */
    public Map<String, Object> remove(String name) { return experts.remove(name); }

    public int size() { return experts.size(); }

    /**
     * 匹配结果
     */
    public record MatchResult(String name, double confidence, Map<String, Object> expert, double successRate) {}
}
