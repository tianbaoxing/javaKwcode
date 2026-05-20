package com.kwcode.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 专家生成器：从轨迹模式中通过LLM生成专家YAML草稿
 * @origin Python: flywheel/expert_generator.py
 */
public class ExpertGenerator {

    private static final Logger log = LoggerFactory.getLogger(ExpertGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 专家生成prompt模板 */
    private static final String EXPERT_GENERATION_PROMPT =
        "你是专家设计师。分析以下%s个成功任务的执行轨迹，提取共性模式，生成一个专家定义。\n\n" +
        "任务轨迹：\n%s\n\n" +
        "要求：\n1. 分析这些任务的共同特征（触发条件、操作模式、成功策略）\n" +
        "2. 生成一个针对这类任务的专家\n3. system_prompt必须具体，包含这类任务的最佳实践\n" +
        "4. trigger_keywords必须精准，避免与其他专家冲突\n5. 只输出JSON，不要解释\n\n" +
        "输出格式：\n{\"name\":\"XxxExpert\",\"trigger_keywords\":[...]," +
        "\"trigger_min_confidence\":0.85,\"system_prompt\":\"...\",\"tool_whitelist\":[...],\"pipeline\":[...]}";

    /** 合法的pipeline步骤 */
    private static final Set<String> VALID_PIPELINE_STEPS = Set.of("locator", "generator", "verifier");

    /** LLM后端接口 */
    private final LlmBackend llm;

    /**
     * LLM后端接口（简化定义）
     */
    public interface LlmBackend {
        /** 生成文本 */
        String generate(String prompt, String system, int maxTokens, double temperature);
    }

    /**
     * 构造函数
     * @param llm LLM后端
     */
    public ExpertGenerator(LlmBackend llm) {
        this.llm = llm;
    }

    /**
     * 从检测到的模式中生成专家定义
     * @param pattern 模式数据，包含trajectories和count
     * @return 解析后的专家定义Map，失败返回null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(Map<String, Object> pattern) {
        List<Map<String, Object>> trajectories = (List<Map<String, Object>>) pattern.getOrDefault("trajectories", List.of());
        int count = ((Number) pattern.getOrDefault("count", 0)).intValue();

        // 构建精简轨迹摘要（限制最多10条，避免token膨胀）
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (int i = 0; i < Math.min(10, trajectories.size()); i++) {
            Map<String, Object> t = trajectories.get(i);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("user_input", truncate(String.valueOf(t.getOrDefault("user_input", "")), 200));
            summary.put("expert_type", t.getOrDefault("expert_used", ""));
            summary.put("pipeline", t.getOrDefault("pipeline_steps", List.of()));
            List<String> files = (List<String>) t.getOrDefault("files_modified", List.of());
            summary.put("files_modified", files.subList(0, Math.min(5, files.size())));
            summary.put("latency_s", t.getOrDefault("latency_s", 0));
            summary.put("search_triggered", t.getOrDefault("search_triggered", false));
            summaries.add(summary);
        }

        String trajJson;
        try {
            trajJson = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summaries);
        } catch (Exception e) {
            trajJson = summaries.toString();
        }

        String prompt = String.format(EXPERT_GENERATION_PROMPT, count, trajJson);

        try {
            String raw = llm.generate(prompt, "你是Kaiwu专家系统的设计师。只输出合法JSON。", 2048, 0.3);
            return parseExpert(raw, pattern);
        } catch (Exception e) {
            log.error("[expert_generator] LLM调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析LLM输出为验证后的专家定义
     * @param raw LLM原始输出
     * @param pattern 原始模式数据
     * @return 专家定义Map，失败返回null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseExpert(String raw, Map<String, Object> pattern) {
        // 处理markdown代码块包裹
        String text = raw.trim();
        if (text.startsWith("```")) {
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n")));
            lines.removeIf(l -> l.trim().startsWith("```"));
            text = String.join("\n", lines);
        }

        Map<String, Object> expert;
        try {
            expert = MAPPER.readValue(text, Map.class);
        } catch (Exception e) {
            log.warn("[expert_generator] JSON解析失败: {}\n原始输出: {}", e.getMessage(), raw.substring(0, Math.min(500, raw.length())));
            return null;
        }

        // 验证必填字段
        Set<String> required = Set.of("name", "trigger_keywords", "trigger_min_confidence", "system_prompt", "pipeline");
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(expert.keySet());
        if (!missing.isEmpty()) {
            log.warn("[expert_generator] 生成的专家缺少字段: {}", missing);
            return null;
        }

        // 验证pipeline步骤
        List<String> pipeline = (List<String>) expert.get("pipeline");
        for (String step : pipeline) {
            if (!VALID_PIPELINE_STEPS.contains(step)) {
                log.warn("[expert_generator] 无效的pipeline步骤: {}", step);
                return null;
            }
        }

        // 添加默认元数据
        expert.putIfAbsent("version", "1.0");
        expert.putIfAbsent("type", pattern.getOrDefault("expert_type", "custom"));
        expert.putIfAbsent("lifecycle", "new");
        expert.putIfAbsent("performance", Map.of("success_rate", 0.0, "avg_latency_s", 0, "task_count", 0));
        expert.put("_source", "flywheel");

        return expert;
    }

    /** 截断字符串 */
    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
