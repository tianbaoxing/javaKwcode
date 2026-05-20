package com.kwcode.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 模式检测器 - 发现可自动生长为专家的重复成功模式
 * <p>
 * 实现三闸门验证系统的第一闸（spec §5.1）：
 * - 同一expert_type出现≥5次
 * - 全部成功
 * - 使用相同流水线序列
 * </p>
 * @origin Python: flywheel.pattern_detector.PatternDetector
 */
public class PatternDetector {

    private static final Logger log = LoggerFactory.getLogger(PatternDetector.class);
    private static final int MIN_PATTERN_COUNT = 5;

    private final TrajectoryCollector collector;

    public PatternDetector(TrajectoryCollector collector) {
        this.collector = collector;
    }

    /**
     * 扫描轨迹寻找专家生成候选
     * <p>
     * 触发条件（全部满足）：
     * - 同一expert_type出现≥5次
     * - 全部成功
     * - 使用相同流水线序列
     * </p>
     * @origin Python: PatternDetector.detect() -> list[dict]
     */
    public List<PatternCandidate> detect() {
        List<TrajectoryCollector.TaskTrajectory> allTrajs = collector.loadRecent(500);

        // 按expert_type分组
        Map<String, List<TrajectoryCollector.TaskTrajectory>> byType = allTrajs.stream()
            .collect(Collectors.groupingBy(t -> t.expertUsed));

        List<PatternCandidate> candidates = new ArrayList<>();
        for (var entry : byType.entrySet()) {
            String expertType = entry.getKey();
            List<TrajectoryCollector.TaskTrajectory> trajs = entry.getValue();

            // 只保留成功的
            List<TrajectoryCollector.TaskTrajectory> successful = trajs.stream()
                .filter(t -> t.success)
                .toList();

            if (successful.size() < MIN_PATTERN_COUNT) {
                // 3/5或4/5时通知进度
                if (successful.size() >= 3) {
                    log.info("[pattern_detector] {} 接近阈值：{}/{}",
                        expertType, successful.size(), MIN_PATTERN_COUNT);
                }
                continue;
            }

            // 检查所有成功轨迹是否使用相同流水线
            String pipelineKey = pipelineKey(successful.get(0).pipelineSteps);
            boolean allSamePipeline = successful.stream()
                .allMatch(t -> pipelineKey(t.pipelineSteps).equals(pipelineKey));

            if (!allSamePipeline) {
                // 按流水线子分组
                Map<String, List<TrajectoryCollector.TaskTrajectory>> subGroups = successful.stream()
                    .collect(Collectors.groupingBy(t -> pipelineKey(t.pipelineSteps)));
                for (var sub : subGroups.entrySet()) {
                    if (sub.getValue().size() >= MIN_PATTERN_COUNT) {
                        candidates.add(new PatternCandidate(
                            expertType, sub.getValue().size(), sub.getValue(),
                            sub.getValue().get(0).pipelineSteps
                        ));
                    }
                }
                continue;
            }

            // 有任何失败则跳过（所有出现必须成功）
            boolean hasFailure = trajs.stream().anyMatch(t -> !t.success);
            if (hasFailure) continue;

            candidates.add(new PatternCandidate(
                expertType, successful.size(), successful,
                successful.get(0).pipelineSteps
            ));
        }

        log.info("[pattern_detector] found {} candidates", candidates.size());
        return candidates;
    }

    private String pipelineKey(List<String> steps) {
        return steps == null ? "" : String.join("→", steps);
    }

    /**
     * 模式候选
     */
    public record PatternCandidate(
        String expertType,
        int count,
        List<TrajectoryCollector.TaskTrajectory> trajectories,
        List<String> pipeline
    ) {}
}
