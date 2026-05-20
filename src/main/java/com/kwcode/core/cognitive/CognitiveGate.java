package com.kwcode.core.cognitive;

import java.util.ArrayList;
import java.util.List;

/**
 * 认知门控 - 基于patch行数变化趋势判断是否应停止重试
 * <p>
 * 检测Generator输出是否在边际收益递减：
 * - patch行数持续递减 → 模型已无有效修复方向 → 停止重试
 * - 替代固定计数熔断，更精确地判断何时该停
 * </p>
 * <p>
 * 理论来源：CC Diminishing Returns Detection、SpecEyes认知门控
 * </p>
 * @origin Python: core.cognitive_gate.CognitiveGate
 */
public class CognitiveGate {

    private final int window;
    private final double threshold;
    private final List<Integer> patchLines = new ArrayList<>();

    /**
     * 构造认知门控
     * @origin Python: core.cognitive_gate.CognitiveGate.__init__(window, threshold)
     * @param window 观察窗口大小（需要多少次记录才开始判断）
     * @param threshold 递减阈值（最后一次 <= 第一次 * threshold 时触发）
     */
    public CognitiveGate(int window, double threshold) {
        this.window = window;
        this.threshold = threshold;
    }

    public CognitiveGate() {
        this(3, 0.3);
    }

    /**
     * 记录一次Generator输出的patch总行数
     * @origin Python: core.cognitive_gate.CognitiveGate.record(patches: list[dict]) -> None
     * @param totalPatchLines 本次patch的总行数
     */
    public void record(int totalPatchLines) {
        patchLines.add(totalPatchLines);
    }

    /**
     * 判断是否应停止重试
     * <p>
     * 三种触发条件：
     * 1. patch行数持续递减且降幅超过阈值 → 边际收益递减
     * 2. 最后一次patch行数极小（≤3行）→ 模型已无从下手
     * 3. 连续输出相同行数 → 原地打转
     * </p>
     * @origin Python: core.cognitive_gate.CognitiveGate.should_stop() -> tuple[bool, str]
     * @return StopDecision，包含是否停止和原因
     */
    public StopDecision shouldStop() {
        if (patchLines.size() < window) {
            return new StopDecision(false, "");
        }

        List<Integer> recent = patchLines.subList(patchLines.size() - window, patchLines.size());

        // 持续递减且降幅超过阈值
        boolean allDecreasing = true;
        for (int i = 0; i < recent.size() - 1; i++) {
            if (recent.get(i) <= recent.get(i + 1)) {
                allDecreasing = false;
                break;
            }
        }
        if (allDecreasing && recent.get(recent.size() - 1) <= recent.get(0) * threshold) {
            return new StopDecision(true,
                "patch行数持续递减 " + recent + "，边际收益递减");
        }

        // 最后一次极小
        if (recent.get(recent.size() - 1) <= 3 && patchLines.size() >= 2) {
            return new StopDecision(true,
                "patch行数降至 " + recent.get(recent.size() - 1) + " 行，停止重试");
        }

        // 连续输出相同行数
        boolean allSame = true;
        for (int i = 1; i < recent.size(); i++) {
            if (!recent.get(i).equals(recent.get(0))) { allSame = false; break; }
        }
        if (allSame && patchLines.size() >= window) {
            return new StopDecision(true,
                "patch行数连续 " + window + " 次相同(" + recent.get(recent.size() - 1) + "行)，原地打转");
        }

        return new StopDecision(false, "");
    }

    /**
     * 重置状态（新任务开始时调用）
     * @origin Python: core.cognitive_gate.CognitiveGate.reset()
     */
    public void reset() {
        patchLines.clear();
    }

    /**
     * 返回patch行数历史记录
     * @origin Python: core.cognitive_gate.CognitiveGate.history -> list[int]
     */
    public List<Integer> getHistory() {
        return List.copyOf(patchLines);
    }

    /**
     * 停止决策结果
     */
    public record StopDecision(boolean shouldStop, String reason) {}
}
