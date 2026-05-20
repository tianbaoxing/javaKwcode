package com.kwcode.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * 审计日志 - 持久化任务执行轨迹为人类可读格式
 * <p>
 * 成功写入success/目录，失败写入failed/目录。
 * 各目录最多保留100条，超出自动清理最旧的。
 * </p>
 * @origin Python: audit.logger.AuditLogger
 */
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LOGS = 100;

    private static final Path LOGS_BASE = Path.of(System.getProperty("user.home"), ".kaiwu", "logs");
    private static final Path LOGS_SUCCESS = LOGS_BASE.resolve("success");
    private static final Path LOGS_FAILED = LOGS_BASE.resolve("failed");

    private final List<Map<String, Object>> events = new ArrayList<>();
    private final List<Map<String, Object>> iterations = new ArrayList<>();
    private final List<Map<String, Object>> llmCalls = new ArrayList<>();
    private long startTime = 0;

    /**
     * 任务开始时调用
     * @origin Python: audit.logger.AuditLogger.start()
     */
    public void start() {
        events.clear(); iterations.clear(); llmCalls.clear();
        startTime = System.currentTimeMillis();
    }

    /**
     * 记录一个执行事件
     * @origin Python: audit.logger.AuditLogger.log(stage, detail)
     */
    public void log(String stage, String detail) {
        double elapsed = startTime > 0 ? (System.currentTimeMillis() - startTime) / 1000.0 : 0;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("elapsed_s", Math.round(elapsed * 10.0) / 10.0);
        entry.put("stage", stage);
        entry.put("detail", detail);
        events.add(entry);
    }

    /**
     * 记录一次LLM调用
     * @origin Python: audit.logger.AuditLogger.log_llm_call(caller, prompt_tokens, prompt_preview, raw_output, output_tokens, engineering_actions)
     */
    public void logLlmCall(String caller, int promptTokens, String promptPreview,
                            String rawOutput, int outputTokens,
                            Map<String, Object> engineeringActions) {
        double elapsed = startTime > 0 ? (System.currentTimeMillis() - startTime) / 1000.0 : 0;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
        entry.put("elapsed_s", Math.round(elapsed * 10.0) / 10.0);
        entry.put("caller", caller);
        entry.put("prompt_tokens", promptTokens);
        entry.put("prompt_preview", promptPreview != null ? promptPreview.substring(0, Math.min(500, promptPreview.length())) : "");
        entry.put("raw_output", rawOutput != null ? rawOutput.substring(0, Math.min(500, rawOutput.length())) : "");
        entry.put("output_tokens", outputTokens);
        entry.put("engineering_actions", engineeringActions != null ? engineeringActions : Map.of());
        llmCalls.add(entry);
    }

    /**
     * 记录每轮retry迭代的结构化决策信息
     * @origin Python: audit.logger.AuditLogger.log_iteration(attempt, gap_type, expert_selected, can_handle_results, transition_reason, test_delta)
     */
    public void logIteration(int attempt, String gapType, String expertSelected,
                              Map<String, Object> canHandleResults,
                              String transitionReason, Map<String, Object> testDelta) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("attempt", attempt);
        entry.put("gap_type", gapType);
        entry.put("expert_selected", expertSelected);
        entry.put("can_handle_results", canHandleResults != null ? canHandleResults : Map.of());
        entry.put("transition_reason", transitionReason);
        entry.put("test_delta", testDelta != null ? testDelta : Map.of());
        iterations.add(entry);
    }

    /**
     * 任务完成时写入日志文件
     * @origin Python: audit.logger.AuditLogger.write(ctx, elapsed, success, model)
     */
    public void write(TaskContext ctx, double elapsed, boolean success, String model) {
        try {
            Path logDir = success ? LOGS_SUCCESS : LOGS_FAILED;
            Files.createDirectories(logDir);

            Map<String, Object> gate = ctx.gateResult != null ? ctx.gateResult : Map.of();
            String expertType = (String) gate.getOrDefault("expert_type", "unknown");
            String difficulty = (String) gate.getOrDefault("difficulty", "?");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
            String filename = ts + "_" + expertType + ".json";

            // 修改的文件列表
            List<String> filesModified = new ArrayList<>();
            List<TaskContext.Patch> patches = List.of();
            if (ctx.generatorOutput != null) {
                patches = ctx.generatorOutput.patches();
                for (TaskContext.Patch p : patches) filesModified.add(p.file());
            }

            // 改动行数
            int linesAdded = 0, linesRemoved = 0;
            for (TaskContext.Patch p : patches) {
                int origLines = p.original() != null ? p.original().split("\n").length : 0;
                int modLines = p.modified() != null ? p.modified().split("\n").length : 0;
                linesAdded += Math.max(0, modLines - origLines);
                linesRemoved += Math.max(0, origLines - modLines);
            }

            // 测试结果
            int testsPassed = ctx.verifierOutput != null ? ctx.verifierOutput.testsPassed() : 0;
            int testsTotal = ctx.verifierOutput != null ? ctx.verifierOutput.testsTotal() : 0;

            // Gap信息
            String initialGapType = "";
            if (ctx.gap != null) initialGapType = ctx.gap.gapType().getKey();

            Map<String, Object> record = new LinkedHashMap<>();
            record.put("task", ctx.userInput.substring(0, Math.min(200, ctx.userInput.length())));
            record.put("timestamp", LocalDateTime.now().toString());
            record.put("model", model);
            record.put("expert_type", expertType);
            record.put("difficulty", difficulty);
            record.put("elapsed_s", Math.round(elapsed * 10.0) / 10.0);
            record.put("success", success);
            record.put("retry_count", ctx.retryCount);
            record.put("files_modified", filesModified);
            record.put("lines_added", linesAdded);
            record.put("lines_removed", linesRemoved);
            record.put("tests_passed", testsPassed);
            record.put("tests_total", testsTotal);
            record.put("search_triggered", ctx.searchTriggered);
            record.put("routing_source", ctx.routingSource);
            record.put("initial_gap_type", initialGapType);
            record.put("iterations", iterations);
            record.put("events", events);
            record.put("llm_calls", llmCalls);

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(logDir.resolve(filename).toFile(), record);
            cleanup(logDir);
        } catch (Exception e) {
            log.debug("Audit log write failed (non-blocking): {}", e.getMessage());
        }
    }

    /** 清理超过MAX_LOGS的旧日志 */
    private void cleanup(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            List<Path> logs = paths.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            if (logs.size() > MAX_LOGS) {
                for (int i = 0; i < logs.size() - MAX_LOGS; i++) {
                    Files.deleteIfExists(logs.get(i));
                }
            }
        } catch (IOException e) { /* ignore */ }
    }
}
