package com.kwcode.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型路由器：根据任务类型选择合适的LLM模型
 * 支持多Provider（OpenRouter/Ollama）和任务级模型路由
 *
 * @origin Python: kaiwu/server/pipeline_factory.py (模型路由逻辑)
 */
@Data
@Component
@ConfigurationProperties(prefix = "kwcode.llm")
public class ModelRouter {

    /** 默认Provider */
    private String defaultProvider = "openrouter";

    /** 模型路由映射: expertType -> provider -> model */
    private Map<String, Map<String, String>> modelRouter = new HashMap<>();

    /**
     * 获取当前默认Provider
     */
    public String getCurrentProvider() {
        return defaultProvider;
    }

    /**
     * 获取当前Provider（别名方法，保持接口一致性）
     */
    public String getProvider() {
        return defaultProvider;
    }

    /**
     * 根据专家类型获取对应的模型名称
     * @param expertType 专家类型（如locator/generator/verifier）
     * @return 模型名称，如果没有配置则返回null
     */
    public String getModelForExpert(String expertType) {
        Map<String, String> providerModels = modelRouter.get(expertType);
        if (providerModels != null) {
            return providerModels.get(defaultProvider);
        }
        return null;
    }

    /**
     * 根据专家类型和指定Provider获取模型名称
     * @param expertType 专家类型
     * @param provider Provider名称（openrouter/ollama）
     * @return 模型名称
     */
    public String getModelForExpert(String expertType, String provider) {
        Map<String, String> providerModels = modelRouter.get(expertType);
        if (providerModels != null) {
            return providerModels.get(provider);
        }
        return null;
    }

    /**
     * 判断是否有指定专家类型的路由配置
     */
    public boolean hasRoute(String expertType) {
        return modelRouter.containsKey(expertType);
    }

    /**
     * 获取所有已配置的专家类型
     */
    public java.util.Set<String> getExpertTypes() {
        return modelRouter.keySet();
    }
}
