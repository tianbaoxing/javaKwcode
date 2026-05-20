# JAVA-KW-CODE (天工开物)

Java 版本地 Coding Agent — 基于 LLM 的智能代码生成、修复与重构工具。

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

| 任务类型 | 专家序列 | 说明 |
|----------|----------|------|
| `codegen` | Generator → Verifier | 新代码生成 |
| `locator_repair` | Locator → Generator → Verifier | Bug 修复 |
| `refactor` | Locator → Generator → Verifier | 代码重构 |
| `doc` | Locator → Generator | 文档生成 |
| `chat` | ChatExpert | 对话问答 |
| `vision` | VisionExpert | 图片理解 |
| `office` | OfficeHandler | Office 文档处理 |

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

田保兴
