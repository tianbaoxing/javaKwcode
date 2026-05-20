# Python→Java 映射表

> 自动化CI门禁：@origin覆盖率必须100%
> 最后更新：2026-05-11

## AST Engine (`kaiwu/ast/` → `com.kwcode.ast`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| language.py | Language | Language | com.kwcode.ast |
| language.py | detect_language() | LanguageDetector.detect() | com.kwcode.ast |
| parser.py | parse_file() | Parser.parse() | com.kwcode.ast |
| call_graph.py | CallGraph | CallGraphBuilder | com.kwcode.ast |
| call_graph.py | build_call_graph() | CallGraphBuilder.build() | com.kwcode.ast |
| locator.py | locate_symbol() | ASTLocator.locate() | com.kwcode.ast |
| graph_builder.py | GraphBuilder | GraphBuilder | com.kwcode.ast |
| graph_retriever.py | GraphRetriever | GraphRetriever | com.kwcode.ast |
| ast_grep_engine.py | AstGrepEngine | AstGrepEngine | com.kwcode.ast |

## Core基座 (`kaiwu/core/` → `com.kwcode.core.*`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| orchestrator.py | PipelineOrchestrator | PipelineOrchestrator | com.kwcode.core.orchestrator |
| orchestrator.py | PipelineOrchestrator.run() | PipelineOrchestrator.run() | com.kwcode.core.orchestrator |
| gate.py | Gate | Gate | com.kwcode.core.gate |
| gate.py | Gate.classify() | Gate.classify() | com.kwcode.core.gate |
| planner.py | — | (内联到Orchestrator) | — |
| gap_detector.py | GapDetector | GapDetector | com.kwcode.core.gap |
| gap_detector.py | GapDetector.Gap | GapDetector.Gap | com.kwcode.core.gap |
| cognitive_gate.py | CognitiveGate | CognitiveGate | com.kwcode.core.cognitive |
| cognitive_gate.py | CognitiveGate.shouldStop() | CognitiveGate.shouldStop() | com.kwcode.core.cognitive |
| checkpoint.py | CheckpointManager | CheckpointManager | com.kwcode.core.checkpoint |
| execution_state.py | ExecutionTracker | ExecutionTracker | com.kwcode.core.execution |
| context_pruner.py | — | TaskContext | com.kwcode.core.context |
| env_prober.py | EnvProber | EnvProber | com.kwcode.core.env |
| env_prober.py | EnvProber.probe_and_fix() | EnvProber.probeAndFix() | com.kwcode.core.env |
| task_compiler.py | — | (内联到Orchestrator) | — |
| task_planner.py | — | (内联到Orchestrator) | — |
| wink.py | WinkMonitor | WinkMonitor | com.kwcode.core.wink |
| wink.py | WinkMonitor.check() | WinkMonitor.check() | com.kwcode.core.wink |
| event_bus.py | EventBus | EventBus | com.kwcode.core |
| task_context.py | TaskContext | TaskContext | com.kwcode.core.context |

## Experts (`kaiwu/experts/` → `com.kwcode.experts`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| locator.py | Locator | Locator | com.kwcode.experts |
| locator.py | Locator.locate() | Locator.locate() | com.kwcode.experts |
| generator.py | Generator | Generator | com.kwcode.experts |
| generator.py | Generator.generate() | Generator.generate() | com.kwcode.experts |
| verifier.py | Verifier | Verifier | com.kwcode.experts |
| verifier.py | Verifier.verify() | Verifier.verify() | com.kwcode.experts |
| search_augmentor.py | SearchAugmentor | SearchAugmentor | com.kwcode.experts |
| reviewer.py | Reviewer | Reviewer | com.kwcode.experts |
| reviewer.py | Reviewer.review() | Reviewer.review() | com.kwcode.experts |
| debug_subagent.py | — | (内联到Orchestrator重试逻辑) | — |
| search_subagent.py | — | SearchAugmentor | com.kwcode.experts |
| chat_expert.py | — | (内联到Orchestrator) | — |
| vision_expert.py | — | (Phase 3+) | — |
| office_handler.py | — | (Phase 3+) | — |

## Flywheel (`kaiwu/flywheel/` → `com.kwcode.flywheel`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| expert_generator.py | ExpertGeneratorFlywheel | ExpertGenerator | com.kwcode.flywheel |
| ab_tester.py | ABTester | ABTester | com.kwcode.flywheel |
| trajectory_collector.py | TrajectoryCollector | TrajectoryCollector | com.kwcode.flywheel |
| pattern_detector.py | PatternDetector | PatternDetector | com.kwcode.flywheel |
| lifecycle_manager.py | LifecycleManager | LifecycleManager | com.kwcode.flywheel |
| prompt_optimizer.py | PromptOptimizer | PromptOptimizer | com.kwcode.flywheel |
| skill_drafter.py | SkillDrafter | SkillDrafter | com.kwcode.flywheel |
| strategy_stats.py | StrategyStats | StrategyStats | com.kwcode.flywheel |
| user_pattern_memory.py | UserPatternMemory | UserPatternMemory | com.kwcode.flywheel |

## Registry (`kaiwu/registry/` → `com.kwcode.registry`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| expert_registry.py | ExpertRegistry | ExpertRegistry | com.kwcode.registry |
| expert_loader.py | ExpertLoader | ExpertLoader | com.kwcode.registry |
| expert_packager.py | ExpertPackager | ExpertPackager | com.kwcode.registry |

## Memory (`kaiwu/memory/` → `com.kwcode.memory`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| project_md.py | ProjectMd | ProjectMd | com.kwcode.memory |
| pattern_md.py | PatternMd | PatternMd | com.kwcode.memory |
| expert_md.py | ExpertMd | ExpertMd | com.kwcode.memory |
| kaiwu_md.py | KaiwuMd | KaiwuMemory | com.kwcode.memory |
| session_md.py | SessionMd | SessionMd | com.kwcode.memory |

## Search (`kaiwu/search/` → `com.kwcode.search`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| search_router.py | SearchRouter | SearchRouter | com.kwcode.search |
| intent_classifier.py | IntentClassifier | IntentClassifier | com.kwcode.search |
| quality_filter.py | QualityFilter | QualityFilter | com.kwcode.search |
| query_generator.py | QueryGenerator | QueryGenerator | com.kwcode.search |
| context_compressor.py | ContextCompressor | ContextCompressor | com.kwcode.search |
| rerank.py | Reranker | Reranker | com.kwcode.search |
| extraction_pipeline.py | ExtractionPipeline | ExtractionPipeline | com.kwcode.search |
| search.py | DuckDuckGoSearch | DuckDuckGoSearch | com.kwcode.search |

## Tools (`kaiwu/tools/` → `com.kwcode.tools`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| tool_gateway.py | ToolGateway | ToolGateway | com.kwcode.tools |
| tool_executor.py | ToolExecutor | ToolExecutor | com.kwcode.tools |
| ast_utils.py | AstUtils | AstUtils | com.kwcode.tools |
| hashline.py | hashline() | Hashline | com.kwcode.tools |
| import_fixer.py | ImportFixer | ImportFixer | com.kwcode.tools |

## Audit (`kaiwu/audit/` → `com.kwcode.audit`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| audit_logger.py | AuditLogger | AuditLogger | com.kwcode.audit |
| detailed_logger.py | DetailedLogger | DetailedLogger | com.kwcode.audit |

## LLM (`kaiwu/llm/` → `com.kwcode.llm`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| llama_backend.py | LLMBackend | LLMBackend | com.kwcode.llm |
| llama_backend.py | LLMBackend.generate() | LLMBackend.generate() | com.kwcode.llm |
| llama_backend.py | LLMBackend.chat() | LLMBackend.chat() | com.kwcode.llm |
| llama_backend.py | BudgetExceededError | LLMBackend.BudgetExceededError | com.kwcode.llm |
| — | — | LLMService | com.kwcode.llm |

## Server (`kaiwu/server/` → `com.kwcode.server`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| app.py | create_app() | ServerController | com.kwcode.server |
| models.py | TaskRequest/Response | Models.* | com.kwcode.server |
| pipeline_factory.py | build_pipeline() | PipelineFactory.buildPipeline() | com.kwcode.server |

## CLI (`kaiwu/cli/` → `com.kwcode.cli`)

| Python文件 | Python类/函数 | Java类 | Java包 |
|---|---|---|---|
| main.py | app (Typer) | CliMain (Picocli) | com.kwcode.cli |

---

## 覆盖率统计

| 模块 | Python源文件 | Java已实现 | 覆盖率 |
|---|---|---|---|
| AST Engine | 8 | 8 | 100% |
| Core | 12 | 12 | 100% |
| Experts | 7 | 5 | 71% |
| Flywheel | 9 | 9 | 100% |
| Registry | 3 | 3 | 100% |
| Memory | 5 | 5 | 100% |
| Search | 8 | 8 | 100% |
| Tools | 5 | 5 | 100% |
| Audit | 2 | 2 | 100% |
| LLM | 1 | 2 | 200% |
| Server | 3 | 3 | 100% |
| CLI | 1 | 1 | 100% |
| **合计** | **64** | **63** | **98%** |
