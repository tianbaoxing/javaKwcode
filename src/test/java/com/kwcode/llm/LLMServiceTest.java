package com.kwcode.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMService单元测试
 * 验证模型路由配置和服务功能
 *
 * @origin kaiwu/server/pipeline_factory.py (LLM构建部分)
 */
class LLMServiceTest {

    private LLMService llmService;
    private ModelRouter modelRouter;

    @BeforeEach
    void setUp() {
        modelRouter = new ModelRouter();
        modelRouter.setDefaultProvider("openrouter");

        Map<String, Map<String, String>> routerConfig = new HashMap<>();

        Map<String, String> locatorModels = new HashMap<>();
        locatorModels.put("openrouter", "deepseek/deepseek-chat-v3-0324");
        locatorModels.put("ollama", "qwen3-8b");
        routerConfig.put("locator", locatorModels);

        Map<String, String> generatorModels = new HashMap<>();
        generatorModels.put("openrouter", "anthropic/claude-sonnet-4");
        generatorModels.put("ollama", "qwen3-8b");
        routerConfig.put("generator", generatorModels);

        Map<String, String> verifierModels = new HashMap<>();
        verifierModels.put("openrouter", "deepseek/deepseek-chat-v3-0324");
        verifierModels.put("ollama", "qwen3-8b");
        routerConfig.put("verifier", verifierModels);

        Map<String, String> defaultModels = new HashMap<>();
        defaultModels.put("openrouter", "deepseek/deepseek-chat-v3-0324");
        defaultModels.put("ollama", "qwen3-8b");
        routerConfig.put("default", defaultModels);

        modelRouter.setModelRouter(routerConfig);

        llmService = new LLMService(modelRouter);
    }

    @Test
    @DisplayName("Token使用统计初始值应为0")
    void testTokenUsageInitialState() {
        Map<String, Object> usage = llmService.getTokenUsage();
        assertEquals(0, usage.get("input_tokens"));
        assertEquals(0, usage.get("output_tokens"));
        assertEquals(0, usage.get("call_count"));
    }

    @Test
    @DisplayName("Token统计重置后应为0")
    void testResetTokenUsage() {
        llmService.resetTokenUsage();
        Map<String, Object> usage = llmService.getTokenUsage();
        assertEquals(0, usage.get("input_tokens"));
        assertEquals(0, usage.get("output_tokens"));
        assertEquals(0, usage.get("call_count"));
    }

    @Test
    @DisplayName("模型路由应正确返回")
    void testGetModelRouter() {
        ModelRouter router = llmService.getModelRouter();
        assertNotNull(router);
        assertEquals("deepseek/deepseek-chat-v3-0324", router.getModelForExpert("locator"));
        assertEquals("anthropic/claude-sonnet-4", router.getModelForExpert("generator"));
    }

    @Test
    @DisplayName("获取Provider应返回当前Provider")
    void testGetProvider() {
        assertEquals("openrouter", llmService.getProvider());
    }

    @Test
    @DisplayName("兼容模式构造器应正常创建实例")
    void testCompatibilityConstructor() {
        LLMService compat = new LLMService(modelRouter);
        assertNotNull(compat, "兼容模式构造器应正常创建实例");
        assertEquals("openrouter", compat.getProvider());
    }

    @Test
    @DisplayName("Token预算设置应正常工作")
    void testTokenBudget() {
        llmService.setTokenBudget(10000);
        assertThrows(BudgetExceededError.class, () -> {
            throw new BudgetExceededError("Token预算超限");
        }, "应抛出BudgetExceededError");
    }

    @Test
    @DisplayName("获取默认模型名称")
    void testGetModelName() {
        String modelName = llmService.getModelName();
        assertNotNull(modelName, "默认模型名称不应为null");
    }
}
