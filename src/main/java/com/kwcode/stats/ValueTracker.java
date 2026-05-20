package com.kwcode.stats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 值追踪仪表盘
 * <p>
 * P2-RED-3: 所有数据存储在本地SQLite，无网络请求。
 * P2-RED-4: 数字真实保守，从不夸大。
 * </p>
 * <p>
 * 保守时间估算：每个成功任务5分钟（P2-RED-4）
 * </p>
 * @origin Python: stats.value_tracker.ValueTracker
 */
public class ValueTracker {

    private static final Logger log = LoggerFactory.getLogger(ValueTracker.class);
    private static final Path DB_PATH = Path.of(System.getProperty("user.home"),
        ".kwcode", "stats.db");
    private static final int MINUTES_PER_TASK = 5;

    public ValueTracker() {
        initDb();
    }

    /**
     * 记录任务完成（P2-RED-3: 仅本地）
     * @origin Python: stats.value_tracker.ValueTracker.record(project_root, expert_type, expert_name, success, elapsed_s, retry_count, model)
     * @param projectRoot 项目根目录
     * @param expertType 专家类型
     * @param expertName 专家名称
     * @param success 是否成功
     * @param elapsedS 耗时秒数
     * @param retryCount 重试次数
     * @param model 模型名称
     */
    public void record(String projectRoot, String expertType, String expertName,
                       boolean success, double elapsedS, int retryCount, String model) {
        String sql = "INSERT INTO task_stats " +
            "(timestamp, project_root, expert_type, expert_name, success, elapsed_s, retry_count, model) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            ps.setString(2, projectRoot);
            ps.setString(3, expertType);
            ps.setString(4, expertName != null ? expertName : "");
            ps.setInt(5, success ? 1 : 0);
            ps.setDouble(6, elapsedS);
            ps.setInt(7, retryCount);
            ps.setString(8, model);
            ps.executeUpdate();
        } catch (Exception e) {
            log.debug("[value_tracker] record failed: {}", e.getMessage());
        }
    }

    /**
     * 获取统计摘要
     * @origin Python: stats.value_tracker.ValueTracker.get_summary(days) -> dict
     * @param days 统计天数
     * @return 摘要Map
     */
    public Map<String, Object> getSummary(int days) {
        String since = LocalDateTime.now().minusDays(days)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("days", days);
        summary.put("total_tasks", 0);
        summary.put("succeeded_tasks", 0);
        summary.put("time_saved_hours", 0.0);
        summary.put("top_expert_name", "");
        summary.put("top_expert_count", 0);
        summary.put("top_expert_rate", 0.0);
        summary.put("total_all_time", 0);

        try (Connection conn = getConnection()) {
            String countSql = "SELECT COUNT(*) as total, SUM(success) as succeeded " +
                "FROM task_stats WHERE timestamp > ?";
            try (PreparedStatement ps = conn.prepareStatement(countSql)) {
                ps.setString(1, since);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int total = rs.getInt("total");
                    int succeeded = rs.getInt("succeeded");
                    double timeSavedH = succeeded * MINUTES_PER_TASK / 60.0;

                    summary.put("total_tasks", total);
                    summary.put("succeeded_tasks", succeeded);
                    summary.put("time_saved_hours", Math.round(timeSavedH * 10.0) / 10.0);
                }
            }

            String topExpertSql = "SELECT expert_name, COUNT(*) as cnt, AVG(success) as rate " +
                "FROM task_stats WHERE timestamp > ? AND expert_name != '' AND expert_name IS NOT NULL " +
                "GROUP BY expert_name ORDER BY cnt DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(topExpertSql)) {
                ps.setString(1, since);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    summary.put("top_expert_name", rs.getString("expert_name"));
                    summary.put("top_expert_count", rs.getInt("cnt"));
                    summary.put("top_expert_rate", rs.getDouble("rate"));
                }
            }

            String totalAllSql = "SELECT COUNT(*) as c FROM task_stats";
            try (PreparedStatement ps = conn.prepareStatement(totalAllSql)) {
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    summary.put("total_all_time", rs.getInt("c"));
                }
            }
        } catch (Exception e) {
            log.debug("[value_tracker] get_summary failed: {}", e.getMessage());
        }

        return summary;
    }

    /**
     * 获取全部任务计数（用于里程碑检测）
     * @origin Python: stats.value_tracker.ValueTracker.get_total_task_count() -> int
     * @return 总任务数
     */
    public int getTotalTaskCount() {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) as c FROM task_stats")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt("c");
        } catch (Exception e) {
            log.debug("[value_tracker] get_total_task_count failed: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 获取Gate路由准确率报告：各expert_type的成功率
     * <p>
     * P2: 显示哪些Gate分类导致了成功的结果。
     * </p>
     * @origin Python: stats.value_tracker.ValueTracker.get_gate_accuracy(days) -> list[dict]
     * @param days 统计天数
     * @return 路由准确率列表
     */
    public List<Map<String, Object>> getGateAccuracy(int days) {
        String since = LocalDateTime.now().minusDays(days)
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<Map<String, Object>> results = new ArrayList<>();

        String sql = "SELECT expert_type, COUNT(*) as total, SUM(success) as succeeded, " +
            "AVG(elapsed_s) as avg_elapsed, AVG(retry_count) as avg_retries " +
            "FROM task_stats WHERE timestamp > ? AND expert_type != '' " +
            "GROUP BY expert_type ORDER BY total DESC";

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, since);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                int total = rs.getInt("total");
                int succeeded = rs.getInt("succeeded");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("expert_type", rs.getString("expert_type"));
                row.put("total", total);
                row.put("success_rate", total > 0 ? (double) succeeded / total : 0.0);
                row.put("avg_elapsed", Math.round(rs.getDouble("avg_elapsed") * 10.0) / 10.0);
                row.put("avg_retries", Math.round(rs.getDouble("avg_retries") * 10.0) / 10.0);
                results.add(row);
            }
        } catch (Exception e) {
            log.debug("[value_tracker] get_gate_accuracy failed: {}", e.getMessage());
        }

        return results;
    }

    private void initDb() {
        String ddl = "CREATE TABLE IF NOT EXISTS task_stats (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "timestamp TEXT NOT NULL, " +
            "project_root TEXT, " +
            "expert_type TEXT, " +
            "expert_name TEXT, " +
            "success INTEGER, " +
            "elapsed_s REAL, " +
            "retry_count INTEGER, " +
            "model TEXT" +
            ")";

        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (Exception e) {
            log.debug("[value_tracker] init_db failed: {}", e.getMessage());
        }
    }

    private Connection getConnection() throws SQLException {
        try {
            Files.createDirectories(DB_PATH.getParent());
        } catch (IOException e) {
            // ignore
        }
        return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
    }
}
