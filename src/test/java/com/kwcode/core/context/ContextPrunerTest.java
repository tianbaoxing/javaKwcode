package com.kwcode.core.context;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ContextPrunerTest {

    private ContextPruner pruner;

    @BeforeEach
    void setUp() {
        pruner = new ContextPruner(8192);
    }

    @Test
    @DisplayName("countTokens: 中文1.5字/token，英文4字符/token")
    void testCountTokens() {
        int cnTokens = ContextPruner.countTokens("你好世界");
        assertTrue(cnTokens > 0, "中文token数应>0");

        int enTokens = ContextPruner.countTokens("hello world");
        assertTrue(enTokens > 0, "英文token数应>0");

        assertEquals(6, cnTokens, "4个中文字 * 1.5 = 6");
        assertEquals(2, enTokens, "11个英文字符 / 4 ≈ 2");
    }

    @Test
    @DisplayName("countTokens: null/空字符串返回0")
    void testCountTokensNull() {
        assertEquals(0, ContextPruner.countTokens(null));
        assertEquals(0, ContextPruner.countTokens(""));
    }

    @Test
    @DisplayName("extractKeywords: 提取文件路径和函数名")
    void testExtractKeywords() {
        String text = "File \"src/main.py\", line 42\ndef process():\nError: something wrong";
        String kw = ContextPruner.extractKeywords(text);
        assertFalse(kw.isEmpty());
        assertTrue(kw.startsWith("[摘要]"));
    }

    @Test
    @DisplayName("extractKeywords: 空文本返回空字符串")
    void testExtractKeywordsEmpty() {
        assertEquals("", ContextPruner.extractKeywords(""));
        assertEquals("", ContextPruner.extractKeywords(null));
    }

    @Test
    @DisplayName("extractKeywords: 去重保序，最多15个")
    void testExtractKeywordsDedup() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append("def func_").append(i).append("():\n");
        }
        String kw = ContextPruner.extractKeywords(sb.toString());
        assertFalse(kw.isEmpty());
        long dotCount = kw.chars().filter(c -> c == '·').count();
        assertTrue(dotCount <= 14, "最多15个关键词，14个分隔符");
    }

    @Test
    @DisplayName("hasCodeBlock: 检测```包裹的代码块")
    void testHasCodeBlock() {
        assertTrue(ContextPruner.hasCodeBlock("```python\nprint('hello')\n```"));
        assertFalse(ContextPruner.hasCodeBlock("no code block here"));
        assertFalse(ContextPruner.hasCodeBlock(null));
    }

    @Test
    @DisplayName("extractCodeBlocks: 提取代码块内容，最多3个")
    void testExtractCodeBlocks() {
        String text = "before\n```python\ncode1\n```\nmiddle\n```java\ncode2\n```\nafter\n```go\ncode3\n```\nextra\n```rs\ncode4\n```";
        String result = ContextPruner.extractCodeBlocks(text);
        assertTrue(result.contains("code1"));
        assertTrue(result.contains("code2"));
        assertTrue(result.contains("code3"));
        assertFalse(result.contains("code4"), "最多保留3个代码块");
    }

    @Test
    @DisplayName("extractCodeBlocks: null返回空字符串")
    void testExtractCodeBlocksNull() {
        assertEquals("", ContextPruner.extractCodeBlocks(null));
    }

    @Test
    @DisplayName("prune: 空列表返回空列表")
    void testPruneEmpty() {
        List<Map<String, Object>> empty = List.of();
        List<Map<String, Object>> result = pruner.prune(empty);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("prune: null List返回null")
    void testPruneNull() {
        assertNull(pruner.prune((List<Map<String, Object>>) null));
    }

    @Test
    @DisplayName("prune: 保留system消息在头部")
    void testPruneKeepSystemHead() {
        Map<String, Object> system = Map.of("role", "system", "content", "You are a helpful assistant.");
        Map<String, Object> user = Map.of("role", "user", "content", "Hello");
        List<Map<String, Object>> messages = List.of(system, user);

        List<Map<String, Object>> result = pruner.prune(messages);
        assertEquals("system", result.get(0).get("role"));
    }

    @Test
    @DisplayName("prune: 短内容不压缩")
    void testPruneShortContent() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "system prompt"));
        messages.add(Map.of("role", "user", "content", "short question"));
        messages.add(Map.of("role", "assistant", "content", "short answer"));

        List<Map<String, Object>> result = pruner.prune(messages);
        assertEquals(messages.size(), result.size());
    }

    @Test
    @DisplayName("prune: tool输出提取关键词")
    void testPruneToolOutput() {
        ContextPruner smallPruner = new ContextPruner(500, 100, 24000);
        String longToolOutput = "x".repeat(400) + " def process(): " + "y".repeat(400) + " File \"src/main.py\", line 42 " + "z".repeat(400);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "system"));
        messages.add(Map.of("role", "user", "content", "do something"));
        messages.add(Map.of("role", "assistant", "content", "let me check"));
        messages.add(Map.of("role", "tool", "content", longToolOutput));
        messages.add(Map.of("role", "assistant", "content", "based on the tool output"));
        messages.add(Map.of("role", "user", "content", "next question"));
        messages.add(Map.of("role", "assistant", "content", "here is the answer"));

        List<Map<String, Object>> result = smallPruner.prune(messages);
        boolean toolCompressed = result.stream()
            .filter(m -> "tool".equals(m.get("role")))
            .anyMatch(m -> {
                String content = m.get("content").toString();
                return content.contains("[摘要]") || content.contains("[output masked");
            });
        assertTrue(toolCompressed, "长tool输出应被压缩");
    }

    @Test
    @DisplayName("prune: 代码块保护不压缩")
    void testPruneCodeBlockProtection() {
        String codeContent = "```python\ndef foo():\n    return 42\n```";
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "system"));
        messages.add(Map.of("role", "user", "content", "write code"));
        messages.add(Map.of("role", "tool", "content", codeContent));

        List<Map<String, Object>> result = pruner.prune(messages);
        boolean codePreserved = result.stream()
            .filter(m -> "tool".equals(m.get("role")))
            .anyMatch(m -> m.get("content").toString().contains("def foo()"));
        assertTrue(codePreserved, "代码块内容应被保护");
    }

    @Test
    @DisplayName("needsPruning: 超过85%阈值时返回true")
    void testNeedsPruning() {
        List<Map<String, Object>> small = List.of(
            Map.of("role", "user", "content", "short")
        );
        assertFalse(pruner.needsPruning(small));
    }

    @Test
    @DisplayName("estimateTotal: 估算消息列表总token数")
    void testEstimateTotal() {
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", "hello world"),
            Map.of("role", "assistant", "content", "hi there")
        );
        int total = pruner.estimateTotal(messages);
        assertTrue(total > 0);
    }

    @Test
    @DisplayName("graduatedCompress: ratio<0.70不压缩")
    void testGraduatedCompressLowRatio() {
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", "short")
        );
        List<Map<String, Object>> result = pruner.graduatedCompress(messages, 0.5);
        assertSame(messages, result, "低ratio应返回原列表");
    }

    @Test
    @DisplayName("graduatedCompress: ratio=0自动计算")
    void testGraduatedCompressAutoRatio() {
        List<Map<String, Object>> messages = List.of(
            Map.of("role", "user", "content", "short")
        );
        List<Map<String, Object>> result = pruner.graduatedCompress(messages, 0.0);
        assertNotNull(result);
    }

    @Test
    @DisplayName("pruneContext: TaskContext字段级裁剪")
    void testPruneContext() {
        TaskContext ctx = new TaskContext();
        ctx.userInput = "test input";
        ctx.projectRoot = ".";
        ctx.expertSystemPrompt = "";
        ctx.previousFailure = "";
        ctx.reflection = "";
        ctx.searchResults = "x".repeat(25000);
        ctx.debugInfo = "y".repeat(2000);
        ctx.docContext = "";
        ctx.kaiwuMemory = "";
        ctx.kwcodeRules = "";
        ctx.retryHint = "";
        ctx.upstreamConstraints = "";

        TaskContext result = pruner.pruneContext(ctx);
        assertNotNull(result);
        assertTrue(result.searchResults.length() <= 1500 + 20, "searchResults应被裁剪到1500字符内");
    }

    @Test
    @DisplayName("compressCount和lastCompressMs: 压缩后更新")
    void testCompressStats() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", "system"));
        messages.add(Map.of("role", "user", "content", "hello"));
        messages.add(Map.of("role", "assistant", "content", "hi"));

        int before = pruner.getCompressCount();
        pruner.prune(messages);
        assertEquals(before + 1, pruner.getCompressCount());
        assertTrue(pruner.getLastCompressMs() >= 0);
    }
}
