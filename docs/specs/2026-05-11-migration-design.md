# kwcode Python→Java 迁移设计文档

> 日期：2026-05-11
> 版本：v1.0
> 状态：待审查

---

## 1. 迁移目标

### 1.1 迁移目的

| 目的 | 说明 |
|------|------|
| **企业部署** | 公司要求Java技术栈，便于运维、监控、容器化 |
| **生态对接** | 接入Java生态的库/系统（Spring AI、Spring Boot等） |
| **学习实验** | 通过重写加深对架构的理解，探索Java最佳实践 |

### 1.2 功能对等原则

**核心功能对等**：Gate、Locator、Generator、Verifier、Orchestrator 核心流水线必须对等，其他模块可选/简化。

### 1.3 验证标准

采用 **A+B+C 综合验证**：

| 验证类型 | 方式 | 产出物 |
|----------|------|--------|
| **A. 静态检查** | Python源码每个类/方法都有Java对应，生成映射清单 | `mapping/Python_Java_Mapping.md` |
| **B. 动态验证** | 单元测试，Python和Java运行同样输入，输出必须一致 | `src/test/java/com/kwcode/.../` |
| **C. 行为对比** | 使用bench_tasks标准任务，Python和Java版分别执行，对比结果 | `validation/e2e_results/` |

---

## 2. 项目路径

| 项目 | 路径 | 说明 |
|------|------|------|
| Python源码（生产） | `E:\ai\aicode\traeHome\workHome\kwcode\kwcode` | 原项目，保持生产可用 |
| Java项目（开发） | `E:\ai\aicode\traeHome\workHome\kwcode\javaKwcode` | Java迁移目标项目 |

---

## 3. 编码规则（强制）

### 3.1 原始名称标注

所有Java类和方法**必须**标注对应的Python原始名称，便于溯源和验证。

**类级别**：在类Javadoc中标注 `@origin` 标签

```java
/**
 * 任务门控 - 负责任务分类和路由
 * @origin Python: gate.TaskGate
 */
public class TaskGate {
```

**方法级别**：在方法Javadoc中标注 `@origin` 标签

```java
/**
 * 对任务进行分类，返回任务类型
 * @origin Python: TaskGate.classify(task: dict) -> str
 */
public TaskType classify(Task task) {
```

### 3.2 中文注释要求

所有Java方法**必须**添加中文注释，说明方法用途和核心逻辑。

```java
/**
 * 执行两阶段定位：先BM25检索候选，再AST调用图精确定位
 * <p>
 * 第一阶段：使用BM25算法从项目文件中检索与任务描述最相关的Top-K文件；
 * 第二阶段：基于AST调用图，从候选文件中精确定位需要修改的函数/类。
 * </p>
 * @origin Python: locator.LocatorExpert.locate(task: dict) -> list[str]
 * @param task 任务描述，包含问题描述和上下文
 * @return 需要修改的文件路径列表，按相关性排序
 */
public List<String> locate(Task task) {
```

### 3.3 注释模板

```java
/**
 * [一句话中文描述方法用途]
 * <p>
 * [详细中文描述核心逻辑，2-5行]
 * </p>
 * @origin Python: [模块名].[类名].[方法名]([参数类型]) -> [返回类型]
 * @param [参数名] [中文说明]
 * @return [中文说明]
 */
```

### 3.4 规则检查

- **A验证**：扫描所有Java文件，检查 `@origin` 标签覆盖率 ≥ 95%
- **代码审查**：每个PR检查中文注释是否完整
- **CI gate**：缺少 `@origin` 或中文注释的方法不予合并

---

## 4. 运行环境

### 4.1 硬性要求

| 工具 | 路径 | 版本要求 |
|------|------|----------|
| JDK | `E:\ai\jdk-17.0.2` | 必须 17.x |
| Maven | `E:\ai\apache-maven-3.9.12\bin` | 必须 3.9.x |
| Node.js | `E:\ai\node-v20.19.4-win-x64` | 必须 20.x |

### 2.2 环境初始化（每次新终端必须执行）

```powershell
$env:JAVA_HOME = "E:\ai\jdk-17.0.2"
$env:Path = "E:\ai\jdk-17.0.2\bin;E:\ai\node-v20.19.4-win-x64;E:\ai\apache-maven-3.9.12\bin;" + $env:Path
```

> **原因**：系统默认Maven 3.6.x与Spring Boot 3.4.0不兼容

---

## 5. 核心类映射表

### 3.1 Core 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| orchestrator.py | PipelineOrchestrator | PipelineOrchestrator | com.kwcode.core.orchestrator |
| gate.py | TaskGate | TaskGate | com.kwcode.core.gate |
| planner.py | Planner | TaskPlanner | com.kwcode.core.planner |
| gap_detector.py | GapDetector, GapType | GapDetector, GapType | com.kwcode.core.gap |
| cognitive_gate.py | CognitiveGate | CognitiveGate | com.kwcode.core.cognitive |
| checkpoint.py | Checkpoint | CheckpointManager, Snapshot | com.kwcode.core.checkpoint |
| execution_state.py | ExecutionStateTracker | ExecutionState, ExecutionTracker | com.kwcode.core.execution |
| context.py | Context | ContextManager | com.kwcode.core.context |
| context_pruner.py | ContextPruner | ContextPruner | com.kwcode.core.context |
| env_prober.py | EnvProber | EnvProber, EnvProbeResult | com.kwcode.core.env |
| task_compiler.py | TaskCompiler | TaskCompiler | com.kwcode.core.gate |
| task_planner.py | TaskPlanner | TaskPlanner | com.kwcode.core.planner |
| upstream_manifest.py | UpstreamManifest | UpstreamManifest | com.kwcode.core.upstream |
| wink.py | WinkMonitor | WinkMonitor, WinkCorrection | com.kwcode.core.wink |
| event_bus.py | EventBus | EventBus | com.kwcode.core |
| execution_trace.py | ExecutionTrace | ExecutionTrace | com.kwcode.core.execution |
| model_capability.py | ModelCapability | ModelCapability | com.kwcode.core |
| network.py | NetworkChecker | NetworkChecker | com.kwcode.core |
| sysinfo.py | SystemInfo | SystemInfo | com.kwcode.core |
| test_parser.py | TestParser | TestParser | com.kwcode.core |
| think_config.py | ThinkConfig | ThinkConfig | com.kwcode.core |
| usage_finder.py | UsageFinder | UsageFinder | com.kwcode.core |
| kwcode_md.py | KWCodeMD | KWCodeMD | com.kwcode.core |

### 3.2 Experts 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| locator.py | LocatorExpert | LocatorExpert | com.kwcode.experts.locator |
| generator.py | GeneratorExpert | GeneratorExpert | com.kwcode.experts.generator |
| verifier.py | VerifierExpert | VerifierExpert | com.kwcode.experts.verifier |
| search_augmentor.py | SearchAugmentor | SearchAugmentor | com.kwcode.experts.search |
| reviewer.py | Reviewer | Reviewer | com.kwcode.experts.reviewer |
| debug_subagent.py | DebugSubagent | DebugSubagent | com.kwcode.experts.debug |
| search_subagent.py | SearchSubagent | SearchSubagent | com.kwcode.experts.search |
| chat_expert.py | ChatExpert | ChatExpert | com.kwcode.experts.chat |
| vision_expert.py | VisionExpert | VisionExpert | com.kwcode.experts.vision |
| office_handler.py | OfficeHandler | OfficeHandler | com.kwcode.experts.office |
| consistency_checker.py | ConsistencyChecker | ConsistencyChecker | com.kwcode.experts |
| __init__.py | Expert (基类) | Expert (接口) | com.kwcode.experts |

### 3.3 AST Engine 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| parser.py | Parser | Parser | com.kwcode.ast |
| call_graph.py | CallGraph | CallGraph, CallGraphNode | com.kwcode.ast |
| locator.py | ASTLocator | ASTLocator | com.kwcode.ast |
| graph_builder.py | GraphBuilder | GraphBuilder | com.kwcode.ast |
| graph_retriever.py | GraphRetriever | GraphRetriever | com.kwcode.ast |
| language_detector.py | LanguageDetector | LanguageDetector, Language(Enum) | com.kwcode.ast |
| ast_grep_engine.py | AstGrepEngine | AstGrepEngine | com.kwcode.ast |

### 3.4 Flywheel 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| expert_generator.py | ExpertGenerator | ExpertGenerator | com.kwcode.flywheel |
| ab_tester.py | ABTester | ABTester | com.kwcode.flywheel |
| trajectory_collector.py | TrajectoryCollector | TrajectoryCollector | com.kwcode.flywheel |
| pattern_detector.py | PatternDetector | PatternDetector | com.kwcode.flywheel |
| lifecycle_manager.py | LifecycleManager | LifecycleManager | com.kwcode.flywheel |
| prompt_optimizer.py | PromptOptimizer | PromptOptimizer | com.kwcode.flywheel |
| skill_drafter.py | SkillDrafter | SkillDrafter | com.kwcode.flywheel |
| strategy_stats.py | StrategyStats | StrategyStats | com.kwcode.flywheel |
| user_pattern_memory.py | UserPatternMemory | UserPatternMemory | com.kwcode.flywheel |

### 3.5 Registry 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| expert_registry.py | ExpertRegistry | ExpertRegistry | com.kwcode.registry |
| expert_loader.py | ExpertLoader | ExpertLoader | com.kwcode.registry |
| expert_packager.py | ExpertPackager | ExpertPackager | com.kwcode.registry |

### 3.6 Memory 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| project_md.py | ProjectMemory | ProjectMd | com.kwcode.memory |
| pattern_md.py | PatternMemory | PatternMd | com.kwcode.memory |
| expert_md.py | ExpertMemory | ExpertMd | com.kwcode.memory |
| kaiwu_md.py | KaiwuMemory | KaiwuMemory | com.kwcode.memory |
| session_md.py | SessionMemory | SessionMd | com.kwcode.memory |

### 3.7 Search 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| duckduckgo.py | DuckDuckGoClient | DuckDuckGoSearch | com.kwcode.search |
| content_fetcher.py | ContentFetcher | ContentFetcher | com.kwcode.search |
| context_compressor.py | ContextCompressor | ContextCompressor | com.kwcode.search |
| quality_filter.py | QualityFilter | QualityFilter | com.kwcode.search |
| intent_classifier.py | IntentClassifier | IntentClassifier | com.kwcode.search |
| query_generator.py | QueryGenerator | QueryGenerator | com.kwcode.search |
| reranker.py | Reranker | Reranker | com.kwcode.search |
| extraction_pipeline.py | ExtractionPipeline | ExtractionPipeline | com.kwcode.search |
| search_router.py | SearchRouter | SearchRouter | com.kwcode.search |
| pced_lite.py | PCEDLite | PCEDLite | com.kwcode.search |

### 3.8 LLM 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| llama_backend.py | LlamaBackend | LLMService, ModelRouter, ProviderConfig | com.kwcode.llm |
| (Spring AI) | - | ChatClient, PromptTemplate | com.kwcode.llm |

### 3.9 Tools 模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| executor.py | ToolExecutor | ToolGateway | com.kwcode.tools |
| tool_gateway.py | ToolGateway | ToolGateway | com.kwcode.tools |
| ast_utils.py | ASTUtils | AstUtils | com.kwcode.tools |
| hashline.py | HashLine | Hashline | com.kwcode.tools |
| import_fixer.py | ImportFixer | ImportFixer | com.kwcode.tools |
| ssh_session.py | SSHSession | SSHSession | com.kwcode.tools |

### 3.10 其他模块

| Python 文件 | Python 类/核心 | Java 类 | 包路径 |
|-------------|---------------|---------|--------|
| audit/detailed_logger.py | DetailedLogger | DetailedLogger | com.kwcode.audit |
| audit/logger.py | AuditLogger | AuditLogger | com.kwcode.audit |
| knowledge/doc_reader.py | DocReader | DocReader | com.kwcode.knowledge |
| mcp/router_mcp.py | RouterMCP | RouterMCP | com.kwcode.mcp |
| notification/flywheel_notifier.py | FlywheelNotifier | FlywheelNotifier | com.kwcode.notification |
| stats/value_tracker.py | ValueTracker | ValueTracker | com.kwcode.stats |
| telemetry/client.py | TelemetryClient | TelemetryClient | com.kwcode.telemetry |
| server/app.py | FastAPI App | ServerController | com.kwcode.server |
| server/models.py | API Models | Models | com.kwcode.server |
| cli/main.py | CLI App | CliMain | com.kwcode.cli |

---

## 6. 三阶段迁移计划

### Phase 1 — 核心引擎（4-6周）

**目标**：迁移无依赖的基础模块，建立验证基础设施

| 序号 | 模块 | 文件数 | 原因 |
|------|------|--------|------|
| 1 | AST Engine | 7 | 其他模块都依赖AST解析，独立性强 |
| 2 | Core 基座 | 5 | 编排器的基础设施，无外部依赖 |
| 3 | Memory | 5 | 数据模型层，结构清晰，依赖简单 |
| 4 | Registry | 3 | 专家系统的基础设施 |
| 5 | Tools | 6 | 内置工具，被专家模块调用 |
| 6 | Audit | 2 | 日志审计，独立性强 |

**完成标志**：
- A验证：AST Engine类/方法映射表完整
- B验证：AST Engine单元测试通过，覆盖率≥80%
- C验证：bench_tasks中AST相关任务执行正确

### Phase 2 — 核心流水线（6-8周）

**目标**：核心流水线100%逻辑对等

| 序号 | 模块 | 文件数 | 原因 |
|------|------|--------|------|
| 5 | Gate | 4 | 任务分类入口，先打通流程 |
| 6 | Locator Expert | 2 | 定位是重试策略的前置条件 |
| 7 | Generator Expert | 2 | 代码生成是核心能力 |
| 8 | Verifier Expert | 1 | 验证通过才算成功 |
| 9 | Orchestrator | 4 | 管道编排器，整合所有专家 |
| 10 | LLM (Spring AI) | 3 | LLM调用，OpenRouter默认 |
| 11 | Search | 10 | 搜索增强，支持定位 |

**完成标志**：
- 核心流水线完整运行
- 15个bench_tasks基准任务通过
- Python/Java输出对比一致

### Phase 3 — 扩展模块（8-12周，可并行）

**目标**：扩展功能，企业级部署

| 序号 | 模块 | 文件数 | 说明 |
|------|------|--------|------|
| 12 | Flywheel | 9 | 专家飞轮系统，可简化 |
| 13 | Experts 扩展 | 5 | DebugSubagent, ChatExpert等 |
| 14 | CLI | 9 | 命令行入口 |
| 15 | Server | 3 | API服务 |
| 16 | 其他 | 5 | Knowledge, MCP, Notification等 |

**完成标志**：
- 所有模块有Java实现
- 完整验证通过
- 可选：部署到生产环境

---

## 7. Java 项目结构

```
E:\ai\aicode\traeHome\workHome\kwcode\javaKwcode\
│
├── pom.xml
│
├── src/
│   ├── main/
│   │   ├── java/com/kwcode/
│   │   │   ├── KwcodeApplication.java
│   │   │   │
│   │   │   ├── core/
│   │   │   │   ├── orchestrator/PipelineOrchestrator.java
│   │   │   │   ├── gate/TaskGate.java, TaskCompiler.java
│   │   │   │   ├── planner/TaskPlanner.java
│   │   │   │   ├── gap/GapDetector.java, GapType.java
│   │   │   │   ├── cognitive/CognitiveGate.java
│   │   │   │   ├── checkpoint/CheckpointManager.java, Snapshot.java
│   │   │   │   ├── execution/ExecutionState.java, ExecutionTracker.java
│   │   │   │   ├── context/ContextManager.java, ContextPruner.java
│   │   │   │   ├── env/EnvProber.java, EnvProbeResult.java
│   │   │   │   ├── wink/WinkMonitor.java, WinkCorrection.java
│   │   │   │   ├── upstream/UpstreamManifest.java
│   │   │   │   ├── EventBus.java
│   │   │   │   ├── ModelCapability.java
│   │   │   │   ├── NetworkChecker.java
│   │   │   │   ├── SystemInfo.java
│   │   │   │   ├── TestParser.java
│   │   │   │   ├── ThinkConfig.java
│   │   │   │   ├── UsageFinder.java
│   │   │   │   └── KWCodeMD.java
│   │   │   │
│   │   │   ├── experts/
│   │   │   │   ├── Expert.java (接口)
│   │   │   │   ├── locator/LocatorExpert.java, BM25Locator.java, ASTLocator.java
│   │   │   │   ├── generator/GeneratorExpert.java, CodeGenerator.java
│   │   │   │   ├── verifier/VerifierExpert.java, TestRunner.java
│   │   │   │   ├── search/SearchAugmentor.java, QueryBuilder.java
│   │   │   │   ├── reviewer/Reviewer.java
│   │   │   │   ├── debug/DebugSubagent.java
│   │   │   │   ├── search/SearchSubagent.java
│   │   │   │   ├── chat/ChatExpert.java
│   │   │   │   ├── vision/VisionExpert.java
│   │   │   │   ├── office/OfficeHandler.java
│   │   │   │   └── ConsistencyChecker.java
│   │   │   │
│   │   │   ├── ast/
│   │   │   │   ├── Parser.java
│   │   │   │   ├── CallGraph.java, CallGraphNode.java
│   │   │   │   ├── ASTLocator.java
│   │   │   │   ├── CallGraphBuilder.java
│   │   │   │   ├── GraphRetriever.java
│   │   │   │   ├── LanguageDetector.java
│   │   │   │   ├── Language.java (枚举)
│   │   │   │   └── AstGrepEngine.java
│   │   │   │
│   │   │   ├── memory/
│   │   │   │   ├── ProjectMd.java
│   │   │   │   ├── PatternMd.java
│   │   │   │   ├── ExpertMd.java
│   │   │   │   ├── KaiwuMemory.java
│   │   │   │   └── SessionMd.java
│   │   │   │
│   │   │   ├── flywheel/
│   │   │   │   ├── FlywheelManager.java
│   │   │   │   ├── ExpertGenerator.java
│   │   │   │   ├── ABTester.java
│   │   │   │   ├── TrajectoryCollector.java
│   │   │   │   ├── PatternDetector.java
│   │   │   │   ├── LifecycleManager.java
│   │   │   │   ├── PromptOptimizer.java
│   │   │   │   ├── SkillDrafter.java
│   │   │   │   ├── StrategyStats.java
│   │   │   │   └── UserPatternMemory.java
│   │   │   │
│   │   │   ├── registry/
│   │   │   │   ├── ExpertRegistry.java
│   │   │   │   ├── ExpertLoader.java
│   │   │   │   └── ExpertPackager.java
│   │   │   │
│   │   │   ├── search/
│   │   │   │   ├── SearchRouter.java
│   │   │   │   ├── DuckDuckGoSearch.java
│   │   │   │   ├── ContentFetcher.java
│   │   │   │   ├── IntentClassifier.java
│   │   │   │   ├── QueryGenerator.java
│   │   │   │   ├── QualityFilter.java
│   │   │   │   ├── Reranker.java
│   │   │   │   ├── ExtractionPipeline.java
│   │   │   │   ├── ContextCompressor.java
│   │   │   │   └── PCEDLite.java
│   │   │   │
│   │   │   ├── llm/
│   │   │   │   ├── LLMService.java
│   │   │   │   ├── ModelRouter.java
│   │   │   │   ├── ProviderConfig.java
│   │   │   │   └── dto/ChatRequest.java, ChatResponse.java
│   │   │   │
│   │   │   ├── tools/
│   │   │   │   ├── ToolGateway.java
│   │   │   │   ├── FileReader.java
│   │   │   │   ├── FileWriter.java
│   │   │   │   ├── BashExecutor.java
│   │   │   │   ├── DirectoryLister.java
│   │   │   │   ├── GitHelper.java
│   │   │   │   ├── AstUtils.java
│   │   │   │   ├── Hashline.java
│   │   │   │   ├── ImportFixer.java
│   │   │   │   └── SSHSession.java
│   │   │   │
│   │   │   ├── audit/
│   │   │   │   ├── DetailedLogger.java
│   │   │   │   └── AuditLogger.java
│   │   │   │
│   │   │   ├── knowledge/
│   │   │   │   └── DocReader.java
│   │   │   │
│   │   │   ├── mcp/
│   │   │   │   └── RouterMCP.java
│   │   │   │
│   │   │   ├── notification/
│   │   │   │   └── FlywheelNotifier.java
│   │   │   │
│   │   │   ├── stats/
│   │   │   │   └── ValueTracker.java
│   │   │   │
│   │   │   ├── telemetry/
│   │   │   │   └── TelemetryClient.java
│   │   │   │
│   │   │   ├── model/
│   │   │   │   ├── Task.java
│   │   │   │   ├── ExpertRequest.java
│   │   │   │   ├── ExpertResponse.java
│   │   │   │   ├── Gap.java
│   │   │   │   └── ValidationResult.java
│   │   │   │
│   │   │   ├── cli/CliMain.java
│   │   │   └── server/ServerController.java
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-ollama.yml
│   │       ├── experts/
│   │       └── prompts/
│   │
│   └── test/java/com/kwcode/
│       ├── core/
│       ├── experts/
│       ├── ast/
│       ├── memory/
│       ├── flywheel/
│       ├── search/
│       ├── llm/
│       ├── tools/
│       └── validation/E2ETest.java
│
├── mapping/
│   ├── Python_Java_Mapping.md
│   └── module_mapping/
│
├── validation/
│   ├── e2e_results/
│   └── benchmarks/
│
├── docs/specs/
│   └── 2026-05-11-migration-design.md
│
├── scripts/
│   ├── generate_mapping.py
│   ├── run_comparison.py
│   └── validate_all.sh
│
└── README.md
```

---

## 8. 技术栈

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 17 LTS | 长期支持，企业首选 |
| **构建** | Maven | 3.9.x | 企业标准，稳定 |
| **框架** | Spring Boot | 3.4.x | 企业级应用框架 |
| **AI框架** | Spring AI | 1.0.x | LLM调用统一接口 |
| **LLM默认** | OpenRouter | - | 兼容OpenAI API格式 |
| **LLM可选** | Ollama | - | 本地部署，profile切换 |
| **AST解析** | JavaParser / ANTLR | - | Java源码解析 + 多语言支持 |
| **搜索** | HttpClient | - | DuckDuckGo等搜索API |
| **存储** | SQLite JDBC | - | 兼容Python版调用图存储 |
| **测试** | JUnit 5 + AssertJ | - | 单元测试 + 断言 |
| **序列化** | Jackson | - | JSON处理 |

### 8.1 Spring AI + OpenRouter 配置

```yaml
spring:
  ai:
    openai:
      base-url: https://openrouter.ai/api
      api-key: ${OPENROUTER_API_KEY}
      chat:
        options:
          model: deepseek/deepseek-chat-v3-0324
          temperature: 0.1

kwcode:
  llm:
    default-provider: openrouter
    model-router:
      locator:
        openrouter: deepseek/deepseek-chat-v3-0324
      generator:
        openrouter: anthropic/claude-sonnet-4
      verifier:
        openrouter: deepseek/deepseek-chat-v3-0324
```

### 8.2 LLM 模块架构

```java
@Service
public class LLMService {

    @Qualifier("openRouterChatClient")
    private final ChatClient openRouterClient;

    @Qualifier("ollamaChatClient")
    private final ChatClient ollamaClient;

    private final ModelRouter modelRouter;

    public String chat(TaskType taskType, String prompt) {
        ChatClient client = modelRouter.getProvider().equals("ollama")
            ? ollamaClient : openRouterClient;
        String model = modelRouter.getModelForTask(taskType);

        return client.prompt()
            .user(prompt)
            .options(ChatOptionsBuilder.builder().withModel(model).build())
            .call()
            .content();
    }

    public Flux<String> chatStream(TaskType taskType, String prompt) {
        return chatClient.prompt()
            .user(prompt)
            .stream()
            .content();
    }
}
```

---

## 9. 验证体系详细设计

### 9.1 A验证 — 静态映射检查

**流程**：
1. 自动扫描Python源码，提取所有类和public方法
2. 扫描Java源码，提取所有类和public方法
3. 按模块生成映射表
4. 标记状态：✅已验证 | ⏳待迁移 | ❌有问题
5. **检查 `@origin` 标签覆盖率 ≥ 95%**
6. **检查中文注释完整性**

**映射表格式**：

```markdown
# AST Engine 模块映射表

| Python 类 | Python 方法 | Java 类 | Java 方法 | 状态 | 备注 |
|-----------|------------|---------|-----------|------|------|
| Parser    | parseFile  | Parser  | parseFile | ✅   | OK   |
| Parser    | parseCode  | Parser  | parseCode | ⏳   | 待迁移 |
```

### 9.2 B验证 — 动态单元测试

**规则**：
- 每个Java public方法至少一个测试用例
- 测试用例来源：Python原有测试 + 新增边界测试
- 核心模块覆盖率 ≥80%
- 失败时两个版本同时运行，打印差异

### 9.3 C验证 — 行为对比

**规则**：
- 使用15个bench_tasks（t01-t21）
- Python/Java必须同时达到"测试通过"状态
- 修复后的代码逻辑必须等价（不要求完全相同）
- 记录每次任务的：输入、输出、耗时、差异

**对比流程**：
```
输入任务 → Python版执行 → Java版执行
    ↓            ↓              ↓
  同输入    Python结果    Java结果
    ↓            ↓              ↓
         行为一致性分析
              ↓
       ✅一致 / ❌差异报告
```

### 9.4 验证时序

```
迁移模块 → A.生成映射表 → B.运行单元测试 → C.行为对比 → 阶段完成
```

---

## 10. 双轨并行策略

```
Python版（生产）          Java版（开发中）
     │                        │
     │  共享测试用例            │
     │◄──────────────────────►│
     │                        │
     │  共享bench_tasks       │
     │◄──────────────────────►│
     │                        │
     ▼                        ▼
 验证层A+B+C             验证层A+B+C
     │                        │
     └────── 对比结果 ─────────┘
              │
              ▼
      验证通过 → Java接管生产
```

**原则**：
1. Python版保持生产可用，继续处理日常任务
2. Java版并行开发，每次迁移后立即验证
3. 两版共享同一套测试用例和任务集
4. 验证通过后，Java版逐步接管生产

---

## 11. 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| Python动态特性难以映射到Java静态类型 | 使用接口/抽象类模拟鸭子类型；Optional替代None |
| tree-sitter Python绑定 → Java绑定 | 改用ANTLR或JavaParser；必要时JNI调用 |
| rank-bm25 Python库 → Java实现 | 使用Lucene BM25或手写实现 |
| Python异步 → Java并发 | CompletableFuture / Virtual Threads (Java 21) |
| SQLite调用图存储兼容 | SQLite JDBC，保持相同表结构 |
| trafilatura内容提取 → Java | 改用Jsoup + 自定义提取逻辑 |
| 工作量超预期 | Phase优先级严格，Phase 1完成再进Phase 2 |

---

## 附录A：Python→Java 关键映射规则

| Python 概念 | Java 对应 |
|-------------|-----------|
| `dict` | `Map<String, Object>` 或 DTO |
| `list` | `List<T>` |
| `tuple` | `Record` 或自定义类 |
| `Optional` / `None` | `Optional<T>` |
| `dataclass` | `Record` (Java 16+) 或 Lombok `@Data` |
| `Enum` | `enum` |
| `__init__` | 构造器 |
| `@staticmethod` | `static` 方法 |
| `@classmethod` | 工厂方法 |
| `*args` | `Object... args` 或显式参数 |
| `**kwargs` | `Map<String, Object>` 或 Builder模式 |
| `try/except` | `try/catch` |
| `with` 上下文管理器 | `try-with-resources` (实现 `AutoCloseable`) |
| `@property` | getter 方法 |
| `abc.ABC` | `abstract class` 或 `interface` |
| 鸭子类型 | 接口 + 默认方法 |
| `pathlib.Path` | `java.nio.file.Path` |
| `subprocess` | `ProcessBuilder` |
| `json.loads/dumps` | Jackson `ObjectMapper` |

## 附录B：核心流水线关键常量

| 常量 | Python值 | Java对应 |
|------|----------|----------|
| MAX_RETRIES | 3 | `static final int MAX_RETRIES = 3` |
| HARD_RETRY_LIMIT | 4 | `static final int HARD_RETRY_LIMIT = 4` |
| GAP_CONFIDENCE_THRESHOLD | 0.7 | `static final double GAP_CONFIDENCE_THRESHOLD = 0.7` |
| SYNTAX_FREE_RETRIES | 2 | `static final int SYNTAX_FREE_RETRIES = 2` |
| SAME_ERROR_STREAK | 3 | `static final int SAME_ERROR_STREAK = 3` |
