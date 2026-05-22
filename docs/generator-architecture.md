# Generator 生成器专家架构

> 源码：[Generator.java](../src/main/java/com/kwcode/experts/Generator.java)
> 调用入口：[PipelineOrchestrator.java#L407-416](../src/main/java/com/kwcode/core/orchestrator/PipelineOrchestrator.java)

## 1. Generator 在流水线中的角色

Generator 是专家流水线的**核心代码生成节点**，负责根据 Locator 的定位结果和用户任务，通过 LLM 生成代码补丁（patch）。它是唯一调用 LLM 生成代码的专家。

```
Gate路由 → EnvProber → GapDetector → Locator → [Generator] → Verifier → Reviewer
                                         ↑ 定位结果输入    ↓ 补丁输出
```

**核心职责**：回答"代码应该怎么改"这个问题，输出结构化的补丁列表。

## 2. 执行流程

Generator.generate() 分四步执行：

```
┌──────────────────────────────────────────────────┐
│ Step 1: buildPrompt(ctx) — 构建提示词             │
│ Step 2: callLlm(ctx, prompt) — 调用LLM生成代码    │
│ Step 3: parsePatches(llmOutput) — 解析补丁        │
│ Step 4: extractExplanation(llmOutput) — 提取说明  │
└──────────────────────────────────────────────────┘
```

## 3. 提示词构建详解（buildPrompt）

这是 Generator 最核心的方法，将 TaskContext 中的所有信息组装为 LLM 可理解的 prompt。

### 3.1 完整 Prompt 结构

```
┌─────────────────────────────────────────┐
│ [专家系统提示词] (expertSystemPrompt)     │  ← 可选，来自ExpertRegistry路由
├─────────────────────────────────────────┤
│ 【重试指导】{retryHint}                  │  ← 可选，重试时注入
├─────────────────────────────────────────┤
│ ## 任务                                 │
│ {userInput}                             │  ← 用户原始输入
├─────────────────────────────────────────┤
│ ## 上次错误                             │  ← 仅 retryStrategy=1
│ {previousFailure}                       │
│                                         │
│ ## 错误分析                             │  ← 仅 retryStrategy=1 且有reflection
│ {reflection}                            │
├─────────────────────────────────────────┤
│ ## 最小化修改要求                        │  ← 仅 retryStrategy=2
│ 请只修改导致测试失败的最少代码...          │
├─────────────────────────────────────────┤
│ ## 相关文件                             │  ← 来自 Locator 输出
│ - file1.java                            │
│ - file2.py                              │
│                                         │
│ ## 相关函数                             │  ← 来自 Locator 输出
│ - com.kwcode.experts.Locator.locate     │
├─────────────────────────────────────────┤
│ ## 代码内容                             │  ← 来自 Locator 收集的代码片段
│ ### file1.java                          │
│ ```                                     │
│ {relevantCodeSnippets}                  │
│ ```                                     │
├─────────────────────────────────────────┤
│ ## 输出格式                             │
│ 请使用以下格式输出补丁：                  │
│ ```patch                                │
│ --- a/file.java                         │
│ +++ b/file.java                         │
│ @@ ... @@                               │
│ -原代码                                 │
│ +新代码                                 │
│ ```                                     │
│ 注意：文件路径必须是项目内的相对路径...    │
└─────────────────────────────────────────┘
```

### 3.2 各分区详解

#### 分区 1：专家系统提示词（expertSystemPrompt）

```java
if (ctx.expertSystemPrompt != null && !ctx.expertSystemPrompt.isEmpty()) {
    sb.append(ctx.expertSystemPrompt).append("\n\n");
}
```

来源：当 Gate 路由到 ExpertRegistry 中注册的专家时，注册表会注入 `system_prompt` 或 `instructions` 字段。这个提示词同时作为 LLM 调用的 system message。

默认值（无注册表路由时）：
```
"You are a coding assistant. Generate code patches in the requested format."
```

#### 分区 2：重试指导（retryHint）

```java
if (ctx.retryHint != null && !ctx.retryHint.isEmpty()) {
    sb.append("【重试指导】").append(ctx.retryHint).append("\n\n");
}
```

由 PipelineOrchestrator.buildRetryHint() 根据错误类型生成，包含具体的修改约束：

| 错误类型 | 重试指导 |
|----------|----------|
| `syntax` | "只修 {error_file}:{error_line} 的语法错误，修改≤5行，不触碰其他函数" |
| `assertion` | "测试期望：{error_message}。只改1个函数使断言通过，修改≤10行" |
| `import` | ""（空，由 import_fixer 处理） |
| `patch_apply` | "必须先read_file读取文件最新内容，禁止使用缓存的original" |
| `runtime` | ""（空，由 debug_subagent 先分析） |
| `unknown` | "只修改1个函数，修改≤15行，不触碰报错位置±20行外的代码" |

模板变量替换：
- `{error_file}` → Verifier 检测到的错误文件
- `{error_line}` → 错误行号
- `{error_message}` → 错误信息

额外追加：如果上次有生成代码，追加"上次生成的代码（有问题）"片段（截取前 300 字符）+ "请不要重复同样的错误。"

#### 分区 3：任务描述（userInput）

```java
sb.append("## 任务\n").append(ctx.userInput).append("\n\n");
```

直接使用用户原始输入，不做任何改写。

#### 分区 4：三阶段重试策略

Generator 支持三种重试策略，通过 `ctx.retryStrategy` 控制：

**strategy=0（正常描述）**：不追加额外信息，直接使用用户输入。

**strategy=1（从错误出发）**：
```java
if (ctx.retryStrategy == 1 && !ctx.previousFailure.isEmpty()) {
    sb.append("## 上次错误\n").append(ctx.previousFailure).append("\n\n");
    if (!ctx.reflection.isEmpty()) {
        sb.append("## 错误分析\n").append(ctx.reflection).append("\n\n");
    }
}
```
注入上次 Verifier 的错误详情和 LLM 对错误的分析。

**strategy=2（最小化修改）**：
```java
else if (ctx.retryStrategy == 2) {
    sb.append("## 最小化修改要求\n请只修改导致测试失败的最少代码，不要改动其他部分。\n\n");
}
```
强制 LLM 只做最小改动，防止过度修改引入新问题。

#### 分区 5：Locator 定位结果

```java
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
```

直接消费 Locator 的 `relevantFiles` 和 `relevantFunctions`，为 LLM 提供修改范围指引。

#### 分区 6：代码片段

```java
if (!ctx.relevantCodeSnippets.isEmpty()) {
    sb.append("\n## 代码内容\n");
    for (var entry : ctx.relevantCodeSnippets.entrySet()) {
        sb.append("### ").append(entry.getKey()).append("\n```\n")
          .append(entry.getValue()).append("\n```\n\n");
    }
}
```

这些代码片段由 Locator 在 Step 4 通过 `ToolGateway.readFile()` 收集，每文件截取前 2000 字符。LLM 基于这些代码内容生成精确的补丁。

#### 分区 7：输出格式约束

```java
sb.append("## 输出格式\n请使用以下格式输出补丁：\n");
String langHint = inferLanguageHint(ctx);
String exampleExt = langHint != null ? LANG_TO_DEFAULT_EXT.getOrDefault(langHint, ".py") : ".py";
String exampleFile = "file" + exampleExt;
sb.append("```patch\n--- a/").append(exampleFile).append("\n+++ b/").append(exampleFile)
  .append("\n@@ ... @@\n-原代码\n+新代码\n```\n");
sb.append("\n注意：文件路径必须是项目内的相对路径，禁止使用 /dev/null 或绝对路径。\n");
```

关键设计：
- **语言感知**：根据推断的项目语言，示例文件使用对应的扩展名（如 `.java`、`.py`）
- **路径约束**：强制使用相对路径，禁止 `/dev/null` 或绝对路径
- **统一 diff 格式**：使用 `--- a/` / `+++ b/` 的标准 patch 格式

### 3.3 语言推断（inferLanguageHint）

Generator 按优先级从四个来源推断项目语言：

```
1. 用户输入关键词 → "java类" → Java
2. Locator定位文件扩展名 → ".java" → Java
3. 代码片段文件扩展名 → ".java" → Java
4. EnvProber检测结果 → ctx.projectLang → "java" → Java
```

支持的语言映射：

| 扩展名 | 语言 | 默认扩展名 |
|--------|------|-----------|
| `.java` | Java | `.java` |
| `.py` | Python | `.py` |
| `.go` | Go | `.go` |
| `.rs` | Rust | `.rs` |
| `.ts`/`.tsx` | TypeScript | `.ts` |
| `.js`/`.jsx` | JavaScript | `.js` |
| `.kt` | Kotlin | `.kt` |
| `.scala` | Scala | `.scala` |
| `.c` | C | `.c` |
| `.cpp` | C++ | `.cpp` |

用户输入语言检测模式（按长度优先匹配）：

| 模式 | 语言 |
|------|------|
| `java类` / `java` | Java |
| `python` | Python |
| `go语言` / `golang` | Go |
| `rust` | Rust |
| `typescript` | TypeScript |
| `javascript` | JavaScript |
| `kt文件` / `kotlin` | Kotlin |
| `scala` | Scala |
| `c++` / `cpp` / `c语言` | C/C++ |

## 4. LLM 调用（callLlm）

```java
private String callLlm(TaskContext ctx, String prompt) {
    String systemPrompt = ctx.expertSystemPrompt != null ? ctx.expertSystemPrompt
        : "You are a coding assistant. Generate code patches in the requested format.";
    return llmService.generateForExpert("generator", prompt, systemPrompt, 4000);
}
```

### 调用参数

| 参数 | 值 | 说明 |
|------|-----|------|
| expertType | `"generator"` | 通过 ModelRouter 路由到生成专用模型 |
| system | expertSystemPrompt 或默认值 | 系统提示词 |
| maxTokens | 4000 | 最大输出 token 数 |

### 消息结构

LLMService.generateForExpert() 将请求组装为 Spring AI 的 Prompt：

```java
List<Message> messages = new ArrayList<>();
messages.add(new SystemMessage(system));   // 系统提示词
messages.add(new UserMessage(prompt));     // 用户提示词（buildPrompt构建的完整内容）
```

### 错误处理

- LLMService 为 null → 返回 `[LLM_ERROR] LLMService is null`
- LLM 调用异常 → 返回 `[LLM_ERROR] {异常信息}`
- PipelineOrchestrator 检测到 `[LLM_ERROR]` 前缀 → 设置 `ctx.llmError`，当前步骤返回 false

## 5. 补丁解析（parsePatches）

LLM 输出的补丁解析支持两种模式：

### 模式 1：标准 patch 代码块

按 ` ```patch ` 分割，每个代码块解析为一个 Patch：

```
```patch
--- a/src/main/java/Foo.java
+++ b/src/main/java/Foo.java
@@ -10,6 +10,6 @@
-old line
+new line
 context line
```
```

### 模式 2：宽松行级解析

当没有 ` ```patch ` 标记时，逐行解析：

| 行格式 | 处理 |
|--------|------|
| `--- a/{path}` | 记录文件路径 |
| `+++ b/{path}` | 记录文件路径（如果尚未记录） |
| `-{content}` | 追加到 original |
| `+{content}` | 追加到 modified |
| ` {content}` | 同时追加到 original 和 modified（上下文行） |

### 路径合法性校验

```java
private static boolean isValidFilePath(String path) {
    if (path.equals("dev/null") || path.equals("/dev/null")) return false;
    if (path.startsWith("/") || path.startsWith("\\")) return false;
    if (path.contains(":")) return false;
    return true;
}
```

拒绝绝对路径、`/dev/null` 和含盘符的 Windows 路径。

## 6. 说明提取（extractExplanation）

```java
private String extractExplanation(String llmOutput) {
    if (llmOutput.startsWith("[LLM_ERROR]")) return llmOutput;
    int patchIdx = llmOutput.indexOf("```patch");
    if (patchIdx > 0) return llmOutput.substring(0, patchIdx).strip();
    return "";
}
```

LLM 输出中 ` ```patch ` 之前的文本作为说明，供 Reviewer 审查时参考。

## 7. 输出结构

```java
public record GeneratorResult(List<Patch> patches, String explanation) {}

public record Patch(String file, String original, String modified) {}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `patches` | `List<Patch>` | 补丁列表 |
| `patches[].file` | `String` | 文件相对路径 |
| `patches[].original` | `String` | 原始代码（被替换的部分） |
| `patches[].modified` | `String` | 修改后代码 |
| `explanation` | `String` | LLM 对修改的说明 |

## 8. 重试策略与提示词演进

Generator 的提示词在重试过程中逐步收紧约束：

```
第1次（strategy=0）: 完整任务描述 + 定位结果 + 代码内容
       ↓ 失败
第2次（strategy=1）: + 上次错误 + 错误分析 + 重试指导
       ↓ 失败
第3次（strategy=2）: + 最小化修改要求（只改最少代码）
```

配合 PipelineOrchestrator 的重试策略映射：

```
syntax错误    → "只修语法错误，修改≤5行"
assertion错误 → "只改1个函数使断言通过，修改≤10行"
patch_apply   → "必须先read_file读取最新内容"
runtime错误   → 先 debug_subagent 分析，再重试
unknown错误   → "只修改1个函数，修改≤15行"
```

## 9. 完整 Prompt 示例

以下是一个 Java 项目的实际 prompt 示例：

```
You are a coding assistant. Generate code patches in the requested format.

## 任务
修复Locator的locate方法空指针异常

## 相关文件
- src/main/java/com/kwcode/experts/Locator.java
- src/main/java/com/kwcode/ast/GraphRetriever.java

## 相关函数
- com.kwcode.experts.Locator.locate
- com.kwcode.ast.GraphRetriever.retrieve

## 代码内容
### src/main/java/com/kwcode/experts/Locator.java
```java
public LocatorResult locate(TaskContext ctx) {
    tools.setExpert("locator");
    List<String> relevantFiles = new ArrayList<>();
    ...
}
```

## 输出格式
请使用以下格式输出补丁：
```patch
--- a/file.java
+++ b/file.java
@@ ... @@
-原代码
+新代码
```

注意：文件路径必须是项目内的相对路径，禁止使用 /dev/null 或绝对路径。
```

重试时的 prompt 追加：

```
【重试指导】只修 Locator.java:45 的语法错误，修改≤5行，不触碰其他函数

上次生成的代码（有问题）：
public LocatorResult locate(TaskContext ctx) {
    if (ctx == null) return null;  // 这行有问题
    ...
}

请不要重复同样的错误。
```

## 10. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 补丁格式 | unified diff（--- a/ +++ b/） | 标准格式，易于解析和验证 |
| 语言推断 | 四级优先级（用户输入 > 文件扩展名 > 代码片段 > EnvProber） | 用户意图最可靠 |
| 重试策略 | 三阶段递进（正常→错误分析→最小化） | 逐步收紧约束，避免过度修改 |
| 路径约束 | 只允许相对路径 | 防止 LLM 生成破坏性路径 |
| 代码片段截取 | 2000 字符/文件（Locator 侧） | 平衡上下文与 Token 开销 |
| maxTokens | 4000 | 补丁通常不需要超长输出 |
| 错误处理 | 返回 `[LLM_ERROR]` 前缀 | 上游可检测并跳过，不抛异常 |
