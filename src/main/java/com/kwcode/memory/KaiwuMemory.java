package com.kwcode.memory;

import com.kwcode.core.context.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.*;
import java.util.*;

/**
 * 天工记忆门面 - 三层记忆系统的统一入口
 * <p>
 * 委托给三层记忆系统（project_md, expert_md, pattern_md）。
 * 文件存储在 {project_root}/.kaiwu/ 目录下。
 * 旧版KAIWU.md在项目根目录仅做迁移读取，新写入全部到.kaiwu/。
 * </p>
 * @origin Python: memory.kaiwu_md.KaiwuMemory
 */
public class KaiwuMemory {

    private static final Logger log = LoggerFactory.getLogger(KaiwuMemory.class);

    private final ProjectMd projectMd = new ProjectMd();
    private final ExpertMd expertMd = new ExpertMd();
    private final PatternMd patternMd = new PatternMd();
    private final SessionMd sessionMd = new SessionMd();

    /**
     * 加载记忆供Gate上下文注入（基础信息section）
     * @origin Python: memory.kaiwu_md.KaiwuMemory.load(project_root) -> str
     */
    public String load(String projectRoot) {
        return projectMd.loadForGate(projectRoot);
    }

    /**
     * 保存到所有三层记忆
     * <p>
     * project_md和expert_md只在verifier通过时写入。
     * pattern_md始终记录（追踪成功和失败）。
     * </p>
     * @origin Python: memory.kaiwu_md.KaiwuMemory.save(project_root, ctx, elapsed)
     */
    public void save(String projectRoot, TaskContext ctx, double elapsed) {
        boolean passed = false;
        if (ctx.verifierOutput != null && ctx.verifierOutput.passed()) {
            passed = true;
        } else {
            String expertType = (String) ctx.gateResult.getOrDefault("expert_type", "");
            if ("doc".equals(expertType) || "office".equals(expertType)) passed = true;
        }

        if (passed) {
            projectMd.save(projectRoot, ctx);
            expertMd.save(projectRoot, ctx, elapsed);
        }

        patternMd.update(projectRoot, ctx, passed, elapsed);
    }

    /**
     * 记录失败任务（只有pattern_md追踪失败）
     * @origin Python: memory.kaiwu_md.KaiwuMemory.save_failure(project_root, ctx, elapsed)
     */
    public void saveFailure(String projectRoot, TaskContext ctx, double elapsed) {
        patternMd.update(projectRoot, ctx, false, elapsed);
    }

    /**
     * 初始化.kaiwu/目录和PROJECT.md
     * @origin Python: memory.kaiwu_md.KaiwuMemory.init(project_root) -> str
     */
    public String init(String projectRoot) {
        try { Files.createDirectories(Path.of(projectRoot, ".kaiwu")); } catch (Exception e) { /* ignore */ }
        return projectMd.init(projectRoot);
    }

    /**
     * 显示所有三层记忆文件
     * @origin Python: memory.kaiwu_md.KaiwuMemory.show(project_root) -> str
     */
    public String show(String projectRoot) {
        return "═══ PROJECT.md ═══\n" + projectMd.show(projectRoot) +
            "\n\n═══ EXPERT.md ═══\n" + expertMd.show(projectRoot) +
            "\n\n═══ PATTERN.md ═══\n" + patternMd.show(projectRoot);
    }

    /**
     * 加载会话摘要
     */
    public String loadSession(String projectRoot) {
        return sessionMd.loadSession(projectRoot);
    }

    // ── Section-specific loaders for pipeline stages ──

    public String loadForGate(String projectRoot) { return projectMd.loadForGate(projectRoot); }
    public String loadForLocator(String projectRoot) { return projectMd.loadForLocator(projectRoot); }
    public String loadForVerifier(String projectRoot) { return projectMd.loadForVerifier(projectRoot); }

    // ── Getters for direct access ──

    public ProjectMd getProjectMd() { return projectMd; }
    public ExpertMd getExpertMd() { return expertMd; }
    public PatternMd getPatternMd() { return patternMd; }
    public SessionMd getSessionMd() { return sessionMd; }
}
