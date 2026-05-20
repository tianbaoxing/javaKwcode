package com.kwcode.flywheel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwcode.registry.ExpertRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * Prompt优化器：分析成功轨迹，将经验规则追加到专家的system_prompt
 * SE-RED-4：使用外部API(Opus/Sonnet)，离线执行
 * @origin Python: flywheel/prompt_optimizer.py
 */
public class PromptOptimizer {

    private static final Logger log = LoggerFactory.getLogger(PromptOptimizer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分析prompt模板 */
    private static final String ANALYSIS_PROMPT =
        "你是KWCode专家系统的prompt优化器。\n\n" +
        "分析以下%s个成功任务的执行轨迹，提取可复用的经验规则。\n\n" +
        "## 轨迹摘要\n%s\n\n" +
        "## 当前专家system_prompt\n```\n%s\n```\n\n" +
        "## 任务\n基于轨迹分析，生成2-5条具体的经验规则，格式如下：\n" +
        "- 每条规则一行，以\"- \"开头\n- 规则必须具体可操作（不要泛泛而谈）\n" +
        "- 规则应该帮助未来同类任务提高成功率\n- 不要重复已有prompt中的内容\n\n" +
        "只输出规则列表，不要解释。";

    private final String apiKey;
    private final String model;

    /**
     * 构造函数
     * @param apiKey Anthropic API密钥
     * @param model 模型名称，默认claude-sonnet-4-20250514
     */
    public PromptOptimizer(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model != null ? model : "claude-sonnet-4-20250514";
    }

    /**
     * 分析轨迹，生成结论，追加到专家的system_prompt
     * 支持YAML和SKILL.md两种格式
     * @param expertName 专家名称
     * @param trajectories 成功轨迹列表
     * @param registry 专家注册表
     * @return 是否成功应用优化
     */
    @SuppressWarnings("unchecked")
    public boolean optimizeExpert(String expertName, List<Map<String, Object>> trajectories,
                                   ExpertRegistry registry) {
        Map<String, Object> expertDef = registry.get(expertName);
        if (expertDef == null) {
            log.warn("[prompt_optimizer] 专家未找到: {}", expertName);
            return false;
        }

        String sourcePath = (String) expertDef.get("_source");
        if (sourcePath == null || !Files.isRegularFile(Path.of(sourcePath))) {
            log.warn("[prompt_optimizer] 专家无源文件: {}", expertName);
            return false;
        }

        String currentPrompt = String.valueOf(
            expertDef.getOrDefault("instructions", expertDef.getOrDefault("system_prompt", "")));
        String summary = summarizeTrajectories(trajectories);

        // 调用API进行分析
        String newRules = callApi(trajectories.size(), summary, currentPrompt);
        if (newRules == null || newRules.isBlank()) {
            log.info("[prompt_optimizer] API未返回规则");
            return false;
        }

        // 根据格式更新
        String fmt = String.valueOf(expertDef.getOrDefault("_format", "yaml"));
        if ("skill".equals(fmt)) {
            return updateSkillMd(sourcePath, newRules, expertName, registry);
        } else {
            String updatedPrompt = currentPrompt.trim() + "\n\n## 经验规则（自动生成）\n" + newRules;
            return updateYaml(sourcePath, updatedPrompt, expertName, registry);
        }
    }

    /**
     * 调用Anthropic API生成优化规则
     * @param taskCount 任务数量
     * @param summary 轨迹摘要
     * @param currentPrompt 当前prompt
     * @return 规则文本，失败返回null
     */
    public String callApi(int taskCount, String summary, String currentPrompt) {
        String prompt = String.format(ANALYSIS_PROMPT,
            taskCount, summary, currentPrompt.substring(0, Math.min(2000, currentPrompt.length())));

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(60))
                .build();

            // 构建请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", 1000);
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            body.put("messages", messages);

            String jsonBody = MAPPER.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("[prompt_optimizer] API返回错误: {} {}", response.statusCode(), response.body());
                return null;
            }

            Map<String, Object> result = MAPPER.readValue(response.body(), Map.class);
            List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
            if (content != null && !content.isEmpty()) {
                return String.valueOf(content.get(0).getOrDefault("text", "")).trim();
            }
            return null;
        } catch (Exception e) {
            log.error("[prompt_optimizer] API调用失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从轨迹中提取模式摘要
     * @param trajectories 轨迹列表
     * @return 摘要文本
     */
    @SuppressWarnings("unchecked")
    public String summarizeTrajectories(List<Map<String, Object>> trajectories) {
        List<String> parts = new ArrayList<>();
        Map<String, Integer> fileFreq = new HashMap<>();

        for (Map<String, Object> t : trajectories) {
            List<String> files = (List<String>) t.getOrDefault("files_modified", List.of());
            for (String f : files) {
                fileFreq.merge(f, 1, Integer::sum);
            }
        }

        if (!fileFreq.isEmpty()) {
            // 高频修改文件
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fileFreq.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder sb = new StringBuilder("高频修改文件: ");
            int count = 0;
            for (var e : sorted) {
                if (count++ >= 5) break;
                sb.append(e.getKey()).append("(").append(e.getValue()).append("次) ");
            }
            parts.add(sb.toString().trim());
        }

        if (!trajectories.isEmpty()) {
            double avgElapsed = trajectories.stream()
                .mapToDouble(t -> ((Number) t.getOrDefault("latency_s", 0)).doubleValue())
                .average().orElse(0);
            parts.add(String.format("平均耗时: %.1f秒", avgElapsed));

            double avgRetries = trajectories.stream()
                .mapToDouble(t -> ((Number) t.getOrDefault("retry_count", 0)).doubleValue())
                .average().orElse(0);
            parts.add(String.format("平均重试: %.1f次", avgRetries));

            // 典型任务样本
            String inputs = trajectories.stream()
                .limit(5)
                .map(t -> String.valueOf(t.getOrDefault("user_input", "")).substring(0, Math.min(80, String.valueOf(t.getOrDefault("user_input", "")).length())))
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
            if (!inputs.isEmpty()) parts.add("典型任务: " + inputs);
        }

        return parts.isEmpty() ? "无足够数据" : String.join("\n", parts);
    }

    /**
     * 更新YAML文件的system_prompt
     * @param sourcePath YAML文件路径
     * @param newPrompt 新的prompt内容
     * @param expertName 专家名称
     * @param registry 专家注册表
     * @return 是否成功
     */
    @SuppressWarnings("unchecked")
    public static boolean updateYaml(String sourcePath, String newPrompt,
                                      String expertName, ExpertRegistry registry) {
        try {
            Path path = Path.of(sourcePath);
            String content = Files.readString(path, StandardCharsets.UTF_8);

            // 简单替换system_prompt字段（YAML格式）
            String updated = content.replaceAll(
                "(?m)^system_prompt:\\s*.*$",
                "system_prompt: |-\n  " + newPrompt.replace("\n", "\n  ")
            );

            Files.writeString(path, updated, StandardCharsets.UTF_8);

            // 更新内存注册表
            Map<String, Object> expertDef = registry.get(expertName);
            if (expertDef != null) {
                expertDef.put("system_prompt", newPrompt);
            }

            log.info("[prompt_optimizer] 已更新 {} 的system_prompt", expertName);
            return true;
        } catch (Exception e) {
            log.error("[prompt_optimizer] 更新YAML失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 追加规则到SKILL.md的经验规则部分
     * @param sourcePath SKILL.md文件路径
     * @param newRules 新规则文本
     * @param expertName 专家名称
     * @param registry 专家注册表
     * @return 是否成功
     */
    @SuppressWarnings("unchecked")
    public static boolean updateSkillMd(String sourcePath, String newRules,
                                          String expertName, ExpertRegistry registry) {
        try {
            Path path = Path.of(sourcePath);
            String content = Files.readString(path, StandardCharsets.UTF_8);

            String rulesHeader = "## 经验规则（自动生成）";
            if (content.contains(rulesHeader)) {
                // 追加到已有规则后
                content = content.trim() + "\n" + newRules + "\n";
            } else {
                // 添加新section
                content = content.trim() + "\n\n" + rulesHeader + "\n" + newRules + "\n";
            }

            Files.writeString(path, content, StandardCharsets.UTF_8);

            // 更新内存注册表
            Map<String, Object> expertDef = registry.get(expertName);
            if (expertDef != null) {
                // 重新提取body作为instructions
                int endOfFrontmatter = content.indexOf("---", content.indexOf("---") + 3);
                if (endOfFrontmatter > 0) {
                    String body = content.substring(endOfFrontmatter + 3).trim();
                    expertDef.put("instructions", body);
                    expertDef.put("system_prompt", body);
                }
            }

            log.info("[prompt_optimizer] 已更新 {} 的SKILL.md", expertName);
            return true;
        } catch (Exception e) {
            log.error("[prompt_optimizer] 更新SKILL.md失败: {}", e.getMessage());
            return false;
        }
    }
}
