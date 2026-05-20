package com.kwcode.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Cross-Encoder搜索结果重排
 * 用CPU推理的轻量reranker，不需要GPU
 * FLEX-2：CPU性能低时跳过，只用BM25
 * Java版：使用HTTP API调用Python reranker服务（如需要），或跳过重排
 * @origin Python: search/reranker.py
 */
public class Reranker {

    private static final Logger log = LoggerFactory.getLogger(Reranker.class);

    /** 默认top-k */
    private static final int DEFAULT_TOP_K = 3;

    /** 重排超时阈值（毫秒） */
    private static final long RERANK_TIMEOUT_MS = 2000;

    /** 是否已禁用（加载失败或太慢时跳过） */
    private boolean disabled = false;

    /**
     * 对搜索结果进行重排
     * Java版暂不加载sentence-transformers模型（需要Python环境），
     * 降级返回原始顺序（FLEX-2策略）
     * @param query 查询文本
     * @param results 搜索结果列表
     * @param topK 返回前K条
     * @return 重排后的结果列表
     */
    public List<Map<String, String>> rerank(String query, List<Map<String, String>> results, int topK) {
        if (disabled || results == null || results.isEmpty()) {
            return results != null ? results.subList(0, Math.min(topK, results.size())) : List.of();
        }

        // Java版降级：直接按原始顺序返回（FLEX-2）
        // 如果需要真正的重排，可以通过HTTP调用Python reranker微服务
        log.debug("[reranker] Java版暂不支持Cross-Encoder重排，降级返回原始顺序");
        return results.subList(0, Math.min(topK, results.size()));
    }

    /**
     * 对搜索结果进行重排（默认top-3）
     * @param query 查询文本
     * @param results 搜索结果列表
     * @return 重排后的结果列表
     */
    public List<Map<String, String>> rerank(String query, List<Map<String, String>> results) {
        return rerank(query, results, DEFAULT_TOP_K);
    }

    /**
     * 检查重排器是否可用
     * @return 是否可用
     */
    public boolean isAvailable() {
        return !disabled;
    }
}
