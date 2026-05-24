package com.kwcode.core.gap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Gap检测器 - 从测试输出确定性计算任务缺口类型
 * <p>
 * 纯确定性逻辑，零LLM调用。从测试输出正则匹配计算GapType，
 * 驱动所有后续决策（专家路由、重试策略等）。
 * </p>
 * <p>
 * 增加源文件扫描：当测试输出中的文件路径不完整时，
 * 扫描项目源码目录补充完整路径。
 * </p>
 * @origin Python: core.gap_detector.GapDetector
 */
public class GapDetector {

    private static final Logger log = LoggerFactory.getLogger(GapDetector.class);

    private static final Set<String> SKIP_DIRS = Set.of(
        "node_modules", ".git", "__pycache__", ".venv", "venv",
        "dist", "build", "target", ".idea", ".vscode", ".tox",
        ".mypy_cache", ".pytest_cache", "egg-info"
    );

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        ".py", ".java", ".go", ".rs", ".ts", ".js", ".tsx", ".jsx",
        ".kt", ".scala", ".c", ".cpp", ".h", ".hpp"
    );

    /**
     * 缺口类型枚举
     * @origin Python: core.gap_detector.GapType
     */
    public enum GapType {
        NONE("none"),
        NOT_IMPLEMENTED("not_implemented"),
        STUB_RETURNS_NONE("stub_returns_none"),
        LOGIC_ERROR("logic_error"),
        MISSING_DEP("missing_dep"),
        SYNTAX_STRUCTURAL("syntax_structural"),
        MISSING_TOOLCHAIN("missing_toolchain"),
        WRONG_FILE("wrong_file"),
        NO_TEST("no_test"),
        ENVIRONMENT("environment"),
        UNKNOWN("unknown");

        private final String key;

        GapType(String key) {
            this.key = key;
        }

        public String getKey() {
            return key;
        }
    }

    public static final Map<GapType, String> GAP_TO_EXPERT_TYPE = Map.ofEntries(
        Map.entry(GapType.NOT_IMPLEMENTED, "locator_repair"),
        Map.entry(GapType.STUB_RETURNS_NONE, "locator_repair"),
        Map.entry(GapType.LOGIC_ERROR, "locator_repair"),
        Map.entry(GapType.MISSING_DEP, "locator_repair"),
        Map.entry(GapType.SYNTAX_STRUCTURAL, "locator_repair"),
        Map.entry(GapType.MISSING_TOOLCHAIN, "env_fix"),
        Map.entry(GapType.NO_TEST, "codegen"),
        Map.entry(GapType.WRONG_FILE, "locator_repair"),
        Map.entry(GapType.ENVIRONMENT, "env_fix"),
        Map.entry(GapType.UNKNOWN, "locator_repair")
    );

    /**
     * 计算Gap类型
     * <p>
     * 主入口方法：从测试输出确定性计算GapType。
     * 按优先级顺序匹配，第一个命中的优先。
     * </p>
     * @origin Python: core.gap_detector.GapDetector.compute(test_output: str, project_root: str) -> Gap
     * @param testOutput 测试输出文本
     * @param projectRoot 项目根目录
     * @return Gap对象，包含类型、置信度、相关文件/函数等
     */
    public Gap compute(String testOutput, String projectRoot) {
        if (testOutput == null || testOutput.isBlank()) {
            log.debug("[gap_detector] compute: null/blank output → NO_TEST");
            return new Gap(GapType.NO_TEST, 0.9, List.of(), List.of(), "", "找不到测试输出");
        }

        if (matchToolchain(testOutput)) {
            log.info("[gap_detector] compute: MATCH toolchain → MISSING_TOOLCHAIN");
            return buildToolchainGap(testOutput);
        }

        if (matchNoProjectStructure(testOutput)) {
            log.info("[gap_detector] compute: MATCH no project structure → NO_TEST");
            return new Gap(GapType.NO_TEST, 0.9, List.of(), List.of(),
                testOutput.substring(0, Math.min(200, testOutput.length())),
                "项目缺少构建文件，无法运行测试");
        }

        if (matchLlmFailure(testOutput)) {
            log.warn("[gap_detector] compute: MATCH LLM failure → ENVIRONMENT");
            return buildLlmFailureGap(testOutput);
        }

        if (testOutput.contains("ModuleNotFoundError") || testOutput.contains("ImportError")) {
            log.info("[gap_detector] compute: MATCH ImportError → MISSING_DEP");
            return buildMissingDepGap(testOutput);
        }

        if (matchJavaMissingDep(testOutput)) {
            log.info("[gap_detector] compute: MATCH Java missing dep → MISSING_DEP");
            return buildJavaMissingDepGap(testOutput);
        }

        if (testOutput.contains("NotImplementedError") ||
            testOutput.toLowerCase().contains("not implemented")) {
            log.info("[gap_detector] compute: MATCH NotImplementedError → NOT_IMPLEMENTED");
            return buildNotImplementedGap(testOutput, projectRoot);
        }

        if (isStubReturnsNone(testOutput)) {
            log.info("[gap_detector] compute: MATCH stub returns None → STUB_RETURNS_NONE");
            return buildStubNoneGap(testOutput, projectRoot);
        }

        if (testOutput.contains("IndentationError") || testOutput.contains("SyntaxError")) {
            log.info("[gap_detector] compute: MATCH SyntaxError → SYNTAX_STRUCTURAL");
            return buildSyntaxGap(testOutput);
        }

        if (matchJavaSyntaxError(testOutput)) {
            log.info("[gap_detector] compute: MATCH Java syntax error → SYNTAX_STRUCTURAL");
            return buildJavaSyntaxGap(testOutput);
        }

        if (testOutput.contains("AssertionError") ||
            testOutput.contains("FAILED") ||
            testOutput.contains("--- FAIL:")) {
            log.info("[gap_detector] compute: MATCH AssertionError/FAILED → LOGIC_ERROR");
            return buildLogicGap(testOutput, projectRoot);
        }

        if (matchJavaTestFailure(testOutput)) {
            log.info("[gap_detector] compute: MATCH Java test failure → LOGIC_ERROR");
            return buildJavaLogicGap(testOutput, projectRoot);
        }

        if (allPassed(testOutput)) {
            log.debug("[gap_detector] compute: all passed → NONE");
            return new Gap(GapType.NONE, 1.0, List.of(), List.of(), "", "");
        }

        log.warn("[gap_detector] compute: no pattern matched → UNKNOWN");
        return new Gap(GapType.UNKNOWN, 0.3, List.of(), List.of(),
            testOutput.substring(0, Math.min(200, testOutput.length())), "");
    }

    /**
     * 扫描项目源文件，补充Gap中的文件列表
     * <p>
     * 当测试输出中只有文件名（无完整路径）时，
     * 扫描项目源码目录查找匹配的完整路径。
     * 同时，根据函数名在源文件中搜索定义位置。
     * </p>
     * @origin Python: core.gap_detector.GapDetector.scan_source_files(gap, project_root) -> Gap
     * @param gap 原始Gap对象
     * @param projectRoot 项目根目录
     * @return 补充了完整文件路径的Gap
     */
    public Gap scanSourceFiles(Gap gap, String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) {
            log.debug("[gap_detector] scanSourceFiles: projectRoot is null/blank, skip");
            return gap;
        }
        if (gap.gapType() == GapType.NONE || gap.gapType() == GapType.NO_TEST) {
            log.debug("[gap_detector] scanSourceFiles: gapType={} skip scan", gap.gapType());
            return gap;
        }

        log.debug("[gap_detector] scanSourceFiles: scanning projectRoot={} gapType={} files={} functions={}",
                projectRoot, gap.gapType(), gap.files().size(), gap.functions().size());

        List<String> resolvedFiles = new ArrayList<>(gap.files());
        List<String> resolvedFunctions = new ArrayList<>(gap.functions());

        Map<String, String> fileIndex = buildFileIndex(projectRoot);

        List<String> fullyResolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String f : resolvedFiles) {
            String resolved = resolveFile(f, fileIndex);
            if (seen.add(resolved)) fullyResolved.add(resolved);
        }

        if (resolvedFunctions.isEmpty() && !fullyResolved.isEmpty()) {
            resolvedFunctions = findFunctionDefinitions(fullyResolved, projectRoot);
        }

        if (fullyResolved.isEmpty() && !resolvedFunctions.isEmpty()) {
            fullyResolved = findFilesByFunctions(resolvedFunctions, fileIndex, projectRoot);
        }

        log.info("[gap_detector] scanSourceFiles: resolved files={} functions={} (from original files={} functions={})",
                fullyResolved.size(), resolvedFunctions.size(), gap.files().size(), gap.functions().size());
        return new Gap(gap.gapType(), gap.confidence(), fullyResolved, resolvedFunctions,
            gap.errorMsg(), gap.suggestion());
    }

    /**
     * 构建项目文件索引：basename → 完整路径
     * @origin Python: core.gap_detector.GapDetector._build_file_index(project_root) -> dict
     */
    private Map<String, String> buildFileIndex(String projectRoot) {
        Map<String, String> index = new LinkedHashMap<>();
        Path root = Path.of(projectRoot);
        if (!Files.isDirectory(root)) return index;

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (SKIP_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx > 0 && SOURCE_EXTENSIONS.contains(name.substring(dotIdx))) {
                        index.putIfAbsent(name, file.toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // 扫描失败不影响主流程
        }
        return index;
    }

    /**
     * 解析文件路径：basename → 完整路径
     */
    private String resolveFile(String filePath, Map<String, String> fileIndex) {
        Path p = Path.of(filePath);
        if (p.isAbsolute() && Files.exists(p)) return filePath;

        String basename = p.getFileName().toString();
        String resolved = fileIndex.get(basename);
        if (resolved != null) return resolved;

        for (var entry : fileIndex.entrySet()) {
            if (entry.getKey().equals(basename)) return entry.getValue();
        }
        return filePath;
    }

    /**
     * 在已定位的源文件中搜索函数定义
     * @origin Python: core.gap_detector.GapDetector._find_function_definitions(files, project_root) -> list[str]
     */
    private List<String> findFunctionDefinitions(List<String> files, String projectRoot) {
        Set<String> skip = Set.of("<module>", "wrapper", "inner", "setUp", "tearDown",
            "run", "main", "__init__", "execute", "test_");
        Set<String> funcs = new LinkedHashSet<>();

        Pattern[] patterns = {
            Pattern.compile("def\\s+(\\w+)\\s*\\("),
            Pattern.compile("func\\s+(\\w+)\\s*\\("),
            Pattern.compile("fn\\s+(\\w+)\\s*\\("),
            Pattern.compile("(?:public|private|protected)?\\s*(?:static)?\\s*\\w+\\s+(\\w+)\\s*\\("),
            Pattern.compile("function\\s+(\\w+)\\s*\\("),
            Pattern.compile("const\\s+(\\w+)\\s*=\\s*(?:\\(|async)")
        };

        for (String filePath : files) {
            try {
                List<String> lines = Files.readAllLines(Path.of(filePath));
                for (String line : lines) {
                    for (Pattern pat : patterns) {
                        Matcher m = pat.matcher(line);
                        if (m.find()) {
                            String fn = m.group(1);
                            if (!skip.contains(fn) && !fn.startsWith("test_")) {
                                funcs.add(fn);
                            }
                        }
                    }
                    if (funcs.size() >= 10) break;
                }
            } catch (IOException ignored) {
            }
            if (funcs.size() >= 10) break;
        }
        return funcs.stream().limit(5).toList();
    }

    /**
     * 根据函数名在文件索引中搜索定义文件
     * @origin Python: core.gap_detector.GapDetector._find_files_by_functions(functions, file_index, project_root) -> list[str]
     */
    private List<String> findFilesByFunctions(List<String> functions, Map<String, String> fileIndex,
                                              String projectRoot) {
        Set<String> found = new LinkedHashSet<>();

        for (String fn : functions) {
            Pattern pyPat = Pattern.compile("def\\s+" + Pattern.quote(fn) + "\\s*\\(");
            Pattern goPat = Pattern.compile("func\\s+" + Pattern.quote(fn) + "\\s*\\(");
            Pattern rsPat = Pattern.compile("fn\\s+" + Pattern.quote(fn) + "\\s*\\(");
            Pattern jsPat = Pattern.compile("function\\s+" + Pattern.quote(fn) + "\\s*\\(");

            for (var entry : fileIndex.entrySet()) {
                try {
                    String content = Files.readString(Path.of(entry.getValue()));
                    if (pyPat.matcher(content).find() || goPat.matcher(content).find() ||
                        rsPat.matcher(content).find() || jsPat.matcher(content).find()) {
                        found.add(entry.getValue());
                    }
                } catch (IOException ignored) {
                }
                if (found.size() >= 5) break;
            }
            if (found.size() >= 5) break;
        }
        return found.stream().limit(5).toList();
    }

    private boolean matchToolchain(String output) {
        String[] patterns = {
            "go: not found", "node: not found", "npm: not found",
            "command not found: go", "command not found: node",
            "command not found: npm", "command not found: cargo",
            "command not found: javac", "Toolchain missing:"
        };
        for (String p : patterns) {
            if (output.contains(p)) return true;
        }
        if (Pattern.compile("/bin/sh: \\d+: \\w+: not found").matcher(output).find()) {
            return true;
        }
        return false;
    }

    private boolean matchNoProjectStructure(String output) {
        String[] patterns = {
            "no POM in this directory",
            "MissingProjectException",
            "requires a project to execute but there is no POM",
            "Could not find a package.json",
            "go: cannot find main module",
            "no Cargo.toml found"
        };
        for (String p : patterns) {
            if (output.contains(p)) return true;
        }
        return false;
    }

    private boolean matchLlmFailure(String output) {
        String[] patterns = {
            "没有可用的ChatModel",
            "没有可用的ChatClient",
            "ChatModel",
            "ChatClient",
            "LLM call failed",
            "LLM调用失败",
            "API key",
            "api_key",
            "Connection refused",
            "connection timed out",
            "model not found",
            "quota exceeded",
            "rate limit"
        };
        String lower = output.toLowerCase();
        for (String p : patterns) {
            if (lower.contains(p.toLowerCase())) return true;
        }
        return false;
    }

    private Gap buildLlmFailureGap(String output) {
        String reason = "LLM服务不可用";
        if (output.contains("ChatModel")) reason = "ChatModel未配置，请检查Spring AI配置";
        else if (output.contains("ChatClient")) reason = "ChatClient未配置，请检查Spring AI配置";
        else if (output.contains("API key") || output.contains("api_key")) reason = "API密钥未配置";
        else if (output.contains("Connection refused")) reason = "LLM服务连接被拒绝";
        else if (output.contains("timed out")) reason = "LLM服务连接超时";
        else if (output.contains("rate limit") || output.contains("quota")) reason = "LLM API配额不足";
        return new Gap(GapType.ENVIRONMENT, 0.95, List.of(), List.of(),
            output.substring(0, Math.min(200, output.length())), reason);
    }

    private boolean isStubReturnsNone(String output) {
        if (output.contains("NoneType") &&
            (output.contains("has no attribute") ||
             output.contains("is not iterable") ||
             output.contains("unsupported operand") ||
             output.contains("is not subscriptable") ||
             output.contains("object is not callable"))) {
            return true;
        }
        if (countMatches(output, "assert None ==") >= 2) return true;
        if (countMatches(output, "where None = ") >= 2) return true;
        if (output.contains("TypeError") && output.contains("takes no arguments")) return true;
        if (countMatches(output, "TypeError:") >= 3) return true;
        return false;
    }

    private boolean allPassed(String output) {
        String[] patterns = {
            "\\d+ passed", "^PASS$", "^ok\\s+", "Tests:.*\\d+ passed",
            "All tests passed", "test result: ok", "\\[no test files\\]",
            "BUILD SUCCESS", "Tests run:.*Failures: 0",
            "\\d+ tests? passed"
        };
        for (String p : patterns) {
            if (Pattern.compile(p, Pattern.MULTILINE).matcher(output).find()) return true;
        }
        return false;
    }

    private Gap buildToolchainGap(String output) {
        String tool = "unknown";
        for (String name : List.of("go", "node", "npm", "cargo", "javac", "rustc")) {
            if (output.toLowerCase().contains(name)) { tool = name; break; }
        }
        return new Gap(GapType.MISSING_TOOLCHAIN, 0.95, List.of(), List.of(),
            output.substring(0, Math.min(200, output.length())),
            "工具链缺失：" + tool + "，需要安装");
    }

    private Gap buildMissingDepGap(String output) {
        List<String> pkgs = new ArrayList<>();
        Matcher m = Pattern.compile("No module named '([^']+)'").matcher(output);
        while (m.find()) pkgs.add(m.group(1).split("\\.")[0]);
        pkgs = pkgs.stream().distinct().toList();
        List<String> files = extractErrorFiles(output);
        return new Gap(GapType.MISSING_DEP, 0.95, files, List.of(),
            output.substring(0, Math.min(200, output.length())),
            pkgs.isEmpty() ? "缺少依赖" : "缺少依赖：" + String.join(", ", pkgs));
    }

    private Gap buildNotImplementedGap(String output, String projectRoot) {
        List<String> files = extractErrorFiles(output);
        List<String> functions = extractFunctionNames(output);
        if (files.isEmpty()) {
            files = findSourceFiles(projectRoot);
        }
        List<String> stubFunctions = scanStubsInFiles(files, projectRoot);
        if (!stubFunctions.isEmpty()) {
            functions = stubFunctions;
        }
        return new Gap(GapType.NOT_IMPLEMENTED, 0.9, files, functions,
            output.substring(0, Math.min(200, output.length())),
            "函数未实现，需要完整实现");
    }

    private Gap buildStubNoneGap(String output, String projectRoot) {
        List<String> files = extractErrorFiles(output);
        List<String> functions = extractFunctionNames(output);
        if (files.isEmpty()) {
            files = findSourceFiles(projectRoot);
        }
        List<String> stubFunctions = scanStubsInFiles(files, projectRoot);
        if (!stubFunctions.isEmpty()) {
            functions = stubFunctions;
        }
        return new Gap(GapType.STUB_RETURNS_NONE, 0.85, files, functions,
            output.substring(0, Math.min(200, output.length())),
            "pass存根返回None，需要实现函数体");
    }

    private Gap buildSyntaxGap(String output) {
        List<String> files = extractErrorFiles(output);
        return new Gap(GapType.SYNTAX_STRUCTURAL, 0.95, files, List.of(),
            output.substring(0, Math.min(200, output.length())),
            "语法或缩进错误");
    }

    private Gap buildLogicGap(String output, String projectRoot) {
        List<String> files = extractErrorFiles(output);
        List<String> functions = extractFunctionNames(output);
        return new Gap(GapType.LOGIC_ERROR, 0.8, files, functions,
            output.substring(0, Math.min(200, output.length())),
            "断言失败，逻辑错误");
    }

    private List<String> extractErrorFiles(String output) {
        List<String> files = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Matcher pyMatch = Pattern.compile("File \"([^\"]+\\.py)\"").matcher(output);
        while (pyMatch.find()) addFile(files, seen, pyMatch.group(1));
        Matcher goMatch = Pattern.compile("(\\S+\\.go):\\d+:").matcher(output);
        while (goMatch.find()) addFile(files, seen, goMatch.group(1));
        Matcher rsMatch = Pattern.compile("-->\\s+(\\S+\\.rs):\\d+").matcher(output);
        while (rsMatch.find()) addFile(files, seen, rsMatch.group(1));
        Matcher tsMatch = Pattern.compile("(\\S+\\.(?:ts|js|tsx|jsx))[:\\(]\\d+").matcher(output);
        while (tsMatch.find()) addFile(files, seen, tsMatch.group(1));
        Matcher javaMatch = Pattern.compile("(\\S+\\.java):\\[?\\d+").matcher(output);
        while (javaMatch.find()) addFile(files, seen, javaMatch.group(1));
        Matcher javaMatch2 = Pattern.compile("\\[(\\S+\\.java)\\]").matcher(output);
        while (javaMatch2.find()) addFile(files, seen, javaMatch2.group(1));
        Matcher mavenMatch = Pattern.compile("/(src/\\S+\\.java)").matcher(output);
        while (mavenMatch.find()) addFile(files, seen, mavenMatch.group(1));

        return files.stream().limit(5).toList();
    }

    private void addFile(List<String> files, Set<String> seen, String f) {
        String basename = f.substring(Math.max(0, f.lastIndexOf('/') + 1));
        if (seen.add(basename) &&
            !f.contains("/lib/python") && !f.contains("/site-packages/")) {
            files.add(f);
        }
    }

    private List<String> extractFunctionNames(String output) {
        Set<String> skip = Set.of("<module>", "wrapper", "inner", "setUp", "tearDown",
            "run", "main", "__init__", "execute");
        Set<String> funcs = new LinkedHashSet<>();
        Matcher m1 = Pattern.compile("in (\\w+)\\n").matcher(output);
        while (m1.find()) {
            String f = m1.group(1);
            if (!skip.contains(f) && !f.startsWith("test_")) funcs.add(f);
        }
        Matcher m2 = Pattern.compile("--- FAIL:\\s+(\\w+)").matcher(output);
        while (m2.find()) funcs.add(m2.group(1));
        return funcs.stream().limit(5).toList();
    }

    private int countMatches(String text, String pattern) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) { count++; idx++; }
        return count;
    }

    /**
     * 扫描project_root下所有非测试源文件（浅层，不递归深目录）
     * @origin Python: core.gap_detector.GapDetector._find_source_files(project_root) -> list[str]
     */
    private List<String> findSourceFiles(String projectRoot) {
        if (projectRoot == null || projectRoot.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        try {
            Path root = Path.of(projectRoot);
            try (var stream = Files.list(root)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        int dotIdx = name.lastIndexOf('.');
                        if (dotIdx <= 0) return false;
                        String ext = name.substring(dotIdx);
                        if (!SOURCE_EXTENSIONS.contains(ext)) return false;
                        String lower = name.toLowerCase();
                        return !lower.contains("test") && !lower.startsWith("test_");
                    })
                    .limit(5)
                    .forEach(p -> result.add(p.toString()));
            }
        } catch (IOException ignored) {
        }
        return result;
    }

    private static final Pattern STUB_PASS_PATTERN = Pattern.compile(
        "^\\s*(pass|raise\\s+NotImplementedError|return\\s+None|\\.\\.\\.)\\s*$", Pattern.MULTILINE
    );
    private static final Pattern FUNC_DEF_PATTERN = Pattern.compile(
        "^\\s*(?:def|func|fn|function|public|private|protected)\\s+(\\w+)\\s*[\\(\\{]", Pattern.MULTILINE
    );
    private static final Pattern CLASS_DEF_PATTERN = Pattern.compile(
        "^\\s*class\\s+(\\w+)", Pattern.MULTILINE
    );

    /**
     * 扫描文件中的pass/raise NotImplementedError存根函数
     * <p>
     * 对齐Python的AST存根扫描，但Java无法做AST解析，
     * 改用正则模式匹配存根函数体。
     * </p>
     * @origin Python: core.gap_detector.GapDetector._scan_stubs_in_files(files, project_root) -> list[str]
     */
    private List<String> scanStubsInFiles(List<String> files, String projectRoot) {
        Set<String> skip = Set.of("__init__", "__str__", "__repr__", "__eq__");
        List<String> stubFunctions = new ArrayList<>();

        for (String fpath : files.stream().limit(3).toList()) {
            Path absPath = Path.of(fpath);
            if (!absPath.isAbsolute()) {
                absPath = Path.of(projectRoot, fpath);
            }
            if (!Files.exists(absPath)) continue;

            try {
                List<String> lines = Files.readAllLines(absPath);
                scanForStubFunctions(lines, skip, stubFunctions);
            } catch (IOException ignored) {
            }
            if (stubFunctions.size() >= 10) break;
        }
        return stubFunctions.stream().limit(5).toList();
    }

    /**
     * 正则模式扫描存根函数
     * <p>
     * 检测模式：
     * - 函数定义后紧跟 pass / raise NotImplementedError / return None / ...
     * - 空函数体（只有docstring）
     * </p>
     */
    private void scanForStubFunctions(List<String> lines, Set<String> skip, List<String> stubFunctions) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher funcMatcher = FUNC_DEF_PATTERN.matcher(line);
            if (!funcMatcher.find()) continue;

            String funcName = funcMatcher.group(1);
            if (skip.contains(funcName) || funcName.startsWith("test_")) continue;
            if (funcName.startsWith("__") && funcName.endsWith("__")) continue;

            if (isStubBody(lines, i + 1)) {
                stubFunctions.add(funcName);
            }
        }
    }

    /**
     * 判断函数体是否是存根
     * @origin Python: core.gap_detector.GapDetector._is_stub_body(body) -> bool
     */
    private boolean isStubBody(List<String> lines, int startIdx) {
        List<String> bodyLines = new ArrayList<>();
        if (startIdx < lines.size()) {
            String firstLine = lines.get(startIdx).trim();
            if (firstLine.startsWith("\"\"\"") || firstLine.startsWith("'''") || firstLine.startsWith("//")) {
                for (int j = startIdx + 1; j < lines.size(); j++) {
                    String l = lines.get(j).trim();
                    if (l.endsWith("\"\"\"") || l.endsWith("'''") || l.isEmpty() || l.startsWith("//")) {
                        startIdx = j + 1;
                        break;
                    }
                }
            }
        }

        for (int j = startIdx; j < Math.min(lines.size(), startIdx + 3); j++) {
            String trimmed = lines.get(j).trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.equals("pass") ||
                trimmed.startsWith("raise NotImplementedError") ||
                trimmed.equals("return None") ||
                trimmed.equals("return null") ||
                trimmed.equals("...") ||
                trimmed.equals("{}") ||
                trimmed.startsWith("throw new UnsupportedOperationException")) {
                return true;
            }
            break;
        }
        return false;
    }

    private boolean matchJavaMissingDep(String output) {
        return output.contains("cannot find symbol") ||
               output.contains("package .* does not exist") ||
               output.contains("Could not resolve dependencies") ||
               output.contains("Cannot resolve") ||
               output.contains("ClassNotFoundException") ||
               output.contains("NoClassDefFoundError");
    }

    private Gap buildJavaMissingDepGap(String output) {
        List<String> pkgs = new ArrayList<>();
        Matcher m = Pattern.compile("package (\\S+) does not exist").matcher(output);
        while (m.find()) pkgs.add(m.group(1));
        Matcher m2 = Pattern.compile("cannot find symbol.*?class\\s+(\\w+)", Pattern.DOTALL).matcher(output);
        while (m2.find()) pkgs.add(m2.group(1));
        pkgs = pkgs.stream().distinct().limit(5).toList();
        List<String> files = extractErrorFiles(output);
        return new Gap(GapType.MISSING_DEP, 0.95, files, List.of(),
            output.substring(0, Math.min(200, output.length())),
            pkgs.isEmpty() ? "缺少Java依赖" : "缺少Java依赖：" + String.join(", ", pkgs));
    }

    private boolean matchJavaSyntaxError(String output) {
        return output.contains("';' expected") ||
               output.contains("illegal start of type") ||
               output.contains("incompatible types") ||
               output.contains("not a statement") ||
               output.contains("reached end of file while parsing") ||
               output.contains("COMPILATION ERROR") ||
               Pattern.compile("error:\\s+.*\\.java").matcher(output).find();
    }

    private Gap buildJavaSyntaxGap(String output) {
        List<String> files = extractErrorFiles(output);
        return new Gap(GapType.SYNTAX_STRUCTURAL, 0.95, files, List.of(),
            output.substring(0, Math.min(200, output.length())),
            "Java编译错误");
    }

    private boolean matchJavaTestFailure(String output) {
        return output.contains("Tests run:") && output.contains("Failures:") ||
               output.contains("BUILD FAILURE") ||
               output.contains("AssertionFailedError") ||
               output.contains("AssertionError") && output.contains("at com.") ||
               output.contains("junit") && output.contains("FAIL") ||
               output.contains("org.junit") && output.contains("failed");
    }

    private Gap buildJavaLogicGap(String output, String projectRoot) {
        List<String> files = extractErrorFiles(output);
        List<String> functions = extractFunctionNames(output);
        return new Gap(GapType.LOGIC_ERROR, 0.8, files, functions,
            output.substring(0, Math.min(200, output.length())),
            "Java测试断言失败，逻辑错误");
    }

    public record Gap(
        GapType gapType,
        double confidence,
        List<String> files,
        List<String> functions,
        String errorMsg,
        String suggestion
    ) {}
}
