package com.kwcode.core.context;

import com.kwcode.core.gap.GapDetector;

import java.util.*;

/**
 * 任务上下文 - 流水线中传递的共享数据结构
 * <p>
 * 不可变式上下文，在流水线中流动。每个专家只读/写自己负责的字段。
 * RED-3: 独立上下文，每个专家的输出字段只由该专家写入。
 * </p>
 * @origin Python: core.context.TaskContext
 */
public class TaskContext {

    // ── 输入（流水线启动时设置一次） ──
    /** 用户原始输入 */
    public String userInput = "";
    /** 项目根目录 */
    public String projectRoot = ".";
    /** Gate分类结果 */
    public Map<String, Object> gateResult = new HashMap<>();
    /** 天工记忆注入 */
    public String kaiwuMemory = "";

    // ── Locator输出（RED-3: 只有Locator写这里） ──
    /** Locator输出：相关文件、函数、编辑位置 */
    public LocatorResult locatorOutput = null;

    // ── Generator输出（RED-3: 只有Generator写这里） ──
    /** Generator输出：补丁列表和解释 */
    public GeneratorResult generatorOutput = null;

    // ── Verifier输出（RED-3: 只有Verifier写这里） ──
    /** Verifier输出：是否通过、测试结果、错误详情 */
    public VerifierResult verifierOutput = null;

    // ── 专家系统提示词（通过注册表路由时注入） ──
    public String expertSystemPrompt = "";

    // ── 重试/搜索状态 ──
    /** 重试次数 */
    public int retryCount = 0;
    /** 重试策略：0=正常/1=从错误出发/2=最小化修改 */
    public int retryStrategy = 0;
    /** 上次失败的error_detail */
    public String previousFailure = "";
    /** LLM对失败原因的一句话分析 */
    public String reflection = "";
    /** 是否触发了搜索增强 */
    public boolean searchTriggered = false;
    /** 搜索结果文本 */
    public String searchResults = "";

    // ── 收集的文件内容（Locator填充，Generator使用） ──
    /** 相关代码片段：文件路径→代码内容 */
    public Map<String, String> relevantCodeSnippets = new HashMap<>();

    // ── 文档上下文（DocReader通过Locator填充） ──
    public String docContext = "";

    // ── Debug子代理输出 ──
    public String debugInfo = "";

    // ── KWCODE.md注入规则 ──
    public String kwcodeRules = "";

    // ── 图片上下文 ──
    public List<String> imagePaths = new ArrayList<>();
    public String imagePath = "";

    // ── 多任务编排 ──
    /** 子任务执行结果 */
    public Map<String, SubTaskResult> subtaskResults = new HashMap<>();
    /** 当前子任务ID */
    public String currentTaskId = "";
    /** 上游依赖结果 */
    public Map<String, Object> upstreamSummary = new HashMap<>();

    // ── 经验回放 ──
    /** 历史相似成功轨迹 */
    public List<Object> similarTrajectories = new ArrayList<>();

    // ── 约束和提示 ──
    /** SearchSubagent跨文件契约 */
    public String upstreamConstraints = "";
    /** 按错误类型生成的重试指导 */
    public String retryHint = "";

    // ── 模型配置 ──
    /** think模式配置 */
    public Map<String, Object> thinkConfig = new HashMap<>(Map.of("think", false, "budget", 0));
    /** 模型能力等级 */
    public String modelTier = "";
    /** 实际可用上下文大小 */
    public int effectiveCtx = 32768;

    // ── MoE架构新增字段 ──
    /** Gap信息（GapDetector计算，驱动所有决策） */
    public GapDetector.Gap gap = null;
    /** 确认可用的测试命令 */
    public String confirmedTestCmd = "";
    /** 项目主语言（envProbe检测） */
    public String projectLang = "";
    /** 路由来源：gap_detector/file_signal/keyword/llm_fallback */
    public String routingSource = "";

    // ── 轨迹记录 ──
    /** 历史教训：每次retry的结构化记录 */
    public List<AttemptRecord> attemptHistory = new ArrayList<>();

    // ── 不退步保护（Regression Guard） ──
    /** 历史最高通过测试数 */
    public int bestTestsPassed = 0;
    /** 最优代码快照 */
    public Map<String, String> bestCodeSnapshot = new HashMap<>();

    // ── 错误类型连续计数 ──
    public ErrorTypeStreak errorTypeStreak = new ErrorTypeStreak("", 0);

    // ── LLM调用错误 ──
    public String llmError = "";

    /**
     * Locator输出结果
     */
    public record LocatorResult(
        List<String> relevantFiles,
        List<String> relevantFunctions,
        List<String> editLocations
    ) {}

    /**
     * Generator输出结果
     */
    public record GeneratorResult(
        List<Patch> patches,
        String explanation
    ) {}

    /**
     * Verifier输出结果
     */
    public record VerifierResult(
        boolean passed,
        boolean syntaxOk,
        int testsPassed,
        int testsTotal,
        String errorType,
        String errorDetail,
        String errorFile,
        int errorLine
    ) {
        public String errorMessage() { return errorDetail != null ? errorDetail : ""; }

        public VerifierResult(boolean passed, boolean syntaxOk, int testsPassed,
                              int testsTotal, String errorType, String errorDetail) {
            this(passed, syntaxOk, testsPassed, testsTotal, errorType, errorDetail, "", 0);
        }
    }

    /**
     * 补丁记录
     */
    public record Patch(String file, String original, String modified) {}

    /**
     * 子任务执行结果
     */
    public record SubTaskResult(
        boolean success,
        List<String> filesModified,
        String explanation,
        List<Patch> patches,
        String searchData
    ) {}

    /**
     * 每轮尝试的结构化记录
     */
    public record AttemptRecord(
        int attempt,
        List<String> passedTests,
        List<String> failedTests,
        String errorType,
        String patchSummary,
        String lesson
    ) {}

    /**
     * 错误类型连续计数（用于熔断）
     */
    public static class ErrorTypeStreak {
        public String type = "";
        public int count = 0;

        public ErrorTypeStreak() {}

        public ErrorTypeStreak(String type, int count) {
            this.type = type;
            this.count = count;
        }

        public void record(String errorType) {
            if (errorType.equals(type)) {
                count++;
            } else {
                type = errorType;
                count = 1;
            }
        }

        public void reset() {
            type = "";
            count = 0;
        }
    }
}
