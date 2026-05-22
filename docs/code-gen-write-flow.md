# 代码生成与文件写入流程

> 源码入口：[PipelineOrchestrator.java](../src/main/java/com/kwcode/core/orchestrator/PipelineOrchestrator.java)

## 1. 整体流程概览

模型生成代码后，**不是立即写入文件**，而是先解析为结构化的 Patch 对象存在内存中，由后续的 Verifier 阶段统一负责"写入 + 验证"。

```
Generator（生成补丁，内存）→ PipelineOrchestrator（存入上下文）→ Verifier（写入磁盘 + 验证）
```

## 2. 四个阶段详解

### 阶段 1：Generator 调用 LLM 生成补丁（不写文件）

源码：[Generator.java](../src/main/java/com/kwcode/experts/Generator.java)

`Generator.generate()` 分四步执行：

```
┌──────────────────────────────────────────────────┐
│ Step 1: buildPrompt(ctx) — 构建提示词             │
│ Step 2: callLlm(ctx, prompt) — 调用LLM生成代码    │
│ Step 3: parsePatches(llmOutput) — 解析补丁        │
│ Step 4: extractExplanation(llmOutput) — 提取说明  │
└──────────────────────────────────────────────────┘
```

- `callLlm()` 通过 `LLMService.generateForExpert("generator", ...)` 调用模型
- `parsePatches()` 将 LLM 输出的 ` ```patch ``` ` 块解析为 `Patch(file, original, modified)` 列表
- 返回 `GeneratorResult(patches, explanation)` — **只存在内存中，不写文件**

类注释明确说明：**"只生成修改部分（patch结构），不直接写文件"**

### 阶段 2：PipelineOrchestrator 将结果存入上下文（不写文件）

源码：[PipelineOrchestrator.java#L411-416](../src/main/java/com/kwcode/core/orchestrator/PipelineOrchestrator.java)

```java
case "generator" -> {
    emit(onStatus, "generator", "生成代码修改...");
    ctx.generatorOutput = generator.generate(ctx);  // 补丁存入上下文，还没写文件
    if (ctx.generatorOutput.explanation() != null
        && ctx.generatorOutput.explanation().startsWith("[LLM_ERROR]")) {
        ctx.llmError = ctx.generatorOutput.explanation();
        return false;
    }
}
```

此时补丁仅存储在 `TaskContext.generatorOutput` 中，磁盘文件尚未被修改。

### 阶段 3：Verifier 应用补丁 — 真正写入文件的时刻

源码：[Verifier.java#L49-55](../src/main/java/com/kwcode/experts/Verifier.java)

```java
if (ctx.generatorOutput != null) {
    for (TaskContext.Patch patch : ctx.generatorOutput.patches()) {
        boolean ok = tools.applyPatch(patch.file(), patch.original(), patch.modified());
        if (ok) patchesApplied++;
        else patchesFailed++;
    }
}
```

调用链：

```
Verifier.verify()
  → ToolGateway.applyPatch()       // 权限检查 + 事件发射
    → ToolExecutor.applyPatch()     // 精确/模糊匹配 original，替换为 modified
      → ToolExecutor.writeFile()    // 最终写入磁盘
```

各层职责：

| 层级 | 类 | 职责 |
|------|-----|------|
| 权限层 | [ToolGateway](../src/main/java/com/kwcode/tools/ToolGateway.java) | 检查 verifier 是否有 `apply_patch` 权限；发射事件；标记文件为脏 |
| 匹配层 | [ToolExecutor](../src/main/java/com/kwcode/tools/ToolExecutor.java) | 精确匹配 `original` 文本 → 模糊匹配（空白归一化）→ 小文件全量覆写 |
| 写入层 | [ToolExecutor.writeFile()](../src/main/java/com/kwcode/tools/ToolExecutor.java) | 安全护栏（禁止写项目外、敏感文件备份）→ `Files.writeString()` 写入磁盘 |

### 阶段 4：Verifier 继续验证（测试）

写入文件后，Verifier 紧接着运行语法检查和测试来验证修改是否正确：

```java
// 运行测试
var result = tools.runBash(testCmd, ctx.projectRoot, 120);
// 分析测试结果
GapDetector.Gap gap = gapDetector.compute(testOutput, ctx.projectRoot);
boolean passed = gap.gapType() == GapDetector.GapType.NONE;
```

## 3. 完整时序图

```
Generator.generate()
  │  buildPrompt() → 组装提示词
  │  callLlm()     → LLM返回文本
  │  parsePatches() → 解析为Patch列表
  │  返回 GeneratorResult（内存中）
  ▼
PipelineOrchestrator.runSequence()
  │  ctx.generatorOutput = result（存入上下文，不写文件）
  ▼
Verifier.verify()              ← ★ 这里才真正写入文件
  │  遍历 patches
  │  tools.applyPatch(file, original, modified)
  │    → ToolGateway（权限检查：verifier有apply_patch权限）
  │      → ToolExecutor.applyPatch()
  │        → readFile() 读取当前文件内容
  │        → 精确匹配 original 文本
  │        → writeFile() 将替换后的内容写入磁盘 ★
  │  runBash(testCmd) 运行测试验证
  ▼
返回验证结果（passed/failed）
```

## 4. 权限模型

[ToolGateway.EXPERT_PERMISSIONS](../src/main/java/com/kwcode/tools/ToolGateway.java) 定义了每个专家可调用的工具白名单：

| 专家 | 可用工具 |
|------|----------|
| locator | read_file, list_dir |
| generator | read_file |
| **verifier** | **apply_patch**, **write_file**, run_bash, read_file |
| debugger | read_file, run_bash |
| reviewer | read_file |

Generator 只有 `read_file` 权限，无法写入文件；Verifier 拥有 `apply_patch` 和 `write_file` 权限，是唯一能将补丁写入磁盘的专家。

## 5. 设计意图

这种"生成与写入分离"的设计有以下好处：

1. **职责单一**：Generator 只负责生成，不关心文件系统状态
2. **安全可控**：写入操作集中在 Verifier，通过 ToolGateway 统一做权限检查和安全护栏
3. **可验证**：写入后立即运行测试，形成"写入→验证"的原子操作
4. **可重试**：如果验证失败，PipelineOrchestrator 可以重新生成补丁，不会留下半成品文件
