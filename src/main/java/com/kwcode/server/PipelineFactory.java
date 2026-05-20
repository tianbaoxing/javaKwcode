package com.kwcode.server;

import com.kwcode.core.cognitive.CognitiveGate;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.core.checkpoint.CheckpointManager;
import com.kwcode.core.execution.ExecutionTracker;
import com.kwcode.core.context.TaskContext;
import com.kwcode.core.wink.WinkMonitor;
import com.kwcode.core.env.EnvProber;
import com.kwcode.core.orchestrator.PipelineOrchestrator;
import com.kwcode.core.gate.Gate;
import com.kwcode.core.EventBus;
import com.kwcode.tools.ToolExecutor;
import com.kwcode.experts.*;
import com.kwcode.llm.LLMService;
import com.kwcode.llm.ModelRouter;
import com.kwcode.memory.KaiwuMemory;
import com.kwcode.registry.ExpertRegistry;
import com.kwcode.flywheel.TrajectoryCollector;
import com.kwcode.flywheel.ABTester;
import com.kwcode.flywheel.StrategyStats;
import com.kwcode.search.SearchRouter;
import com.kwcode.tools.ToolGateway;
import com.kwcode.audit.AuditLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.ChatClient;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.ollama.api.OllamaApi;

import java.util.*;

/**
 * 流水线工厂：构建完整的kwcode处理管线
 * CLI和Server共享此工厂创建pipeline实例
 * 对应Python: kaiwu/server/pipeline_factory.py::build_pipeline
 *
 * @origin kaiwu/server/pipeline_factory.py::build_pipeline
 */
public class PipelineFactory {

    private static final Logger log = LoggerFactory.getLogger(PipelineFactory.class);

    /**
     * 构建完整的kwcode pipeline
     * @return PipelineBundle 包含gate, orchestrator, memory, registry
     * @origin kaiwu/server/pipeline_factory.py::build_pipeline
     */
    public static PipelineBundle buildPipeline(PipelineConfig config) {

        log.info("构建kwcode pipeline: model={}, project={}", config.getModel(), config.getProjectRoot());

        // 1. 模型路由
        ModelRouter modelRouter = new ModelRouter();
        if (config.getModelRouter() != null) {
            Map<String, Map<String, String>> routerMap = new HashMap<>();
            config.getModelRouter().forEach((expertType, model) -> {
                Map<String, String> providerModel = new HashMap<>();
                providerModel.put("openrouter", model);
                routerMap.put(expertType, providerModel);
            });
            modelRouter.setModelRouter(routerMap);
        }

        // 2. LLM服务（手动创建ChatClient，支持CLI非Spring环境）
        ChatClient openRouterClient = null;
        ChatClient ollamaClient = null;

        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            String baseUrl = config.getOllamaUrl();
            if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("openrouter.ai")) {
                baseUrl = "https://openrouter.ai/api";
            }
            if (baseUrl.endsWith("/v1")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 3);
                log.info("[pipeline] 修正baseUrl: 移除末尾/v1（OpenAiApi会自动追加/v1/chat/completions）");
            }
            log.info("[pipeline] 创建OpenRouter ChatClient: baseUrl={}, model={}", baseUrl, config.getModel());
            OpenAiApi api = new OpenAiApi(baseUrl, config.getApiKey());
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .withModel(config.getModel())
                    .withTemperature(0.1f)
                    .build();
            openRouterClient = new OpenAiChatClient(api, options);
        }

        if (config.getOllamaUrl() != null && !config.getOllamaUrl().isBlank()
            && !config.getOllamaUrl().contains("openrouter.ai")) {
            log.info("[pipeline] 创建Ollama ChatClient: url={}", config.getOllamaUrl());
            OllamaApi ollamaApi = new OllamaApi(config.getOllamaUrl());
            ollamaClient = new OllamaChatClient(ollamaApi);
        }

        LLMService llmService = new LLMService(openRouterClient, ollamaClient, modelRouter);

        // 3. 工具网关
        ToolExecutor toolExecutor = new ToolExecutor(config.getProjectRoot());
        ToolGateway tools = new ToolGateway(toolExecutor);

        // 4. 记忆系统
        KaiwuMemory memory = new KaiwuMemory();

        // 5. 专家注册表
        ExpertRegistry registry = new ExpertRegistry();

        // 6. Core组件
        CognitiveGate cognitiveGate = new CognitiveGate();
        GapDetector gapDetector = new GapDetector();
        WinkMonitor winkMonitor = new WinkMonitor();
        EnvProber envProber = new EnvProber();
        EventBus eventBus = new EventBus();
        AuditLogger auditLogger = new AuditLogger();

        // 7. Gate
        Gate gate = new Gate(llmService, registry);

        // 8. 各专家
        Locator locator = new Locator(tools, memory, null);
        Generator generator = new Generator(llmService);
        Verifier verifier = new Verifier(tools, gapDetector);
        SearchAugmentor search = new SearchAugmentor(llmService);
        Reviewer reviewer = new Reviewer(llmService);

        // 9. Flywheel组件
        TrajectoryCollector trajectoryCollector = new TrajectoryCollector();
        StrategyStats strategyStats = new StrategyStats();
        ABTester abTester = new ABTester(registry, trajectoryCollector);

        // 10. Orchestrator
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(
                tools, locator, generator, verifier, gate, gapDetector,
                cognitiveGate, winkMonitor, eventBus, envProber, memory, auditLogger
        );
        orchestrator.setDefaultModelName(config.getModel());

        log.info("kwcode pipeline构建完成");

        return new PipelineBundle(gate, orchestrator, memory, registry, llmService);
    }

    /**
     * Pipeline构建结果
     */
    public static class PipelineBundle {
        private final Gate gate;
        private final PipelineOrchestrator orchestrator;
        private final KaiwuMemory memory;
        private final ExpertRegistry registry;
        private final LLMService llmService;

        public PipelineBundle(Gate gate, PipelineOrchestrator orchestrator,
                              KaiwuMemory memory, ExpertRegistry registry, LLMService llmService) {
            this.gate = gate;
            this.orchestrator = orchestrator;
            this.memory = memory;
            this.registry = registry;
            this.llmService = llmService;
        }

        public Gate getGate() { return gate; }
        public PipelineOrchestrator getOrchestrator() { return orchestrator; }
        public KaiwuMemory getMemory() { return memory; }
        public ExpertRegistry getRegistry() { return registry; }
        public LLMService getLlmService() { return llmService; }
    }

    /**
     * Pipeline配置
     */
    public static class PipelineConfig {
        private String ollamaUrl = "http://localhost:11434";
        private String model = "qwen3-8b";
        private String apiKey = "";
        private String projectRoot = ".";
        private boolean verbose = false;
        private Map<String, String> modelRouter;

        public String getOllamaUrl() { return ollamaUrl; }
        public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getProjectRoot() { return projectRoot; }
        public void setProjectRoot(String projectRoot) { this.projectRoot = projectRoot; }
        public boolean isVerbose() { return verbose; }
        public void setVerbose(boolean verbose) { this.verbose = verbose; }
        public Map<String, String> getModelRouter() { return modelRouter; }
        public void setModelRouter(Map<String, String> modelRouter) { this.modelRouter = modelRouter; }
    }
}
