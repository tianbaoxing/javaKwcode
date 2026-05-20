package com.kwcode.core.gate;

import com.kwcode.core.gap.GapDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate分类器单元测试
 * @origin kaiwu/core/gate.py::Gate
 */
class GateTest {

    private Gate gate;

    @BeforeEach
    void setUp() {
        gate = new Gate(null, null);
    }

    @Test
    @DisplayName("修复类任务应路由到locator_repair")
    void testRepairRouting() {
        Map<String, Object> result = gate.classify("修复这个bug", "", null);
        String expertType = (String) result.getOrDefault("expert_type", "chat");
        assertEquals("locator_repair", expertType,
                "修复类任务应路由到locator_repair专家");
    }

    @Test
    @DisplayName("生成类任务应路由到codegen")
    void testCodegenRouting() {
        Map<String, Object> result = gate.classify("写一个快速排序算法", "", null);
        String expertType = (String) result.getOrDefault("expert_type", "chat");
        assertEquals("codegen", expertType,
                "生成代码类任务应路由到codegen专家");
    }

    @Test
    @DisplayName("重构类任务应路由到refactor")
    void testRefactorRouting() {
        Map<String, Object> result = gate.classify("重构这个函数", "", null);
        String expertType = (String) result.getOrDefault("expert_type", "chat");
        assertEquals("refactor", expertType,
                "重构类任务应路由到refactor专家");
    }

    @Test
    @DisplayName("文档类任务应路由到doc")
    void testDocRouting() {
        Map<String, Object> result = gate.classify("添加文档注释", "", null);
        String expertType = (String) result.getOrDefault("expert_type", "chat");
        assertEquals("doc", expertType,
                "文档类任务应路由到doc专家");
    }

    @Test
    @DisplayName("未知任务默认路由到chat")
    void testDefaultChatRouting() {
        Map<String, Object> result = gate.classify("你好", "", null);
        String expertType = (String) result.getOrDefault("expert_type", "chat");
        assertEquals("chat", expertType,
                "未知任务应默认路由到chat专家");
    }

    @Test
    @DisplayName("分类结果应包含expert_type字段")
    void testResultContainsExpertType() {
        Map<String, Object> result = gate.classify("测试任务", "", null);
        assertTrue(result.containsKey("expert_type"),
                "分类结果必须包含expert_type字段");
    }
}
