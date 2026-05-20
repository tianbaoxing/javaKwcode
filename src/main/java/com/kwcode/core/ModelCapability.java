package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型能力检测 - 根据模型名称推断能力等级和上下文大小
 * <p>
 * 对齐Python原始实现的3级Tier体系(SMALL/MEDIUM/LARGE)。
 * 查询链（四层，任何层失败静默进下一层）：
 * 1. 用户config覆盖（最高优先级）
 * 2. 精确匹配已知模型表
 * 3. 云API模型表（非localhost默认128K）
 * 4. 按tier给保守默认值
 * </p>
 * <p>
 * P2-RED-1: 所有检测都是本地的，不向外部服务器发送数据。
 * </p>
 * @origin Python: core.model_capability
 */
public class ModelCapability {

    private static final Logger log = LoggerFactory.getLogger(ModelCapability.class);

    public enum Tier {
        SMALL("small", 16384),
        MEDIUM("medium", 32768),
        LARGE("large", 65536);

        private final String key;
        private final int defaultCtx;
        Tier(String key, int defaultCtx) { this.key = key; this.defaultCtx = defaultCtx; }
        public String getKey() { return key; }
        public int getDefaultCtx() { return defaultCtx; }
    }

    private static final Map<String, Tier> MODEL_TIER_MAP = new LinkedHashMap<>();
    private static final Map<String, Integer> MODEL_CTX_MAP = new LinkedHashMap<>();
    private static final Map<Tier, ModelStrategy> STRATEGIES = new EnumMap<>(Tier.class);
    private static final Set<String> KNOWN_SMALL = Set.of(
        "gemma3:4b", "gemma4:e2b", "phi3:mini", "qwen3:8b", "deepseek-r1:8b"
    );
    private static final Set<String> KNOWN_LARGE = Set.of(
        "qwen3:72b", "deepseek-r1:70b", "llama3:70b", "qwen3:110b"
    );
    private static final Pattern PARAM_PATTERN = Pattern.compile("[:\\-](\\d+)b");
    private static final Pattern PARAM_PATTERN2 = Pattern.compile("(\\d+)b");

    private static final ConcurrentHashMap<String, Tier> TIER_CACHE = new ConcurrentHashMap<>();

    private static volatile int userConfigCtx = -1;

    static {
        MODEL_TIER_MAP.put("gemma3:4b", Tier.SMALL);
        MODEL_TIER_MAP.put("gemma4:e2b", Tier.SMALL);
        MODEL_TIER_MAP.put("phi3:mini", Tier.SMALL);
        MODEL_TIER_MAP.put("gpt-4o", Tier.LARGE);
        MODEL_TIER_MAP.put("gpt-4-turbo", Tier.LARGE);
        MODEL_TIER_MAP.put("gpt-4", Tier.MEDIUM);
        MODEL_TIER_MAP.put("gpt-3.5", Tier.SMALL);
        MODEL_TIER_MAP.put("claude-3-opus", Tier.LARGE);
        MODEL_TIER_MAP.put("claude-3-sonnet", Tier.MEDIUM);
        MODEL_TIER_MAP.put("claude-3-haiku", Tier.SMALL);
        MODEL_TIER_MAP.put("deepseek-r1", Tier.LARGE);
        MODEL_TIER_MAP.put("deepseek-v3", Tier.MEDIUM);
        MODEL_TIER_MAP.put("deepseek-v4", Tier.LARGE);
        MODEL_TIER_MAP.put("deepseek-coder", Tier.MEDIUM);
        MODEL_TIER_MAP.put("qwen3:72b", Tier.LARGE);
        MODEL_TIER_MAP.put("qwen3:14b", Tier.MEDIUM);
        MODEL_TIER_MAP.put("qwen3:8b", Tier.SMALL);
        MODEL_TIER_MAP.put("qwen-72b", Tier.LARGE);
        MODEL_TIER_MAP.put("qwen-7b", Tier.SMALL);
        MODEL_TIER_MAP.put("llama3-70b", Tier.LARGE);
        MODEL_TIER_MAP.put("llama3-8b", Tier.SMALL);
        MODEL_TIER_MAP.put("codestral", Tier.MEDIUM);
        MODEL_TIER_MAP.put("mistral-large", Tier.MEDIUM);
        MODEL_TIER_MAP.put("mistral-small", Tier.SMALL);
        MODEL_TIER_MAP.put("codellama", Tier.MEDIUM);

        MODEL_CTX_MAP.put("gpt-4o", 128000);
        MODEL_CTX_MAP.put("gpt-4-turbo", 128000);
        MODEL_CTX_MAP.put("gpt-4", 8192);
        MODEL_CTX_MAP.put("claude-3-opus", 200000);
        MODEL_CTX_MAP.put("claude-3-sonnet", 200000);
        MODEL_CTX_MAP.put("claude-3-haiku", 200000);
        MODEL_CTX_MAP.put("deepseek-r1", 65536);
        MODEL_CTX_MAP.put("deepseek-r1:8b", 65536);
        MODEL_CTX_MAP.put("deepseek-r1:14b", 65536);
        MODEL_CTX_MAP.put("deepseek-r1:32b", 65536);
        MODEL_CTX_MAP.put("deepseek-r1:70b", 65536);
        MODEL_CTX_MAP.put("deepseek-v3", 65536);
        MODEL_CTX_MAP.put("deepseek-v4", 131072);
        MODEL_CTX_MAP.put("deepseek-coder", 16384);
        MODEL_CTX_MAP.put("qwen2.5-coder:7b", 32768);
        MODEL_CTX_MAP.put("qwen2.5-coder:14b", 32768);
        MODEL_CTX_MAP.put("qwen2.5-coder:32b", 32768);
        MODEL_CTX_MAP.put("qwen3:8b", 32768);
        MODEL_CTX_MAP.put("qwen3:14b", 32768);
        MODEL_CTX_MAP.put("qwen3:30b-a3b", 32768);
        MODEL_CTX_MAP.put("qwen3:72b", 32768);
        MODEL_CTX_MAP.put("qwen-72b", 32768);
        MODEL_CTX_MAP.put("qwen-7b", 32768);
        MODEL_CTX_MAP.put("llama3-70b", 8192);
        MODEL_CTX_MAP.put("llama3-8b", 8192);
        MODEL_CTX_MAP.put("gemma3:4b", 8192);
        MODEL_CTX_MAP.put("gemma4:e2b", 8192);
        MODEL_CTX_MAP.put("codellama:7b", 16384);
        MODEL_CTX_MAP.put("codellama:13b", 16384);
        MODEL_CTX_MAP.put("codellama:34b", 16384);
        MODEL_CTX_MAP.put("glm-4", 128000);
        MODEL_CTX_MAP.put("kimi", 131072);

        STRATEGIES.put(Tier.SMALL, new ModelStrategy(Tier.SMALL, 0.90, true, 2, 5, 1, 1, 2));
        STRATEGIES.put(Tier.MEDIUM, new ModelStrategy(Tier.MEDIUM, 0.80, false, 4, 10, 3, 2, 4));
        STRATEGIES.put(Tier.LARGE, new ModelStrategy(Tier.LARGE, 0.70, false, 8, 20, 3, 2, 8));
    }

    private static final Map<String, Integer> CLOUD_CTX = Map.ofEntries(
        Map.entry("deepseek-v4", 131072),
        Map.entry("deepseek-v3", 65536),
        Map.entry("deepseek-r1", 65536),
        Map.entry("qwen-max", 131072),
        Map.entry("qwen-plus", 131072),
        Map.entry("qwen-turbo", 32768),
        Map.entry("qwen2.5-coder", 32768),
        Map.entry("qwen3", 32768),
        Map.entry("glm-4", 128000),
        Map.entry("kimi", 131072)
    );

    private static final int CLOUD_DEFAULT_CTX = 131072;

    /**
     * 设置用户配置的ctx覆盖值
     * <p>
     * 对齐Python：用户config.yaml手动配了ctx → 最高优先级
     * </p>
     * @origin Python: core.model_capability.get_effective_ctx() 第1层：load_config().get("default").get("ctx")
     * @param ctx 用户配置的ctx值，-1表示未配置
     */
    public static void setUserConfigCtx(int ctx) {
        log.info("[model_cap] setUserConfigCtx: {} → {} (override={})", userConfigCtx, ctx, ctx > 0);
        userConfigCtx = ctx;
    }

    /**
     * 清除缓存（测试用）
     */
    public static void clearCache() {
        int size = TIER_CACHE.size();
        TIER_CACHE.clear();
        log.debug("[model_cap] clearCache: cleared {} entries", size);
    }

    /**
     * 检测模型能力等级
     * <p>
     * 优先级：缓存 → 精确匹配 → 参数量推断(如qwen3:8b) → 已知列表 → 默认MEDIUM
     * </p>
     * @origin Python: core.model_capability.detect_model_tier(model_name, ollama_url) -> ModelTier
     * @param modelName 模型名称
     * @return 能力等级
     */
    public static Tier detectTier(String modelName) {
        if (modelName == null || modelName.isEmpty()) {
            log.debug("[model_cap] detectTier: null/empty → MEDIUM");
            return Tier.MEDIUM;
        }

        Tier cached = TIER_CACHE.get(modelName);
        if (cached != null) {
            log.debug("[model_cap] detectTier: cache HIT model={} → {}", modelName, cached);
            return cached;
        }

        Tier tier = detectTierFromName(modelName);
        TIER_CACHE.put(modelName, tier);
        log.debug("[model_cap] detectTier: cache MISS model={} → {} (computed)", modelName, tier);
        return tier;
    }

    /**
     * 从模型名称推断Tier（不含缓存）
     * @origin Python: core.model_capability._detect_from_name(model_name) -> ModelTier
     */
    private static Tier detectTierFromName(String modelName) {
        String lower = modelName.toLowerCase();

        Matcher m = PARAM_PATTERN.matcher(lower);
        if (m.find()) {
            int params = Integer.parseInt(m.group(1));
            if (params < 10) return Tier.SMALL;
            if (params < 30) return Tier.MEDIUM;
            return Tier.LARGE;
        }

        Matcher m2 = PARAM_PATTERN2.matcher(lower);
        if (m2.find()) {
            int params = Integer.parseInt(m2.group(1));
            if (params < 10) return Tier.SMALL;
            if (params < 30) return Tier.MEDIUM;
            return Tier.LARGE;
        }

        String prefix = lower.split(":")[0].split("/")[0];
        for (var entry : MODEL_TIER_MAP.entrySet()) {
            if (prefix.contains(entry.getKey()) || lower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        if (KNOWN_SMALL.contains(modelName)) return Tier.SMALL;
        if (KNOWN_LARGE.contains(modelName)) return Tier.LARGE;

        return Tier.MEDIUM;
    }

    /**
     * 检测模型上下文窗口大小
     * <p>
     * 查询链（四层，对齐Python）：
     * 1. 用户config覆盖（最高优先级）
     * 2. 精确匹配已知模型表
     * 3. 云API模型表（非localhost默认128K）
     * 4. 按tier给保守默认值
     * </p>
     * @origin Python: core.model_capability.get_effective_ctx(model_name, ollama_url) -> int
     * @param modelName 模型名称
     * @return 上下文窗口大小（token数）
     */
    public static int detectCtx(String modelName) {
        return detectCtx(modelName, true);
    }

    /**
     * 检测模型上下文窗口大小
     * @origin Python: core.model_capability.get_effective_ctx(model_name, ollama_url) -> int
     * @param modelName 模型名称
     * @param isLocal 是否为本地模型（影响云API默认值）
     * @return 上下文窗口大小（token数）
     */
    public static int detectCtx(String modelName, boolean isLocal) {
        if (modelName == null || modelName.isEmpty()) {
            log.debug("[model_cap] detectCtx: null/empty → MEDIUM default={}", Tier.MEDIUM.getDefaultCtx());
            return Tier.MEDIUM.getDefaultCtx();
        }

        // 1. 用户config覆盖
        if (userConfigCtx > 0) {
            log.info("[model_cap] detectCtx: userConfig OVERRIDE model={} → {} (isLocal={})", modelName, userConfigCtx, isLocal);
            return userConfigCtx;
        }

        String lower = modelName.toLowerCase();

        // 2. 精确匹配已知模型表
        if (MODEL_CTX_MAP.containsKey(lower)) {
            int ctx = MODEL_CTX_MAP.get(lower);
            log.debug("[model_cap] detectCtx: exact match model={} → {}", modelName, ctx);
            return ctx;
        }
        String prefix = lower.split(":")[0].split("/")[0];
        for (var entry : MODEL_CTX_MAP.entrySet()) {
            if (lower.contains(entry.getKey()) || prefix.contains(entry.getKey())) {
                log.debug("[model_cap] detectCtx: prefix match model={} key={} → {}", modelName, entry.getKey(), entry.getValue());
                return entry.getValue();
            }
        }

        // 3. 云API模型表（非localhost默认128K）
        if (!isLocal) {
            for (var entry : CLOUD_CTX.entrySet()) {
                if (lower.contains(entry.getKey())) {
                    log.debug("[model_cap] detectCtx: cloud table match model={} → {}", modelName, entry.getValue());
                    return entry.getValue();
                }
            }
            log.info("[model_cap] detectCtx: cloud DEFAULT model={} → {} (isLocal=false)", modelName, CLOUD_DEFAULT_CTX);
            return CLOUD_DEFAULT_CTX;
        }

        // 4. 按tier给保守默认值
        int ctx = detectTier(modelName).getDefaultCtx();
        log.debug("[model_cap] detectCtx: tier default model={} → {} (isLocal=true)", modelName, ctx);
        return ctx;
    }

    /**
     * 获取有效上下文大小（扣除系统提示等开销）
     * <p>
     * 对齐Python：effective_ctx = ctx * 0.9（90%折扣率）
     * </p>
     * @origin Python: core.model_capability.get_effective_ctx() 返回 n_ctx * 0.9
     * @param modelName 模型名称
     * @return 有效上下文大小
     */
    public static int getEffectiveCtx(String modelName) {
        return getEffectiveCtx(modelName, true);
    }

    /**
     * 获取有效上下文大小
     * @param modelName 模型名称
     * @param isLocal 是否为本地模型
     * @return 有效上下文大小
     */
    public static int getEffectiveCtx(String modelName, boolean isLocal) {
        int ctx = detectCtx(modelName, isLocal);
        return (int) (ctx * 0.9);
    }

    public static boolean isReasoningModel(String modelName) {
        if (modelName == null) return false;
        String lower = modelName.toLowerCase();
        return lower.contains("deepseek-r1") || lower.contains("o1-") || lower.contains("o3-")
            || lower.contains("qwq") || lower.contains("think") || lower.contains("reasoning");
    }

    public static Map<String, Object> getCapability(String modelName) {
        return getCapability(modelName, true);
    }

    public static Map<String, Object> getCapability(String modelName, boolean isLocal) {
        Tier tier = detectTier(modelName);
        int ctx = detectCtx(modelName, isLocal);
        int effectiveCtx = getEffectiveCtx(modelName, isLocal);
        boolean reasoning = isReasoningModel(modelName);

        Map<String, Object> cap = new LinkedHashMap<>();
        cap.put("model", modelName);
        cap.put("tier", tier.getKey());
        cap.put("ctx_window", ctx);
        cap.put("effective_ctx", effectiveCtx);
        cap.put("is_reasoning", reasoning);
        cap.put("supports_think", reasoning);
        cap.put("supports_vision", lowerContainsAny(modelName,
            "gpt-4o", "gpt-4-turbo", "claude-3", "gemini", "qwen-vl"));
        return cap;
    }

    public static ModelStrategy getStrategy(Tier tier) {
        return STRATEGIES.getOrDefault(tier, STRATEGIES.get(Tier.MEDIUM));
    }

    public static String tierDisplayName(Tier tier) {
        return switch (tier) {
            case SMALL -> "小模型模式";
            case MEDIUM -> "中等模型";
            case LARGE -> "大模型模式";
        };
    }

    private static boolean lowerContainsAny(String name, String... patterns) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        for (String p : patterns) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    public static class ModelStrategy {
        public final Tier tier;
        public final double gateConfidenceThreshold;
        public final boolean forcePlanMode;
        public final int maxFilesPerTask;
        public final int maxFunctionsPerTask;
        public final int maxRetries;
        public final int searchTriggerAfter;
        public final int complexityWarningThreshold;

        public ModelStrategy(Tier tier, double gateConfidenceThreshold, boolean forcePlanMode,
                             int maxFilesPerTask, int maxFunctionsPerTask, int maxRetries,
                             int searchTriggerAfter, int complexityWarningThreshold) {
            this.tier = tier;
            this.gateConfidenceThreshold = gateConfidenceThreshold;
            this.forcePlanMode = forcePlanMode;
            this.maxFilesPerTask = maxFilesPerTask;
            this.maxFunctionsPerTask = maxFunctionsPerTask;
            this.maxRetries = maxRetries;
            this.searchTriggerAfter = searchTriggerAfter;
            this.complexityWarningThreshold = complexityWarningThreshold;
        }
    }
}
