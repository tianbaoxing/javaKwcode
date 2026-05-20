package com.kwcode.core.env;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 环境探测器 - 任务开始前确定性探测并修复环境
 * <p>
 * 确定性逻辑，不走LLM，所有步骤失败静默。
 * 结果缓存到 .kaiwu/env_profile.json（24小时有效，只缓存成功）。
 * </p>
 * @origin Python: core.env_prober.EnvProber
 */
public class EnvProber {

    private static final Logger log = LoggerFactory.getLogger(EnvProber.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CACHE_FILE = ".kaiwu/env_profile.json";
    private static final long CACHE_TTL_MS = 24 * 3600 * 1000L;

    /**
     * 语言工具链配置
     * @origin Python: core.env_prober.LANG_TOOLCHAIN
     */
    public static final Map<String, ToolchainConfig> LANG_TOOLCHAIN = Map.of(
        "go", new ToolchainConfig("go version", "go mod download", "go.mod"),
        "typescript", new ToolchainConfig("npx --version", "npm install", "package.json"),
        "javascript", new ToolchainConfig("node --version", "npm install", "package.json"),
        "rust", new ToolchainConfig("cargo --version", "cargo fetch", "Cargo.toml"),
        "java", new ToolchainConfig("javac -version", "mvn dependency:resolve -q", "pom.xml"),
        "python", new ToolchainConfig("python3 --version", "pip install -r requirements.txt", "requirements.txt")
    );

    /**
     * 探测并修复环境
     * <p>
     * 返回探测结果：语言、是否就绪、已安装的工具、测试命令等。
     * 检查缓存 → 检测语言 → 工具链检测 → 依赖安装 → 验证测试命令。
     * </p>
     * @origin Python: core.env_prober.EnvProber.probe_and_fix(project_root, tools) -> dict
     * @param projectRoot 项目根目录
     * @return 探测结果
     */
    private static final Map<String, String> PROJECT_MARKER_TO_LANG = Map.of(
        "pom.xml", "java",
        "build.gradle", "java",
        "build.gradle.kts", "java",
        "go.mod", "go",
        "Cargo.toml", "rust",
        "requirements.txt", "python",
        "pyproject.toml", "python",
        "setup.py", "python",
        "package.json", "javascript"
    );

    private static final Map<String, String> USER_INPUT_LANG_PATTERNS = Map.ofEntries(
        Map.entry("java类", "java"),
        Map.entry("java", "java"),
        Map.entry("python", "python"),
        Map.entry("go语言", "go"),
        Map.entry("golang", "go"),
        Map.entry("rust", "rust"),
        Map.entry("typescript", "typescript"),
        Map.entry("javascript", "javascript"),
        Map.entry("kotlin", "java"),
        Map.entry("scala", "java"),
        Map.entry("c++", "cpp"),
        Map.entry("cpp", "cpp")
    );

    private String lastUserInput;

    public void setUserInputHint(String userInput) {
        this.lastUserInput = userInput;
    }

    public EnvProbeResult probeAndFix(String projectRoot) {
        EnvProbeResult cached = loadCache(projectRoot);
        if (cached != null) return cached;

        String lang = detectLang(projectRoot);
        EnvProbeResult result = new EnvProbeResult(lang, false, new ArrayList<>(), "", false);

        // 工具链检测
        ToolchainConfig tc = LANG_TOOLCHAIN.get(lang);
        if (tc != null && tc.checkCmd() != null) {
            try {
                int rc = runCommand(tc.checkCmd(), projectRoot, 30);
                if (rc != 0) {
                    log.debug("[env] toolchain check failed for {}", lang);
                }
            } catch (Exception e) {
                log.debug("[env] toolchain check error: {}", e.getMessage());
            }
        }

        // 项目依赖
        if (tc != null && tc.depFile() != null) {
            Path depPath = Path.of(projectRoot, tc.depFile());
            if (Files.exists(depPath)) {
                try {
                    int rc = runCommand(tc.depCmd(), projectRoot, 180);
                    if (rc == 0) {
                        result.installed().add("deps:" + tc.depFile());
                    }
                } catch (Exception e) {
                    log.debug("[env] dep install failed: {}", e.getMessage());
                }
            }
        }

        // Python额外处理
        if ("python".equals(lang)) {
            for (String[] pkg : new String[][]{{"pyproject.toml", "pip install -e ."}, {"setup.py", "pip install -e ."}}) {
                if (Files.exists(Path.of(projectRoot, pkg[0]))) {
                    try { runCommand(pkg[1], projectRoot, 120); } catch (Exception e) { /* ignore */ }
                    break;
                }
            }
        }

        // 验证测试命令
        result.testCmd = findWorkingTestCmd(lang, projectRoot);
        ToolchainConfig tc2 = LANG_TOOLCHAIN.get(lang);
        if (tc2 != null && tc2.checkCmd() != null) {
            try {
                result.ready = runCommand(tc2.checkCmd(), projectRoot, 30) == 0;
            } catch (Exception e) {
                result.ready = false;
            }
        } else {
            result.ready = result.testCmd != null && !result.testCmd.isEmpty();
        }

        // JDK/Maven环境信息探测
        probeJdkInfo(result, projectRoot);

        // 缓存
        saveCache(projectRoot, result);
        return result;
    }

    /**
     * 探测JDK版本、安装目录和Maven版本
     * <p>
     * 在probeAndFix中调用，结果写入EnvProbeResult。
     * </p>
     */
    private void probeJdkInfo(EnvProbeResult result, String projectRoot) {
        // JDK版本
        try {
            var pb = new ProcessBuilder("cmd", "/c", "java -version");
            pb.directory(Path.of(projectRoot).toFile());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            // java -version 输出到 stderr，已合并到 stdout
            var m = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(output);
            if (m.find()) {
                result.setJdkVersion(m.group(1));
                log.info("[env] JDK version: {}", m.group(1));
            }
        } catch (Exception e) {
            log.debug("[env] JDK version probe failed: {}", e.getMessage());
        }

        // JAVA_HOME
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isEmpty()) {
            result.setJdkHome(javaHome);
            log.info("[env] JAVA_HOME: {}", javaHome);
        } else {
            // 尝试从 java 命令路径推断
            try {
                var pb = new ProcessBuilder("cmd", "/c", "where java");
                pb.redirectErrorStream(true);
                var proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                // 典型输出: C:\Program Files\Java\jdk-17\bin\java.exe
                var lines = output.split("\r?\n");
                if (lines.length > 0) {
                    String javaPath = lines[0].trim();
                    int binIdx = javaPath.toLowerCase().indexOf("\\bin\\");
                    if (binIdx > 0) {
                        result.setJdkHome(javaPath.substring(0, binIdx));
                        log.info("[env] JDK home (inferred): {}", javaPath.substring(0, binIdx));
                    }
                }
            } catch (Exception e) {
                log.debug("[env] JDK home inference failed: {}", e.getMessage());
            }
        }

        // Maven版本
        try {
            var pb = new ProcessBuilder("cmd", "/c", "mvn -version");
            pb.directory(Path.of(projectRoot).toFile());
            pb.redirectErrorStream(true);
            var proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            var m = java.util.regex.Pattern.compile("Apache Maven (\\S+)").matcher(output);
            if (m.find()) {
                result.setMavenVersion(m.group(1));
                log.info("[env] Maven version: {}", m.group(1));
            }
        } catch (Exception e) {
            log.debug("[env] Maven version probe failed: {}", e.getMessage());
        }
    }

    /**
     * 按文件扩展名统计主语言
     * @origin Python: core.env_prober.EnvProber._detect_lang(project_root: str) -> str
     */
    private String detectLang(String projectRoot) {
        String fromUserInput = detectLangFromUserInput();
        if (fromUserInput != null) {
            log.info("[env] detectLang: from userInput hint → {}", fromUserInput);
            return fromUserInput;
        }

        String fromMarker = detectLangFromProjectMarker(projectRoot);
        if (fromMarker != null) {
            log.info("[env] detectLang: from project marker → {}", fromMarker);
            return fromMarker;
        }

        String fromExtCount = detectLangFromExtCount(projectRoot);
        if (fromExtCount != null) {
            log.info("[env] detectLang: from extension count → {}", fromExtCount);
            return fromExtCount;
        }

        log.info("[env] detectLang: no signal found → unknown");
        return "unknown";
    }

    private String detectLangFromUserInput() {
        if (lastUserInput == null || lastUserInput.isEmpty()) return null;
        String lower = lastUserInput.toLowerCase();
        String best = null;
        int bestLen = 0;
        for (var entry : USER_INPUT_LANG_PATTERNS.entrySet()) {
            if (lower.contains(entry.getKey()) && entry.getKey().length() > bestLen) {
                best = entry.getValue();
                bestLen = entry.getKey().length();
            }
        }
        return best;
    }

    private String detectLangFromProjectMarker(String projectRoot) {
        for (var entry : PROJECT_MARKER_TO_LANG.entrySet()) {
            if (Files.exists(Path.of(projectRoot, entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String detectLangFromExtCount(String projectRoot) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String[], String> extMap = Map.of(
            new String[]{".go"}, "go",
            new String[]{".ts", ".tsx"}, "typescript",
            new String[]{".js", ".jsx"}, "javascript",
            new String[]{".rs"}, "rust",
            new String[]{".java"}, "java",
            new String[]{".py"}, "python"
        );

        Set<String> skipParts = Set.of("node_modules", ".git", "venv", "__pycache__", "target");
        try (Stream<Path> paths = Files.walk(Path.of(projectRoot))) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> skipParts.stream().noneMatch(s -> p.toString().contains(s)))
                 .forEach(p -> {
                     String name = p.getFileName().toString().toLowerCase();
                     for (var entry : extMap.entrySet()) {
                         for (String ext : entry.getKey()) {
                             if (name.endsWith(ext)) {
                                 counts.merge(entry.getValue(), 1, Integer::sum);
                             }
                         }
                     }
                 });
        } catch (IOException e) { /* ignore */ }

        return counts.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .filter(e -> e.getValue() > 0)
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    /** 验证测试命令 */
    private String findWorkingTestCmd(String lang, String projectRoot) {
        Map<String, String[]> verifyCmds = Map.of(
            "python", new String[]{"python -m pytest --version"},
            "go", new String[]{"go build ./..."},
            "java", new String[]{"mvn validate -q", "javac -version"},
            "rust", new String[]{"cargo check"},
            "unknown", new String[]{"mvn validate -q", "javac -version", "python -m pytest --version", "go build ./..."}
        );
        Map<String, String> testCmds = Map.of(
            "python", "python -m pytest -x -q",
            "go", "go test ./...",
            "typescript", "npx jest --passWithNoTests",
            "javascript", "npx jest --passWithNoTests",
            "rust", "cargo test",
            "java", "mvn test -q",
            "unknown", ""
        );

        for (String verifyCmd : verifyCmds.getOrDefault(lang, new String[0])) {
            try {
                if (runCommand(verifyCmd, projectRoot, 30) == 0) {
                    String testCmd = testCmds.getOrDefault(lang, "");
                    if ("java".equals(lang) && !Files.exists(Path.of(projectRoot, "pom.xml"))
                        && !Files.exists(Path.of(projectRoot, "build.gradle"))
                        && !Files.exists(Path.of(projectRoot, "build.gradle.kts"))) {
                        log.info("[env] Java toolchain found but no build file (pom.xml/build.gradle), skipping mvn test");
                        return "";
                    }
                    return testCmd;
                }
            } catch (Exception e) { continue; }
        }
        return "";
    }

    /** 运行命令 */
    private int runCommand(String cmd, String cwd, int timeoutSec) throws Exception {
        var pb = new ProcessBuilder("cmd", "/c", cmd);
        pb.directory(Path.of(cwd).toFile());
        pb.redirectErrorStream(true);
        var proc = pb.start();
        proc.getInputStream().readAllBytes();
        return proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            ? proc.exitValue() : -1;
    }

    /** 加载缓存 */
    private EnvProbeResult loadCache(String projectRoot) {
        Path cachePath = Path.of(projectRoot, CACHE_FILE);
        try {
            if (!Files.exists(cachePath)) return null;
            Map<String, Object> cache = MAPPER.readValue(cachePath.toFile(), Map.class);
            long cachedAt = ((Number) cache.getOrDefault("cached_at", 0L)).longValue();
            if (System.currentTimeMillis() - cachedAt > CACHE_TTL_MS) return null;

            String lang = (String) cache.get("lang");
            String testCmd = (String) cache.getOrDefault("test_cmd", "");

            if ("java".equals(lang) && testCmd != null && testCmd.startsWith("mvn")
                && !Files.exists(Path.of(projectRoot, "pom.xml"))
                && !Files.exists(Path.of(projectRoot, "build.gradle"))
                && !Files.exists(Path.of(projectRoot, "build.gradle.kts"))) {
                log.info("[env] cache invalidated: Java project has no build file but cached testCmd={}", testCmd);
                return null;
            }

            return new EnvProbeResult(
                lang,
                (Boolean) cache.getOrDefault("ready", false),
                (List<String>) cache.getOrDefault("installed", List.of()),
                testCmd,
                (Boolean) cache.getOrDefault("rig_built", false)
            );
        } catch (Exception e) { return null; }
    }

    /** 保存缓存（只缓存成功） */
    private void saveCache(String projectRoot, EnvProbeResult result) {
        if (!result.ready) return;
        Path cachePath = Path.of(projectRoot, CACHE_FILE);
        try {
            Files.createDirectories(cachePath.getParent());
            Map<String, Object> cache = new LinkedHashMap<>();
            cache.put("lang", result.lang());
            cache.put("ready", result.ready());
            cache.put("installed", result.installed());
            cache.put("test_cmd", result.testCmd());
            cache.put("rig_built", result.rigBuilt());
            cache.put("cached_at", System.currentTimeMillis());
            MAPPER.writeValue(cachePath.toFile(), cache);
        } catch (Exception e) {
            log.debug("[env] cache save failed: {}", e.getMessage());
        }
    }

    /** 工具链配置 */
    public record ToolchainConfig(String checkCmd, String depCmd, String depFile) {}

    /** 探测结果 */
    public static class EnvProbeResult {
        private final String lang;
        private boolean ready;
        private final List<String> installed;
        private String testCmd;
        private final boolean rigBuilt;
        private String jdkVersion;
        private String jdkHome;
        private String mavenVersion;

        public EnvProbeResult(String lang, boolean ready, List<String> installed, String testCmd, boolean rigBuilt) {
            this.lang = lang;
            this.ready = ready;
            this.installed = installed;
            this.testCmd = testCmd;
            this.rigBuilt = rigBuilt;
        }

        public String lang() { return lang; }
        public boolean ready() { return ready; }
        public List<String> installed() { return installed; }
        public String testCmd() { return testCmd; }
        public boolean rigBuilt() { return rigBuilt; }
        public String jdkVersion() { return jdkVersion; }
        public String jdkHome() { return jdkHome; }
        public String mavenVersion() { return mavenVersion; }

        public void setReady(boolean v) { this.ready = v; }
        public void setTestCmd(String v) { this.testCmd = v; }
        public void setJdkVersion(String v) { this.jdkVersion = v; }
        public void setJdkHome(String v) { this.jdkHome = v; }
        public void setMavenVersion(String v) { this.mavenVersion = v; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("lang", lang);
            m.put("ready", ready);
            m.put("installed", installed);
            m.put("test_cmd", testCmd);
            m.put("rig_built", rigBuilt);
            if (jdkVersion != null) m.put("jdk_version", jdkVersion);
            if (jdkHome != null) m.put("jdk_home", jdkHome);
            if (mavenVersion != null) m.put("maven_version", mavenVersion);
            return m;
        }
    }
}
