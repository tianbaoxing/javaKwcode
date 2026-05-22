# Locator 定位器专家架构

> 源码：[Locator.java](../src/main/java/com/kwcode/experts/Locator.java)
> 调用入口：[PipelineOrchestrator.java#L403-406](../src/main/java/com/kwcode/core/orchestrator/PipelineOrchestrator.java)

## 1. 定位器在流水线中的角色

Locator 是专家流水线的**第一个执行节点**，负责从项目代码库中定位与用户任务相关的文件和函数。它的输出直接驱动下游 Generator 生成补丁。

```
Gate路由 → EnvProber探测 → GapDetector → [Locator] → Generator → Verifier → Reviewer
                                      ↑ 定位入口          ↓ 定位结果消费
```

**核心职责**：回答"代码应该改哪里"这个问题，将模糊的用户意图映射到具体的文件路径和函数签名。

## 2. 定位流程（六步）

Locator.locate() 执行以下六步：

```
┌─────────────────────────────────────────────────────┐
│ Step 1: 记忆加载 — 从PROJECT.md获取已知结构规律        │
│ Step 2: 调用图检索 — BM25召回 + 调用图N跳扩展          │
│ Step 3: 关键词后备 — 无调用图时按文件名关键词匹配        │
│ Step 4: 代码片段收集 — 读取定位到的文件内容             │
│ Step 5: Gap补充 — 将GapDetector的文件/函数加入结果      │
│ Step 6: 编辑位置构建 — 输出LocatorResult               │
└─────────────────────────────────────────────────────┘
```

### Step 1: 记忆加载

```java
String structureHints = memory.loadForLocator(ctx.projectRoot);
```

从 `.kaiwu/PROJECT.md` 的"已知结构规律"分区加载历史定位经验。Token 限制 750 字符。

记忆内容示例：
```markdown
已知结构规律：
- src/main/java/com/kwcode/experts/Locator.java
- src/main/java/com/kwcode/ast/GraphRetriever.java
- fn: com.kwcode.experts.Locator.locate
```

**意义**：避免每次从零开始定位，利用历史成功轨迹加速检索。

### Step 2: 调用图检索（主路径）

当 `GraphRetriever.hasGraph() == true` 时，执行两阶段检索：

#### Stage 1: BM25 关键词召回

将用户输入 + Gap 函数名 + Gap 错误信息拼接为查询：

```java
private String buildQuery(TaskContext ctx) {
    StringBuilder sb = new StringBuilder(ctx.userInput);
    if (ctx.gap != null) {
        if (!ctx.gap.functions().isEmpty())
            sb.append(" ").append(String.join(" ", ctx.gap.functions()));
        if (!ctx.gap.errorMsg().isEmpty())
            sb.append(" ").append(ctx.gap.errorMsg().substring(0, Math.min(100, ctx.gap.errorMsg().length())));
    }
    return sb.toString();
}
```

查询示例：
```
"修复Locator的locate方法报错" + "locate" + "NullPointerException at Locator.java:45"
```

BM25 从 SQLite 的 `nodes` 表中检索，每个节点包含 `search_text` 字段（函数名+注释+文档字符串），返回 Top-20 候选。

#### Stage 2: 调用图 N 跳扩展

从 BM25 候选节点出发，沿调用图双向扩展 2 跳：

```sql
-- 下游：frontier调用的函数
SELECT to_id FROM edges WHERE from_id IN (?) AND project_root=?

-- 上游：调用frontier的函数
SELECT from_id FROM edges WHERE to_id IN (?) AND project_root=?
```

**扩展逻辑**：
1. 种子节点 = BM25 Top-20 的 ID 集合
2. 第 1 跳：找到种子调用和被调用的函数
3. 第 2 跳：从第 1 跳结果继续扩展
4. 过滤噪音：跳过 `__init__`、`setUp`、`test_*` 等函数

最终合并 BM25 候选 + 扩展节点，按 BM25 分数降序排列，取 Top-10。

**性能约束**：总检索时间 < 3 秒（LOC-RED-5 红线）。

### Step 3: 关键词后备（无调用图时）

当项目没有调用图数据时，退化为文件名关键词匹配：

```java
private List<String> findFilesByKeywords(TaskContext ctx) { ... }
```

**关键词提取规则**：
1. 按空白/标点分割用户输入
2. 过滤停用词：`创建|实现|写|添加|修改|删除|a|an|the|to|for|in|of|with`
3. 保留长度 >= 2 的词元
4. 与项目源文件名（去扩展名）做子串匹配
5. 最多返回 5 个候选

**兜底策略**：如果关键词匹配无结果，返回项目前 3 个源文件。

**扫描范围**：
- 跳过目录：`node_modules`, `.git`, `__pycache__`, `venv`, `target`, `build`, `dist`, `.idea`, `.vscode`, `.kaiwu`
- 源码扩展名：`.java`, `.py`, `.go`, `.rs`, `.ts`, `.js`, `.tsx`, `.jsx`, `.kt`, `.scala`

### Step 4: 代码片段收集

```java
Map<String, String> snippets = collectSnippets(ctx, relevantFiles);
ctx.relevantCodeSnippets = snippets;
```

通过 `ToolGateway.readFile()` 读取定位到的文件内容，写入 `ctx.relevantCodeSnippets`：

- 最多读取 10 个文件
- 每个文件截取前 2000 字符（上下文压缩）
- 超长内容追加 `...(truncated)` 标记

**这些代码片段直接被 Generator 的 prompt 消费**，作为"## 代码内容"分区注入。

### Step 5: Gap 信息补充

```java
if (ctx.gap != null) {
    for (String f : ctx.gap.files()) {
        if (!relevantFiles.contains(f)) relevantFiles.add(f);
    }
    for (String fn : ctx.gap.functions()) {
        if (!relevantFunctions.contains(fn)) relevantFunctions.add(fn);
    }
}
```

将 GapDetector 检测到的文件和函数强制加入结果集，确保错误位置不被遗漏。

### Step 6: 输出结果

```java
return new TaskContext.LocatorResult(relevantFiles, relevantFunctions, editLocations);
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `relevantFiles` | `List<String>` | 相关文件相对路径列表 |
| `relevantFunctions` | `List<String>` | 相关函数全限定名列表 |
| `editLocations` | `List<String>` | 编辑位置（当前等于文件列表） |

## 3. 定位结果如何被消费

Locator 的输出通过 `TaskContext` 流向下游专家：

### Generator 消费方式

Generator.buildPrompt() 中直接使用 Locator 结果：

```java
// 相关文件列表
if (ctx.locatorOutput != null) {
    sb.append("## 相关文件\n");
    for (String f : ctx.locatorOutput.relevantFiles()) {
        sb.append("- ").append(f).append("\n");
    }
    // 相关函数列表
    if (!ctx.locatorOutput.relevantFunctions().isEmpty()) {
        sb.append("\n## 相关函数\n");
        for (String fn : ctx.locatorOutput.relevantFunctions()) {
            sb.append("- ").append(fn).append("\n");
        }
    }
}

// 代码片段（Locator在Step 4填充到ctx）
if (!ctx.relevantCodeSnippets.isEmpty()) {
    sb.append("\n## 代码内容\n");
    for (var entry : ctx.relevantCodeSnippets.entrySet()) {
        sb.append("### ").append(entry.getKey()).append("\n```\n")
          .append(entry.getValue()).append("\n```\n\n");
    }
}
```

### 记忆系统消费方式

任务成功后，ProjectMd.save() 将 Locator 的定位结果写入"已知结构规律"分区：

```java
for (String f : ctx.locatorOutput.relevantFiles().stream().limit(5).toList()) {
    String line = "- " + f;
    // 写入 PROJECT.md
}
for (String fn : ctx.locatorOutput.relevantFunctions().stream().limit(3).toList()) {
    String line = "- fn: " + fn;
    // 写入 PROJECT.md
}
```

形成**飞轮效应**：定位结果 → 写入记忆 → 下次定位加载记忆 → 更精准的检索。

## 4. GraphRetriever 底层机制

### 数据存储

调用图数据存储在 SQLite 数据库中，包含两张核心表：

**nodes 表**（函数/类节点）：

| 字段 | 说明 |
|------|------|
| `id` | 节点 ID |
| `name` | 函数/类名 |
| `qualified` | 全限定名 |
| `file_path` | 文件相对路径 |
| `start_line` / `end_line` | 行号范围 |
| `node_type` | 节点类型（function/class/method） |
| `search_text` | 搜索文本（名称+注释+文档字符串） |
| `task_count` / `success_count` | 飞轮统计 |

**edges 表**（调用关系）：

| 字段 | 说明 |
|------|------|
| `from_id` | 调用方节点 ID |
| `to_id` | 被调用方节点 ID |
| `project_root` | 项目根目录 |

### BM25 简化实现

当前使用关键词匹配替代完整 BM25 算法：

```java
private List<Map<String, Object>> bm25Recall(String query, int topK) {
    String[] queryTokens = query.toLowerCase().split("\\s+");
    // 对每个节点，统计查询词元在search_text中出现的次数作为分数
    for (var node : nodesCache) {
        String searchText = node.get("search_text").toLowerCase();
        double score = 0.0;
        for (String token : queryTokens) {
            if (searchText.contains(token)) score += 1.0;
        }
        // score > 0 的节点加入候选
    }
    // 按分数降序，返回 Top-K
}
```

BM25 索引缓存 5 分钟，避免重复构建。

### 飞轮数据更新

```java
public void updateTaskStats(List<Integer> nodeIds, boolean success) {
    // 成功：task_count+1, success_count+1
    // 失败：task_count+1
}
```

## 5. 定位策略决策树

```
用户输入 + Gap信息
       │
       ▼
  有调用图数据？──── 否 ───→ 关键词文件名匹配（Step 3）
       │                         │
       是                        │
       │                         │
       ▼                         │
  BM25召回Top-20                 │
       │                         │
       ▼                         │
  调用图2跳扩展                   │
       │                         │
       ▼                         │
  合并+排序+Top-10               │
       │                         │
       ◄─────────────────────────┘
       │
       ▼
  读取代码片段（Step 4）
       │
       ▼
  Gap文件/函数补充（Step 5）
       │
       ▼
  输出 LocatorResult（Step 6）
```

## 6. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 检索算法 | BM25 + 调用图扩展 | BM25 保证语义召回，调用图保证结构完整性 |
| 后备策略 | 文件名关键词匹配 | 零依赖，无需构建索引，适合小项目 |
| 代码片段截取 | 2000 字符/文件 | 平衡上下文丰富度与 Token 开销 |
| 记忆注入 | 只加载"已知结构规律"分区 | 避免注入无关信息干扰检索 |
| 噪音过滤 | 跳过 `__init__`/`setUp`/`test_*` | 这些函数几乎不是修改目标 |
| 性能红线 | 3 秒 | LOC-RED-5：定位不能成为流水线瓶颈 |
| BM25 缓存 | 5 分钟 | 避免每次定位都重建索引 |

## 7. 与 Python 版的差异

| 方面 | Python 版 | Java 版 |
|------|-----------|---------|
| BM25 实现 | rank-bm25 库 | 关键词匹配（待替换为 Lucene） |
| 调用图存储 | SQLite | SQLite（一致） |
| 记忆加载 | KAIWU.md 单文件 | .kaiwu/PROJECT.md 分区 |
| 文件扫描 | os.walk | Files.walkFileTree |
| 代码片段读取 | 直接文件 I/O | ToolGateway.readFile() |
