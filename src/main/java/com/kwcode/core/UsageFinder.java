package com.kwcode.core;

import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用法查找器 - 在项目中查找符号的用法位置
 * <p>
 * 纯确定性，零LLM调用。使用grep/ripgrep搜索符号引用。
 * 供Locator和Refactor专家使用。
 * </p>
 * @origin Python: core.usage_finder.UsageFinder
 */
public class UsageFinder {

    private static final Logger log = LoggerFactory.getLogger(UsageFinder.class);

    private static final Pattern FUNC_DEF_PATTERN = Pattern.compile(
        "(?:def|function|func|fn|public|private|protected|static)\\s+(\\w+)\\s*\\("
    );

    private static final Pattern CLASS_DEF_PATTERN = Pattern.compile(
        "(?:class|interface|enum|struct|type)\\s+(\\w+)"
    );

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
        "(?:import|from|require|use)\\s+.*?\\b(\\w+)\\b"
    );

    private final ToolGateway tools;

    public UsageFinder(ToolGateway tools) {
        this.tools = tools;
    }

    public UsageFinder() {
        this.tools = null;
    }

    /**
     * 查找符号的所有用法位置
     * <p>
     * 返回每个用法位置的文件路径和行号。
     * </p>
     * @origin Python: core.usage_finder.UsageFinder.find_usages(symbol, project_root) -> list[Usage]
     * @param symbol 符号名称（函数名、类名、变量名）
     * @param projectRoot 项目根目录
     * @return 用法位置列表
     */
    public List<Usage> findUsages(String symbol, String projectRoot) {
        if (symbol == null || symbol.isEmpty()) return List.of();

        List<Usage> usages = new ArrayList<>();

        if (tools != null) {
            try {
                String grepCmd = "grep -rn \"" + symbol + "\" " + projectRoot + " --include=\"*.py\" --include=\"*.java\" --include=\"*.js\" --include=\"*.ts\" 2>/dev/null || true";
                var result = tools.runBash(grepCmd, projectRoot, 10);
                String grepResult = result.stdout();
                if (grepResult != null && !grepResult.isEmpty()) {
                    usages.addAll(parseGrepOutput(grepResult, symbol));
                }
            } catch (Exception e) {
                log.debug("[usage_finder] grep failed: {}", e.getMessage());
            }
        }

        log.debug("[usage_finder] Found {} usages for '{}'", usages.size(), symbol);
        return usages;
    }

    /**
     * 查找文件中定义的所有符号
     * <p>
     * 提取函数定义和类定义。
     * </p>
     * @origin Python: core.usage_finder.UsageFinder.find_definitions(file_path) -> list[str]
     * @param filePath 文件路径
     * @return 符号名称列表
     */
    public List<String> findDefinitions(String filePath) {
        List<String> defs = new ArrayList<>();

        String content = null;
        if (tools != null) {
            content = tools.readFile(filePath);
        }

        if (content == null || content.isEmpty() || content.startsWith("[ERROR]")) {
            return defs;
        }

        Matcher funcMatcher = FUNC_DEF_PATTERN.matcher(content);
        while (funcMatcher.find()) {
            defs.add(funcMatcher.group(1));
        }

        Matcher classMatcher = CLASS_DEF_PATTERN.matcher(content);
        while (classMatcher.find()) {
            defs.add(classMatcher.group(1));
        }

        return defs;
    }

    /**
     * 查找文件的导入依赖
     * @origin Python: core.usage_finder.UsageFinder.find_imports(file_path) -> list[str]
     * @param filePath 文件路径
     * @return 导入的模块/符号列表
     */
    public List<String> findImports(String filePath) {
        List<String> imports = new ArrayList<>();

        String content = null;
        if (tools != null) {
            content = tools.readFile(filePath);
        }

        if (content == null || content.isEmpty() || content.startsWith("[ERROR]")) {
            return imports;
        }

        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find()) {
            String symbol = m.group(1);
            if (!imports.contains(symbol)) {
                imports.add(symbol);
            }
        }

        return imports;
    }

    /**
     * 解析grep输出为Usage列表
     */
    private List<Usage> parseGrepOutput(String grepResult, String symbol) {
        List<Usage> usages = new ArrayList<>();
        String[] lines = grepResult.split("\n");

        for (String line : lines) {
            if (line.trim().isEmpty()) continue;

            int colonIdx = line.indexOf(':');
            if (colonIdx < 0) continue;

            String filePart = line.substring(0, colonIdx);
            String rest = line.substring(colonIdx + 1);

            int lineNum = 0;
            int secondColon = rest.indexOf(':');
            if (secondColon > 0) {
                try {
                    lineNum = Integer.parseInt(rest.substring(0, secondColon).trim());
                    rest = rest.substring(secondColon + 1);
                } catch (NumberFormatException ignored) {}
            }

            UsageType type = classifyUsage(rest, symbol);
            usages.add(new Usage(filePart, lineNum, rest.trim(), type));
        }

        return usages;
    }

    /**
     * 分类用法类型
     */
    private UsageType classifyUsage(String line, String symbol) {
        String trimmed = line.trim();
        if (trimmed.startsWith("def ") || trimmed.startsWith("class ") ||
            trimmed.startsWith("function ") || trimmed.startsWith("public ")) {
            return UsageType.DEFINITION;
        }
        if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
            return UsageType.IMPORT;
        }
        if (trimmed.contains(symbol + "(") || trimmed.contains(symbol + ".") || trimmed.contains(symbol + " =")) {
            return UsageType.CALL;
        }
        return UsageType.REFERENCE;
    }

    /**
     * 用法位置
     */
    public record Usage(
        String filePath,
        int lineNumber,
        String lineContent,
        UsageType type
    ) {}

    /**
     * 用法类型
     */
    public enum UsageType {
        DEFINITION("definition"),
        CALL("call"),
        IMPORT("import"),
        REFERENCE("reference");

        private final String key;
        UsageType(String key) { this.key = key; }
        public String getKey() { return key; }
    }
}
