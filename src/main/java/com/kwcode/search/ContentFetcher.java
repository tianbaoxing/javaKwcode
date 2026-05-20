package com.kwcode.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 网页内容抓取器 - 抓取URL内容并提取正文
 * <p>
 * 使用Jsoup解析HTML，提取正文文本。
 * 内网/离线环境静默降级，不报错不阻塞流水线。
 * </p>
 * @origin Python: search/content_fetcher.ContentFetcher
 */
public class ContentFetcher {

    private static final Logger log = LoggerFactory.getLogger(ContentFetcher.class);

    private static final int TIMEOUT_SECONDS = 10;
    private static final int MAX_CONTENT_LENGTH = 5000;

    private final HttpClient httpClient;

    public ContentFetcher() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 抓取URL内容并提取正文
     * <p>
     * 失败时静默返回空字符串，不阻塞流水线。
     * </p>
     * @origin Python: search/content_fetcher.ContentFetcher.fetch(url) -> str
     * @param url 目标URL
     * @return 提取的正文文本
     */
    public String fetch(String url) {
        if (url == null || url.isEmpty()) return "";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.debug("[content_fetcher] Non-200 status for {}: {}", url, resp.statusCode());
                return "";
            }

            return extractText(resp.body());
        } catch (Exception e) {
            log.debug("[content_fetcher] Fetch failed for {}: {}", url, e.getMessage());
            return "";
        }
    }

    /**
     * 批量抓取多个URL
     * @origin Python: search/content_fetcher.ContentFetcher.fetch_batch(urls) -> list[str]
     * @param urls URL列表
     * @param maxUrls 最大抓取数量
     * @return 正文文本列表
     */
    public java.util.List<String> fetchBatch(java.util.List<String> urls, int maxUrls) {
        java.util.List<String> results = new java.util.ArrayList<>();
        int count = 0;

        for (String url : urls) {
            if (count >= maxUrls) break;
            String content = fetch(url);
            if (!content.isEmpty()) {
                results.add(content);
                count++;
            }
        }

        return results;
    }

    /**
     * 从HTML中提取正文文本
     */
    private String extractText(String html) {
        try {
            var doc = org.jsoup.Jsoup.parse(html);

            doc.select("script, style, nav, header, footer, aside, .sidebar, .ad, .advertisement").remove();

            String text = doc.body().text();

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "...";
            }

            return text.strip();
        } catch (Exception e) {
            log.debug("[content_fetcher] HTML parse failed: {}", e.getMessage());
            return "";
        }
    }
}
