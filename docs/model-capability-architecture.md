# ModelCapability 模型能力检测架构

> 源码：[ModelCapability.java](../src/main/java/com/kwcode/core/ModelCapability.java)

## 1. 为什么需要检测模型能力

不同规模的 LLM 在代码生成能力上差异巨大。系统需要根据模型能力自动调整策略：

| 策略参数 | 小模型 | 中等模型 | 大模型 |
|----------|--------|----------|--------|
| 最大重试次数 | 2 | 3 | 5 |
| Gate置信度阈值 | 0.90 | 0.80 | 0.70 |
| 强制规划模式 | 是 | 否 | 否 |
| 每任务最大文件数 | 4 | 10 | 20 |
| 每任务最大函数数 | 5 | 10 | 20 |
| 搜索触发轮次 | 1 | 2 | 2 |
| 复杂度警告阈值 | 2 | 4 | 8 |

**核心原则**：小模型能力有限，用更严格的约束防止失控；大模型能力强，给予更大自由度。

## 2. 三级 Tier 体系

```java
public enum Tier {
    SMALL("small", 16384),    // 小模型：上下文16K
    MEDIUM("medium", 32768),  // 中等模型：上下文32K
    LARGE("large", 65536);    // 大模型：上下文64K
}
```

## 3. 检测链（四层优先级）

```
模型名称输入
     │
     ▼
┌──────────────────┐
│ 1. 缓存命中？     │──── 是 ───→ 直接返回
└──────────────────┘
     │ 否
     ▼
┌──────────────────┐
│ 2. 参数量推断     │──── 匹配 ───→ 按参数量区间划分
└──────────────────┘           <10B→SMALL, 10-29B→MEDIUM, >=30B→LARGE
     │ 无数字
     ▼
┌──────────────────┐
│ 3. 精确匹配模型表 │──── 命中 ───→ 返回硬编码Tier
└──────────────────┘
     │ 未命中
     ▼
┌──────────────────┐
│ 4. 已知列表匹配   │──── 命中 ───→ SMALL 或 LARGE
└──────────────────┘
     │ 未命中
     ▼
  兜底：MEDIUM
```

### 第 1 层：缓存

```java
private static final ConcurrentHashMap<String, Tier> TIER_CACHE = new ConcurrentHashMap<>();
```

模型名 → Tier 的缓存，避免重复计算。首次检测后写入缓存，后续直接命中。

### 第 2 层：参数量推断

从模型名称中提取参数量数字，按区间划分 Tier：

```java
// 模式1：冒号/横杠+数字，如 qwen3:8b, deepseek-r1:70b
Pattern: [:\-](\d+)b

// 模式2：纯数字+b，如 8b, 72b
Pattern: (\d+)b
```

| 参数量 | Tier | 示例 |
|--------|------|------|
| < 10B | SMALL | `qwen3:8b`, `gemma3:4b`, `phi3:mini` |
| 10B ~ 29B | MEDIUM | `qwen3:14b`, `deepseek-r1:14b` |
| >= 30B | LARGE | `qwen3:72b`, `deepseek-r1:70b`, `llama3:70b` |

**匹配优先级**：模式1（冒号/横杠分隔）优先于模式2（纯数字）。

### 第 3 层：精确匹配已知模型表

`MODEL_TIER_MAP` 硬编码了常见模型的 Tier 映射：

**LARGE（大模型）**：

| 模型 | 上下文 |
|------|--------|
| `gpt-4o` | 128K |
| `gpt-4-turbo` | 128K |
| `claude-3-opus` | 200K |
| `deepseek-r1` | 65K |
| `deepseek-v4` | 131K |

**MEDIUM（中等模型）**：

| 模型 | 上下文 |
|------|--------|
| `gpt-4` | 8K |
| `claude-3-sonnet` | 200K |
| `deepseek-v3` | 65K |
| `deepseek-coder` | 16K |
| `codestral` | - |
| `mistral-large` | - |
| `codellama` | 16K |

**SMALL（小模型）**：

| 模型 | 上下文 |
|------|--------|
| `gpt-3.5` | - |
| `claude-3-haiku` | 200K |
| `qwen3:8b` | 32K |
| `llama3-8b` | 8K |

**匹配方式**：取模型名前缀（按 `:` 和 `/` 分割），与表中的 key 做 `contains` 匹配。

示例：
```
deepseek/deepseek-v4-pro → 前缀 "deepseek" → contains "deepseek-v4" → LARGE
anthropic/claude-3-sonnet → 前缀 "anthropic" → contains "claude-3-sonnet" → MEDIUM
```

### 第 4 层：已知列表 + 兜底

```java
KNOWN_SMALL = {"gemma3:4b", "gemma4:e2b", "phi3:mini", "qwen3:8b", "deepseek-r1:8b"}
KNOWN_LARGE = {"qwen3:72b", "deepseek-r1:70b", "llama3:70b", "qwen3:110b"}
```

不在任何已知列表中的模型，默认 **MEDIUM**。

## 4. 上下文窗口检测

上下文窗口检测同样遵循四层优先级：

```
1. 用户config覆盖（最高优先级）  → userConfigCtx
2. 精确匹配已知模型表           → MODEL_CTX_MAP
3. 云API模型表（非localhost）    → CLOUD_CTX + 默认128K
4. 按Tier给保守默认值           → SMALL=16K, MEDIUM=32K, LARGE=64K
```

### 有效上下文

```java
effectiveCtx = (int)(ctx * 0.9);  // 90%折扣率，预留系统提示等开销
```

### 云 API 检测

当模型不是本地模型（URL 非 localhost）时，使用云 API 模型表：

```java
CLOUD_CTX = {
    "deepseek-v4": 131072,
    "deepseek-v3": 65536,
    "qwen-max": 131072,
    "glm-4": 128000,
    "kimi": 131072,
    ...
}
CLOUD_DEFAULT_CTX = 131072;  // 云API默认128K
```

## 5. 推理模型检测

```java
public static boolean isReasoningModel(String modelName) {
    String lower = modelName.toLowerCase();
    return lower.contains("deepseek-r1") || lower.contains("o1-")
        || lower.contains("o3-") || lower.contains("qwq")
        || lower.contains("think") || lower.contains("reasoning");
}
```

推理模型影响 ThinkConfig 的自动配置：
- 推理模型 → `MODE_AUTO`（允许模型自主决定是否启用思考）
- 非推理模型 → `MODE_NEVER`（不启用思考模式）

## 6. 检测示例

| 模型名 | 参数量推断 | 精确匹配 | 最终Tier | 上下文 |
|--------|-----------|---------|---------|--------|
| `deepseek/deepseek-v4-pro` | 无数字 | `deepseek-v4` → LARGE | LARGE | 131K |
| `qwen3:8b` | 8 < 10 → SMALL | - | SMALL | 32K |
| `qwen3:72b` | 72 >= 30 → LARGE | - | LARGE | 32K |
| `gpt-4o` | 无数字 | `gpt-4o` → LARGE | LARGE | 128K |
| `claude-3-haiku` | 无数字 | `claude-3-haiku` → SMALL | SMALL | 200K |
| `my-custom-model` | 无数字 | 未命中 | MEDIUM | 32K |
| `ollama/llama3:70b` | 70 >= 30 → LARGE | - | LARGE | 8K |

## 7. 策略如何影响流水线

PipelineOrchestrator 在执行任务时读取 ModelStrategy：

```java
// PipelineOrchestrator.java
Tier tier = ModelCapability.detectTier(modelName);
ModelStrategy strategy = ModelCapability.getStrategy(tier);

// 策略覆盖默认重试次数
if (strategy.maxRetries < maxRetries) {
    maxRetries = strategy.maxRetries;
}

// 策略影响上下文压缩
ctx.effectiveCtx = ModelCapability.getEffectiveCtx(modelName);
```

**终端日志示例**：
```
[orchestrator] model strategy: model=deepseek/deepseek-v4-pro tier=大模型模式 strategyMaxRetries=5 forcePlanMode=false
[orchestrator] strategy override: maxRetries 5 → 5 (tier=大模型模式)
```

## 8. 完整检测示例：deepseek/deepseek-v4-pro

以用户使用 `deepseek/deepseek-v4-pro` 模型为例，逐步追踪检测过程：

### 8.1 Tier 检测

```
输入: modelName = "deepseek/deepseek-v4-pro"

第1层：缓存
  TIER_CACHE.get("deepseek/deepseek-v4-pro") → null（首次检测）
  ❌ 未命中

第2层：参数量推断
  Pattern [:\-](\d+)b 匹配 "deepseek/deepseek-v4-pro" → 无匹配（v4不是参数量）
  Pattern (\d+)b 匹配 "deepseek/deepseek-v4-pro" → 无匹配
  ❌ 无数字可提取

第3层：精确匹配模型表
  lower = "deepseek/deepseek-v4-pro"
  prefix = lower.split(":")[0].split("/")[0] = "deepseek"

  遍历 MODEL_TIER_MAP：
    "deepseek-v4" → "deepseek/deepseek-v4-pro".contains("deepseek-v4") → true ✅
    → Tier.LARGE

  写入缓存：TIER_CACHE.put("deepseek/deepseek-v4-pro", LARGE)

  ✅ 结果：LARGE
```

### 8.2 上下文窗口检测

```
输入: modelName = "deepseek/deepseek-v4-pro", isLocal = false（OpenRouter云端）

第1层：用户config覆盖
  userConfigCtx = -1（未配置）
  ❌ 跳过

第2层：精确匹配已知模型表
  MODEL_CTX_MAP 不包含 "deepseek/deepseek-v4-pro"
  遍历前缀匹配：
    "deepseek-v4" → "deepseek/deepseek-v4-pro".contains("deepseek-v4") → true ✅
    → ctx = 131072

  ✅ 结果：131072（128K）
```

### 8.3 有效上下文

```
effectiveCtx = (int)(131072 * 0.9) = 117964
```

### 8.4 推理模型检测

```
lower = "deepseek/deepseek-v4-pro"
contains("deepseek-r1") → false
contains("o1-") → false
contains("qwq") → false
contains("think") → false
contains("reasoning") → false

结果：非推理模型 → ThinkConfig = MODE_NEVER
```

### 8.5 策略参数

```
Tier = LARGE → ModelStrategy(LARGE, 0.70, false, 8, 20, 5, 2, 8)
```

| 参数 | 值 | 含义 |
|------|-----|------|
| gateConfidenceThreshold | 0.70 | Gate路由置信度≥0.70即可通过 |
| forcePlanMode | false | 不强制规划模式 |
| maxFilesPerTask | 8 | 每次任务最多涉及8个文件 |
| maxFunctionsPerTask | 20 | 每次任务最多涉及20个函数 |
| maxRetries | 5 | 最大重试5次 |
| searchTriggerAfter | 2 | 第2次重试后触发搜索增强 |
| complexityWarningThreshold | 8 | 复杂度超过8发出警告 |

### 8.6 流水线中的实际影响

```
PipelineOrchestrator 执行流程：

1. maxRetries = MAX_RETRIES(5)   ← 默认值
2. tier = ModelCapability.detectTier("deepseek/deepseek-v4-pro") → LARGE
3. strategy = ModelCapability.getStrategy(LARGE) → maxRetries=5
4. strategy.maxRetries(5) < maxRetries(5) → false → 不覆盖
5. finalMaxRetries = 5

终端日志：
[orchestrator] model strategy: model=deepseek/deepseek-v4-pro tier=大模型模式 strategyMaxRetries=5 forcePlanMode=false
[orchestrator] strategy override: maxRetries 5 → 5 (tier=大模型模式)
```

## 9. 扩展新模型

添加新模型只需在 `ModelCapability.java` 的 `static {}` 块中添加映射：

```java
// 添加Tier映射
MODEL_TIER_MAP.put("new-model-name", Tier.LARGE);

// 添加上下文映射
MODEL_CTX_MAP.put("new-model-name", 131072);
```

对于参数量命名规范的模型（如 `model:70b`），无需手动添加，参数量推断会自动识别。
