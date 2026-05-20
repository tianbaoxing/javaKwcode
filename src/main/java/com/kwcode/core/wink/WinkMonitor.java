package com.kwcode.core.wink;

import com.kwcode.core.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Wink自修复监控 - 轨迹监控+偏离检测+课程纠正
 * <p>
 * 轻量异步观察agent执行，检测四类问题行为：
 * - Specification Drift：偏离用户原始意图（scope creep）
 * - Reasoning Problems：同类错误反复（原地打转）
 * - Tool Call Failures：patch持续失败
 * - Progress Stall：重试未提升通过率（免疫机制）
 * </p>
 * <p>
 * 理论来源：Wink(arXiv:2602.17037)、CodeScout(arXiv:2603.05744)
 * </p>
 * @origin Python: core.wink.WinkMonitor
 */
public class WinkMonitor {

    private static final Logger log = LoggerFactory.getLogger(WinkMonitor.class);

    /**
     * 偏离模式定义
     * @origin Python: core.wink.WinkMonitor.DRIFT_PATTERNS
     */
    private final List<DriftPattern> driftPatterns;

    public WinkMonitor() {
        this.driftPatterns = List.of(
            // Specification Drift：任务范围过大
            new DriftPattern("scope_creep",
                ctx -> ctx.locatorOutput != null
                    && ctx.locatorOutput.getRelevantFiles().size() > 5
                    && "easy".equals(ctx.gateResult.get("difficulty")),
                "任务范围过大，只修改用户明确指定的文件，不要扩散到其他文件"
            ),
            // Reasoning Problems：同类错误反复
            new DriftPattern("repetitive_fix",
                ctx -> ctx.errorTypeStreak.count >= 2,
                "你已经用同样的方式修改了 {count} 次，换一个完全不同的思路"
            ),
            // Tool Call Failures：patch持续失败
            new DriftPattern("patch_miss",
                ctx -> ctx.verifierOutput != null
                    && "patch_apply".equals(ctx.verifierOutput.getErrorType())
                    && ctx.retryCount >= 1,
                "patch 未命中，文件内容可能已变化，请重新读取文件再生成 patch"
            ),
            // Generator输出为空
            new DriftPattern("empty_output",
                ctx -> ctx.generatorOutput != null
                    && (ctx.generatorOutput.getPatches() == null || ctx.generatorOutput.getPatches().isEmpty())
                    && ctx.retryCount >= 1,
                "Generator 未产出有效 patch，尝试简化任务描述或缩小修改范围"
            ),
            // 免疫机制：重试未提升通过率
            new DriftPattern("tests_no_progress",
                ctx -> ctx.retryCount >= 2
                    && ctx.verifierOutput != null
                    && ctx.verifierOutput.getTestsPassed() <= ctx.prevTestsPassed,
                "连续重试未提升通过率，尝试完全不同的实现方式，不要在同一个方向上继续"
            )
        );
    }

    /**
     * 检查当前context是否有偏离，返回纠正hint或null
     * <p>
     * 非阻塞，任何异常静默忽略。
     * </p>
     * @origin Python: core.wink.WinkMonitor.check(ctx, bus) -> Optional[str]
     * @param ctx 当前任务上下文
     * @param bus 事件总线，可为null
     * @return 纠正hint字符串，无偏离返回null
     */
    public String check(WinkContext ctx, EventBus bus) {
        // 记录本次tests_passed供下次比较
        if (ctx.verifierOutput != null) {
            ctx.prevTestsPassed = ctx.verifierOutput.getTestsPassed();
        }

        for (DriftPattern pattern : driftPatterns) {
            try {
                if (pattern.detect().test(ctx)) {
                    String hint = pattern.hint;
                    if (hint.contains("{count}")) {
                        hint = hint.replace("{count}", String.valueOf(ctx.errorTypeStreak.count));
                    }

                    if (bus != null) {
                        Map<String, Object> payload = new HashMap<>();
                        payload.put("pattern", pattern.name);
                        payload.put("msg", "检测到 " + pattern.name + "，注入纠正");
                        bus.emit("wink_intervene", payload);
                    }

                    log.info("[wink] detected {}, injecting hint", pattern.name);
                    return hint;
                }
            } catch (Exception e) {
                continue;
            }
        }
        return null;
    }

    /**
     * 偏离模式
     */
    private record DriftPattern(String name, java.util.function.Predicate<WinkContext> detect, String hint) {}

    /**
     * 错误类型连续计数
     */
    public record ErrorTypeStreak(String type, int count) {
        public ErrorTypeStreak withIncrement() {
            return new ErrorTypeStreak(type, count + 1);
        }
    }

    /**
     * Wink检查用的简化上下文
     */
    public static class WinkContext {
        public LocatorOutput locatorOutput;
        public GeneratorOutput generatorOutput;
        public VerifierOutput verifierOutput;
        public Map<String, Object> gateResult = Map.of();
        public int retryCount;
        public ErrorTypeStreak errorTypeStreak = new ErrorTypeStreak("", 0);
        public int prevTestsPassed;
    }

    /** Locator输出简化接口 */
    public interface LocatorOutput {
        List<String> getRelevantFiles();
    }

    /** Generator输出简化接口 */
    public interface GeneratorOutput {
        List<?> getPatches();
    }

    /** Verifier输出简化接口 */
    public interface VerifierOutput {
        String getErrorType();
        int getTestsPassed();
    }
}
