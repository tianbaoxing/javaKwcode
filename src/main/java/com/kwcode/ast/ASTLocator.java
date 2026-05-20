package com.kwcode.ast;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AST定位器 - 基于tree-sitter调用图的代码定位
 * <p>
 * 定位策略：
 * 1. 构建项目调用图
 * 2. 从任务描述/错误信息中提取关键词
 * 3. 匹配入口函数
 * 4. 沿调用图扩展2跳
 * 5. 返回候选函数及文件位置
 * </p>
 * @origin Python: ast_engine.locator.ASTLocator
 */
public class ASTLocator {

    private final Parser parser;

    /**
     * 需要跳过的常见Python关键字/噪音词
     * @origin Python: ast_engine.locator._SKIP_KEYWORDS
     */
    private static final Set<String> SKIP_KEYWORDS = Set.of(
        "self", "cls", "none", "true", "false", "return", "import", "from",
        "class", "def", "if", "else", "for", "while", "try", "except",
        "with", "as", "in", "not", "and", "or", "is", "the", "a", "an",
        "print", "len", "str", "int", "list", "dict", "set", "type"
    );

    /**
     * 中文术语到英文函数名关键词的映射
     * @origin Python: ast_engine.locator._extract_keywords 中的 cn_map
     */
    private static final Map<String, String> CN_MAP = Map.ofEntries(
        Map.entry("密码", "password"),
        Map.entry("登录", "login"),
        Map.entry("分页", "paginate"),
        Map.entry("上传", "upload"),
        Map.entry("缓存", "cache"),
        Map.entry("过期", "expire"),
        Map.entry("断开", "disconnect"),
        Map.entry("日期", "date"),
        Map.entry("时区", "timezone"),
        Map.entry("订单", "order"),
        Map.entry("库存", "stock"),
        Map.entry("配置", "config"),
        Map.entry("环境变量", "env"),
        Map.entry("邮件", "email"),
        Map.entry("附件", "attach"),
        Map.entry("导出", "export"),
        Map.entry("乱码", "encode"),
        Map.entry("校验", "verify"),
        Map.entry("发送", "send"),
        Map.entry("连接", "connect"),
        Map.entry("刷新", "refresh"),
        Map.entry("超卖", "deduct"),
        Map.entry("文件名", "filename")
    );

    public ASTLocator(Parser parser) {
        this.parser = parser;
    }

    public ASTLocator() {
        this.parser = new Parser();
    }

    /**
     * 使用AST调用图定位相关函数
     * <p>
     * 完整定位流程：构建调用图 → 提取关键词 → 匹配入口函数 →
     * 调用图扩展 → 按关系和关键词相关性排序 → 返回结果。
     * </p>
     * @origin Python: ast_engine.locator.ASTLocator.locate(project_root: str, task_description: str, error_keywords: list[str]|None) -> dict
     * @param projectRoot 项目根目录
     * @param taskDescription 任务描述文本
     * @param errorKeywords 额外的错误关键词，可为null
     * @return 定位结果，包含相关文件、函数和候选列表
     */
    public LocateResult locate(String projectRoot, String taskDescription,
                                List<String> errorKeywords) {
        // 1. 构建调用图
        CallGraph graph = CallGraphBuilder.buildFromProject(projectRoot, parser, 50);

        // 2. 提取关键词
        List<String> keywords = extractKeywords(taskDescription);
        if (errorKeywords != null) {
            for (String kw : errorKeywords) {
                keywords.add(kw.toLowerCase());
            }
        }

        // 3. 查找入口函数
        Set<String> entryFuncs = new LinkedHashSet<>();
        for (String kw : keywords) {
            entryFuncs.addAll(graph.findByKeyword(kw));
        }

        // 3b. 如果关键词无匹配，尝试匹配文件路径
        if (entryFuncs.isEmpty()) {
            for (String kw : keywords) {
                for (String funcName : graph.getFunctions()) {
                    CallGraph.FunctionLocation loc = graph.getLocation(funcName);
                    if (loc != null && loc.file().toLowerCase().contains(kw)) {
                        entryFuncs.add(funcName);
                    }
                }
            }
        }

        // 4. 沿调用图扩展
        List<CallGraph.RelatedFunction> allCandidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String entry : entryFuncs) {
            List<CallGraph.RelatedFunction> related = graph.getRelated(entry, 2);
            for (CallGraph.RelatedFunction r : related) {
                if (seen.add(r.name())) {
                    allCandidates.add(r);
                }
            }
        }

        // 5. 排序：entry > callee > caller，然后按关键词相关性
        Map<String, Integer> relationOrder = Map.of("entry", 0, "callee", 1, "caller", 2);
        allCandidates.sort((a, b) -> {
            int relCmp = Integer.compare(
                relationOrder.getOrDefault(a.relation(), 3),
                relationOrder.getOrDefault(b.relation(), 3)
            );
            if (relCmp != 0) return relCmp;
            return Integer.compare(
                -keywordScore(a.name(), keywords),
                -keywordScore(b.name(), keywords)
            );
        });

        // 6. 提取不重复的相关文件列表
        List<String> relevantFiles = new ArrayList<>();
        Set<String> fileSeen = new HashSet<>();
        for (CallGraph.RelatedFunction c : allCandidates) {
            if (fileSeen.add(c.file())) {
                relevantFiles.add(c.file());
            }
        }

        List<String> relevantFunctions = allCandidates.stream()
            .map(CallGraph.RelatedFunction::name)
            .toList();

        return new LocateResult(relevantFiles, relevantFunctions, allCandidates);
    }

    /**
     * 从任务描述中提取有意义的关键词
     * <p>
     * 分词后过滤掉常见关键字和短词，同时支持中文术语映射到英文函数名。
     * </p>
     * @origin Python: ast_engine.locator.ASTLocator._extract_keywords(text: str) -> list[str]
     * @param text 任务描述文本
     * @return 提取的关键词列表
     */
    public static List<String> extractKeywords(String text) {
        if (text == null) return List.of();

        // 提取英文标识符
        Pattern pattern = Pattern.compile("[a-zA-Z_]\\w*");
        Matcher matcher = pattern.matcher(text.toLowerCase());
        List<String> keywords = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!SKIP_KEYWORDS.contains(token) && token.length() > 2) {
                keywords.add(token);
            }
        }

        // 中文术语映射
        for (Map.Entry<String, String> entry : CN_MAP.entrySet()) {
            if (text.contains(entry.getKey())) {
                keywords.add(entry.getValue());
            }
        }

        return keywords;
    }

    /**
     * 计算函数名与关键词的匹配分数
     * @origin Python: ast_engine.locator.ASTLocator._keyword_score(func_name: str, keywords: list[str]) -> int
     * @param funcName 函数名
     * @param keywords 关键词列表
     * @return 匹配的关键词数量
     */
    public static int keywordScore(String funcName, List<String> keywords) {
        String nameLower = funcName.toLowerCase();
        int score = 0;
        for (String kw : keywords) {
            if (nameLower.contains(kw)) score++;
        }
        return score;
    }

    /**
     * 定位结果
     */
    public record LocateResult(
        List<String> relevantFiles,
        List<String> relevantFunctions,
        List<CallGraph.RelatedFunction> candidates
    ) {}
}
