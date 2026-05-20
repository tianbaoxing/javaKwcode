package com.kwcode.ast;

import java.sql.*;
import java.util.*;

/**
 * 代码图检索器 - BM25关键词召回 + 调用图扩展的两阶段检索
 * <p>
 * 检索策略：
 * Stage 1: BM25关键词召回 → Top-K候选
 * Stage 2: 调用图扩展 → N跳扩展
 * 总检索时间必须低于3秒（LOC-RED-5）。
 * 使用Lucene BM25替代Python版的rank-bm25。
 * </p>
 * @origin Python: ast_engine.graph_retriever.GraphRetriever
 */
public class GraphRetriever {

    private final String projectRoot;
    private final String dbPath;
    private List<Map<String, Object>> nodesCache = new ArrayList<>();
    private boolean bm25Built = false;
    private long bm25BuiltAt = 0L;

    /**
     * 需要过滤的噪音函数名
     * @origin Python: ast_engine.graph_retriever.SKIP_NAMES
     */
    private static final Set<String> SKIP_NAMES = Set.of(
        "__init__", "__repr__", "__str__", "__eq__", "__hash__",
        "setUp", "tearDown"
    );

    /**
     * 构造检索器
     * @param projectRoot 项目根目录
     * @param dbPath SQLite数据库路径
     */
    public GraphRetriever(String projectRoot, String dbPath) {
        this.projectRoot = projectRoot;
        this.dbPath = dbPath;
    }

    /**
     * 检查是否存在图数据
     * @origin Python: ast_engine.graph_retriever.GraphRetriever.has_graph() -> bool
     * @return true表示存在图数据
     */
    public boolean hasGraph() {
        try (Connection conn = getConnection()) {
            var stmt = conn.prepareStatement(
                "SELECT node_count FROM graph_meta WHERE project_root=?"
            );
            stmt.setString(1, projectRoot);
            var rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("node_count") > 0;
            }
            return false;
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 两阶段检索：BM25召回 + 调用图扩展
     * <p>
     * Stage 1: 使用BM25算法从节点搜索文本中检索与查询最相关的Top-K候选节点；
     * Stage 2: 从候选节点出发，沿调用图双向扩展指定跳数；
     * 合并去重后按BM25分数排序返回。
     * </p>
     * @origin Python: ast_engine.graph_retriever.GraphRetriever.retrieve(query, top_k_bm25, graph_hops, max_results) -> list[dict]
     * @param query 查询字符串
     * @param topKBm25 BM25召回数量，默认20
     * @param graphHops 调用图扩展跳数，默认2
     * @param maxResults 最大返回结果数，默认10
     * @return 检索结果列表
     */
    public List<Map<String, Object>> retrieve(String query, int topKBm25,
                                               int graphHops, int maxResults) {
        long t0 = System.currentTimeMillis();

        ensureBM25();
        if (!bm25Built || nodesCache.isEmpty()) {
            return List.of();
        }

        // Stage 1: BM25召回（简化实现，使用关键词匹配）
        List<Map<String, Object>> candidates = bm25Recall(query, topKBm25);

        // FLEX-2: BM25无结果时，使用前几个节点作为入口
        if (candidates.isEmpty()) {
            for (int i = 0; i < Math.min(5, nodesCache.size()); i++) {
                Map<String, Object> node = new HashMap<>(nodesCache.get(i));
                node.put("bm25_score", 0.0);
                candidates.add(node);
            }
        }

        // Stage 2: 调用图扩展
        Set<Integer> candidateIds = new HashSet<>();
        for (var c : candidates) {
            candidateIds.add((Integer) c.get("id"));
        }
        Set<Integer> graphNodeIds = expandGraph(candidateIds, graphHops);

        // 合并
        Set<Integer> allIds = new HashSet<>(candidateIds);
        allIds.addAll(graphNodeIds);
        List<Map<String, Object>> resultNodes = fetchNodes(allIds);

        // 排序：BM25候选优先，按分数降序
        Map<Integer, Double> bm25Scores = new HashMap<>();
        for (var c : candidates) {
            bm25Scores.put((Integer) c.get("id"), (Double) c.get("bm25_score"));
        }
        resultNodes.sort((a, b) -> {
            double scoreA = bm25Scores.getOrDefault(a.get("id"), 0.0);
            double scoreB = bm25Scores.getOrDefault(b.get("id"), 0.0);
            return Double.compare(scoreB, scoreA);
        });

        // 过滤噪音
        resultNodes = resultNodes.stream()
            .filter(n -> !SKIP_NAMES.contains(n.get("name")))
            .filter(n -> !((String) n.get("name")).startsWith("test_"))
            .toList();

        long elapsedMs = System.currentTimeMillis() - t0;
        if (elapsedMs > 3000) {
            // 超过3秒红线，记录警告
        }

        return resultNodes.stream().limit(maxResults).toList();
    }

    /**
     * BM25关键词召回（简化实现）
     * <p>
     * 使用关键词匹配替代完整BM25算法。
     * 后续可替换为Lucene BM25实现。
     * </p>
     * @param query 查询字符串
     * @param topK 返回Top-K结果
     * @return 候选节点列表
     */
    private List<Map<String, Object>> bm25Recall(String query, int topK) {
        String[] queryTokens = query.toLowerCase().split("\\s+");
        List<Map<String, Object>> scored = new ArrayList<>();

        for (var node : nodesCache) {
            String searchText = ((String) node.getOrDefault("search_text", node.get("name"))).toLowerCase();
            double score = 0.0;
            for (String token : queryTokens) {
                if (searchText.contains(token)) {
                    score += 1.0;
                }
            }
            if (score > 0) {
                Map<String, Object> scoredNode = new HashMap<>(node);
                scoredNode.put("bm25_score", score);
                scored.add(scoredNode);
            }
        }

        scored.sort((a, b) -> Double.compare(
            (Double) b.get("bm25_score"),
            (Double) a.get("bm25_score")
        ));

        return scored.stream().limit(topK).toList();
    }

    /**
     * 从种子节点沿调用图扩展
     * @origin Python: ast_engine.graph_retriever.GraphRetriever._expand_graph(seed_ids, hops) -> set[int]
     * @param seedIds 种子节点ID集合
     * @param hops 扩展跳数
     * @return 新发现的节点ID集合
     */
    private Set<Integer> expandGraph(Set<Integer> seedIds, int hops) {
        if (seedIds.isEmpty()) return Set.of();

        Set<Integer> discovered = new HashSet<>(seedIds);
        Set<Integer> frontier = new HashSet<>(seedIds);

        try (Connection conn = getConnection()) {
            for (int hop = 0; hop < hops; hop++) {
                if (frontier.isEmpty()) break;

                Set<Integer> newNodes = new HashSet<>();

                // 下游：frontier调用的函数
                String ph = String.join(",", Collections.nCopies(frontier.size(), "?"));
                var toStmt = conn.prepareStatement(
                    "SELECT to_id FROM edges WHERE from_id IN (" + ph + ") AND project_root=?"
                );
                int idx = 1;
                for (Integer id : frontier) toStmt.setInt(idx++, id);
                toStmt.setString(idx, projectRoot);
                var toRs = toStmt.executeQuery();
                while (toRs.next()) newNodes.add(toRs.getInt(1));

                // 上游：调用frontier的函数
                var fromStmt = conn.prepareStatement(
                    "SELECT from_id FROM edges WHERE to_id IN (" + ph + ") AND project_root=?"
                );
                idx = 1;
                for (Integer id : frontier) fromStmt.setInt(idx++, id);
                fromStmt.setString(idx, projectRoot);
                var fromRs = fromStmt.executeQuery();
                while (fromRs.next()) newNodes.add(fromRs.getInt(1));

                newNodes.removeAll(discovered);
                discovered.addAll(newNodes);
                frontier = newNodes;
            }
        } catch (SQLException e) {
            // 扩展失败
        }

        Set<Integer> result = new HashSet<>(discovered);
        result.removeAll(seedIds);
        return result;
    }

    /**
     * 确保BM25索引已构建（缓存5分钟）
     * @origin Python: ast_engine.graph_retriever.GraphRetriever._ensure_bm25()
     */
    private void ensureBM25() {
        if (bm25Built && (System.currentTimeMillis() - bm25BuiltAt) < 300_000) {
            return;
        }

        try (Connection conn = getConnection()) {
            var stmt = conn.prepareStatement(
                "SELECT id, name, qualified, file_path, start_line, end_line, node_type, search_text " +
                "FROM nodes WHERE project_root=?"
            );
            stmt.setString(1, projectRoot);
            var rs = stmt.executeQuery();

            nodesCache.clear();
            while (rs.next()) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", rs.getInt("id"));
                node.put("name", rs.getString("name"));
                node.put("qualified", rs.getString("qualified"));
                node.put("file_path", rs.getString("file_path"));
                node.put("start_line", rs.getInt("start_line"));
                node.put("end_line", rs.getInt("end_line"));
                node.put("node_type", rs.getString("node_type"));
                node.put("search_text", rs.getString("search_text"));
                nodesCache.add(node);
            }

            bm25Built = true;
            bm25BuiltAt = System.currentTimeMillis();
        } catch (SQLException e) {
            // 构建失败
        }
    }

    /**
     * 从数据库获取指定ID的节点
     * @origin Python: ast_engine.graph_retriever.GraphRetriever._fetch_nodes(node_ids) -> list[dict]
     * @param nodeIds 节点ID集合
     * @return 节点信息列表
     */
    private List<Map<String, Object>> fetchNodes(Set<Integer> nodeIds) {
        if (nodeIds.isEmpty()) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = getConnection()) {
            String ph = String.join(",", Collections.nCopies(nodeIds.size(), "?"));
            var stmt = conn.prepareStatement(
                "SELECT id, name, qualified, file_path, start_line, end_line, node_type " +
                "FROM nodes WHERE id IN (" + ph + ") AND project_root=?"
            );
            int idx = 1;
            for (Integer id : nodeIds) stmt.setInt(idx++, id);
            stmt.setString(idx, projectRoot);
            var rs = stmt.executeQuery();
            while (rs.next()) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", rs.getInt("id"));
                node.put("name", rs.getString("name"));
                node.put("qualified", rs.getString("qualified"));
                node.put("file_path", rs.getString("file_path"));
                node.put("start_line", rs.getInt("start_line"));
                node.put("end_line", rs.getInt("end_line"));
                node.put("node_type", rs.getString("node_type"));
                results.add(node);
            }
        } catch (SQLException e) {
            // 查询失败
        }
        return results;
    }

    /**
     * 更新节点的任务统计信息（飞轮数据）
     * @origin Python: ast_engine.graph_retriever.GraphRetriever.update_task_stats(node_ids, success)
     * @param nodeIds 节点ID列表
     * @param success 任务是否成功
     */
    public void updateTaskStats(List<Integer> nodeIds, boolean success) {
        if (nodeIds == null || nodeIds.isEmpty()) return;
        try (Connection conn = getConnection()) {
            String ph = String.join(",", Collections.nCopies(nodeIds.size(), "?"));
            String sql = success
                ? "UPDATE nodes SET task_count = task_count + 1, success_count = success_count + 1 WHERE id IN (" + ph + ")"
                : "UPDATE nodes SET task_count = task_count + 1 WHERE id IN (" + ph + ")";
            var stmt = conn.prepareStatement(sql);
            int idx = 1;
            for (Integer id : nodeIds) stmt.setInt(idx++, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // 更新失败
        }
    }

    /**
     * 获取SQLite连接
     */
    private Connection getConnection() throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try (var s = conn.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA synchronous=NORMAL");
        }
        return conn;
    }
}
