package com.kwcode.core.execution;

import java.util.*;

/**
 * 执行状态追踪器 - 追踪每次修改后测试结果的变化
 * <p>
 * 不是reset重试，是Git bisect式定位：知道哪步引入了问题。
 * 代码状态的回滚完全交给Checkpoint，本类只追踪测试状态。
 * </p>
 * @origin Python: core.execution_state.ExecutionStateTracker
 */
public class ExecutionTracker {

    private final List<TestDelta> history = new ArrayList<>();
    private Set<String> baselineFailing = new HashSet<>();

    /**
     * 测试状态变化记录
     * @origin Python: core.execution_state.TestDelta
     */
    public record TestDelta(
        int attempt,
        List<String> newlyPassing,
        List<String> newlyFailing,
        List<String> unchangedFailing,
        String gapType
    ) {}

    /**
     * 重置tracker状态（新任务开始时调用）
     * @origin Python: core.execution_state.ExecutionStateTracker.reset()
     */
    public void reset() {
        history.clear();
        baselineFailing = new HashSet<>();
    }

    /**
     * 记录基线（任务开始前的失败测试）
     * @origin Python: core.execution_state.ExecutionStateTracker.set_baseline(initial_failing: list[str])
     * @param initialFailing 初始失败的测试列表
     */
    public void setBaseline(List<String> initialFailing) {
        this.baselineFailing = new HashSet<>(initialFailing);
    }

    /**
     * 每次verifier运行后记录状态变化
     * <p>
     * 计算本次相比基线的变化：哪些测试新通过、哪些新失败、哪些一直失败。
     * </p>
     * @origin Python: core.execution_state.ExecutionStateTracker.record(attempt, current_failing, current_passing, gap_type)
     * @param attempt 当前尝试次数
     * @param currentFailing 当前失败的测试列表
     * @param currentPassing 当前通过的测试列表
     * @param gapType 当前Gap类型
     */
    public void record(int attempt, List<String> currentFailing,
                       List<String> currentPassing, String gapType) {
        Set<String> failingSet = new HashSet<>(currentFailing);

        List<String> newlyPassing = currentPassing.stream()
            .filter(baselineFailing::contains)
            .toList();

        List<String> newlyFailing = currentFailing.stream()
            .filter(t -> !baselineFailing.contains(t))
            .toList();

        List<String> unchangedFailing = currentFailing.stream()
            .filter(baselineFailing::contains)
            .toList();

        history.add(new TestDelta(attempt, newlyPassing, newlyFailing, unchangedFailing, gapType));
    }

    /**
     * 最近一次修改是否引入了回归
     * @origin Python: core.execution_state.ExecutionStateTracker.has_regression() -> bool
     * @return true表示存在回归
     */
    public boolean hasRegression() {
        if (history.isEmpty()) return false;
        return !history.get(history.size() - 1).newlyFailing().isEmpty();
    }

    /**
     * 找历史上最好的中间状态
     * <p>
     * 通过了最多新测试且没有引入回归的状态，可以从此继续。
     * </p>
     * @origin Python: core.execution_state.ExecutionStateTracker.get_best_partial_state() -> Optional[TestDelta]
     * @return 最佳中间状态，不存在返回null
     */
    public TestDelta getBestPartialState() {
        return history.stream()
            .filter(d -> d.newlyFailing().isEmpty())
            .max(Comparator.comparingInt(d -> d.newlyPassing().size()))
            .orElse(null);
    }

    /**
     * 找到引入回归的attempt编号
     * @origin Python: core.execution_state.ExecutionStateTracker.get_regression_point() -> Optional[int]
     * @return 回归点attempt编号，不存在返回-1
     */
    public int getRegressionPoint() {
        return history.stream()
            .filter(d -> !d.newlyFailing().isEmpty())
            .mapToInt(TestDelta::attempt)
            .findFirst()
            .orElse(-1);
    }

    /**
     * 获取当前进展摘要
     * @origin Python: core.execution_state.ExecutionStateTracker.get_progress_summary() -> dict
     * @return 进展摘要Map
     */
    public Map<String, Object> getProgressSummary() {
        Map<String, Object> summary = new HashMap<>();
        summary.put("total_attempts", history.size());

        TestDelta best = getBestPartialState();
        summary.put("best_passing", best != null ? best.newlyPassing().size() : 0);

        long regressions = history.stream()
            .filter(d -> !d.newlyFailing().isEmpty())
            .count();
        summary.put("regressions", regressions);
        summary.put("baseline_failing_count", baselineFailing.size());

        return summary;
    }
}
