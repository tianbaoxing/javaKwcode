package com.kwcode.core.checkpoint;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * 快照 - 文件状态的时间点快照
 * <p>
 * Snapshot记录一组文件在某个时间点的内容，
 * 用于回退到之前的状态（progress_stall场景）。
 * 与CheckpointManager的区别：
 * - CheckpointManager: 任务开始前创建整体快照（git stash/文件复制）
 * - Snapshot: 记录过程中最优状态，用于回退
 * </p>
 * @origin Python: core.checkpoint.Snapshot
 */
public class Snapshot {

    private static final Logger log = LoggerFactory.getLogger(Snapshot.class);

    private final String snapshotId;
    private final Instant createdAt;
    private final Map<String, String> fileContents;
    private final int testsPassed;
    private final String label;

    public Snapshot(String snapshotId, Map<String, String> fileContents, int testsPassed, String label) {
        this.snapshotId = snapshotId;
        this.createdAt = Instant.now();
        this.fileContents = new LinkedHashMap<>(fileContents);
        this.testsPassed = testsPassed;
        this.label = label != null ? label : "";
    }

    /**
     * 从当前文件系统创建快照
     * <p>
     * 读取指定文件列表的当前内容，创建快照。
     * </p>
     * @origin Python: core.checkpoint.Snapshot.from_files(file_paths, project_root, tests_passed) -> Snapshot
     * @param filePaths 文件路径列表
     * @param projectRoot 项目根目录
     * @param testsPassed 当前通过测试数
     * @return 快照
     */
    public static Snapshot fromFiles(List<String> filePaths, String projectRoot, int testsPassed) {
        String id = "snap-" + System.currentTimeMillis() / 1000;
        Map<String, String> contents = new LinkedHashMap<>();

        for (String relPath : filePaths) {
            Path fullPath = Path.of(projectRoot, relPath);
            try {
                String content = Files.readString(fullPath);
                contents.put(relPath, content);
            } catch (IOException e) {
                log.debug("[snapshot] Cannot read {}: {}", relPath, e.getMessage());
            }
        }

        return new Snapshot(id, contents, testsPassed, "auto");
    }

    /**
     * 恢复快照（将文件内容写回磁盘）
     * <p>
     * P1-RED-3: 恢复失败必须报告，不允许静默。
     * </p>
     * @origin Python: core.checkpoint.Snapshot.restore(project_root) -> bool
     * @param projectRoot 项目根目录
     * @return true表示全部恢复成功
     */
    public boolean restore(String projectRoot) {
        boolean allOk = true;
        for (var entry : fileContents.entrySet()) {
            Path fullPath = Path.of(projectRoot, entry.getKey());
            try {
                Files.writeString(fullPath, entry.getValue());
                log.debug("[snapshot] Restored {}", entry.getKey());
            } catch (IOException e) {
                log.warn("[snapshot] Failed to restore {}: {}", entry.getKey(), e.getMessage());
                allOk = false;
            }
        }

        if (allOk) {
            log.info("[snapshot] Restored {} files (id={}, tests_passed={})",
                fileContents.size(), snapshotId, testsPassed);
        }

        return allOk;
    }

    /**
     * 转换为Map（序列化用）
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("snapshot_id", snapshotId);
        map.put("created_at", createdAt.toString());
        map.put("file_count", fileContents.size());
        map.put("tests_passed", testsPassed);
        map.put("label", label);
        map.put("files", new ArrayList<>(fileContents.keySet()));
        return map;
    }

    public String getSnapshotId() { return snapshotId; }
    public Instant getCreatedAt() { return createdAt; }
    public Map<String, String> getFileContents() { return Collections.unmodifiableMap(fileContents); }
    public int getTestsPassed() { return testsPassed; }
    public String getLabel() { return label; }
    public int getFileCount() { return fileContents.size(); }
}
