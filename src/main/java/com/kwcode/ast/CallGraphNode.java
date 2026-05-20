package com.kwcode.ast;

import java.util.*;

/**
 * 调用图节点 - 函数在调用图中的节点表示
 * <p>
 * CallGraphNode是CallGraph中函数节点的完整表示，
 * 包含函数签名、位置、调用关系等结构化信息。
 * 与CallGraph中的record类型（FunctionLocation/RelatedFunction）互补：
 * - FunctionLocation: 只存位置
 * - RelatedFunction: 只存关系
 * - CallGraphNode: 完整节点信息，用于序列化和图遍历
 * </p>
 * @origin Python: ast_engine.call_graph.CallGraphNode
 */
public class CallGraphNode {

    private final String qualifiedName;
    private final String shortName;
    private final String filePath;
    private final int startLine;
    private final int endLine;
    private final String signature;
    private final Set<String> calls;
    private final Set<String> calledBy;
    private final Map<String, Object> metadata;

    public CallGraphNode(String qualifiedName, String filePath, int startLine, int endLine) {
        this.qualifiedName = qualifiedName;
        this.shortName = extractShortName(qualifiedName);
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.signature = "";
        this.calls = new LinkedHashSet<>();
        this.calledBy = new LinkedHashSet<>();
        this.metadata = new LinkedHashMap<>();
    }

    public CallGraphNode(String qualifiedName, String filePath, int startLine, int endLine,
                          String signature, Set<String> calls, Set<String> calledBy) {
        this.qualifiedName = qualifiedName;
        this.shortName = extractShortName(qualifiedName);
        this.filePath = filePath;
        this.startLine = startLine;
        this.endLine = endLine;
        this.signature = signature != null ? signature : "";
        this.calls = new LinkedHashSet<>(calls);
        this.calledBy = new LinkedHashSet<>(calledBy);
        this.metadata = new LinkedHashMap<>();
    }

    /**
     * 添加调用关系
     * @origin Python: ast_engine.call_graph.CallGraphNode.add_call(callee)
     * @param callee 被调用的函数全限定名
     */
    public void addCall(String callee) {
        calls.add(callee);
    }

    /**
     * 添加被调用关系
     * @origin Python: ast_engine.call_graph.CallGraphNode.add_caller(caller)
     * @param caller 调用此函数的函数全限定名
     */
    public void addCaller(String caller) {
        calledBy.add(caller);
    }

    /**
     * 转换为Map（序列化用）
     * @origin Python: ast_engine.call_graph.CallGraphNode.to_dict() -> dict
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("qualified_name", qualifiedName);
        map.put("short_name", shortName);
        map.put("file", filePath);
        map.put("start_line", startLine);
        map.put("end_line", endLine);
        map.put("signature", signature);
        map.put("calls", new ArrayList<>(calls));
        map.put("called_by", new ArrayList<>(calledBy));
        map.put("call_count", calls.size());
        map.put("called_by_count", calledBy.size());
        map.putAll(metadata);
        return map;
    }

    /**
     * 从Map反序列化
     */
    @SuppressWarnings("unchecked")
    public static CallGraphNode fromMap(Map<String, Object> map) {
        String qn = (String) map.get("qualified_name");
        String file = (String) map.get("file");
        int start = ((Number) map.getOrDefault("start_line", 0)).intValue();
        int end = ((Number) map.getOrDefault("end_line", 0)).intValue();
        String sig = (String) map.getOrDefault("signature", "");

        Set<String> calls = new LinkedHashSet<>();
        Object callsObj = map.get("calls");
        if (callsObj instanceof List<?> list) {
            for (Object o : list) calls.add(o.toString());
        }

        Set<String> calledBy = new LinkedHashSet<>();
        Object calledByObj = map.get("called_by");
        if (calledByObj instanceof List<?> list) {
            for (Object o : list) calledBy.add(o.toString());
        }

        return new CallGraphNode(qn, file, start, end, sig, calls, calledBy);
    }

    /**
     * 判断是否为叶子节点（不调用任何函数）
     */
    public boolean isLeaf() {
        return calls.isEmpty();
    }

    /**
     * 判断是否为入口节点（不被任何函数调用）
     */
    public boolean isEntry() {
        return calledBy.isEmpty();
    }

    /**
     * 获取函数体行数
     */
    public int getLineCount() {
        return endLine - startLine + 1;
    }

    private static String extractShortName(String qualifiedName) {
        if (qualifiedName == null) return "";
        int dot = qualifiedName.lastIndexOf('.');
        return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
    }

    public String getQualifiedName() { return qualifiedName; }
    public String getShortName() { return shortName; }
    public String getFilePath() { return filePath; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getSignature() { return signature; }
    public Set<String> getCalls() { return Collections.unmodifiableSet(calls); }
    public Set<String> getCalledBy() { return Collections.unmodifiableSet(calledBy); }
    public Map<String, Object> getMetadata() { return metadata; }

    public void setMetadata(String key, Object value) { metadata.put(key, value); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CallGraphNode that)) return false;
        return qualifiedName.equals(that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return qualifiedName.hashCode();
    }

    @Override
    public String toString() {
        return qualifiedName + " (" + filePath + ":" + startLine + ")";
    }
}
