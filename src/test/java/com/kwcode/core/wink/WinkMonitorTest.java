package com.kwcode.core.wink;

import com.kwcode.core.EventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WinkMonitor偏离检测单元测试
 * @origin kaiwu/core/wink.py::WinkMonitor
 */
class WinkMonitorTest {

    private WinkMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new WinkMonitor();
    }

    @Test
    @DisplayName("WinkMonitor应正常创建")
    void testCreation() {
        assertNotNull(monitor, "WinkMonitor应正常创建");
    }

    @Test
    @DisplayName("check无偏离时返回null")
    void testCheckNoDrift() {
        WinkMonitor.WinkContext ctx = new WinkMonitor.WinkContext();
        ctx.gateResult = java.util.Map.of();
        ctx.retryCount = 1;
        ctx.prevTestsPassed = 0;
        String result = monitor.check(ctx, null);
        // 无偏离时返回null
        assertNull(result, "无偏离时check应返回null");
    }
}
