package com.kwcode.ast;

import java.util.*;

/**
 * 函数调用图 - 基于tree-sitter分析构建的调用关系图
 * <p>
 * 使用简单的Map结构存储调用关系（而非networkx），
 * 图规模通常较小，Map足以胜任。支持双向查找：
 * 调用者→被调用函数，被调用函数→调用者。
 * </p>
 * @origin Python: ast_engine.call_graph.CallGraph
 */
public class CallGraph {

    /** 函数 → 该函数调用的所有函数集合 */
    private final Map<String, Set<String>> callers = new HashMap<>();

    /** 函数 → 调用该函数的所有函数集合 */
    private final Map<String, Set<String>> callees = new HashMap<>();

    /** 函数 → 位置信息（文件路径、起止行号） */
    private final Map<String, FunctionLocation> locations = new HashMap<>();

    /**
     * 函数位置信息
     */
    public record FunctionLocation(String file, int startLine, int endLine) {}

    /**
     * 注册一个函数定义
     * <p>
     * 在调用图中添加一个函数节点，记录其位置信息。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph.add_function(name: str, file: str, start_line: int, end_line: int)
     * @param name 函数全限定名（如 "ClassName.methodName"）
     * @param file 文件相对路径
     * @param startLine 函数起始行号
     * @param endLine 函数结束行号
     */
    public void addFunction(String name, String file, int startLine, int endLine) {
        callers.computeIfAbsent(name, k -> new HashSet<>());
        callees.computeIfAbsent(name, k -> new HashSet<>());
        locations.put(name, new FunctionLocation(file, startLine, endLine));
    }

    /**
     * 注册一个函数调用关系
     * <p>
     * 记录 caller 调用了 callee 的关系，同时更新双向索引。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph.add_call(caller: str, callee: str)
     * @param caller 调用方函数名
     * @param callee 被调用方函数名
     */
    public void addCall(String caller, String callee) {
        callers.computeIfAbsent(caller, k -> new HashSet<>()).add(callee);
        callees.computeIfAbsent(callee, k -> new HashSet<>()).add(caller);
    }

    /**
     * 获取所有已注册的函数名列表
     * @origin Python: ast_engine.call_graph.CallGraph.functions -> list[str]
     * @return 函数名列表
     */
    public List<String> getFunctions() {
        return new ArrayList<>(locations.keySet());
    }

    /**
     * 获取函数的位置信息
     * @origin Python: ast_engine.call_graph.CallGraph.get_location(name: str) -> Optional[dict]
     * @param name 函数名
     * @return 位置信息，不存在返回null
     */
    public FunctionLocation getLocation(String name) {
        return locations.get(name);
    }

    /**
     * 获取与入口函数相关的所有函数（双向扩展）
     * <p>
     * 从入口函数出发，沿着调用图双向扩展（被调用和调用者），
     * 在指定深度内查找所有相关函数。结果按关系类型排序：
     * entry > callee > caller。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph.get_related(entry_func: str, depth: int = 2) -> list[dict]
     * @param entryFunc 入口函数名
     * @param depth 扩展深度（跳数），默认2
     * @return 相关函数列表，包含名称、文件、行号和关系类型
     */
    public List<RelatedFunction> getRelated(String entryFunc, int depth) {
        if (!locations.containsKey(entryFunc)) {
            return List.of();
        }

        Map<String, String> visited = new LinkedHashMap<>();
        visited.put(entryFunc, "entry");
        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        queue.add(Map.entry(entryFunc, 0));

        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> current = queue.poll();
            String func = current.getKey();
            int d = current.getValue();
            if (d >= depth) continue;

            // 当前函数调用的函数（下游）
            for (String callee : callers.getOrDefault(func, Set.of())) {
                if (!visited.containsKey(callee) && locations.containsKey(callee)) {
                    visited.put(callee, "callee");
                    queue.add(Map.entry(callee, d + 1));
                }
            }

            // 调用当前函数的函数（上游）
            for (String caller : callees.getOrDefault(func, Set.of())) {
                if (!visited.containsKey(caller) && locations.containsKey(caller)) {
                    visited.put(caller, "caller");
                    queue.add(Map.entry(caller, d + 1));
                }
            }
        }

        List<RelatedFunction> results = new ArrayList<>();
        for (Map.Entry<String, String> entry : visited.entrySet()) {
            FunctionLocation loc = locations.get(entry.getKey());
            if (loc != null) {
                results.add(new RelatedFunction(
                    entry.getKey(), loc.file, loc.startLine, entry.getValue()
                ));
            }
        }
        return results;
    }

    /**
     * 按关键词查找函数（大小写不敏感）
     * <p>
     * 查找函数名中包含指定关键词的所有函数。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph.find_by_keyword(keyword: str) -> list[str]
     * @param keyword 搜索关键词
     * @return 匹配的函数名列表
     */
    public List<String> findByKeyword(String keyword) {
        String kw = keyword.toLowerCase();
        return locations.keySet().stream()
            .filter(name -> name.toLowerCase().contains(kw))
            .toList();
    }

    /**
     * 解析非全限定调用名为全限定名
     * <p>
     * 当调用图中的callee是非全限定名（如"bar"）时，
     * 尝试将其解析为全限定名（如"Foo.bar"）。
     * 只有当短名只有唯一匹配时才解析。
     * </p>
     * @origin Python: ast_engine.call_graph.CallGraph._resolve_calls()
     */
    public void resolveCalls() {
        // 构建 short_name -> [qualified_names] 索引
        Map<String, List<String>> shortToQualified = new HashMap<>();
        for (String name : locations.keySet()) {
            String shortName = name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1)
                : name;
            shortToQualified.computeIfAbsent(shortName, k -> new ArrayList<>()).add(name);
        }

        // 解析每个caller的callee集合
        Map<String, Set<String>> newCallers = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : callers.entrySet()) {
            Set<String> resolved = new HashSet<>();
            for (String callee : entry.getValue()) {
                if (locations.containsKey(callee)) {
                    resolved.add(callee);
                } else {
                    List<String> candidates = shortToQualified.getOrDefault(callee, List.of());
                    if (candidates.size() == 1) {
                        resolved.add(candidates.get(0));
                    } else {
                        resolved.add(callee);
                    }
                }
            }
            newCallers.put(entry.getKey(), resolved);
        }
        callers.clear();
        callers.putAll(newCallers);

        // 重建callees索引
        callees.clear();
        for (Map.Entry<String, Set<String>> entry : callers.entrySet()) {
            for (String callee : entry.getValue()) {
                callees.computeIfAbsent(callee, k -> new HashSet<>()).add(entry.getKey());
            }
        }
    }

    /**
     * 获取caller映射（函数→它调用的函数集合）
     */
    public Map<String, Set<String>> getCallers() {
        return Collections.unmodifiableMap(callers);
    }

    /**
     * 获取callee映射（函数→调用它的函数集合）
     */
    public Map<String, Set<String>> getCallees() {
        return Collections.unmodifiableMap(callees);
    }

    /**
     * 相关函数记录
     */
    public record RelatedFunction(String name, String file, int startLine, String relation) {}
}
