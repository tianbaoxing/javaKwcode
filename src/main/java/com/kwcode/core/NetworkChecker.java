package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.time.Duration;

/**
 * 网络检测器 - 检测网络连通性和外部服务可用性
 * <p>
 * 纯确定性，零LLM调用。用于EnvProber和搜索模块降级决策。
 * </p>
 * @origin Python: core.network.NetworkChecker
 */
public class NetworkChecker {

    private static final Logger log = LoggerFactory.getLogger(NetworkChecker.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private static final String[] CHECK_URLS = {
        "https://html.duckduckgo.com",
        "https://api.openrouter.ai",
        "https://www.google.com"
    };

    private Boolean lastCheckResult;
    private long lastCheckTime;
    private static final long CACHE_TTL_MS = 60_000;

    /**
     * 检测网络是否可用
     * <p>
     * 带缓存，60秒内不重复检测。
     * </p>
     * @origin Python: core.network.NetworkChecker.is_available() -> bool
     * @return true表示网络可用
     */
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (lastCheckResult != null && (now - lastCheckTime) < CACHE_TTL_MS) {
            return lastCheckResult;
        }

        lastCheckResult = checkNetwork();
        lastCheckTime = now;
        return lastCheckResult;
    }

    /**
     * 检测指定URL是否可达
     * @origin Python: core.network.NetworkChecker.can_reach(url) -> bool
     * @param url 目标URL
     * @return true表示可达
     */
    public boolean canReach(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout((int) TIMEOUT.toMillis());
            conn.setReadTimeout((int) TIMEOUT.toMillis());
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0 && code < 500;
        } catch (IOException e) {
            log.debug("[network] Cannot reach {}: {}", url, e.getMessage());
            return false;
        }
    }

    /**
     * 检测LLM服务是否可用
     * @origin Python: core.network.NetworkChecker.is_llm_available(provider) -> bool
     * @param provider LLM提供商URL
     * @return true表示LLM服务可用
     */
    public boolean isLlmAvailable(String provider) {
        if (provider == null || provider.isEmpty()) return false;
        if (provider.contains("localhost") || provider.contains("127.0.0.1")) {
            return canReach(provider + "/api/tags");
        }
        return canReach(provider);
    }

    /**
     * 获取网络状态摘要
     * @origin Python: core.network.NetworkChecker.get_status() -> dict
     * @return 状态Map
     */
    public java.util.Map<String, Object> getStatus() {
        java.util.Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("network_available", isAvailable());

        java.util.Map<String, Boolean> services = new java.util.LinkedHashMap<>();
        for (String url : CHECK_URLS) {
            String host = url.replace("https://", "").split("/")[0];
            services.put(host, canReach(url));
        }
        status.put("services", services);

        return status;
    }

    private boolean checkNetwork() {
        for (String url : CHECK_URLS) {
            if (canReach(url)) {
                log.debug("[network] Network available (reached {})", url);
                return true;
            }
        }
        log.debug("[network] Network unavailable");
        return false;
    }

    public void invalidateCache() {
        lastCheckResult = null;
        lastCheckTime = 0;
    }
}
