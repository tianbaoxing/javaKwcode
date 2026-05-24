package com.kwcode.core;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class ModelCapabilityTest {

    @AfterEach
    void tearDown() {
        ModelCapability.clearCache();
        ModelCapability.setUserConfigCtx(-1);
    }

    @Test
    @DisplayName("detectTier: 参数量后缀推断 - qwen3:8b → SMALL")
    void testDetectTierByParamSuffix() {
        assertEquals(ModelCapability.Tier.SMALL, ModelCapability.detectTier("qwen3:8b"));
    }

    @Test
    @DisplayName("detectTier: 参数量后缀推断 - qwen3:14b → MEDIUM")
    void testDetectTierMediumByParam() {
        assertEquals(ModelCapability.Tier.MEDIUM, ModelCapability.detectTier("qwen3:14b"));
    }

    @Test
    @DisplayName("detectTier: 参数量后缀推断 - qwen3:72b → LARGE")
    void testDetectTierLargeByParam() {
        assertEquals(ModelCapability.Tier.LARGE, ModelCapability.detectTier("qwen3:72b"));
    }

    @Test
    @DisplayName("detectTier: 精确匹配 - gpt-4o → LARGE")
    void testDetectTierExactMatch() {
        assertEquals(ModelCapability.Tier.LARGE, ModelCapability.detectTier("gpt-4o"));
    }

    @Test
    @DisplayName("detectTier: 未知模型默认MEDIUM")
    void testDetectTierDefault() {
        assertEquals(ModelCapability.Tier.MEDIUM, ModelCapability.detectTier("unknown-model-xyz"));
    }

    @Test
    @DisplayName("detectTier: null/空字符串默认MEDIUM")
    void testDetectTierNull() {
        assertEquals(ModelCapability.Tier.MEDIUM, ModelCapability.detectTier(null));
        assertEquals(ModelCapability.Tier.MEDIUM, ModelCapability.detectTier(""));
    }

    @Test
    @DisplayName("缓存: 两次调用同一模型返回相同结果且缓存生效")
    void testCacheHit() {
        ModelCapability.clearCache();
        ModelCapability.Tier t1 = ModelCapability.detectTier("qwen3:8b");
        ModelCapability.Tier t2 = ModelCapability.detectTier("qwen3:8b");
        assertEquals(t1, t2);
        assertEquals(ModelCapability.Tier.SMALL, t1);
    }

    @Test
    @DisplayName("缓存: clearCache后可重新计算")
    void testCacheClear() {
        ModelCapability.Tier before = ModelCapability.detectTier("qwen3:8b");
        ModelCapability.clearCache();
        ModelCapability.Tier after = ModelCapability.detectTier("qwen3:8b");
        assertEquals(before, after);
    }

    @Test
    @DisplayName("config覆盖: setUserConfigCtx后detectCtx返回覆盖值")
    void testUserConfigCtxOverride() {
        ModelCapability.setUserConfigCtx(99999);
        int ctx = ModelCapability.detectCtx("qwen3:8b");
        assertEquals(99999, ctx, "用户config覆盖应优先于模型表");
    }

    @Test
    @DisplayName("config覆盖: setUserConfigCtx(-1)恢复默认行为")
    void testUserConfigCtxReset() {
        ModelCapability.setUserConfigCtx(99999);
        assertEquals(99999, ModelCapability.detectCtx("qwen3:8b"));

        ModelCapability.setUserConfigCtx(-1);
        int ctx = ModelCapability.detectCtx("qwen3:8b");
        assertEquals(32768, ctx, "重置后应回到模型表默认值");
    }

    @Test
    @DisplayName("云API默认ctx: 非本地模型走CLOUD_DEFAULT_CTX=131072")
    void testCloudDefaultCtx() {
        int ctx = ModelCapability.detectCtx("some-unknown-cloud-model", false);
        assertEquals(131072, ctx, "非本地未知模型应走云API默认131072");
    }

    @Test
    @DisplayName("云API表: deepseek-v4 → 131072")
    void testCloudCtxDeepseekV4() {
        int ctx = ModelCapability.detectCtx("deepseek-v4", false);
        assertEquals(131072, ctx);
    }

    @Test
    @DisplayName("本地模型: 未知模型走tier默认值")
    void testLocalDefaultCtx() {
        int ctx = ModelCapability.detectCtx("some-unknown-local-model", true);
        ModelCapability.Tier tier = ModelCapability.detectTier("some-unknown-local-model");
        assertEquals(tier.getDefaultCtx(), ctx);
    }

    @Test
    @DisplayName("effectiveCtx: ctx * 0.9 折扣率")
    void testEffectiveCtx() {
        ModelCapability.setUserConfigCtx(-1);
        int ctx = ModelCapability.detectCtx("qwen3:8b");
        int effective = ModelCapability.getEffectiveCtx("qwen3:8b");
        assertEquals((int) (ctx * 0.9), effective);
    }

    @Test
    @DisplayName("effectiveCtx: 云API模型也按0.9折扣")
    void testEffectiveCtxCloud() {
        int effective = ModelCapability.getEffectiveCtx("deepseek-v4", false);
        assertEquals((int) (131072 * 0.9), effective);
    }

    @Test
    @DisplayName("deepseek-r1本地变体: :8b/:14b/:32b/:70b 都有65536 ctx")
    void testDeepseekR1LocalVariants() {
        assertEquals(65536, ModelCapability.detectCtx("deepseek-r1:8b"));
        assertEquals(65536, ModelCapability.detectCtx("deepseek-r1:14b"));
        assertEquals(65536, ModelCapability.detectCtx("deepseek-r1:32b"));
        assertEquals(65536, ModelCapability.detectCtx("deepseek-r1:70b"));
    }

    @Test
    @DisplayName("isReasoningModel: deepseek-r1/o1/qwq为推理模型")
    void testIsReasoningModel() {
        assertTrue(ModelCapability.isReasoningModel("deepseek-r1:70b"));
        assertTrue(ModelCapability.isReasoningModel("o1-preview"));
        assertTrue(ModelCapability.isReasoningModel("qwq-32b"));
        assertFalse(ModelCapability.isReasoningModel("gpt-4o"));
        assertFalse(ModelCapability.isReasoningModel("qwen3:14b"));
    }

    @Test
    @DisplayName("getCapability: 返回完整能力Map")
    void testGetCapability() {
        var cap = ModelCapability.getCapability("qwen3:8b");
        assertEquals("qwen3:8b", cap.get("model"));
        assertEquals("small", cap.get("tier"));
        assertTrue((int) cap.get("ctx_window") > 0);
        assertTrue((int) cap.get("effective_ctx") > 0);
        assertFalse((boolean) cap.get("is_reasoning"));
    }

    @Test
    @DisplayName("getStrategy: SMALL tier限制maxRetries=1, forcePlanMode=true")
    void testStrategySmall() {
        var s = ModelCapability.getStrategy(ModelCapability.Tier.SMALL);
        assertEquals(1, s.maxRetries);
        assertTrue(s.forcePlanMode);
    }

    @Test
    @DisplayName("getStrategy: LARGE tier允许maxRetries=5")
    void testStrategyLarge() {
        var s = ModelCapability.getStrategy(ModelCapability.Tier.LARGE);
        assertEquals(5, s.maxRetries);
        assertFalse(s.forcePlanMode);
    }

    @Test
    @DisplayName("tierDisplayName: 中文显示名")
    void testTierDisplayName() {
        assertEquals("小模型模式", ModelCapability.tierDisplayName(ModelCapability.Tier.SMALL));
        assertEquals("中等模型", ModelCapability.tierDisplayName(ModelCapability.Tier.MEDIUM));
        assertEquals("大模型模式", ModelCapability.tierDisplayName(ModelCapability.Tier.LARGE));
    }
}
