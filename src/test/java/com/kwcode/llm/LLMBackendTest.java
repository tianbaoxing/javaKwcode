package com.kwcode.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLMBackend单元测试
 * @origin kaiwu/llm/llama_backend.py::LLMBackend
 */
class LLMBackendTest {

    private LLMBackend backend;

    @BeforeEach
    void setUp() {
        backend = new LLMBackend();
        backend.setDefaultModel("qwen3-8b");
        backend.setDefaultProvider("ollama");
    }

    @Test
    @DisplayName("推理模型检测：deepseek-r1应被识别为推理模型")
    void testReasoningModelDetectionDeepSeek() {
        LLMBackend ds = new LLMBackend();
        ds.setDefaultModel("deepseek-r1:14b");
        assertTrue(ds.isReasoning(), "deepseek-r1应被识别为推理模型");
    }

    @Test
    @DisplayName("推理模型检测：qwq应被识别为推理模型")
    void testReasoningModelDetectionQwQ() {
        LLMBackend qwq = new LLMBackend();
        qwq.setDefaultModel("qwq:32b");
        assertTrue(qwq.isReasoning(), "qwq应被识别为推理模型");
    }

    @Test
    @DisplayName("qwen3-8b属于推理模型（qwen3前缀匹配）")
    void testQwen3IsReasoning() {
        assertTrue(backend.isReasoning(), "qwen3前缀应被识别为推理模型");
    }

    @Test
    @DisplayName("静态推理模型检测方法")
    void testStaticReasoningDetection() {
        assertTrue(LLMBackend.detectReasoningModel("deepseek-r1:14b"), "deepseek-r1应为推理模型");
        assertTrue(LLMBackend.detectReasoningModel("qwq:32b"), "qwq应为推理模型");
        assertTrue(LLMBackend.detectReasoningModel("qwen3-8b"), "qwen3应为推理模型");
        assertFalse(LLMBackend.detectReasoningModel("llama3"), "llama3不应为推理模型");
        assertFalse(LLMBackend.detectReasoningModel("gpt-4"), "gpt-4不应为推理模型");
    }

    @Test
    @DisplayName("Token预算超限应抛出BudgetExceededError")
    void testBudgetExceeded() {
        assertThrows(BudgetExceededError.class, () -> {
            throw new BudgetExceededError("Token预算超限");
        }, "应抛出BudgetExceededError");
    }

    @Test
    @DisplayName("Token使用统计应正确追踪")
    void testTokenUsageTracking() {
        Map<String, Object> usage = backend.getTokenUsage();
        assertTrue(usage.containsKey("input_tokens"), "应包含input_tokens");
        assertTrue(usage.containsKey("output_tokens"), "应包含output_tokens");
        assertTrue(usage.containsKey("call_count"), "应包含call_count");
        assertEquals(0, usage.get("call_count"), "初始调用次数应为0");
    }

    @Test
    @DisplayName("重置Token使用统计")
    void testResetTokenUsage() {
        backend.resetTokenUsage();
        Map<String, Object> usage = backend.getTokenUsage();
        assertEquals(0, usage.get("input_tokens"), "重置后input_tokens应为0");
        assertEquals(0, usage.get("output_tokens"), "重置后output_tokens应为0");
        assertEquals(0, usage.get("call_count"), "重置后call_count应为0");
    }

    @Test
    @DisplayName("Getter方法应返回正确值")
    void testGetters() {
        LLMBackend llama = new LLMBackend();
        llama.setDefaultModel("llama3");
        llama.setDefaultProvider("ollama");
        assertEquals("llama3", llama.getOllamaModel(), "模型名应匹配");
        assertEquals("ollama", llama.getOllamaUrl(), "Provider应匹配");
        assertFalse(llama.isReasoning(), "llama3不应被识别为推理模型");
    }

    @Test
    @DisplayName("兼容模式构造器应正常创建实例")
    void testCompatibilityConstructor() {
        LLMBackend compat = new LLMBackend();
        assertNotNull(compat, "兼容模式构造器应正常创建实例");
        Map<String, Object> usage = compat.getTokenUsage();
        assertNotNull(usage, "Token使用统计应可用");
    }

    @Test
    @DisplayName("setDefaultProvider和setDefaultModel应正常工作")
    void testSetters() {
        backend.setDefaultProvider("openrouter");
        backend.setDefaultModel("deepseek/deepseek-chat-v3-0324");
        assertEquals("openrouter", backend.getOllamaUrl(), "Provider应更新");
        assertEquals("deepseek/deepseek-chat-v3-0324", backend.getOllamaModel(), "模型应更新");
    }
}
