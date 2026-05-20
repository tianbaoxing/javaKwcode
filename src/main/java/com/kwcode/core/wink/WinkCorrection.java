package com.kwcode.core.wink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Wink纠正建议 - 根据偏离模式生成具体的纠正提示
 * <p>
 * WinkCorrection是WinkMonitor的补充组件：
 * - WinkMonitor: 检测偏离
 * - WinkCorrection: 生成纠正建议
 * </p>
 * <p>
 * 纠正策略：
 * 1. scope_creep → 缩小修改范围
 * 2. repetitive_fix → 换思路
 * 3. patch_miss → 先读文件再改
 * 4. progress_stall → 回退到最佳快照
 * </p>
 * @origin Python: core.wink.WinkCorrection
 */
public class WinkCorrection {

    private static final Logger log = LoggerFactory.getLogger(WinkCorrection.class);

    private static final Map<String, CorrectionStrategy> STRATEGIES = new LinkedHashMap<>();

    static {
        STRATEGIES.put("scope_creep", new CorrectionStrategy(
            "scope_creep",
            "任务范围过大，只修改用户明确指定的文件，不要扩散到其他文件",
            List.of(
                "只修改用户明确提到的文件",
                "不要修改测试文件以外的文件",
                "不要添加用户没有要求的新功能"
            )
        ));

        STRATEGIES.put("repetitive_fix", new CorrectionStrategy(
            "repetitive_fix",
            "你已经用同样的方式修改了多次，换一个完全不同的思路",
            List.of(
                "换一个完全不同的修改方式",
                "检查是否遗漏了import或依赖",
                "考虑是否需要修改调用方而不是被调用方"
            )
        ));

        STRATEGIES.put("patch_miss", new CorrectionStrategy(
            "patch_miss",
            "补丁应用失败，必须先读取文件最新内容",
            List.of(
                "先read_file读取文件当前内容",
                "使用文件中的实际代码作为original",
                "不要使用缓存的代码片段"
            )
        ));

        STRATEGIES.put("progress_stall", new CorrectionStrategy(
            "progress_stall",
            "重试未提升通过率，考虑回退到最佳快照",
            List.of(
                "回退到之前通过最多测试的版本",
                "从那个版本重新开始修改",
                "每次只改一个最小改动"
            )
        ));

        STRATEGIES.put("specification_drift", new CorrectionStrategy(
            "specification_drift",
            "偏离了用户原始意图，重新审视用户需求",
            List.of(
                "重新阅读用户的原始需求",
                "只做用户要求的修改",
                "不要添加额外的优化或重构"
            )
        ));
    }

    /**
     * 根据偏离模式生成纠正建议
     * <p>
     * 返回纠正提示文本，可直接注入到Generator的prompt中。
     * </p>
     * @origin Python: core.wink.WinkCorrection.correct(drift_type, ctx) -> str
     * @param driftType 偏离类型
     * @param context 上下文信息（可选）
     * @return 纠正建议文本
     */
    public String correct(String driftType, Map<String, Object> context) {
        CorrectionStrategy strategy = STRATEGIES.get(driftType);

        if (strategy == null) {
            log.debug("[wink_correction] Unknown drift type: {}", driftType);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【Wink纠正】").append(strategy.summary).append("\n\n");
        sb.append("建议操作：\n");

        List<String> hints = strategy.hints;
        if (context != null && context.containsKey("streak_count")) {
            int streak = ((Number) context.get("streak_count")).intValue();
            if (streak >= 3) {
                hints = new ArrayList<>(hints);
                hints.add("连续" + streak + "次同样错误，强烈建议完全改变修改策略");
            }
        }

        for (int i = 0; i < hints.size(); i++) {
            sb.append((i + 1)).append(". ").append(hints.get(i)).append("\n");
        }

        log.info("[wink_correction] Generated correction for: {}", driftType);
        return sb.toString();
    }

    /**
     * 简化版纠正（无上下文）
     */
    public String correct(String driftType) {
        return correct(driftType, null);
    }

    /**
     * 获取所有支持的偏离类型
     */
    public Set<String> getSupportedDriftTypes() {
        return Collections.unmodifiableSet(STRATEGIES.keySet());
    }

    /**
     * 纠正策略定义
     */
    private static class CorrectionStrategy {
        final String driftType;
        final String summary;
        final List<String> hints;

        CorrectionStrategy(String driftType, String summary, List<String> hints) {
            this.driftType = driftType;
            this.summary = summary;
            this.hints = hints;
        }
    }
}
