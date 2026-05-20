package com.kwcode.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 专家加载器 - 支持YAML文件和SKILL.md目录格式
 * <p>
 * 渐进式披露：元数据（Level 1）启动时加载，
 * 指令（Level 2）按需加载，脚本（Level 3）按需执行。
 * </p>
 * @origin Python: registry.expert_loader.ExpertLoader
 */
public class ExpertLoader {

    private static final Logger log = LoggerFactory.getLogger(ExpertLoader.class);

    private static final Set<String> REQUIRED_FIELDS = Set.of(
        "name", "version", "type", "trigger_keywords", "trigger_min_confidence", "system_prompt", "pipeline"
    );
    private static final Set<String> REQUIRED_FIELDS_SKILL = Set.of(
        "name", "trigger_keywords", "trigger_min_confidence", "pipeline"
    );
    private static final Set<String> VALID_LIFECYCLES = Set.of("new", "mature", "declining", "archived");
    private static final Set<String> VALID_PIPELINE_STEPS = Set.of("locator", "generator", "verifier", "office", "chat");

    /**
     * 从目录加载所有专家（YAML文件+SKILL.md子目录）
     * @origin Python: registry.expert_loader.ExpertLoader.load_directory(dir_path) -> list[dict]
     */
    public List<Map<String, Object>> loadDirectory(String dirPath) {
        List<Map<String, Object>> experts = new ArrayList<>();
        if (!Files.isDirectory(Path.of(dirPath))) return experts;

        Map<String, String> loadedNames = new HashMap<>(); // name → format

        try (Stream<Path> paths = Files.list(Path.of(dirPath))) {
            List<Path> sorted = paths.sorted().toList();
            for (Path fpath : sorted) {
                String fname = fpath.getFileName().toString();

                // SKILL.md目录格式（优先）
                if (Files.isDirectory(fpath) && Files.exists(fpath.resolve("SKILL.md"))) {
                    try {
                        Map<String, Object> expert = loadSkillDir(fpath.toString());
                        String name = (String) expert.get("name");
                        if ("yaml".equals(loadedNames.get(name))) {
                            experts.removeIf(e -> name.equals(e.get("name")));
                        }
                        loadedNames.put(name, "skill");
                        experts.add(expert);
                    } catch (Exception e) {
                        log.warn("Failed to load skill dir {}: {}", fname, e.getMessage());
                    }
                    continue;
                }

                // YAML文件格式
                if (fname.endsWith(".yaml") || fname.endsWith(".yml")) {
                    try {
                        Map<String, Object> expert = loadYaml(fpath.toString());
                        String name = (String) expert.get("name");
                        if (!"skill".equals(loadedNames.get(name))) {
                            loadedNames.put(name, "yaml");
                            experts.add(expert);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load expert {}: {}", fname, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Expert directory not found: {}", dirPath);
        }

        return experts;
    }

    /**
     * 加载YAML专家文件（使用SnakeYAML）
     * @origin Python: registry.expert_loader.ExpertLoader.load_yaml(path) -> dict
     */
    public Map<String, Object> loadYaml(String path) throws IOException {
        // 使用SnakeYAML解析
        org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
        try (InputStream is = Files.newInputStream(Path.of(path))) {
            Map<String, Object> data = yaml.load(is);
            if (data == null) throw new IllegalArgumentException("Empty YAML: " + path);

            String[] err = new String[1];
            if (!validate(data, err)) throw new IllegalArgumentException("Validation failed: " + err[0]);

            data.putIfAbsent("lifecycle", "new");
            data.putIfAbsent("performance", Map.of("success_rate", 0.0, "avg_latency_s", 0, "task_count", 0));
            data.put("_source", path);
            data.put("_format", "yaml");
            return data;
        }
    }

    /**
     * 加载SKILL.md目录专家
     * @origin Python: registry.expert_loader.ExpertLoader.load_skill_dir(dir_path) -> dict
     */
    public Map<String, Object> loadSkillDir(String dirPath) throws IOException {
        Path skillPath = Path.of(dirPath, "SKILL.md");
        if (!Files.exists(skillPath)) throw new IllegalArgumentException("No SKILL.md in " + dirPath);

        String content = Files.readString(skillPath, StandardCharsets.UTF_8);
        Map<String, Object> frontmatter = new HashMap<>();
        String body = content;

        // 解析YAML frontmatter
        Matcher fm = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL).matcher(content);
        if (fm.find()) {
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            frontmatter = yaml.load(fm.group(1));
            body = fm.group(2);
        }

        if (frontmatter.isEmpty()) throw new IllegalArgumentException("No frontmatter in " + skillPath);

        // 构建专家定义
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", frontmatter.get("name"));
        data.put("version", frontmatter.getOrDefault("version", "1.0.0"));
        data.put("type", "skill");
        data.put("trigger_keywords", frontmatter.get("trigger_keywords"));
        data.put("trigger_min_confidence", frontmatter.get("trigger_min_confidence"));
        data.put("pipeline", frontmatter.get("pipeline"));
        data.put("lifecycle", frontmatter.getOrDefault("lifecycle", "new"));
        data.put("performance", Map.of("success_rate", 0.0, "avg_latency_s", 0, "task_count", 0));
        data.put("instructions", body.strip());
        data.put("system_prompt", body.strip());
        data.put("scripts", scanScripts(dirPath));
        data.put("_source", skillPath.toString());
        data.put("_source_dir", dirPath);
        data.put("_format", "skill");

        return data;
    }

    /**
     * 验证专家定义
     * @origin Python: registry.expert_loader.ExpertLoader.validate(expert_def) -> tuple[bool, str]
     */
    public boolean validate(Map<String, Object> def, String[] err) {
        Set<String> missing = new HashSet<>(REQUIRED_FIELDS);
        missing.removeAll(def.keySet());
        if (!missing.isEmpty()) { err[0] = "Missing fields: " + missing; return false; }

        Object kw = def.get("trigger_keywords");
        if (!(kw instanceof List) || ((List<?>) kw).isEmpty()) {
            err[0] = "trigger_keywords must be a non-empty list"; return false;
        }

        double conf = ((Number) def.getOrDefault("trigger_min_confidence", 0)).doubleValue();
        if (conf <= 0 || conf > 1) { err[0] = "trigger_min_confidence must be in (0,1]"; return false; }

        Object pipeline = def.get("pipeline");
        if (pipeline instanceof List) {
            for (Object step : (List<?>) pipeline) {
                if (!VALID_PIPELINE_STEPS.contains(step.toString())) {
                    err[0] = "Invalid pipeline step: " + step; return false;
                }
            }
        }

        String lifecycle = (String) def.getOrDefault("lifecycle", "new");
        if (!VALID_LIFECYCLES.contains(lifecycle)) { err[0] = "Invalid lifecycle: " + lifecycle; return false; }

        return true;
    }

    /** 扫描scripts/目录 */
    private List<Map<String, Object>> scanScripts(String dirPath) {
        Path scriptsDir = Path.of(dirPath, "scripts");
        if (!Files.isDirectory(scriptsDir)) return List.of();

        List<Map<String, Object>> scripts = new ArrayList<>();
        try (Stream<Path> paths = Files.list(scriptsDir)) {
            paths.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                if (name.endsWith(".py")) {
                    Map<String, Object> s = new HashMap<>();
                    s.put("name", name.substring(0, name.length() - 3));
                    s.put("path", p.toString());
                    scripts.add(s);
                }
            });
        } catch (IOException e) { /* ignore */ }
        return scripts;
    }
}
