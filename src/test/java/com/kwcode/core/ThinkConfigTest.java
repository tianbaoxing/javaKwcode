package com.kwcode.core;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThinkConfigTest {

    @Test
    @DisplayName("getThinkConfig: chat × easy → think=false, budget=0")
    void testChatEasy() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("chat", "easy");
        assertFalse(tc.isEnabled());
        assertEquals(0, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: chat × hard → think=false (chat永远不think)")
    void testChatHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("chat", "hard");
        assertFalse(tc.isEnabled());
    }

    @Test
    @DisplayName("getThinkConfig: codegen × easy → think=false")
    void testCodegenEasy() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("codegen", "easy");
        assertFalse(tc.isEnabled());
    }

    @Test
    @DisplayName("getThinkConfig: codegen × medium → think=true, budget=512")
    void testCodegenMedium() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("codegen", "medium");
        assertTrue(tc.isEnabled());
        assertEquals(512, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: codegen × hard → think=true, budget=2048")
    void testCodegenHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("codegen", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(2048, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: locator_repair × hard → think=true, budget=2048")
    void testLocatorRepairHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("locator_repair", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(2048, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: refactor × medium → think=true, budget=1024")
    void testRefactorMedium() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("refactor", "medium");
        assertTrue(tc.isEnabled());
        assertEquals(1024, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: refactor × hard → think=true, budget=4096")
    void testRefactorHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("refactor", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(4096, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: doc × medium → think=false")
    void testDocMedium() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("doc", "medium");
        assertFalse(tc.isEnabled());
    }

    @Test
    @DisplayName("getThinkConfig: doc × hard → think=true, budget=1024")
    void testDocHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("doc", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(1024, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: office × hard → think=true, budget=1024")
    void testOfficeHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("office", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(1024, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: vision × medium → think=true, budget=512")
    void testVisionMedium() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("vision", "medium");
        assertTrue(tc.isEnabled());
        assertEquals(512, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: vision × hard → think=true, budget=2048")
    void testVisionHard() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("vision", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(2048, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("getThinkConfig: 未知expert_type → 默认think=false")
    void testUnknownExpertType() {
        ThinkConfig tc = ThinkConfig.getThinkConfig("unknown_type", "hard");
        assertFalse(tc.isEnabled());
    }

    @Test
    @DisplayName("autoConfigure: 非推理模型 → 永远关闭think")
    void testAutoConfigureNonReasoning() {
        ThinkConfig tc = ThinkConfig.autoConfigure("gpt-4o", "codegen", "hard");
        assertFalse(tc.isEnabled());
        assertEquals(ThinkConfig.MODE_NEVER, tc.getMode());
    }

    @Test
    @DisplayName("autoConfigure: 推理模型 + codegen + hard → think=true, budget=2048")
    void testAutoConfigureReasoningHard() {
        ThinkConfig tc = ThinkConfig.autoConfigure("deepseek-r1:70b", "codegen", "hard");
        assertTrue(tc.isEnabled());
        assertEquals(2048, tc.getBudgetTokens());
    }

    @Test
    @DisplayName("autoConfigure: 推理模型 + codegen + easy → think=false")
    void testAutoConfigureReasoningEasy() {
        ThinkConfig tc = ThinkConfig.autoConfigure("deepseek-r1:70b", "codegen", "easy");
        assertFalse(tc.isEnabled());
    }

    @Test
    @DisplayName("autoConfigure: 两参数版本默认expertType=locator_repair")
    void testAutoConfigureTwoParams() {
        ThinkConfig tc3 = ThinkConfig.autoConfigure("deepseek-r1:70b", "locator_repair", "medium");
        ThinkConfig tc2 = ThinkConfig.autoConfigure("deepseek-r1:70b", "medium");
        assertEquals(tc3.isEnabled(), tc2.isEnabled());
        assertEquals(tc3.getBudgetTokens(), tc2.getBudgetTokens());
    }

    @Test
    @DisplayName("applyThinkToMaxTokens: 非推理模型不变")
    void testApplyThinkNonReasoning() {
        ThinkConfig tc = new ThinkConfig(true, 2048, ThinkConfig.MODE_AUTO);
        assertEquals(1000, tc.applyThinkToMaxTokens(1000, false));
    }

    @Test
    @DisplayName("applyThinkToMaxTokens: 推理模型+think=true → max_tokens+budget")
    void testApplyThinkReasoningEnabled() {
        ThinkConfig tc = new ThinkConfig(true, 2048, ThinkConfig.MODE_AUTO);
        assertEquals(3048, tc.applyThinkToMaxTokens(1000, true));
    }

    @Test
    @DisplayName("applyThinkToMaxTokens: 推理模型+think=false → 不变")
    void testApplyThinkReasoningDisabled() {
        ThinkConfig tc = new ThinkConfig(false, 0, ThinkConfig.MODE_AUTO);
        assertEquals(1000, tc.applyThinkToMaxTokens(1000, true));
    }

    @Test
    @DisplayName("shouldThink: MODE_ALWAYS → true")
    void testShouldThinkAlways() {
        ThinkConfig tc = new ThinkConfig(false, 0, ThinkConfig.MODE_ALWAYS);
        assertTrue(tc.shouldThink());
    }

    @Test
    @DisplayName("shouldThink: MODE_NEVER → false")
    void testShouldThinkNever() {
        ThinkConfig tc = new ThinkConfig(true, 2048, ThinkConfig.MODE_NEVER);
        assertFalse(tc.shouldThink());
    }

    @Test
    @DisplayName("toMap/fromMap: 往返一致性")
    void testToMapFromMap() {
        ThinkConfig original = new ThinkConfig(true, 1024, ThinkConfig.MODE_AUTO);
        Map<String, Object> map = original.toMap();
        ThinkConfig restored = ThinkConfig.fromMap(map);
        assertEquals(original.isEnabled(), restored.isEnabled());
        assertEquals(original.getBudgetTokens(), restored.getBudgetTokens());
        assertEquals(original.getMode(), restored.getMode());
    }

    @Test
    @DisplayName("fromMap: null → 默认配置")
    void testFromMapNull() {
        ThinkConfig tc = ThinkConfig.fromMap(null);
        assertFalse(tc.isEnabled());
        assertEquals(0, tc.getBudgetTokens());
    }
}
