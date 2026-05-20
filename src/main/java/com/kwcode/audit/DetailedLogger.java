package com.kwcode.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 详细日志记录器 - 完整不截断的流水线日志
 * <p>
 * 每个任务生成一个JSON文件，记录：
 * - LLM完整prompt/output（不截断）
 * - 各节点的输入输出
 * - 工程机制决策（重试策略、搜索、熔断等）
 * </p>
 * @origin Python: audit.detailed_logger.DetailedLogger
 */
public class DetailedLogger {

    private static final Logger log = LoggerFactory.getLogger(DetailedLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private boolean enabled = true;
    private Path logDir;
    private long startTime;
    private String taskId;
    private String userInput;
    private String model;
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private final Map<String, Object> metadata = new HashMap<>();

    public DetailedLogger(String userInput, String model) {
        String envVal = System.getenv("KWCODE_DETAIL_LOG_DIR");
        if (envVal != null && envVal.isEmpty()) { enabled = false; return; }
        this.logDir = envVal != null ? Path.of(envVal) : Path.of("logs");
        this.startTime = System.currentTimeMillis();
        this.taskId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss"));
        this.userInput = userInput;
        this.model = model;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 设置任务级元数据
     * @origin Python: audit.detailed_logger.DetailedLogger.set_metadata(**kwargs)
     */
    public void setMetadata(String key, Object value) {
        if (!enabled) return;
        metadata.put(key, value);
    }

    /**
     * 记录一次LLM调用（完整不截断）
     * @origin Python: audit.detailed_logger.DetailedLogger.log_llm(caller, prompt, system, raw_output, tokens, elapsed_ms, messages)
     */
    public void logLlm(String caller, String prompt, String system,
                        String rawOutput, Map<String, Integer> tokens,
                        double elapsedMs, List<Map<String, String>> messages) {
        if (!enabled) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
            entry.put("elapsed_s", (System.currentTimeMillis() - startTime) / 1000.0);
            entry.put("type", "llm_call");
            entry.put("caller", caller);
            Map<String, Object> input = new LinkedHashMap<>();
            if (messages != null) input.put("messages", messages);
            else { input.put("system", system); input.put("prompt", prompt); }
            entry.put("input", input);
            entry.put("output", rawOutput);
            entry.put("elapsed_ms", elapsedMs);
            entry.put("tokens", tokens != null ? tokens : Map.of());
            timeline.add(entry);
        } catch (Exception e) { /* 非阻塞 */ }
    }

    /**
     * 记录一个流水线节点的输入输出
     * @origin Python: audit.detailed_logger.DetailedLogger.log_node(stage, input_data, output_data, detail)
     */
    public void logNode(String stage, Map<String, Object> inputData,
                         Map<String, Object> outputData, String detail) {
        if (!enabled) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
            entry.put("elapsed_s", (System.currentTimeMillis() - startTime) / 1000.0);
            entry.put("type", "node_io");
            entry.put("stage", stage);
            entry.put("input", inputData);
            entry.put("output", outputData);
            if (detail != null && !detail.isEmpty()) entry.put("detail", detail);
            timeline.add(entry);
        } catch (Exception e) { /* 非阻塞 */ }
    }

    /**
     * 记录一个工程决策
     * @origin Python: audit.detailed_logger.DetailedLogger.log_decision(stage, decision, reason, context)
     */
    public void logDecision(String stage, String decision, String reason,
                             Map<String, Object> context) {
        if (!enabled) return;
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
            entry.put("elapsed_s", (System.currentTimeMillis() - startTime) / 1000.0);
            entry.put("type", "decision");
            entry.put("stage", stage);
            entry.put("decision", decision);
            entry.put("reason", reason);
            if (context != null) entry.put("context", context);
            timeline.add(entry);
        } catch (Exception e) { /* 非阻塞 */ }
    }

    /**
     * 任务结束时写入日志文件
     * @origin Python: audit.detailed_logger.DetailedLogger.write(expert_type, success)
     */
    public void write(String expertType, boolean success) {
        if (!enabled) return;
        try {
            Files.createDirectories(logDir);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("task_id", taskId);
            record.put("user_input", userInput);
            record.put("model", model);
            record.put("expert_type", expertType);
            record.put("success", success);
            record.put("total_elapsed_s", (System.currentTimeMillis() - startTime) / 1000.0);
            record.put("timestamp", LocalDateTime.now().toString());
            record.putAll(metadata);
            record.put("timeline", timeline);

            String filename = taskId + "_" + expertType + ".json";
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(logDir.resolve(filename).toFile(), record);
        } catch (Exception e) {
            log.debug("DetailedLogger write failed (non-blocking): {}", e.getMessage());
        }
    }
}
