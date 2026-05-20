package com.kwcode.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 多语言源码解析器
 * <p>
 * 使用JavaParser解析Java源码，使用ANTLR解析其他语言。
 * 从源文件中提取函数定义和函数调用关系。
 * 替代Python版tree-sitter解析器。
 * </p>
 * @origin Python: ast_engine.parser.TreeSitterParser
 */
public class Parser {

    /**
     * 支持的文件扩展名到语言的映射
     * @origin Python: ast_engine.parser.TreeSitterParser.EXT_MAP
     */
    public static final Map<String, Language> EXT_MAP = Map.of(
        ".py", Language.PYTHON,
        ".js", Language.JAVASCRIPT,
        ".mjs", Language.JAVASCRIPT,
        ".ts", Language.TYPESCRIPT,
        ".tsx", Language.TYPESCRIPT,
        ".go", Language.GO,
        ".rs", Language.RUST,
        ".java", Language.JAVA
    );

    /**
     * 获取当前支持的语言列表
     * @origin Python: ast_engine.parser.TreeSitterParser.supported_languages() -> list[str]
     * @return 支持的语言列表
     */
    public List<Language> supportedLanguages() {
        // JavaParser始终可用，其他语言按ANTLR语法文件可用性决定
        return List.of(Language.JAVA, Language.PYTHON);
    }

    /**
     * 检测文件的语言类型
     * @origin Python: ast_engine.parser.TreeSitterParser.detect_file_language(filepath: str) -> Optional[str]
     * @param filePath 文件路径
     * @return 语言枚举，不支持返回null
     */
    public Language detectFileLanguage(String filePath) {
        if (filePath == null) return null;
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx < 0) return null;
        String ext = filePath.substring(dotIdx).toLowerCase();
        Language lang = EXT_MAP.get(ext);
        if (lang != null && supportedLanguages().contains(lang)) {
            return lang;
        }
        return null;
    }

    /**
     * 解析单个源文件，提取函数定义
     * <p>
     * 根据文件扩展名自动选择解析器（JavaParser或ANTLR），
     * 返回文件中所有函数/方法的定义信息。
     * </p>
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_functions(tree, source, language) -> list[dict]
     * @param filePath 源文件路径
     * @return 函数定义列表
     */
    public List<FunctionDef> parseFile(String filePath) {
        Language lang = detectFileLanguage(filePath);
        if (lang == null) return List.of();

        try {
            String source = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
            return extractFunctions(source, lang);
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 从源码字符串中提取函数定义
     * <p>
     * 按语言类型分派到对应的解析逻辑。
     * Java使用JavaParser，其他语言使用正则或ANTLR。
     * </p>
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_functions(tree, source, language) -> list[dict]
     * @param source 源码字符串
     * @param language 语言类型
     * @return 函数定义列表
     */
    public List<FunctionDef> extractFunctions(String source, Language language) {
        return switch (language) {
            case JAVA -> extractJavaFunctions(source);
            case PYTHON -> extractPythonFunctions(source);
            default -> List.of();
        };
    }

    /**
     * 从源码中提取函数调用关系
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_calls(tree, source, language) -> list[dict]
     * @param source 源码字符串
     * @param language 语言类型
     * @return 函数调用列表
     */
    public List<FunctionCall> extractCalls(String source, Language language) {
        return switch (language) {
            case JAVA -> extractJavaCalls(source);
            case PYTHON -> extractPythonCalls(source);
            default -> List.of();
        };
    }

    /**
     * 使用JavaParser提取Java函数定义
     * <p>
     * 解析Java源码，提取所有方法声明（包括构造方法），
     * 返回方法名、参数、起止行号等信息。
     * </p>
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_functions (java branch)
     * @param source Java源码
     * @return 函数定义列表
     */
    private List<FunctionDef> extractJavaFunctions(String source) {
        List<FunctionDef> results = new ArrayList<>();
        try {
            com.github.javaparser.JavaParser jp = new com.github.javaparser.JavaParser();
            var cu = jp.parse(source).getResult().orElse(null);
            if (cu == null) return results;

            cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(md -> {
                String className = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElse("");
                String funcName = md.getNameAsString();
                String qualified = className.isEmpty() ? funcName : className + "." + funcName;
                List<String> params = md.getParameters().stream()
                    .map(p -> p.getNameAsString())
                    .toList();
                md.getRange().ifPresent(range -> {
                    results.add(new FunctionDef(
                        qualified,
                        range.begin.line,
                        range.end.line,
                        params
                    ));
                });
            });

            cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class).forEach(cd -> {
                String className = cd.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElse("");
                List<String> params = cd.getParameters().stream()
                    .map(p -> p.getNameAsString())
                    .toList();
                cd.getRange().ifPresent(range -> {
                    results.add(new FunctionDef(
                        className + ".<init>",
                        range.begin.line,
                        range.end.line,
                        params
                    ));
                });
            });
        } catch (Exception e) {
            // 解析失败，静默返回
        }
        return results;
    }

    /**
     * 使用正则提取Python函数定义（简易版）
     * <p>
     * 通过正则匹配提取Python函数定义，支持class.method格式。
     * 不如tree-sitter精确，但无需Python运行时。
     * </p>
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_functions (python branch)
     * @param source Python源码
     * @return 函数定义列表
     */
    private List<FunctionDef> extractPythonFunctions(String source) {
        List<FunctionDef> results = new ArrayList<>();
        java.util.regex.Pattern funcPattern = java.util.regex.Pattern.compile(
            "^(\\s*)def\\s+(\\w+)\\s*\\(([^)]*)\\)", java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Pattern classPattern = java.util.regex.Pattern.compile(
            "^class\\s+(\\w+)", java.util.regex.Pattern.MULTILINE
        );

        // 先找到所有类定义的位置和名称
        List<int[]> classRanges = new ArrayList<>();
        var classMatcher = classPattern.matcher(source);
        while (classMatcher.find()) {
            classRanges.add(new int[]{classMatcher.start(), classMatcher.end(), classMatcher.start()});
        }

        String[] lines = source.split("\n");
        String currentClass = "";
        int lineNum = 0;
        int classStartLine = -1;

        for (String line : lines) {
            lineNum++;
            // 检测类定义
            var cm = java.util.regex.Pattern.compile("^class\\s+(\\w+)").matcher(line.trim());
            if (cm.find()) {
                currentClass = cm.group(1);
                classStartLine = lineNum;
            }
            // 检测函数定义
            var fm = java.util.regex.Pattern.compile("^\\s*def\\s+(\\w+)\\s*\\(([^)]*)\\)").matcher(line);
            if (fm.find()) {
                String funcName = fm.group(1);
                String paramsStr = fm.group(2);
                List<String> params = parsePythonParams(paramsStr);
                String qualified = currentClass.isEmpty() ? funcName : currentClass + "." + funcName;
                // 简化：endLine近似为startLine（精确值需要更复杂的缩进分析）
                results.add(new FunctionDef(qualified, lineNum, lineNum, params));
            }
        }
        return results;
    }

    /**
     * 使用JavaParser提取Java函数调用
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_calls (java branch)
     * @param source Java源码
     * @return 函数调用列表
     */
    private List<FunctionCall> extractJavaCalls(String source) {
        List<FunctionCall> results = new ArrayList<>();
        try {
            com.github.javaparser.JavaParser jp = new com.github.javaparser.JavaParser();
            var cu = jp.parse(source).getResult().orElse(null);
            if (cu == null) return results;

            cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
                String callee = call.getNameAsString();
                String caller = call.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
                    .map(md -> {
                        String className = md.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
                            .map(c -> c.getNameAsString())
                            .orElse("");
                        return className.isEmpty() ? md.getNameAsString() : className + "." + md.getNameAsString();
                    })
                    .orElse(null);
                call.getRange().ifPresent(range -> {
                    results.add(new FunctionCall(callee, range.begin.line, caller));
                });
            });
        } catch (Exception e) {
            // 解析失败，静默返回
        }
        return results;
    }

    /**
     * 使用正则提取Python函数调用（简易版）
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_calls (python branch)
     * @param source Python源码
     * @return 函数调用列表
     */
    private List<FunctionCall> extractPythonCalls(String source) {
        List<FunctionCall> results = new ArrayList<>();
        // 匹配 function_name( 或 obj.method( 格式的调用
        java.util.regex.Pattern callPattern = java.util.regex.Pattern.compile(
            "(\\w+(?:\\.\\w+)?)\\s*\\("
        );
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            var matcher = callPattern.matcher(lines[i]);
            while (matcher.find()) {
                String callee = matcher.group(1);
                // 取最后一部分作为方法名
                if (callee.contains(".")) {
                    callee = callee.substring(callee.lastIndexOf('.') + 1);
                }
                results.add(new FunctionCall(callee, i + 1, null)); // caller需要更复杂分析
            }
        }
        return results;
    }

    /**
     * 解析Python函数参数字符串
     * @origin Python: ast_engine.parser.TreeSitterParser._extract_params_python(params_node) -> list[str]
     * @param paramsStr 参数字符串（如 "self, name, age=18"）
     * @return 参数名列表
     */
    private List<String> parsePythonParams(String paramsStr) {
        if (paramsStr == null || paramsStr.isBlank()) return List.of();
        List<String> params = new ArrayList<>();
        for (String param : paramsStr.split(",")) {
            param = param.trim();
            if (param.isEmpty()) continue;
            // 去掉类型注解和默认值
            param = param.split(":")[0].split("=")[0].trim();
            if (!param.isEmpty()) {
                params.add(param);
            }
        }
        return params;
    }

    /**
     * 函数定义信息
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_functions 返回值
     */
    public record FunctionDef(String name, int startLine, int endLine, List<String> params) {}

    /**
     * 函数调用信息
     * @origin Python: ast_engine.parser.TreeSitterParser.extract_calls 返回值
     */
    public record FunctionCall(String name, int line, String inFunction) {}
}
