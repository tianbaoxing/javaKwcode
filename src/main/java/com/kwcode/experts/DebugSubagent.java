package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 运行时调试子代理 - 分析Verifier失败输出，生成结构化调试信息
 * <p>
 * 只在Verifier失败时被PipelineOrchestrator调用。
 * 零副作用 — 只读分析，不修改任何文件。
 * </p>
 * <p>
 * 输出写入 ctx.debugInfo，供Generator重试时使用。
 * 理论来源："Debugging with LLM" (2025): LLM辅助debug比人工快3.2倍
 * </p>
 * @origin Python: experts.debug_subagent.DebugSubagent
 */
public class DebugSubagent implements Expert {

    private static final Logger log = LoggerFactory.getLogger(DebugSubagent.class);

    private static final String DEBUG_SYSTEM_PROMPT =
        "你是调试专家。分析错误信息，给出结构化诊断。\n" +
        "输出格式：\n" +
        "1. 错误类型：[syntax/import/runtime/assertion/other]\n" +
        "2. 错误位置：[文件:行号]\n" +
        "3. 根因分析：[一句话]\n" +
        "4. 修复建议：[具体操作]";

    private static final Pattern ERROR_LINE_PATTERN = Pattern.compile(
        "File \"([^\"]+)\", line (\\d+)|" +
        "at ([\\w.]+)\\(([^)]+):(\\d+)\\)|" +
        "(\\S+\\.\\w+):(\\d+):(\\d+):"
    );

    private static final Pattern ERROR_TYPE_PATTERN = Pattern.compile(
        "(\\w+Error|\\w+Exception|\\w+Warning):\\s*(.+?)(?:\\n|$)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern IMPORT_ERROR_PATTERN = Pattern.compile(
        "(?:ModuleNotFoundError|ImportError|No module named)\\s*:?\\s*(.+?)(?:\\n|$)",
        Pattern.CASE_INSENSITIVE
    );

    private final LLMService llmService;

    public DebugSubagent(LLMService llmService) {
        this.llmService = llmService;
    }

    public DebugSubagent() {
        this(null);
    }

    @Override
    public String name() {
        return "debug_subagent";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return debug(ctx);
    }

    /**
     * 调试Verifier失败输出
     * <p>
     * 1. 正则提取错误位置和类型（零LLM）
     * 2. 如果LLM可用，调用LLM生成诊断
     * 3. 写入ctx.debugInfo
     * </p>
     * @origin Python: experts.debug_subagent.DebugSubagent.debug(ctx) -> dict
     * @param ctx 任务上下文
     * @return 调试结果
     */
    public ExpertResult debug(TaskContext ctx) {
        String errorOutput = extractErrorOutput(ctx);
        if (errorOutput == null || errorOutput.isEmpty()) {
            ctx.debugInfo = "";
            return ExpertResult.ok("");
        }

        DebugInfo info = new DebugInfo();
        info.rawError = truncate(errorOutput, 2000);

        extractErrorLocation(errorOutput, info);
        extractErrorType(errorOutput, info);
        extractImportError(errorOutput, info);

        if (llmService != null) {
            try {
                String llmDiagnosis = callLlmForDiagnosis(ctx, errorOutput);
                if (llmDiagnosis != null && !llmDiagnosis.isEmpty()) {
                    info.llmDiagnosis = llmDiagnosis;
                }
            } catch (Exception e) {
                log.debug("[debug_subagent] LLM diagnosis failed (non-blocking): {}", e.getMessage());
            }
        }

        String debugText = formatDebugInfo(info);
        ctx.debugInfo = debugText;

        log.info("[debug_subagent] error_type={}, location={}, has_llm_diagnosis={}",
            info.errorType, info.errorLocation, info.llmDiagnosis != null);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("error_type", info.errorType != null ? info.errorType : "unknown");
        metadata.put("error_location", info.errorLocation != null ? info.errorLocation : "");
        metadata.put("is_import_error", info.isImportError);

        return ExpertResult.ok(debugText, metadata);
    }

    /**
     * 从上下文提取错误输出
     */
    private String extractErrorOutput(TaskContext ctx) {
        if (ctx.verifierOutput != null && ctx.verifierOutput.errorDetail() != null
            && !ctx.verifierOutput.errorDetail().isEmpty()) {
            return ctx.verifierOutput.errorDetail();
        }
        if (ctx.previousFailure != null && !ctx.previousFailure.isEmpty()) {
            return ctx.previousFailure;
        }
        return "";
    }

    /**
     * 正则提取错误位置
     */
    private void extractErrorLocation(String errorOutput, DebugInfo info) {
        Matcher m = ERROR_LINE_PATTERN.matcher(errorOutput);
        if (m.find()) {
            if (m.group(1) != null) {
                info.errorLocation = m.group(1) + ":" + m.group(2);
            } else if (m.group(3) != null) {
                info.errorLocation = m.group(4) + ":" + m.group(5);
            } else if (m.group(6) != null) {
                info.errorLocation = m.group(6) + ":" + m.group(7);
            }
        }
    }

    /**
     * 正则提取错误类型
     */
    private void extractErrorType(String errorOutput, DebugInfo info) {
        Matcher m = ERROR_TYPE_PATTERN.matcher(errorOutput);
        if (m.find()) {
            info.errorType = classifyError(m.group(1));
            info.errorMessage = truncate(m.group(2), 200);
        }
    }

    /**
     * 检测import错误
     */
    private void extractImportError(String errorOutput, DebugInfo info) {
        Matcher m = IMPORT_ERROR_PATTERN.matcher(errorOutput);
        if (m.find()) {
            info.isImportError = true;
            info.missingModule = truncate(m.group(1).trim(), 100);
        }
    }

    /**
     * 调用LLM生成诊断
     */
    private String callLlmForDiagnosis(TaskContext ctx, String errorOutput) {
        String prompt = "分析以下错误，给出结构化诊断：\n\n" +
            "## 用户任务\n" + truncate(ctx.userInput, 300) + "\n\n" +
            "## 错误输出\n" + truncate(errorOutput, 1500) + "\n\n" +
            "## 相关代码\n";

        if (ctx.relevantCodeSnippets != null && !ctx.relevantCodeSnippets.isEmpty()) {
            for (var entry : ctx.relevantCodeSnippets.entrySet()) {
                prompt += "[" + entry.getKey() + "]\n" + truncate(entry.getValue(), 500) + "\n";
            }
        }

        return llmService.generateForExpert("debug_subagent", prompt, DEBUG_SYSTEM_PROMPT, 500);
    }

    /**
     * 格式化调试信息
     */
    private String formatDebugInfo(DebugInfo info) {
        List<String> lines = new ArrayList<>();

        if (info.errorType != null) {
            lines.add("[错误类型] " + info.errorType);
        }
        if (info.errorMessage != null) {
            lines.add("[错误信息] " + info.errorMessage);
        }
        if (info.errorLocation != null) {
            lines.add("[错误位置] " + info.errorLocation);
        }
        if (info.isImportError && info.missingModule != null) {
            lines.add("[缺失模块] " + info.missingModule);
        }
        if (info.llmDiagnosis != null) {
            lines.add("[LLM诊断]\n" + info.llmDiagnosis);
        }

        return String.join("\n", lines);
    }

    /**
     * 分类错误类型
     */
    private String classifyError(String errorName) {
        if (errorName == null) return "unknown";
        String lower = errorName.toLowerCase();
        if (lower.contains("syntax")) return "syntax";
        if (lower.contains("import") || lower.contains("modulenotfound") || lower.contains("nomodule")) return "import";
        if (lower.contains("assertion") || lower.contains("assert")) return "assertion";
        if (lower.contains("type") || lower.contains("value") || lower.contains("key")
            || lower.contains("index") || lower.contains("attribute") || lower.contains("name")) return "runtime";
        return "other";
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    /**
     * 调试信息结构
     */
    private static class DebugInfo {
        String rawError = "";
        String errorType = null;
        String errorMessage = null;
        String errorLocation = null;
        boolean isImportError = false;
        String missingModule = null;
        String llmDiagnosis = null;
    }
}
