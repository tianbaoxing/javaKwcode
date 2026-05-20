package com.kwcode.flywheel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 策略统计 - 记录各重试策略的成功/失败统计
 * <p>
 * 按error_type×策略维度追踪，用于飞轮优化重试策略选择。
 * </p>
 * @origin Python: flywheel.strategy_stats.StrategyStats
 */
public class StrategyStats {

    private static final Logger log = LoggerFactory.getLogger(StrategyStats.class);
    private final Map<String, StrategyRecord> stats = new ConcurrentHashMap<>();

    /**
     * 记录一次策略使用结果
     * @param errorType 错误类型
     * @param strategy 使用的重试策略
     * @param success 是否成功
     */
    public void record(String errorType, String strategy, boolean success) {
        String key = errorType + ":" + strategy;
        stats.computeIfAbsent(key, k -> new StrategyRecord(errorType, strategy))
             .record(success);
    }

    /** 获取某错误类型的最佳策略 */
    public String getBestStrategy(String errorType) {
        return stats.values().stream()
            .filter(r -> errorType.equals(r.errorType))
            .filter(r -> r.total >= 3) // 至少3次样本
            .max(Comparator.comparingDouble(r -> r.successRate()))
            .map(r -> r.strategy)
            .orElse(null);
    }

    /** 获取所有统计 */
    public Map<String, StrategyRecord> getAllStats() {
        return Collections.unmodifiableMap(stats);
    }

    public static class StrategyRecord {
        public final String errorType;
        public final String strategy;
        public int success = 0;
        public int total = 0;

        public StrategyRecord(String errorType, String strategy) {
            this.errorType = errorType;
            this.strategy = strategy;
        }

        public void record(boolean success) {
            total++;
            if (success) this.success++;
        }

        public double successRate() {
            return total > 0 ? (double) success / total : 0;
        }
    }
}
