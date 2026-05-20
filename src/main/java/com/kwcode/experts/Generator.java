package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.llm.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 生成器专家 - 只生成修改部分（patch结构），不直接写文件
 * <p>
 * 通过LLM生成补丁列表，每个补丁包含file/original/modified。
 * 三阶段重试策略：正常描述→错误分析→最小化修改。
 * </p>
 * @origin Python: experts.generator.Generator
 */
public class Generator {

    private static final Logger log = LoggerFactory.getLogger(Generator.class);

    private final LLMService llmService;

    public Generator(LLMService llmService) {
        this.llmService = llmService;
    }

    public Generator() { this(null); }

    /**
     * 生成补丁列表
     * <p>
     * 根据Locator的定位结果和重试策略，构建prompt调用LLM生成补丁。
     * RED-3: 独立上下文，每次重试使用新鲜prompt。
     * </p>
     * @origin Python: experts.generator.Generator.generate(ctx) -> dict
     * @param ctx 任务上下文
     * @return Generator结果：patches和explanation
     */
    public TaskContext.GeneratorResult generate(TaskContext ctx) {
        String prompt = buildPrompt(ctx);

        String llmOutput = callLlm(ctx, prompt);

        List<TaskContext.Patch> patches = parsePatches(llmOutput);

        String explanation = extractExplanation(llmOutput);

        log.info("[generator] produced {} patches", patches.size());
        return new TaskContext.GeneratorResult(patches, explanation);
    }

    /**
     * 构建生成prompt
     * <p>
     * 三阶段重试策略：
     * strategy=0: 正常描述
     * strategy=1: 从错误出发
     * strategy=2: 最小化修改
     * </p>
     * @origin Python: experts.generator.Generator._build_prompt(ctx)
     */
    private String buildPrompt(TaskContext ctx) {
        StringBuilder sb = new StringBuilder();

        if (ctx.expertSystemPrompt != null && !ctx.expertSystemPrompt.isEmpty()) {
            sb.append(ctx.expertSystemPrompt).append("\n\n");
        }

        if (ctx.retryHint != null && !ctx.retryHint.isEmpty()) {
            sb.append("【重试指导】").append(ctx.retryHint).append("\n\n");
        }

        sb.append("## 任务\n").append(ctx.userInput).append("\n\n");

        if (ctx.retryStrategy == 1 && !ctx.previousFailure.isEmpty()) {
            sb.append("## 上次错误\n").append(ctx.previousFailure).append("\n\n");
            if (!ctx.reflection.isEmpty()) {
                sb.append("## 错误分析\n").append(ctx.reflection).append("\n\n");
            }
        } else if (ctx.retryStrategy == 2) {
            sb.append("## 最小化修改要求\n请只修改导致测试失败的最少代码，不要改动其他部分。\n\n");
        }

        if (ctx.locatorOutput != null) {
            sb.append("## 相关文件\n");
            for (String f : ctx.locatorOutput.relevantFiles()) {
                sb.append("- ").append(f).append("\n");
            }
            if (!ctx.locatorOutput.relevantFunctions().isEmpty()) {
                sb.append("\n## 相关函数\n");
                for (String fn : ctx.locatorOutput.relevantFunctions()) {
                    sb.append("- ").append(fn).append("\n");
                }
            }
        }

        if (!ctx.relevantCodeSnippets.isEmpty()) {
            sb.append("\n## 代码内容\n");
            for (var entry : ctx.relevantCodeSnippets.entrySet()) {
                sb.append("### ").append(entry.getKey()).append("\n```\n")
                  .append(entry.getValue()).append("\n```\n\n");
            }
        }

        sb.append("## 输出格式\n请使用以下格式输出补丁：\n");
        String langHint = inferLanguageHint(ctx);
        String exampleExt = langHint != null ? LANG_TO_DEFAULT_EXT.getOrDefault(langHint, ".py") : ".py";
        String exampleFile = "file" + exampleExt;
        sb.append("```patch\n--- a/").append(exampleFile).append("\n+++ b/").append(exampleFile).append("\n@@ ... @@\n-原代码\n+新代码\n```\n");
        sb.append("\n注意：文件路径必须是项目内的相对路径，禁止使用 /dev/null 或绝对路径。\n");

        return sb.toString();
    }

    private String callLlm(TaskContext ctx, String prompt) {
        if (llmService == null) {
            log.warn("[generator] LLMService is null, cannot generate code");
            return "[LLM_ERROR] LLMService is null";
        }
        try {
            String systemPrompt = ctx.expertSystemPrompt != null ? ctx.expertSystemPrompt 
                : "You are a coding assistant. Generate code patches in the requested format.";
            return llmService.generateForExpert("generator", prompt, systemPrompt, 4000);
        } catch (Exception e) {
            log.error("[generator] LLM call failed: {}", e.getMessage());
            return "[LLM_ERROR] " + e.getMessage();
        }
    }

    private static final Map<String, String> EXT_TO_LANG = Map.ofEntries(
        Map.entry(".java", "Java"),
        Map.entry(".py", "Python"),
        Map.entry(".go", "Go"),
        Map.entry(".rs", "Rust"),
        Map.entry(".ts", "TypeScript"),
        Map.entry(".tsx", "TypeScript"),
        Map.entry(".js", "JavaScript"),
        Map.entry(".jsx", "JavaScript"),
        Map.entry(".kt", "Kotlin"),
        Map.entry(".scala", "Scala"),
        Map.entry(".c", "C"),
        Map.entry(".cpp", "C++"),
        Map.entry(".h", "C/C++")
    );

    private static final Map<String, String> LANG_FROM_PROBE = Map.of(
        "java", "Java",
        "python", "Python",
        "go", "Go",
        "rust", "Rust",
        "typescript", "TypeScript",
        "javascript", "JavaScript"
    );

    private static final Map<String, String> USER_INPUT_LANG_PATTERNS = Map.ofEntries(
        Map.entry("java类", "Java"),
        Map.entry("java", "Java"),
        Map.entry("python", "Python"),
        Map.entry("go语言", "Go"),
        Map.entry("golang", "Go"),
        Map.entry("rust", "Rust"),
        Map.entry("typescript", "TypeScript"),
        Map.entry("javascript", "JavaScript"),
        Map.entry("js函数", "JavaScript"),
        Map.entry("ts类", "TypeScript"),
        Map.entry("kt文件", "Kotlin"),
        Map.entry("kotlin", "Kotlin"),
        Map.entry("scala", "Scala"),
        Map.entry("c++", "C++"),
        Map.entry("cpp", "C++"),
        Map.entry("c语言", "C")
    );

    private static final Map<String, String> LANG_TO_DEFAULT_EXT = Map.of(
        "Java", ".java",
        "Python", ".py",
        "Go", ".go",
        "Rust", ".rs",
        "TypeScript", ".ts",
        "JavaScript", ".js",
        "Kotlin", ".kt",
        "Scala", ".scala",
        "C", ".c",
        "C++", ".cpp"
    );

    private String inferLanguageHint(TaskContext ctx) {
        String fromUserInput = inferLangFromUserInput(ctx.userInput);
        if (fromUserInput != null) {
            log.info("[generator] inferLanguageHint from userInput: {}", fromUserInput);
            return fromUserInput;
        }
        if (ctx.locatorOutput != null) {
            for (String f : ctx.locatorOutput.relevantFiles()) {
                String lang = extToLang(f);
                if (lang != null) {
                    log.debug("[generator] inferLanguageHint from file {}: {}", f, lang);
                    return lang;
                }
            }
        }
        for (String f : ctx.relevantCodeSnippets.keySet()) {
            String lang = extToLang(f);
            if (lang != null) {
                log.debug("[generator] inferLanguageHint from snippet {}: {}", f, lang);
                return lang;
            }
        }
        if (ctx.projectLang != null && !ctx.projectLang.isEmpty()) {
            String lang = LANG_FROM_PROBE.get(ctx.projectLang);
            if (lang != null) {
                log.debug("[generator] inferLanguageHint from projectLang: {} -> {}", ctx.projectLang, lang);
                return lang;
            }
        }
        return null;
    }

    private static String inferLangFromUserInput(String input) {
        if (input == null || input.isEmpty()) return null;
        String lower = input.toLowerCase();
        String best = null;
        int bestLen = 0;
        for (var entry : USER_INPUT_LANG_PATTERNS.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    private String extToLang(String filePath) {
        int dotIdx = filePath.lastIndexOf('.');
        if (dotIdx > 0) {
            return EXT_TO_LANG.get(filePath.substring(dotIdx).toLowerCase());
        }
        return null;
    }

    private List<TaskContext.Patch> parsePatches(String llmOutput) {
        if (llmOutput == null || llmOutput.isEmpty()) return List.of();
        List<TaskContext.Patch> patches = new ArrayList<>();

        String[] blocks = llmOutput.split("```patch\\n?");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i].split("```")[0];
            TaskContext.Patch patch = parseSinglePatch(block);
            if (patch != null) patches.add(patch);
        }

        if (patches.isEmpty()) {
            String[] lines = llmOutput.split("\n");
            String currentFile = null;
            StringBuilder original = new StringBuilder();
            StringBuilder modified = new StringBuilder();
            boolean inMinus = false, inPlus = false;

            for (String line : lines) {
                if (line.startsWith("--- a/")) {
                    String raw = line.substring(6).trim();
                    if (isValidFilePath(raw)) currentFile = raw;
                } else if (line.startsWith("--- /")) {
                    String raw = line.substring(5).trim();
                    if (isValidFilePath(raw)) currentFile = raw;
                } else if (line.startsWith("+++ b/")) {
                    String raw = line.substring(6).trim();
                    if (currentFile == null && isValidFilePath(raw)) currentFile = raw;
                } else if (line.startsWith("+++ /")) {
                    String raw = line.substring(5).trim();
                    if (currentFile == null && isValidFilePath(raw)) currentFile = raw;
                } else if (line.startsWith("-") && !line.startsWith("---")) {
                    original.append(line.substring(1)).append("\n");
                    inMinus = true;
                } else if (line.startsWith("+") && !line.startsWith("+++")) {
                    modified.append(line.substring(1)).append("\n");
                    inPlus = true;
                } else if (inMinus || inPlus) {
                    if (currentFile != null && (original.length() > 0 || modified.length() > 0)) {
                        patches.add(new TaskContext.Patch(currentFile, original.toString().stripTrailing(), modified.toString().stripTrailing()));
                    }
                    currentFile = null;
                    original = new StringBuilder();
                    modified = new StringBuilder();
                    inMinus = false;
                    inPlus = false;
                }
            }
            if (currentFile != null && (original.length() > 0 || modified.length() > 0)) {
                patches.add(new TaskContext.Patch(currentFile, original.toString().stripTrailing(), modified.toString().stripTrailing()));
            }
        }

        return patches;
    }

    private TaskContext.Patch parseSinglePatch(String block) {
        String[] lines = block.split("\n");
        String file = null;
        StringBuilder original = new StringBuilder();
        StringBuilder modified = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("--- a/")) {
                String raw = line.substring(6).trim();
                if (isValidFilePath(raw)) file = raw;
            } else if (line.startsWith("--- /")) {
                String raw = line.substring(5).trim();
                if (isValidFilePath(raw)) file = raw;
            } else if (line.startsWith("+++ b/")) {
                String raw = line.substring(6).trim();
                if (file == null && isValidFilePath(raw)) file = raw;
            } else if (line.startsWith("+++ /")) {
                String raw = line.substring(5).trim();
                if (file == null && isValidFilePath(raw)) file = raw;
            } else if (line.startsWith("@@")) {
                continue;
            } else if (line.startsWith("-")) {
                original.append(line.substring(1)).append("\n");
            } else if (line.startsWith("+")) {
                modified.append(line.substring(1)).append("\n");
            } else if (line.startsWith(" ")) {
                original.append(line.substring(1)).append("\n");
                modified.append(line.substring(1)).append("\n");
            }
        }

        if (file != null) return new TaskContext.Patch(file, original.toString().stripTrailing(), modified.toString().stripTrailing());
        return null;
    }

    private static boolean isValidFilePath(String path) {
        if (path == null || path.isEmpty()) return false;
        if (path.equals("dev/null") || path.equals("/dev/null")) return false;
        if (path.startsWith("/") || path.startsWith("\\")) return false;
        if (path.contains(":")) return false;
        return true;
    }

    private String extractExplanation(String llmOutput) {
        if (llmOutput == null) return "";
        if (llmOutput.startsWith("[LLM_ERROR]")) return llmOutput;
        int patchIdx = llmOutput.indexOf("```patch");
        if (patchIdx > 0) return llmOutput.substring(0, patchIdx).strip();
        return "";
    }
}
