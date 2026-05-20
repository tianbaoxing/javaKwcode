package com.kwcode.core.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文裁剪器 - 对话历史压缩，不调用LLM
 * <p>
 * 对齐Python原始实现：对LLM对话消息列表做头尾保留+中间压缩。
 * 核心策略：保留头部（system + 首轮）+ 保留尾部（最近tailTokens），
 * 中间部分：tool输出提取关键词，assistant长输出截断+关键词，代码块保护不压缩。
 * </p>
 * <p>
 * 理论来源：
 * - "Lost in the Middle" (2023): 中间位置信息最易丢失，裁剪时保留首尾
 * - UI-RED-2: 耗时必须 &lt;5ms
 * </p>
 * <p>
 * 同时保留TaskContext字段级裁剪能力（pruneContext），供PipelineOrchestrator使用。
 * </p>
 * @origin Python: core.context_pruner.ContextPruner
 */
public class ContextPruner {

    private static final Logger log = LoggerFactory.getLogger(ContextPruner.class);

    private static final int DEFAULT_MAX_TOKENS = 8192;
    private static final int DEFAULT_TAIL_TOKENS = 8192;
    private static final int MASK_MIN_TOKENS = 200;
    private static final int HEAD_TURNS = 1;

    private static final int DEFAULT_MAX_CHARS = 24000;
    private static final int MAX_SNIPPET_CHARS = 2000;
    private static final int MAX_SEARCH_CHARS = 1500;
    private static final int MAX_DEBUG_CHARS = 1000;
    private static final int MAX_DOC_CHARS = 1000;
    private static final int MAX_MEMORY_CHARS = 800;

    private static final Pattern CODE_BLOCK_RE = Pattern.compile("```[\\w]*\\n(.*?)```", Pattern.DOTALL);

    private static final List<Pattern> KEYWORD_PATTERNS = List.of(
        Pattern.compile("(?:^|\\s)((?:\\w+/)+\\w+\\.\\w+)"),
        Pattern.compile("\\bdef\\s+(\\w+)\\s*\\("),
        Pattern.compile("\\bfunction\\s+(\\w+)\\s*[\\(\\{]"),
        Pattern.compile("\\bfunc\\s+(\\w+)\\s*\\("),
        Pattern.compile("\\bclass\\s+(\\w+)[\\s:\\(]"),
        Pattern.compile("(?:TODO|FIXME|BUG|HACK|NOTE):\\s*(.{0,60})"),
        Pattern.compile("(?:Error|Exception|Traceback)[:\\s]+(.{0,80})"),
        Pattern.compile("(?:line\\s+|L)(\\d+)"),
        Pattern.compile("^(?:import|from)\\s+\\S+", Pattern.MULTILINE)
    );

    private final int maxTokens;
    private final int tailTokens;
    private final int maxChars;
    private int compressCount = 0;
    private double lastCompressMs = 0.0;

    public ContextPruner() {
        this(DEFAULT_MAX_TOKENS, DEFAULT_TAIL_TOKENS, DEFAULT_MAX_CHARS);
    }

    public ContextPruner(int maxTokens) {
        this(maxTokens, Math.min(DEFAULT_TAIL_TOKENS, maxTokens * 3 / 4), DEFAULT_MAX_CHARS);
    }

    public ContextPruner(int maxTokens, int tailTokens, int maxChars) {
        this.maxTokens = maxTokens;
        this.tailTokens = Math.min(tailTokens, maxTokens * 3 / 4);
        this.maxChars = maxChars;
    }

    // ══════════════════════════════════════
    // 对话消息列表压缩（对齐Python核心逻辑）
    // ══════════════════════════════════════

    /**
     * 压缩消息列表。返回压缩后的副本，不修改原列表。
     * <p>
     * 压缩流程：
     * 1. 分离头部（system + 前HEAD_TURNS轮）
     * 2. 分离尾部（最近tailTokens tokens）
     * 3. 中间部分：tool输出提取关键词，assistant长输出截断+关键词
     * 4. 代码块保护不压缩
     * 5. 合并返回
     * </p>
     * @origin Python: core.context_pruner.ContextPruner.prune(messages) -> list[dict]
     * @param messages 消息列表，每条消息需含 "role" 和 "content"
     * @return 压缩后的消息列表
     */
    public List<Map<String, Object>> prune(List<Map<String, Object>> messages) {
        long t0 = System.nanoTime();

        if (messages == null || messages.isEmpty()) return messages;

        List<Map<String, Object>> rest = new ArrayList<>(messages);

        List<Map<String, Object>> head = new ArrayList<>();
        if (!rest.isEmpty() && "system".equals(rest.get(0).get("role"))) {
            head.add(rest.remove(0));
        }

        int turnsKept = 0;
        while (!rest.isEmpty() && turnsKept < HEAD_TURNS) {
            if ("user".equals(rest.get(0).get("role"))) {
                head.add(rest.remove(0));
                if (!rest.isEmpty() && "assistant".equals(rest.get(0).get("role"))) {
                    head.add(rest.remove(0));
                }
                turnsKept++;
            } else {
                break;
            }
        }

        List<Map<String, Object>> tail = new ArrayList<>();
        int tailTokensAcc = 0;
        for (int i = rest.size() - 1; i >= 0; i--) {
            int t = countTokens(getStringContent(rest.get(i)));
            if (tailTokensAcc + t > tailTokens) break;
            tail.add(0, rest.get(i));
            tailTokensAcc += t;
        }

        int tailSize = tail.size();
        List<Map<String, Object>> middle = rest.subList(0, rest.size() - tailSize);

        log.debug("[pruner] prune: head={} middle={} tail={} tailTokensAcc={}/{}", head.size(), middle.size(), tailSize, tailTokensAcc, tailTokens);

        List<Map<String, Object>> compressedMiddle = new ArrayList<>();
        for (Map<String, Object> msg : middle) {
            String role = (String) msg.getOrDefault("role", "");
            String content = getStringContent(msg);
            int tokens = countTokens(content);

            if (tokens < MASK_MIN_TOKENS) {
                compressedMiddle.add(msg);
                continue;
            }

            if (hasCodeBlock(content)) {
                String codeOnly = extractCodeBlocks(content);
                if (!codeOnly.isEmpty()) {
                    log.debug("[pruner] compress: code block preserved role={} origTokens={}", role, tokens);
                    Map<String, Object> compressed = new LinkedHashMap<>(msg);
                    compressed.put("content", codeOnly);
                    compressedMiddle.add(compressed);
                } else {
                    compressedMiddle.add(msg);
                }
                continue;
            }

            if ("tool".equals(role)) {
                String keywords = extractKeywords(content);
                Map<String, Object> compressed = new LinkedHashMap<>(msg);
                if (!keywords.isEmpty()) {
                    compressed.put("content", keywords);
                    log.info("[pruner] compress: tool output masked by keywords origTokens={}", tokens);
                } else {
                    compressed.put("content", "[output masked, " + tokens + " tokens]");
                    log.info("[pruner] compress: tool output fully masked origTokens={}", tokens);
                }
                compressedMiddle.add(compressed);
            } else if ("assistant".equals(role)) {
                String preview = content.substring(0, Math.min(200, content.length())).trim();
                String keywords = extractKeywords(content);
                String summary = preview;
                if (!keywords.isEmpty()) {
                    summary += "\n" + keywords;
                }
                Map<String, Object> compressed = new LinkedHashMap<>(msg);
                compressed.put("content", summary);
                log.debug("[pruner] compress: assistant summary origTokens={}", tokens);
                compressedMiddle.add(compressed);
            } else {
                compressedMiddle.add(msg);
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(head.size() + compressedMiddle.size() + tail.size());
        result.addAll(head);
        result.addAll(compressedMiddle);
        result.addAll(tail);

        double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
        lastCompressMs = elapsedMs;
        compressCount++;

        int origTokens = estimateTotal(messages);
        int newTokens = estimateTotal(result);
        double ratio = (1 - (double) newTokens / Math.max(origTokens, 1)) * 100;

        log.info("[pruner] 压缩完成 {}%，{}→{} tokens，耗时 {}ms（第{}次）",
            String.format("%.0f", ratio), origTokens, newTokens,
            String.format("%.2f", elapsedMs), compressCount);

        if (elapsedMs > 5) {
            log.warn("[pruner] 耗时 {}ms 超过5ms红线", String.format("%.2f", elapsedMs));
        }

        return result;
    }

    /**
     * 是否需要压缩：超过maxTokens的85%时触发
     * @origin Python: core.context_pruner.ContextPruner.needs_pruning(messages) -> bool
     */
    public boolean needsPruning(List<Map<String, Object>> messages) {
        return estimateTotal(messages) > maxTokens * 0.85;
    }

    /**
     * 估算消息列表的总token数
     * @origin Python: core.context_pruner.ContextPruner.estimate_total(messages) -> int
     */
    public int estimateTotal(List<Map<String, Object>> messages) {
        return messages.stream()
            .mapToInt(m -> countTokens(getStringContent(m)))
            .sum();
    }

    // ══════════════════════════════════════
    // TaskContext字段级裁剪（保留兼容）
    // ══════════════════════════════════════

    /**
     * 裁剪TaskContext使其不超过上下文窗口
     * <p>
     * 按优先级保留字段，低优先级字段优先裁剪。
     * </p>
     * @origin Python: core.context_pruner.ContextPruner.prune(ctx, max_chars) -> TaskContext
     * @param ctx 任务上下文
     * @return 裁剪后的上下文（原地修改）
     */
    public TaskContext pruneContext(TaskContext ctx) {
        int total = estimateChars(ctx);

        if (total <= maxChars) {
            log.debug("[context_pruner] No pruning needed: {} <= {}", total, maxChars);
            return ctx;
        }

        log.info("[context_pruner] Pruning context: {} -> target {}", total, maxChars);

        pruneSearchResults(ctx);
        pruneDebugInfo(ctx);
        pruneDocContext(ctx);
        pruneMemory(ctx);
        pruneCodeSnippets(ctx);

        int after = estimateChars(ctx);
        log.info("[context_pruner] After pruning: {} (removed {})", after, total - after);

        return ctx;
    }

    /**
     * 保留旧接口兼容性：prune(TaskContext)
     */
    public TaskContext prune(TaskContext ctx) {
        return pruneContext(ctx);
    }

    /**
     * 估算TaskContext总字符数
     */
    public int estimateChars(TaskContext ctx) {
        int total = 0;
        total += ctx.userInput.length();
        total += ctx.projectRoot.length();
        total += ctx.expertSystemPrompt.length();
        total += ctx.previousFailure.length();
        total += ctx.reflection.length();
        total += ctx.searchResults.length();
        total += ctx.debugInfo.length();
        total += ctx.docContext.length();
        total += ctx.kaiwuMemory.length();
        total += ctx.kwcodeRules.length();
        total += ctx.retryHint.length();
        total += ctx.upstreamConstraints.length();

        if (ctx.relevantCodeSnippets != null) {
            for (var entry : ctx.relevantCodeSnippets.entrySet()) {
                total += entry.getKey().length() + entry.getValue().length();
            }
        }

        if (ctx.generatorOutput != null && ctx.generatorOutput.explanation() != null) {
            total += ctx.generatorOutput.explanation().length();
        }

        return total;
    }

    // ══════════════════════════════════════
    // 渐进压缩（对齐Python GraduatedCompactor）
    // ══════════════════════════════════════

    /**
     * 按token使用率分级压缩
     * <p>
     * Layer 1 (70%)：裁剪tool输出冗余
     * Layer 2 (85%)：压缩中间轮次assistant输出
     * Layer 3 (95%)：摘要化早期对话，只保留关键决策
     * </p>
     * @origin Python: core.context_pruner.GraduatedCompactor.compress(messages, usage_ratio, bus)
     * @param messages 消息列表
     * @param usageRatio 当前token使用率(0.0~1.0)，0表示自动计算
     * @return 压缩后的消息列表
     */
    public List<Map<String, Object>> graduatedCompress(List<Map<String, Object>> messages, double usageRatio) {
        if (messages == null || messages.isEmpty()) return messages;

        double ratio = usageRatio;
        if (ratio <= 0) {
            int total = messages.stream().mapToInt(m -> countTokens(getStringContent(m))).sum();
            ratio = (double) total / Math.max(maxTokens, 1);
        }

        if (ratio < 0.70) return messages;

        if (ratio < 0.85) {
            return layer1TrimTools(messages);
        } else if (ratio < 0.95) {
            return prune(messages);
        } else {
            return layer3SummarizeEarly(messages);
        }
    }

    public List<Map<String, Object>> graduatedCompress(List<Map<String, Object>> messages) {
        return graduatedCompress(messages, 0.0);
    }

    /** Layer 1: 裁剪tool输出冗余（>500 token的tool输出提取关键词） */
    private List<Map<String, Object>> layer1TrimTools(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            if ("tool".equals(msg.get("role"))) {
                String content = getStringContent(msg);
                int tokens = countTokens(content);
                if (tokens > 500) {
                    if (hasCodeBlock(content)) {
                        String codeOnly = extractCodeBlocks(content);
                        if (!codeOnly.isEmpty()) {
                            Map<String, Object> compressed = new LinkedHashMap<>(msg);
                            compressed.put("content", codeOnly);
                            result.add(compressed);
                            continue;
                        }
                    }
                    String kw = extractKeywords(content);
                    Map<String, Object> compressed = new LinkedHashMap<>(msg);
                    if (!kw.isEmpty()) {
                        compressed.put("content", kw);
                    } else {
                        compressed.put("content", "[tool output masked, " + tokens + " tokens]");
                    }
                    result.add(compressed);
                    continue;
                }
            }
            result.add(msg);
        }
        return result;
    }

    /** Layer 3: 摘要化早期对话，只保留关键决策 */
    private List<Map<String, Object>> layer3SummarizeEarly(List<Map<String, Object>> messages) {
        if (messages.size() < 6) return prune(messages);

        List<Map<String, Object>> rest = new ArrayList<>(messages);
        List<Map<String, Object>> head = new ArrayList<>();

        if (!rest.isEmpty() && "system".equals(rest.get(0).get("role"))) {
            head.add(rest.remove(0));
        }
        if (!rest.isEmpty() && "user".equals(rest.get(0).get("role"))) {
            head.add(rest.remove(0));
            if (!rest.isEmpty() && "assistant".equals(rest.get(0).get("role"))) {
                head.add(rest.remove(0));
            }
        }

        if (rest.size() <= 4) {
            List<Map<String, Object>> result = new ArrayList<>(head);
            result.addAll(rest);
            return result;
        }

        List<Map<String, Object>> recent = rest.subList(rest.size() - 4, rest.size());
        List<Map<String, Object>> middlePart = rest.subList(0, rest.size() - 4);

        List<String> middleKeywords = new ArrayList<>();
        for (Map<String, Object> m : middlePart) {
            String role = (String) m.getOrDefault("role", "");
            if ("assistant".equals(role) || "tool".equals(role)) {
                String kw = extractKeywords(getStringContent(m));
                if (!kw.isEmpty()) {
                    middleKeywords.add(kw.replace("[摘要] ", ""));
                }
            }
        }

        String summaryText = middleKeywords.isEmpty() ? "[早期对话已压缩]"
            : String.join(" | ", middleKeywords.subList(0, Math.min(20, middleKeywords.size())));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", "system");
        summary.put("content", "[早期对话摘要] " + summaryText);

        List<Map<String, Object>> result = new ArrayList<>(head);
        result.add(summary);
        result.addAll(recent);
        return result;
    }

    // ══════════════════════════════════════
    // 辅助方法
    // ══════════════════════════════════════

    /**
     * 粗估token数。中文1.5字/token，英文4字符/token。
     * @origin Python: core.context_pruner._count_tokens(text) -> int
     */
    static int countTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cn = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') cn++;
        }
        int en = text.length() - cn;
        return (int) (cn * 1.5 + en / 4.0);
    }

    /**
     * 从文本中提取关键词/路径/函数名，拼成一行摘要
     * @origin Python: core.context_pruner._extract_keywords(text) -> str
     */
    static String extractKeywords(String text) {
        if (text == null || text.isEmpty()) return "";

        List<String> hits = new ArrayList<>();
        for (Pattern pat : KEYWORD_PATTERNS) {
            Matcher m = pat.matcher(text);
            while (m.find()) {
                String val = m.groupCount() > 0 ? m.group(1) : m.group(0);
                if (val != null) {
                    val = val.trim();
                    if (val.length() > 2) hits.add(val);
                }
            }
        }

        if (hits.isEmpty()) return "";

        List<String> unique = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String h : hits) {
            if (seen.add(h)) unique.add(h);
            if (unique.size() >= 15) break;
        }

        return "[摘要] " + String.join(" · ", unique);
    }

    /**
     * 检测文本是否包含代码块
     * @origin Python: core.context_pruner._has_code_block(text) -> bool
     */
    static boolean hasCodeBlock(String text) {
        return text != null && CODE_BLOCK_RE.matcher(text).find();
    }

    /**
     * 提取所有代码块内容，去掉解释文字。代码块内容不压缩，保持精确。
     * @origin Python: core.context_pruner._extract_code_blocks(text) -> str
     */
    static String extractCodeBlocks(String text) {
        if (text == null) return "";
        Matcher m = CODE_BLOCK_RE.matcher(text);
        List<String> blocks = new ArrayList<>();
        while (m.find() && blocks.size() < 3) {
            blocks.add("```\n" + m.group(1).trim() + "\n```");
        }
        return String.join("\n\n", blocks);
    }

    private String getStringContent(Map<String, Object> msg) {
        Object content = msg.get("content");
        return content != null ? content.toString() : "";
    }

    // ── TaskContext字段级裁剪辅助 ──

    private void pruneSearchResults(TaskContext ctx) {
        if (ctx.searchResults.length() > MAX_SEARCH_CHARS) {
            log.debug("[context_pruner] pruneSearchResults: {} → {} chars", ctx.searchResults.length(), MAX_SEARCH_CHARS);
            ctx.searchResults = ctx.searchResults.substring(0, MAX_SEARCH_CHARS) + "\n...(pruned)";
        }
    }

    private void pruneDebugInfo(TaskContext ctx) {
        if (ctx.debugInfo.length() > MAX_DEBUG_CHARS) {
            log.debug("[context_pruner] pruneDebugInfo: {} → {} chars", ctx.debugInfo.length(), MAX_DEBUG_CHARS);
            ctx.debugInfo = ctx.debugInfo.substring(0, MAX_DEBUG_CHARS) + "\n...(pruned)";
        }
    }

    private void pruneDocContext(TaskContext ctx) {
        if (ctx.docContext.length() > MAX_DOC_CHARS) {
            log.debug("[context_pruner] pruneDocContext: {} → {} chars", ctx.docContext.length(), MAX_DOC_CHARS);
            ctx.docContext = ctx.docContext.substring(0, MAX_DOC_CHARS) + "\n...(pruned)";
        }
    }

    private void pruneMemory(TaskContext ctx) {
        if (ctx.kaiwuMemory.length() > MAX_MEMORY_CHARS) {
            log.debug("[context_pruner] pruneMemory: {} → {} chars", ctx.kaiwuMemory.length(), MAX_MEMORY_CHARS);
            ctx.kaiwuMemory = ctx.kaiwuMemory.substring(0, MAX_MEMORY_CHARS) + "\n...(pruned)";
        }
    }

    private void pruneCodeSnippets(TaskContext ctx) {
        if (ctx.relevantCodeSnippets == null || ctx.relevantCodeSnippets.isEmpty()) return;

        Map<String, String> pruned = new LinkedHashMap<>();
        for (var entry : ctx.relevantCodeSnippets.entrySet()) {
            String file = entry.getKey();
            String code = entry.getValue();
            if (code.length() > MAX_SNIPPET_CHARS) {
                String head = code.substring(0, MAX_SNIPPET_CHARS / 2);
                String tail = code.substring(code.length() - MAX_SNIPPET_CHARS / 4);
                pruned.put(file, head + "\n... (middle pruned) ...\n" + tail);
            } else {
                pruned.put(file, code);
            }
        }

        int snippetChars = pruned.values().stream().mapToInt(String::length).sum();
        int snippetBudget = maxChars / 2;

        if (snippetChars > snippetBudget) {
            Map<String, String> limited = new LinkedHashMap<>();
            int used = 0;
            for (var entry : pruned.entrySet()) {
                if (used + entry.getValue().length() > snippetBudget) break;
                limited.put(entry.getKey(), entry.getValue());
                used += entry.getValue().length();
            }
            pruned = limited;
        }

        ctx.relevantCodeSnippets = pruned;
    }

    public int getCompressCount() { return compressCount; }
    public double getLastCompressMs() { return lastCompressMs; }
}
