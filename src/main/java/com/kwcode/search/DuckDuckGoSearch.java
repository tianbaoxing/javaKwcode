package com.kwcode.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * DuckDuckGo搜索引擎
 * Java版使用DuckDuckGo HTML搜索（无需额外Python依赖）
 * 内网/离线环境静默降级，不报错不阻塞流水线
 * @origin Python: search/duckduckgo.py
 */
public class DuckDuckGoSearch {

    private static final Logger log = LoggerFactory.getLogger(DuckDuckGoSearch.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 搜索超时（秒） */
    private static final double TIMEOUT = 10.0;

    /** HTTP客户端 */
    private final HttpClient httpClient;

    /**
     * 构造函数
     */
    public DuckDuckGoSearch() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds((long) TIMEOUT))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 搜索入口：DuckDuckGo HTML搜索
     * 内网/离线环境静默返回空列表，不报错不阻塞
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @param timeoutSeconds 超时秒数
     * @return [{url, title, snippet}, ...]
     */
    public List<Map<String, String>> search(String query, int maxResults, double timeoutSeconds) {
        try {
            // 使用DuckDuckGo的HTML搜索页面
            String url = "https://html.duckduckgo.com/html/?q=" +
                java.net.URLEncoder.encode(query, "UTF-8");

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds((long) timeoutSeconds))
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("[ddg] 搜索返回非200状态: {}", resp.statusCode());
                return List.of();
            }

            // 解析HTML结果
            return parseHtmlResults(resp.body(), maxResults);
        } catch (Exception e) {
            log.debug("[ddg] 搜索异常(静默): {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 搜索（默认超时10秒）
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 搜索结果列表
     */
    public List<Map<String, String>> search(String query, int maxResults) {
        return search(query, maxResults, TIMEOUT);
    }

    /**
     * 搜索（默认5条结果，10秒超时）
     * @param query 搜索词
     * @return 搜索结果列表
     */
    public List<Map<String, String>> search(String query) {
        return search(query, 10);
    }

    /**
     * 解析DuckDuckGo HTML搜索结果
     * @param html HTML内容
     * @param maxResults 最大结果数
     * @return 结果列表
     */
    private List<Map<String, String>> parseHtmlResults(String html, int maxResults) {
        List<Map<String, String>> results = new ArrayList<>();

        try {
            // DuckDuckGo HTML结果使用特定的class标记
            // 结果块：<div class="result results_links results_links_deep web-result">
            // 标题：<a class="result__a" href="...">
            // 摘要：<a class="result__snippet">
            var doc = org.jsoup.Jsoup.parse(html);
            var resultElements = doc.select(".result");

            for (var element : resultElements) {
                if (results.size() >= maxResults) break;

                // 提取标题和URL
                var titleLink = element.selectFirst(".result__a");
                if (titleLink == null) continue;

                String title = titleLink.text().trim();
                String resultUrl = titleLink.attr("href");

                // DuckDuckGo的URL格式: //duckduckgo.com/l/?uddg=<encoded_url>&...
                resultUrl = extractActualUrl(resultUrl);

                // 提取摘要
                var snippetEl = element.selectFirst(".result__snippet");
                String snippet = snippetEl != null ? snippetEl.text().trim() : "";

                if (!title.isEmpty()) {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("url", resultUrl);
                    result.put("title", title);
                    result.put("snippet", snippet);
                    results.add(result);
                }
            }
        } catch (Exception e) {
            log.debug("[ddg] HTML解析失败: {}", e.getMessage());
        }

        log.debug("[ddg] 返回 {} 条结果", results.size());
        return results;
    }

    /**
     * 从DuckDuckGo重定向URL中提取实际URL
     * @param ddgUrl DuckDuckGo格式的URL
     * @return 实际URL
     */
    private String extractActualUrl(String ddgUrl) {
        if (ddgUrl == null || ddgUrl.isEmpty()) return "";
        try {
            if (ddgUrl.contains("uddg=")) {
                int idx = ddgUrl.indexOf("uddg=");
                String encoded = ddgUrl.substring(idx + 5);
                int ampIdx = encoded.indexOf('&');
                if (ampIdx > 0) encoded = encoded.substring(0, ampIdx);
                return java.net.URLDecoder.decode(encoded, "UTF-8");
            }
        } catch (Exception e) {
            // 忽略解析错误
        }
        return ddgUrl;
    }
}
