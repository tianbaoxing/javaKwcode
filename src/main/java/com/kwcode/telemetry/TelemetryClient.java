package com.kwcode.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * 匿名遥测客户端
 * <p>
 * 默认关闭，用户opt-in后只上传行为元数据：
 * - error_type（枚举值）
 * - retry_count（数字）
 * - success（bool）
 * - model（模型名称）
 * 绝不上传代码内容、文件路径、任务描述、用户身份信息。
 * </p>
 * <p>
 * Fire-and-forget模式：daemon线程 + 3s超时，绝不阻塞主流程。
 * HMAC-SHA256签名防伪造。
 * </p>
 * @origin Python: telemetry.client.TelemetryClient
 */
public class TelemetryClient {

    private static final Logger log = LoggerFactory.getLogger(TelemetryClient.class);
    private static final String TELEMETRY_URL = "https://llmbbs.com/api/v1/event";
    private static final Path CONFIG_PATH = Path.of(System.getProperty("user.home"),
        ".kwcode", "config.yaml");
    private static final String VERSION = "1.6.2";
    private static final byte[] HMAC_SECRET = "kwcode-telemetry-2026-v1".getBytes(StandardCharsets.UTF_8);

    private volatile boolean enabled;
    private final ExecutorService executor;
    private final HttpClient httpClient;

    public TelemetryClient() {
        this.enabled = readConfig();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "telemetry");
            t.setDaemon(true);
            return t;
        });
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    }

    /**
     * 是否启用遥测
     * @origin Python: telemetry.client.TelemetryClient.is_enabled() -> bool
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 重新读取配置（用于enable/disable后刷新）
     * @origin Python: telemetry.client.TelemetryClient.reload()
     */
    public void reload() {
        this.enabled = readConfig();
    }

    /**
     * Fire-and-forget上传匿名统计
     * <p>
     * daemon线程 + 3s超时，绝不阻塞主流程。
     * HMAC-SHA256签名防伪造。
     * </p>
     * @origin Python: telemetry.client.TelemetryClient.report(error_type, retry_count, success, model)
     * @param errorType 错误类型枚举值
     * @param retryCount 重试次数
     * @param success 是否成功
     * @param model 模型名称
     */
    public void report(String errorType, int retryCount, boolean success, String model) {
        if (!enabled) return;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("error_type", errorType != null ? errorType : "unknown");
        payload.put("retry_count", retryCount);
        payload.put("success", success);
        payload.put("model", model != null ? model : "unknown");
        payload.put("version", VERSION);

        executor.submit(() -> upload(payload));
    }

    private void upload(Map<String, Object> payload) {
        try {
            String body = toJson(payload);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String signature = hmacSha256(bodyBytes);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TELEMETRY_URL))
                .header("Content-Type", "application/json")
                .header("X-KWCode-Sig", signature)
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build();

            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("telemetry upload failed (non-blocking): {}", e.getMessage());
        }
    }

    private boolean readConfig() {
        if (!Files.exists(CONFIG_PATH)) return false;

        try {
            String content = Files.readString(CONFIG_PATH);
            int idx = content.indexOf("telemetry_enabled");
            if (idx < 0) return false;

            String after = content.substring(idx + "telemetry_enabled".length()).strip();
            if (after.startsWith(":")) after = after.substring(1).strip();
            if (after.startsWith("=")) after = after.substring(1).strip();

            return after.startsWith("true") || after.startsWith("yes") || after.startsWith("1");
        } catch (IOException e) {
            log.debug("telemetry config read failed: {}", e.getMessage());
            return false;
        }
    }

    private static String hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HMAC_SECRET, "HmacSHA256"));
            byte[] hash = mac.doFinal(data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        for (String key : keys) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(key).append("\":");
            Object val = map.get(key);
            if (val instanceof String s) {
                sb.append("\"").append(escapeJson(s)).append("\"");
            } else if (val instanceof Boolean b) {
                sb.append(b);
            } else if (val instanceof Number n) {
                sb.append(n);
            } else {
                sb.append("\"").append(val).append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
