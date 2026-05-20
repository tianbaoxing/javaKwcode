package com.kwcode.flywheel;

import com.kwcode.registry.ExpertRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 专家生命周期状态机：new → mature → declining → archived
 * 实现规范 §4.4 生命周期转换
 * @origin Python: flywheel/lifecycle_manager.py
 */
public class LifecycleManager {

    private static final Logger log = LoggerFactory.getLogger(LifecycleManager.class);

    /** 成熟态最低任务数 */
    private static final int MATURE_MIN_TASKS = 5;
    /** 成熟态最低成功率 */
    private static final double MATURE_MIN_SUCCESS_RATE = 0.75;
    /** 衰退态成功率阈值 */
    private static final double DECLINING_SUCCESS_RATE = 0.50;
    /** 衰退态未使用天数 */
    private static final int DECLINING_UNUSED_DAYS = 30;

    /** 关键词重叠合并阈值 */
    private static final double MERGE_OVERLAP_THRESHOLD = 0.6;

    /** 专家注册表 */
    private final ExpertRegistry registry;

    /**
     * 构造函数
     * @param registry 专家注册表
     */
    public LifecycleManager(ExpertRegistry registry) {
        this.registry = registry;
    }

    /**
     * 评估并可能转换专家的生命周期状态
     * 状态机规则：
     * - new: success_rate>=75% AND task_count>=5 → mature
     * - mature: success_rate<50% → declining; 30天未用 → declining
     * - declining→archived 和 archived→new 是手动操作
     * @param expertName 专家名称
     * @return 新的生命周期状态（如果发生变化），否则null
     */
    @SuppressWarnings("unchecked")
    public String evaluate(String expertName) {
        Map<String, Object> expert = registry.get(expertName);
        if (expert == null) return null;

        String lifecycle = String.valueOf(expert.getOrDefault("lifecycle", "new"));
        Map<String, Object> perf = (Map<String, Object>) expert.getOrDefault("performance", Map.of());
        double sr = ((Number) perf.getOrDefault("success_rate", 0.0)).doubleValue();
        int count = ((Number) perf.getOrDefault("task_count", 0)).intValue();

        String newState = null;

        switch (lifecycle) {
            case "new":
                // 成熟条件：成功率高且任务数足够
                if (count >= MATURE_MIN_TASKS && sr >= MATURE_MIN_SUCCESS_RATE) {
                    newState = "mature";
                }
                break;
            case "mature":
                // 衰退条件：成功率过低或长期未使用
                if (count >= MATURE_MIN_TASKS && sr < DECLINING_SUCCESS_RATE) {
                    newState = "declining";
                } else if (daysSinceLastUse(expert) >= DECLINING_UNUSED_DAYS) {
                    newState = "declining";
                }
                break;
            // declining → archived 和 archived → new 需要手动操作
            default:
                break;
        }

        if (newState != null && !newState.equals(lifecycle)) {
            expert.put("lifecycle", newState);
            log.info("[lifecycle] 专家 {} 生命周期: {} → {} (sr={:.0%}, count={})",
                expertName, lifecycle, newState, sr * 100, count);
            return newState;
        }

        return null;
    }

    /**
     * 查找关键词重叠超过60%的专家对（合并候选）
     * @return 合并候选对列表，每对包含name_a和name_b
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> checkMergeCandidates() {
        List<Map<String, Object>> experts = registry.listExperts("");
        List<Map<String, String>> pairs = new ArrayList<>();

        for (int i = 0; i < experts.size(); i++) {
            Map<String, Object> a = experts.get(i);
            Set<String> kwA = new HashSet<>();
            for (String kw : (List<String>) a.getOrDefault("trigger_keywords", List.of())) {
                kwA.add(kw.toLowerCase());
            }
            if (kwA.isEmpty()) continue;

            for (int j = i + 1; j < experts.size(); j++) {
                Map<String, Object> b = experts.get(j);
                Set<String> kwB = new HashSet<>();
                for (String kw : (List<String>) b.getOrDefault("trigger_keywords", List.of())) {
                    kwB.add(kw.toLowerCase());
                }
                if (kwB.isEmpty()) continue;

                // 计算Jaccard系数
                Set<String> intersection = new HashSet<>(kwA);
                intersection.retainAll(kwB);
                Set<String> union = new HashSet<>(kwA);
                union.addAll(kwB);

                double overlap = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
                if (overlap > MERGE_OVERLAP_THRESHOLD) {
                    Map<String, String> pair = new LinkedHashMap<>();
                    pair.put("name_a", String.valueOf(a.get("name")));
                    pair.put("name_b", String.valueOf(b.get("name")));
                    pairs.add(pair);
                }
            }
        }

        if (!pairs.isEmpty()) {
            log.info("[lifecycle] 发现 {} 对合并候选", pairs.size());
        }
        return pairs;
    }

    /**
     * 计算专家距上次使用的天数
     * @param expert 专家定义
     * @return 天数，从未使用返回Double.POSITIVE_INFINITY
     */
    public static double daysSinceLastUse(Map<String, Object> expert) {
        String lastUsed = (String) expert.get("last_used");
        if (lastUsed == null || lastUsed.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            OffsetDateTime lastDt = OffsetDateTime.parse(lastUsed);
            double deltaSeconds = Instant.from(OffsetDateTime.now(ZoneOffset.UTC))
                .getEpochSecond() - Instant.from(lastDt).getEpochSecond();
            return deltaSeconds / 86400.0;
        } catch (DateTimeException e) {
            return Double.POSITIVE_INFINITY;
        }
    }
}
