# EnvProber 项目环境探测模块

> 基于 `com.kwcode.core.env.EnvProber` 源码分析。
> 在流水线中作为 Phase 0 执行，所有逻辑确定性，零 LLM 调用。

---

## 一、探测的意义

### 1.1 为什么需要环境探测

kwcode 作为 Coding Agent，在执行任何代码修改之前，必须先了解目标项目的运行环境。原因如下：

| 问题 | 后果 | 探测解决 |
|------|------|---------|
| 不知道项目语言 | Generator 生成的代码语言不匹配 | 确定语言后注入正确的系统提示词 |
| 工具链未安装 | Verifier 运行测试必定失败，浪费重试配额 | 提前发现 `MISSING_TOOLCHAIN`，快速熔断 |
| 测试命令未知 | Verifier 无法验证修改是否正确 | 探测可用测试命令，写入 `ctx.confirmedTestCmd` |
| 依赖未安装 | 编译/运行失败，误判为代码错误 | 自动安装依赖，减少误判 |
| JDK/Maven 版本不对 | Java 项目编译失败 | 记录版本信息，供诊断和提示 |

### 1.2 在流水线中的位置

```
用户输入
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Phase 0: EnvProber 环境探测（确定性，零 LLM）         │
│   ├─ 检测项目语言                                     │
│   ├─ 检测工具链是否就绪                               │
│   ├─ 解析测试命令                                     │
│   ├─ 探测 JDK/Maven 版本                             │
│   └─ 写入 ctx.confirmedTestCmd, ctx.projectLang      │
└─────────────────────────────────────────────────────┘
  │
  ▼
┌─────────────────────────────────────────────────────┐
│ Phase 1: 前置测试 + GapDetector                      │
│   └─ 使用 ctx.confirmedTestCmd 运行测试              │
└─────────────────────────────────────────────────────┘
  │
  ▼
│ MISSING_TOOLCHAIN? → 快速熔断，返回失败              │
│
  ▼
│ Gate 路由 → expert_type + 专家序列                    │
│   └─ 使用 ctx.projectLang 选择正确的专家行为          │
```

**关键设计**：EnvProber 是整个流水线的第一个阶段，其输出 `projectLang` 和 `confirmedTestCmd` 被后续所有阶段依赖。

---

## 二、探测方法详解

### 2.1 总体流程

```
probeAndFix(projectRoot)
  │
  ├─► 1. 检查缓存 → 命中且未过期则直接返回
  │
  ├─► 2. 检测项目语言（三级优先级）
  │     ├─ 用户输入关键词
  │     ├─ 项目标记文件
  │     └─ 文件扩展名统计
  │
  ├─► 3. 工具链检测
  │     └─ 执行 checkCmd，判断工具链是否可用
  │
  ├─► 4. 依赖安装
  │     ├─ 检测依赖文件是否存在
  │     └─ 执行 depCmd 安装依赖
  │
  ├─► 5. 验证测试命令
  │     ├─ 逐个尝试 verifyCmds
  │     └─ 返回第一个成功的 testCmd
  │
  ├─► 6. JDK/Maven 信息探测
  │     ├─ java -version → jdkVersion
  │     ├─ JAVA_HOME / where java → jdkHome
  │     └─ mvn -version → mavenVersion
  │
  └─► 7. 保存缓存 → 写入 .kaiwu/env_profile.json
```

### 2.2 项目语言检测（三级优先级）

**优先级1：用户输入关键词匹配**

从用户输入文本中提取语言信号，优先级最高：

| 用户输入关键词 | 检测语言 |
|--------------|---------|
| `java类`、`java`、`kotlin`、`scala` | java |
| `python` | python |
| `go语言`、`golang` | go |
| `rust` | rust |
| `typescript` | typescript |
| `javascript` | javascript |
| `c++`、`cpp` | cpp |

匹配规则：最长匹配优先。例如用户输入"用java类实现"，`java类`(4字符) 优先于 `java`(4字符)，但两者都映射到 `java`。

**优先级2：项目标记文件**

扫描项目根目录下的标记文件：

| 标记文件 | 检测语言 |
|---------|---------|
| `pom.xml`、`build.gradle`、`build.gradle.kts` | java |
| `go.mod` | go |
| `Cargo.toml` | rust |
| `requirements.txt`、`pyproject.toml`、`setup.py` | python |
| `package.json` | javascript |

**优先级3：文件扩展名统计**

遍历项目源码文件，统计各语言文件数量，取最多的语言。跳过以下目录：

- `node_modules`、`.git`、`venv`、`__pycache__`、`target`

| 扩展名 | 语言 |
|--------|------|
| `.go` | go |
| `.ts`、`.tsx` | typescript |
| `.js`、`.jsx` | javascript |
| `.rs` | rust |
| `.java` | java |
| `.py` | python |

**兜底**：三级检测均无信号 → 返回 `"unknown"`

### 2.3 工具链检测

根据检测到的语言，查找对应的工具链配置：

| 语言 | 检测命令 | 依赖安装命令 | 依赖文件 |
|------|---------|-------------|---------|
| java | `javac -version` | `mvn dependency:resolve -q` | `pom.xml` |
| python | `python3 --version` | `pip install -r requirements.txt` | `requirements.txt` |
| go | `go version` | `go mod download` | `go.mod` |
| rust | `cargo --version` | `cargo fetch` | `Cargo.toml` |
| javascript | `node --version` | `npm install` | `package.json` |
| typescript | `npx --version` | `npm install` | `package.json` |

流程：
1. 执行 `checkCmd`，返回码为 0 表示工具链可用
2. 检查 `depFile` 是否存在
3. 存在则执行 `depCmd` 安装依赖
4. Python 额外处理：检测 `pyproject.toml` / `setup.py`，执行 `pip install -e .`

### 2.4 测试命令验证

逐个尝试验证命令，第一个成功的命令对应的测试命令即为结果：

| 语言 | 验证命令 | 测试命令 |
|------|---------|---------|
| python | `python -m pytest --version` | `python -m pytest -x -q` |
| go | `go build ./...` | `go test ./...` |
| java | `mvn validate -q` / `javac -version` | `mvn test -q` |
| rust | `cargo check` | `cargo test` |
| typescript | — | `npx jest --passWithNoTests` |
| javascript | — | `npx jest --passWithNoTests` |
| unknown | 依次尝试 java/python/go | `""`（空） |

**Java 特殊处理**：如果检测到 Java 工具链但项目没有 `pom.xml`/`build.gradle`/`build.gradle.kts`，则跳过 `mvn test`，返回空测试命令。避免在非 Java 项目上误执行 Maven。

### 2.5 JDK/Maven 信息探测

在工具链检测完成后，额外探测 JDK 和 Maven 的详细信息：

| 探测项 | 命令 | 解析方式 | 存储字段 |
|--------|------|---------|---------|
| JDK 版本 | `java -version` | 正则提取 `"xx.y.z"` 中的版本号 | `jdkVersion` |
| JDK 目录 | `JAVA_HOME` 环境变量 | 直接读取 | `jdkHome` |
| JDK 目录（推断） | `where java` | 取路径中 `\bin\` 前的部分 | `jdkHome` |
| Maven 版本 | `mvn -version` | 正则提取 `Apache Maven x.y.z` | `mavenVersion` |

**JDK 目录推断逻辑**：
1. 优先读取 `JAVA_HOME` 环境变量
2. 若 `JAVA_HOME` 为空，执行 `where java` 获取 java 可执行文件路径
3. 从路径中截取 `\bin\` 之前的部分作为 JDK 目录
4. 例如 `C:\Program Files\Java\jdk-17\bin\java.exe` → `C:\Program Files\Java\jdk-17`

---

## 三、数据存储

### 3.1 EnvProbeResult 数据结构

```java
public static class EnvProbeResult {
    private final String lang;           // 项目语言: java/python/go/rust/...
    private boolean ready;               // 工具链是否就绪
    private final List<String> installed; // 已安装的依赖列表
    private String testCmd;              // 验证通过的测试命令
    private final boolean rigBuilt;      // 是否已构建
    private String jdkVersion;           // JDK版本号，如 "17.0.2"
    private String jdkHome;              // JDK安装目录
    private String mavenVersion;         // Maven版本号，如 "3.9.12"
}
```

### 3.2 缓存机制

**缓存文件**：`.kaiwu/env_profile.json`

**缓存策略**：
- 只缓存 `ready=true` 的探测结果（失败不缓存，下次重新探测）
- 缓存有效期 24 小时（`CACHE_TTL_MS = 86400000`）
- 缓存失效条件：超过 TTL / Java 项目缺少构建文件

**缓存文件格式**：

```json
{
  "lang": "java",
  "ready": true,
  "installed": ["deps:pom.xml"],
  "test_cmd": "mvn test -q",
  "rig_built": false,
  "jdk_version": "17.0.2",
  "jdk_home": "E:\\ai\\jdk-17.0.2",
  "maven_version": "3.9.12",
  "cached_at": 1716307200000
}
```

**缓存失效的特殊处理**：

当缓存中 `lang=java` 且 `test_cmd` 以 `mvn` 开头，但项目中已不存在 `pom.xml`/`build.gradle`/`build.gradle.kts` 时，缓存自动失效。这防止了以下场景：

1. 项目原本是 Java 项目（缓存了 `mvn test -q`）
2. 用户删除了 `pom.xml`，项目不再是 Java 项目
3. 如果继续使用缓存，Verifier 会执行不存在的 `mvn test`

### 3.3 数据流向

```
EnvProber.probeAndFix()
  │
  ├─► EnvProbeResult.toMap()
  │     └─► PipelineOrchestrator 读取
  │           ├─ envResult.get("lang")        → ctx.projectLang
  │           ├─ envResult.get("test_cmd")     → ctx.confirmedTestCmd
  │           ├─ envResult.get("ready")        → 判断是否继续
  │           ├─ envResult.get("jdk_version")  → 状态输出
  │           ├─ envResult.get("jdk_home")     → 状态输出
  │           └─ envResult.get("maven_version")→ 状态输出
  │
  └─► saveCache()
        └─► .kaiwu/env_profile.json
              └─► 下次 loadCache() 直接返回
```

**下游消费者**：

| 字段 | 消费者 | 用途 |
|------|--------|------|
| `lang` | Gate | 语言感知的路由决策 |
| `lang` | Generator | 生成对应语言的代码 |
| `lang` | Locator | 语言感知的文件搜索（扩展名过滤） |
| `test_cmd` | Verifier | 执行测试验证 |
| `ready` | PipelineOrchestrator | 判断是否继续（MISSING_TOOLCHAIN 熔断） |
| `jdk_version` | 状态输出 | 用户可见的环境信息 |
| `jdk_home` | 状态输出 | 用户可见的环境信息 |
| `maven_version` | 状态输出 | 用户可见的环境信息 |

---

## 四、设计原则

| 原则 | 说明 |
|------|------|
| **确定性优先** | 所有探测逻辑不依赖 LLM，纯命令行检测 |
| **失败静默** | 任何探测步骤失败不抛异常，只记录 debug 日志 |
| **非阻塞** | PipelineOrchestrator 中 EnvProber 失败不中断流水线 |
| **只缓存成功** | `ready=false` 的结果不缓存，确保下次重新探测 |
| **缓存可失效** | Java 项目构建文件删除时自动失效缓存 |
| **最长匹配** | 用户输入语言检测时，优先匹配更长的关键词 |

---

## 五、命令执行环境

所有命令通过 `cmd /c` 在 Windows 上执行：

```java
ProcessBuilder pb = new ProcessBuilder("cmd", "/c", command);
pb.directory(Path.of(cwd).toFile());
pb.redirectErrorStream(true);  // 合并 stderr 到 stdout
```

超时设置：

| 场景 | 超时时间 |
|------|---------|
| 工具链检测 | 30 秒 |
| 依赖安装 | 180 秒 |
| Python pip install -e | 120 秒 |
| JDK 版本探测 | 10 秒 |
| Maven 版本探测 | 15 秒 |
