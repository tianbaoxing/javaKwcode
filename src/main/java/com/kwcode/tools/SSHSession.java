package com.kwcode.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH远程会话 - 通过SSH连接远程主机执行命令
 * <p>
 * 支持两种模式：
 * 1. 本地模式：直接执行本地命令（默认）
 * 2. SSH模式：通过ProcessBuilder调用ssh命令连接远程主机
 * </p>
 * <p>
 * 安全设计：
 * - 不存储密码，使用SSH key或ssh-agent
 * - 命令超时保护
 * - 连接池复用
 * </p>
 * @origin Python: tools.ssh_session.SSHSession
 */
public class SSHSession {

    private static final Logger log = LoggerFactory.getLogger(SSHSession.class);

    private static final int DEFAULT_TIMEOUT = 120;
    private static final int CONNECT_TIMEOUT = 10;

    private final String host;
    private final String user;
    private final int port;
    private final String keyFile;
    private final boolean isLocal;
    private final Map<String, String> envVars;
    private final ToolExecutor executor;

    private boolean connected = false;
    private String projectRoot;

    public SSHSession(String host, String user, int port, String keyFile, String projectRoot) {
        this.host = host;
        this.user = user;
        this.port = port;
        this.keyFile = keyFile;
        this.isLocal = false;
        this.envVars = new ConcurrentHashMap<>();
        this.projectRoot = projectRoot != null ? projectRoot : ".";
        this.executor = new ToolExecutor(this.projectRoot);
    }

    public SSHSession(String host, String user, int port, String keyFile) {
        this(host, user, port, keyFile, ".");
    }

    public SSHSession() {
        this.host = "localhost";
        this.user = System.getProperty("user.name");
        this.port = 22;
        this.keyFile = null;
        this.isLocal = true;
        this.envVars = new ConcurrentHashMap<>();
        this.projectRoot = ".";
        this.executor = new ToolExecutor(".");
        this.connected = true;
    }

    /**
     * 连接远程主机
     * <p>
     * 验证SSH连接可用性。本地模式直接返回true。
     * </p>
     * @origin Python: tools.ssh_session.SSHSession.connect() -> bool
     * @return true表示连接成功
     */
    public boolean connect() {
        if (isLocal) return true;

        try {
            String cmd = buildSshPrefix() + "echo ok";
            var result = executor.runBash(cmd, ".", CONNECT_TIMEOUT);
            boolean ok = result.returnCode() == 0 && result.stdout().strip().equals("ok");
            if (ok) {
                connected = true;
                log.info("[ssh] Connected to {}@{}:{}", user, host, port);
            }
            return ok;
        } catch (Exception e) {
            log.warn("[ssh] Connection failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 在远程/本地执行命令
     * <p>
     * 本地模式直接执行，SSH模式通过ssh命令转发。
     * </p>
     * @origin Python: tools.ssh_session.SSHSession.run(cmd, cwd, timeout) -> dict
     * @param cmd 命令
     * @param cwd 工作目录
     * @param timeout 超时秒数
     * @return 执行结果
     */
    public ToolExecutor.BashResult run(String cmd, String cwd, int timeout) {
        if (timeout <= 0) timeout = DEFAULT_TIMEOUT;

        if (isLocal) {
            return executor.runBash(cmd, cwd != null ? cwd : ".", timeout);
        }

        if (!connected) {
            return new ToolExecutor.BashResult("", "SSH not connected", -1);
        }

        String remoteCmd = buildSshPrefix() + "cd " + (cwd != null ? cwd : "~") + " && " + cmd;
        return executor.runBash(remoteCmd, ".", timeout);
    }

    /**
     * 上传文件到远程
     * <p>
     * 使用scp命令上传文件。
     * </p>
     * @origin Python: tools.ssh_session.SSHSession.upload(local_path, remote_path) -> bool
     * @param localPath 本地文件路径
     * @param remotePath 远程文件路径
     * @return true表示成功
     */
    public boolean upload(String localPath, String remotePath) {
        if (isLocal) {
            try {
                java.nio.file.Files.copy(Path.of(localPath), Path.of(remotePath));
                return true;
            } catch (Exception e) {
                log.warn("[ssh] Local copy failed: {}", e.getMessage());
                return false;
            }
        }

        try {
            String target = user + "@" + host + ":" + remotePath;
            String scpCmd = "scp -P " + port;
            if (keyFile != null) scpCmd += " -i " + keyFile;
            scpCmd += " " + localPath + " " + target;

            var result = executor.runBash(scpCmd, ".", 60);
            return result.returnCode() == 0;
        } catch (Exception e) {
            log.warn("[ssh] Upload failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 下载远程文件
     * @origin Python: tools.ssh_session.SSHSession.download(remote_path, local_path) -> bool
     * @param remotePath 远程文件路径
     * @param localPath 本地文件路径
     * @return true表示成功
     */
    public boolean download(String remotePath, String localPath) {
        if (isLocal) {
            try {
                java.nio.file.Files.copy(Path.of(remotePath), Path.of(localPath));
                return true;
            } catch (Exception e) {
                log.warn("[ssh] Local copy failed: {}", e.getMessage());
                return false;
            }
        }

        try {
            String source = user + "@" + host + ":" + remotePath;
            String scpCmd = "scp -P " + port;
            if (keyFile != null) scpCmd += " -i " + keyFile;
            scpCmd += " " + source + " " + localPath;

            var result = executor.runBash(scpCmd, ".", 60);
            return result.returnCode() == 0;
        } catch (Exception e) {
            log.warn("[ssh] Download failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 设置环境变量
     */
    public void setEnv(String key, String value) {
        envVars.put(key, value);
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        connected = false;
        log.debug("[ssh] Disconnected");
    }

    /**
     * 获取连接信息
     */
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("host", host);
        info.put("user", user);
        info.put("port", port);
        info.put("is_local", isLocal);
        info.put("connected", connected);
        return info;
    }

    private String buildSshPrefix() {
        StringBuilder sb = new StringBuilder("ssh -o StrictHostKeyChecking=no -o ConnectTimeout=");
        sb.append(CONNECT_TIMEOUT);
        sb.append(" -p ").append(port);
        if (keyFile != null) sb.append(" -i ").append(keyFile);
        sb.append(" ").append(user).append("@").append(host).append(" ");
        return sb.toString();
    }

    public boolean isConnected() { return connected; }
    public boolean isLocal() { return isLocal; }
    public String getHost() { return host; }
}
