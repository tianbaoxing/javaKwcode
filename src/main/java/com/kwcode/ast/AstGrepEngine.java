package com.kwcode.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * ast-grep引擎 - 预定义查询模板的多语言代码搜索
 * <p>
 * LLM从不直接生成ast-grep模式，只填充参数（函数名等）。
 * 使用预定义模板进行安全的代码结构搜索。
 * 优先使用JavaParser/ANTLR实现，CLI作为后备。
 * </p>
 * @origin Python: ast_engine.ast_grep_engine
 */
public class AstGrepEngine {

    /**
     * 预定义查询模板 - LLM只填充$NAME等参数，不生成原始模式
     * @origin Python: ast_engine.ast_grep_engine.QUERY_TEMPLATES
     */
    public static final Map<String, Map<Language, String>> QUERY_TEMPLATES = Map.of(
        "find_function", Map.of(
            Language.PYTHON, "def $NAME($$$ARGS):\n    $$$BODY",
            Language.JAVASCRIPT, "function $NAME($$$ARGS) { $$$BODY }",
            Language.TYPESCRIPT, "function $NAME($$$ARGS): $RET { $$$BODY }",
            Language.GO, "func $NAME($$$ARGS) $$$RET { $$$BODY }",
            Language.RUST, "fn $NAME($$$ARGS) $$$RET { $$$BODY }",
            Language.JAVA, "$MOD $TYPE $NAME($$$ARGS) { $$$BODY }"
        ),
        "find_class", Map.of(
            Language.PYTHON, "class $NAME($$$PARENTS):\n    $$$BODY",
            Language.JAVASCRIPT, "class $NAME { $$$BODY }",
            Language.TYPESCRIPT, "class $NAME { $$$BODY }",
            Language.JAVA, "class $NAME { $$$BODY }"
        ),
        "find_imports", Map.of(
            Language.PYTHON, "import $MODULE",
            Language.JAVASCRIPT, "import $WHAT from '$MODULE'",
            Language.TYPESCRIPT, "import $WHAT from '$MODULE'",
            Language.GO, "\"$MODULE\"",
            Language.RUST, "use $MODULE",
            Language.JAVA, "import $MODULE"
        ),
        "find_from_import", Map.of(
            Language.PYTHON, "from $MODULE import $WHAT"
        ),
        "find_method_call", Map.of(
            Language.PYTHON, "$OBJ.$METHOD($$$ARGS)",
            Language.JAVASCRIPT, "$OBJ.$METHOD($$$ARGS)",
            Language.TYPESCRIPT, "$OBJ.$METHOD($$$ARGS)",
            Language.GO, "$OBJ.$METHOD($$$ARGS)",
            Language.RUST, "$OBJ.$METHOD($$$ARGS)",
            Language.JAVA, "$OBJ.$METHOD($$$ARGS)"
        )
    );

    /**
     * 使用预定义模板查询代码
     * <p>
     * 根据模板名称和语言获取查询模板，填充参数后执行查询。
     * Java语言使用JavaParser实现，其他语言使用正则匹配。
     * </p>
     * @origin Python: ast_engine.ast_grep_engine.query(pattern_key, lang, code, params) -> list[dict]
     * @param patternKey 模板名称（如 "find_function"）
     * @param language 语言类型
     * @param code 源码字符串
     * @param params 模板参数（如 {"NAME": "main"}），可为null
     * @return 匹配结果列表
     */
    public List<MatchResult> query(String patternKey, Language language,
                                    String code, Map<String, String> params) {
        Map<Language, String> templates = QUERY_TEMPLATES.get(patternKey);
        if (templates == null) return List.of();

        String template = templates.get(language);
        if (template == null) return List.of();

        // Java使用JavaParser实现
        if (language == Language.JAVA) {
            return queryWithJavaParser(patternKey, code, params);
        }

        // 其他语言：使用正则匹配（简化实现）
        return queryWithRegex(template, code, params);
    }

    /**
     * 查找文件中所有函数定义
     * @origin Python: ast_engine.ast_grep_engine.find_functions(file_path, language) -> list[dict]
     * @param filePath 文件路径
     * @param language 语言类型
     * @return 匹配结果列表
     */
    public List<MatchResult> findFunctions(String filePath, Language language) {
        String code = readFile(filePath);
        if (code == null) return List.of();
        return query("find_function", language, code, null);
    }

    /**
     * 查找文件中所有类定义
     * @origin Python: ast_engine.ast_grep_engine.find_classes(file_path, language) -> list[dict]
     * @param filePath 文件路径
     * @param language 语言类型
     * @return 匹配结果列表
     */
    public List<MatchResult> findClasses(String filePath, Language language) {
        String code = readFile(filePath);
        if (code == null) return List.of();
        return query("find_class", language, code, null);
    }

    /**
     * 查找文件中所有导入语句
     * @origin Python: ast_engine.ast_grep_engine.find_imports(file_path, language) -> list[dict]
     * @param filePath 文件路径
     * @param language 语言类型
     * @return 匹配结果列表
     */
    public List<MatchResult> findImports(String filePath, Language language) {
        String code = readFile(filePath);
        if (code == null) return List.of();
        List<MatchResult> results = new ArrayList<>(query("find_imports", language, code, null));
        if (language == Language.PYTHON) {
            results.addAll(query("find_from_import", language, code, null));
        }
        return results;
    }

    /**
     * 检查ast-grep引擎是否可用
     * @origin Python: ast_engine.ast_grep_engine.is_available() -> bool
     * @return true表示可用
     */
    public boolean isAvailable() {
        // JavaParser始终可用
        return true;
    }

    /**
     * 使用JavaParser执行查询
     * <p>
     * 针对Java源码，使用JavaParser进行精确的结构化查询。
     * </p>
     * @param patternKey 查询模板名称
     * @param code Java源码
     * @param params 查询参数
     * @return 匹配结果列表
     */
    private List<MatchResult> queryWithJavaParser(String patternKey, String code,
                                                   Map<String, String> params) {
        List<MatchResult> results = new ArrayList<>();
        try {
            com.github.javaparser.JavaParser jp = new com.github.javaparser.JavaParser();
            var cu = jp.parse(code).getResult().orElse(null);
            if (cu == null) return results;

            switch (patternKey) {
                case "find_function" -> {
                    cu.findAll(com.github.javaparser.ast.body.MethodDeclaration.class).forEach(md -> {
                        String name = md.getNameAsString();
                        if (params == null || name.equals(params.get("NAME"))) {
                            md.getRange().ifPresent(range -> {
                                results.add(new MatchResult(
                                    md.toString(),
                                    range.begin.line,
                                    range.end.line,
                                    range.begin.column,
                                    range.end.column
                                ));
                            });
                        }
                    });
                }
                case "find_class" -> {
                    cu.findAll(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class).forEach(cd -> {
                        String name = cd.getNameAsString();
                        if (params == null || name.equals(params.get("NAME"))) {
                            cd.getRange().ifPresent(range -> {
                                results.add(new MatchResult(
                                    cd.toString(),
                                    range.begin.line,
                                    range.end.line,
                                    range.begin.column,
                                    range.end.column
                                ));
                            });
                        }
                    });
                }
                case "find_imports" -> {
                    cu.findAll(com.github.javaparser.ast.ImportDeclaration.class).forEach(imp -> {
                        imp.getRange().ifPresent(range -> {
                            results.add(new MatchResult(
                                imp.toString(),
                                range.begin.line,
                                range.end.line,
                                range.begin.column,
                                range.end.column
                            ));
                        });
                    });
                }
            }
        } catch (Exception e) {
            // 解析失败
        }
        return results;
    }

    /**
     * 使用正则表达式执行查询（简化实现，非Java语言）
     * @param template 查询模板
     * @param code 源码字符串
     * @param params 模板参数
     * @return 匹配结果列表
     */
    private List<MatchResult> queryWithRegex(String template, String code,
                                              Map<String, String> params) {
        // 简化实现：将模板转换为正则
        // TODO: 更精确的多语言正则匹配
        return List.of();
    }

    /**
     * 读取文件内容
     * @param filePath 文件路径
     * @return 文件内容，读取失败返回null
     */
    private String readFile(String filePath) {
        try {
            return Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 匹配结果
     * @origin Python: ast_engine.ast_grep_engine query返回值
     */
    public record MatchResult(
        String text,
        int startLine,
        int endLine,
        int startCol,
        int endCol
    ) {}
}
