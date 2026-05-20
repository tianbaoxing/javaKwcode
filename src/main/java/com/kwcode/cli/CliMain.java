package com.kwcode.cli;

import com.kwcode.server.PipelineFactory;
import com.kwcode.core.orchestrator.PipelineOrchestrator;
import com.kwcode.core.gate.Gate;
import com.kwcode.memory.KaiwuMemory;
import com.kwcode.registry.ExpertRegistry;
import com.kwcode.llm.LLMService;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * kwcode CLI入口
 * kwcode → 交互式REPL
 * kwcode "修复bug" → 单次执行
 * kwcode init → 初始化KAIWU.md
 * kwcode serve → 启动HTTP Server
 *
 * 对应Python: kaiwu/cli/main.py::app
 *
 * @origin kaiwu/cli/main.py
 */
@Command(
        name = "kwcode",
        mixinStandardHelpOptions = true,
        version = "1.0.0-SNAPSHOT",
        description = "天工开物 - Java版本地Coding Agent",
        subcommands = {
                CliMain.InitCommand.class,
                CliMain.ServeCommand.class,
                CliMain.StatusCommand.class,
                CliMain.MemoryCommand.class
        }
)
public class CliMain implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "任务描述（不提供则进入交互模式）")
    private String task;

    @Option(names = {"-m", "--model"}, description = "模型名称 (默认: qwen3-8b)")
    private String model = "qwen3-8b";

    @Option(names = {"--ollama-url"}, description = "Ollama服务地址")
    private String ollamaUrl = "http://localhost:11434";

    @Option(names = {"--api-key"}, description = "API密钥")
    private String apiKey = "";

    @Option(names = {"-d", "--project"}, description = "项目根目录")
    private String projectDir = ".";

    @Option(names = {"-v", "--verbose"}, description = "显示详细日志")
    private boolean verbose = false;

    @Option(names = {"--no-search"}, description = "禁用搜索增强")
    private boolean noSearch = false;

    @Option(names = {"-p", "--plan"}, description = "先输出计划，确认后执行")
    private boolean plan = false;

    @Override
    public Integer call() throws Exception {
        if (task == null || task.isBlank()) {
            return runRepl();
        }
        return runTask(task);
    }

    /**
     * 运行单次任务
     * @origin kaiwu/cli/main.py::main (单次执行部分)
     */
    private int runTask(String taskDesc) {
        System.out.printf("🎯 KW-CODE | %s | %s%n", model, projectDir);

        PipelineFactory.PipelineConfig config = new PipelineFactory.PipelineConfig();
        config.setModel(model);
        config.setOllamaUrl(ollamaUrl);
        config.setApiKey(apiKey);
        config.setProjectRoot(projectDir);
        config.setVerbose(verbose);

        PipelineFactory.PipelineBundle bundle = PipelineFactory.buildPipeline(config);

        try {
            System.out.println("📋 分析任务...");
            Map<String, Object> gateResult = bundle.getGate().classify(taskDesc, "", null);
            String expertType = (String) gateResult.getOrDefault("expert_type", "chat");
            System.out.printf("🔀 路由到专家: %s%n", expertType);

            var oResult = bundle.getOrchestrator().run(
                    taskDesc, gateResult, projectDir,
                    (s, d) -> System.out.println("  [" + s + "] " + d),
                    noSearch, false);

            boolean success = oResult.success();
            if (success) {
                System.out.println("✅ 任务完成");
                return 0;
            } else {
                System.out.println("❌ 任务失败: " + oResult.error());
                return 1;
            }
        } catch (Exception e) {
            System.err.println("❌ 执行异常: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 交互式REPL模式
     * @origin kaiwu/cli/repl.py
     */
    private int runRepl() {
        
        System.out.println("🎯 KW-CODE 交互模式 (输入 /help 查看命令, /quit 退出)");
        System.out.printf("   模型: %s | 项目: %s%n", model, projectDir);

        PipelineFactory.PipelineConfig config = new PipelineFactory.PipelineConfig();
        config.setModel(model);
        config.setOllamaUrl(ollamaUrl);
        config.setApiKey(apiKey);
        config.setProjectRoot(projectDir);

        PipelineFactory.PipelineBundle bundle = PipelineFactory.buildPipeline(config);
        java.util.Scanner scanner = new java.util.Scanner(System.in);

        while (true) {
            System.out.print("\n> ");
            if (!scanner.hasNextLine()) break;
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;
            if (input.equals("/quit") || input.equals("/exit")) break;
            if (input.equals("/help")) {
                System.out.println("  /help  - 显示帮助");
                System.out.println("  /quit  - 退出");
                System.out.println("  /model - 显示当前模型");
                System.out.println("  /stats - 显示Token统计");
                continue;
            }
            if (input.equals("/model")) {
                System.out.println("  " + bundle.getLlmService().getModelName());
                continue;
            }
            if (input.equals("/stats")) {
                System.out.println("  " + bundle.getLlmService().getTokenUsage());
                continue;
            }

            try {
                Map<String, Object> gateResult = bundle.getGate().classify(input, "", null);
                var oResult = bundle.getOrchestrator().run(
                        input, gateResult, projectDir,
                        (s, d) -> System.out.println("  [" + s + "] " + d),
                        noSearch, false);
                boolean success = oResult.success();
                System.out.println(success ? "✅ 完成" : "❌ 失败");
            } catch (Exception e) {
                System.err.println("❌ 异常: " + e.getMessage());
            }
        }
        return 0;
    }

    // ── 子命令 ──

    /**
     * 初始化KAIWU.md
     * @origin kaiwu/cli/commands/config.py::cmd_init
     */
    @Command(name = "init", description = "初始化项目KAIWU.md")
    static class InitCommand implements Callable<Integer> {
        @Option(names = {"-d", "--project"}, defaultValue = ".", description = "项目根目录")
        String projectDir;

        @Override
        public Integer call() {
            KaiwuMemory memory = new KaiwuMemory();
            System.out.println(memory.init(projectDir));
            return 0;
        }
    }

    /**
     * 启动HTTP Server
     * @origin kaiwu/cli/commands/config.py::cmd_serve
     */
    @Command(name = "serve", description = "启动HTTP Server")
    static class ServeCommand implements Callable<Integer> {
        @Option(names = {"--port"}, defaultValue = "7355", description = "服务端口")
        int port;

        @Override
        public Integer call() {
            System.out.printf("🚀 启动kwcode HTTP Server (端口: %d)%n", port);
            System.out.println("   提示: 需设置 kwcode.server.enabled=true 激活REST端点");
            return 0;
        }
    }

    /**
     * 显示状态
     * @origin kaiwu/cli/commands/config.py::cmd_status
     */
    @Command(name = "status", description = "显示当前状态")
    static class StatusCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("  kwcode v1.0.0-SNAPSHOT");
            System.out.println("  Java版 Coding Agent");
            return 0;
        }
    }

    /**
     * 查看项目记忆
     * @origin kaiwu/cli/commands/config.py::cmd_memory
     */
    @Command(name = "memory", description = "查看项目记忆")
    static class MemoryCommand implements Callable<Integer> {
        @Option(names = {"-d", "--project"}, defaultValue = ".", description = "项目根目录")
        String projectDir;

        @Override
        public Integer call() {
            KaiwuMemory memory = new KaiwuMemory();
            System.out.println(memory.show(projectDir));
            return 0;
        }
    }

    public static void main(String[] args) {
        System.out.println(" java ai to code");
        System.out.println("author 田保兴");
        int exitCode = new CommandLine(new CliMain()).execute(args);
        System.exit(exitCode);
    }
}
