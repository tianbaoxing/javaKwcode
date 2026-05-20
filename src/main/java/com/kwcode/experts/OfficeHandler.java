package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Office文档处理专家 - 处理xlsx/docx/pptx等办公文档
 * <p>
 * 当用户输入涉及Office文档时，OfficeHandler负责：
 * 1. 识别文档类型
 * 2. 读取文档内容
 * 3. 根据用户需求修改或生成文档
 * </p>
 * <p>
 * 触发条件：用户输入包含 .xlsx/.docx/.pptx/excel/word/ppt 等关键词
 * </p>
 * @origin Python: experts.office_handler.OfficeHandler
 */
public class OfficeHandler implements Expert {

    private static final Logger log = LoggerFactory.getLogger(OfficeHandler.class);

    private static final Map<String, String> DOC_TYPE_MAP = Map.of(
        ".xlsx", "excel",
        ".xls", "excel",
        ".csv", "excel",
        ".docx", "word",
        ".doc", "word",
        ".pptx", "powerpoint",
        ".ppt", "powerpoint"
    );

    private static final String OFFICE_PROMPT =
        "你是一个Office文档处理专家。请根据用户需求处理文档。\n\n" +
        "文档类型：%s\n用户需求：%s\n\n" +
        "要求：\n" +
        "1. 生成正确的文档操作指令\n" +
        "2. 保持原有格式和样式\n" +
        "3. 如果是数据操作，确保数据完整性";

    private final LLMService llmService;

    public OfficeHandler(LLMService llmService) {
        this.llmService = llmService;
    }

    public OfficeHandler() {
        this.llmService = null;
    }

    @Override
    public String name() {
        return "office";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return handle(ctx);
    }

    /**
     * 处理Office文档
     * <p>
     * 识别文档类型，生成处理指令。
     * </p>
     * @origin Python: experts.office_handler.OfficeHandler.handle(ctx) -> dict
     * @param ctx 任务上下文
     * @return 处理结果
     */
    public ExpertResult handle(TaskContext ctx) {
        String docType = detectDocType(ctx.userInput);

        if (docType == null) {
            return ExpertResult.fail("Cannot detect office document type from input");
        }

        try {
            String result;
            if (llmService != null) {
                String prompt = String.format(OFFICE_PROMPT, docType, ctx.userInput);
                result = llmService.generateForExpert("office", prompt,
                    "你是Office文档处理专家。", 2000);
            } else {
                result = "Document type: " + docType + ". Processing requires LLM service.";
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("doc_type", docType);

            log.info("[office_handler] Handled {} document", docType);

            return ExpertResult.ok(result, metadata);

        } catch (Exception e) {
            log.warn("[office_handler] Failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    /**
     * 检测文档类型
     */
    private String detectDocType(String input) {
        String lower = input.toLowerCase();

        for (var entry : DOC_TYPE_MAP.entrySet()) {
            if (lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        if (lower.contains("excel") || lower.contains("表格") || lower.contains("spreadsheet")) return "excel";
        if (lower.contains("word") || lower.contains("文档") || lower.contains("报告")) return "word";
        if (lower.contains("ppt") || lower.contains("幻灯片") || lower.contains("演示")) return "powerpoint";

        return null;
    }
}
