package com.kwcode.core.gate;

import com.kwcode.core.ModelCapability;
import com.kwcode.core.ThinkConfig;
import com.kwcode.core.context.ContextPruner;
import com.kwcode.core.context.TaskContext;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.llm.LLMService;
import com.kwcode.registry.ExpertRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 任务门控 - Gate的高层封装，整合环境探测+分类+上下文初始化
 * <p>
 * TaskGate是PipelineOrchestrator调用的入口，
 * 封装了Gate.classify + 上下文初始化 + ThinkConfig配置。
 * </p>
 * <p>
 * 职责：
 * 1. 调用Gate进行确定性优先路由
 * 2. 根据模型能力配置ThinkConfig
 * 3. 初始化TaskContext的模型相关字段
 * 4. 执行上下文裁剪
 * </p>
 * @origin Python: core.gate.TaskGate
 */
public class TaskGate {

    private static final Logger log = LoggerFactory.getLogger(TaskGate.class);

    private final Gate gate;
    private final ContextPruner pruner;

    public TaskGate(Gate gate, ContextPruner pruner) {
        this.gate = gate;
        this.pruner = pruner;
    }

    public TaskGate(LLMService llmService, ExpertRegistry registry) {
        this.gate = new Gate(llmService, registry);
        this.pruner = new ContextPruner();
    }

    public TaskGate() {
        this.gate = new Gate();
        this.pruner = new ContextPruner();
    }

    /**
     * 执行任务门控
     * <p>
     * 完整流程：分类 → 模型配置 → 上下文初始化 → 裁剪
     * </p>
     * @origin Python: core.gate.TaskGate.process(user_input, ctx, gap) -> TaskContext
     * @param userInput 用户输入
     * @param ctx 任务上下文（部分初始化）
     * @param gap Gap信息（可选）
     * @return 完整初始化的TaskContext
     */
    public TaskContext process(String userInput, TaskContext ctx, GapDetector.Gap gap) {
        ctx.userInput = userInput;

        Map<String, Object> gateResult = gate.classify(userInput, ctx.kaiwuMemory, gap);
        ctx.gateResult = gateResult;

        configureModel(ctx);

        configureThink(ctx);

        if (pruner != null) {
            pruner.prune(ctx);
        }

        log.info("[task_gate] Routed to {} (source={}, confidence={})",
            gateResult.get("expert_type"),
            gateResult.get("routing_source"),
            gateResult.get("confidence"));

        return ctx;
    }

    /**
     * 配置模型能力
     */
    private void configureModel(TaskContext ctx) {
        String modelName = (String) ctx.gateResult.getOrDefault("model", "");
        if (modelName.isEmpty()) modelName = "default";

        Map<String, Object> cap = ModelCapability.getCapability(modelName);
        ctx.modelTier = ((String) cap.getOrDefault("tier", "medium"));
        ctx.effectiveCtx = ((Number) cap.getOrDefault("effective_ctx", 32768)).intValue();
        log.info("[task_gate] configureModel: model={} tier={} effectiveCtx={}", modelName, ctx.modelTier, ctx.effectiveCtx);
    }

    /**
     * 配置Think模式
     */
    private void configureThink(TaskContext ctx) {
        String modelName = (String) ctx.gateResult.getOrDefault("model", "");
        String expertType = (String) ctx.gateResult.getOrDefault("expert_type", "locator_repair");
        String difficulty = (String) ctx.gateResult.getOrDefault("difficulty", "easy");

        ThinkConfig thinkConfig = ThinkConfig.autoConfigure(modelName, expertType, difficulty);
        ctx.thinkConfig = thinkConfig.toMap();
        log.info("[task_gate] configureThink: model={} expertType={} difficulty={} → think={} budget={} mode={}",
                modelName, expertType, difficulty, thinkConfig.isEnabled(), thinkConfig.getBudgetTokens(), thinkConfig.getMode());
    }

    public Gate getGate() { return gate; }
}
