package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 验证器专家适配器 - 实现Expert接口的Verifier包装
 * <p>
 * 将Verifier的verify()方法适配为Expert.run()接口。
 * PipelineOrchestrator通过Expert接口统一调度。
 * </p>
 * @origin Python: experts.verifier.VerifierExpert
 */
public class VerifierExpert implements Expert {

    private static final Logger log = LoggerFactory.getLogger(VerifierExpert.class);

    private final Verifier verifier;

    public VerifierExpert(ToolGateway tools, GapDetector gapDetector) {
        this.verifier = new Verifier(tools, gapDetector);
    }

    public VerifierExpert(Verifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public String name() {
        return "verifier";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        try {
            TaskContext.VerifierResult result = verifier.verify(ctx);
            ctx.verifierOutput = result;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("passed", result.passed());
            metadata.put("syntax_ok", result.syntaxOk());
            metadata.put("tests_passed", result.testsPassed());
            metadata.put("tests_total", result.testsTotal());
            metadata.put("error_type", result.errorType());

            if (result.passed()) {
                log.info("[verifier_expert] PASSED ({}/{})", result.testsPassed(), result.testsTotal());
            } else {
                log.info("[verifier_expert] FAILED ({}/{}, error={})",
                    result.testsPassed(), result.testsTotal(), result.errorType());
            }

            return ExpertResult.ok(
                result.passed() ? "Tests passed" : "Tests failed: " + result.errorType(),
                metadata
            );
        } catch (Exception e) {
            log.warn("[verifier_expert] Failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    public Verifier getVerifier() { return verifier; }
}
