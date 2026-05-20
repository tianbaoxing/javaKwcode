package com.kwcode.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST辅助工具 - 从源代码提取函数/类定义列表
 * <p>
 * 给Locator提供候选列表，LLM只需从中选择，不需要自己找。
 * Python用AST精确提取，其他语言用正则降级。
 * </p>
 * @origin Python: tools.ast_utils
 */
public class AstUtils {

    private static final Logger log = LoggerFactory.getLogger(AstUtils.class);

    /**
     * 从源代码提取所有函数/类定义
     * @origin Python: tools.ast_utils.extract_symbols(source, language) -> list[dict]
     * @param source 源代码字符串
     * @param language 语言类型
     * @return 符号列表
     */
    public List<SymbolInfo> extractSymbols(String source, String language) {
        // Java等非Python语言用正则
        return extractRegex(source);
    }

    /**
     * 正则降级提取（支持Python/JS/Go/Rust等）
     * @origin Python: tools.ast_utils._extract_regex(source) -> list[dict]
     */
    private List<SymbolInfo> extractRegex(String source) {
        List<SymbolInfo> symbols = new ArrayList<>();

        // 多语言匹配模式
        List<SymbolPattern> patterns = List.of(
            // Python: def func_name( / async def func_name(
            new SymbolPattern("^\\s*(?:async\\s+)?def\\s+(\\w+)\\s*\\(", "function"),
            new SymbolPattern("^\\s*class\\s+(\\w+)", "class"),
            // JavaScript/TypeScript
            new SymbolPattern("^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)", "function"),
            new SymbolPattern("^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*=\\s*(?:async\\s+)?\\(", "function"),
            // Go: func Name(
            new SymbolPattern("^\\s*func\\s+(?:\\([^)]*\\)\\s+)?(\\w+)\\s*\\(", "function"),
            // Rust: fn name( / pub fn name(
            new SymbolPattern("^\\s*(?:pub\\s+)?fn\\s+(\\w+)", "function"),
            new SymbolPattern("^\\s*(?:pub\\s+)?struct\\s+(\\w+)", "class"),
            // Java: method declarations
            new SymbolPattern("^\\s*(?:public|private|protected|static|final|synchronized|native|abstract)\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(", "function"),
            new SymbolPattern("^\\s*class\\s+(\\w+)", "class")
        );

        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            for (SymbolPattern sp : patterns) {
                Matcher m = sp.pattern.matcher(lines[i]);
                if (m.find()) {
                    symbols.add(new SymbolInfo(m.group(1), sp.type, i + 1));
                    break; // 一行只匹配一次
                }
            }
        }
        return symbols;
    }

    /**
     * 格式化为LLM可读的候选列表
     * @origin Python: tools.ast_utils.format_symbol_list(symbols) -> str
     */
    public String formatSymbolList(List<SymbolInfo> symbols) {
        if (symbols.isEmpty()) return "(无函数/类定义)";
        StringBuilder sb = new StringBuilder();
        for (SymbolInfo s : symbols) {
            sb.append("  - ").append(s.name()).append(" (").append(s.type()).append(", line ").append(s.line()).append(")\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 符号信息 */
    public record SymbolInfo(String name, String type, int line) {}

    /** 正则模式封装 */
    private record SymbolPattern(Pattern pattern, String type) {
        SymbolPattern(String regex, String type) { this(Pattern.compile(regex), type); }
    }
}
