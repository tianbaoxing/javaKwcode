package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 生成器专家适配器 - 实现Expert接口的Generator包装
 * <p>
 * 将Generator的generate()方法适配为Expert.run()接口。
 * PipelineOrchestrator通过Expert接口统一调度。
 * </p>
 * @origin Python: experts.generator.GeneratorExpert
 */
public class GeneratorExpert implements Expert {

    private static final Logger log = LoggerFactory.getLogger(GeneratorExpert.class);

    private final Generator generator;

    public GeneratorExpert(LLMService llmService) {
        this.generator = new Generator(llmService);
    }

    public GeneratorExpert(Generator generator) {
        this.generator = generator;
    }

    @Override
    public String name() {
        return "generator";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        try {
            TaskContext.GeneratorResult result = generator.generate(ctx);
            ctx.generatorOutput = result;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("patch_count", result.patches().size());
            metadata.put("explanation_length", result.explanation().length());

            log.info("[generator_expert] Generated {} patches", result.patches().size());

            return ExpertResult.ok(result.explanation(), metadata);
        } catch (Exception e) {
            log.warn("[generator_expert] Failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    public Generator getGenerator() { return generator; }
}
