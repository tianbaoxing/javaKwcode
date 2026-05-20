package com.kwcode.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 查询生成器：一次LLM调用，生成2-3条英文搜索query
 * LLM自动决定site:限定（第一条query），不需要额外API
 * 意图影响query风格，不影响搜索引擎选择
 * @origin Python: search/query_generator.py
 */
public class QueryGenerator {

    private static final Logger log = LoggerFactory.getLogger(QueryGenerator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 查询生成prompt模板 */
    private static final String QUERY_GEN_PROMPT =
        "You are a search query generator for a coding agent.\n" +
        "Given the user's coding task and intent, generate 2-3 concise English search queries.\n\n" +
        "Rules:\n- Each query should be 5-12 words\n- %s\n" +
        "- For the FIRST query: decide which site(s) would most likely have the answer,\n" +
        "  and append site restriction. Use your judgment — examples only:\n" +
        "  research/papers/frontier → site:arxiv.org OR site:semanticscholar.org\n" +
        "  open source code/models → site:github.com OR site:huggingface.co\n" +
        "  python packages → site:pypi.org OR site:docs.python.org\n" +
        "  general dev questions → site:stackoverflow.com\n" +
        "  If none applies clearly, do NOT add site restriction.\n" +
        "- For the remaining queries: broader searches without site restriction\n" +
        "- Output ONLY a JSON array of strings, no explanation.\n\n" +
        "User task: %s\nVerifier feedback: %s";

    /** 意图→query方向提示 */
    private static final Map<String, String> DIRECTION_MAP = Map.ofEntries(
        Map.entry("code_search", "Generate queries that will find code implementations, GitHub repos, or technical solutions"),
        Map.entry("academic", "Generate queries that will find research papers, algorithms, or theoretical foundations"),
        Map.entry("package", "Generate queries to find specific packages/libraries and their documentation"),
        Map.entry("debug", "Generate queries focused on error messages and fixes. Include the exact error text"),
        Map.entry("general", "Focus on practical coding solutions"),
        Map.entry("realtime", "Generate queries for real-time data (weather, prices, news). First query should target authoritative data sources"),
        Map.entry("github", "Include 'github' or 'repository' in at least one query"),
        Map.entry("arxiv", "Include 'arxiv' or 'paper' in at least one query"),
        Map.entry("pypi", "Include 'python package' or 'pip install' in at least one query"),
        Map.entry("bug", "Include 'fix' or 'solution' in at least one query")
    );

    /** 注入攻击检测正则 */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore\\s+previous", Pattern.CASE_INSENSITIVE),
        Pattern.compile("system\\s*:", Pattern.CASE_INSENSITIVE),
        Pattern.compile("<\\|.*?\\|>"),
        Pattern.compile("\\[INST\\]")
    );

    /** LLM后端（可选） */
    private IntentClassifier.LlmBackend llm;

    /**
     * 构造函数（无LLM）
     */
    public QueryGenerator() {
        this.llm = null;
    }

    /**
     * 构造函数（带LLM）
     * @param llm LLM后端
     */
    public QueryGenerator(IntentClassifier.LlmBackend llm) {
        this.llm = llm;
    }

    /**
     * 生成2-3条英文搜索query
     * @param taskText 任务文本
     * @param feedback 验证器反馈
     * @param intent 意图类型
     * @return 搜索query列表
     */
    public List<String> generate(String taskText, String feedback, String intent) {
        String direction = DIRECTION_MAP.getOrDefault(intent, DIRECTION_MAP.get("general"));

        // 如果没有LLM，直接返回fallback
        if (llm == null) {
            String fallback = taskText.trim().substring(0, Math.min(100, taskText.trim().length()));
            return fallback.isEmpty() ? List.of("python coding help") : List.of(fallback);
        }

        String prompt = String.format(QUERY_GEN_PROMPT,
            direction,
            taskText,
            feedback != null ? feedback.substring(0, Math.min(500, feedback.length())) : "");

        try {
            String raw = llm.generate(prompt, 256, 0.3);
            List<String> queries = parseQueries(raw);
            if (!queries.isEmpty()) {
                // 安全过滤
                queries = queries.stream().limit(3).map(QueryGenerator::cleanQuery)
                    .filter(q -> !q.isEmpty()).toList();
                if (!queries.isEmpty()) return queries;
            }
        } catch (Exception e) {
            log.warn("[query_gen] LLM调用失败: {}", e.getMessage());
        }

        // 回退：直接用task构造一条query
        String fallback = taskText.trim().substring(0, Math.min(100, taskText.trim().length()));
        return fallback.isEmpty() ? List.of("python coding help") : List.of(fallback);
    }

    /**
     * 生成搜索query（纯文本调用）
     * @param taskText 任务文本
     * @param intent 意图类型
     * @return 搜索query列表
     */
    public List<String> generate(String taskText, String intent) {
        return generate(taskText, "", intent);
    }

    /**
     * 从LLM输出中提取JSON数组
     * @param raw LLM原始输出
     * @return query列表
     */
    private static List<String> parseQueries(String raw) {
        // 去掉markdown代码块标记
        String cleaned = raw.replaceAll("```(?:json)?\\s*", "").trim().replaceAll("`+$", "");
        try {
            List<?> result = MAPPER.readValue(cleaned, List.class);
            return result.stream()
                .map(Object::toString)
                .filter(s -> !s.trim().isEmpty())
                .map(String::trim)
                .toList();
        } catch (JsonProcessingException e) {
            // 尝试逐行提取带引号的字符串
            List<String> lines = new ArrayList<>();
            var m = Pattern.compile("\"([^\"]+)\"").matcher(raw);
            while (m.find()) lines.add(m.group(1));
            return lines;
        }
    }

    /**
     * 安全过滤：去掉可能的prompt injection或无效字符
     * 保留site:限定（这是合法的搜索语法）
     * @param query 待过滤的query
     * @return 清理后的query，无效返回空字符串
     */
    private static String cleanQuery(String query) {
        // 去掉控制字符
        query = query.replaceAll("[\\x00-\\x1f\\x7f]", "");
        // 截断过长query
        query = query.substring(0, Math.min(256, query.length()));
        // 检测injection尝试
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(query).find()) {
                log.warn("[query_gen] 阻止可疑query: {}", query.substring(0, Math.min(50, query.length())));
                return "";
            }
        }
        return query.trim();
    }
}
