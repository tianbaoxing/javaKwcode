package com.kwcode.flywheel;

import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * 轨迹收集器 - 记录任务执行轨迹到 ~/.kaiwu/trajectories/
 * <p>
 * 每个轨迹是一个JSON文件 {task_id}.json。
 * 支持按expert_type查找相似轨迹（经验回放）。
 * </p>
 * @origin Python: flywheel.trajectory_collector.TrajectoryCollector
 */
public class TrajectoryCollector {

    private static final Logger log = LoggerFactory.getLogger(TrajectoryCollector.class);
    private static final String TRAJECTORIES_DIR = System.getProperty("user.home") + "/.kaiwu/trajectories";

    private final String dir;

    public TrajectoryCollector() { this(TRAJECTORIES_DIR); }
    public TrajectoryCollector(String dir) {
        this.dir = dir;
        try { Files.createDirectories(Path.of(dir)); } catch (IOException e) { /* ignore */ }
    }

    /**
     * 任务轨迹
     */
    public static class TaskTrajectory {
        public String taskId;
        public String userInput;
        public Map<String, Object> gateResult = new HashMap<>();
        public String expertUsed;
        public List<String> pipelineSteps = new ArrayList<>();
        public List<String> filesModified = new ArrayList<>();
        public boolean success;
        public int retryCount;
        public double latencyS;
        public String modelUsed;
        public String timestamp;
        public boolean searchTriggered;
        public String projectHash;
        public List<Map<String, Object>> attempts = new ArrayList<>();
    }

    /**
     * 记录完成的任务轨迹
     * @origin Python: TrajectoryCollector.record(ctx, success, elapsed, model)
     */
    public TaskTrajectory record(TaskContext ctx, boolean success, double elapsedS, String model) {
        TaskTrajectory traj = new TaskTrajectory();
        traj.taskId = UUID.randomUUID().toString();
        traj.userInput = ctx.userInput;
        traj.gateResult = ctx.gateResult != null ? ctx.gateResult : new HashMap<>();
        traj.expertUsed = (String) ctx.gateResult.getOrDefault("expert_type", "unknown");
        traj.pipelineSteps = GatePipelineUtil.getPipeline(ctx.gateResult);
        traj.success = success;
        traj.retryCount = ctx.retryCount;
        traj.latencyS = elapsedS;
        traj.modelUsed = model;
        traj.timestamp = Instant.now().toString();
        traj.searchTriggered = ctx.searchTriggered;
        traj.projectHash = hashProject(ctx.projectRoot);

        // 提取修改文件
        if (ctx.generatorOutput != null) {
            for (var patch : ctx.generatorOutput.patches()) {
                traj.filesModified.add(patch.file());
            }
        }

        // 持久化到JSON文件
        saveTrajectory(traj);
        return traj;
    }

    /**
     * 查找相似的成功轨迹
     * @origin Python: TrajectoryCollector.find_similar(user_input, expert_type, k)
     */
    public List<TaskTrajectory> findSimilar(String userInput, String expertType, int k) {
        List<TaskTrajectory> all = loadRecent(500);
        // 简化匹配：按expert_type过滤，按输入相似度排序
        return all.stream()
            .filter(t -> t.success && (expertType == null || expertType.equals(t.expertUsed)))
            .sorted((a, b) -> {
                double sa = textSimilarity(userInput, a.userInput);
                double sb = textSimilarity(userInput, b.userInput);
                return Double.compare(sb, sa);
            })
            .limit(k)
            .toList();
    }

    /** 加载最近的轨迹 */
    public List<TaskTrajectory> loadRecent(int limit) {
        List<TaskTrajectory> trajs = new ArrayList<>();
        try {
            List<Path> files = Files.list(Path.of(dir))
                .filter(p -> p.toString().endsWith(".json"))
                .sorted((a, b) -> {
                    try { return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a)); }
                    catch (IOException e) { return 0; }
                })
                .limit(limit)
                .toList();
            // TODO: JSON反序列化，Phase 3完善
        } catch (IOException e) { /* ignore */ }
        return trajs;
    }

    // ── 辅助 ──

    private void saveTrajectory(TaskTrajectory traj) {
        try {
            Path file = Path.of(dir, traj.taskId + ".json");
            // TODO: JSON序列化，Phase 3完善
            log.debug("[trajectory] saved {}", traj.taskId);
        } catch (Exception e) {
            log.warn("[trajectory] save failed: {}", e.getMessage());
        }
    }

    private String hashProject(String projectRoot) {
        try { return Integer.toHexString(projectRoot.hashCode()); }
        catch (Exception e) { return "unknown"; }
    }

    /** 简单文本相似度（词重叠率） */
    private double textSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        Set<String> wordsA = new HashSet<>(Arrays.asList(a.toLowerCase().split("\\s+")));
        Set<String> wordsB = new HashSet<>(Arrays.asList(b.toLowerCase().split("\\s+")));
        wordsA.retainAll(wordsB);
        return wordsA.isEmpty() ? 0 : (double) wordsA.size() / Math.max(
            Arrays.asList(a.toLowerCase().split("\\s+")).size(),
            Arrays.asList(b.toLowerCase().split("\\s+")).size()
        );
    }

    /** Gate流水线工具 */
    private static class GatePipelineUtil {
        static List<String> getPipeline(Map<String, Object> gateResult) {
            if (gateResult != null && gateResult.containsKey("pipeline")) {
                @SuppressWarnings("unchecked")
                List<String> p = (List<String>) gateResult.get("pipeline");
                if (p != null) return p;
            }
            return List.of("generator", "verifier");
        }
    }
}
