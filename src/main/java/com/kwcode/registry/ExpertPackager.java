package com.kwcode.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

/**
 * 专家包管理器 - .kwx格式的导入导出
 * <p>
 * .kwx格式是zip文件，包含expert.yaml和可选的test_cases/、README.md、CHANGELOG.md。
 * </p>
 * @origin Python: registry.expert_packager.ExpertPackager
 */
public class ExpertPackager {

    private static final Logger log = LoggerFactory.getLogger(ExpertPackager.class);
    private static final String USER_EXPERTS_DIR =
        Path.of(System.getProperty("user.home"), ".kaiwu", "experts").toString();

    /**
     * 导出专家为.kwx包
     * @origin Python: registry.expert_packager.ExpertPackager.export(registry, expert_name, output_dir) -> str
     */
    public String export(ExpertRegistry registry, String expertName, String outputDir) throws IOException {
        Map<String, Object> expert = registry.get(expertName);
        if (expert == null) throw new IllegalArgumentException("Expert not found: " + expertName);

        String version = (String) expert.getOrDefault("version", "0.0.0");
        String safeName = expertName.replace(" ", "_");
        String kwxName = safeName + "-" + version + ".kwx";
        Path kwxPath = Path.of(outputDir).toAbsolutePath().resolve(kwxName);

        // 移除内部字段
        Map<String, Object> data = new LinkedHashMap<>();
        for (var e : expert.entrySet()) {
            if (!e.getKey().startsWith("_")) data.put(e.getKey(), e.getValue());
        }

        Files.createDirectories(Path.of(outputDir));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(kwxPath))) {
            Yaml yaml = new Yaml();
            String yamlContent = yaml.dump(data);
            ZipEntry entry = new ZipEntry("expert.yaml");
            zos.putNextEntry(entry);
            zos.write(yamlContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        log.info("Exported {} to {}", expertName, kwxPath);
        return kwxPath.toString();
    }

    /**
     * 从.kwx文件安装专家
     * @origin Python: registry.expert_packager.ExpertPackager.install(kwx_path, registry) -> str
     */
    public String install(String kwxPath, ExpertRegistry registry) throws IOException {
        Path path = Path.of(kwxPath).toAbsolutePath();
        if (!Files.exists(path)) throw new FileNotFoundException("File not found: " + kwxPath);
        if (!isZipFile(path)) throw new IllegalArgumentException("Not a valid .kwx file: " + kwxPath);

        Map<String, Object> expertDef;
        try (ZipFile zf = new ZipFile(path.toFile())) {
            ZipEntry yamlEntry = zf.getEntry("expert.yaml");
            if (yamlEntry == null) throw new IllegalArgumentException("Invalid .kwx: missing expert.yaml");

            String yamlContent;
            try (InputStream is = zf.getInputStream(yamlEntry)) {
                yamlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            Yaml yaml = new Yaml();
            expertDef = yaml.load(yamlContent);

            ExpertLoader loader = new ExpertLoader();
            String[] err = new String[1];
            if (!loader.validate(expertDef, err)) {
                throw new IllegalArgumentException("Invalid expert definition: " + err[0]);
            }

            // 复制expert.yaml到用户专家目录
            Files.createDirectories(Path.of(USER_EXPERTS_DIR));
            String safeName = ((String) expertDef.get("name")).toLowerCase().replace(" ", "_");
            Path dest = Path.of(USER_EXPERTS_DIR, safeName + ".yaml");
            Files.writeString(dest, yamlContent, StandardCharsets.UTF_8);

            // 解压可选的test_cases/
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.getName().startsWith("test_cases/") && !e.isDirectory()) {
                    Path target = Path.of(USER_EXPERTS_DIR, safeName + "_tests",
                        e.getName().substring("test_cases/".length()));
                    Files.createDirectories(target.getParent());
                    try (InputStream is = zf.getInputStream(e)) {
                        Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        expertDef.putIfAbsent("lifecycle", "new");
        expertDef.putIfAbsent("performance", Map.of("success_rate", 0.0, "avg_latency_s", 0, "task_count", 0));
        registry.register(expertDef);

        log.info("Installed expert {} from {}", expertDef.get("name"), kwxPath);
        return (String) expertDef.get("name");
    }

    /**
     * 移除已安装的专家
     * @origin Python: registry.expert_packager.ExpertPackager.remove(expert_name, registry) -> bool
     */
    public boolean remove(String expertName, ExpertRegistry registry) throws IOException {
        Map<String, Object> expert = registry.get(expertName);
        if (expert == null) throw new IllegalArgumentException("Expert not found: " + expertName);

        String source = (String) expert.getOrDefault("_source", "");
        if (!source.isEmpty() && Files.exists(Path.of(source)) && source.contains(USER_EXPERTS_DIR)) {
            Files.delete(Path.of(source));
        }

        String safeName = expertName.toLowerCase().replace(" ", "_");
        Path testDir = Path.of(USER_EXPERTS_DIR, safeName + "_tests");
        if (Files.isDirectory(testDir)) {
            try (Stream<Path> paths = Files.walk(testDir)) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }

        registry.remove(expertName);
        return true;
    }

    /**
     * 创建新的专家模板
     * @origin Python: registry.expert_packager.ExpertPackager.create_template(name) -> str
     */
    public String createTemplate(String name) throws IOException {
        String safeName = name.toLowerCase().replace(" ", "_");
        Files.createDirectories(Path.of(USER_EXPERTS_DIR));
        Path dest = Path.of(USER_EXPERTS_DIR, safeName + ".yaml");

        if (Files.exists(dest)) throw new FileAlreadyExistsException("Expert file exists: " + dest);

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", name);
        template.put("version", "1.0.0");
        template.put("type", "custom");
        template.put("author", "");
        template.put("trigger_keywords", List.of("keyword1", "keyword2"));
        template.put("trigger_min_confidence", 0.75);
        template.put("system_prompt", "你是一个专家。请描述你的专长和工作方式。\n");
        template.put("tool_whitelist", List.of("read_file", "write_file", "run_bash"));
        template.put("pipeline", List.of("locator", "generator", "verifier"));
        template.put("performance", Map.of("success_rate", 0.0, "avg_latency_s", 0, "task_count", 0));
        template.put("lifecycle", "new");

        Yaml yaml = new Yaml();
        Files.writeString(dest, yaml.dump(template), StandardCharsets.UTF_8);

        log.info("Created expert template: {}", dest);
        return dest.toString();
    }

    private boolean isZipFile(Path path) {
        try { return Files.probeContentType(path) != null && Files.probeContentType(path).contains("zip"); }
        catch (IOException e) { return false; }
    }
}
