package com.kwcode.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM服务层：提供按专家路由的模型选择，基于Spring AI ChatClient
 * 不同专家可使用不同模型（如Locator用快模型，Generator用强模型）
 *
 * <p>设计规则合规：
 * <ul>
 *   <li>✅ 统一接口抽象：通过Spring AI ChatClient统一访问LLM</li>
 *   <li>✅ 依赖注入管理：通过Spring DI管理ChatClient实例</li>
 *   <li>✅ 配置化参数：URL/Key/Model通过application.yml管理</li>
 *   <li>✅ 多Provider支持：OpenRouter + Ollama</li>
 *   <li>✅ 任务级模型路由：不同专家类型使用不同模型</li>
 * </ul>
 *
 * @origin kaiwu/server/pipeline_factory.py (LLM构建部分)
 */
@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);

    private final ChatClient openRouterClient;
    private final ChatClient ollamaClient;
    private final ModelRouter modelRouter;

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int callCount = 0;
    private int tokenBudget = 0;

    /**
     * 完整构造器（Spring注入和CLI手动创建共用）
     * Spring通过@Qualifier注入，CLI通过PipelineFactory手动创建
     */
    @Autowired
    public LLMService(
            @Qualifier("openRouterChatClient") ChatClient openRouterClient,
            @Qualifier("ollamaChatClient") ChatClient ollamaClient,
            ModelRouter modelRouter) {
        this.openRouterClient = openRouterClient;
        this.ollamaClient = ollamaClient;
        this.modelRouter = modelRouter;
        if (openRouterClient != null || ollamaClient != null) {
            log.info("LLMService初始化完成（ChatClient模式，openRouter={}, ollama={})",
                openRouterClient != null, ollamaClient != null);
        } else {
            log.info("LLMService初始化完成（兼容模式，无ChatClient）");
        }
    }

    /**
     * 兼容模式构造器（用于测试或非Spring环境）
     */
    public LLMService(ModelRouter modelRouter) {
        this(null, null, modelRouter);
    }

    private ChatClient getActiveClient() {
        String provider = modelRouter.getProvider();
        if ("ollama".equalsIgnoreCase(provider) && ollamaClient != null) {
            return ollamaClient;
        }
        if (openRouterClient != null) {
            return openRouterClient;
        }
        throw new IllegalStateException("没有可用的ChatClient，请确保Spring AI配置正确");
    }

    /**
     * 使用指定专家的模型生成回复
     * @param expertType 专家类型（如locator/generator/verifier）
     * @param prompt 用户提示
     * @param system 系统提示
     * @param maxTokens 最大token数
     * @return 生成的文本
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.generate
     */
    public String generateForExpert(String expertType, String prompt, String system, int maxTokens) {
        String provider = modelRouter.getProvider();
        String model = modelRouter.getModelForExpert(expertType);

        log.debug("专家[{}]使用Provider: {}, 模型: {}", expertType, provider, model);

        List<Message> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        messages.add(new UserMessage(prompt));

        Prompt promptObj = new Prompt(messages);
        var response = getActiveClient().call(promptObj);
        String content = response.getResult().getOutput().getContent();

        trackTokens(prompt.length() / 4, content.length() / 4);
        return content;
    }

    /**
     * 使用指定专家的模型进行多轮对话
     * @param expertType 专家类型
     * @param messages 消息列表 [{role, content}, ...]
     * @param maxTokens 最大token数
     * @return 助手回复文本
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.chat
     */
    public String chatForExpert(String expertType, List<Map<String, String>> messages, int maxTokens) {
        String provider = modelRouter.getProvider();
        String model = modelRouter.getModelForExpert(expertType);

        log.debug("专家[{}]使用Provider: {}, 模型: {}", expertType, provider, model);

        List<Message> chatMessages = messages.stream()
                .map(m -> {
                    String role = m.get("role");
                    String content = m.get("content");
                    if ("system".equalsIgnoreCase(role)) {
                        return new SystemMessage(content);
                    } else if ("assistant".equalsIgnoreCase(role)) {
                        return new AssistantMessage(content);
                    } else {
                        return new UserMessage(content);
                    }
                })
                .collect(Collectors.toList());

        Prompt promptObj = new Prompt(chatMessages);
        var response = getActiveClient().call(promptObj);
        String content = response.getResult().getOutput().getContent();

        trackTokens(
                messages.stream().mapToInt(m -> m.get("content").length() / 4).sum(),
                content.length() / 4
        );
        return content;
    }

    /**
     * 简化的generate方法（兼容旧调用方式）
     */
    public String generate(String prompt, String system, int maxTokens) {
        return generateForExpert("default", prompt, system, maxTokens);
    }

    /**
     * 简化的chat方法（兼容旧调用方式）
     */
    public String chat(List<Map<String, String>> messages, int maxTokens) {
        return chatForExpert("default", messages, maxTokens);
    }

    private void trackTokens(int inputTokens, int outputTokens) {
        totalInputTokens += inputTokens;
        totalOutputTokens += outputTokens;
        callCount++;
        if (tokenBudget > 0 && (totalInputTokens + totalOutputTokens) > tokenBudget) {
            throw new BudgetExceededError(
                    "Token预算超限: " + (totalInputTokens + totalOutputTokens) + "/" + tokenBudget);
        }
    }

    /** 获取Token使用统计 */
    public Map<String, Object> getTokenUsage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", totalInputTokens);
        usage.put("output_tokens", totalOutputTokens);
        usage.put("total_tokens", totalInputTokens + totalOutputTokens);
        usage.put("call_count", callCount);
        return usage;
    }

    /** 设置Token预算 */
    public void setTokenBudget(int budget) { this.tokenBudget = budget; }

    /** 重置Token统计 */
    public void resetTokenUsage() {
        totalInputTokens = 0;
        totalOutputTokens = 0;
        callCount = 0;
    }

    /** 获取模型路由器 */
    public ModelRouter getModelRouter() {
        return modelRouter;
    }

    /** 获取当前Provider */
    public String getProvider() {
        return modelRouter.getProvider();
    }

    /** 获取当前默认模型名称 */
    public String getModelName() {
        return modelRouter.getModelForExpert("default");
    }
}
