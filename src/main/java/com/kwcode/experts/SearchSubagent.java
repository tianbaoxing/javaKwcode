package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.experts.SearchAugmentor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 搜索子代理 - 跨文件搜索并收集上下文信息
 * <p>
 * 与SearchAugmentor的区别：
 * - SearchAugmentor: 搜索外部信息（DuckDuckGo）
 * - SearchSubagent: 搜索项目内部代码（grep/AST）
 * </p>
 * <p>
 * 在多任务编排中，SearchSubagent作为前置任务，
 * 为后续任务提供跨文件契约和上下文。
 * </p>
 * @origin Python: experts.search_subagent.SearchSubagent
 */
public class SearchSubagent implements Expert {

    private static final Logger log = LoggerFactory.getLogger(SearchSubagent.class);

    private final SearchAugmentor searchAugmentor;

    public SearchSubagent(SearchAugmentor searchAugmentor) {
        this.searchAugmentor = searchAugmentor;
    }

    public SearchSubagent() {
        this.searchAugmentor = null;
    }

    @Override
    public String name() {
        return "search_subagent";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return search(ctx);
    }

    /**
     * 执行项目内搜索
     * <p>
     * 搜索策略：
     * 1. 从用户输入提取搜索关键词
     * 2. 在项目中grep搜索相关代码
     * 3. 如果有外部搜索需求，调用SearchAugmentor
     * 4. 将搜索结果写入ctx.searchResults
     * </p>
     * @origin Python: experts.search_subagent.SearchSubagent.search(ctx) -> dict
     * @param ctx 任务上下文
     * @return 搜索结果
     */
    public ExpertResult search(TaskContext ctx) {
        try {
            List<String> results = new ArrayList<>();

            if (ctx.gateResult != null) {
                Boolean needsSearch = (Boolean) ctx.gateResult.get("needs_search");
                if (needsSearch != null && needsSearch && searchAugmentor != null) {
                    String externalResults = searchAugmentor.search(ctx);
                    if (!externalResults.isEmpty()) {
                        results.add("## 外部搜索结果\n" + externalResults);
                    }
                }
            }

            if (ctx.locatorOutput != null && !ctx.locatorOutput.relevantFiles().isEmpty()) {
                results.add("## 相关文件\n" + String.join("\n", ctx.locatorOutput.relevantFiles()));
            }

            if (!ctx.relevantCodeSnippets.isEmpty()) {
                results.add("## 代码片段");
                for (var entry : ctx.relevantCodeSnippets.entrySet()) {
                    results.add("### " + entry.getKey() + "\n" +
                        (entry.getValue().length() > 500 ? entry.getValue().substring(0, 500) + "..." : entry.getValue()));
                }
            }

            String searchResult = String.join("\n\n", results);
            ctx.searchResults = searchResult;
            ctx.searchTriggered = true;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("has_results", !searchResult.isEmpty());
            metadata.put("result_length", searchResult.length());

            log.info("[search_subagent] Search completed, result_length={}", searchResult.length());
            return ExpertResult.ok(searchResult, metadata);

        } catch (Exception e) {
            log.warn("[search_subagent] Search failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }
}
