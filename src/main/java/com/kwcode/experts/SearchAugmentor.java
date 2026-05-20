package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import com.kwcode.search.DuckDuckGoSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 搜索增强专家 - 搜索+LLM提取关键信息
 * <p>
 * 红线：
 * SEARCH-RED-1: 零外部API key（SearXNG本地Docker / DuckDuckGo HTML）
 * SEARCH-RED-3: 失败不中断主流程，返回空字符串
 * SEARCH-RED-4: 总耗时≤15s
 * </p>
 * @origin Python: experts.search_augmentor.SearchAugmentorExpert
 */
public class SearchAugmentor {

    private static final Logger log = LoggerFactory.getLogger(SearchAugmentor.class);
    private static final int MAX_SEARCH_SECONDS = 15;
    private static final int MAX_RESULTS = 5;

    private final LLMService llmService;
    private final DuckDuckGoSearch searchEngine;

    public SearchAugmentor(LLMService llmService) {
        this.llmService = llmService;
        this.searchEngine = new DuckDuckGoSearch();
    }

    public SearchAugmentor(LLMService llmService, DuckDuckGoSearch searchEngine) {
        this.llmService = llmService;
        this.searchEngine = searchEngine;
    }

    public SearchAugmentor() { this(null); }

    /**
     * 完整搜索流水线（供重试路径使用）
     * <p>
     * 任何异常返回空字符串，不阻塞流水线
     * </p>
     * @origin Python: experts.search_augmentor.SearchAugmentorExpert.search(ctx) -> str
     */
    public String search(TaskContext ctx) {
        long t0 = System.currentTimeMillis();
        try {
            String query = cleanQuery(ctx.userInput.substring(0, Math.min(120, ctx.userInput.length())));
            if (query.length() < 4) return "";

            // 搜索（Phase 3实现SearXNG/DuckDuckGo集成）
            String raw = searchAndCollect(query, t0);
            if (raw.isEmpty()) return "";

            // LLM提取关键信息
            return extract(query, raw);
        } catch (Exception e) {
            log.debug("[search] pipeline error (静默): {}", e.getMessage());
            return "";
        }
    }

    /**
     * 简化搜索（供ChatExpert使用）
     * @origin Python: experts.search_augmentor.SearchAugmentorExpert.search_only(query) -> str
     */
    public String searchOnly(String query) {
        try {
            String cleanQ = cleanQuery(query);
            log.info("[search_only] query: {}", cleanQ);
            String raw = searchAndCollect(cleanQ, System.currentTimeMillis());
            if (raw.isEmpty()) return "";
            return extract(cleanQ, raw);
        } catch (Exception e) {
            log.warn("[search_only] failed: {}", e.getMessage());
            return "";
        }
    }

    /** 搜索并收集原始snippet */
    private String searchAndCollect(String query, long t0) {
        try {
            List<Map<String, String>> results = searchEngine.search(query, MAX_RESULTS, MAX_SEARCH_SECONDS);
            if (results.isEmpty()) {
                log.debug("[search] DuckDuckGo returned 0 results for: {}", query);
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (Map<String, String> r : results) {
                String title = r.getOrDefault("title", "");
                String snippet = r.getOrDefault("snippet", "");
                String url = r.getOrDefault("url", "");
                if (!title.isEmpty()) sb.append("## ").append(title).append("\n");
                if (!snippet.isEmpty()) sb.append(snippet).append("\n");
                if (!url.isEmpty()) sb.append("来源: ").append(url).append("\n");
                sb.append("\n");
            }

            long elapsed = (System.currentTimeMillis() - t0) / 1000;
            log.info("[search] DuckDuckGo returned {} results in {}s", results.size(), elapsed);
            return sb.toString();
        } catch (Exception e) {
            log.debug("[search] DuckDuckGo error (静默): {}", e.getMessage());
            return "";
        }
    }

    /** 用LLM从原始搜索结果中提取关键信息 */
    private String extract(String query, String rawResults) {
        if (rawResults.isEmpty()) return "";

        if (llmService != null) {
            try {
                String prompt = "从以下搜索结果中提取与问题相关的关键信息，只保留有用的部分：\n\n" +
                    "问题：" + query + "\n\n搜索结果：\n" +
                    (rawResults.length() > 3000 ? rawResults.substring(0, 3000) : rawResults);
                String extracted = llmService.generateForExpert("search_augmentor", prompt,
                    "你是信息提取专家，只提取与问题直接相关的关键信息，去除广告和无关内容。", 1000);
                if (extracted != null && !extracted.isEmpty()) {
                    return extracted.length() > 1500 ? extracted.substring(0, 1500) : extracted;
                }
            } catch (Exception e) {
                log.debug("[search] LLM extraction failed (降级返回原始): {}", e.getMessage());
            }
        }

        return rawResults.length() > 1500 ? rawResults.substring(0, 1500) : rawResults;
    }

    /** 清洗用户输入为搜索query */
    private String cleanQuery(String raw) {
        String q = raw.strip();
        String[] prefixes = {"你好", "你好呀", "嗨", "hi", "hello", "帮我", "请",
            "帮我搜索", "帮我查", "搜索一下", "搜一下", "查一下",
            "帮我看下", "帮我看看", "我想知道", "我想了解", "告诉我", "请问"};
        for (String prefix : prefixes) {
            if (q.startsWith(prefix)) {
                q = q.substring(prefix.length()).strip().replaceFirst("^[，, ]+", "");
            }
        }
        q = q.replaceAll("[？?！!。.~～]+$", "").strip();
        return q.length() >= 4 ? q : raw.strip();
    }
}
