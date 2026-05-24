package com.kwcode.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM统一后端：基于Spring AI ChatModel实现
 * 对应Python: kaiwu/llm/llama_backend.py LLMBackend
 *
 * <p>Spring AI 1.0.0 API变更：
 * <ul>
 *   <li>ChatClient → ChatModel（旧ChatClient重命名为ChatModel）</li>
 *   <li>call()方法签名不变</li>
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

    private ChatModel openRouterModel;
    private ChatModel ollamaModel;
    private String defaultProvider = "openrouter";
    private String defaultModel = "deepseek/deepseek-chat-v3-0324";

    private int totalInputTokens = 0;
    private int totalOutputTokens = 0;
    private int callCount = 0;
    private int tokenBudget = 0;

    private double lastElapsed = 0.0;

    /**
     * Spring环境构造器：自动注入ChatModel
     * Spring AI 1.0.0: ChatClient → ChatModel
     */
    @Autowired
    public LLMBackend(
            @Qualifier("openRouterChatModel") ChatModel openRouterModel,
            @Qualifier("ollamaChatModel") ChatModel ollamaModel) {
        this.openRouterModel = openRouterModel;
        this.ollamaModel = ollamaModel;
        log.info("LLMBackend初始化（Spring AI模式）: provider={}, model={}", defaultProvider, defaultModel);
    }

    /**
     * 兼容模式构造器（用于测试或非Spring环境）
     */
    public LLMBackend() {
        this.openRouterModel = null;
        this.ollamaModel = null;
        log.info("LLMBackend初始化（兼容模式，无ChatModel）");
    }

    public void setDefaultProvider(String provider) {
        this.defaultProvider = provider;
    }

    public void setDefaultModel(String model) {
        this.defaultModel = model;
    }

    private ChatModel getActiveModel() {
        if ("ollama".equalsIgnoreCase(defaultProvider) && ollamaModel != null) {
            return ollamaModel;
        }
        if (openRouterModel != null) {
            return openRouterModel;
        }
        throw new IllegalStateException("没有可用的ChatModel，请确保Spring AI配置正确");
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
        var response = getActiveModel().call(promptObj);
        String content = response.getResult().getOutput().getText();

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
        var response = getActiveModel().call(promptObj);
        String content = response.getResult().getOutput().getText();

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
