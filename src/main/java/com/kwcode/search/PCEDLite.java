package com.kwcode.search;

import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * PCED精简版 - Prompt-Centric Evidence Distillation
 * <p>
 * 1次LLM调用，从搜索结果中蒸馏出与prompt直接相关的证据。
 * 理论来源：PCED (2025) - 以prompt为中心的证据蒸馏。
 * </p>
 * <p>
 * 与ContextCompressor的区别：
 * - ContextCompressor: 通用压缩，保留所有相关信息
 * - PCEDLite: 以prompt为中心，只保留直接回答prompt的证据
 * </p>
 * @origin Python: search/pced_lite.PCEDLite
 */
public class PCEDLite {

    private static final Logger log = LoggerFactory.getLogger(PCEDLite.class);

    private static final String DISTILL_PROMPT =
        "你是一个证据蒸馏专家。从搜索结果中提取直接回答问题的证据。\n\n" +
        "问题：%s\n\n搜索结果：\n%s\n\n要求：\n" +
        "1. 只提取直接回答问题的证据，忽略无关信息\n" +
        "2. 保留具体的代码片段、函数签名、配置项\n" +
        "3. 输出不超过300字\n" +
        "4. 如果搜索结果中没有直接证据，输出'无相关证据'";

    private static final int MAX_EVIDENCE_LENGTH = 300;

    private final LLMService llmService;

    public PCEDLite(LLMService llmService) {
        this.llmService = llmService;
    }

    public PCEDLite() {
        this.llmService = null;
    }

    /**
     * 蒸馏证据
     * <p>
     * 1次LLM调用，从搜索结果中提取与prompt直接相关的证据。
     * 失败时降级返回截断的原始结果。
     * </p>
     * @origin Python: search/pced_lite.PCEDLite.distill(prompt, search_results) -> str
     * @param prompt 用户问题/prompt
     * @param searchResults 搜索结果文本
     * @return 蒸馏后的证据文本
     */
    public String distill(String prompt, String searchResults) {
        if (searchResults == null || searchResults.isEmpty()) return "";
        if (prompt == null || prompt.isEmpty()) prompt = "总结搜索结果";

        if (searchResults.length() <= MAX_EVIDENCE_LENGTH) {
            return searchResults;
        }

        if (llmService != null) {
            try {
                String fullPrompt = String.format(DISTILL_PROMPT,
                    prompt.substring(0, Math.min(200, prompt.length())),
                    searchResults.substring(0, Math.min(2000, searchResults.length())));

                String result = llmService.generateForExpert("pced", fullPrompt,
                    "你是证据蒸馏专家，只提取直接回答问题的证据。", 400);

                if (result != null && !result.isEmpty() && !result.contains("无相关证据")) {
                    return result.length() > MAX_EVIDENCE_LENGTH
                        ? result.substring(0, MAX_EVIDENCE_LENGTH)
                        : result;
                }
            } catch (Exception e) {
                log.debug("[pced] LLM distillation failed (降级): {}", e.getMessage());
            }
        }

        return searchResults.substring(0, Math.min(MAX_EVIDENCE_LENGTH, searchResults.length()));
    }

    /**
     * 批量蒸馏多个搜索结果
     * @origin Python: search/pced_lite.PCEDLite.distill_batch(prompt, results_list) -> list[str]
     * @param prompt 用户问题
     * @param resultsList 搜索结果列表
     * @return 蒸馏后的证据列表
     */
    public List<String> distillBatch(String prompt, List<String> resultsList) {
        List<String> distilled = new ArrayList<>();
        for (String result : resultsList) {
            distilled.add(distill(prompt, result));
        }
        return distilled;
    }
}
