package com.kwcode.core.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 检查点管理器 - 任务执行前的文件快照
 * <p>
 * Git仓库使用git stash，非Git仓库复制文件到 ~/.kwcode/checkpoints/。
 * 失败必须报告给用户，不允许静默失败（P1-RED-3）。
 * 非Git仓库使用文件复制后备方案（P1-FLEX-1）。
 * </p>
 * @origin Python: core.checkpoint.Checkpoint
 */
public class CheckpointManager {

    private static final Logger log = LoggerFactory.getLogger(CheckpointManager.class);
    private static final String STASH_PREFIX = "kwcode-checkpoint";
    private static final Path CHECKPOINT_DIR = Path.of(System.getProperty("user.home"), ".kwcode", "checkpoints");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path projectRoot;
    private final boolean isGit;
    private final String stashName;
    private Path fileBackupDir;
    private boolean saved = false;

    public CheckpointManager(String projectRoot) {
        this.projectRoot = Path.of(projectRoot).toAbsolutePath();
        this.isGit = Files.exists(this.projectRoot.resolve(".git"));
        this.stashName = STASH_PREFIX + "-" + System.currentTimeMillis() / 1000;
    }

    /**
     * 创建快照
     * <p>
     * 任务执行前创建文件快照。Git仓库用git stash，非Git仓库用文件复制。
     * 返回false表示失败，调用方必须通知用户（P1-RED-3）。
     * </p>
     * @origin Python: core.checkpoint.Checkpoint.save(modified_files: list[str]|None) -> bool
     * @param modifiedFiles 需要备份的文件列表，null表示自动扫描
     * @return true表示成功
     */
    public boolean save(List<String> modifiedFiles) {
        try {
            if (!Files.exists(projectRoot)) {
                log.debug("[checkpoint] project_root does not exist: {}", projectRoot);
                return false;
            }
            if (isGit) {
                return gitStash();
            } else {
                return fileCopy(modifiedFiles != null ? modifiedFiles : List.of());
            }
        } catch (Exception e) {
            log.warn("[checkpoint] save failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 恢复到快照状态
     * @origin Python: core.checkpoint.Checkpoint.restore() -> bool
     * @return true表示恢复成功
     */
    public boolean restore() {
        if (!saved) return false;
        try {
            return isGit ? gitStashPop() : fileRestore();
        } catch (Exception e) {
            log.warn("[checkpoint] restore failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 丢弃快照（任务成功后调用）
     * @origin Python: core.checkpoint.Checkpoint.discard()
     */
    public void discard() {
        if (!saved) return;
        try {
            if (isGit) {
                new ProcessBuilder("git", "stash", "drop")
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            } else if (fileBackupDir != null) {
                deleteRecursively(fileBackupDir);
            }
        } catch (Exception e) {
            // 清理失败不影响任务
        }
    }

    /** Git stash快照 */
    private boolean gitStash() throws Exception {
        var pb = new ProcessBuilder("git", "stash", "push", "--include-untracked", "-m", stashName);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int rc = proc.waitFor();
        if (rc == 0) {
            saved = !output.contains("No local changes");
            return true;
        }
        log.warn("[checkpoint] git stash failed: {}", output);
        return false;
    }

    /** Git stash pop恢复 */
    private boolean gitStashPop() throws Exception {
        var pb = new ProcessBuilder("git", "stash", "pop");
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes(); // drain
        return proc.waitFor() == 0;
    }

    /**
     * 非Git后备：文件复制备份
     * @origin Python: core.checkpoint.Checkpoint._file_copy(files: list[str]) -> bool
     */
    private boolean fileCopy(List<String> files) throws IOException {
        Path backupDir = CHECKPOINT_DIR.resolve(stashName);
        Files.createDirectories(backupDir);
        this.fileBackupDir = backupDir;

        // 自动扫描项目代码文件
        if (files.isEmpty()) {
            String[] exts = {".py", ".js", ".ts", ".go", ".rs", ".java", ".html", ".css"};
            Set<String> skipParts = Set.of(".git", "__pycache__", "node_modules", ".venv");
            for (String ext : exts) {
                try (Stream<Path> paths = Files.walk(projectRoot)) {
                    paths.filter(p -> p.toString().endsWith(ext))
                         .filter(p -> skipParts.stream().noneMatch(s -> p.toString().contains(s)))
                         .forEach(p -> files.add(p.toString()));
                }
            }
        }

        if (files.isEmpty()) {
            saved = true;
            return true;
        }

        // 复制文件并记录manifest
        Map<String, String> manifest = new LinkedHashMap<>();
        for (String f : files) {
            Path src = Path.of(f);
            if (!Files.exists(src)) continue;
            Path rel = projectRoot.relativize(src);
            Path dst = backupDir.resolve(rel);
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            manifest.put(rel.toString(), src.toString());
        }

        // 保存manifest
        Path manifestPath = backupDir.resolve("_manifest.json");
        MAPPER.writeValue(manifestPath.toFile(), manifest);
        saved = true;
        return true;
    }

    /**
     * 非Git后备：从备份恢复文件
     * @origin Python: core.checkpoint.Checkpoint._file_restore() -> bool
     */
    @SuppressWarnings("unchecked")
    private boolean fileRestore() throws IOException {
        if (fileBackupDir == null) return false;

        Path manifestPath = fileBackupDir.resolve("_manifest.json");
        if (Files.exists(manifestPath)) {
            try {
                Map<String, String> manifest = MAPPER.readValue(manifestPath.toFile(), Map.class);
                for (var entry : manifest.entrySet()) {
                    Path backupFile = fileBackupDir.resolve(entry.getKey());
                    Path original = Path.of(entry.getValue());
                    if (Files.exists(backupFile)) {
                        Files.copy(backupFile, original, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return true;
            } catch (Exception e) {
                log.debug("Manifest JSON damaged, falling back to name-based restore");
            }
        }

        // 后备：按文件名恢复
        try (Stream<Path> paths = Files.walk(fileBackupDir)) {
            List<Path> backupFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> !p.getFileName().toString().equals("_manifest.json"))
                .toList();
            for (Path backupFile : backupFiles) {
                try (Stream<Path> candidates = Files.walk(projectRoot)) {
                    candidates.filter(p -> p.getFileName().equals(backupFile.getFileName()))
                              .findFirst()
                              .ifPresent(target -> {
                                  try {
                                      Files.copy(backupFile, target, StandardCopyOption.REPLACE_EXISTING);
                                  } catch (IOException e) { /* ignore */ }
                              });
                }
            }
        }
        return true;
    }

    /** 递归删除目录 */
    private void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                     .map(Path::toFile)
                     .forEach(File::delete);
            }
        }
    }
}
