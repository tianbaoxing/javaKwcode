package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.core.upstream.UpstreamManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 一致性检查专家 - 验证跨文件修改的一致性
 * <p>
 * 使用UpstreamManifest检查：
 * 1. 函数签名调用是否匹配
 * 2. 常量重定义是否冲突
 * 3. import是否缺失
 * </p>
 * <p>
 * 纯确定性检查，零LLM调用。在Verifier通过后、Reviewer之前执行。
 * </p>
 * @origin Python: experts.consistency_checker.ConsistencyChecker
 */
public class ConsistencyChecker implements Expert {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyChecker.class);

    private final UpstreamManifest manifest;

    public ConsistencyChecker(UpstreamManifest manifest) {
        this.manifest = manifest;
    }

    public ConsistencyChecker() {
        this.manifest = new UpstreamManifest();
    }

    @Override
    public String name() {
        return "consistency_checker";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return check(ctx);
    }

    /**
     * 检查代码修改的跨文件一致性
     * <p>
     * 步骤：
     * 1. 更新manifest（从patches提取签名/常量）
     * 2. 对每个修改文件检查一致性
     * 3. 返回违规列表
     * </p>
     * @origin Python: experts.consistency_checker.ConsistencyChecker.check(ctx) -> dict
     * @param ctx 任务上下文
     * @return 检查结果
     */
    public ExpertResult check(TaskContext ctx) {
        try {
            if (ctx.generatorOutput == null || ctx.generatorOutput.patches().isEmpty()) {
                return ExpertResult.ok("No patches to check", Map.of("violations", List.of()));
            }

            manifest.update(ctx.generatorOutput.patches());

            List<String> allViolations = new ArrayList<>();
            for (TaskContext.Patch patch : ctx.generatorOutput.patches()) {
                List<String> violations = manifest.checkConsistency(patch.file(), patch.modified());
                allViolations.addAll(violations);
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("violation_count", allViolations.size());
            metadata.put("violations", allViolations);

            if (allViolations.isEmpty()) {
                log.info("[consistency_checker] All checks passed");
                return ExpertResult.ok("Consistency check passed", metadata);
            } else {
                String details = String.join("\n", allViolations);
                log.warn("[consistency_checker] Found {} violations", allViolations.size());
                return ExpertResult.ok(details, metadata);
            }

        } catch (Exception e) {
            log.warn("[consistency_checker] Check failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    /**
     * 获取指定文件的跨文件约束
     * <p>
     * 供Generator注入prompt使用。
     * </p>
     * @param filePath 文件路径
     * @return 约束文本
     */
    public String getConstraintsForFile(String filePath) {
        return manifest.getConstraintsForFile(filePath);
    }
}
