package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 视觉专家 - 处理图片输入任务
 * <p>
 * 当用户输入包含图片时，VisionExpert负责：
 * 1. 解析图片路径
 * 2. 调用多模态LLM分析图片
 * 3. 生成图片描述或基于图片的代码修改
 * </p>
 * <p>
 * 触发条件：用户输入包含 [图片:...] 或 [image:...] 标记
 * </p>
 * @origin Python: experts.vision_expert.VisionExpert
 */
public class VisionExpert implements Expert {

    private static final Logger log = LoggerFactory.getLogger(VisionExpert.class);

    private static final String VISION_PROMPT =
        "你是一个视觉分析专家。请分析提供的图片，并根据用户需求生成响应。\n\n" +
        "用户需求：%s\n\n" +
        "要求：\n" +
        "1. 详细描述图片中的内容\n" +
        "2. 如果是代码截图，提取代码内容\n" +
        "3. 如果是UI截图，描述界面结构和元素\n" +
        "4. 根据用户需求给出具体建议";

    private final LLMService llmService;

    public VisionExpert(LLMService llmService) {
        this.llmService = llmService;
    }

    public VisionExpert() {
        this.llmService = null;
    }

    @Override
    public String name() {
        return "vision";
    }

    @Override
    public ExpertResult run(TaskContext ctx) {
        return analyze(ctx);
    }

    /**
     * 分析图片
     * <p>
     * 解析图片路径，调用多模态LLM分析。
     * </p>
     * @origin Python: experts.vision_expert.VisionExpert.analyze(ctx) -> dict
     * @param ctx 任务上下文
     * @return 分析结果
     */
    public ExpertResult analyze(TaskContext ctx) {
        List<String> imagePaths = resolveImagePaths(ctx);

        if (imagePaths.isEmpty()) {
            return ExpertResult.fail("No image paths found in input");
        }

        if (llmService == null) {
            return ExpertResult.fail("No LLM service available for vision analysis");
        }

        try {
            String prompt = String.format(VISION_PROMPT, ctx.userInput);

            String description = llmService.generateForExpert("vision", prompt,
                "你是视觉分析专家，擅长从图片中提取信息和代码。", 1000);

            if (description == null || description.isEmpty()) {
                return ExpertResult.fail("Vision analysis returned empty result");
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("image_count", imagePaths.size());
            metadata.put("description_length", description.length());

            log.info("[vision_expert] Analyzed {} images", imagePaths.size());

            return ExpertResult.ok(description, metadata);

        } catch (Exception e) {
            log.warn("[vision_expert] Analysis failed: {}", e.getMessage());
            return ExpertResult.fail(e.getMessage());
        }
    }

    /**
     * 从上下文中解析图片路径
     */
    private List<String> resolveImagePaths(TaskContext ctx) {
        List<String> paths = new ArrayList<>(ctx.imagePaths);

        if (ctx.imagePath != null && !ctx.imagePath.isEmpty()) {
            paths.add(ctx.imagePath);
        }

        String input = ctx.userInput;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(?:图片|image):\\s*([^\\]]+)\\]")
            .matcher(input);
        while (m.find()) {
            paths.add(m.group(1).trim());
        }

        return paths;
    }
}
