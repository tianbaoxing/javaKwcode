package com.kwcode.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM统一后端：基于Spring AI ChatClient实现
 * 对应Python: kaiwu/llm/llama_backend.py LLMBackend
 *
 * <p>设计规则合规：
 * <ul>
 *   <li>✅ 统一接口抽象：通过Spring AI ChatClient统一访问LLM</li>
 *   <li>✅ 依赖注入管理：通过Spring DI管理ChatClient实例</li>
 *   <li>✅ 配置化参数：URL/Key/Model通过application.yml管理</li>
 *   <li>✅ 多Provider支持：OpenRouter + Ollama</li>
 * </ul>
 *
 * @origin kaiwu/llm/llama_backend.py::LLMBackend
 */
@Component
public class LLMBackend {

    private static final Logger log = LoggerFactory.getLogger(LLMBackend.class);

    private static final List<String> REASONING_PREFIXES = List.of(
            "deepseek-r1", "qwq", "qwen3", "gemma4"
    );

    private static final Pattern THINKING_PATTERN = Pattern.compile("<think.*?>.*?</think >", Pattern.DOTALL);

    private ChatClient openRouterClient;
    private ChatClient ollamaClient;
    private String defaultProvider = "openrouter";
    private String defaultModel = "deepseek/deepseek-chat-v3-0324";

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int callCount = 0;
    private int tokenBudget = 0;

    private double lastElapsed = 0.0;

    /**
     * Spring环境构造器：自动注入ChatClient
     */
    @Autowired
    public LLMBackend(
            @Qualifier("openRouterChatClient") ChatClient openRouterClient,
            @Qualifier("ollamaChatClient") ChatClient ollamaClient) {
        this.openRouterClient = openRouterClient;
        this.ollamaClient = ollamaClient;
        log.info("LLMBackend初始化（Spring AI模式）: provider={}, model={}", defaultProvider, defaultModel);
    }

    /**
     * 兼容模式构造器（用于测试或非Spring环境）
     */
    public LLMBackend() {
        this.openRouterClient = null;
        this.ollamaClient = null;
        log.info("LLMBackend初始化（兼容模式，无ChatClient）");
    }

    public void setDefaultProvider(String provider) {
        this.defaultProvider = provider;
    }

    public void setDefaultModel(String model) {
        this.defaultModel = model;
    }

    private ChatClient getActiveClient() {
        if ("ollama".equalsIgnoreCase(defaultProvider) && ollamaClient != null) {
            return ollamaClient;
        }
        if (openRouterClient != null) {
            return openRouterClient;
        }
        throw new IllegalStateException("没有可用的ChatClient，请确保Spring AI配置正确");
    }

    // ── generate：单轮文本补全 ──

    /**
     * 生成文本补全
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.generate
     */
    public String generate(String prompt, String system, int maxTokens, double temperature) {
        long t0 = System.nanoTime();

        List<Message> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(new SystemMessage(system));
        }
        messages.add(new UserMessage(prompt));

        Prompt promptObj = new Prompt(messages);
        var response = getActiveClient().call(promptObj);
        String content = response.getResult().getOutput().getContent();

        lastElapsed = (System.nanoTime() - t0) / 1_000_000_000.0;

        int inputEst = prompt.length() / 4;
        int outputEst = content.length() / 4;
        trackTokens(inputEst, outputEst);

        return stripThinking(content);
    }

    /**
     * 生成文本补全（简化版）
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.generate
     */
    public String generate(String prompt, String system, int maxTokens) {
        return generate(prompt, system, maxTokens, 0.0);
    }

    // ── chat：多轮对话 ──

    /**
     * 多轮对话补全
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.chat
     */
    public String chat(List<Map<String, String>> messages, int maxTokens, double temperature) {
        long t0 = System.nanoTime();

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

        lastElapsed = (System.nanoTime() - t0) / 1_000_000_000.0;

        int inputEst = messages.stream().mapToInt(m -> m.get("content").length() / 4).sum();
        int outputEst = content.length() / 4;
        trackTokens(inputEst, outputEst);

        return stripThinking(content);
    }

    /**
     * 多轮对话补全（简化版）
     * @origin kaiwu/llm/llama_backend.py::LLMBackend.chat
     */
    public String chat(List<Map<String, String>> messages, int maxTokens) {
        return chat(messages, maxTokens, 0.0);
    }

    // ── 工具方法 ──

    private static String stripThinking(String text) {
        String cleaned = THINKING_PATTERN.matcher(text).replaceAll("").trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    public static boolean detectReasoningModel(String modelName) {
        String nameLower = modelName.toLowerCase().split(":")[0];
        return REASONING_PREFIXES.stream().anyMatch(nameLower::startsWith);
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

    // ── Getter/Setter ──

    public Map<String, Object> getTokenUsage() {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", totalInputTokens);
        usage.put("output_tokens", totalOutputTokens);
        usage.put("total_tokens", totalInputTokens + totalOutputTokens);
        usage.put("call_count", callCount);
        return usage;
    }

    public void setTokenBudget(int budget) { this.tokenBudget = budget; }
    public void resetTokenUsage() { totalInputTokens = 0; totalOutputTokens = 0; callCount = 0; }
    public double getLastElapsed() { return lastElapsed; }
    public String getOllamaModel() { return defaultModel; }
    public String getOllamaUrl() { return defaultProvider; }
    public boolean isReasoning() { return detectReasoningModel(defaultModel); }
}
