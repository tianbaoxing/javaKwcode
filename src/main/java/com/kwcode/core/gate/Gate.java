package com.kwcode.core.gate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.llm.LLMService;
import com.kwcode.registry.ExpertRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Gate门控 - 确定性优先路由，LLM只做最后兜底
 * <p>
 * 决策优先级：特殊任务快速路由 → 测试gap路由 → 文件特征检测 → 关键词匹配 → LLM兜底二分类。
 * </p>
 * @origin Python: core.gate.Gate
 */
public class Gate {

    private static final Logger log = LoggerFactory.getLogger(Gate.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> VALID_EXPERT_TYPES = Set.of(
        "locator_repair", "codegen", "refactor", "doc", "office", "chat", "vision"
    );

    /** 专家流水线序列映射 */
    public static final Map<String, List<String>> EXPERT_SEQUENCES = Map.of(
        "locator_repair", List.of("locator", "generator", "verifier"),
        "codegen", List.of("generator", "verifier"),
        "refactor", List.of("locator", "generator", "verifier"),
        "doc", List.of("locator", "generator"),
        "office", List.of("office"),
        "chat", List.of("chat"),
        "vision", List.of("vision")
    );

    /** 重试策略序列 */
    public static final Map<String, List<String>> RETRY_STRATEGIES = Map.of(
        "syntax", List.of("generator", "verifier"),
        "assertion", List.of("generator", "verifier"),
        "import", List.of("import_fixer", "verifier"),
        "runtime", List.of("debug_subagent", "generator", "verifier"),
        "contract_violation", List.of("locator", "generator", "verifier"),
        "patch_apply", List.of("locator", "generator", "verifier")
    );

    private final LLMService llmService;
    private final ExpertRegistry registry;

    public Gate(LLMService llmService, ExpertRegistry registry) {
        this.llmService = llmService;
        this.registry = registry;
    }

    public Gate() { this(null, null); }

    /**
     * 确定性优先路由
     * <p>
     * 优先级：1.特殊任务快速路由 → 2.gap路由 → 3.关键词匹配 → 4.LLM兜底
     * </p>
     * @origin Python: core.gate.Gate.classify(user_input, memory_context, gap) -> dict
     */
    public Map<String, Object> classify(String userInput, String memoryContext,
                                          GapDetector.Gap gap) {
        String lower = userInput.toLowerCase();

        // 优先级1：特殊任务快速路由
        if (userInput.contains("[图片:") || lower.contains("[image:")) {
            return quickRoute("vision", userInput, "keyword");
        }

        List<String> chatSignals = List.of("你好", "hello", "hi", "什么是", "解释一下", "为什么", "怎么理解", "帮我理解", "告诉我");
        if (chatSignals.stream().anyMatch(lower::contains) && !hasCodeSignal(lower)) {
            return quickRoute("chat", userInput, "keyword");
        }

        List<String> officeSignals = List.of(".xlsx", ".docx", ".pptx", "excel", "word文档", "ppt", "幻灯片", "演示文稿", "汇报");
        if (officeSignals.stream().anyMatch(lower::contains)) {
            return quickRoute("office", userInput, "keyword");
        }

        // 优先级2：测试gap路由（confidence >= 0.7）
        if (gap != null && gap.gapType() != GapDetector.GapType.UNKNOWN
            && gap.gapType() != GapDetector.GapType.NONE && gap.confidence() >= 0.7) {
            String expertType = GapDetector.GAP_TO_EXPERT_TYPE.get(gap.gapType());
            if (expertType != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("expert_type", expertType);
                result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
                result.put("difficulty", computeDifficultyFromGap(gap));
                result.put("routing_source", "gap_detector");
                result.put("confidence", gap.confidence());
                result.put("needs_search", false);
                result.put("subtask_hint", "");

                // 消解用户意图和gap的冲突
                String keywordType = keywordClassify(lower);
                if (keywordType != null && !keywordType.equals(expertType)) {
                    String resolved = resolveIntentVsGap(keywordType, gap);
                    result.put("expert_type", resolved);
                    if (!resolved.equals(expertType)) result.put("routing_source", "conflict_user_wins");
                }
                return injectRegistry(result, userInput);
            }
        }

        // 优先级3：关键词匹配
        String keywordResult = keywordClassify(lower);
        if (keywordResult != null) {
            double confidence = keywordConfidence(lower, keywordResult);
            if (confidence >= 0.75) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("expert_type", keywordResult);
                result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
                result.put("difficulty", "easy");
                result.put("routing_source", "keyword");
                result.put("confidence", confidence);
                result.put("needs_search", needsSearch(lower));
                result.put("subtask_hint", "");
                return injectRegistry(result, userInput);
            }
        }

        // 优先级4：LLM兜底二分类
        Map<String, Object> llmResult = llmFallbackClassify(userInput);
        if (llmResult != null) {
            return injectRegistry(llmResult, userInput);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expert_type", "chat");
        result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
        result.put("difficulty", "easy");
        result.put("routing_source", "llm_fallback");
        result.put("confidence", 0.3);
        result.put("needs_search", false);
        result.put("subtask_hint", "");
        return injectRegistry(result, userInput);
    }

    /**
     * LLM兜底二分类
     * <p>
     * 只做最简单的二分类：新建文件(create) vs 修改文件(modify)。
     * 与Python原始实现对齐，LLM只做最简兜底，不做完整5分类。
     * </p>
     * @origin Python: core.gate.Gate._llm_minimal_classify(user_input)
     */
    private Map<String, Object> llmFallbackClassify(String userInput) {
        if (llmService == null) return null;

        try {
            String prompt = "判断这个任务是\"新建文件\"还是\"修改文件\"，只输出一个JSON：\n" +
                "{\"action\": \"create\"} 或 {\"action\": \"modify\"}\n\n" +
                "任务：" + userInput.substring(0, Math.min(200, userInput.length()));

            String response = llmService.generateForExpert("gate", prompt,
                "你是任务分类器，只输出JSON，不要解释。", 30);

            if (response == null || response.isEmpty()) return null;

            return parseLlmBinaryResponse(response, userInput);
        } catch (Exception e) {
            log.debug("[gate] LLM fallback classify failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析LLM二分类响应（create/modify）
     * @origin Python: core.gate.Gate._llm_minimal_classify() JSON解析部分
     */
    private Map<String, Object> parseLlmBinaryResponse(String response, String userInput) {
        try {
            String json = response;
            int braceStart = response.indexOf('{');
            int braceEnd = response.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = response.substring(braceStart, braceEnd + 1);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = MAPPER.readValue(json, Map.class);

            if (parsed.containsKey("action")) {
                String action = (String) parsed.get("action");
                String expertType = "create".equals(action) ? "codegen" : "locator_repair";

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("expert_type", expertType);
                result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
                result.put("difficulty", "easy");
                result.put("routing_source", "llm_fallback");
                result.put("confidence", 0.55);
                result.put("needs_search", false);
                result.put("subtask_hint", "");

                log.info("[gate] LLM binary classified as {} (action={})", expertType, action);
                return result;
            }

            String expertType = (String) parsed.get("expert_type");
            if (expertType != null && VALID_EXPERT_TYPES.contains(expertType)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("expert_type", expertType);
                result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
                result.put("difficulty", parsed.getOrDefault("difficulty", "easy"));
                result.put("routing_source", "llm_fallback");
                result.put("confidence", 0.55);
                result.put("needs_search", false);
                result.put("subtask_hint", "");
                return result;
            }

            return null;
        } catch (Exception e) {
            log.debug("[gate] LLM response parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ── 辅助方法 ──

    private Map<String, Object> quickRoute(String expertType, String userInput, String source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expert_type", expertType);
        result.put("task_summary", userInput.substring(0, Math.min(10, userInput.length())));
        result.put("difficulty", "easy");
        result.put("routing_source", source);
        result.put("confidence", 0.95);
        result.put("needs_search", false);
        result.put("subtask_hint", "");
        result.put("expert_name", null);
        result.put("route_type", "general");
        return result;
    }

    private boolean hasCodeSignal(String lower) {
        List<String> codeSignals = List.of(".py", ".js", ".ts", ".go", ".rs", ".java",
            "函数", "方法", "类", "接口", "bug", "修复", "实现", "创建", "生成", "重构", "代码");
        return codeSignals.stream().anyMatch(lower::contains);
    }

    private String keywordClassify(String lower) {
        List<String> repairSignals = List.of("修复", "fix", "bug", "报错", "错误", "失败", "不工作", "broken", "error");
        if (repairSignals.stream().anyMatch(lower::contains)) return "locator_repair";

        List<String> createSignals = List.of("写一个", "创建", "生成", "新建", "from scratch", "写个", "实现一个", "generate", "create");
        if (createSignals.stream().anyMatch(lower::contains)) return "codegen";

        List<String> refactorSignals = List.of("重构", "优化", "整理", "拆分", "extract", "rename", "refactor");
        if (refactorSignals.stream().anyMatch(lower::contains)) return "refactor";

        List<String> docSignals = List.of("文档", "注释", "docstring", "readme", "doc");
        if (docSignals.stream().anyMatch(lower::contains)) return "doc";

        return null;
    }

    private double keywordConfidence(String lower, String expertType) {
        Map<String, List<String>> strongSignals = Map.of(
            "locator_repair", List.of("修复", "fix", "bug", "报错", "错误", "失败", ".py:", "line "),
            "codegen", List.of("写一个", "创建", "生成", "新建", "from scratch", "写个"),
            "refactor", List.of("重构", "优化", "整理", "拆分", "extract", "rename"),
            "doc", List.of("文档", "注释", "docstring", "readme")
        );
        List<String> signals = strongSignals.getOrDefault(expertType, List.of());
        long matched = signals.stream().filter(lower::contains).count();
        if (matched >= 2) return 0.92;
        if (matched == 1) return 0.75;
        return 0.55;
    }

    private String resolveIntentVsGap(String userExpert, GapDetector.Gap gap) {
        String gapExpert = GapDetector.GAP_TO_EXPERT_TYPE.getOrDefault(gap.gapType(), userExpert);
        if (gap.confidence() >= 0.85) return gapExpert;
        if (gap.confidence() >= 0.5) return gapExpert.equals(userExpert) ? gapExpert : userExpert;
        return userExpert;
    }

    private String computeDifficultyFromGap(GapDetector.Gap gap) {
        if (gap.files().size() > 2) return "hard";
        if (gap.gapType() == GapDetector.GapType.LOGIC_ERROR || gap.gapType() == GapDetector.GapType.NOT_IMPLEMENTED) return "hard";
        return "easy";
    }

    private boolean needsSearch(String lower) {
        List<String> searchSignals = List.of("天气", "气温", "weather", "股价", "汇率", "新闻", "最新", "最近", "today", "latest");
        return searchSignals.stream().anyMatch(lower::contains);
    }

    private Map<String, Object> injectRegistry(Map<String, Object> result, String userInput) {
        result.putIfAbsent("expert_name", null);
        result.putIfAbsent("route_type", "general");

        if (registry != null) {
            ExpertRegistry.MatchResult match = registry.match(userInput);
            if (match != null) {
                result.put("expert_name", match.name());
                if (!result.containsKey("confidence") || match.confidence() > ((Number) result.getOrDefault("confidence", 0)).doubleValue()) {
                    result.put("confidence", match.confidence());
                }
                @SuppressWarnings("unchecked")
                List<String> pipeline = (List<String>) match.expert().get("pipeline");
                if (pipeline != null) result.put("pipeline", pipeline);

                String instructions = (String) match.expert().getOrDefault("instructions", match.expert().get("system_prompt"));
                if (instructions != null) result.put("system_prompt", instructions);
            }
        }
        result.putIfAbsent("confidence", 0.55);
        return result;
    }
}
