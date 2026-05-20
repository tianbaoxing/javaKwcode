package com.kwcode.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 意图分类器：关键词快速匹配 + LLM语义fallback
 * 将用户输入分类为 code_search / academic / package / debug / general
 * v0.6.2: 增强关键词覆盖 + LLM fallback 分类
 * @origin Python: search/intent_classifier.py
 */
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 关键词→意图映射（优先级从上到下，首次命中即返回） */
    private static final List<Map.Entry<String, Pattern>> INTENT_PATTERNS = buildPatterns();

    /** LLM分类prompt */
    private static final String LLM_CLASSIFY_PROMPT =
        "你是搜索意图分类器。根据用户问题，判断应该搜索什么类型的数据源。\n\n" +
        "分类选项：\n- code_search：找代码实现、开源项目、最佳实践、框架对比\n" +
        "- academic：找论文、算法原理、学术研究、理论证明\n" +
        "- package：找软件包、库、依赖、安装方法\n" +
        "- debug：修bug、解决报错、排查问题\n" +
        "- general：通用问题、天气、新闻、其他\n\n" +
        "用户问题：%s\n\n只返回一个分类名称，不要解释。";

    /** 合法的意图类型 */
    private static final Set<String> VALID_INTENTS = Set.of(
        "code_search", "academic", "package", "debug", "general"
    );

    /** LLM后端（可选） */
    private LlmBackend llm;

    /**
     * LLM后端接口
     */
    public interface LlmBackend {
        String generate(String prompt, int maxTokens, double temperature);
    }

    /**
     * 构造函数（无LLM fallback）
     */
    public IntentClassifier() {
        this.llm = null;
    }

    /**
     * 构造函数（带LLM fallback）
     * @param llm LLM后端
     */
    public IntentClassifier(LlmBackend llm) {
        this.llm = llm;
    }

    /**
     * 对用户输入做意图分类
     * 流程：1. 关键词快速匹配(<1ms) → 命中直接返回  2. LLM fallback(如果提供了llm)
     * @param userInput 用户输入
     * @param taskSummary 任务摘要（可选）
     * @return 意图类型：code_search|academic|package|debug|general
     */
    public String classify(String userInput, String taskSummary) {
        String combined = userInput + " " + (taskSummary != null ? taskSummary : "");

        // Level 1: 关键词快速匹配
        for (var entry : INTENT_PATTERNS) {
            if (entry.getValue().matcher(combined).find()) {
                log.debug("[intent] 关键词匹配: {}", entry.getKey());
                return entry.getKey();
            }
        }

        // Level 2: LLM语义分类（可选）
        if (llm != null) {
            String result = llmClassify(userInput);
            if (result != null) return result;
        }

        return "general";
    }

    /**
     * 对用户输入做意图分类（无任务摘要）
     * @param userInput 用户输入
     * @return 意图类型
     */
    public String classify(String userInput) {
        return classify(userInput, "");
    }

    /**
     * LLM语义分类fallback
     * @param userInput 用户输入
     * @return 意图类型，失败返回null
     */
    private String llmClassify(String userInput) {
        try {
            String prompt = String.format(LLM_CLASSIFY_PROMPT,
                userInput.substring(0, Math.min(200, userInput.length())));
            String raw = llm.generate(prompt, 20, 0.0);
            String result = raw.trim().toLowerCase().replace("\"", "").replace("'", "");
            for (String intent : VALID_INTENTS) {
                if (result.contains(intent)) {
                    log.debug("[intent] LLM分类: {}", intent);
                    return intent;
                }
            }
        } catch (Exception e) {
            log.debug("[intent] LLM分类失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构建预编译的意图关键词正则
     */
    private static List<Map.Entry<String, Pattern>> buildPatterns() {
        // 关键词定义
        Map<String, List<String>> intentKeywords = new LinkedHashMap<>();
        intentKeywords.put("debug", List.of(
            "报错", "error", "bug", "fix", "失败", "异常", "traceback",
            "crash", "segfault", "panic", "exception", "stack trace",
            "不工作", "出错", "修复", "解决"
        ));
        intentKeywords.put("code_search", List.of(
            "开源", "github", "仓库", "repo", "star", "框架推荐",
            "最佳实践", "best practice", "实现方案", "怎么实现",
            "有没有库", "有没有工具", "推荐一个", "哪个框架",
            "源码", "source code", "示例代码", "example",
            "最优解", "算法实现", "设计模式"
        ));
        intentKeywords.put("academic", List.of(
            "论文", "paper", "arxiv", "研究", "survey",
            "算法原理", "理论", "证明", "公式",
            "state of the art", "sota", "benchmark",
            "学术", "文献", "引用", "citation"
        ));
        intentKeywords.put("package", List.of(
            "库", "package", "pip", "安装", "依赖",
            "npm", "cargo", "gem", "maven",
            "版本", "version", "兼容", "compatible",
            "pip install", "requirements"
        ));

        List<Map.Entry<String, Pattern>> patterns = new ArrayList<>();
        for (var entry : intentKeywords.entrySet()) {
            String intent = entry.getKey();
            String regex = String.join("|", entry.getValue().stream()
                .map(Pattern::quote).toList());
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            patterns.add(Map.entry(intent, pattern));
        }
        return patterns;
    }
}
