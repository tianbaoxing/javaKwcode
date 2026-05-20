package com.kwcode.core.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自动任务分解 - 1次LLM调用将复合任务拆分为DAG，失败降级为单任务
 * <p>
 * 设计原则：
 * - Planner只做1次LLM调用，输出结构化JSON
 * - 失败时降级为单任务（不死循环）
 * - 简单任务不拆分（Gate difficulty=easy直接跳过）
 * </p>
 * <p>
 * 理论来源：
 * - "Hidden Architectural Seam" (2026): 分离Planner和Executor提升9-15%
 * - CodeDelegator (2025): Delegator(持久) + Coder(临时) 隔离context
 * </p>
 * @origin Python: core.task_planner.TaskPlanner
 */
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PLANNER_PROMPT =
        "分析以下任务，判断是否需要拆分为多个子任务。\n\n" +
        "任务：{user_input}\n\n" +
        "规则：\n" +
        "1. 如果任务只涉及一个操作（修一个bug、写一个函数、加注释），输出 single\n" +
        "2. 如果任务涉及多个有依赖的步骤（先搜索数据再生成页面、先重构再写测试），拆分为子任务\n" +
        "3. 如果任务涉及多个独立操作（给3个函数各加注释），拆分为并行子任务\n" +
        "4. 最多拆分5个子任务\n\n" +
        "输出JSON格式（不要解释）：\n" +
        "单任务：{{\"type\": \"single\"}}\n" +
        "多任务：{{\"type\": \"multi\", \"tasks\": [{{\"id\": \"t1\", \"input\": \"子任务描述\", \"depends_on\": []}}, " +
        "{{\"id\": \"t2\", \"input\": \"子任务描述\", \"depends_on\": [\"t1\"]}}]}}";

    private static final String PLANNER_SYSTEM = "你是任务分解专家，只输出JSON，不要解释。";

    private static final Pattern JSON_PATTERN = Pattern.compile(
        "\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}", Pattern.DOTALL
    );

    private final LLMService llmService;

    public TaskPlanner(LLMService llmService) {
        this.llmService = llmService;
    }

    /**
     * 分析任务是否需要拆分
     * <p>
     * 返回task list（供TaskCompiler执行）或null（单任务，走普通流程）。
     * 只在difficulty=hard时尝试拆分。easy任务直接返回null。
     * </p>
     * @origin Python: core.task_planner.TaskPlanner.plan(user_input, difficulty) -> Optional[list[dict]]
     * @param userInput 用户输入
     * @param difficulty 任务难度
     * @return 子任务列表，null表示单任务
     */
    public List<TaskDef> plan(String userInput, String difficulty) {
        if (!"hard".equals(difficulty)) {
            return null;
        }

        if (userInput.length() < 30) {
            return null;
        }

        try {
            String prompt = PLANNER_PROMPT.replace("{user_input}", userInput.substring(0, Math.min(300, userInput.length())));
            String response = llmService.generateForExpert("planner", prompt, PLANNER_SYSTEM, 300);

            List<TaskDef> result = parseResponse(response);
            if (result == null) {
                return null;
            }

            if (!validateTasks(result)) {
                log.warn("[task_planner] Invalid task structure, fallback to single");
                return null;
            }

            log.info("[task_planner] Decomposed into {} subtasks", result.size());
            return result;

        } catch (Exception e) {
            log.warn("[task_planner] Planning failed, fallback to single: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析LLM输出的JSON
     * @origin Python: core.task_planner.TaskPlanner._parse_response(response)
     */
    List<TaskDef> parseResponse(String response) {
        if (response == null || response.isBlank()) return null;

        Matcher matcher = JSON_PATTERN.matcher(response);
        if (!matcher.find()) return null;

        try {
            Map<String, Object> data = MAPPER.readValue(matcher.group(), new TypeReference<>() {});

            String type = (String) data.get("type");
            if ("single".equals(type)) return null;

            if ("multi".equals(type) && data.containsKey("tasks")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tasks = (List<Map<String, Object>>) data.get("tasks");
                if (tasks != null && tasks.size() >= 2) {
                    List<TaskDef> result = new ArrayList<>();
                    for (Map<String, Object> t : tasks) {
                        String id = (String) t.get("id");
                        String input = (String) t.get("input");
                        @SuppressWarnings("unchecked")
                        List<String> dependsOn = (List<String>) t.getOrDefault("depends_on", List.of());
                        if (id != null && input != null) {
                            result.add(new TaskDef(id, input, dependsOn != null ? dependsOn : List.of()));
                        }
                    }
                    return result.size() >= 2 ? result : null;
                }
            }
        } catch (Exception e) {
            log.debug("[task_planner] JSON parse failed: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 验证任务列表结构正确
     * @origin Python: core.task_planner.TaskPlanner._validate_tasks(tasks)
     */
    boolean validateTasks(List<TaskDef> tasks) {
        if (tasks == null || tasks.isEmpty() || tasks.size() > 5) return false;

        Set<String> ids = new HashSet<>();
        for (TaskDef task : tasks) {
            if (task.id() == null || task.id().isBlank()) return false;
            if (task.input() == null || task.input().isBlank()) return false;
            ids.add(task.id());
        }

        for (TaskDef task : tasks) {
            for (String dep : task.dependsOn()) {
                if (!ids.contains(dep)) return false;
            }
        }

        return true;
    }

    /**
     * 子任务定义
     * @origin Python: core.task_planner.TaskPlanner task dict
     */
    public record TaskDef(
        String id,
        String input,
        List<String> dependsOn
    ) {}
}
