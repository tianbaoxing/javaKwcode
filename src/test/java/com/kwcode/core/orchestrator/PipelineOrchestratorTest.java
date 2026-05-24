package com.kwcode.core.orchestrator;

import com.kwcode.core.context.TaskContext;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineOrchestratorTest {

    private PipelineOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new PipelineOrchestrator(
            null, null, null, null, null, null, null, null, null, null, null, null
        );
    }

    @Test
    @DisplayName("buildRetryHint: syntax类型包含模板变量替换")
    void testBuildRetryHintSyntax() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "syntax", "SyntaxError at line 5", "main.py", 5
        );

        String hint = invokeBuildRetryHint(ctx, "syntax");
        assertTrue(hint.contains("main.py"), "hint应包含error_file替换结果");
        assertTrue(hint.contains("5"), "hint应包含error_line替换结果");
        assertTrue(hint.contains("语法错误"));
    }

    @Test
    @DisplayName("buildRetryHint: assertion类型包含error_message")
    void testBuildRetryHintAssertion() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "assertion", "expected 5 got 3", "test.py", 10
        );

        String hint = invokeBuildRetryHint(ctx, "assertion");
        assertTrue(hint.contains("expected 5 got 3"), "hint应包含error_message");
        assertTrue(hint.contains("断言"));
    }

    @Test
    @DisplayName("buildRetryHint: patch_apply类型包含read_file提示")
    void testBuildRetryHintPatchApply() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "patch_apply", "patch failed", "", 0
        );

        String hint = invokeBuildRetryHint(ctx, "patch_apply");
        assertTrue(hint.contains("read_file"));
    }

    @Test
    @DisplayName("buildRetryHint: import类型hint为空")
    void testBuildRetryHintImport() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "import", "import error", "", 0
        );

        String hint = invokeBuildRetryHint(ctx, "import");
        assertTrue(hint.isEmpty() || hint.startsWith("\n\n上次"), "import hint模板应为空");
    }

    @Test
    @DisplayName("buildRetryHint: unknown类型包含±20行提示")
    void testBuildRetryHintUnknown() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "unknown", "something wrong", "", 0
        );

        String hint = invokeBuildRetryHint(ctx, "unknown");
        assertTrue(hint.contains("±20行"));
    }

    @Test
    @DisplayName("buildRetryHint: 携带上次生成代码")
    void testBuildRetryHintWithLastCode() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "syntax", "error", "main.py", 5
        );
        ctx.generatorOutput = new TaskContext.GeneratorResult(
            List.of(new TaskContext.Patch("main.py", "old", "def foo():\n    return 1\n")),
            "generated"
        );

        String hint = invokeBuildRetryHint(ctx, "syntax");
        assertTrue(hint.contains("上次生成的代码"), "应追加上次生成代码");
        assertTrue(hint.contains("请不要重复同样的错误"));
    }

    @Test
    @DisplayName("buildRetryHint: 上次生成代码截断到300字符")
    void testBuildRetryHintLastCodeTruncation() throws Exception {
        TaskContext ctx = new TaskContext();
        ctx.verifierOutput = new TaskContext.VerifierResult(
            false, false, 0, 1, "syntax", "error", "main.py", 5
        );
        String longCode = "x".repeat(500);
        ctx.generatorOutput = new TaskContext.GeneratorResult(
            List.of(new TaskContext.Patch("main.py", "old", longCode)),
            "generated"
        );

        String hint = invokeBuildRetryHint(ctx, "syntax");
        assertTrue(hint.contains("上次生成的代码"));
        int codeStart = hint.indexOf("上次生成的代码");
        String codeSection = hint.substring(codeStart);
        assertTrue(codeSection.length() < 400, "代码部分应被截断");
    }

    @Test
    @DisplayName("buildRetryHint: verifierOutput为null时不崩溃")
    void testBuildRetryHintNullVerifier() throws Exception {
        TaskContext ctx = new TaskContext();
        String hint = invokeBuildRetryHint(ctx, "syntax");
        assertNotNull(hint);
    }

    @Test
    @DisplayName("RETRY_STRATEGIES_MAP: 包含6种错误类型")
    void testRetryStrategiesMapComplete() throws Exception {
        var field = PipelineOrchestrator.class.getDeclaredField("RETRY_STRATEGIES_MAP");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> map = (Map<String, ?>) field.get(null);
        assertTrue(map.containsKey("syntax"));
        assertTrue(map.containsKey("assertion"));
        assertTrue(map.containsKey("import"));
        assertTrue(map.containsKey("patch_apply"));
        assertTrue(map.containsKey("runtime"));
        assertTrue(map.containsKey("unknown"));
    }

    @Test
    @DisplayName("RETRY_STRATEGIES_MAP: syntax序列为[generator, verifier]")
    void testRetryStrategiesMapSyntaxSequence() throws Exception {
        var field = PipelineOrchestrator.class.getDeclaredField("RETRY_STRATEGIES_MAP");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) field.get(null);
        Object syntax = map.get("syntax");

        var seqMethod = syntax.getClass().getDeclaredMethod("sequence");
        seqMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> seq = (List<String>) seqMethod.invoke(syntax);
        assertEquals(List.of("generator", "verifier"), seq);
    }

    @Test
    @DisplayName("RETRY_STRATEGIES_MAP: import序列为[import_fixer, verifier]")
    void testRetryStrategiesMapImportSequence() throws Exception {
        var field = PipelineOrchestrator.class.getDeclaredField("RETRY_STRATEGIES_MAP");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) field.get(null);
        Object importStrat = map.get("import");

        var seqMethod = importStrat.getClass().getDeclaredMethod("sequence");
        seqMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> seq = (List<String>) seqMethod.invoke(importStrat);
        assertEquals(List.of("import_fixer", "verifier"), seq);
    }

    @Test
    @DisplayName("RETRY_STRATEGIES_MAP: patch_apply序列为[locator, generator, verifier]")
    void testRetryStrategiesMapPatchApplySequence() throws Exception {
        var field = PipelineOrchestrator.class.getDeclaredField("RETRY_STRATEGIES_MAP");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) field.get(null);
        Object pa = map.get("patch_apply");

        var seqMethod = pa.getClass().getDeclaredMethod("sequence");
        seqMethod.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> seq = (List<String>) seqMethod.invoke(pa);
        assertEquals(List.of("locator", "generator", "verifier"), seq);
    }

    @Test
    @DisplayName("常量: MAX_RETRIES=5, HARD_MAX_RETRIES=10, FREE_SYNTAX_RETRIES=2")
    void testConstants() {
        assertEquals(5, PipelineOrchestrator.MAX_RETRIES);
        assertEquals(10, PipelineOrchestrator.HARD_MAX_RETRIES);
        assertEquals(2, PipelineOrchestrator.FREE_SYNTAX_RETRIES);
    }

    @Test
    @DisplayName("常量: TASK_TIMEOUT_SECONDS=300")
    void testTimeoutConstant() {
        assertEquals(300, PipelineOrchestrator.TASK_TIMEOUT_SECONDS);
    }

    @Test
    @DisplayName("setDefaultModelName: 设置默认模型名称")
    void testSetDefaultModelName() {
        orchestrator.setDefaultModelName("deepseek/deepseek-v4-pro");
        assertEquals("deepseek/deepseek-v4-pro", orchestrator.getDefaultModelName());
    }

    @Test
    @DisplayName("setDefaultModelName: null值转为空字符串")
    void testSetDefaultModelNameNull() {
        orchestrator.setDefaultModelName(null);
        assertEquals("", orchestrator.getDefaultModelName());
    }

    @Test
    @DisplayName("setDefaultModelName: 默认值为空字符串")
    void testDefaultModelNameEmpty() {
        assertEquals("", orchestrator.getDefaultModelName());
    }

    private String invokeBuildRetryHint(TaskContext ctx, String errorType) throws Exception {
        Method method = PipelineOrchestrator.class.getDeclaredMethod(
            "buildRetryHint", TaskContext.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(orchestrator, ctx, errorType);
    }
}
