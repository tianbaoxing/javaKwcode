package com.kwcode.search;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 四级内容提取管线
 * Pipeline:
 *   1. Jsoup智能提取（article标签优先，替代trafilatura+newspaper3k）
 *   2. Readability-style提取（main/section标签检测）
 *   3. Jsoup get_text 兜底（去除script/style/nav/footer/header/aside）
 * @origin Python: search/extraction_pipeline.py
 */
public class ExtractionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ExtractionPipeline.class);

    /** 模板板关键词（用于质量评分） */
    private static final List<String> BOILERPLATE_KEYWORDS = List.of(
        "cookie", "sign up", "newsletter", "subscribe",
        "accept all", "privacy policy", "terms of service",
        "登录", "注册", "隐私政策", "用户协议"
    );

    /** 最小内容长度 */
    private static final int MIN_CONTENT_LENGTH = 50;

    /** 模板板惩罚系数 */
    private static final int BOILERPLATE_PENALTY = 500;

    /** HTTP请求头 */
    private static final Map<String, String> HEADERS = Map.of(
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    );

    /**
     * 四级提取管线，返回最佳质量内容
     * @param html HTML内容
     * @param url 页面URL（可选）
     * @return 提取的文本，失败返回null
     */
    public String extractContent(String html, String url) {
        if (html == null || html.isBlank()) return null;

        // Level 1: Jsoup智能提取（article标签优先）
        String content = extractJsoupArticle(html);
        if (content != null && content.length() >= MIN_CONTENT_LENGTH) return content;

        // Level 2: Readability-style提取（main/section标签）
        content = extractReadabilityStyle(html);
        if (content != null && content.length() >= MIN_CONTENT_LENGTH) return content;

        // Level 3: Jsoup get_text 兜底
        content = extractSoupText(html);
        if (content != null && content.length() >= MIN_CONTENT_LENGTH) return content;

        return null;
    }

    /**
     * 抓取URL并通过管线提取内容
     * @param url 目标URL
     * @param timeoutSeconds 超时秒数
     * @param maxChars 最大字符数
     * @return 压缩后的文本，失败返回空字符串
     */
    public String fetchAndExtract(String url, double timeoutSeconds, int maxChars) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis((long)(Math.min(timeoutSeconds, 8.0) * 1000)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis((long)(Math.min(timeoutSeconds, 8.0) * 1000)));

            for (var entry : HEADERS.entrySet()) {
                reqBuilder.header(entry.getKey(), entry.getValue());
            }

            HttpResponse<String> resp = client.send(reqBuilder.GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "";

            String html = resp.body();
            String content = extractContent(html, url);
            if (content == null) return "";

            // 压缩到maxChars
            String result = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
            if (result.length() > maxChars) {
                result = result.substring(0, maxChars) + "...";
            }
            return result;
        } catch (Exception e) {
            log.debug("[pipeline] 抓取失败 {}: {}", url.substring(0, Math.min(60, url.length())), e.getMessage());
            return "";
        }
    }

    /**
     * Level 1: Jsoup article标签智能提取
     * @param html HTML内容
     * @return 提取文本，失败返回null
     */
    private String extractJsoupArticle(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // 优先查找article标签
            Element article = doc.selectFirst("article");
            if (article != null) {
                // 移除article内的导航和侧边栏
                article.select("nav, aside, footer, .sidebar, .advertisement").remove();
                String text = article.text();
                if (qualityScore(text) > 0) return text;
            }
            return null;
        } catch (Exception e) {
            log.debug("[pipeline] Jsoup article提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Level 2: Readability-style提取（main/content区域）
     * @param html HTML内容
     * @return 提取文本，失败返回null
     */
    private String extractReadabilityStyle(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // 查找main标签或role=main
            Element main = doc.selectFirst("main, [role=main], .post-content, .article-content, .entry-content");
            if (main != null) {
                main.select("nav, aside, footer, header, .sidebar, .advertisement, .comments").remove();
                String text = main.text();
                if (qualityScore(text) > 0) return text;
            }
            return null;
        } catch (Exception e) {
            log.debug("[pipeline] Readability-style提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Level 3: Jsoup get_text兜底（去除噪声标签后提取）
     * @param html HTML内容
     * @return 提取文本，失败返回null
     */
    private String extractSoupText(String html) {
        try {
            Document doc = Jsoup.parse(html);
            // 移除噪声标签
            doc.select("script, style, nav, footer, header, aside, .sidebar, .advertisement, .comments, .cookie-banner").remove();
            String text = doc.body() != null ? doc.body().text() : doc.text();
            // 清理过多空白
            text = text.replaceAll("\\n{3,}", "\n\n");
            return text.length() >= MIN_CONTENT_LENGTH ? text : null;
        } catch (Exception e) {
            log.debug("[pipeline] Soup兜底提取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 质量评分：文本长度减去模板板惩罚
     * @param text 待评分文本
     * @return 质量分数
     */
    private int qualityScore(String text) {
        if (text == null || text.isEmpty()) return 0;
        String lower = text.toLowerCase();
        int boilerplate = 0;
        for (String kw : BOILERPLATE_KEYWORDS) {
            if (lower.contains(kw)) boilerplate++;
        }
        return text.length() - (boilerplate * BOILERPLATE_PENALTY);
    }
}
