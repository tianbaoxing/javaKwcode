package com.kwcode.core.gap;

import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GapDetectorTest {

    private GapDetector detector;

    @BeforeEach
    void setUp() {
        detector = new GapDetector();
    }

    @Test
    @DisplayName("compute: 空输出 → NO_TEST")
    void testComputeEmptyOutput() {
        var gap = detector.compute("", ".");
        assertEquals(GapDetector.GapType.NO_TEST, gap.gapType());
        assertEquals(0.9, gap.confidence(), 0.01);
    }

    @Test
    @DisplayName("compute: null输出 → NO_TEST")
    void testComputeNullOutput() {
        var gap = detector.compute(null, ".");
        assertEquals(GapDetector.GapType.NO_TEST, gap.gapType());
    }

    @Test
    @DisplayName("compute: 工具链缺失 → MISSING_TOOLCHAIN")
    void testComputeMissingToolchain() {
        var gap = detector.compute("go: not found in PATH", ".");
        assertEquals(GapDetector.GapType.MISSING_TOOLCHAIN, gap.gapType());
        assertEquals(0.95, gap.confidence(), 0.01);
    }

    @Test
    @DisplayName("compute: ImportError → MISSING_DEP")
    void testComputeMissingDep() {
        var gap = detector.compute("ModuleNotFoundError: No module named 'flask'", ".");
        assertEquals(GapDetector.GapType.MISSING_DEP, gap.gapType());
        assertTrue(gap.suggestion().contains("flask"));
    }

    @Test
    @DisplayName("compute: NotImplementedError → NOT_IMPLEMENTED")
    void testComputeNotImplemented() {
        var gap = detector.compute("NotImplementedError: method not implemented", ".");
        assertEquals(GapDetector.GapType.NOT_IMPLEMENTED, gap.gapType());
        assertEquals(0.9, gap.confidence(), 0.01);
    }

    @Test
    @DisplayName("compute: NoneType错误 → STUB_RETURNS_NONE")
    void testComputeStubReturnsNone() {
        var gap = detector.compute("NoneType object has no attribute 'split'", ".");
        assertEquals(GapDetector.GapType.STUB_RETURNS_NONE, gap.gapType());
    }

    @Test
    @DisplayName("compute: SyntaxError → SYNTAX_STRUCTURAL")
    void testComputeSyntaxError() {
        var gap = detector.compute("SyntaxError: invalid syntax", ".");
        assertEquals(GapDetector.GapType.SYNTAX_STRUCTURAL, gap.gapType());
    }

    @Test
    @DisplayName("compute: AssertionError → LOGIC_ERROR")
    void testComputeLogicError() {
        var gap = detector.compute("AssertionError: expected 5 got 3", ".");
        assertEquals(GapDetector.GapType.LOGIC_ERROR, gap.gapType());
    }

    @Test
    @DisplayName("compute: FAILED → LOGIC_ERROR")
    void testComputeGoFail() {
        var gap = detector.compute("--- FAIL: TestAdd", ".");
        assertEquals(GapDetector.GapType.LOGIC_ERROR, gap.gapType());
    }

    @Test
    @DisplayName("compute: 所有测试通过 → NONE")
    void testComputeAllPassed() {
        var gap = detector.compute("5 passed in 0.3s", ".");
        assertEquals(GapDetector.GapType.NONE, gap.gapType());
        assertEquals(1.0, gap.confidence(), 0.01);
    }

    @Test
    @DisplayName("compute: 未知输出 → UNKNOWN")
    void testComputeUnknown() {
        var gap = detector.compute("some random output without patterns", ".");
        assertEquals(GapDetector.GapType.UNKNOWN, gap.gapType());
    }

    @Test
    @DisplayName("compute: 提取错误文件路径")
    void testComputeExtractErrorFiles() {
        String output = "File \"src/main.py\", line 42\nAssertionError: wrong value";
        var gap = detector.compute(output, ".");
        assertEquals(GapDetector.GapType.LOGIC_ERROR, gap.gapType());
        assertFalse(gap.files().isEmpty());
        assertTrue(gap.files().get(0).contains("main.py"));
    }

    @Test
    @DisplayName("compute: 提取Go文件路径（需配合FAILED信号）")
    void testComputeExtractGoFiles() {
        String output = "src/handler.go:42: undefined: Foo\n--- FAIL: TestHandler";
        var gap = detector.compute(output, ".");
        assertFalse(gap.files().isEmpty());
    }

    @Test
    @DisplayName("GAP_TO_EXPERT_TYPE: NOT_IMPLEMENTED → locator_repair")
    void testGapToExpertType() {
        assertEquals("locator_repair", GapDetector.GAP_TO_EXPERT_TYPE.get(GapDetector.GapType.NOT_IMPLEMENTED));
        assertEquals("env_fix", GapDetector.GAP_TO_EXPERT_TYPE.get(GapDetector.GapType.MISSING_TOOLCHAIN));
        assertEquals("codegen", GapDetector.GAP_TO_EXPERT_TYPE.get(GapDetector.GapType.NO_TEST));
    }

    @Test
    @DisplayName("scanSourceFiles: 补充完整文件路径")
    void testScanSourceFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("gap_test");
        try {
            Path srcDir = Files.createDirectory(tempDir.resolve("src"));
            Path pyFile = Files.writeString(srcDir.resolve("handler.py"), "def handle(): pass\n");

            var gap = detector.compute("File \"handler.py\", line 1\nNotImplementedError", tempDir.toString());
            var scanned = detector.scanSourceFiles(gap, tempDir.toString());
            assertFalse(scanned.files().isEmpty());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("scanSourceFiles: NONE/NO_TEST类型不扫描")
    void testScanSourceFilesSkipNone() {
        var gapNone = new GapDetector.Gap(GapDetector.GapType.NONE, 1.0, java.util.List.of(), java.util.List.of(), "", "");
        var result = detector.scanSourceFiles(gapNone, ".");
        assertSame(gapNone, result);
    }

    @Test
    @DisplayName("compute+findSourceFiles: files为空时自动扫描项目目录")
    void testFindSourceFilesWhenEmpty() throws IOException {
        Path tempDir = Files.createTempDirectory("gap_src_test");
        try {
            Path pyFile = Files.writeString(tempDir.resolve("calculator.py"), "def add(a, b): pass\n");
            var gap = detector.compute("NotImplementedError: not done", tempDir.toString());
            assertEquals(GapDetector.GapType.NOT_IMPLEMENTED, gap.gapType());
            assertFalse(gap.files().isEmpty(), "files为空时应自动扫描项目目录");
            assertTrue(gap.files().stream().anyMatch(f -> f.contains("calculator.py")));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("compute+scanStubsInFiles: 检测pass存根函数")
    void testScanStubsInFiles() throws IOException {
        Path tempDir = Files.createTempDirectory("gap_stub_test");
        try {
            Path pyFile = Files.writeString(tempDir.resolve("service.py"),
                "def process():\n    pass\n\ndef compute():\n    raise NotImplementedError\n\ndef working():\n    return 42\n");
            var gap = detector.compute("NotImplementedError", tempDir.toString());
            assertFalse(gap.functions().isEmpty(), "应检测到存根函数");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    @DisplayName("isStubReturnsNone: 多种NoneType模式")
    void testIsStubReturnsNonePatterns() {
        String output1 = "NoneType object is not iterable";
        var gap1 = detector.compute(output1, ".");
        assertEquals(GapDetector.GapType.STUB_RETURNS_NONE, gap1.gapType());

        String output2 = "NoneType object is not subscriptable";
        var gap2 = detector.compute(output2, ".");
        assertEquals(GapDetector.GapType.STUB_RETURNS_NONE, gap2.gapType());
    }

    @Test
    @DisplayName("compute: 优先级 - 工具链缺失 > 依赖缺失")
    void testPriorityToolchainOverDep() {
        String output = "go: not found\nModuleNotFoundError: No module named 'xyz'";
        var gap = detector.compute(output, ".");
        assertEquals(GapDetector.GapType.MISSING_TOOLCHAIN, gap.gapType());
    }

    @Test
    @DisplayName("compute: IndentationError → SYNTAX_STRUCTURAL")
    void testComputeIndentationError() {
        var gap = detector.compute("IndentationError: unexpected indent", ".");
        assertEquals(GapDetector.GapType.SYNTAX_STRUCTURAL, gap.gapType());
    }

    @Test
    @DisplayName("compute: 多个passed模式匹配")
    void testComputeAllPassedVariants() {
        var gap1 = detector.compute("PASS", ".");
        assertEquals(GapDetector.GapType.NONE, gap1.gapType());

        var gap2 = detector.compute("ok 1 - test case\nok 2 - another", ".");
        assertEquals(GapDetector.GapType.NONE, gap2.gapType());
    }

    @Test
    @DisplayName("compute: ChatModel不可用 → ENVIRONMENT")
    void testComputeLlmChatClientFailure() {
        var gap = detector.compute("没有可用的ChatModel，请确保Spring AI配置正确", ".");
        assertEquals(GapDetector.GapType.ENVIRONMENT, gap.gapType());
        assertEquals(0.95, gap.confidence(), 0.01);
        assertTrue(gap.suggestion().contains("ChatModel"));
    }

    @Test
    @DisplayName("compute: LLM call failed → ENVIRONMENT")
    void testComputeLlmCallFailed() {
        var gap = detector.compute("LLM call failed: connection timed out", ".");
        assertEquals(GapDetector.GapType.ENVIRONMENT, gap.gapType());
    }

    @Test
    @DisplayName("compute: API key未配置 → ENVIRONMENT")
    void testComputeLlmApiKeyMissing() {
        var gap = detector.compute("Error: API key not configured", ".");
        assertEquals(GapDetector.GapType.ENVIRONMENT, gap.gapType());
        assertTrue(gap.suggestion().contains("API密钥"));
    }

    @Test
    @DisplayName("compute: Connection refused → ENVIRONMENT")
    void testComputeLlmConnectionRefused() {
        var gap = detector.compute("java.net.ConnectException: Connection refused", ".");
        assertEquals(GapDetector.GapType.ENVIRONMENT, gap.gapType());
        assertTrue(gap.suggestion().contains("连接被拒绝"));
    }

    @Test
    @DisplayName("compute: rate limit → ENVIRONMENT")
    void testComputeLlmRateLimit() {
        var gap = detector.compute("HTTP 429: rate limit exceeded", ".");
        assertEquals(GapDetector.GapType.ENVIRONMENT, gap.gapType());
        assertTrue(gap.suggestion().contains("配额"));
    }

    @Test
    @DisplayName("compute: 优先级 - 工具链缺失 > LLM失败")
    void testPriorityToolchainOverLlm() {
        String output = "go: not found\nChatClient not available";
        var gap = detector.compute(output, ".");
        assertEquals(GapDetector.GapType.MISSING_TOOLCHAIN, gap.gapType());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        }
    }
}
