package com.kwcode.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 调用图构建器 - 从项目源码构建函数调用图
 * <p>
 * 遍历项目目录，解析支持的源码文件，
 * 提取函数定义和调用关系，构建CallGraph对象。
 * </p>
 * @origin Python: ast_engine.call_graph.CallGraph.build_from_project
 */
public class CallGraphBuilder {

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
     * 需要跳过的文件名模式
     * @origin Python: ast_engine.graph_builder.SKIP_FILE_PATTERNS
     */
    public static final Set<String> SKIP_FILE_PATTERNS = Set.of("test_", "_test.", "conftest.");

    /**
     * 从项目构建调用图
     * <p>
     * 遍历项目目录，解析所有支持的源码文件，
     * 提取函数定义和调用关系，构建完整的CallGraph。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph.build_from_project(project_root: str, parser, max_files: int = 50) -> CallGraph
     * @param projectRoot 项目根目录路径
     * @param parser 源码解析器
     * @param maxFiles 最大解析文件数，防止大型项目耗时过长
     * @return 构建好的调用图
     */
    public static CallGraph buildFromProject(String projectRoot, Parser parser, int maxFiles) {
        CallGraph graph = new CallGraph();
        int fileCount = 0;

        try (Stream<Path> paths = Files.walk(Path.of(projectRoot))) {
            List<Path> sourceFiles = paths
                .filter(Files::isRegularFile)
                .filter(p -> !shouldSkipPath(p, projectRoot))
                .filter(p -> isSupportedExtension(p))
                .sorted()
                .limit(maxFiles)
                .toList();

            for (Path filePath : sourceFiles) {
                if (fileCount >= maxFiles) break;

                String relPath = Path.of(projectRoot).relativize(filePath)
                    .toString().replace('\\', '/');

                try {
                    String source = Files.readString(filePath, StandardCharsets.UTF_8);
                    Language lang = parser.detectFileLanguage(filePath.toString());
                    if (lang == null) continue;

                    fileCount++;

                    // 提取并注册函数定义
                    List<Parser.FunctionDef> functions = parser.extractFunctions(source, lang);
                    for (Parser.FunctionDef func : functions) {
                        graph.addFunction(func.name(), relPath, func.startLine(), func.endLine());
                    }

                    // 提取调用关系并构建边
                    List<Parser.FunctionCall> calls = parser.extractCalls(source, lang);
                    for (Parser.FunctionCall call : calls) {
                        if (call.inFunction() != null) {
                            graph.addCall(call.inFunction(), call.name());
                        }
                    }
                } catch (IOException e) {
                    // 读取失败，跳过
                }
            }
        } catch (IOException e) {
            // 遍历失败
        }

        // 解析非全限定调用名
        graph.resolveCalls();

        return graph;
    }

    /**
     * 检查路径是否应跳过
     * <p>
     * 跳过隐藏目录、SKIP_DIRS中的目录和SKIP_FILE_PATTERNS匹配的文件。
     * </p>
     * @origin Python: ast_engine.graph_builder._collect_source_files
     * @param path 文件路径
     * @param projectRoot 项目根目录
     * @return true表示应跳过
     */
    private static boolean shouldSkipPath(Path path, String projectRoot) {
        Path rel = Path.of(projectRoot).relativize(path);
        for (int i = 0; i < rel.getNameCount(); i++) {
            String name = rel.getName(i).toString();
            if (SKIP_DIRS.contains(name) || name.startsWith(".")) {
                return true;
            }
        }
        String fileName = path.getFileName().toString();
        for (String pattern : SKIP_FILE_PATTERNS) {
            if (fileName.startsWith(pattern) || fileName.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查文件扩展名是否受支持
     * @param path 文件路径
     * @return true表示受支持
     */
    private static boolean isSupportedExtension(Path path) {
        String fileName = path.getFileName().toString();
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0) return false;
        String ext = fileName.substring(dotIdx).toLowerCase();
        return Parser.EXT_MAP.containsKey(ext);
    }
}
