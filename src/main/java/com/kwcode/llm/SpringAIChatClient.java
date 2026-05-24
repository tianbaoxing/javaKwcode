package com.kwcode.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring AI ChatModel 封装器
 * 提供统一的LLM调用接口，支持多Provider切换
 *
 * <p>Spring AI 1.0.0 API变更：
 * <ul>
 *   <li>ChatClient → ChatModel（旧ChatClient重命名为ChatModel）</li>
 * </ul>
 *
 * @origin kaiwu/llm/llama_backend.py::LLMBackend (Spring AI版本)
 */
@Component
public class SpringAIChatClient {

    private static final Logger log = LoggerFactory.getLogger(SpringAIChatClient.class);

    private final ChatModel openRouterModel;
    private final ChatModel ollamaModel;
    private final ModelRouter modelRouter;

    public SpringAIChatClient(
            @Qualifier("openRouterChatModel") ChatModel openRouterModel,
            @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            ModelRouter modelRouter) {
        this.openRouterModel = openRouterModel;
        this.ollamaModel = ollamaModel;
        this.modelRouter = modelRouter;
        log.info("SpringAIChatClient初始化完成");
    }

    /**
     * 使用指定专家的模型生成回复
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

        ChatModel chatModel = "ollama".equalsIgnoreCase(provider) ? ollamaModel : openRouterModel;

        Prompt promptObj = new Prompt(messages);
        var response = chatModel.call(promptObj);
        return response.getResult().getOutput().getText();
    }

    /**
     * 使用指定专家的模型进行多轮对话
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

        ChatModel chatModel = "ollama".equalsIgnoreCase(provider) ? ollamaModel : openRouterModel;

        Prompt promptObj = new Prompt(chatMessages);
        var response = chatModel.call(promptObj);
        return response.getResult().getOutput().getText();
    }
}
