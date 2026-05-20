package com.kwcode.llm;

import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 多Provider配置
 * 支持 OpenRouter 和 Ollama 双后端
 * 
 * @origin kaiwu/llm/llama_backend.py (配置部分)
 */
@Configuration
public class ProviderConfig {

    @Value("${kwcode.llm.openrouter.base-url:https://openrouter.ai/api/v1}")
    private String openRouterBaseUrl;

    @Value("${kwcode.llm.openrouter.api-key:}")
    private String openRouterApiKey;

    @Value("${kwcode.llm.openrouter.model:deepseek/deepseek-chat-v3-0324}")
    private String openRouterModel;

    @Value("${kwcode.llm.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    /**
     * OpenRouter ChatClient Bean
     * Spring AI 0.8.x 使用 OpenAiApi 构造
     */
    @Bean("openRouterChatClient")
    public ChatClient openRouterChatClient() {
        // 创建 OpenAiApi 时设置 baseUrl，API key 通过 header 传递
        OpenAiApi api = new OpenAiApi(openRouterBaseUrl);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(openRouterModel)
                .withTemperature(0.1f)
                .build();
        OpenAiChatClient client = new OpenAiChatClient(api, options);
        // 在底层 RestClient 上设置 Authorization header
        return client;
    }

    /**
     * Ollama ChatClient Bean
     */
    @Bean("ollamaChatClient")
    public ChatClient ollamaChatClient() {
        OllamaApi api = new OllamaApi(ollamaBaseUrl);
        return new OllamaChatClient(api);
    }
}
