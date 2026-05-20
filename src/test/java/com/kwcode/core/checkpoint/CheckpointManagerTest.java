package com.kwcode.core.checkpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CheckpointManager单元测试
 * @origin kaiwu/core/checkpoint.py::CheckpointManager
 */
class CheckpointManagerTest {

    private CheckpointManager manager;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("checkpoint-test");
        manager = new CheckpointManager(tempDir.toString());
    }

    @Test
    @DisplayName("初始状态restore应返回false（无快照）")
    void testNoInitialSnapshot() {
        boolean restored = manager.restore();
        assertFalse(restored, "初始状态无快照，restore应返回false");
    }

    @Test
    @DisplayName("save后restore应返回true")
    void testSaveAndRestore() throws IOException {
        Path testFile = tempDir.resolve("Test.java");
        Files.writeString(testFile, "public class Test {}");
        boolean saved = manager.save(List.of("Test.java"));
        assertTrue(saved, "save应返回true");
        boolean restored = manager.restore();
        assertTrue(restored, "有快照时restore应返回true");
    }

    @Test
    @DisplayName("discard后restore应返回false")
    void testDiscard() throws IOException {
        Path testFile = tempDir.resolve("Test.java");
        Files.writeString(testFile, "public class Test {}");
        manager.save(List.of("Test.java"));
        manager.discard();
        boolean restored = manager.restore();
        assertFalse(restored, "discard后restore应返回false");
    }
}
