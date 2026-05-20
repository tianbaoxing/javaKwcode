package com.kwcode.core.context;

import com.kwcode.core.KWCodeMD;
import com.kwcode.core.ModelCapability;
import com.kwcode.core.ThinkConfig;
import com.kwcode.core.context.ContextPruner;
import com.kwcode.memory.KaiwuMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 上下文管理器 - 统一管理TaskContext的构建、注入和裁剪
 * <p>
 * ContextManager负责：
 * 1. 构建初始TaskContext（从用户输入+环境信息）
 * 2. 注入KWCODE.md规则
 * 3. 注入天工记忆
 * 4. 执行上下文裁剪（ContextPruner）
 * 5. 配置Think模式
 * </p>
 * <p>
 * 与TaskContext的关系：
 * - TaskContext: 纯数据结构，在流水线中流动
 * - ContextManager: 构建和管理TaskContext的服务
 * </p>
 * @origin Python: core.context.ContextManager
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final ContextPruner pruner;
    private final KWCodeMD kwcodeMd;
    private final KaiwuMemory memory;

    public ContextManager(ContextPruner pruner, KWCodeMD kwcodeMd, KaiwuMemory memory) {
        this.pruner = pruner;
        this.kwcodeMd = kwcodeMd;
        this.memory = memory;
    }

    public ContextManager(String projectRoot) {
        this.pruner = new ContextPruner();
        this.kwcodeMd = new KWCodeMD(projectRoot);
        this.memory = new KaiwuMemory();
    }

    public ContextManager() {
        this.pruner = new ContextPruner();
        this.kwcodeMd = new KWCodeMD();
        this.memory = new KaiwuMemory();
    }

    /**
     * 构建初始TaskContext
     * <p>
     * 从用户输入和环境信息构建完整的TaskContext。
     * </p>
     * @origin Python: core.context.ContextManager.build(user_input, project_root, gate_result) -> TaskContext
     * @param userInput 用户输入
     * @param projectRoot 项目根目录
     * @param gateResult Gate分类结果
     * @return 初始化的TaskContext
     */
    public TaskContext build(String userInput, String projectRoot, Map<String, Object> gateResult) {
        TaskContext ctx = new TaskContext();
        ctx.userInput = userInput;
        ctx.projectRoot = projectRoot != null ? projectRoot : ".";
        ctx.gateResult = gateResult != null ? gateResult : new HashMap<>();

        injectKwcodeRules(ctx);
        injectMemory(ctx);
        configureModelFields(ctx);
        configureThinkMode(ctx);

        log.debug("[context_manager] Built context for: {}",
            userInput.substring(0, Math.min(50, userInput.length())));
        return ctx;
    }

    /**
     * 增强上下文（在流水线中间调用）
     * <p>
     * 在Locator/Generator执行后，注入额外上下文信息。
     * </p>
     * @origin Python: core.context.ContextManager.enrich(ctx) -> TaskContext
     * @param ctx 任务上下文
     * @return 增强后的上下文
     */
    public TaskContext enrich(TaskContext ctx) {
        if (ctx.locatorOutput != null && ctx.relevantCodeSnippets.isEmpty()) {
            log.debug("[context_manager] Locator output exists but no snippets collected");
        }

        if (pruner != null) {
            pruner.prune(ctx);
        }

        return ctx;
    }

    /**
     * 注入KWCODE.md规则
     */
    private void injectKwcodeRules(TaskContext ctx) {
        if (kwcodeMd != null) {
            ctx.kwcodeRules = kwcodeMd.toPromptText();
        }
    }

    /**
     * 注入天工记忆
     */
    private void injectMemory(TaskContext ctx) {
        if (memory != null) {
            ctx.kaiwuMemory = memory.loadForLocator(ctx.projectRoot);
        }
    }

    /**
     * 配置模型相关字段
     */
    private void configureModelFields(TaskContext ctx) {
        String modelName = (String) ctx.gateResult.getOrDefault("model", "");
        if (modelName.isEmpty()) modelName = "default";

        Map<String, Object> cap = ModelCapability.getCapability(modelName);
        ctx.modelTier = (String) cap.getOrDefault("tier", "medium");
        ctx.effectiveCtx = ((Number) cap.getOrDefault("effective_ctx", 32768)).intValue();
    }

    /**
     * 配置Think模式
     */
    private void configureThinkMode(TaskContext ctx) {
        String modelName = (String) ctx.gateResult.getOrDefault("model", "");
        String difficulty = (String) ctx.gateResult.getOrDefault("difficulty", "easy");

        ThinkConfig thinkConfig = ThinkConfig.autoConfigure(modelName, difficulty);
        ctx.thinkConfig = thinkConfig.toMap();
    }

    public ContextPruner getPruner() { return pruner; }
    public KWCodeMD getKwcodeMd() { return kwcodeMd; }
}
