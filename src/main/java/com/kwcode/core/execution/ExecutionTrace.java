package com.kwcode.core.execution;

import java.time.Instant;
import java.util.*;

/**
 * 执行轨迹 - 完整的任务执行过程记录
 * <p>
 * 记录从开始到结束的每一步操作，供飞轮回放和经验学习使用。
 * 序列化友好，可持久化到文件。
 * </p>
 * @origin Python: core.execution_trace.ExecutionTrace
 */
public class ExecutionTrace {

    private final String traceId;
    private final String userInput;
    private final String projectRoot;
    private final Instant createdAt;
    private final List<TraceStep> steps = new ArrayList<>();
    private boolean success;
    private String finalError;
    private long totalElapsedMs;
    private int totalRetries;
    private String expertType;

    public ExecutionTrace(String userInput, String projectRoot) {
        this.traceId = UUID.randomUUID().toString().substring(0, 12);
        this.userInput = userInput;
        this.projectRoot = projectRoot;
        this.createdAt = Instant.now();
    }

    /**
     * 记录一个执行步骤
     */
    public void addStep(String phase, String action, Map<String, Object> data) {
        steps.add(new TraceStep(
            steps.size(), phase, action, Instant.now(), data
        ));
    }

    /**
     * 记录一个执行步骤（带耗时）
     */
    public void addStep(String phase, String action, Map<String, Object> data, long elapsedMs) {
        TraceStep step = new TraceStep(steps.size(), phase, action, Instant.now(), data);
        step.setElapsedMs(elapsedMs);
        steps.add(step);
    }

    /**
     * 标记执行完成
     */
    public void complete(boolean success, String error, long totalElapsedMs) {
        this.success = success;
        this.finalError = error != null ? error : "";
        this.totalElapsedMs = totalElapsedMs;
    }

    /**
     * 转换为可序列化的Map
     * @origin Python: core.execution_trace.ExecutionTrace.to_dict()
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("trace_id", traceId);
        map.put("user_input", userInput);
        map.put("project_root", projectRoot);
        map.put("created_at", createdAt.toString());
        map.put("success", success);
        map.put("final_error", finalError);
        map.put("total_elapsed_ms", totalElapsedMs);
        map.put("total_retries", totalRetries);
        map.put("expert_type", expertType != null ? expertType : "");
        map.put("step_count", steps.size());

        List<Map<String, Object>> stepList = new ArrayList<>();
        for (TraceStep step : steps) {
            stepList.add(step.toMap());
        }
        map.put("steps", stepList);

        return map;
    }

    public String getTraceId() { return traceId; }
    public String getUserInput() { return userInput; }
    public List<TraceStep> getSteps() { return Collections.unmodifiableList(steps); }
    public boolean isSuccess() { return success; }
    public String getFinalError() { return finalError; }
    public long getTotalElapsedMs() { return totalElapsedMs; }
    public int getTotalRetries() { return totalRetries; }
    public String getExpertType() { return expertType; }

    public void setTotalRetries(int totalRetries) { this.totalRetries = totalRetries; }
    public void setExpertType(String expertType) { this.expertType = expertType; }

    /**
     * 单个执行步骤
     */
    public static class TraceStep {
        private final int index;
        private final String phase;
        private final String action;
        private final Instant timestamp;
        private final Map<String, Object> data;
        private long elapsedMs;

        public TraceStep(int index, String phase, String action, Instant timestamp, Map<String, Object> data) {
            this.index = index;
            this.phase = phase;
            this.action = action;
            this.timestamp = timestamp;
            this.data = data != null ? new HashMap<>(data) : new HashMap<>();
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("phase", phase);
            map.put("action", action);
            map.put("timestamp", timestamp.toString());
            map.put("elapsed_ms", elapsedMs);
            if (!data.isEmpty()) map.put("data", data);
            return map;
        }

        public int getIndex() { return index; }
        public String getPhase() { return phase; }
        public String getAction() { return action; }
        public Instant getTimestamp() { return timestamp; }
        public Map<String, Object> getData() { return data; }
        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    }
}
