package com.kwcode.core.execution;

import java.time.Instant;
import java.util.*;

/**
 * 执行状态 - 单次任务执行的状态快照
 * <p>
 * 记录每个阶段的执行状态、耗时、成功/失败。
 * 供ExecutionTracker追踪和飞轮回放使用。
 * </p>
 * @origin Python: core.execution_state.ExecutionState
 */
public class ExecutionState {

    public enum Phase {
        ENV_PROBE("env_probe"),
        PRE_TEST("pre_test"),
        GATE("gate"),
        LOCATE("locate"),
        GENERATE("generate"),
        VERIFY("verify"),
        DEBUG("debug"),
        REVIEW("review"),
        SEARCH("search"),
        DONE("done"),
        FAILED("failed");

        private final String key;
        Phase(String key) { this.key = key; }
        public String getKey() { return key; }
    }

    private String taskId;
    private Phase currentPhase;
    private Phase previousPhase;
    private Instant startedAt;
    private Instant completedAt;
    private boolean success;
    private String errorMessage;
    private Map<Phase, Long> phaseDurations = new LinkedHashMap<>();
    private Map<String, Object> metadata = new HashMap<>();
    private int retryCount;
    private String expertType;

    public ExecutionState() {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.currentPhase = Phase.ENV_PROBE;
        this.startedAt = Instant.now();
    }

    public void transitionTo(Phase phase) {
        if (currentPhase != null && phaseDurations != null) {
            long elapsed = java.time.Duration.between(startedAt, Instant.now()).toMillis();
            phaseDurations.put(currentPhase, elapsed);
        }
        this.previousPhase = this.currentPhase;
        this.currentPhase = phase;
    }

    public void complete(boolean success, String errorMessage) {
        this.completedAt = Instant.now();
        this.success = success;
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.currentPhase = success ? Phase.DONE : Phase.FAILED;
    }

    public long getTotalDurationMs() {
        if (completedAt != null) {
            return java.time.Duration.between(startedAt, completedAt).toMillis();
        }
        return java.time.Duration.between(startedAt, Instant.now()).toMillis();
    }

    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("task_id", taskId);
        summary.put("phase", currentPhase != null ? currentPhase.getKey() : "unknown");
        summary.put("success", success);
        summary.put("retry_count", retryCount);
        summary.put("expert_type", expertType != null ? expertType : "");
        summary.put("total_ms", getTotalDurationMs());
        summary.put("phase_durations", phaseDurations);
        if (errorMessage != null && !errorMessage.isEmpty()) {
            summary.put("error", errorMessage);
        }
        return summary;
    }

    public String getTaskId() { return taskId; }
    public Phase getCurrentPhase() { return currentPhase; }
    public Phase getPreviousPhase() { return previousPhase; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public int getRetryCount() { return retryCount; }
    public String getExpertType() { return expertType; }
    public Map<Phase, Long> getPhaseDurations() { return Collections.unmodifiableMap(phaseDurations); }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setExpertType(String expertType) { this.expertType = expertType; }
    public void setMetadata(String key, Object value) { this.metadata.put(key, value); }
}
