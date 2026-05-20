package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求对齐审查专家 - Verifier通过后执行
 * <p>
 * 用 LLM 对比用户原始意图和实际代码变更，判断是否真正完成了任务。
 * 非阻塞：审查失败不回滚，只记录gap供用户参考。
 * 元专家体系第5个原子能力：Locator→Generator→Verifier→Debugger→Reviewer
 * </p>
 * @origin Python: experts.reviewer.ReviewerExpert
 */
public class Reviewer {

    private static final Logger log = LoggerFactory.getLogger(Reviewer.class);

    private final LLMService llmService;

    public Reviewer(LLMService llmService) {
        this.llmService = llmService;
    }

    public Reviewer() { this(null); }

    /**
     * 审查Generator输出是否对齐用户需求
     * <p>
     * 返回 aligned/confidence/gap
     * 失败时乐观降级：返回 aligned=True, confidence=0.0
     * </p>
     * @origin Python: experts.reviewer.ReviewerExpert.review(ctx) -> dict
     */
    public ReviewResult review(TaskContext ctx) {
        try {
            String changes = extractChanges(ctx);
            if (changes.isEmpty()) {
                return new ReviewResult(true, 0.0, "");
            }

            if (llmService != null) {
                ReviewResult llmResult = llmReview(ctx, changes);
                if (llmResult != null) return llmResult;
            }

            return new ReviewResult(true, 0.0, "");

        } catch (Exception e) {
            log.warn("[reviewer] review failed: {}", e.getMessage());
            return new ReviewResult(true, 0.0, "");
        }
    }

    /**
     * LLM需求对齐审查
     * <p>
     * 对比用户原始意图和实际代码变更，判断是否对齐。
     * 只做1次LLM调用，失败时乐观降级。
     * </p>
     * @origin Python: experts.reviewer.ReviewerExpert._llm_review(ctx, changes)
     */
    private ReviewResult llmReview(TaskContext ctx, String changes) {
        try {
            String prompt = "审查代码变更是否对齐用户需求：\n\n" +
                "## 用户需求\n" + ctx.userInput.substring(0, Math.min(300, ctx.userInput.length())) + "\n\n" +
                "## 代码变更\n" + (changes.length() > 1500 ? changes.substring(0, 1500) : changes) + "\n\n" +
                "输出JSON格式：\n" +
                "{\"aligned\": true/false, \"confidence\": 0.0-1.0, \"gap\": \"未对齐的具体描述，对齐则为空\"}";

            String response = llmService.generateForExpert("reviewer", prompt,
                "你是代码审查专家，判断代码变更是否真正满足用户需求。只输出JSON。", 300);

            if (response == null || response.isEmpty()) return null;

            return parseReviewResponse(response);
        } catch (Exception e) {
            log.debug("[reviewer] LLM review failed (降级): {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析LLM审查响应
     */
    private ReviewResult parseReviewResponse(String response) {
        try {
            String json = response;
            int braceStart = response.indexOf('{');
            int braceEnd = response.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                json = response.substring(braceStart, braceEnd + 1);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);

            boolean aligned = Boolean.TRUE.equals(parsed.get("aligned"));
            double confidence = 0.5;
            if (parsed.get("confidence") instanceof Number n) {
                confidence = n.doubleValue();
            }
            String gap = (String) parsed.getOrDefault("gap", "");

            log.info("[reviewer] LLM review: aligned={}, confidence={}, gap={}", aligned, confidence, gap);
            return new ReviewResult(aligned, confidence, gap);
        } catch (Exception e) {
            log.debug("[reviewer] LLM response parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 从ctx提取代码变更摘要 */
    private String extractChanges(TaskContext ctx) {
        if (ctx.generatorOutput == null) return "";

        List<String> parts = new ArrayList<>();
        List<TaskContext.Patch> patches = ctx.generatorOutput.patches();
        for (int i = 0; i < Math.min(3, patches.size()); i++) {
            TaskContext.Patch patch = patches.get(i);
            String modified = patch.modified().length() > 300 ? patch.modified().substring(0, 300) : patch.modified();
            String original = patch.original().length() > 200 ? patch.original().substring(0, 200) : patch.original();
            parts.add("文件: " + patch.file() + "\n修改后:\n" + modified);
            if (!original.isEmpty()) parts.add("修改前:\n" + original);
        }

        if (ctx.generatorOutput.explanation() != null && !ctx.generatorOutput.explanation().isEmpty()) {
            String exp = ctx.generatorOutput.explanation();
            parts.add("说明: " + (exp.length() > 200 ? exp.substring(0, 200) : exp));
        }

        return String.join("\n\n", parts);
    }

    /** 审查结果 */
    public record ReviewResult(boolean aligned, double confidence, String gap) {}
}
