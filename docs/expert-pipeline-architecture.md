# 专家体系与编排流程

> 基于 `com.kwcode.experts`、`com.kwcode.core.orchestrator`、`com.kwcode.core.gate` 包源码分析。

## 一、五大元专家

元专家（Atomic Expert）是系统中最小的不可分割的能力单元，按职责分工，固定不变。

### 1. Locator（定位器）

**源码**：[Locator.java](../src/main/java/com/kwcode/experts/Locator.java)

**职责**：从用户输入和 Gap 信息中定位相关文件和函数，收集代码片段供 Generator 使用。

**核心算法**：BM25 + AST 调用图两阶段定位

```
用户描述 "修复登录失败的 bug"
  ├─► 阶段1：调用图检索（GraphRetriever.retrieve）→ Top-K 候选文件+函数
  └─► 阶段2：关键词文件搜索（findFilesByKeywords）→ 后备方案
```

**执行流程**：

1. 从 KaiwuMemory 加载已知结构规律（`memory.loadForLocator`）
2. 调用图检索（如果 `GraphRetriever` 可用且有图）
3. 关键词文件搜索（无调用图时的后备）
4. 读取相关代码片段（每个文件截取前 2000 字符）
5. 补充 Gap 信息中的文件/函数
6. 构建编辑位置列表

**工具权限**：`read_file`, `list_dir`

**输出**：`TaskContext.LocatorResult(relevantFiles, relevantFunctions, editLocations)`

**关键设计**：
- 零 LLM 调用，纯确定性定位
- 跳过 `node_modules`、`.git`、`target`、`build` 等目录
- 只扫描源码扩展名（`.java`, `.py`, `.go`, `.rs`, `.ts`, `.js` 等）
- 关键词提取时过滤停用词（"创建"、"实现"、"the"、"for" 等）

---

### 2. Generator（生成器）

**源码**：[Generator.java](../src/main/java/com/kwcode/experts/Generator.java)

**职责**：通过 LLM 生成补丁列表（patch 结构），不直接写文件。

**核心算法**：三阶段重试策略 + LLM 补丁生成

```
strategy=0: 正常描述 → 完整 prompt
strategy=1: 从错误出发 → 注入上次错误 + 反思分析
strategy=2: 最小化修改 → "只修改导致测试失败的最少代码"
```

**执行流程**：

1. 构建 prompt（根据重试策略注入不同内容）
2. 调用 LLM（`llmService.generateForExpert`）
3. 解析补丁（支持 ````patch` 代码块和逐行 diff 两种格式）
4. 提取说明文本

**工具权限**：`read_file`（仅读取，不写入）

**输出**：`TaskContext.GeneratorResult(patches, explanation)`

**补丁格式**：

```patch
--- a/src/main/java/com/example/UserService.java
+++ b/src/main/java/com/example/UserService.java
@@ ... @@
-原代码行
+新代码行
```

**关键设计**：
- 每次重试使用新鲜 prompt（RED-3：独立上下文）
- 语言自动推断：用户输入关键词 → 文件扩展名 → 项目语言
- 路径安全校验：禁止 `/dev/null`、绝对路径、含 `:` 的路径
- 补丁解析容错：先尝试 ````patch` 块解析，失败则逐行解析

---

### 3. Verifier（验证器）

**源码**：[Verifier.java](../src/main/java/com/kwcode/experts/Verifier.java)

**职责**：应用补丁 → 语法检查 → 运行测试 → 用 GapDetector 分析结果。

**执行流程**：

1. 应用补丁（`tools.applyPatch`，逐个应用）
2. 解析测试命令（优先 `ctx.confirmedTestCmd`，其次按语言默认命令）
3. 运行测试（`tools.runBash`，超时 120 秒）
4. 解析测试结果（支持 pytest、Maven/JUnit、通用格式）
5. 用 GapDetector 计算缺口类型
6. 返回验证结果

**工具权限**：`apply_patch`, `write_file`, `run_bash`, `read_file`

**输出**：`TaskContext.VerifierResult(passed, syntaxOk, testsPassed, testsTotal, errorType, errorDetail)`

**测试命令解析优先级**：

| 优先级 | 来源 | 示例 |
|--------|------|------|
| 1 | `ctx.confirmedTestCmd`（EnvProber 探测结果） | `mvn test -q` |
| 2 | 语言默认命令 | `python -m pytest -x -q` |
| 3 | 空字符串（跳过测试） | — |

**关键设计**：
- Java 项目无 `pom.xml` 时跳过 `mvn test`
- 测试输出截取前 500 字符（防止上下文爆炸）
- 支持多种测试框架输出格式（pytest、JUnit/Maven、通用）

---

### 4. DebugSubagent（调试子代理）

**源码**：[DebugSubagent.java](../src/main/java/com/kwcode/experts/DebugSubagent.java)

**职责**：分析 Verifier 失败输出，生成结构化调试信息。只在 Verifier 失败时被调用。

**核心算法**：正则提取 + LLM 诊断（可选）

```
Verifier 失败输出
  ├─► 正则提取错误位置（Python/Java/Go/Rust 格式）
  ├─► 正则提取错误类型（SyntaxError/ImportError/AssertionError 等）
  ├─► 检测 import 错误（ModuleNotFoundError）
  └─► LLM 诊断（如果 LLM 可用，生成结构化诊断）
```

**工具权限**：无（零副作用，只读分析）

**输出**：写入 `ctx.debugInfo`，供 Generator 重试时使用

**错误位置正则**：

| 语言 | 格式 | 正则 |
|------|------|------|
| Python | `File "xxx", line N` | `File "([^"]+)", line (\d+)` |
| Java | `at pkg.Class(File.java:N)` | `at ([\w.]+)\(([^)]+):(\d+)\)` |
| Go/Rust | `file.go:N:M:` | `(\S+\.\w+):(\d+):(\d+):` |

**错误类型分类**：

| 关键词 | 分类 |
|--------|------|
| `syntax` | syntax |
| `import`/`ModuleNotFound` | import |
| `assertion`/`assert` | assertion |
| `type`/`value`/`key`/`index`/`attribute` | runtime |
| 其他 | other |

**关键设计**：
- 零副作用：不修改任何文件
- LLM 诊断失败时静默降级（不影响主流程）
- 理论来源：Debug2Fix（Microsoft, ICML 2026）——弱模型 + 调试器 > 强模型裸跑

---

### 5. Reviewer（审查员）

**源码**：[Reviewer.java](../src/main/java/com/kwcode/experts/Reviewer.java)

**职责**：Verifier 通过后，用 LLM 对比用户原始意图和实际代码变更，判断是否真正完成任务。

**核心算法**：LLM 需求对齐审查

```
用户原始意图 + 代码变更摘要
  └─► LLM 对比 → {aligned: true/false, confidence: 0.0-1.0, gap: "未对齐描述"}
```

**执行流程**：

1. 从 `ctx.generatorOutput` 提取代码变更摘要（最多 3 个补丁）
2. 调用 LLM 审查（1 次调用，失败时乐观降级）
3. 解析 JSON 响应

**工具权限**：`read_file`

**输出**：`ReviewResult(aligned, confidence, gap)`

**关键设计**：
- 非阻塞：审查失败不回滚，只记录 gap 供用户参考
- 乐观降级：LLM 审查失败时返回 `aligned=true, confidence=0.0`
- 只做 1 次 LLM 调用，控制成本
- 变更摘要截取：每个补丁 modified 最多 300 字符，original 最多 200 字符

---

## 二、专家编排流程

**源码**：[PipelineOrchestrator.java](../src/main/java/com/kwcode/core/orchestrator/PipelineOrchestrator.java)

### 完整执行流程

```
用户输入
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Phase 0: EnvProber 环境探测（确定性，零 LLM）         │
│   ├─ 检测项目语言                                     │
│   ├─ 检测工具链是否就绪                               │
│   ├─ 解析测试命令                                     │
│   └─ 写入 ctx.confirmedTestCmd, ctx.projectLang      │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Phase 1: 前置测试 + GapDetector                      │
│   ├─ 运行初始测试获取基线                             │
│   ├─ GapDetector.compute → GapType                   │
│   ├─ Gap.scanSourceFiles → 补充文件路径               │
│   └─ Gap 驱动 expert_type 覆盖（confidence ≥ 0.7）   │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ MISSING_TOOLCHAIN? → 快速熔断，返回失败              │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Gate 路由 → expert_type + 专家序列                    │
│   └─ getSequence() → [locator, generator, verifier]  │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ 重试循环（最多 3 次，hard 任务 4 次）                  │
│                                                      │
│   for each step in sequence:                         │
│     locator   → ctx.locatorOutput                    │
│     generator → ctx.generatorOutput                  │
│     verifier  → ctx.verifierOutput                   │
│       ├─ passed → 返回成功                           │
│       └─ failed → 进入重试                           │
│                                                      │
│   重试前检查：                                        │
│     ├─ LLM 错误 → 熔断                              │
│     ├─ syntax 免费（≤2 次不计入配额）                 │
│     ├─ CognitiveGate 边际收益递减 → 熔断             │
│     ├─ environment 错误 → 快速熔断                   │
│     ├─ 同类错误连续 3 次 → 熔断                      │
│     └─ WinkMonitor 自修复检测                        │
│                                                      │
│   重试策略路由：                                      │
│     syntax    → [generator, verifier]                │
│     assertion → [generator, verifier]                │
│     import    → [import_fixer, verifier]             │
│     runtime   → [debug_subagent, generator, verifier]│
│     patch_apply → [locator, generator, verifier]     │
│                                                      │
│   RED-3: 刷新上下文，保留定位器输出                   │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Reviewer 审查（Verifier 通过后）                      │
│   └─ 非阻塞，只记录 gap                              │
└─────────────────────────────────────────────────────┘
  │
  ▼
结果：OrchestratorResult(success, context, error, elapsedMs)
```

### 重试策略详解

| 错误类型 | 重试序列 | 重试提示模板 |
|----------|----------|-------------|
| `syntax` | generator → verifier | 只修 {error_file}:{error_line} 的语法错误，修改≤5行 |
| `assertion` | generator → verifier | 测试期望：{error_message}。只改1个函数使断言通过，修改≤10行 |
| `import` | import_fixer → verifier | （自动修复） |
| `runtime` | debug_subagent → generator → verifier | （DebugSubagent 注入调试信息） |
| `patch_apply` | locator → generator → verifier | 必须先 read_file 读取文件最新内容 |
| `unknown` | generator → verifier | 只修改1个函数，修改≤15行 |

### 熔断机制

| 触发条件 | 行为 |
|----------|------|
| LLM 调用失败 | 立即停止，不重试 |
| `environment` 类型错误 | 立即停止（LLM 不可用，重试无意义） |
| `MISSING_TOOLCHAIN` | Phase 1 即熔断 |
| 同类错误连续 3 次 | 硬熔断 |
| CognitiveGate 边际收益递减 | 软熔断 |
| 任务超时 300 秒 | 看门狗强制终止 |

### 模型能力自适应

| 模型规模 | 最大重试 | 任务范围 | 搜索触发 |
|----------|---------|---------|---------|
| <10B（qwen3:8b） | 2 | ≤2 文件 | 第1次失败触发 |
| 10-30B（qwen3:14b） | 3 | ≤4 文件 | 第2次失败触发 |
| >30B（qwen3:72b） | 4 | ≤8 文件 | 自动处理 |

---

## 三、意图识别（Gate 路由）

**源码**：[Gate.java](../src/main/java/com/kwcode/core/gate/Gate.java)、[TaskGate.java](../src/main/java/com/kwcode/core/gate/TaskGate.java)

### 路由决策优先级

```
用户输入
  │
  ├─► 优先级1: 特殊任务快速路由（confidence=0.95）
  │     ├─ [图片:/[image:] → vision
  │     ├─ 聊天信号词（你好/hello/什么是） → chat
  │     └─ Office 信号词（.xlsx/.docx/excel） → office
  │
  ├─► 优先级2: Gap 路由（confidence ≥ 0.7）
  │     └─ GapDetector.GAP_TO_EXPERT_TYPE 映射
  │        ├─ NOT_IMPLEMENTED → locator_repair
  │        ├─ STUB_RETURNS_NONE → locator_repair
  │        ├─ LOGIC_ERROR → locator_repair
  │        ├─ MISSING_DEP → locator_repair
  │        ├─ SYNTAX_STRUCTURAL → locator_repair
  │        ├─ MISSING_TOOLCHAIN → env_fix
  │        ├─ NO_TEST → codegen
  │        └─ ENVIRONMENT → env_fix
  │
  ├─► 优先级3: 关键词匹配（confidence ≥ 0.75）
  │     ├─ 修复/fix/bug/报错/错误 → locator_repair
  │     ├─ 写一个/创建/生成/新建 → codegen
  │     ├─ 重构/优化/拆分 → refactor
  │     └─ 文档/注释/readme → doc
  │
  ├─► 优先级4: LLM 兜底二分类（confidence=0.55）
  │     └─ create → codegen | modify → locator_repair
  │
  └─► 默认: chat（confidence=0.3）
```

### 关键词置信度计算

| 匹配信号数 | 置信度 |
|-----------|--------|
| ≥2 个强信号 | 0.92 |
| 1 个强信号 | 0.75 |
| 0 个强信号 | 0.55 |

### 意图与 Gap 冲突消解

当用户意图（关键词分类）与 Gap 检测结果不一致时：

| Gap 置信度 | 决策 |
|-----------|------|
| ≥ 0.85 | 采纳 Gap 路由 |
| 0.5-0.85 | Gap 和用户意图一致则采纳 Gap，否则采纳用户意图 |
| < 0.5 | 采纳用户意图 |

### 专家序列映射

| expert_type | 序列 | 说明 |
|-------------|------|------|
| `locator_repair` | locator → generator → verifier | Bug 修复（最常用） |
| `codegen` | generator → verifier | 新代码生成 |
| `refactor` | locator → generator → verifier | 代码重构 |
| `doc` | locator → generator | 文档生成 |
| `office` | office | Office 文档处理 |
| `chat` | chat | 对话问答 |
| `vision` | vision | 图片理解 |

### TaskGate 高层封装

TaskGate 是 PipelineOrchestrator 调用的入口，封装了：

1. **Gate.classify** — 确定性优先路由
2. **configureModel** — 模型能力配置（tier/effectiveCtx）
3. **configureThink** — ThinkConfig 配置（推理预算）
4. **ContextPruner.prune** — 上下文裁剪

---

## 四、GapDetector 缺口检测

**源码**：[GapDetector.java](../src/main/java/com/kwcode/core/gap/GapDetector.java)

### GapType 优先级匹配

```
测试输出
  │
  ├─► 空输出 → NO_TEST
  ├─► 工具链缺失（go not found / javac not found） → MISSING_TOOLCHAIN
  ├─► 无项目结构（no POM / no package.json） → NO_TEST
  ├─► LLM 不可用（ChatClient / API key / Connection refused） → ENVIRONMENT
  ├─► Python ImportError / ModuleNotFoundError → MISSING_DEP
  ├─► Java 依赖缺失（ClassNotFoundException） → MISSING_DEP
  ├─► NotImplementedError / not implemented → NOT_IMPLEMENTED
  ├─► 存根返回 None（NoneType + has no attribute） → STUB_RETURNS_NONE
  ├─► Python SyntaxError / IndentationError → SYNTAX_STRUCTURAL
  ├─► Java 语法错误（compiler error） → SYNTAX_STRUCTURAL
  ├─► AssertionError / FAILED → LOGIC_ERROR
  ├─► Java 测试失败（Tests run + Failures > 0） → LOGIC_ERROR
  ├─► 全部通过 → NONE
  └─► 无匹配 → UNKNOWN
```

### 源文件扫描

当测试输出中的文件路径不完整时，`scanSourceFiles` 补充完整路径：

1. 构建项目文件索引（basename → 完整路径）
2. 解析文件路径（basename 匹配）
3. 在源文件中搜索函数定义（支持 Python/Go/Rust/Java/JS 模式）
4. 根据函数名反查文件

---

## 五、数据流总结

```
TaskContext（贯穿整个流水线的上下文对象）
  │
  ├─ 输入字段
  │    ├─ userInput          用户原始输入
  │    ├─ projectRoot        项目根目录
  │    ├─ projectLang        项目语言（EnvProber 检测）
  │    ├─ confirmedTestCmd   测试命令（EnvProber 检测）
  │    ├─ gateResult         Gate 路由结果
  │    ├─ expertSystemPrompt 专家系统提示词
  │    └─ kaiwuMemory        项目记忆
  │
  ├─ 中间字段（专家写入）
  │    ├─ locatorOutput           Locator 输出
  │    │    ├─ relevantFiles      相关文件列表
  │    │    ├─ relevantFunctions  相关函数列表
  │    │    └─ editLocations      编辑位置列表
  │    ├─ relevantCodeSnippets    代码片段（Locator 收集）
  │    ├─ generatorOutput         Generator 输出
  │    │    ├─ patches            补丁列表
  │    │    └─ explanation        说明文本
  │    ├─ verifierOutput          Verifier 输出
  │    │    ├─ passed             是否通过
  │    │    ├─ syntaxOk           语法是否正确
  │    │    ├─ testsPassed/Total  测试结果
  │    │    ├─ errorType          错误类型
  │    │    └─ errorDetail        错误详情
  │    ├─ debugInfo               DebugSubagent 输出
  │    └─ gap                     GapDetector 输出
  │
  ├─ 重试字段
  │    ├─ retryCount             重试次数
  │    ├─ retryStrategy          重试策略（0/1/2）
  │    ├─ retryHint              重试提示
  │    ├─ previousFailure        上次失败信息
  │    ├─ reflection             反思分析
  │    └─ llmError               LLM 错误（触发熔断）
  │
  └─ 模型字段
       ├─ modelTier              模型层级
       ├─ effectiveCtx           有效上下文长度
       └─ thinkConfig            Think 模式配置
```

---

## 六、设计原则

| 原则 | 说明 |
|------|------|
| RED-2 | 确定性流水线，每个 expert_type 对应固定序列 |
| RED-3 | 每次重试使用新鲜上下文，不继承上次 Generator 的输出 |
| RED-5 | 最多 3 次重试（hard 任务 4 次），syntax 免费 ≤2 次 |
| Deny-First | 每个专家只能调用白名单内的工具 |
| 确定性优先 | Gate/GapDetector/EnvProber 零 LLM，LLM 只在 Generator/DebugSubagent/Reviewer 中调用 |
| 乐观降级 | Reviewer/DebugSubagent 失败时静默降级，不阻塞主流程 |
