package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;

import java.util.Map;

/**
 * 专家统一接口
 * <p>
 * 所有专家（Locator, Generator, Verifier, DebugSubagent, ChatExpert等）
 * 实现此接口，支持PipelineOrchestrator统一调度。
 * </p>
 * @origin Python: experts.__init__.Expert (基类)
 */
public interface Expert {

    /**
     * 获取专家名称
     * @return 专家类型标识，如 "locator", "generator", "verifier"
     */
    String name();

    /**
     * 执行专家逻辑
     * <p>
     * 读取ctx中自己负责的输入字段，写入输出字段。
     * RED-3: 每个专家只写自己负责的字段。
     * </p>
     * @param ctx 任务上下文
     * @return 执行结果，包含 success 和输出数据
     */
    ExpertResult run(TaskContext ctx);

    /**
     * 专家执行结果
     */
    record ExpertResult(
        boolean success,
        String output,
        Map<String, Object> metadata
    ) {
        public static ExpertResult ok(String output) {
            return new ExpertResult(true, output, Map.of());
        }

        public static ExpertResult ok(String output, Map<String, Object> metadata) {
            return new ExpertResult(true, output, metadata);
        }

        public static ExpertResult fail(String error) {
            return new ExpertResult(false, error, Map.of());
        }
    }
}
