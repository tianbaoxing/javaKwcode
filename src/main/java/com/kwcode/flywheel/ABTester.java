package com.kwcode.flywheel;

import com.kwcode.registry.ExpertRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * AB测试器 - 三闸门专家验证系统
 * <p>
 * Gate 1: 数量检查（由PatternDetector处理：≥5次成功同类型任务）
 * Gate 2: 回测 — 回放源轨迹的任务通过新专家流水线
 * Gate 3: 生产AB测试（10个真实任务：5新 vs 5基线，新必须胜出>10%）
 * </p>
 * @origin Python: flywheel.ab_tester.ABTester
 */
public class ABTester {

    private static final Logger log = LoggerFactory.getLogger(ABTester.class);
    private static final String CANDIDATES_DIR = System.getProperty("user.home") + "/.kaiwu/candidates";

    private final ExpertRegistry registry;
    private final TrajectoryCollector collector;
    private final Map<String, CandidateInfo> candidates = new HashMap<>();

    public ABTester(ExpertRegistry registry, TrajectoryCollector collector) {
        this.registry = registry;
        this.collector = collector;
        loadCandidates();
    }

    /**
     * 提交候选专家进行Gate 2回测验证
     * <p>
     * Gate 1已由PatternDetector通过。Gate 2：
     * - 验证YAML结构
     * - 回放源轨迹任务通过新专家流水线
     * - 新专家成功率必须≥基线
     * </p>
     * @origin Python: ABTester.submit_candidate(expert_def, source_trajectories)
     */
    public void submitCandidate(Map<String, Object> expertDef, List<TrajectoryCollector.TaskTrajectory> sourceTrajectories) {
        String name = (String) expertDef.get("name");

        // Gate 2: 计算基线
        long baselineSuccesses = sourceTrajectories.stream().filter(t -> t.success).count();
        int baselineTotal = sourceTrajectories.size();
        double baselineSR = baselineTotal > 0 ? (double) baselineSuccesses / baselineTotal : 0;

        // Gate 2: 回测（Phase 3实现实际回放逻辑）
        double backtestSR = 0;
        // TODO: 实际回放

        boolean gate2Passed = backtestSR >= baselineSR;

        if (gate2Passed) {
            candidates.put(name, new CandidateInfo(name, expertDef, "ab_testing",
                new ArrayList<>(), baselineSR, backtestSR));
            log.info("[ab_tester] Gate 2 passed for {}: backtest_sr={:.0%} >= baseline_sr={:.0%}",
                name, backtestSR, baselineSR);
            saveCandidates();
        } else {
            log.info("[ab_tester] Gate 2 failed for {}: backtest_sr={:.0%} < baseline_sr={:.0%}",
                name, backtestSR, baselineSR);
        }
    }

    /**
     * 判断当前任务是否应使用候选专家（Gate 3 AB测试）
     * @origin Python: ABTester.should_use_candidate(expert_type) -> Optional[dict]
     */
    public Map<String, Object> shouldUseCandidate(String expertType) {
        for (var entry : candidates.entrySet()) {
            CandidateInfo info = entry.getValue();
            if ("ab_testing".equals(info.status) && info.abResults.size() < 10) {
                String type = (String) info.expertDef.getOrDefault("type", "");
                if (type.equals(expertType)) {
                    return info.expertDef;
                }
            }
        }
        return null;
    }

    /** 记录AB测试结果 */
    public void recordResult(String candidateName, boolean success) {
        CandidateInfo info = candidates.get(candidateName);
        if (info == null) return;
        info.abResults.add(success);

        // 达到10次测试，判断是否毕业
        if (info.abResults.size() >= 10) {
            long wins = info.abResults.stream().filter(b -> b).count();
            double winRate = (double) wins / info.abResults.size();
            if (winRate > 0.6) {
                info.status = "graduated";
                log.info("[ab_tester] {} 毕业为正式专家", candidateName);
                // 注册到ExpertRegistry
                registry.register(info.expertDef);
            } else {
                info.status = "archived";
                log.info("[ab_tester] {} AB测试未通过，归档", candidateName);
            }
            saveCandidates();
        }
    }

    private void loadCandidates() { /* TODO: Phase 3实现持久化 */ }
    private void saveCandidates() { /* TODO: Phase 3实现持久化 */ }

    /**
     * 候选专家信息
     */
    public static class CandidateInfo {
        public String name;
        public Map<String, Object> expertDef;
        public String status;
        public List<Boolean> abResults;
        public double baselineSR;
        public double backtestSR;

        public CandidateInfo(String name, Map<String, Object> expertDef, String status,
                             List<Boolean> abResults, double baselineSR, double backtestSR) {
            this.name = name;
            this.expertDef = expertDef;
            this.status = status;
            this.abResults = abResults;
            this.baselineSR = baselineSR;
            this.backtestSR = backtestSR;
        }
    }
}
