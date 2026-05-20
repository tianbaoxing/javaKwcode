package com.kwcode.core.gate;

import com.kwcode.core.context.TaskContext;
import com.kwcode.core.orchestrator.PipelineOrchestrator;
import com.kwcode.core.upstream.UpstreamManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级DAG任务编译器
 * <p>
 * 接受带依赖的任务定义，构建DAG，通过ThreadPoolExecutor按拓扑序执行。
 * 每个任务获得独立的TaskContext（RED-3: 独立上下文）。
 * </p>
 * <p>
 * 零新依赖（ThreadPoolExecutor是JDK标准库）。
 * </p>
 * @origin Python: core.task_compiler.TaskCompiler
 */
public class TaskCompiler {

    private static final Logger log = LoggerFactory.getLogger(TaskCompiler.class);
    private static final int MAX_PARALLEL_WORKERS = 4;

    private final PipelineOrchestrator orchestrator;
    private final Gate gate;
    private final String projectRoot;
    private final UpstreamManifest manifest;

    public TaskCompiler(PipelineOrchestrator orchestrator, Gate gate, String projectRoot) {
        this.orchestrator = orchestrator;
        this.gate = gate;
        this.projectRoot = projectRoot;
        this.manifest = new UpstreamManifest();
    }

    /**
     * 执行DAG任务
     * <p>
     * 每个任务调用orchestrator.run()执行。
     * </p>
     * @origin Python: core.task_compiler.TaskCompiler.compile_and_run(tasks, on_status)
     * @param tasks 任务定义列表
     * @param onStatus 状态回调
     * @return 编译执行结果
     */
    public CompileResult compileAndRun(List<TaskDef> tasks, BiConsumer<String, String> onStatus) {
        long startTime = System.currentTimeMillis();

        if (tasks == null || tasks.isEmpty()) {
            return new CompileResult(Map.of(), true, 0);
        }

        Map<String, TaskDef> taskMap = new LinkedHashMap<>();
        for (TaskDef t : tasks) {
            taskMap.put(t.id(), t);
        }

        validateGraph(taskMap);

        List<List<String>> layers = topologicalLayers(taskMap);

        Map<String, TaskResult> results = new ConcurrentHashMap<>();
        boolean allSuccess = true;

        for (List<String> layer : layers) {
            if (layer.size() == 1) {
                String taskId = layer.get(0);
                TaskDef taskDef = taskMap.get(taskId);
                TaskResult result = executeTask(taskDef, results, onStatus);
                results.put(taskId, result);
                if (!result.success()) allSuccess = false;
            } else {
                int poolSize = Math.min(layer.size(), MAX_PARALLEL_WORKERS);
                ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
                    Thread t = new Thread(r, "task_compiler");
                    t.setDaemon(true);
                    return t;
                });

                try {
                    List<Future<Map.Entry<String, TaskResult>>> futures = new ArrayList<>();
                    for (String taskId : layer) {
                        TaskDef taskDef = taskMap.get(taskId);
                        futures.add(pool.submit(() -> {
                            TaskResult result = executeTask(taskDef, results, onStatus);
                            return Map.entry(taskId, result);
                        }));
                    }

                    for (Future<Map.Entry<String, TaskResult>> future : futures) {
                        try {
                            Map.Entry<String, TaskResult> entry = future.get();
                            results.put(entry.getKey(), entry.getValue());
                            if (!entry.getValue().success()) allSuccess = false;
                        } catch (Exception e) {
                            log.error("[task_compiler] Task raised: {}", e.getMessage());
                        }
                    }
                } finally {
                    pool.shutdownNow();
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return new CompileResult(results, allSuccess, elapsed);
    }

    /**
     * 执行单个任务
     * <p>
     * 注入依赖上下文：将已完成任务输出追加到输入。
     * PENCIL式：用上游patch更新manifest。
     * </p>
     * @origin Python: core.task_compiler.TaskCompiler._execute_task(task_def, completed, on_status)
     */
    private TaskResult executeTask(TaskDef taskDef, Map<String, TaskResult> completed,
                                    BiConsumer<String, String> onStatus) {
        String taskId = taskDef.id();
        String userInput = taskDef.input();

        Map<String, Object> upstreamDict = new HashMap<>();
        List<String> deps = taskDef.dependsOn();
        if (deps != null && !deps.isEmpty()) {
            upstreamDict = buildDependencyContext(deps, completed);
            if (upstreamDict.containsKey("modified_files")) {
                @SuppressWarnings("unchecked")
                List<String> modifiedFiles = (List<String>) upstreamDict.get("modified_files");
                if (modifiedFiles != null && !modifiedFiles.isEmpty()) {
                    String upstreamText = formatUpstreamText(upstreamDict);
                    userInput = userInput + "\n\n[前置任务结果]\n" + upstreamText;
                }
            }
            updateManifestFromDeps(deps, completed);
        }

        Map<String, Object> gateResult;
        String expertType = taskDef.expertType();
        if (expertType != null && !expertType.isEmpty()) {
            gateResult = new HashMap<>();
            gateResult.put("expert_type", expertType);
            gateResult.put("task_summary", userInput.substring(0, Math.min(20, userInput.length())));
            gateResult.put("difficulty", "easy");
        } else {
            gateResult = gate.classify(userInput, "", null);
        }

        log.info("[task_compiler] Executing task {}: {}", taskId, userInput.substring(0, Math.min(50, userInput.length())));

        try {
            PipelineOrchestrator.OrchestratorResult result = orchestrator.run(
                userInput, gateResult, projectRoot, onStatus, false, true
            );

            if (result.context() != null && !upstreamDict.isEmpty()) {
                result.context().upstreamSummary = upstreamDict;
            }

            return new TaskResult(
                result.success(),
                result.context(),
                result.error(),
                result.elapsedMs()
            );
        } catch (Exception e) {
            log.error("[task_compiler] Task {} failed: {}", taskId, e.getMessage());
            return new TaskResult(false, null, e.getMessage(), 0);
        }
    }

    /**
     * 构建依赖上下文
     * @origin Python: core.task_compiler.TaskCompiler._build_dependency_context(dep_ids, completed)
     */
    private Map<String, Object> buildDependencyContext(List<String> depIds, Map<String, TaskResult> completed) {
        List<String> modifiedFiles = new ArrayList<>();
        Map<String, String> diffs = new LinkedHashMap<>();
        List<String> newSymbols = new ArrayList<>();

        Pattern funcPattern = Pattern.compile("def\\s+(\\w+)\\s*\\(");

        for (String depId : depIds) {
            TaskResult result = completed.get(depId);
            if (result == null || result.context() == null) continue;
            TaskContext ctx = result.context();
            if (ctx.generatorOutput == null) continue;

            for (TaskContext.Patch patch : ctx.generatorOutput.patches()) {
                String filePath = patch.file();
                if (filePath == null || filePath.isEmpty()) continue;
                if (!modifiedFiles.contains(filePath)) modifiedFiles.add(filePath);

                String modified = patch.modified();
                if (modified != null && !modified.isEmpty() && !diffs.containsKey(filePath)) {
                    String[] lines = modified.split("\n");
                    if (lines.length > 200) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 200; i++) sb.append(lines[i]).append("\n");
                        sb.append("... (truncated)");
                        diffs.put(filePath, sb.toString());
                    } else {
                        diffs.put(filePath, modified);
                    }
                }

                if (modified != null) {
                    Matcher m = funcPattern.matcher(modified);
                    while (m.find()) {
                        String symbol = m.group(1);
                        if (!newSymbols.contains(symbol)) newSymbols.add(symbol);
                    }
                }
            }
        }

        return Map.of(
            "modified_files", modifiedFiles,
            "diffs", diffs,
            "new_symbols", newSymbols,
            "broken_interfaces", List.of()
        );
    }

    /**
     * 格式化上游文本
     * @origin Python: core.task_compiler.TaskCompiler._format_upstream_text(upstream_dict)
     */
    private String formatUpstreamText(Map<String, Object> upstreamDict) {
        List<String> parts = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<String> modified = (List<String>) upstreamDict.get("modified_files");
        if (modified != null && !modified.isEmpty()) {
            parts.add("修改文件: " + String.join(", ", modified));
        }

        @SuppressWarnings("unchecked")
        List<String> newSymbols = (List<String>) upstreamDict.get("new_symbols");
        if (newSymbols != null && !newSymbols.isEmpty()) {
            parts.add("新增符号: " + String.join(", ", newSymbols));
        }

        @SuppressWarnings("unchecked")
        Map<String, String> diffs = (Map<String, String>) upstreamDict.get("diffs");
        if (diffs != null && !diffs.isEmpty()) {
            parts.add("--- Diffs ---");
            for (var entry : diffs.entrySet()) {
                parts.add("[" + entry.getKey() + "]\n" + entry.getValue());
            }
        }

        return String.join("\n", parts);
    }

    /**
     * 更新manifest
     */
    private void updateManifestFromDeps(List<String> depIds, Map<String, TaskResult> completed) {
        try {
            for (String depId : depIds) {
                TaskResult result = completed.get(depId);
                if (result == null || result.context() == null) continue;
                TaskContext ctx = result.context();
                if (ctx.generatorOutput != null && !ctx.generatorOutput.patches().isEmpty()) {
                    manifest.update(ctx.generatorOutput.patches());
                }
            }
        } catch (Exception e) {
            log.debug("[task_compiler] manifest update failed (non-blocking): {}", e.getMessage());
        }
    }

    /**
     * 验证任务图
     * @origin Python: core.task_compiler.TaskCompiler._validate_graph(task_map)
     */
    private void validateGraph(Map<String, TaskDef> taskMap) {
        for (var entry : taskMap.entrySet()) {
            String taskId = entry.getKey();
            List<String> deps = entry.getValue().dependsOn();
            if (deps != null) {
                for (String dep : deps) {
                    if (!taskMap.containsKey(dep)) {
                        throw new IllegalArgumentException(
                            "Task '" + taskId + "' depends on '" + dep + "' which does not exist"
                        );
                    }
                }
            }
        }
    }

    /**
     * Kahn算法生成拓扑层
     * <p>
     * 每层包含可并行执行的任务。
     * </p>
     * @origin Python: core.task_compiler.TaskCompiler._topological_layers(task_map)
     */
    private List<List<String>> topologicalLayers(Map<String, TaskDef> taskMap) {
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();

        for (String tid : taskMap.keySet()) {
            inDegree.put(tid, 0);
            dependents.put(tid, new ArrayList<>());
        }

        for (var entry : taskMap.entrySet()) {
            String tid = entry.getKey();
            List<String> deps = entry.getValue().dependsOn();
            if (deps != null) {
                for (String dep : deps) {
                    inDegree.merge(tid, 1, Integer::sum);
                    dependents.get(dep).add(tid);
                }
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (var entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<List<String>> layers = new ArrayList<>();
        int processed = 0;

        while (!queue.isEmpty()) {
            List<String> layer = new ArrayList<>(queue);
            queue.clear();
            layers.add(layer);
            processed += layer.size();

            for (String tid : layer) {
                for (String dependent : dependents.get(tid)) {
                    inDegree.merge(dependent, -1, Integer::sum);
                    if (inDegree.get(dependent) == 0) {
                        queue.add(dependent);
                    }
                }
            }
        }

        if (processed != taskMap.size()) {
            throw new CycleError("Task DAG contains a cycle");
        }

        return layers;
    }

    public UpstreamManifest getManifest() {
        return manifest;
    }

    // ── 数据结构 ──

    /**
     * 任务定义
     */
    public record TaskDef(
        String id,
        String input,
        List<String> dependsOn,
        String expertType
    ) {
        public TaskDef(String id, String input, List<String> dependsOn) {
            this(id, input, dependsOn, null);
        }
    }

    /**
     * 单个任务执行结果
     */
    public record TaskResult(
        boolean success,
        TaskContext context,
        String error,
        long elapsedMs
    ) {}

    /**
     * 编译执行结果
     */
    public record CompileResult(
        Map<String, TaskResult> results,
        boolean success,
        long elapsedMs
    ) {}

    /**
     * DAG循环错误
     */
    public static class CycleError extends RuntimeException {
        public CycleError(String message) {
            super(message);
        }
    }
}
