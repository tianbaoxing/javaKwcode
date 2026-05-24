package com.kwcode.core.orchestrator;

import com.kwcode.audit.AuditLogger;
import com.kwcode.core.EventBus;
import com.kwcode.core.ModelCapability;
import com.kwcode.core.ThinkConfig;
import com.kwcode.core.cognitive.CognitiveGate;
import com.kwcode.core.context.TaskContext;
import com.kwcode.core.env.EnvProber;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.core.gate.Gate;
import com.kwcode.core.wink.WinkMonitor;
import com.kwcode.experts.Generator;
import com.kwcode.experts.Locator;
import com.kwcode.experts.Reviewer;
import com.kwcode.experts.SearchAugmentor;
import com.kwcode.experts.Verifier;
import com.kwcode.memory.KaiwuMemory;
import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * 管道编排器 - 确定性专家流水线的核心控制器
 * <p>
 * 编排完整的任务执行流程：
 * Phase 0: EnvProber环境检测 → Phase 1: 前置测试+GapDetector → Gate路由 →
 * 专家流水线执行 → 重试循环 → Reviewer审查
 * </p>
 * <p>
 * 关键设计原则：
 * - RED-2: 确定性流水线，每个expert_type固定序列
 * - RED-3: 每次重试使用新鲜上下文
 * - RED-5: 最多3次重试（syntax免费重试≤2次不计入）
 * - 超时看门狗：单任务硬超时，防卡死
 * - ThinkConfig：推理模型按expert_type×difficulty配置think预算
 * </p>
 * @origin Python: core.orchestrator.PipelineOrchestrator
 */
public class PipelineOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    public static final int MAX_RETRIES = 5;
    public static final int HARD_MAX_RETRIES = 10;
    public static final int FREE_SYNTAX_RETRIES = 2;
    public static final int TASK_TIMEOUT_SECONDS = 300;

    private final ToolGateway tools;
    private final Locator locator;
    private final Generator generator;
    private final Verifier verifier;
    private final Gate gate;
    private final GapDetector gapDetector;
    private final CognitiveGate cognitiveGate;
    private final WinkMonitor winkMonitor;
    private final EventBus eventBus;
    private final EnvProber envProber;
    private final KaiwuMemory memory;
    private final AuditLogger auditLogger;
    private String defaultModelName = "";

    private String streakErrorType = "";
    private int streakCount = 0;
    private int syntaxFreeRetries = 0;

    public PipelineOrchestrator(ToolGateway tools, Locator locator, Generator generator,
                                 Verifier verifier, Gate gate, GapDetector gapDetector,
                                 CognitiveGate cognitiveGate, WinkMonitor winkMonitor,
                                 EventBus eventBus, EnvProber envProber, KaiwuMemory memory,
                                 AuditLogger auditLogger) {
        this.tools = tools;
        this.locator = locator;
        this.generator = generator;
        this.verifier = verifier;
        this.gate = gate;
        this.gapDetector = gapDetector;
        this.cognitiveGate = cognitiveGate;
        this.winkMonitor = winkMonitor;
        this.eventBus = eventBus;
        this.envProber = envProber;
        this.memory = memory;
        this.auditLogger = auditLogger;
    }

    /**
     * 执行完整的专家流水线
     * <p>
     * 返回结果包含 success/context/error/elapsed
     * </p>
     * @origin Python: PipelineOrchestrator.run(user_input, gate_result, project_root, ...)
     */
    public OrchestratorResult run(String userInput, Map<String, Object> gateResult,
                                   String projectRoot, BiConsumer<String, String> onStatus,
                                   boolean noSearch, boolean skipCheckpoint) {
        long startTime = System.currentTimeMillis();
        auditLogger.start();

        String expertType = (String) gateResult.getOrDefault("expert_type", "locator_repair");
        String difficulty = (String) gateResult.getOrDefault("difficulty", "medium");
        String systemPrompt = (String) gateResult.getOrDefault("system_prompt", "");
        log.info("[orchestrator] Gate结果 → expertType={}", expertType);
        log.info("[orchestrator] Gate结果 → difficulty={}", difficulty);
        log.info("[orchestrator] Gate结果 → systemPrompt={}", systemPrompt);
        
        String modelNameRaw = (String) gateResult.getOrDefault("model", "");
        String modelName = !modelNameRaw.isEmpty() ? modelNameRaw : defaultModelName;
        if (modelNameRaw.isEmpty() && !defaultModelName.isEmpty()) {
            log.info("[orchestrator] model from defaultModelName: {} (gateResult has no model)", modelName);
        }

        TaskContext ctx = new TaskContext();
        ctx.userInput = userInput;
        ctx.projectRoot = projectRoot;
        ctx.gateResult = gateResult;
        ctx.expertSystemPrompt = systemPrompt;
        ctx.retryCount = 0;
        ctx.retryStrategy = 0;

        // ── ThinkConfig配置 ──
        ThinkConfig thinkConfig = ThinkConfig.autoConfigure(modelName, expertType, difficulty);
        ctx.thinkConfig = thinkConfig.toMap();
        if (thinkConfig.shouldThink()) {
            emit(onStatus, "think_config", "Think模式: 开启, 预算=" + thinkConfig.getBudgetTokens() + "tokens");
        }

        // ── 模型能力配置 ──
        Map<String, Object> cap = ModelCapability.getCapability(modelName);
        ctx.modelTier = (String) cap.getOrDefault("tier", "medium");
        ctx.effectiveCtx = ((Number) cap.getOrDefault("effective_ctx", 32768)).intValue();

        // ── 记忆初始化与加载 ──
        if (memory != null) {
            // 自动初始化.kaiwu/目录和PROJECT.md（与Python行为一致）
            memory.init(projectRoot);
            ctx.kaiwuMemory = memory.load(projectRoot);
        }

        // ══════════════════════════════════════
        // chat/vision/office类型：早期返回
        // ══════════════════════════════════════
        if ("chat".equals(expertType)) {
            emit(onStatus, "chat", "聊天模式");
            return simpleResult(true, ctx, null, startTime);
        }
        if ("vision".equals(expertType)) {
            emit(onStatus, "vision", "图片处理模式");
            return simpleResult(false, ctx, "Vision专家未配置", startTime);
        }
        if ("office".equals(expertType)) {
            emit(onStatus, "office", "办公文档模式");
            return simpleResult(false, ctx, "Office专家未配置", startTime);
        }

        // ══════════════════════════════════════
        // Phase 0: 环境探针（确定性，不走LLM）
        // ══════════════════════════════════════
        emit(onStatus, "env_probe", "检测项目环境...");
        try {
            envProber.setUserInputHint(userInput);
            Map<String, Object> envResult = envProber.probeAndFix(projectRoot).toMap();
            emit(onStatus, "env_probe", "  语言: " + envResult.getOrDefault("lang", "unknown"));
            emit(onStatus, "env_probe", "  就绪: " + envResult.getOrDefault("ready", false));
            emit(onStatus, "env_probe", "  已安装: " + envResult.getOrDefault("installed", "[]"));
            emit(onStatus, "env_probe", "  测试命令: " + envResult.getOrDefault("test_cmd", "none"));
            if (envResult.containsKey("jdk_version")) {
                emit(onStatus, "env_probe", "  JDK版本: " + envResult.get("jdk_version"));
            }
            if (envResult.containsKey("jdk_home")) {
                emit(onStatus, "env_probe", "  JDK目录: " + envResult.get("jdk_home"));
            }
            if (envResult.containsKey("maven_version")) {
                emit(onStatus, "env_probe", "  Maven版本: " + envResult.get("maven_version"));
            }
            if (envResult.containsKey("test_cmd")) {
                ctx.confirmedTestCmd = (String) envResult.get("test_cmd");
            }
            ctx.projectLang = (String) envResult.getOrDefault("lang", "");
        } catch (Exception e) {
            log.debug("EnvProber failed (non-blocking): {}", e.getMessage());
        }

        // ══════════════════════════════════════
        // Phase 1: 前置测试 + GapDetector
        // ══════════════════════════════════════
        emit(onStatus, "pre_test", "运行初始测试获取基线...");
        try {
            String testOutput = runPreTest(ctx);
            if (testOutput != null && !testOutput.isEmpty()) {
                ctx.gap = gapDetector.compute(testOutput, projectRoot);

                // Gap驱动的源文件扫描
                if (ctx.gap != null && ctx.gap.gapType() != GapDetector.GapType.NONE
                    && ctx.gap.gapType() != GapDetector.GapType.NO_TEST) {
                    ctx.gap = gapDetector.scanSourceFiles(ctx.gap, projectRoot);
                }

                // Gap驱动的expert_type覆盖（置信度≥70%）
                if (ctx.gap != null && ctx.gap.gapType() != GapDetector.GapType.UNKNOWN
                    && ctx.gap.gapType() != GapDetector.GapType.NONE && ctx.gap.confidence() >= 0.7) {
                    String gapExpert = GapDetector.GAP_TO_EXPERT_TYPE.get(ctx.gap.gapType());
                    if (gapExpert != null && !gapExpert.equals(expertType)) {
                        emit(onStatus, "gap_override", "Gap路由覆盖：" + expertType + " → " + gapExpert);
                        expertType = gapExpert;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Pre-test failed (non-blocking): {}", e.getMessage());
        }

        // MISSING_TOOLCHAIN快速熔断
        if (ctx.gap != null && ctx.gap.gapType() == GapDetector.GapType.MISSING_TOOLCHAIN) {
            emit(onStatus, "circuit_break", "工具链缺失，请手动安装后重试");
            return simpleResult(false, ctx, "工具链缺失: " + ctx.gap.errorMsg(), startTime);
        }

        List<String> sequence = getSequence(gateResult, expertType);
        emit(onStatus, "gate", "任务类型：" + expertType + " | 序列：" + sequence);

        int maxRetries = "hard".equals(difficulty) ? HARD_MAX_RETRIES : MAX_RETRIES;

        ModelCapability.Tier tier = ModelCapability.detectTier(modelName);
        ModelCapability.ModelStrategy strategy = ModelCapability.getStrategy(tier);
        log.info("[orchestrator] model strategy: model={} tier={} strategyMaxRetries={} forcePlanMode={}", modelName, ModelCapability.tierDisplayName(tier), strategy.maxRetries, strategy.forcePlanMode);
        if (strategy.maxRetries < maxRetries) {
            log.info("[orchestrator] strategy override: maxRetries {} → {} (tier={})", maxRetries, strategy.maxRetries, ModelCapability.tierDisplayName(tier));
            maxRetries = strategy.maxRetries;
            emit(onStatus, "model_strategy", "模型" + ModelCapability.tierDisplayName(tier) + "，限制最大重试=" + maxRetries);
        }
        final int finalMaxRetries = maxRetries;

        cognitiveGate.reset();
        streakErrorType = "";
        streakCount = 0;
        syntaxFreeRetries = 0;

        // ══════════════════════════════════════
        // 超时看门狗
        // ══════════════════════════════════════
        final String finalExpertType = expertType;
        ExecutorService watchdog = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "orchestrator-watchdog");
            t.setDaemon(true);
            return t;
        });

        Future<OrchestratorResult> future = watchdog.submit(() ->
            runWithTimeout(ctx, sequence, finalMaxRetries, finalExpertType, difficulty, modelName,
                           onStatus, startTime, strategy, thinkConfig));

        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            emit(onStatus, "timeout", "任务超时(" + TASK_TIMEOUT_SECONDS + "s)，强制终止");
            long elapsed = System.currentTimeMillis() - startTime;
            return new OrchestratorResult(false, ctx, "任务超时(" + TASK_TIMEOUT_SECONDS + "s)", elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OrchestratorResult(false, ctx, "任务被中断", System.currentTimeMillis() - startTime);
        } catch (ExecutionException e) {
            return new OrchestratorResult(false, ctx, "执行异常: " + e.getCause().getMessage(),
                System.currentTimeMillis() - startTime);
        } finally {
            watchdog.shutdownNow();
        }
    }

    /**
     * 带超时保护的执行体
     */
    private OrchestratorResult runWithTimeout(TaskContext ctx, List<String> sequence,
                                              int maxRetries, String expertType,
                                              String difficulty, String modelName,
                                              BiConsumer<String, String> onStatus,
                                              long startTime,
                                              ModelCapability.ModelStrategy strategy,
                                              ThinkConfig thinkConfig) {
        // ══════════════════════════════════════
        // 重试循环 (RED-5: 最多maxRetries次)
        // ══════════════════════════════════════
        while (ctx.retryCount < maxRetries) {
            // 每次重试前检查超时
            if (Thread.currentThread().isInterrupted()) {
                return new OrchestratorResult(false, ctx, "任务被中断",
                    System.currentTimeMillis() - startTime);
            }

            boolean success = runSequence(sequence, ctx, onStatus, strategy, thinkConfig);

            if (success) {
                long elapsed = System.currentTimeMillis() - startTime;
                return recordSuccess(ctx, elapsed);
            }

            if (ctx.llmError != null && !ctx.llmError.isEmpty()) {
                log.warn("[orchestrator] LLM error detected, skipping retry: {}", ctx.llmError);
                emit(onStatus, "circuit_break", "LLM调用失败，停止重试: " + ctx.llmError);
                break;
            }

            ctx.retryCount++;

            // syntax错误免费重试（≤2次不计入）
            if (ctx.verifierOutput != null && "syntax".equals(ctx.verifierOutput.errorType())) {
                syntaxFreeRetries++;
                if (syntaxFreeRetries <= FREE_SYNTAX_RETRIES) {
                    ctx.retryCount--;
                }
            }

            // CognitiveGate: 边际收益递减检测
            if (ctx.generatorOutput != null && !ctx.generatorOutput.patches().isEmpty()) {
                cognitiveGate.record(ctx.generatorOutput.patches().size());
            }
            if (cognitiveGate.shouldStop().shouldStop()) {
                emit(onStatus, "circuit_break", "边际收益递减，停止重试");
                break;
            }

            // 错误类型追踪
            String currentErrorType = ctx.verifierOutput != null ? ctx.verifierOutput.errorType() : "unknown";
            if (currentErrorType.equals(streakErrorType)) {
                streakCount++;
            } else {
                streakErrorType = currentErrorType;
                streakCount = 1;
            }

            // ENVIRONMENT类型快速失败：LLM服务不可用时重试无意义
            if ("environment".equals(currentErrorType)) {
                emit(onStatus, "circuit_break", "LLM服务不可用(environment)，停止重试");
                log.warn("[orchestrator] ENVIRONMENT gap detected, fast-fail without retry");
                break;
            }

            // 硬熔断：同类错误连续3次
            if (streakCount >= 3) {
                emit(onStatus, "circuit_break", "同类错误(" + currentErrorType + ")连续" + streakCount + "次，停止重试");
                break;
            }

            // Wink自修复检测
            String winkHint = null;
            try {
                var winkCtx = new WinkMonitor.WinkContext();
                winkCtx.locatorOutput = ctx.locatorOutput != null ? () -> ctx.locatorOutput.relevantFiles() : null;
                winkCtx.generatorOutput = ctx.generatorOutput != null ? () -> ctx.generatorOutput.patches() : null;
                winkCtx.verifierOutput = ctx.verifierOutput != null ?
                    new WinkMonitor.VerifierOutput() {
                        public String getErrorType() { return ctx.verifierOutput.errorType(); }
                        public int getTestsPassed() { return ctx.verifierOutput.testsPassed(); }
                    } : null;
                winkCtx.gateResult = ctx.gateResult;
                winkCtx.retryCount = ctx.retryCount;
                winkCtx.errorTypeStreak = new WinkMonitor.ErrorTypeStreak(streakErrorType, streakCount);
                winkHint = winkMonitor.check(winkCtx, eventBus);
            } catch (Exception e) {
                log.debug("WinkMonitor check failed: {}", e.getMessage());
            }

            // 错误策略路由
            if ("contract_violation".equals(currentErrorType)) currentErrorType = "patch_apply";
            RetryStrategy retryStrat = RETRY_STRATEGIES_MAP.getOrDefault(currentErrorType,
                RETRY_STRATEGIES_MAP.get("unknown"));
            List<String> retrySequence = retryStrat.sequence();
            sequence = retrySequence;

            // 构建重试提示（将上次错误信息传递给Generator，使LLM在重试时能看到失败原因）
            ctx.previousFailure = ctx.verifierOutput != null ? ctx.verifierOutput.errorDetail() : "";
            ctx.retryHint = buildRetryHint(ctx, currentErrorType);
            if (winkHint != null && !winkHint.isEmpty()) {
                ctx.retryHint = (ctx.retryHint + "\n" + winkHint).strip();
            }
            ctx.retryStrategy = ctx.retryCount;

            emit(onStatus, "retry", "第" + ctx.retryCount + "次尝试失败：" + currentErrorType);

            // RED-3: 刷新上下文，保留定位器输出
            if (sequence.contains("locator")) {
                ctx.locatorOutput = null;
                ctx.relevantCodeSnippets = Map.of();
            }
            ctx.generatorOutput = null;
            ctx.verifierOutput = null;
        }

        long elapsed = System.currentTimeMillis() - startTime;
        return recordFailure(ctx, maxRetries, elapsed);
    }

    // ── 序列执行 ──

    /** 执行一个专家序列 */
    private boolean runSequence(List<String> sequence, TaskContext ctx, BiConsumer<String, String> onStatus,
                                ModelCapability.ModelStrategy strategy, ThinkConfig thinkConfig) {
        for (String step : sequence) {
            if (Thread.currentThread().isInterrupted()) return false;

            switch (step) {
                case "locator" -> {
                    emit(onStatus, "locator", "定位相关文件和函数...");
                    ctx.locatorOutput = locator.locate(ctx);
                }
                case "generator" -> {
                    emit(onStatus, "generator", "生成代码修改...");
                    ctx.generatorOutput = generator.generate(ctx);
                    if (ctx.generatorOutput.explanation() != null
                        && ctx.generatorOutput.explanation().startsWith("[LLM_ERROR]")) {
                        log.warn("[orchestrator] LLM call failed in generator: {}", ctx.generatorOutput.explanation());
                        ctx.llmError = ctx.generatorOutput.explanation();
                        return false;
                    }
                }
                case "verifier" -> {
                    emit(onStatus, "verifier", "验证修改结果...");
                    ctx.verifierOutput = verifier.verify(ctx);
                    if (ctx.verifierOutput.passed()) {
                        emit(onStatus, "passed", "验证通过！");
                        return true;
                    }
                }
                case "import_fixer" -> {
                    emit(onStatus, "import_fixer", "修复import...");
                }
                case "debug_subagent", "debugger" -> {
                    emit(onStatus, "debug", "调试错误...");
                }
                default -> log.warn("Unknown expert step: {}", step);
            }
        }
        return false;
    }

    /** 运行前置测试 */
    private String runPreTest(TaskContext ctx) {
        String testCmd = ctx.confirmedTestCmd;
        if (testCmd == null || testCmd.isEmpty()) return "";
        String prevExpert = tools.currentExpert();
        tools.setExpert("verifier");
        try {
            var result = tools.runBash(testCmd, ctx.projectRoot, 120);
            return result.stdout() + "\n" + result.stderr();
        } finally {
            tools.setExpert(prevExpert);
        }
    }

    /** 获取专家流水线序列 */
    private List<String> getSequence(Map<String, Object> gateResult, String expertType) {
        if ("expert_registry".equals(gateResult.get("route_type")) && gateResult.containsKey("pipeline")) {
            @SuppressWarnings("unchecked")
            List<String> pipeline = (List<String>) gateResult.get("pipeline");
            if (pipeline != null && !pipeline.isEmpty()) return pipeline;
        }
        return Gate.EXPERT_SEQUENCES.getOrDefault(expertType, List.of("generator", "verifier"));
    }

    private static final Map<String, RetryStrategy> RETRY_STRATEGIES_MAP = Map.of(
        "syntax", new RetryStrategy(List.of("generator", "verifier"),
            "只修 {error_file}:{error_line} 的语法错误，修改≤5行，不触碰其他函数", false),
        "assertion", new RetryStrategy(List.of("generator", "verifier"),
            "测试期望：{error_message}。只改1个函数使断言通过，修改≤10行", false),
        "import", new RetryStrategy(List.of("import_fixer", "verifier"),
            "", true),
        "patch_apply", new RetryStrategy(List.of("locator", "generator", "verifier"),
            "必须先read_file读取文件最新内容，禁止使用缓存的original", false),
        "runtime", new RetryStrategy(List.of("debug_subagent", "generator", "verifier"),
            "", false),
        "unknown", new RetryStrategy(List.of("generator", "verifier"),
            "只修改1个函数，修改≤15行，不触碰报错位置±20行外的代码", false)
    );

    private record RetryStrategy(List<String> sequence, String hintTemplate, boolean needsSearch) {}

    /** 构建重试提示（对齐Python的_build_retry_hint，支持模板变量） */
    private String buildRetryHint(TaskContext ctx, String errorType) {
        RetryStrategy strategy = RETRY_STRATEGIES_MAP.getOrDefault(errorType,
            RETRY_STRATEGIES_MAP.get("unknown"));
        log.info("[orchestrator] buildRetryHint: errorType={} strategy={} sequence={}", errorType, strategy.hintTemplate().substring(0, Math.min(40, strategy.hintTemplate().length())) + "...", strategy.sequence());

        String hint = strategy.hintTemplate();
        if (hint != null && !hint.isEmpty() && hint.contains("{")) {
            String errorFile = ctx.verifierOutput != null ? ctx.verifierOutput.errorFile() : "";
            int errorLine = ctx.verifierOutput != null ? ctx.verifierOutput.errorLine() : 0;
            String errorMessage = ctx.verifierOutput != null ? ctx.verifierOutput.errorMessage() : "";
            hint = hint.replace("{error_file}", errorFile)
                       .replace("{error_line}", String.valueOf(errorLine))
                       .replace("{error_message}", errorMessage);
            log.debug("[orchestrator] buildRetryHint: template vars replaced errorFile={} errorLine={}", errorFile, errorLine);
        }

        if (ctx.generatorOutput != null && !ctx.generatorOutput.patches().isEmpty()) {
            String lastCode = ctx.generatorOutput.patches().get(0).modified();
            if (lastCode != null && !lastCode.isEmpty()) {
                lastCode = lastCode.substring(0, Math.min(300, lastCode.length()));
                hint += "\n\n上次生成的代码（有问题）：\n" + lastCode + "\n\n请不要重复同样的错误。";
            }
        }

        return hint;
    }

    // ── 结果记录 ──

    public record OrchestratorResult(boolean success, TaskContext context, String error, long elapsedMs) {}

    private OrchestratorResult simpleResult(boolean success, TaskContext ctx, String error, long startTime) {
        return new OrchestratorResult(success, ctx, error, System.currentTimeMillis() - startTime);
    }

    private OrchestratorResult recordSuccess(TaskContext ctx, long elapsedMs) {
        log.info("[orchestrator] 任务成功，耗时{}ms，重试{}次", elapsedMs, ctx.retryCount);
        // 写入三层记忆（project_md, expert_md, pattern_md）
        if (memory != null) {
            double elapsedSec = elapsedMs / 1000.0;
            memory.save(ctx.projectRoot, ctx, elapsedSec);
        }
        return new OrchestratorResult(true, ctx, null, elapsedMs);
    }

    private OrchestratorResult recordFailure(TaskContext ctx, int maxRetries, long elapsedMs) {
        String error = "重试" + ctx.retryCount + "次后仍失败";
        log.warn("[orchestrator] 任务失败：{}", error);
        // 写入失败记忆（只有pattern_md追踪失败）
        if (memory != null) {
            double elapsedSec = elapsedMs / 1000.0;
            memory.saveFailure(ctx.projectRoot, ctx, elapsedSec);
        }
        return new OrchestratorResult(false, ctx, error, elapsedMs);
    }

    public void setDefaultModelName(String modelName) {
        this.defaultModelName = modelName != null ? modelName : "";
        log.debug("[orchestrator] setDefaultModelName: {}", this.defaultModelName);
    }

    public String getDefaultModelName() {
        return defaultModelName;
    }

    // ── 辅助 ──

    private void emit(BiConsumer<String, String> onStatus, String stage, String detail) {
        if (onStatus != null) onStatus.accept(stage, detail);
        eventBus.emit(stage, Map.of("detail", detail));
    }
}
