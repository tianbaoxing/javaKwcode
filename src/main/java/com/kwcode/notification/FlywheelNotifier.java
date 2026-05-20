package com.kwcode.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 飞轮可见性通知系统
 * <p>
 * P2-RED-2: 通知从不中断当前任务，排队后在下一个REPL循环显示。
 * 通知类型：
 * - expert_born: 专家毕业通知
 * - progress: 积累进度通知（3/5, 4/5）
 * - milestone: 里程碑通知（50/100/200/500任务）
 * </p>
 * @origin Python: notification.flywheel_notifier.FlywheelNotifier
 */
public class FlywheelNotifier {

    private static final Logger log = LoggerFactory.getLogger(FlywheelNotifier.class);
    private static final Path NOTIFY_PATH = Path.of(System.getProperty("user.home"),
        ".kwcode", "pending_notifications.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 飞轮通知数据
     * @origin Python: notification.flywheel_notifier.FlywheelNotification
     */
    public static class FlywheelNotification {
        public String type;
        public String expertName = "";
        public List<String> triggerKeywords = new ArrayList<>();
        public int taskCount = 0;
        public double successRateNew = 0.0;
        public double successRateBaseline = 0.0;
        public double avgLatencyNew = 0.0;
        public double avgLatencyBaseline = 0.0;
        public int progressCurrent = 0;
        public int progressTotal = 5;
        public int milestoneTasks = 0;
        public double speedup = 0.0;

        public FlywheelNotification() {}

        public FlywheelNotification(String type) {
            this.type = type;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("expert_name", expertName);
            map.put("trigger_keywords", triggerKeywords);
            map.put("task_count", taskCount);
            map.put("success_rate_new", successRateNew);
            map.put("success_rate_baseline", successRateBaseline);
            map.put("avg_latency_new", avgLatencyNew);
            map.put("avg_latency_baseline", avgLatencyBaseline);
            map.put("progress_current", progressCurrent);
            map.put("progress_total", progressTotal);
            map.put("milestone_tasks", milestoneTasks);
            map.put("speedup", speedup);
            return map;
        }
    }

    /**
     * 排队专家毕业通知（P2-RED-2: 不立即显示）
     * @origin Python: notification.flywheel_notifier.FlywheelNotifier.queue_expert_born(expert_def, metrics)
     * @param expertDef 专家定义
     * @param metrics 指标数据
     */
    public void queueExpertBorn(Map<String, Object> expertDef, Map<String, Object> metrics) {
        FlywheelNotification notif = new FlywheelNotification("expert_born");
        notif.expertName = getString(expertDef, "name", "");

        Object kwObj = expertDef.get("trigger_keywords");
        if (kwObj instanceof List<?> list) {
            for (int i = 0; i < Math.min(4, list.size()); i++) {
                notif.triggerKeywords.add(list.get(i).toString());
            }
        }

        notif.taskCount = getInt(metrics, "task_count", 0);
        notif.successRateNew = getDouble(metrics, "success_rate_new", 0);
        notif.successRateBaseline = getDouble(metrics, "success_rate_baseline", 0);
        notif.avgLatencyNew = getDouble(metrics, "avg_latency_new", 0);
        notif.avgLatencyBaseline = getDouble(metrics, "avg_latency_baseline", 0);

        save(notif);
    }

    /**
     * 排队积累进度通知
     * @origin Python: notification.flywheel_notifier.FlywheelNotifier.queue_progress(expert_type, current, total)
     * @param expertType 专家类型
     * @param current 当前进度
     * @param total 总计
     */
    public void queueProgress(String expertType, int current, int total) {
        FlywheelNotification notif = new FlywheelNotification("progress");
        notif.expertName = expertType;
        notif.progressCurrent = current;
        notif.progressTotal = total;
        save(notif);
    }

    /**
     * 排队里程碑通知
     * @origin Python: notification.flywheel_notifier.FlywheelNotifier.queue_milestone(total_tasks, expert_count, avg_speedup)
     * @param totalTasks 总任务数
     * @param expertCount 专家数量
     * @param avgSpeedup 平均加速比
     */
    public void queueMilestone(int totalTasks, int expertCount, double avgSpeedup) {
        FlywheelNotification notif = new FlywheelNotification("milestone");
        notif.milestoneTasks = totalTasks;
        notif.taskCount = expertCount;
        notif.speedup = avgSpeedup;
        save(notif);
    }

    /**
     * 显示所有待处理通知并清空队列
     * <p>
     * 在REPL循环开始时调用（P2-RED-2: 上一个任务完成后）。
     * </p>
     * @origin Python: notification.flywheel_notifier.FlywheelNotifier.flush(console) -> int
     * @param appendable 输出目标（如StringBuilder或PrintWriter）
     * @return 显示的通知数量
     */
    public int flush(Appendable appendable) {
        List<Map<String, Object>> notifications = load();
        if (notifications.isEmpty()) return 0;

        for (Map<String, Object> notifData : notifications) {
            FlywheelNotification notif = mapToNotification(notifData);
            display(notif, appendable);
        }

        try {
            Files.writeString(NOTIFY_PATH, "[]");
        } catch (IOException e) {
            log.debug("[notifier] clear failed: {}", e.getMessage());
        }

        return notifications.size();
    }

    /**
     * 获取待处理通知数量（不消费）
     */
    public int pendingCount() {
        return load().size();
    }

    private void display(FlywheelNotification n, Appendable out) {
        try {
            switch (n.type) {
                case "expert_born" -> displayExpertBorn(n, out);
                case "progress" -> displayProgress(n, out);
                case "milestone" -> displayMilestone(n, out);
            }
        } catch (IOException e) {
            log.debug("[notifier] display failed: {}", e.getMessage());
        }
    }

    private void displayExpertBorn(FlywheelNotification n, Appendable out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ KWCode 为你生成了一个新专家 ─────────────────────\n");
        sb.append("│ ").append(n.expertName).append("\n");

        String keywords = n.triggerKeywords.isEmpty()
            ? "N/A"
            : String.join("、", n.triggerKeywords);
        sb.append("│ 触发词：").append(keywords).append("\n");
        sb.append("│\n");
        sb.append("│ 基于你过去 ").append(n.taskCount).append(" 次成功任务\n");

        double rateDiff = n.successRateNew - n.successRateBaseline;
        if (rateDiff > 0) {
            sb.append(String.format("│ 成功率：%.0f%%（↑%.0f%% vs 通用流水线）\n",
                n.successRateNew * 100, rateDiff * 100));
        }

        if (n.avgLatencyBaseline > 0 && n.avgLatencyNew > 0) {
            double ratio = n.avgLatencyBaseline / n.avgLatencyNew;
            sb.append(String.format("│ 速度：平均 %.0fs（快了 %.1fx）\n",
                n.avgLatencyNew, ratio));
        }

        sb.append("│\n");
        sb.append("│ 输入 /experts 查看全部专家 · kwcode expert export ")
          .append(n.expertName).append(" 导出分享\n");
        sb.append("└──────────────────────────────────────────────────\n");

        out.append(sb.toString());
    }

    private void displayProgress(FlywheelNotification n, Appendable out) throws IOException {
        int remaining = n.progressTotal - n.progressCurrent;
        out.append(String.format("  [飞轮] %s · 已积累 %d/%d 次成功 · 再 %d 次可生成专属专家%n",
            n.expertName, n.progressCurrent, n.progressTotal, remaining));
    }

    private void displayMilestone(FlywheelNotification n, Appendable out) throws IOException {
        out.append(String.format("%n  ★ 里程碑  已完成 %d 个任务 · 积累了 %d 个专属专家 · 同类任务平均快了 %.1fx%n",
            n.milestoneTasks, n.taskCount, n.speedup));
    }

    private void save(FlywheelNotification notif) {
        List<Map<String, Object>> existing = load();
        existing.add(notif.toMap());
        try {
            Files.createDirectories(NOTIFY_PATH.getParent());
            MAPPER.writeValue(NOTIFY_PATH.toFile(), existing);
        } catch (IOException e) {
            log.debug("[notifier] save failed: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> load() {
        if (!Files.exists(NOTIFY_PATH)) return new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = MAPPER.readValue(
                NOTIFY_PATH.toFile(), List.class);
            return data != null ? data : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    private FlywheelNotification mapToNotification(Map<String, Object> map) {
        FlywheelNotification n = new FlywheelNotification((String) map.getOrDefault("type", ""));
        n.expertName = (String) map.getOrDefault("expert_name", "");
        n.taskCount = ((Number) map.getOrDefault("task_count", 0)).intValue();
        n.successRateNew = ((Number) map.getOrDefault("success_rate_new", 0)).doubleValue();
        n.successRateBaseline = ((Number) map.getOrDefault("success_rate_baseline", 0)).doubleValue();
        n.avgLatencyNew = ((Number) map.getOrDefault("avg_latency_new", 0)).doubleValue();
        n.avgLatencyBaseline = ((Number) map.getOrDefault("avg_latency_baseline", 0)).doubleValue();
        n.progressCurrent = ((Number) map.getOrDefault("progress_current", 0)).intValue();
        n.progressTotal = ((Number) map.getOrDefault("progress_total", 5)).intValue();
        n.milestoneTasks = ((Number) map.getOrDefault("milestone_tasks", 0)).intValue();
        n.speedup = ((Number) map.getOrDefault("speedup", 0.0)).doubleValue();

        Object kwObj = map.get("trigger_keywords");
        if (kwObj instanceof List<?> list) {
            for (Object o : list) n.triggerKeywords.add(o.toString());
        }

        return n;
    }

    private static String getString(Map<String, Object> map, String key, String def) {
        Object v = map.get(key);
        return v != null ? v.toString() : def;
    }

    private static int getInt(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private static double getDouble(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }
}
