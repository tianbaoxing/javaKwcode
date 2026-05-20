package com.kwcode.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExpertRegistry单元测试
 * @origin kaiwu/registry/expert_registry.py::ExpertRegistry
 */
class ExpertRegistryTest {

    private ExpertRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ExpertRegistry();
    }

    @Test
    @DisplayName("初始注册表应为空或包含内置专家")
    void testInitialRegistry() {
        List<Map<String, Object>> experts = registry.listExperts("");
        assertNotNull(experts, "专家列表不应为null");
    }

    @Test
    @DisplayName("注册专家后应能查到")
    void testRegisterAndList() {
        Map<String, Object> expertDef = new LinkedHashMap<>();
        expertDef.put("name", "test_expert");
        expertDef.put("description", "测试专家");
        expertDef.put("class", "com.kwcode.experts.TestExpert");
        expertDef.put("confidence", 0.8);
        expertDef.put("type", "");  // 空类型，与listExperts("")匹配
        registry.register(expertDef);
        List<Map<String, Object>> experts = registry.listExperts("");
        assertTrue(experts.size() >= 1, "注册后应能查到专家");
        assertEquals("test_expert", experts.get(0).get("name"));
    }
}
