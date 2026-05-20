package com.kwcode.core.upstream;

import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨文件契约追踪 - 确定性跨文件签名/常量/import提取
 * <p>
 * 从patches中提取函数签名、常量、import语句。
 * 零LLM调用 — 纯AST/正则提取。
 * </p>
 * <p>
 * 被以下组件使用：
 * - TaskCompiler: update() 在每个子任务完成后调用
 * - Generator: getConstraintsForFile() 注入prompt
 * - Verifier: checkConsistency() 验证跨文件契约
 * </p>
 * @origin Python: core.upstream_manifest.UpstreamManifest
 */
public class UpstreamManifest {

    private static final Logger log = LoggerFactory.getLogger(UpstreamManifest.class);

    private static final Pattern FUNC_SIG_PATTERN = Pattern.compile(
        "^(?:(?:async\\s+)?def|func|fn|function)\\s+(\\w+)\\s*\\(([^)]*)\\)",
        Pattern.MULTILINE
    );

    private static final Pattern CONST_PATTERN = Pattern.compile(
        "^([A-Z][A-Z_0-9]+)\\s*[:=]\\s*(.+?)$",
        Pattern.MULTILINE
    );

    private static final Pattern IMPORT_FROM_PATTERN = Pattern.compile(
        "from\\s+([\\w.]+)\\s+import"
    );

    private final Map<String, Map<String, String>> signatures = new HashMap<>();
    private final Map<String, Map<String, String>> constants = new HashMap<>();
    private final Map<String, List<String>> imports = new HashMap<>();
    private final Map<String, List<String>> dependencyGraph = new HashMap<>();

    /**
     * 从补丁列表更新manifest
     * <p>
     * 每个patch: {"file": str, "original": str, "modified": str}
     * </p>
     * @origin Python: core.upstream_manifest.UpstreamManifest.update(patches)
     * @param patches 补丁列表
     */
    public void update(List<TaskContext.Patch> patches) {
        for (TaskContext.Patch patch : patches) {
            String filePath = patch.file();
            String modified = patch.modified();
            if (filePath == null || filePath.isEmpty() || modified == null || modified.isEmpty()) {
                continue;
            }
            extractFromCode(filePath, modified);
        }
    }

    /**
     * 从map格式的patches更新（兼容旧接口）
     */
    public void updateFromMaps(List<Map<String, String>> patches) {
        for (Map<String, String> patch : patches) {
            String filePath = patch.get("file");
            String modified = patch.get("modified");
            if (filePath == null || filePath.isEmpty() || modified == null || modified.isEmpty()) {
                continue;
            }
            extractFromCode(filePath, modified);
        }
    }

    /**
     * 返回指定文件的跨文件约束，注入Generator prompt
     * <p>
     * 包含：其他文件的签名、常量、本文件导出签名
     * </p>
     * @origin Python: core.upstream_manifest.UpstreamManifest.get_constraints_for_file(file_path)
     * @param filePath 文件路径
     * @return 约束文本
     */
    public String getConstraintsForFile(String filePath) {
        List<String> lines = new ArrayList<>();

        List<String> deps = dependencyGraph.getOrDefault(filePath, List.of());
        for (String depFile : deps) {
            Map<String, String> depSigs = signatures.getOrDefault(depFile, Map.of());
            for (var entry : depSigs.entrySet()) {
                lines.add("[契约] " + depFile + " 提供: " + entry.getValue());
            }
        }

        for (String depFile : deps) {
            Map<String, String> depConsts = constants.getOrDefault(depFile, Map.of());
            for (var entry : depConsts.entrySet()) {
                lines.add("[常量] " + depFile + ": " + entry.getKey() + " = " + entry.getValue());
            }
        }

        Map<String, String> ownSigs = signatures.getOrDefault(filePath, Map.of());
        if (!ownSigs.isEmpty()) {
            lines.add("[本文件导出]");
            for (var entry : ownSigs.entrySet()) {
                lines.add("  " + entry.getValue());
            }
        }

        return lines.isEmpty() ? "" : String.join("\n", lines);
    }

    /**
     * 检查代码是否与manifest契约一致
     * <p>
     * 纯确定性，零LLM。
     * </p>
     * @origin Python: core.upstream_manifest.UpstreamManifest.check_consistency(file_path, code)
     * @param filePath 文件路径
     * @param code 代码内容
     * @return 违规描述列表，空列表表示全部一致
     */
    public List<String> checkConsistency(String filePath, String code) {
        List<String> violations = new ArrayList<>();
        if (code == null || code.isEmpty()) return violations;

        Set<String> calls = extractFunctionCalls(code);

        List<String> deps = dependencyGraph.getOrDefault(filePath, List.of());
        for (String depFile : deps) {
            Map<String, String> depSigs = signatures.getOrDefault(depFile, Map.of());
            for (var entry : depSigs.entrySet()) {
                String funcName = entry.getKey();
                if (!calls.contains(funcName)) continue;

                Integer expectedParams = countParams(entry.getValue());
                if (expectedParams == null) continue;

                Pattern callPattern = Pattern.compile(funcName + "\\s*\\(([^)]*)\\)");
                Matcher m = callPattern.matcher(code);
                while (m.find()) {
                    int actualArgs = countArgs(m.group(1));
                    if (actualArgs > expectedParams) {
                        violations.add(filePath + ": " + funcName + "() 调用传了" + actualArgs +
                            "个参数，但签名只接受" + expectedParams + "个 (来自 " + depFile + ")");
                    }
                }
            }

            Map<String, String> depConsts = constants.getOrDefault(depFile, Map.of());
            for (var entry : depConsts.entrySet()) {
                String constName = entry.getKey();
                String expectedVal = entry.getValue();
                if (!code.contains(constName) || "<complex>".equals(expectedVal)) continue;

                Pattern redefPattern = Pattern.compile("^" + Pattern.quote(constName) + "\\s*=\\s*(.+?)$", Pattern.MULTILINE);
                Matcher m = redefPattern.matcher(code);
                while (m.find()) {
                    String redef = m.group(1).trim();
                    if (!redef.equals(expectedVal)) {
                        violations.add(filePath + ": " + constName + " 重定义为 " + redef +
                            "，但上游 " + depFile + " 定义为 " + expectedVal);
                    }
                }
            }
        }

        return violations;
    }

    /**
     * PENCIL式压缩：只保留结构化产物给下游
     * @origin Python: core.upstream_manifest.UpstreamManifest.to_compact_summary()
     */
    public Map<String, Object> toCompactSummary() {
        return Map.of(
            "signatures", signatures,
            "constants", constants,
            "imports", imports
        );
    }

    public Map<String, Map<String, String>> getAllSignatures() {
        return Collections.unmodifiableMap(signatures);
    }

    public Map<String, Map<String, String>> getAllConstants() {
        return Collections.unmodifiableMap(constants);
    }

    public void clear() {
        signatures.clear();
        constants.clear();
        imports.clear();
        dependencyGraph.clear();
    }

    // ── 内部方法 ──

    private void extractFromCode(String filePath, String code) {
        Map<String, String> sigs = new HashMap<>();
        Map<String, String> consts = new HashMap<>();
        List<String> imps = new ArrayList<>();

        Matcher funcMatcher = FUNC_SIG_PATTERN.matcher(code);
        while (funcMatcher.find()) {
            String name = funcMatcher.group(1);
            String params = funcMatcher.group(2).trim();
            sigs.put(name, name + "(" + params + ")");
        }

        Matcher constMatcher = CONST_PATTERN.matcher(code);
        while (constMatcher.find()) {
            String constName = constMatcher.group(1);
            String constVal = constMatcher.group(2).trim();
            if (constVal.length() > 100) constVal = constVal.substring(0, 100);
            consts.put(constName, constVal);
        }

        Matcher importMatcher = IMPORT_FROM_PATTERN.matcher(code);
        while (importMatcher.find()) {
            imps.add(importMatcher.group(0));
            String modulePath = importMatcher.group(1).replace(".", "/") + ".py";
            dependencyGraph.computeIfAbsent(filePath, k -> new ArrayList<>());
            if (!dependencyGraph.get(filePath).contains(modulePath)) {
                dependencyGraph.get(filePath).add(modulePath);
            }
        }

        if (!sigs.isEmpty()) signatures.put(filePath, sigs);
        if (!consts.isEmpty()) constants.put(filePath, consts);
        if (!imps.isEmpty()) imports.put(filePath, imps);
    }

    private Set<String> extractFunctionCalls(String code) {
        Set<String> calls = new HashSet<>();
        Pattern callPattern = Pattern.compile("(\\w+)\\s*\\(");
        Matcher m = callPattern.matcher(code);
        while (m.find()) {
            calls.add(m.group(1));
        }
        return calls;
    }

    private Integer countParams(String sig) {
        Matcher m = Pattern.compile("\\(([^)]*)\\)").matcher(sig);
        if (!m.find()) return null;
        String paramsStr = m.group(1).trim();
        if (paramsStr.isEmpty()) return 0;
        String[] params = paramsStr.split(",");
        List<String> filtered = new ArrayList<>();
        for (String p : params) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            String paramName = trimmed.split(":")[0].trim();
            if ("self".equals(paramName) || "cls".equals(paramName)) continue;
            if (paramName.startsWith("*")) return null;
            filtered.add(trimmed);
        }
        return filtered.size();
    }

    private int countArgs(String argsStr) {
        String trimmed = argsStr.trim();
        if (trimmed.isEmpty()) return 0;
        int depth = 0;
        int count = 1;
        for (char ch : trimmed.toCharArray()) {
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth--;
            else if (ch == ',' && depth == 0) count++;
        }
        return count;
    }
}
