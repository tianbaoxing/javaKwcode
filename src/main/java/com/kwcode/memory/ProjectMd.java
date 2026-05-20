package com.kwcode.memory;

import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PROJECT.md记忆 - 项目结构信息（语言、框架、测试命令等）
 * <p>
 * Spec §7.2: 从KAIWU.md拆分到 .kaiwu/PROJECT.md。
 * 包含三个分区：基础信息、已知结构规律、注意事项，
 * 各分区有独立的token限制，供不同流水线阶段注入。
 * </p>
 * @origin Python: memory.project_md
 */
public class ProjectMd {

    private static final Logger log = LoggerFactory.getLogger(ProjectMd.class);

    /** Gate阶段token限制（约500字符） */
    public static final int GATE_LIMIT = 500;
    /** Locator阶段token限制（约750字符） */
    public static final int LOCATOR_LIMIT = 750;
    /** Verifier阶段token限制（约250字符） */
    public static final int VERIFIER_LIMIT = 250;

    private static final String TEMPLATE = """
# Kaiwu 项目记忆
> 自动维护，请勿手动删除关键字段

## 基础信息
- 语言：%s
- 框架：%s
- 包管理：%s
- 测试命令：%s
- 主入口：%s
- 代码风格：%s

## 已知结构规律

## 注意事项
""";

    /**
     * 加载PROJECT.md全文
     * @origin Python: memory.project_md.load(project_root) -> str
     */
    public String load(String projectRoot) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) return "";
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read PROJECT.md: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 任务成功后更新PROJECT.md
     * @origin Python: memory.project_md.save(project_root, ctx)
     */
    public void save(String projectRoot, TaskContext ctx) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) {
            init(projectRoot);
        }
        if (!Files.exists(path)) return;

        String content = load(projectRoot);

        // 更新已知结构规律
        if (ctx.locatorOutput != null) {
            String existing = extractSection(content, "## 已知结构规律");
            Set<String> existingLines = new HashSet<>(Arrays.asList(existing.split("\n")));
            List<String> newLines = new ArrayList<>();
            for (String f : ctx.locatorOutput.relevantFiles().stream().limit(5).toList()) {
                String line = "- " + f;
                if (!existingLines.contains(line)) newLines.add(line);
            }
            for (String fn : ctx.locatorOutput.relevantFunctions().stream().limit(3).toList()) {
                String line = "- fn: " + fn;
                if (!existingLines.contains(line)) newLines.add(line);
            }
            if (!newLines.isEmpty()) {
                String updated = (existing + "\n" + String.join("\n", newLines)).strip();
                List<String> lines = Arrays.asList(updated.split("\n"));
                if (lines.size() > 30) lines = lines.subList(lines.size() - 30, lines.size());
                content = replaceSection(content, "## 已知结构规律", String.join("\n", lines));
            }
        }

        // 更新注意事项
        if (ctx.verifierOutput != null) {
            String notes = extractSection(content, "## 注意事项");
            Set<String> existingLines = new HashSet<>(Arrays.asList(notes.split("\n")));
            List<String> newLines = new ArrayList<>();
            if (ctx.verifierOutput.passed()) {
                if (ctx.verifierOutput.testsTotal() > 0) {
                    String line = "- 测试通过 " + ctx.verifierOutput.testsPassed() + "/" + ctx.verifierOutput.testsTotal();
                    if (!existingLines.contains(line)) newLines.add(line);
                }
            } else {
                String error = ctx.verifierOutput.errorDetail();
                if (error != null && !error.isEmpty()) {
                    String line = "- 注意: " + error.substring(0, Math.min(80, error.length()));
                    if (!existingLines.contains(line)) newLines.add(line);
                }
            }
            if (!newLines.isEmpty()) {
                String updated = (notes + "\n" + String.join("\n", newLines)).strip();
                List<String> lines = Arrays.asList(updated.split("\n"));
                if (lines.size() > 20) lines = lines.subList(lines.size() - 20, lines.size());
                content = replaceSection(content, "## 注意事项", String.join("\n", lines));
            }
        }

        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to write PROJECT.md: {}", e.getMessage());
        }
    }

    /**
     * 初始化PROJECT.md（自动检测项目信息）
     * @origin Python: memory.project_md.init(project_root) -> str
     */
    public String init(String projectRoot) {
        ensureDir(projectRoot);
        Path path = mdPath(projectRoot);
        if (Files.exists(path)) return "PROJECT.md already exists";

        String content = String.format(TEMPLATE,
            detectLanguage(projectRoot),
            detectFramework(projectRoot),
            detectPkgManager(projectRoot),
            detectTestCmd(projectRoot),
            detectEntry(projectRoot),
            detectCodeStyle(projectRoot)
        );

        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return "Created PROJECT.md at " + path;
        } catch (IOException e) {
            return "Failed to create PROJECT.md: " + e.getMessage();
        }
    }

    /**
     * 显示PROJECT.md内容
     * @origin Python: memory.project_md.show(project_root) -> str
     */
    public String show(String projectRoot) {
        Path path = mdPath(projectRoot);
        if (!Files.exists(path)) return "PROJECT.md not found. Run init().";
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "Failed to read PROJECT.md: " + e.getMessage();
        }
    }

    /**
     * Gate阶段加载：只返回基础信息section
     * @origin Python: memory.project_md.load_for_gate(project_root) -> str
     */
    public String loadForGate(String projectRoot) {
        String content = load(projectRoot);
        if (content.isEmpty()) return "";
        String section = extractSection(content, "## 基础信息");
        if (section.isEmpty()) return "";
        String result = "项目信息：\n" + section + "\n";
        if (result.length() > GATE_LIMIT) result = result.substring(0, GATE_LIMIT) + "\n...(截断)";
        return result;
    }

    /**
     * Locator阶段加载：只返回已知结构规律section
     * @origin Python: memory.project_md.load_for_locator(project_root) -> str
     */
    public String loadForLocator(String projectRoot) {
        String content = load(projectRoot);
        if (content.isEmpty()) return "";
        String section = extractSection(content, "## 已知结构规律");
        if (section.isEmpty()) return "";
        String result = "已知结构规律：\n" + section + "\n";
        if (result.length() > LOCATOR_LIMIT) result = result.substring(0, LOCATOR_LIMIT) + "\n...(截断)";
        return result;
    }

    /**
     * Verifier阶段加载：只返回注意事项section
     * @origin Python: memory.project_md.load_for_verifier(project_root) -> str
     */
    public String loadForVerifier(String projectRoot) {
        String content = load(projectRoot);
        if (content.isEmpty()) return "";
        String section = extractSection(content, "## 注意事项");
        if (section.isEmpty()) return "";
        String result = "注意事项：\n" + section + "\n";
        if (result.length() > VERIFIER_LIMIT) result = result.substring(0, VERIFIER_LIMIT) + "\n...(截断)";
        return result;
    }

    // ── 检测辅助方法 ──

    private String detectLanguage(String projectRoot) {
        Map<String, Integer> counts = new HashMap<>();
        try (Stream<Path> paths = Files.walk(Path.of(projectRoot))) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> !p.toString().contains("node_modules") && !p.toString().contains(".git"))
                 .forEach(p -> {
                     String name = p.getFileName().toString().toLowerCase();
                     Map<String, String> extMap = Map.of(
                         ".py", "Python", ".js", "JavaScript", ".ts", "TypeScript",
                         ".go", "Go", ".rs", "Rust", ".java", "Java"
                     );
                     for (var e : extMap.entrySet()) {
                         if (name.endsWith(e.getKey())) counts.merge(e.getValue(), 1, Integer::sum);
                     }
                 });
        } catch (IOException e) { /* ignore */ }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse("未检测");
    }

    private String detectFramework(String projectRoot) {
        Map<String, Map<String, String>> indicators = Map.of(
            "requirements.txt", Map.of("fastapi", "FastAPI", "django", "Django", "flask", "Flask"),
            "pyproject.toml", Map.of("fastapi", "FastAPI", "django", "Django", "flask", "Flask"),
            "package.json", Map.of("react", "React", "vue", "Vue", "next", "Next.js", "express", "Express"),
            "go.mod", Map.of("gin", "Gin", "echo", "Echo", "fiber", "Fiber")
        );
        for (var entry : indicators.entrySet()) {
            Path fpath = Path.of(projectRoot, entry.getKey());
            if (Files.exists(fpath)) {
                try {
                    String content = Files.readString(fpath, StandardCharsets.UTF_8).toLowerCase();
                    for (var fw : entry.getValue().entrySet()) {
                        if (content.contains(fw.getKey())) return fw.getValue();
                    }
                } catch (IOException e) { /* ignore */ }
            }
        }
        return "未检测";
    }

    private String detectTestCmd(String projectRoot) {
        if (Files.exists(Path.of(projectRoot, "pytest.ini")) || Files.exists(Path.of(projectRoot, "pyproject.toml"))) return "pytest";
        if (Files.exists(Path.of(projectRoot, "package.json"))) return "npm test";
        if (Files.exists(Path.of(projectRoot, "go.mod"))) return "go test ./...";
        return "未检测";
    }

    private String detectEntry(String projectRoot) {
        for (String c : List.of("main.py", "app.py", "src/main.py", "src/app.py", "index.js", "main.go")) {
            if (Files.exists(Path.of(projectRoot, c))) return c;
        }
        return "未检测";
    }

    private String detectPkgManager(String projectRoot) {
        Map<String, String> checks = Map.of(
            "poetry.lock", "Poetry", "pdm.lock", "PDM", "requirements.txt", "pip",
            "yarn.lock", "Yarn", "pnpm-lock.yaml", "pnpm", "package-lock.json", "npm",
            "go.sum", "Go Modules", "Cargo.lock", "Cargo"
        );
        for (var e : checks.entrySet()) {
            if (Files.exists(Path.of(projectRoot, e.getKey()))) return e.getValue();
        }
        return "未检测";
    }

    private String detectCodeStyle(String projectRoot) {
        Map<String, String> styleFiles = Map.of(
            ".flake8", "Flake8", "ruff.toml", "Ruff", ".eslintrc.js", "ESLint",
            ".prettierrc", "Prettier", "biome.json", "Biome"
        );
        for (var e : styleFiles.entrySet()) {
            if (Files.exists(Path.of(projectRoot, e.getKey()))) return e.getValue();
        }
        return "未检测";
    }

    // ── Markdown section辅助 ──

    private String extractSection(String content, String header) {
        Pattern p = Pattern.compile(Pattern.quote(header) + "\n(.*?)(?=\n## |\\Z)", Pattern.DOTALL);
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1).strip() : "";
    }

    private String replaceSection(String content, String header, String newBody) {
        Pattern p = Pattern.compile(Pattern.quote(header) + "\n(.*?)(?=\n## |\\Z)", Pattern.DOTALL);
        String replacement = header + "\n" + newBody + "\n";
        String result = m_replaceSection_pmatcher(content, p, replacement);
        if (result.equals(content)) {
            result = content.stripTrailing() + "\n\n" + replacement;
        }
        return result;
    }

    private String m_replaceSection_pmatcher(String content, Pattern p, String replacement) {
        Matcher m = p.matcher(content);
        if (m.find()) {
            return content.substring(0, m.start(1)) + replacement.stripTrailing() + content.substring(m.end(1));
        }
        return content;
    }

    private Path kaiwuDir(String projectRoot) { return Path.of(projectRoot, ".kaiwu"); }
    private Path mdPath(String projectRoot) { return kaiwuDir(projectRoot).resolve("PROJECT.md"); }
    private void ensureDir(String projectRoot) {
        try { Files.createDirectories(kaiwuDir(projectRoot)); } catch (IOException e) { /* ignore */ }
    }
}
