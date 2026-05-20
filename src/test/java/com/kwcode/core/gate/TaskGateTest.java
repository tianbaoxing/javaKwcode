package com.kwcode.core.gate;

import com.kwcode.core.ModelCapability;
import com.kwcode.core.ThinkConfig;
import com.kwcode.core.context.ContextPruner;
import com.kwcode.core.context.TaskContext;
import com.kwcode.core.gap.GapDetector;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskGateTest {

    private TaskGate taskGate;

    @BeforeEach
    void setUp() {
        ModelCapability.clearCache();
        ModelCapability.setUserConfigCtx(-1);
        taskGate = new TaskGate();
    }

    @AfterEach
    void tearDown() {
        ModelCapability.clearCache();
        ModelCapability.setUserConfigCtx(-1);
    }

    @Test
    @DisplayName("process: 修复类任务路由到locator_repair，expertType传入ThinkConfig")
    void testProcessRepairTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("修复这个bug", ctx, null);

        assertEquals("locator_repair", ctx.gateResult.get("expert_type"));
        assertNotNull(ctx.thinkConfig);
        assertFalse((boolean) ctx.thinkConfig.getOrDefault("think", false));
    }

    @Test
    @DisplayName("process: chat任务路由到chat")
    void testProcessChatTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("你好", ctx, null);
        assertEquals("chat", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: 生成类任务路由到codegen")
    void testProcessCodegenTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("写一个快速排序", ctx, null);
        assertEquals("codegen", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: configureThink传入expertType（非默认locator_repair）")
    void testConfigureThinkWithExpertType() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("写一个快速排序", ctx, null);

        String expertType = (String) ctx.gateResult.get("expert_type");
        assertEquals("codegen", expertType);

        // 验证ThinkConfig使用了codegen的配置（easy → think=false）
        assertFalse((boolean) ctx.thinkConfig.getOrDefault("think", false));
    }

    @Test
    @DisplayName("process: gap驱动路由覆盖")
    void testProcessGapOverride() {
        TaskContext ctx = new TaskContext();
        GapDetector.Gap gap = new GapDetector.Gap(
            GapDetector.GapType.NOT_IMPLEMENTED, 0.9,
            java.util.List.of(), java.util.List.of(), "NotImplementedError", "函数未实现"
        );

        ctx = taskGate.process("帮我处理一下", ctx, gap);
        assertEquals("locator_repair", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: modelTier和effectiveCtx被正确设置")
    void testConfigureModel() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("修复bug", ctx, null);

        assertNotNull(ctx.modelTier);
        assertTrue(ctx.effectiveCtx > 0);
    }

    @Test
    @DisplayName("process: config覆盖影响effectiveCtx")
    void testConfigureModelWithUserConfig() {
        ModelCapability.setUserConfigCtx(50000);
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("修复bug", ctx, null);

        int expectedEffective = (int) (50000 * 0.9);
        assertEquals(expectedEffective, ctx.effectiveCtx);
    }

    @Test
    @DisplayName("process: vision任务路由到vision")
    void testProcessVisionTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("[图片: screenshot.png] 分析这个界面", ctx, null);
        assertEquals("vision", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: office任务路由到office")
    void testProcessOfficeTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("帮我处理这个.xlsx文件", ctx, null);
        assertEquals("office", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: 重构任务路由到refactor")
    void testProcessRefactorTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("重构这个函数", ctx, null);
        assertEquals("refactor", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: 文档任务路由到doc")
    void testProcessDocTask() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("添加文档注释", ctx, null);
        assertEquals("doc", ctx.gateResult.get("expert_type"));
    }

    @Test
    @DisplayName("process: gateResult包含routing_source字段")
    void testRoutingSourcePresent() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("修复bug", ctx, null);
        assertTrue(ctx.gateResult.containsKey("routing_source"));
        assertEquals("keyword", ctx.gateResult.get("routing_source"));
    }

    @Test
    @DisplayName("process: gateResult包含confidence字段")
    void testConfidencePresent() {
        TaskContext ctx = new TaskContext();
        ctx = taskGate.process("修复bug", ctx, null);
        assertTrue(ctx.gateResult.containsKey("confidence"));
        double confidence = ((Number) ctx.gateResult.get("confidence")).doubleValue();
        assertTrue(confidence > 0 && confidence <= 1.0);
    }
}
