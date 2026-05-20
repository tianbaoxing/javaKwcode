package com.kwcode.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * ContextCompressor: 一次LLM调用，将多页面正文压缩为≤400字摘要注入context
 * @origin Python: search/context_compressor.py
 */
public class ContextCompressor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressor.class);

    /** 压缩prompt模板 */
    private static final String COMPRESS_PROMPT =
        "你是信息提炼专家。从搜索结果中提取对解决任务最有用的信息。\n\n" +
        "当前任务：%s\n\n搜索内容：\n%s\n\n要求：\n" +
        "1. 只保留和任务直接相关的信息\n2. 输出不超过400字\n" +
        "3. 保留具体代码片段、函数名、配置项、版本号等关键细节\n" +
        "4. 用中文总结，保留英文技术术语\n5. 直接输出文字，不要加标题";

    /** LLM后端 */
    private final IntentClassifier.LlmBackend llm;

    /**
     * 构造函数
     * @param llm LLM后端
     */
    public ContextCompressor(IntentClassifier.LlmBackend llm) {
        this.llm = llm;
    }

    /**
     * 将多个页面正文压缩为一段摘要
     * ≤500字直接截断返回，省掉一次LLM调用
     * @param task 当前任务描述
     * @param contents 多个页面正文列表
     * @return 压缩后的摘要文本
     */
    public String compress(String task, List<String> contents) {
        List<String> valid = contents.stream()
            .filter(c -> c != null && !c.isBlank())
            .toList();

        if (valid.isEmpty()) return "";

        String combined = String.join("\n\n", valid);

        // 内容够短，直接截断返回，不浪费LLM调用
        if (combined.length() <= 500) {
            return combined.substring(0, Math.min(400, combined.length()));
        }

        // 内容多才调LLM压缩
        String prompt = String.format(COMPRESS_PROMPT,
            task,
            combined.substring(0, Math.min(3000, combined.length())));

        try {
            String result = llm.generate(prompt, 600, 0.0).trim();
            // 硬截断：LLM可能超出400字限制
            if (result.length() > 400) {
                result = result.substring(0, 400);
            }
            return result;
        } catch (Exception e) {
            log.warn("[compressor] LLM失败: {}，返回原始截断", e.getMessage());
            return combined.substring(0, Math.min(400, combined.length()));
        }
    }
}
