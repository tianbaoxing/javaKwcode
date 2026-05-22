# JAVA-KW-CODE (天工开物)

> **致谢**：本项目灵感源自 [val1813/kwcode](https://github.com/val1813/kwcode)（Python 版天工开物），感谢原作者用 Python 实现了确定性专家流水线架构，让 Java 程序员有机会用 Java 重新实现 Agent 逻辑，在 JVM 生态中探索 Coding Agent 的可能性。

Java 版本地 Coding Agent — 基于 LLM 的智能代码生成、修复与重构工具。

## 与上游项目的关系

| 维度 | [kwcode (Python)](https://github.com/val1813/kwcode) | javaKwcode (本项目) |
|------|------|------|
| 语言 | Python 3.10+ | Java 17+ |
| 框架 | 纯 Python + pip | Spring Boot 3.4 + Spring AI |
| AST 引擎 | tree-sitter | JavaParser + ANTLR 4 |
| 调试器 | sys.settrace (Python) | Java Agent / JDI |
| 搜索 | rank-bm25 + sentence-transformers | Apache Lucene (BM25) + DuckDuckGo |
| LLM 集成 | OpenAI Python SDK | Spring AI (OpenAI/Ollama) |
| 安装方式 | `pip install kwcode` | `mvn clean package` |
| 协议 | MIT | MIT |

**共享的核心设计**（源自上游项目思路）：

- 确定性专家流水线：EnvProber → Gate → Expert Pipeline → Verifier → Retry
- BM25 + 调用图两阶段定位
- Gap 驱动重试 + 熔断机制
- Deny-First 专家权限模型
- 飞轮系统（轨迹收集 → 模式检测 → 专家生成）
- 项目记忆（PROJECT.md / PATTERN.md / EXPERT.md）

**Java 版独有特性**：

- Spring AI 统一 LLM 调用层，无缝切换 OpenRouter / Ollama / 任意 OpenAI 兼容 API
- JavaParser 深度 AST 分析，原生支持 Java 语法树操作
- Maven/Gradle 项目自动检测与测试命令适配
- JVM 生态集成（Spring Boot 应用、Maven 插件化扩展）

## 简介

KW-CODE 是一个确定性流水线驱动的 Coding Agent，核心理念是 **"确定性优先，LLM 兜底"**：

- **确定性流水线**：EnvProber → Gate路由 → 专家序列执行 → Verifier验证 → 重试循环
- **LLM 只做生成**：LLM 仅在 Generator/Chat 等专家中调用，不参与决策流程
- **多模型支持**：OpenRouter（Claude/GPT/DeepSeek）、Ollama 本地模型、任意 OpenAI 兼容 API

## 架构

```
用户输入
  │
  ▼
┌─────────────┐    ┌──────────────┐    ┌──────────────────┐
│  EnvProber  │───▶│  Gate 路由   │───▶│  Pipeline 编排   │
│  环境探测    │    │  任务分类    │    │  专家流水线执行   │
└─────────────┘    └──────────────┘    └──────────────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    ▼                         ▼                         ▼
             ┌───────────┐           ┌──────────────┐          ┌───────────┐
             │  Locator  │           │  Generator   │          │ Verifier  │
             │  定位相关  │           │  生成代码    │          │ 验证结果  │
             │  文件/函数 │           │  应用补丁    │          │ 运行测试  │
             └───────────┘           └──────────────┘          └───────────┘
                    │                         │                         │
                    └─────────────────────────┼─────────────────────────┘
                                              ▼
                                    ┌──────────────────┐
                                    │  GapDetector     │
                                    │  差距分析+重试    │
                                    └──────────────────┘
```

### 核心模块

| 模块 | 包路径 | 说明 |
|------|--------|------|
| **PipelineOrchestrator** | `core.orchestrator` | 管道编排器，控制完整执行流程和重试策略 |
| **Gate** | `core.gate` | 任务分类路由，确定性优先 + LLM 兜底 |
| **GapDetector** | `core.gap` | 从测试输出确定性计算任务缺口类型 |
| **EnvProber** | `core.env` | 任务开始前探测并修复环境（语言/工具链/依赖） |
| **CognitiveGate** | `core.cognitive` | 认知门控，判断是否继续重试 |
| **WinkMonitor** | `core.wink` | 自修复检测，识别"差一点就对了"的模式 |
| **Locator** | `experts` | BM25 + AST 调用图两阶段定位 |
| **Generator** | `experts` | LLM 驱动的代码生成与补丁应用 |
| **Verifier** | `experts` | 语法检查 + 测试执行 + 结果分析 |
| **ToolGateway** | `tools` | 专家/工具分层，权限检查 + 文件读缓存 + 脏标记 |
| **LLMService** | `llm` | 统一 LLM 调用，支持 OpenRouter / Ollama / 兼容模式 |
| **SearchRouter** | `search` | 搜索增强，DuckDuckGo + SearXNG，零外部 API Key |
| **KaiwuMemory** | `memory` | 项目记忆系统（PROJECT.md / PATTERN.md / EXPERT.md） |
| **Flywheel** | `flywheel` | 飞轮系统：A/B 测试、轨迹收集、提示优化、专家生成 |

### 专家流水线

不同任务类型对应不同的专家序列：

> 详细的专家体系、编排流程和意图识别机制请参阅 [专家体系与编排流程](docs/expert-pipeline-architecture.md)

| 任务类型 | 专家序列 | 说明 |
|----------|----------|------|
| `codegen` | Generator → Verifier | 新代码生成 |
| `locator_repair` | Locator → Generator → Verifier | Bug 修复 |
| `refactor` | Locator → Generator → Verifier | 代码重构 |
| `doc` | Locator → Generator | 文档生成 |
| `chat` | ChatExpert | 对话问答 |
| `vision` | VisionExpert | 图片理解 |
| `office` | OfficeHandler | Office 文档处理 |

### 详细文档

| 文档 | 说明 |
|------|------|
| [专家体系与编排流程](docs/expert-pipeline-architecture.md) | 专家流水线架构、意图识别与编排机制 |
| [环境探测架构](docs/env-prober-architecture.md) | EnvProber 探测方法、数据存储与缓存机制 |
| [Locator 定位器架构](docs/locator-architecture.md) | BM25+调用图两阶段定位、检索策略与记忆飞轮 |
| [Generator 生成器架构](docs/generator-architecture.md) | 提示词构建、三阶段重试、补丁解析与语言推断 |
| [Git 仓库初始化指南](docs/git-setup-guide.md) | 仓库初始化、.gitignore 配置与安全检查清单 |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+
- （可选）Ollama 用于本地模型

### 构建

```bash
mvn clean package -DskipTests
```

### 运行

**交互模式（REPL）**：

```bash
# 使用 Ollama 本地模型
java -jar target/kwcode-1.0.0-SNAPSHOT.jar -m qwen3-8b -d /path/to/your/project

# 使用 OpenRouter 云端模型
java -jar target/kwcode-1.0.0-SNAPSHOT.jar \
  --ollama-url https://openrouter.ai/api/v1 \
  --api-key $OPENROUTER_API_KEY \
  -m deepseek/deepseek-chat-v3-0324 \
  -d /path/to/your/project
```

**单次执行**：

```bash
java -jar target/kwcode-1.0.0-SNAPSHOT.jar "修复UserService的空指针异常" -d /path/to/project
```

**CLI 子命令**：

```bash
kwcode init          # 初始化项目 KAIWU.md
kwcode serve         # 启动 HTTP Server（端口 7355）
kwcode status        # 显示当前状态
kwcode memory        # 查看项目记忆
```

**断点 launch.json启动文件参数例子**：

```bash
{
            "type": "java",
            "name": "CliMain",
            "request": "launch",
            "mainClass": "com.kwcode.cli.CliMain",
            "projectName": "kwcode",
            "env": {
                "JAVA_HOME": "E:\\ai\\jdk-17.0.2",
                "PATH": "E:\\ai\\jdk-17.0.2\\bin;E:\\ai\\node-v20.19.4-win-x64;E:\\ai\\apache-maven-3.9.12\\bin;${env:PATH}"
            },
            "vmArgs": "-Dfile.encoding=GBK -Dsun.jnu.encoding=GBK",
            "args": [
                "--ollama-url",
                "https://openrouter.ai/api/v1",
                "--api-key",
                "sk-or-v1-秘钥key",
                "-m",
                "deepseek/deepseek-v4-pro",
                "-d",
                "E:\\ai\\aicode\\traeHome\\workHome\\kwcode\\testKwcode",
                "-v"
            ]
        }
```
**断点 launch.json启动，例如：创建java类，实现string截取功能**：
 java ai to code
author tianbaoxing
?JAVA-KW-CODE 交互模式 (输入 /help 查看命令, /quit 退出)
   模型: deepseek/deepseek-v4-pro | 项目: E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
15:22:17.625 [main] INFO com.kwcode.server.PipelineFactory -- 构建kwcode pipeline: model=deepseek/deepseek-v4-pro, project=E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
-秘钥' '-m' 'deepseek/deepseek-v4-pro' '-d' 'E:\ai\aicode\traeHome\workHome\kwcode\testKwcode' '-v'
 java ai to code
author tianbaoxing
?JAVA-KW-CODE 交互模式 (输入 /help 查看命令, /quit 退出)
   模型: deepseek/deepseek-v4-pro | 项目: E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
15:22:17.625 [main] INFO com.kwcode.server.PipelineFactory -- 构建kwcode pipeline: model=deepseek/deepseek-v4-pro, project=E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
   模型: deepseek/deepseek-v4-pro | 项目: E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
15:22:17.625 [main] INFO com.kwcode.server.PipelineFactory -- 构建kwcode pipeline: model=deepseek/deepseek-v4-pro, project=E:\ai\aicode\traeHome\workHome\kwcode\testKwcode
15:22:17.633 [main] INFO com.kwcode.server.PipelineFactory -- [pipeline] 创建OpenRouter ChatClient: baseUrl=https://openrouter.ai/api, model=deepseek/deepseek-v4-pro
15:22:18.683 [main] INFO com.kwcode.llm.LLMService -- LLMService初始化完成（ChatClient模式，openRouter=true, ollama=false)
15:22:18.711 [main] INFO com.kwcode.server.PipelineFactory -- kwcode pipeline构建完成

> 创建java类，实现string截取功能

? 路由到专家: {expert_type=codegen, task_summary=创建java类，实现, difficulty=easy, routing_source=keyword, confidence=0.75, needs_search=false, subtask_hint=, expert_name=null, route_type=general}
15:22:59.085 [main] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] Gate结果 → expertType=codegen
15:22:59.085 [main] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] Gate结果 → difficulty=easy
15:22:59.086 [main] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] Gate结果 → systemPrompt=
15:22:59.086 [main] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] model from defaultModelName: deepseek/deepseek-v4-pro (gateResult has no model)
15:22:59.093 [main] INFO com.kwcode.core.ThinkConfig -- [think_config] autoConfigure: model=deepseek/deepseek-v4-pro isReasoning=false → MODE_NEVER (expertType=codegen difficulty=easy)   
  [env_probe] 检测项目环境...
  [env_probe]   语言: java
  [env_probe]   就绪: true
  [env_probe]   已安装: []
  [env_probe]   测试命令:
  [pre_test] 运行初始测试获取基线...
  [gate] 任务类型：codegen | 序列：[generator, verifier]
15:22:59.191 [main] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] model strategy: model=deepseek/deepseek-v4-pro tier=大模型模式 strategyMaxRetries=3 forcePlanMode=false
  [generator] 生成代码修改...
15:22:59.197 [orchestrator-watchdog] INFO com.kwcode.experts.Generator -- [generator] inferLanguageHint from userInput: Java
15:23:34.214 [orchestrator-watchdog] INFO com.kwcode.experts.Generator -- [generator] produced 1 patches
  [verifier] 验证修改结果...
15:23:34.216 [orchestrator-watchdog] INFO com.kwcode.tools.ToolExecutor -- [apply_patch] creating new file: filePath='SubstringUtil.java' resolved='E:\ai\aicode\traeHome\workHome\kwcode\testKwcode\SubstringUtil.java' parentExists=true
15:23:34.221 [orchestrator-watchdog] INFO com.kwcode.tools.ToolExecutor -- Wrote 725 bytes to E:\ai\aicode\traeHome\workHome\kwcode\testKwcode\SubstringUtil.java
15:23:34.223 [orchestrator-watchdog] INFO com.kwcode.experts.Verifier -- [verifier] Java project has no pom.xml, skipping mvn test cmd
15:23:34.223 [orchestrator-watchdog] INFO com.kwcode.experts.Verifier -- [verifier] passed=true, syntax=true, tests=0/0, patches=1/1
  [passed] 验证通过！
15:23:34.226 [orchestrator-watchdog] INFO com.kwcode.core.orchestrator.PipelineOrchestrator -- [orchestrator] 任务成功，耗时35141ms，重试0次
? 完成

### 配置

配置文件位于 `src/main/resources/application.yml`：

```yaml
spring:
  ai:
    openai:
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPENROUTER_API_KEY:}    # 通过环境变量注入，勿硬编码
      chat:
        options:
          model: deepseek/deepseek-chat-v3-0324
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: qwen3-8b
```

纯 Ollama 模式使用 `application-ollama.yml`：

```bash
java -jar target/kwcode.jar --spring.profiles.active=ollama -m qwen3-8b -d /path/to/project
```

## 项目结构

```
src/main/java/com/kwcode/
├── cli/                    # CLI 入口（Picocli）
├── core/
│   ├── orchestrator/       # 管道编排器
│   ├── gate/               # 任务门控路由
│   ├── gap/                # Gap 检测器
│   ├── env/                # 环境探测器
│   ├── cognitive/          # 认知门控
│   ├── checkpoint/         # 检查点管理
│   ├── context/            # 上下文管理与压缩
│   ├── execution/          # 执行追踪
│   ├── planner/            # 任务规划
│   ├── wink/               # 自修复检测
│   └── upstream/           # 上游清单
├── experts/                # 专家实现
├── ast/                    # AST 引擎（JavaParser + ANTLR）
├── llm/                    # LLM 服务层（Spring AI）
├── tools/                  # 工具网关与执行器
├── memory/                 # 项目记忆系统
├── search/                 # 搜索增强
├── flywheel/               # 飞轮系统
├── registry/               # 专家注册表
├── server/                 # HTTP Server
├── audit/                  # 审计日志
├── knowledge/              # 知识库
├── mcp/                    # MCP 路由
├── notification/           # 通知
├── stats/                  # 统计
└── telemetry/              # 遥测
```

## 关键设计

### 确定性优先

所有决策节点（Gate 路由、Gap 检测、环境探测）均为纯确定性逻辑，零 LLM 调用。LLM 仅在代码生成环节参与，保证系统行为可预测、可调试。

### Deny-First 权限模型

每个专家只能调用白名单内的工具：

| 专家 | 允许的工具 |
|------|-----------|
| locator | read_file, list_dir |
| generator | read_file |
| verifier | apply_patch, write_file, run_bash, read_file |
| debugger | read_file, run_bash |
| reviewer | read_file |

### 重试策略

- 最多 3 次重试（高难度任务 4 次）
- Syntax 错误免费重试（≤2 次不计入配额）
- 同类错误连续 3 次触发熔断
- 环境问题（LLM 不可用/工具链缺失）快速熔断
- 边际收益递减检测

### 多语言支持

自动检测项目语言（用户输入 → 项目标记文件 → 文件扩展名统计），适配对应的测试命令和错误模式：

| 语言 | 测试命令 | 构建文件 |
|------|---------|---------|
| Java | `mvn test -q` | pom.xml, build.gradle |
| Python | `python -m pytest -x -q` | requirements.txt |
| Go | `go test ./...` | go.mod |
| Rust | `cargo test` | Cargo.toml |
| TypeScript | `npx jest --passWithNoTests` | package.json |

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 3.4 | 应用框架 |
| Spring AI 0.8 | LLM 集成（OpenAI/Ollama） |
| JavaParser | Java AST 解析 |
| ANTLR 4 | 多语言 AST |
| Apache Lucene | BM25 检索 |
| Jackson | JSON/YAML 序列化 |
| Picocli | CLI 框架 |
| SQLite | 本地存储 |
| Jsoup | HTML 解析 |

## 安全注意事项

- API Key 通过环境变量 `${OPENROUTER_API_KEY}` 注入，**勿硬编码到代码或配置文件中**
- `.gitignore` 已排除 `.kaiwu/`、`.env`、`credentials.json`、`secrets.yaml`、`id_rsa` 等敏感文件
- ToolExecutor 内置敏感文件保护列表，拒绝读写 `credentials.json`、`id_rsa` 等文件
- ToolGateway 的 deny-first 权限模型限制专家的工具访问范围

## 开发

```bash
# 编译
mvn compile

# 运行测试
mvn test

# 运行单个测试
mvn test -Dtest=GapDetectorTest

# 打包
mvn clean package -DskipTests
```

## License

MIT License

## Author

tianbaoxing
