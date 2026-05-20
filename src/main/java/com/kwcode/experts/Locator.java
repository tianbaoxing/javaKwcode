package com.kwcode.experts;

import com.kwcode.ast.*;
import com.kwcode.core.context.TaskContext;
import com.kwcode.memory.KaiwuMemory;
import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 定位器专家 - BM25+AST调用图两阶段定位
 * <p>
 * Stage 1: BM25关键词召回 → Top-K候选
 * Stage 2: 调用图扩展 → N跳扩展
 * 定位相关文件和函数，收集代码片段供Generator使用。
 * </p>
 * @origin Python: experts.locator.Locator
 */
public class Locator {

    private static final Logger log = LoggerFactory.getLogger(Locator.class);

    private final ToolGateway tools;
    private final KaiwuMemory memory;
    private final GraphRetriever retriever;

    public Locator(ToolGateway tools, KaiwuMemory memory, GraphRetriever retriever) {
        this.tools = tools;
        this.memory = memory;
        this.retriever = retriever;
    }

    /**
     * 执行定位
     * <p>
     * 从用户输入和gap信息中提取关键词，通过BM25+调用图定位相关文件和函数。
     * </p>
     * @origin Python: experts.locator.Locator.locate(ctx) -> dict
     * @param ctx 任务上下文
     * @return 定位结果：relevant_files, relevant_functions, edit_locations
     */
    public TaskContext.LocatorResult locate(TaskContext ctx) {
        tools.setExpert("locator");

        List<String> relevantFiles = new ArrayList<>();
        List<String> relevantFunctions = new ArrayList<>();
        List<String> editLocations = new ArrayList<>();

        // 1. 从记忆中获取已知结构规律
        String structureHints = "";
        if (memory != null) {
            structureHints = memory.loadForLocator(ctx.projectRoot);
        }

        // 2. 从调用图检索（如果可用）
        if (retriever != null && retriever.hasGraph()) {
            String query = buildQuery(ctx);
            List<Map<String, Object>> results = retriever.retrieve(query, 20, 2, 10);
            for (var r : results) {
                String filePath = (String) r.get("file_path");
                String qualified = (String) r.get("qualified");
                if (filePath != null && !relevantFiles.contains(filePath)) relevantFiles.add(filePath);
                if (qualified != null && !relevantFunctions.contains(qualified)) relevantFunctions.add(qualified);
            }
        }

        // 3. 文件特征检测（没有调用图时的后备）
        if (relevantFiles.isEmpty()) {
            List<String> candidates = findFilesByKeywords(ctx);
            relevantFiles.addAll(candidates.subList(0, Math.min(5, candidates.size())));
        }

        // 4. 读取相关代码片段
        Map<String, String> snippets = collectSnippets(ctx, relevantFiles);
        ctx.relevantCodeSnippets = snippets;

        // 5. Gap信息中的文件/函数补充
        if (ctx.gap != null) {
            for (String f : ctx.gap.files()) {
                if (!relevantFiles.contains(f)) relevantFiles.add(f);
            }
            for (String fn : ctx.gap.functions()) {
                if (!relevantFunctions.contains(fn)) relevantFunctions.add(fn);
            }
        }

        // 6. 构建编辑位置
        for (String f : relevantFiles) {
            editLocations.add(f);
        }

        log.info("[locator] found {} files, {} functions", relevantFiles.size(), relevantFunctions.size());
        return new TaskContext.LocatorResult(relevantFiles, relevantFunctions, editLocations);
    }

    /** 从上下文构建检索查询 */
    private String buildQuery(TaskContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.userInput);
        if (ctx.gap != null) {
            if (!ctx.gap.functions().isEmpty()) sb.append(" ").append(String.join(" ", ctx.gap.functions()));
            if (!ctx.gap.errorMsg().isEmpty()) sb.append(" ").append(ctx.gap.errorMsg().substring(0, Math.min(100, ctx.gap.errorMsg().length())));
        }
        return sb.toString();
    }

    /** 通过关键词搜索文件 */
    private List<String> findFilesByKeywords(TaskContext ctx) {
        List<String> candidates = new ArrayList<>();
        try {
            String[] parts = ctx.userInput.split("[\\s,，。./]+");
            List<String> keywords = new ArrayList<>();
            for (String part : parts) {
                if (part.length() >= 2 && !part.matches("^(创建|实现|写|添加|修改|删除|a|an|the|to|for|in|of|with)$")) {
                    keywords.add(part.toLowerCase());
                }
            }

            List<String> allFiles = scanProjectFiles(ctx.projectRoot);
            for (String filePath : allFiles) {
                String fileName = filePath.toLowerCase();
                String baseName = fileName.contains("/") ? fileName.substring(fileName.lastIndexOf('/') + 1) : fileName;
                baseName = baseName.contains("\\") ? baseName.substring(baseName.lastIndexOf('\\') + 1) : baseName;
                int dotIdx = baseName.lastIndexOf('.');
                String nameNoExt = dotIdx > 0 ? baseName.substring(0, dotIdx) : baseName;

                for (String kw : keywords) {
                    if (nameNoExt.contains(kw) || baseName.contains(kw)) {
                        if (!candidates.contains(filePath)) candidates.add(filePath);
                        break;
                    }
                }
                if (candidates.size() >= 5) break;
            }

            if (candidates.isEmpty() && !allFiles.isEmpty()) {
                candidates.addAll(allFiles.subList(0, Math.min(3, allFiles.size())));
            }
        } catch (Exception e) { /* ignore */ }
        return candidates;
    }

    private List<String> scanProjectFiles(String projectRoot) {
        List<String> files = new ArrayList<>();
        Set<String> skipDirs = Set.of("node_modules", ".git", "__pycache__", "venv", ".venv",
            "target", "build", "dist", ".idea", ".vscode", ".kaiwu");
        Set<String> sourceExts = Set.of(".java", ".py", ".go", ".rs", ".ts", ".js", ".tsx", ".jsx", ".kt", ".scala");
        try {
            java.nio.file.Files.walkFileTree(java.nio.file.Path.of(projectRoot), new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (skipDirs.contains(dir.getFileName().toString())) return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = file.getFileName().toString().toLowerCase();
                    int dotIdx = name.lastIndexOf('.');
                    if (dotIdx > 0 && sourceExts.contains(name.substring(dotIdx))) {
                        String rel = java.nio.file.Path.of(projectRoot).relativize(file).toString().replace('\\', '/');
                        files.add(rel);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) { /* ignore */ }
        return files;
    }

    /** 收集代码片段 */
    private Map<String, String> collectSnippets(TaskContext ctx, List<String> files) {
        Map<String, String> snippets = new HashMap<>();
        for (String file : files.stream().limit(10).toList()) {
            String content = tools.readFile(file);
            if (!content.startsWith("[ERROR]")) {
                // 截取前2000字符（上下文压缩）
                snippets.put(file, content.length() > 2000 ? content.substring(0, 2000) + "\n...(truncated)" : content);
            }
        }
        return snippets;
    }
}
