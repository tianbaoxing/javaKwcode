package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * KWCODE.md规则加载器 - 读取项目根目录的KWCODE.md文件
 * <p>
 * KWCODE.md是项目级规则文件，定义编码规范、约束和偏好。
 * 这些规则会被注入到Generator的prompt中。
 * </p>
 * <p>
 * 文件格式（Markdown）：
 * ## 编码规范
 * - 规则1
 * - 规则2
 * ## 禁止事项
 * - 禁止1
 * </p>
 * @origin Python: core.kwcode_md.KWCodeMD
 */
public class KWCodeMD {

    private static final Logger log = LoggerFactory.getLogger(KWCodeMD.class);

    private static final String DEFAULT_FILENAME = "KWCODE.md";
    private static final Set<String> ALT_FILENAMES = Set.of("KWCODE.md", "kwcode.md", ".kwcode.md", "CODE_RULES.md");

    private String projectRoot;
    private String cachedContent;
    private long lastModified;
    private Map<String, List<String>> sections = new LinkedHashMap<>();

    public KWCodeMD() {
        this.projectRoot = ".";
    }

    public KWCodeMD(String projectRoot) {
        this.projectRoot = projectRoot != null ? projectRoot : ".";
    }

    /**
     * 加载KWCODE.md内容
     * <p>
     * 自动查找项目根目录下的KWCODE.md文件。
     * 支持多种文件名格式。
     * </p>
     * @origin Python: core.kwcode_md.KWCodeMD.load(project_root) -> str
     * @return 规则文本，未找到返回空字符串
     */
    public String load() {
        try {
            Path mdFile = findKwcodeFile();
            if (mdFile == null) {
                log.debug("[kwcode_md] No KWCODE.md found in {}", projectRoot);
                return "";
            }

            long modified = Files.getLastModifiedTime(mdFile).toMillis();
            if (cachedContent != null && modified == lastModified) {
                return cachedContent;
            }

            String content = Files.readString(mdFile);
            cachedContent = content;
            lastModified = modified;
            sections = parseSections(content);

            log.info("[kwcode_md] Loaded {} ({} chars, {} sections)",
                mdFile.getFileName(), content.length(), sections.size());

            return content;
        } catch (IOException e) {
            log.debug("[kwcode_md] Failed to load: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 获取指定section的内容
     * @origin Python: core.kwcode_md.KWCodeMD.get_section(section_name) -> list[str]
     * @param sectionName section名称（不含##）
     * @return 规则列表
     */
    public List<String> getSection(String sectionName) {
        if (sections.isEmpty() && cachedContent == null) {
            load();
        }
        return sections.getOrDefault(sectionName, List.of());
    }

    /**
     * 获取所有编码规范规则
     * @origin Python: core.kwcode_md.KWCodeMD.get_coding_rules() -> list[str]
     * @return 编码规范列表
     */
    public List<String> getCodingRules() {
        List<String> rules = new ArrayList<>();
        for (String key : List.of("编码规范", "Coding Rules", "Rules", "规范")) {
            List<String> section = getSection(key);
            if (!section.isEmpty()) {
                rules.addAll(section);
                break;
            }
        }
        return rules;
    }

    /**
     * 获取所有禁止事项
     * @origin Python: core.kwcode_md.KWCodeMD.get_prohibitions() -> list[str]
     * @return 禁止事项列表
     */
    public List<String> getProhibitions() {
        List<String> rules = new ArrayList<>();
        for (String key : List.of("禁止事项", "Prohibitions", "Forbidden", "禁止")) {
            List<String> section = getSection(key);
            if (!section.isEmpty()) {
                rules.addAll(section);
                break;
            }
        }
        return rules;
    }

    /**
     * 生成注入到prompt的规则文本
     * @origin Python: core.kwcode_md.KWCodeMD.to_prompt_text() -> str
     * @return 格式化的规则文本
     */
    public String toPromptText() {
        String content = load();
        if (content.isEmpty()) return "";

        if (content.length() > 1500) {
            content = content.substring(0, 1500) + "\n...(truncated)";
        }

        return "## 项目规则（来自KWCODE.md）\n" + content;
    }

    /**
     * 查找KWCODE.md文件
     */
    private Path findKwcodeFile() {
        Path root = Paths.get(projectRoot);

        for (String name : ALT_FILENAMES) {
            Path file = root.resolve(name);
            if (Files.exists(file) && Files.isRegularFile(file)) {
                return file;
            }
        }

        Path docsDir = root.resolve("docs");
        for (String name : ALT_FILENAMES) {
            Path file = docsDir.resolve(name);
            if (Files.exists(file) && Files.isRegularFile(file)) {
                return file;
            }
        }

        return null;
    }

    /**
     * 解析Markdown sections
     */
    private Map<String, List<String>> parseSections(String content) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        String currentSection = null;
        List<String> currentItems = new ArrayList<>();

        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("## ")) {
                if (currentSection != null && !currentItems.isEmpty()) {
                    result.put(currentSection, new ArrayList<>(currentItems));
                }
                currentSection = trimmed.substring(3).strip();
                currentItems = new ArrayList<>();
            } else if (trimmed.startsWith("- ") && currentSection != null) {
                currentItems.add(trimmed.substring(2).strip());
            }
        }

        if (currentSection != null && !currentItems.isEmpty()) {
            result.put(currentSection, new ArrayList<>(currentItems));
        }

        return result;
    }

    public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
    public Map<String, List<String>> getSections() { return Collections.unmodifiableMap(sections); }
}
