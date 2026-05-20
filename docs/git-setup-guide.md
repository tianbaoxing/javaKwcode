# Git 仓库初始化与推送指南

> 记录 javaKwcode 项目从零初始化 Git 仓库并推送到 GitHub 的完整过程。

## 1. 初始化本地仓库

项目目录初始状态没有 `.git`，需要手动初始化：

```bash
cd E:\ai\aicode\traeHome\workHome\kwcode\javaKwcode
git init
```

输出：

```
Initialized empty Git repository in E:/ai/aicode/traeHome/workHome/kwcode/javaKwcode/.git/
```

## 2. 创建 .gitignore

在项目根目录创建 `.gitignore`，排除编译产物、IDE 配置、运行时缓存和敏感文件：

```gitignore
# Compiled
target/
*.class

# IDE
.idea/
*.iml
.vscode/
*.swp
*.swo

# OS
.DS_Store
Thumbs.db

# Build
*.jar
*.war

# Kwcode runtime
.kaiwu/

# Sensitive
*.env
.env
credentials.json
secrets.yaml
id_rsa

# Maven
*.log
```

关键排除项说明：

| 排除项 | 原因 |
|--------|------|
| `target/` | Maven 编译产物，无需版本控制 |
| `.vscode/` | IDE 配置，含 `launch.json` 中可能硬编码的 API Key |
| `.kaiwu/` | 运行时缓存（`env_profile.json` 等），含本地环境信息 |
| `.env` / `credentials.json` | 敏感信息，禁止提交 |

## 3. 添加文件到暂存区

选择性添加，避免误提交不需要的文件：

```bash
git add README.md .gitignore pom.xml src/ docs/ mapping/
```

> **注意**：`target/`、`.kaiwu/`、`.vscode/` 已被 `.gitignore` 排除，无需担心误提交。

验证暂存区状态：

```bash
git status --short
```

应看到所有源文件标记为 `A`（Added），且不包含 `target/`、`.kaiwu/`、`.vscode/` 下的文件。

## 4. 首次提交

```bash
git commit -m "feat: initial release of KW-CODE Java Coding Agent"
```

## 5. 设置远程仓库

```bash
git remote add origin git@github.com:tianbaoxing/javaKwcode.git
```

验证远程地址：

```bash
git remote -v
```

输出：

```
origin  git@github.com:tianbaoxing/javaKwcode.git (fetch)
origin  git@github.com:tianbaoxing/javaKwcode.git (push)
```

## 6. 重命名分支为 main

```bash
git branch -M main
```

## 7. 推送到远程

```bash
git push -u origin main
```

输出：

```
To github.com:tianbaoxing/javaKwcode.git
 * [new branch]      main -> main
branch 'main' set up to track 'origin/main'.
```

推送成功后，仓库地址为：https://github.com/tianbaoxing/javaKwcode

## 8. 后续提交与推送

日常开发中的提交推送流程：

```bash
# 查看变更
git status
git diff

# 添加变更文件
git add <具体文件>

# 提交
git commit -m "fix: 修复EnvProber对无pom.xml项目的误判"

# 推送
git push
```

## 安全检查清单

每次提交前应检查：

- [ ] `.gitignore` 中已排除 `.vscode/`（含可能硬编码的 API Key）
- [ ] `.gitignore` 中已排除 `.kaiwu/`（含本地环境缓存）
- [ ] 配置文件中 API Key 使用环境变量引用（`${OPENROUTER_API_KEY:}`），而非硬编码
- [ ] `git diff --staged` 中无敏感信息（API Key、密码、私钥等）
- [ ] 不提交 `target/` 编译产物
