package com.kwcode.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 工具执行器 - 确定性工具执行层，不涉及LLM
 * <p>
 * 提供read_file, write_file, run_bash, list_dir, apply_patch, git_commit等工具。
 * 内置安全护栏：危险命令拦截、敏感文件自动备份、写操作限制在项目根目录内。
 * </p>
 * @origin Python: tools.executor.ToolExecutor
 */
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /** 危险命令模式 */
    public static final List<String> DANGEROUS_PATTERNS = List.of(
        "rm -rf", "rm -r /", "rmdir /s",
        "git push --force", "git push -f", "git reset --hard",
        "drop database", "drop table", "truncate table",
        "format c:", "del /f /s /q", "> /dev/null", "mkfs"
    );

    /** 受保护的敏感文件 */
    public static final List<String> PROTECTED_FILES = List.of(
        ".env", ".env.local", ".env.production",
        "credentials.json", "secrets.yaml", "id_rsa",
        ".ssh/", "token.json", "service_account.json"
    );

    private final String projectRoot;

    public ToolExecutor(String projectRoot) {
        this.projectRoot = Path.of(projectRoot).toAbsolutePath().toString();
    }

    /**
     * 读取文件内容
     * @origin Python: tools.executor.ToolExecutor.read_file(path) -> str
     * @param path 文件路径（相对或绝对）
     * @return 文件内容，失败返回[ERROR]前缀字符串
     */
    public String readFile(String path) {
        Path full = resolve(path);
        try {
            return Files.readString(full, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return "[ERROR] File not found: " + full;
        } catch (IOException e) {
            return "[ERROR] Read failed: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容
     * <p>
     * 安全护栏：限制在项目根目录内写入，敏感文件自动备份。
     * </p>
     * @origin Python: tools.executor.ToolExecutor.write_file(path, content) -> bool
     */
    public boolean writeFile(String path, String content) {
        Path full = resolve(path);
        Path root = Path.of(projectRoot).normalize();

        // 护栏：禁止写入项目根目录外
        if (!full.startsWith(root)) {
            log.warn("[guardrail] Blocked write outside project: full={}, projectRoot={}", full, root);
            return false;
        }

        // 护栏：敏感文件自动备份
        if (isProtected(full) && Files.exists(full)) {
            Path backup = Path.of(full + ".bak");
            try {
                Files.copy(full, backup, StandardCopyOption.REPLACE_EXISTING);
                log.info("[guardrail] Backed up sensitive file: {} → {}", full, backup);
            } catch (IOException e) {
                log.warn("[guardrail] Failed to backup {}: {}", full, e.getMessage());
            }
        }

        try {
            Path parent = full.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
                log.debug("[writeFile] created parent dir: {}", parent);
            }
            Files.writeString(full, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Wrote {} bytes to {}", content.length(), full);
            return true;
        } catch (IOException e) {
            log.error("Write failed: {} - {} [{}] parentExists={} parent={}", full, e.getMessage(), e.getClass().getSimpleName(), full.getParent() != null && Files.exists(full.getParent()), full.getParent());
            return false;
        }
    }

    /**
     * 执行Shell命令
     * <p>
     * 返回(stdout, stderr, returnCode)三元组。
     * 安全护栏：拦截危险命令。
     * </p>
     * @origin Python: tools.executor.ToolExecutor.run_bash(command, cwd, timeout) -> tuple
     */
    public BashResult runBash(String command, String cwd, int timeoutSec) {
        String blocked = checkDangerous(command);
        if (blocked != null) {
            log.warn("[guardrail] Blocked dangerous command: {}", command.substring(0, Math.min(80, command.length())));
            return new BashResult("", "[BLOCKED] 危险操作被拦截: " + blocked + "。如需执行请手动在终端运行。", -2);
        }

        String workDir = cwd != null ? cwd : projectRoot;
        try {
            var pb = new ProcessBuilder("cmd", "/c", command);
            pb.directory(Path.of(workDir).toFile());
            pb.redirectErrorStream(false);
            var proc = pb.start();

            String stdout = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean finished = proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return new BashResult("", "[ERROR] Command timed out after " + timeoutSec + "s", -1);
            }
            return new BashResult(stdout, stderr, proc.exitValue());
        } catch (Exception e) {
            return new BashResult("", "[ERROR] " + e.getMessage(), -1);
        }
    }

    public BashResult runBash(String command) {
        return runBash(command, null, 60);
    }

    /**
     * 列出目录内容
     * @origin Python: tools.executor.ToolExecutor.list_dir(path) -> list[str]
     */
    public List<String> listDir(String path) {
        Path full = resolve(path);
        try (Stream<Path> paths = Files.list(full)) {
            return paths.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (NoSuchFileException e) {
            return List.of("[ERROR] Directory not found: " + full);
        } catch (IOException e) {
            return List.of("[ERROR] " + e.getMessage());
        }
    }

    /**
     * 应用文本替换补丁（精确匹配）
     * @origin Python: tools.executor.ToolExecutor.apply_patch(file_path, original, modified) -> bool
     */
    public boolean applyPatch(String filePath, String original, String modified) {
        if (original == null || original.isEmpty()) {
            if (modified != null && !modified.isEmpty()) {
                Path resolved = resolve(filePath);
                if (Files.exists(resolved)) {
                    log.info("[apply_patch] file already exists, overwriting: filePath='{}'", filePath);
                    return writeFile(filePath, modified);
                }
                log.info("[apply_patch] creating new file: filePath='{}' resolved='{}' parentExists={}", filePath, resolved, resolved.getParent() != null && Files.exists(resolved.getParent()));
                return writeFile(filePath, modified);
            }
            log.warn("apply_patch called with empty original and modified, skip");
            return false;
        }
        String content = readFile(filePath);
        if (content.startsWith("[ERROR]")) return false;

        if (content.contains(original)) {
            String newContent = content.replaceFirst(java.util.regex.Pattern.quote(original), modified);
            return writeFile(filePath, newContent);
        }

        String normalizedOriginal = normalizeWhitespace(original);
        String normalizedContent = normalizeWhitespace(content);
        if (normalizedContent.contains(normalizedOriginal)) {
            int start = findApproximateStart(content, original);
            if (start >= 0) {
                String newContent = content.substring(0, start) + modified + content.substring(start + original.length());
                log.info("[apply_patch] fuzzy match succeeded for {}", resolve(filePath));
                return writeFile(filePath, newContent);
            }
        }

        if (content.length() < 500 && modified != null && !modified.isEmpty()) {
            log.info("[apply_patch] small file, falling back to full overwrite: {}", resolve(filePath));
            return writeFile(filePath, modified);
        }

        log.warn("Original text not found in {}", resolve(filePath));
        return false;
    }

    private String normalizeWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private int findApproximateStart(String content, String original) {
        String[] origLines = original.split("\n");
        if (origLines.length == 0) return -1;
        String firstLine = origLines[0].trim();
        if (firstLine.isEmpty() && origLines.length > 1) firstLine = origLines[1].trim();
        if (firstLine.isEmpty()) return -1;
        int idx = content.indexOf(firstLine);
        if (idx >= 0) return idx;
        String[] contentLines = content.split("\n");
        for (int ci = 0; ci < contentLines.length; ci++) {
            if (normalizeWhitespace(contentLines[ci]).equals(normalizeWhitespace(firstLine))) {
                int pos = 0;
                for (int k = 0; k < ci; k++) pos += contentLines[k].length() + 1;
                return pos;
            }
        }
        return -1;
    }

    /**
     * Git add + commit
     * @origin Python: tools.executor.ToolExecutor.git_commit(message, cwd) -> bool
     */
    public boolean gitCommit(String message, String cwd) {
        String workDir = cwd != null ? cwd : projectRoot;
        var addResult = runBash("git add -A", workDir, 30);
        if (addResult.returnCode() != 0) return false;
        var commitResult = runBash("git commit -m \"" + message + "\"", workDir, 30);
        return commitResult.returnCode() == 0;
    }

    /**
     * 生成文件树字符串（供Locator上下文注入）
     * @origin Python: tools.executor.ToolExecutor.get_file_tree(path, max_depth, max_files) -> str
     */
    public String getFileTree(String path, int maxDepth, int maxFiles) {
        Path root = resolve(path);
        StringBuilder sb = new StringBuilder();
        Set<String> skipDirs = Set.of("node_modules", "__pycache__", ".git", "venv", ".venv", "target");
        int[] count = {0};

        try (Stream<Path> paths = Files.walk(root, maxDepth)) {
            paths.filter(p -> {
                for (Path part : root.relativize(p)) {
                    if (part.toString().startsWith(".") || skipDirs.contains(part.toString())) return false;
                }
                return true;
            }).sorted().forEach(p -> {
                if (count[0] >= maxFiles) return;
                Path rel = root.relativize(p);
                int depth = rel.getNameCount() - 1;
                String indent = "  ".repeat(depth);
                if (Files.isDirectory(p)) {
                    sb.append(indent).append(p.getFileName()).append("/\n");
                } else {
                    sb.append(indent).append("  ").append(p.getFileName()).append("\n");
                }
                count[0]++;
            });
        } catch (IOException e) {
            return "[ERROR] " + e.getMessage();
        }

        if (count[0] >= maxFiles) sb.append("  ... (truncated at ").append(maxFiles).append(" files)\n");
        return sb.toString();
    }

    // ── 内部辅助 ──

    private Path resolve(String path) {
        Path p = Path.of(path);
        if (p.isAbsolute()) return p.normalize();
        return Path.of(projectRoot, path).normalize();
    }

    private String checkDangerous(String command) {
        String cmdLower = command.toLowerCase().strip();
        for (String pattern : DANGEROUS_PATTERNS) {
            if (cmdLower.contains(pattern)) return pattern;
        }
        return null;
    }

    private boolean isProtected(Path fullPath) {
        String pathStr = fullPath.toString().toLowerCase().replace('\\', '/');
        for (String protected_ : PROTECTED_FILES) {
            if (pathStr.contains(protected_)) return true;
        }
        return false;
    }

    /**
     * Shell命令执行结果
     */
    public record BashResult(String stdout, String stderr, int returnCode) {}
}
