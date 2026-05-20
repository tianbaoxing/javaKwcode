package com.kwcode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectMd项目记忆单元测试
 * @origin kaiwu/memory/project_md.py::ProjectMd
 */
class ProjectMdTest {

    private ProjectMd projectMd;

    @BeforeEach
    void setUp() {
        projectMd = new ProjectMd();
    }

    @Test
    @DisplayName("ProjectMd应正常创建")
    void testCreation() {
        assertNotNull(projectMd, "ProjectMd应正常创建");
    }

    @Test
    @DisplayName("load应返回非null")
    void testLoad() {
        String result = projectMd.load(".");
        assertNotNull(result, "load应返回非null结果");
    }

    @Test
    @DisplayName("init应返回非null")
    void testInit() {
        String result = projectMd.init(".");
        assertNotNull(result, "init应返回非null结果");
    }

    @Test
    @DisplayName("show应返回非null")
    void testShow() {
        String result = projectMd.show(".");
        assertNotNull(result, "show应返回非null结果");
    }
}
