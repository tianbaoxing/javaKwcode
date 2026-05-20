package com.kwcode.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.llm.LLMService;
import com.kwcode.core.gate.Gate;
import com.kwcode.core.orchestrator.PipelineOrchestrator;
import com.kwcode.registry.ExpertRegistry;
import com.kwcode.memory.KaiwuMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * kwcode HTTP Server：REST + SSE事件流
 * 对应Python: kaiwu/server/app.py::create_app
 *
 * @origin kaiwu/server/app.py::create_app
 */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "kwcode.server.enabled", havingValue = "true", matchIfMissing = false)
public class ServerController {

    private static final Logger log = LoggerFactory.getLogger(ServerController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private PipelineFactory.PipelineBundle pipeline;

    private final Instant startTime = Instant.now();
    /** 任务队列：taskId → 事件队列 */
    private final Map<String, SseEmitter> taskEmitters = new ConcurrentHashMap<>();
    /** 任务结果：taskId → 结果 */
    private final Map<String, Map<String, Object>> taskResults = new ConcurrentHashMap<>();

    // ── 健康检查 ──

    /**
     * 健康检查端点
     * @origin kaiwu/server/app.py::health
     */
    @GetMapping("/health")
    public ResponseEntity<Models.HealthResponse> health() {
        Models.HealthResponse resp = new Models.HealthResponse();
        resp.setModel(pipeline.getLlmService().getModelName());
        return ResponseEntity.ok(resp);
    }

    // ── 服务状态 ──

    /**
     * 服务状态端点
     * @origin kaiwu/server/app.py::status
     */
    @GetMapping("/status")
    public ResponseEntity<Models.StatusResponse> status() {
        Models.StatusResponse resp = new Models.StatusResponse();
        resp.setModel(pipeline.getLlmService().getModelName());
        resp.setExpertsLoaded(pipeline.getRegistry().listExperts("").size());
        resp.setUptimeSeconds(java.time.Duration.between(startTime, Instant.now()).getSeconds());
        return ResponseEntity.ok(resp);
    }

    // ── 提交任务 ──

    /**
     * 提交任务，返回taskId用于SSE流
     * @origin kaiwu/server/app.py::submit_task
     */
    @PostMapping("/task")
    public ResponseEntity<Models.TaskResponse> submitTask(@RequestBody Models.TaskRequest request) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        log.info("收到任务 [{}]: {}", taskId, request.getInput());

        // 创建SSE emitter
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时
        taskEmitters.put(taskId, emitter);

        // 异步执行任务
        CompletableFuture.runAsync(() -> executeTask(taskId, request, emitter));

        return ResponseEntity.ok(new Models.TaskResponse(taskId));
    }

    // ── SSE事件流 ──

    /**
     * 任务SSE事件流
     * @origin kaiwu/server/app.py::task_events
     */
    @GetMapping(value = "/task/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter taskEvents(@PathVariable String taskId) {
        SseEmitter emitter = taskEmitters.get(taskId);
        if (emitter == null) {
            emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Task not found: " + taskId));
        }
        return emitter;
    }

    // ── 任务结果 ──

    /**
     * 获取任务最终结果
     * @origin kaiwu/server/app.py::task_result
     */
    @GetMapping("/task/{taskId}/result")
    public ResponseEntity<Map<String, Object>> taskResult(@PathVariable String taskId) {
        Map<String, Object> result = taskResults.get(taskId);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    // ── 文件浏览 ──

    /**
     * 列出项目文件树
     * @origin kaiwu/server/app.py::list_files
     */
    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> listFiles(
            @RequestParam(defaultValue = ".") String path,
            @RequestParam(defaultValue = "3") int maxDepth) {
        // 简化实现
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("root", path);
        result.put("items", Collections.emptyList());
        return ResponseEntity.ok(result);
    }

    /**
     * 读取文件内容
     * @origin kaiwu/server/app.py::read_file
     */
    @GetMapping("/file")
    public ResponseEntity<Models.FileContent> readFile(@RequestParam String path) {
        try {
            Path fullPath = Path.of(System.getProperty("user.dir")).resolve(path);
            if (!Files.isRegularFile(fullPath)) {
                return ResponseEntity.notFound().build();
            }
            String content = Files.readString(fullPath);
            Models.FileContent fc = new Models.FileContent(path, content);
            return ResponseEntity.ok(fc);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    // ── 内部方法 ──

    /**
     * 异步执行任务
     */
    private void executeTask(String taskId, Models.TaskRequest request, SseEmitter emitter) {
        try {
            sendEvent(emitter, "gate_start", "分析任务...");

            // Gate分类
            Map<String, Object> gateResult = pipeline.getGate().classify(request.getInput(), "", null);
            String expertType = (String) gateResult.getOrDefault("expert_type", "chat");
            sendEvent(emitter, "gate_done", expertType);

            // 运行Orchestrator
            var oResult = pipeline.getOrchestrator().run(
                    request.getInput(), gateResult, request.getProjectRoot(),
                    (s, d) -> sendEvent(emitter, s, d),
                    request.isNoSearch(), false
            );

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", oResult.success());
            if (oResult.error() != null) result.put("error", oResult.error());
            result.put("elapsed_ms", oResult.elapsedMs());

            taskResults.put(taskId, result);
            sendEvent(emitter, "task_completed", "完成");
            emitter.complete();

        } catch (Exception e) {
            log.error("任务[{}]执行失败: {}", taskId, e.getMessage());
            sendEvent(emitter, "task_error", e.getMessage());
            taskResults.put(taskId, Map.of("success", false, "error", e.getMessage()));
            emitter.completeWithError(e);
        } finally {
            taskEmitters.remove(taskId);
        }
    }

    /** 发送SSE事件 */
    private void sendEvent(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(Map.of("event", event, "msg", data),
                            MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE发送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }
}
