package com.kwcode.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 用户错误模式记忆
 * 跨项目统计用户高频错误类型，用于任务开始时主动提示
 * 存在用户home目录 ~/.kaiwu/user_patterns.json，跨项目有效
 * 不收集任何代码内容
 * @origin Python: flywheel/user_pattern_memory.py
 */
public class UserPatternMemory {

    private static final Logger log = LoggerFactory.getLogger(UserPatternMemory.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 用户模式文件路径 */
    private static final Path USER_PATTERNS_FILE =
        Path.of(System.getProperty("user.home"), ".kaiwu", "user_patterns.json");

    /** 高频错误阈值 */
    private static final int TOP_ERROR_THRESHOLD = 5;

    /** 展示的Top错误数 */
    private static final int TOP_N_ERRORS = 3;

    /** 触发警告提示的最低任务数 */
    private static final int MIN_TASKS_FOR_HINT = 20;

    /** 错误类型→提示映射 */
    private static final Map<String, String> ERROR_HINTS = Map.of(
        "assertion", "注意：你经常遇到断言错误，建议先确认测试的期望值",
        "import", "注意：你经常遇到缺少依赖，建议检查 requirements.txt 是否完整",
        "runtime", "注意：你经常遇到运行时错误，建议在修改前先阅读完整的调用链",
        "syntax", "注意：你经常遇到语法错误，建议修改后先用 linter 检查",
        "patch_apply", "注意：你经常遇到 patch 未命中，建议先确认文件没有被其他工具修改"
    );

    /** 内部数据 */
    private Map<String, Object> data;

    /**
     * 构造函数，自动加载数据
     */
    public UserPatternMemory() {
        this.data = load();
    }

    /**
     * 从磁盘加载用户模式数据
     * @return 模式数据Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> load() {
        try {
            if (Files.exists(USER_PATTERNS_FILE)) {
                return MAPPER.readValue(USER_PATTERNS_FILE.toFile(), Map.class);
            }
        } catch (Exception e) {
            log.debug("[user_pattern] 加载失败: {}", e.getMessage());
        }
        // 默认数据结构
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("error_frequency", new LinkedHashMap<String, Integer>());
        defaults.put("top_errors", new ArrayList<String>());
        defaults.put("total_tasks", 0);
        defaults.put("success_rate", 0.0);
        defaults.put("last_updated", "");
        return defaults;
    }

    /**
     * 保存数据到磁盘
     */
    private void save() {
        try {
            Files.createDirectories(USER_PATTERNS_FILE.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(USER_PATTERNS_FILE.toFile(), data);
        } catch (Exception e) {
            log.debug("[user_pattern] 保存失败(非阻塞): {}", e.getMessage());
        }
    }

    /**
     * 记录一次任务的错误类型统计
     * @param errorTypesEncountered 本次任务遇到的错误类型列表
     * @param success 任务是否最终成功
     */
    @SuppressWarnings("unchecked")
    public void recordTask(List<String> errorTypesEncountered, boolean success) {
        // 增量更新总任务数和成功率
        int totalTasks = ((Number) data.getOrDefault("total_tasks", 0)).intValue() + 1;
        data.put("total_tasks", totalTasks);

        double oldRate = ((Number) data.getOrDefault("success_rate", 0.0)).doubleValue();
        double newRate = oldRate + ((success ? 1.0 : 0.0) - oldRate) / totalTasks;
        data.put("success_rate", newRate);

        // 更新错误频率
        Map<String, Integer> freq = (Map<String, Integer>) data.getOrDefault("error_frequency", new LinkedHashMap<>());
        for (String errorType : errorTypesEncountered) {
            if (errorType != null && !"unknown".equals(errorType)) {
                freq.merge(errorType, 1, Integer::sum);
            }
        }
        data.put("error_frequency", freq);

        // 更新Top错误列表
        List<String> topErrors = new ArrayList<>();
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(freq.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(TOP_N_ERRORS, sorted.size()); i++) {
            Map.Entry<String, Integer> entry = sorted.get(i);
            if (entry.getValue() >= TOP_ERROR_THRESHOLD) {
                topErrors.add(entry.getKey());
            }
        }
        data.put("top_errors", topErrors);

        // 更新时间戳
        data.put("last_updated", LocalDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        save();
    }

    /**
     * 根据用户高频错误模式生成提示，注入Gate的memory_context
     * 只有积累足够数据后才生成提示，避免误报
     * @return 提示文本，无足够数据返回空字符串
     */
    public String getWarningHint() {
        int totalTasks = ((Number) data.getOrDefault("total_tasks", 0)).intValue();
        if (totalTasks < MIN_TASKS_FOR_HINT) {
            return "";
        }

        @SuppressWarnings("unchecked")
        List<String> topErrors = (List<String>) data.getOrDefault("top_errors", List.of());
        if (topErrors.isEmpty()) {
            return "";
        }

        List<String> hints = new ArrayList<>();
        for (String error : topErrors) {
            String hint = ERROR_HINTS.get(error);
            if (hint != null) {
                hints.add(hint);
            }
        }

        return hints.isEmpty() ? "" : String.join("\n", hints);
    }

    /**
     * 返回用户模式摘要，用于/stats命令展示
     * @return 摘要数据
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total_tasks", data.getOrDefault("total_tasks", 0));

        double sr = ((Number) data.getOrDefault("success_rate", 0.0)).doubleValue();
        result.put("success_rate", String.format("%.1f%%", sr * 100));

        result.put("top_errors", data.getOrDefault("top_errors", List.of()));
        result.put("error_frequency", data.getOrDefault("error_frequency", Map.of()));

        return result;
    }
}
