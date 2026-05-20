package com.kwcode.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hashline单元测试
 * @origin kaiwu/tools/hashline.py::hashline
 */
class HashlineTest {

    private Hashline hashline;

    @BeforeEach
    void setUp() {
        hashline = new Hashline();
    }

    @Test
    @DisplayName("addAnchors应在内容行添加锚点")
    void testAddAnchors() {
        String content = "public class Test {\n    int x;\n}";
        String anchored = hashline.addAnchors(content);
        assertNotNull(anchored, "添加锚点后不应为null");
        // 格式: 行号|hash| 内容
        assertTrue(anchored.contains("|"), "锚点内容应包含|分隔符");
        assertTrue(anchored.contains("1|"), "应包含行号1");
    }

    @Test
    @DisplayName("stripAnchors应去除锚点标记")
    void testStripAnchors() {
        String content = "public class Test {\n    int x;\n}";
        String anchored = hashline.addAnchors(content);
        String stripped = hashline.stripAnchors(anchored);
        assertFalse(stripped.contains("|"), "去除后不应包含|分隔符");
    }

    @Test
    @DisplayName("addAnchors+stripAnchors应还原原文")
    void testRoundTrip() {
        String original = "line1\nline2\nline3";
        String anchored = hashline.addAnchors(original);
        String stripped = hashline.stripAnchors(anchored);
        assertEquals(original, stripped, "添加再去除锚点应还原原文");
    }

    @Test
    @DisplayName("parseAnchorEdits应返回非null")
    void testParseAnchorEditsEmpty() {
        var edits = hashline.parseAnchorEdits("");
        assertNotNull(edits, "解析结果不应为null");
    }
}
