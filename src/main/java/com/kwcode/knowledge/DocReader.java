package com.kwcode.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 文档阅读器 - 读取项目文档并提取结构化内容
 * <p>
 * DocReader负责读取项目中的文档文件（Markdown、README、docstring等），
 * 提取与任务相关的文档上下文，供Locator注入到Generator的prompt中。
 * </p>
 * <p>
 * 支持格式：.md, .rst, .txt, .adoc
 * 搜索范围：项目根目录、docs/、doc/、documentation/
 * </p>
 * @origin Python: knowledge.doc_reader.DocReader
 */
public class DocReader {

    private static final Logger log = LoggerFactory.getLogger(DocReader.class);

    private static final Set<String> DOC_EXTENSIONS = Set.of(".md", ".rst", ".txt", ".adoc", ".markdown");

    private static final Set<String> DOC_DIRS = Set.of("docs", "doc", "documentation", "wiki");

    private static final Set<String> README_NAMES = Set.of(
        "README.md", "README.rst", "README.txt", "README",
        "readme.md", "readme.rst", "readme.txt"
    );

    private static final int MAX_DOC_LENGTH = 3000;
    private static final int MAX_FILES = 10;

    /**
     * 读取项目文档上下文
     * <p>
     * 搜索策略：
     * 1. 读取README
     * 2. 搜索docs/目录
     * 3. 按关键词过滤相关文档
     * </p>
     * @origin Python: knowledge.doc_reader.DocReader.read(project_root, keywords) -> str
     * @param projectRoot 项目根目录
     * @param keywords 关键词列表（用于过滤相关文档）
     * @return 文档上下文文本
     */
    public String read(String projectRoot, List<String> keywords) {
        List<String> sections = new ArrayList<>();

        String readme = readReadme(projectRoot);
        if (!readme.isEmpty()) {
            sections.add("## README\n" + readme);
        }

        List<String> docFiles = findDocFiles(projectRoot);
        for (String docFile : docFiles) {
            if (sections.size() >= MAX_FILES) break;

            String content = readFileContent(Path.of(projectRoot, docFile));
            if (content.isEmpty()) continue;

            if (keywords != null && !keywords.isEmpty()) {
                content = filterByKeywords(content, keywords);
                if (content.isEmpty()) continue;
            }

            sections.add("## " + docFile + "\n" + content);
        }

        if (sections.isEmpty()) return "";

        String result = String.join("\n\n", sections);
        if (result.length() > MAX_DOC_LENGTH) {
            result = result.substring(0, MAX_DOC_LENGTH) + "\n...(truncated)";
        }

        log.debug("[doc_reader] Read {} doc sections, {} chars", sections.size(), result.length());
        return result;
    }

    /**
     * 读取README文件
     * @origin Python: knowledge.doc_reader.DocReader._read_readme(project_root) -> str
     * @param projectRoot 项目根目录
     * @return README内容
     */
    public String readReadme(String projectRoot) {
        Path root = Path.of(projectRoot);

        for (String name : README_NAMES) {
            Path readme = root.resolve(name);
            if (Files.exists(readme) && Files.isRegularFile(readme)) {
                return readFileContent(readme);
            }
        }

        return "";
    }

    /**
     * 查找项目中的文档文件
     * @origin Python: knowledge.doc_reader.DocReader._find_doc_files(project_root) -> list[str]
     * @param projectRoot 项目根目录
     * @return 相对路径列表
     */
    public List<String> findDocFiles(String projectRoot) {
        List<String> files = new ArrayList<>();
        Path root = Path.of(projectRoot);

        for (String dirName : DOC_DIRS) {
            Path docDir = root.resolve(dirName);
            if (!Files.exists(docDir) || !Files.isDirectory(docDir)) continue;

            try (Stream<Path> walk = Files.walk(docDir, 4)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> DOC_EXTENSIONS.contains(getExtension(p)))
                    .forEach(p -> files.add(root.relativize(p).toString().replace('\\', '/')));
            } catch (IOException e) {
                log.debug("[doc_reader] Walk failed for {}: {}", dirName, e.getMessage());
            }
        }

        return files;
    }

    /**
     * 按关键词过滤文档内容
     * <p>
     * 只保留包含关键词的段落（以空行分隔的文本块）。
     * </p>
     * @origin Python: knowledge.doc_reader.DocReader._filter_by_keywords(content, keywords) -> str
     * @param content 文档内容
     * @param keywords 关键词列表
     * @return 过滤后的内容
     */
    public String filterByKeywords(String content, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return content;

        String[] blocks = content.split("\n\n");
        List<String> matched = new ArrayList<>();

        for (String block : blocks) {
            String lower = block.toLowerCase();
            for (String kw : keywords) {
                if (lower.contains(kw.toLowerCase())) {
                    matched.add(block);
                    break;
                }
            }
        }

        return String.join("\n\n", matched);
    }

    private String readFileContent(Path path) {
        try {
            String content = Files.readString(path);
            if (content.length() > 1000) {
                content = content.substring(0, 1000) + "...";
            }
            return content.strip();
        } catch (IOException e) {
            return "";
        }
    }

    private String getExtension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : "";
    }
}
