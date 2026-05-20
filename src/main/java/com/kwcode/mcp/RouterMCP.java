package com.kwcode.mcp;

import com.kwcode.core.context.TaskContext;
import com.kwcode.core.gate.TaskGate;
import com.kwcode.core.orchestrator.PipelineOrchestrator;
import com.kwcode.core.orchestrator.PipelineOrchestrator.OrchestratorResult;
import com.kwcode.memory.KaiwuMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * MCP路由器 - 将KwCode流水线包装为MCP工具
 * <p>
 * CORE-7: 这是唯一的外部入口点。LLM不直接看到专家。
 * 单一工具: kwcode_execute(task_description) -> str
 * </p>
 * <p>
 * MCP包是可选的。此模块始终可导入，但启动服务器需要MCP运行时。
 * </p>
 * @origin Python: mcp.router_mcp.KaiwuMCP
 */
public class RouterMCP {

    private static final Logger log = LoggerFactory.getLogger(RouterMCP.class);

    private final TaskGate gate;
    private final PipelineOrchestrator orchestrator;
    private final KaiwuMemory memory;
    private final String projectRoot;
    private final ExecutorService executor;

    public RouterMCP(TaskGate gate, PipelineOrchestrator orchestrator,
                     KaiwuMemory memory, String projectRoot) {
        this.gate = gate;
        this.orchestrator = orchestrator;
        this.memory = memory;
        this.projectRoot = projectRoot;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-exec");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 执行kwcode_execute工具
     * <p>
     * 运行完整流水线：Gate分类 -> Orchestrator执行 -> 返回摘要
     * </p>
     * @origin Python: mcp.router_mcp.KaiwuMCP._execute(task) -> str
     * @param taskDescription 自然语言任务描述
     * @return 执行结果摘要文本
     */
    public String execute(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            return "Error: task_description is required.";
        }

        String task = taskDescription.strip();

        try {
            TaskContext ctx = new TaskContext();
            ctx.projectRoot = projectRoot;
            ctx.kaiwuMemory = memory.load(projectRoot);

            ctx = gate.process(task, ctx, null);

            OrchestratorResult result = orchestrator.run(
                task, ctx.gateResult, projectRoot, null, false, false);

            return formatResult(result);
        } catch (Exception e) {
            log.error("[mcp] kwcode_execute failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 异步执行kwcode_execute
     * @origin Python: mcp.router_mcp.KaiwuMCP._execute (async)
     * @param taskDescription 任务描述
     * @return CompletableFuture包装的结果
     */
    public CompletableFuture<String> executeAsync(String taskDescription) {
        return CompletableFuture.supplyAsync(() -> execute(taskDescription), executor);
    }

    /**
     * 获取工具定义列表
     * @origin Python: mcp.router_mcp.KaiwuMCP._setup_tools -> list_tools
     * @return 工具定义Map列表
     */
    public List<Map<String, Object>> listTools() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", "kwcode_execute");
        tool.put("description",
            "Execute a coding task through KwCode's local-model expert pipeline. " +
            "KwCode automatically selects the right expert, locates relevant files, " +
            "generates patches, and verifies the result.");

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> taskProp = new LinkedHashMap<>();
        taskProp.put("type", "string");
        taskProp.put("description", "Natural language description of the coding task.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_description", taskProp);
        schema.put("properties", properties);
        schema.put("required", List.of("task_description"));

        tool.put("inputSchema", schema);

        return List.of(tool);
    }

    /**
     * 调用工具
     * @origin Python: mcp.router_mcp.KaiwuMCP._setup_tools -> call_tool
     * @param name 工具名
     * @param arguments 参数Map
     * @return 结果文本
     */
    public String callTool(String name, Map<String, Object> arguments) {
        if (!"kwcode_execute".equals(name)) {
            return "Unknown tool: " + name;
        }

        if (arguments == null) {
            return "Error: arguments must be a JSON object.";
        }

        Object taskRaw = arguments.get("task_description");
        String task = taskRaw != null ? taskRaw.toString().strip() : "";
        if (task.isEmpty()) {
            return "Error: task_description is required.";
        }

        return execute(task);
    }

    private String formatResult(OrchestratorResult result) {
        if (result.success()) {
            TaskContext ctx = result.context();
            List<String> files = new ArrayList<>();

            if (ctx != null && ctx.locatorOutput != null) {
                files.addAll(ctx.locatorOutput.relevantFiles());
            }

            if (ctx != null && ctx.generatorOutput != null && files.isEmpty()) {
                for (var patch : ctx.generatorOutput.patches()) {
                    files.add(patch.file());
                }
            }

            String explanation = "";
            if (ctx != null && ctx.generatorOutput != null) {
                explanation = ctx.generatorOutput.explanation();
                if (explanation != null && explanation.length() > 500) {
                    explanation = explanation.substring(0, 500);
                }
            }

            double elapsed = result.elapsedMs() / 1000.0;

            List<String> parts = new ArrayList<>();
            parts.add(String.format("Done (%.1fs)", elapsed));
            if (!files.isEmpty()) {
                parts.add("Files: " + String.join(", ", files.subList(0, Math.min(10, files.size()))));
            }
            if (explanation != null && !explanation.isEmpty()) {
                parts.add(explanation);
            }
            return String.join("\n", parts);
        } else {
            return "Failed: " + (result.error() != null ? result.error() : "Unknown error");
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}
