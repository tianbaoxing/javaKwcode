package com.kwcode.core.cognitive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CognitiveGate熔断器单元测试
 * @origin kaiwu/core/cognitive_gate.py::CognitiveGate
 */
class CognitiveGateTest {

    private CognitiveGate gate;

    @BeforeEach
    void setUp() {
        gate = new CognitiveGate();
    }

    @Test
    @DisplayName("初始状态不应熔断")
    void testInitialState() {
        // CognitiveGate.shouldStop()无参数，需设置内部状态
        // 初始状态默认不熔断
        var decision = gate.shouldStop();
        assertFalse(decision.shouldStop(),
                "初始状态不应触发熔断");
    }

    @Test
    @DisplayName("shouldStop返回StopDecision record")
    void testShouldStopReturnsDecision() {
        var decision = gate.shouldStop();
        assertNotNull(decision, "应返回非null的StopDecision");
        assertNotNull(decision.reason(), "reason字段不应为null");
    }
}
