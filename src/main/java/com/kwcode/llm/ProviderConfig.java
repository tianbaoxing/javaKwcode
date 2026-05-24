package com.kwcode.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
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
     * OpenRouter ChatModel Bean
     * Spring AI 1.0.0: OpenAiChatClient → OpenAiChatModel, ChatClient → ChatModel
     */
    @Bean("openRouterChatModel")
    public ChatModel openRouterChatModel() {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(openRouterBaseUrl)
                .apiKey(openRouterApiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(openRouterModel)
                .temperature(0.1)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
    }

    /**
     * Ollama ChatModel Bean
     * Spring AI 1.0.0: OllamaChatClient → OllamaChatModel
     */
    @Bean("ollamaChatModel")
    public ChatModel ollamaChatModel() {
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .build();
    }
}
