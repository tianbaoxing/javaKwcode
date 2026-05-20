package com.kwcode.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ModelRouter单元测试
 * 验证任务级模型路由配置和选择逻辑
 *
 * @origin kaiwu/server/pipeline_factory.py (模型路由逻辑)
 */
class ModelRouterTest {

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        router = new ModelRouter();
        // 模拟配置注入
        Map<String, Map<String, String>> modelRouterMap = new HashMap<>();
        
        Map<String, String> locatorModels = new HashMap<>();
        locatorModels.put("openrouter", "deepseek/deepseek-chat-v3-0324");
        locatorModels.put("ollama", "qwen3-8b");
        
        Map<String, String> generatorModels = new HashMap<>();
        generatorModels.put("openrouter", "anthropic/claude-sonnet-4");
        generatorModels.put("ollama", "qwen3-8b");
        
        modelRouterMap.put("locator", locatorModels);
        modelRouterMap.put("generator", generatorModels);
        
        router.setModelRouter(modelRouterMap);
    }

    @Test
    @DisplayName("默认Provider应为openrouter")
    void testDefaultProvider() {
        assertEquals("openrouter", router.getCurrentProvider());
    }

    @Test
    @DisplayName("Locator专家应正确路由到deepseek模型（openrouter）")
    void testLocatorModelOpenRouter() {
        router.setDefaultProvider("openrouter");
        String model = router.getModelForExpert("locator");
        assertEquals("deepseek/deepseek-chat-v3-0324", model);
    }

    @Test
    @DisplayName("Locator专家应正确路由到qwen3模型（ollama）")
    void testLocatorModelOllama() {
        router.setDefaultProvider("ollama");
        String model = router.getModelForExpert("locator");
        assertEquals("qwen3-8b", model);
    }

    @Test
    @DisplayName("Generator专家应使用claude-sonnet-4（openrouter）")
    void testGeneratorModelOpenRouter() {
        router.setDefaultProvider("openrouter");
        String model = router.getModelForExpert("generator");
        assertEquals("anthropic/claude-sonnet-4", model);
    }

    @Test
    @DisplayName("Generator专家应使用qwen3-8b（ollama）")
    void testGeneratorModelOllama() {
        router.setDefaultProvider("ollama");
        String model = router.getModelForExpert("generator");
        assertEquals("qwen3-8b", model);
    }

    @Test
    @DisplayName("未配置的专家类型应返回null")
    void testUnknownExpertType() {
        String model = router.getModelForExpert("unknown");
        assertNull(model);
    }

    @Test
    @DisplayName("hasRoute应正确判断路由是否存在")
    void testHasRoute() {
        assertTrue(router.hasRoute("locator"), "locator路由应存在");
        assertTrue(router.hasRoute("generator"), "generator路由应存在");
        assertFalse(router.hasRoute("unknown"), "unknown路由不应存在");
    }

    @Test
    @DisplayName("getExpertTypes应返回所有已配置的专家类型")
    void testGetExpertTypes() {
        var types = router.getExpertTypes();
        assertTrue(types.contains("locator"), "应包含locator");
        assertTrue(types.contains("generator"), "应包含generator");
        assertEquals(2, types.size(), "应有2个专家类型");
    }

    @Test
    @DisplayName("按Provider获取模型应正确工作")
    void testGetModelForExpertWithProvider() {
        String openrouterModel = router.getModelForExpert("locator", "openrouter");
        String ollamaModel = router.getModelForExpert("locator", "ollama");
        
        assertEquals("deepseek/deepseek-chat-v3-0324", openrouterModel);
        assertEquals("qwen3-8b", ollamaModel);
    }
}
