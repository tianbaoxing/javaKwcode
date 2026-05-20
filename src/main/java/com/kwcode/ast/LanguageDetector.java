package com.kwcode.ast;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 语言检测器 - 扫描项目文件确定主要编程语言
 * <p>
 * 通过统计项目文件扩展名来检测项目使用的主要编程语言，
 * 同时支持通过项目标记文件（如go.mod、pom.xml）进行更可靠的检测。
 * 结果可缓存到 .kaiwu/rig.json 的 "language_stats" 字段。
 * </p>
 * @origin Python: ast_engine.language_detector.LanguageDetector
 */
public class LanguageDetector {

    /**
     * 支持的语言及其对应的文件扩展名
     * @origin Python: ast_engine.language_detector.SUPPORTED_LANGUAGES
     */
    public static final Map<Language, Set<String>> SUPPORTED_LANGUAGES = Map.of(
        Language.PYTHON, Set.of(".py"),
        Language.JAVASCRIPT, Set.of(".js", ".mjs"),
        Language.TYPESCRIPT, Set.of(".ts", ".tsx"),
        Language.GO, Set.of(".go"),
        Language.RUST, Set.of(".rs"),
        Language.JAVA, Set.of(".java"),
        Language.CSHARP, Set.of(".cs")
    );

    /**
     * 项目标记文件 - 用于确认项目使用的语言
     * @origin Python: ast_engine.language_detector.PROJECT_MARKERS
     */
    public static final Map<Language, Set<String>> PROJECT_MARKERS = Map.of(
        Language.PYTHON, Set.of("pyproject.toml", "setup.py", "requirements.txt", "Pipfile"),
        Language.JAVASCRIPT, Set.of("package.json"),
        Language.TYPESCRIPT, Set.of("tsconfig.json"),
        Language.GO, Set.of("go.mod"),
        Language.RUST, Set.of("Cargo.toml"),
        Language.JAVA, Set.of("pom.xml", "build.gradle", "build.gradle.kts"),
        Language.CSHARP, Set.of(".csproj", ".sln")
    );

    /**
     * 各语言的测试命令
     * @origin Python: ast_engine.language_detector.TEST_COMMANDS
     */
    public static final Map<Language, String> TEST_COMMANDS = Map.of(
        Language.PYTHON, "python -m pytest tests/ --tb=short -q",
        Language.JAVASCRIPT, "npx jest --ci --passWithNoTests",
        Language.TYPESCRIPT, "npx jest --ci --passWithNoTests",
        Language.GO, "go test ./...",
        Language.RUST, "cargo test 2>&1",
        Language.JAVA, "mvn test -q",
        Language.CSHARP, "dotnet test --no-build -q"
    );

    /**
     * 各语言的语法检查命令
     * @origin Python: ast_engine.language_detector.SYNTAX_CHECK_COMMANDS
     */
    public static final Map<Language, String> SYNTAX_CHECK_COMMANDS = Map.of(
        Language.PYTHON, "python -m py_compile \"{file}\"",
        Language.GO, "go vet \"{file}\"",
        Language.RUST, "cargo check",
        Language.JAVA, "javac -d /tmp \"{file}\""
    );

    /**
     * 需要跳过的目录名集合
     * @origin Python: ast_engine.graph_builder.SKIP_DIRS
     */
    public static final Set<String> SKIP_DIRS = Set.of(
        ".git", "__pycache__", "node_modules", ".venv", "venv",
        "env", "dist", "build", ".tox", "htmlcov", ".pytest_cache",
        ".eggs", ".mypy_cache"
    );

    /**
     * 统计项目中各语言的文件数量
     * <p>
     * 遍历项目目录，按文件扩展名统计各语言的文件数量，
     * 结果按数量降序排列。
     * </p>
     * @origin Python: ast_engine.language_detector.detect_project_languages(project_root: str) -> dict[str, int]
     * @param projectRoot 项目根目录路径
     * @return 语言文件计数列表，按数量降序排列
     */
    public List<LanguageCount> detectProjectLanguages(String projectRoot) {
        Map<Language, Integer> counts = new EnumMap<>(Language.class);
        Path root = Path.of(projectRoot);

        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        try (Stream<Path> walk = Files.walk(root, 16)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !isInSkipDir(p, root))
                .forEach(p -> {
                    Language lang = detectLanguageForFile(p.toString());
                    if (lang != null) {
                        counts.merge(lang, 1, Integer::sum);
                    }
                });
        } catch (IOException e) {
            return List.of();
        }

        return counts.entrySet().stream()
            .map(e -> new LanguageCount(e.getKey(), e.getValue()))
            .sorted()
            .toList();
    }

    /**
     * 获取项目的主要编程语言
     * <p>
     * 返回项目中文件数量最多的语言，默认为Python。
     * </p>
     * @origin Python: ast_engine.language_detector.get_primary_language(project_root: str) -> str
     * @param projectRoot 项目根目录路径
     * @return 主要语言枚举，默认返回PYTHON
     */
    public Language getPrimaryLanguage(String projectRoot) {
        List<LanguageCount> counts = detectProjectLanguages(projectRoot);
        if (counts.isEmpty()) {
            return Language.PYTHON;
        }
        return counts.get(0).language();
    }

    /**
     * 检测单个文件的语言类型
     * @origin Python: ast_engine.language_detector.detect_language_for_file(file_path: str) -> Optional[str]
     * @param filePath 文件路径
     * @return 语言枚举，无法识别返回null
     */
    public Language detectLanguageForFile(String filePath) {
        if (filePath == null) return null;
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx < 0) return null;
        String ext = filePath.substring(dotIdx).toLowerCase();
        return Language.fromExtension(ext);
    }

    /**
     * 获取指定语言的测试命令
     * @origin Python: ast_engine.language_detector.get_test_command(language: str) -> Optional[str]
     * @param language 语言枚举
     * @return 测试命令字符串，不支持返回null
     */
    public String getTestCommand(Language language) {
        return TEST_COMMANDS.getOrDefault(language, null);
    }

    /**
     * 获取指定语言的语法检查命令
     * @origin Python: ast_engine.language_detector.get_syntax_check_command(language: str, file_path: str = "") -> Optional[str]
     * @param language 语言枚举
     * @param filePath 待检查的文件路径
     * @return 语法检查命令，不支持返回null
     */
    public String getSyntaxCheckCommand(Language language, String filePath) {
        String template = SYNTAX_CHECK_COMMANDS.getOrDefault(language, null);
        if (template != null && template.contains("{file}") && filePath != null) {
            return template.replace("{file}", filePath);
        }
        return template;
    }

    /**
     * 通过项目标记文件检测语言
     * <p>
     * 比文件计数更可靠，适用于混合语言项目。
     * 检测项目根目录下的标记文件（如go.mod、pom.xml等）。
     * </p>
     * @origin Python: ast_engine.language_detector.detect_project_marker(project_root: str) -> Optional[str]
     * @param projectRoot 项目根目录路径
     * @return 检测到的语言枚举，未检测到返回null
     */
    public Language detectProjectMarker(String projectRoot) {
        Path root = Path.of(projectRoot);
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return null;
        }

        for (Map.Entry<Language, Set<String>> entry : PROJECT_MARKERS.entrySet()) {
            for (String marker : entry.getValue()) {
                if (Files.exists(root.resolve(marker))) {
                    return entry.getKey();
                }
            }
        }

        return null;
    }

    private boolean isInSkipDir(Path path, Path root) {
        Path rel = root.relativize(path);
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (SKIP_DIRS.contains(rel.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 语言文件计数记录
     */
    public record LanguageCount(Language language, int count) implements Comparable<LanguageCount> {
        @Override
        public int compareTo(LanguageCount other) {
            return Integer.compare(other.count, this.count); // 降序
        }
    }
}
