package com.kwcode.tools;

import com.kwcode.core.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 工具网关 - 专家/工具分层，权限检查+事件emit+文件读缓存+脏标记
 * <p>
 * 正确分层：专家层（只做生成）→ ToolGateway（权限+缓存+事件）→ ToolExecutor（执行）
 * deny-first权限模型：每个专家只能调用白名单内的工具。
 * </p>
 * @origin Python: tools.tool_gateway.ToolGateway
 */
public class ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ToolGateway.class);

    /**
     * 每个专家允许调用的工具白名单
     * @origin Python: tools.tool_gateway.EXPERT_PERMISSIONS
     */
    public static final Map<String, List<String>> EXPERT_PERMISSIONS = Map.of(
        "locator",   List.of("read_file", "list_dir"),
        "generator", List.of("read_file"),
        "verifier",  List.of("apply_patch", "write_file", "run_bash", "read_file"),
        "debugger",  List.of("read_file", "run_bash"),
        "reviewer",  List.of("read_file"),
        "office",    List.of("write_file", "read_file"),
        "vision",    List.of("read_file"),
        "chat",      List.of("read_file", "run_bash", "list_dir"),
        "search",    List.of()
    );

    private final ToolExecutor executor;
    private final EventBus bus;
    private String expert = "unknown";
    private final Map<String, String> cache = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();

    public ToolGateway(ToolExecutor executor, EventBus bus) {
        this.executor = executor;
        this.bus = bus != null ? bus : new EventBus();
    }

    public ToolGateway(ToolExecutor executor) {
        this(executor, null);
    }

    /**
     * 设置当前专家身份（用于权限检查）
     * @origin Python: tools.tool_gateway.ToolGateway.set_expert(name)
     */
    public void setExpert(String name) { this.expert = name; }

    public String currentExpert() { return this.expert; }

    /**
     * 读取文件，带缓存。脏文件自动刷新缓存。
     * @origin Python: tools.tool_gateway.ToolGateway.read_file(path) -> str
     */
    public String readFile(String path) {
        check("read_file");
        if (dirty.remove(path)) cache.remove(path);
        if (cache.containsKey(path)) return cache.get(path);

        bus.emit("reading_file", Map.of("path", path, "expert", expert));
        String content = executor.readFile(path);
        if (!content.startsWith("[ERROR]")) cache.put(path, content);
        return content;
    }

    /**
     * 写入文件，标记为脏
     * @origin Python: tools.tool_gateway.ToolGateway.write_file(path, content) -> bool
     */
    public boolean writeFile(String path, String content) {
        check("write_file");
        bus.emit("writing_file", Map.of("path", path, "expert", expert));
        boolean result = executor.writeFile(path, content);
        if (result) {
            dirty.add(path);
            cache.remove(path);
            bus.emit("file_written", Map.of("path", path));
        }
        return result;
    }

    /**
     * 应用patch，标记文件为脏
     * @origin Python: tools.tool_gateway.ToolGateway.apply_patch(path, original, modified) -> bool
     */
    public boolean applyPatch(String path, String original, String modified) {
        check("apply_patch");
        bus.emit("applying_patch", Map.of("path", path, "expert", expert));
        boolean result = executor.applyPatch(path, original, modified);
        if (result) { dirty.add(path); cache.remove(path); }
        bus.emit("patch_result", Map.of("path", path, "success", result));
        return result;
    }

    /**
     * 执行Shell命令
     * @origin Python: tools.tool_gateway.ToolGateway.run_bash(cmd, cwd, timeout) -> str
     */
    public ToolExecutor.BashResult runBash(String cmd, String cwd, int timeout) {
        check("run_bash");
        bus.emit("running_cmd", Map.of("cmd", cmd.substring(0, Math.min(80, cmd.length())), "expert", expert));
        return executor.runBash(cmd, cwd, timeout);
    }

    /**
     * 列出目录内容
     * @origin Python: tools.tool_gateway.ToolGateway.list_dir(path) -> list
     */
    public List<String> listDir(String path) {
        check("list_dir");
        return executor.listDir(path);
    }

    /**
     * 权限检查：当前专家是否有权调用此工具
     * @origin Python: tools.tool_gateway.ToolGateway._check(tool)
     */
    private void check(String tool) {
        List<String> allowed = EXPERT_PERMISSIONS.getOrDefault(expert, List.of());
        if (!allowed.contains(tool)) {
            String msg = "[" + expert + "] 无权调用 " + tool + "，允许：" + allowed;
            log.warn("[gateway] {}", msg);
            throw new SecurityException(msg);
        }
    }

    /**
     * 重置缓存和脏标记（新任务开始时调用）
     * @origin Python: tools.tool_gateway.ToolGateway.reset_session()
     */
    public void resetSession() { cache.clear(); dirty.clear(); }

    /**
     * 手动标记文件为脏
     * @origin Python: tools.tool_gateway.ToolGateway.invalidate(path)
     */
    public void invalidate(String path) { dirty.add(path); cache.remove(path); }

    /** 当前缓存的文件数 */
    public int cacheSize() { return cache.size(); }
}
