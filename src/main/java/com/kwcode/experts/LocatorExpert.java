package com.kwcode.experts;

import com.kwcode.ast.GraphRetriever;
import com.kwcode.core.context.TaskContext;
import com.kwcode.memory.KaiwuMemory;
import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 定位器专家适配器 - 实现Expert接口的Locator包装
 * <p>
 * 将Locator的locate()方法适配为Expert.run()接口。
 * PipelineOrchestrator通过Expert接口统一调度。
 * </p>
 * @origin Python: experts.locator.LocatorExpert
 */
public class LocatorExpert implements Expert {

    private static final Logger log = LoggerFactory.getLogger(LocatorExpert.class);

    private final Locator locator;

    public LocatorExpert(ToolGateway tools, KaiwuMemory memory, GraphRetriever retriever) {
        this.locator = new Locator(tools, memory, retriever);
    }

    public LocatorExpert(Locator locator) {
        this.locator = locator;
    }

    @Override
    public String name() {
        return "locator";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        try {
            TaskContext.LocatorResult result = locator.locate(ctx);
            ctx.locatorOutput = result;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("relevant_files", result.relevantFiles().size());
            metadata.put("relevant_functions", result.relevantFunctions().size());
            metadata.put("edit_locations", result.editLocations().size());

            log.info("[locator_expert] Found {} files, {} functions",
                result.relevantFiles().size(), result.relevantFunctions().size());

            return ExpertResult.ok("Located " + result.relevantFiles().size() + " files", metadata);
        } catch (Exception e) {
            log.warn("[locator_expert] Failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    public Locator getLocator() { return locator; }
}
