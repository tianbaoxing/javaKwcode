package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 聊天专家 - 处理非编码类输入（问答、解释、建议等）
 * <p>
 * Gate路由expert_type=chat时使用。
 * 不走Locator→Generator→Verifier流水线，直接LLM对话。
 * </p>
 * <p>
 * 设计原则：
 * - 无副作用：不修改任何文件
 * - 快速响应：单次LLM调用
 * - 安全边界：不执行任何代码
 * </p>
 * @origin Python: experts.chat_expert.ChatExpert
 */
public class ChatExpert implements Expert {

    private static final Logger log = LoggerFactory.getLogger(ChatExpert.class);

    private static final String CHAT_SYSTEM_PROMPT =
        "你是kwcode的聊天助手。你擅长：\n" +
        "1. 解释代码逻辑和架构\n" +
        "2. 提供编程建议和最佳实践\n" +
        "3. 分析技术方案的优缺点\n" +
        "4. 回答编程相关问题\n\n" +
        "注意：\n" +
        "- 你不直接修改代码文件\n" +
        "- 如果用户需要修改代码，建议他们使用编码模式\n" +
        "- 回答要简洁、准确、有针对性";

    private final LLMService llmService;

    public ChatExpert(LLMService llmService) {
        this.llmService = llmService;
    }

    public ChatExpert() {
        this(null);
    }

    @Override
    public String name() {
        return "chat";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return chat(ctx);
    }

    /**
     * 处理聊天请求
     * <p>
     * 单次LLM调用，无副作用。
     * </p>
     * @origin Python: experts.chat_expert.ChatExpert.chat(ctx) -> dict
     * @param ctx 任务上下文
     * @return 聊天结果
     */
    public ExpertResult chat(TaskContext ctx) {
        String userInput = ctx.userInput;
        if (userInput == null || userInput.isEmpty()) {
            return ExpertResult.fail("Empty input");
        }

        if (llmService == null) {
            String fallback = generateFallbackResponse(userInput);
            return ExpertResult.ok(fallback);
        }

        try {
            String systemPrompt = buildSystemPrompt(ctx);
            String response = llmService.generateForExpert("chat", userInput, systemPrompt, 2000);

            if (response == null || response.isEmpty()) {
                return ExpertResult.fail("LLM returned empty response");
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("expert_type", "chat");
            metadata.put("model_used", "chat");

            log.info("[chat_expert] Response generated, length={}", response.length());
            return ExpertResult.ok(response, metadata);

        } catch (Exception e) {
            log.warn("[chat_expert] LLM call failed: {}", e.getMessage());
            String fallback = generateFallbackResponse(userInput);
            return ExpertResult.ok(fallback);
        }
    }

    /**
     * 构建系统提示词
     * <p>
     * 注入项目上下文（如果有）
     * </p>
     */
    private String buildSystemPrompt(TaskContext ctx) {
        StringBuilder sb = new StringBuilder(CHAT_SYSTEM_PROMPT);

        if (ctx.projectRoot != null && !ctx.projectRoot.isEmpty()) {
            sb.append("\n\n项目根目录: ").append(ctx.projectRoot);
        }

        if (ctx.kaiwuMemory != null && !ctx.kaiwuMemory.isEmpty()) {
            String memory = ctx.kaiwuMemory;
            if (memory.length() > 500) {
                memory = memory.substring(0, 500) + "...";
            }
            sb.append("\n\n项目记忆:\n").append(memory);
        }

        if (ctx.kwcodeRules != null && !ctx.kwcodeRules.isEmpty()) {
            String rules = ctx.kwcodeRules;
            if (rules.length() > 300) {
                rules = rules.substring(0, 300) + "...";
            }
            sb.append("\n\n项目规则:\n").append(rules);
        }

        return sb.toString();
    }

    /**
     * LLM不可用时的降级响应
     */
    private String generateFallbackResponse(String userInput) {
        if (userInput.contains("什么") || userInput.contains("what") || userInput.contains("?") || userInput.contains("？")) {
            return "抱歉，我当前无法连接到大模型服务。请检查LLM配置后重试。";
        }
        if (userInput.contains("怎么") || userInput.contains("如何") || userInput.contains("how")) {
            return "抱歉，我当前无法连接到大模型服务来回答这个问题。请检查LLM配置后重试。";
        }
        return "抱歉，我当前无法连接到大模型服务。请检查LLM配置后重试。";
    }
}
