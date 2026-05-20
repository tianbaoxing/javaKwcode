package com.kwcode.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图感知搜索路由，零key默认可用
 * 分层架构：
 *   Layer 0：专项API（零key，最精准）
 *     - arxiv.org API → 研究论文
 *     - Semantic Scholar → 学术搜索
 *     - GitHub REST API → 开源代码（60次/小时）
 *     - PyPI JSON API → 包文档
 *     - Open-Meteo API → 天气数据
 *   Layer 1：DuckDuckGo（零key，通用搜索）
 *   Layer 2：Tavily（可选key，质量提升）
 * @origin Python: search/search_router.py
 */
public class SearchRouter {

    private static final Logger log = LoggerFactory.getLogger(SearchRouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP超时（秒） */
    private static final double TIMEOUT = 10.0;

    /** HTTP客户端（复用） */
    private final HttpClient httpClient;

    /** Tavily API密钥（可选） */
    private final String tavilyKey;

    /** GitHub Token（可选） */
    private final String githubToken;

    /**
     * 构造函数
     * @param tavilyKey Tavily API密钥（可选，空字符串表示不使用）
     * @param githubToken GitHub Token（可选，空字符串表示不使用）
     */
    public SearchRouter(String tavilyKey, String githubToken) {
        this.tavilyKey = tavilyKey != null ? tavilyKey : "";
        this.githubToken = githubToken != null ? githubToken : "";
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 构造函数（无API key）
     */
    public SearchRouter() {
        this("", "");
    }

    /**
     * 按意图路由搜索
     * @param query 搜索词
     * @param intent 意图类型 (research/code_solution/code_example/weather/library_doc/general)
     * @param errorContext 错误上下文（可选）
     * @return 格式化的搜索结果文本
     */
    public String search(String query, String intent, Map<String, Object> errorContext) {
        return switch (intent) {
            case "research" -> {
                List<Map<String, String>> results = arxivSearch(query);
                if (results.isEmpty()) results = semanticScholarSearch(query);
                yield formatResults(results);
            }
            case "code_solution" -> {
                List<Map<String, String>> results = githubSearch(query);
                if (results.isEmpty()) results = duckduckgoSearch(query + " site:stackoverflow.com");
                yield formatResults(results);
            }
            case "code_example" -> formatResults(githubSearch(query));
            case "weather" -> openMeteoSearch(query);
            case "library_doc" -> {
                List<Map<String, String>> results = pypiSearch(query);
                if (results.isEmpty()) results = githubSearch(query);
                yield formatResults(results);
            }
            default -> {
                if (!tavilyKey.isEmpty()) yield tavilySearch(query);
                else yield formatResults(duckduckgoSearch(query));
            }
        };
    }

    /**
     * 按意图路由搜索（无错误上下文）
     * @param query 搜索词
     * @param intent 意图类型
     * @return 格式化的搜索结果文本
     */
    public String search(String query, String intent) {
        return search(query, intent, null);
    }

    // ==================== Layer 0: 专项API ====================

    /**
     * arXiv API搜索（零key，无限制）
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 搜索结果列表
     */
    public List<Map<String, String>> arxivSearch(String query, int maxResults) {
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = String.format(
                "http://export.arxiv.org/api/query?search_query=all:%s&max_results=%d&sortBy=relevance",
                encodedQuery, maxResults);
            String body = httpGet(url);
            if (body == null) return List.of();

            // 简单XML解析
            List<Map<String, String>> results = new ArrayList<>();
            Pattern entryPattern = Pattern.compile("<entry>(.*?)</entry>", Pattern.DOTALL);
            Matcher entryMatcher = entryPattern.matcher(body);

            int count = 0;
            while (entryMatcher.find() && count < maxResults) {
                String entry = entryMatcher.group(1);
                String title = extractXmlTag(entry, "title");
                String summary = extractXmlTag(entry, "summary");
                String id = extractXmlTag(entry, "id");
                if (title != null) {
                    Map<String, String> result = new LinkedHashMap<>();
                    result.put("title", title.replace("\n", " ").trim());
                    result.put("content", summary != null ? summary.substring(0, Math.min(500, summary.length())) : "");
                    result.put("url", id != null ? id.trim() : "");
                    results.add(result);
                    count++;
                }
            }
            return results;
        } catch (Exception e) {
            log.debug("[search_router] arxiv失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** arXiv搜索（默认5条） */
    public List<Map<String, String>> arxivSearch(String query) {
        return arxivSearch(query, 5);
    }

    /**
     * Semantic Scholar API搜索（零key，AI相关性排序）
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 搜索结果列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> semanticScholarSearch(String query, int maxResults) {
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = String.format(
                "https://api.semanticscholar.org/graph/v1/paper/search?query=%s&limit=%d&fields=title,abstract,url",
                encodedQuery, maxResults);
            String body = httpGet(url);
            if (body == null) return List.of();

            Map<String, Object> data = MAPPER.readValue(body, Map.class);
            List<Map<String, Object>> papers = (List<Map<String, Object>>) data.getOrDefault("data", List.of());
            List<Map<String, String>> results = new ArrayList<>();
            for (Map<String, Object> paper : papers) {
                Map<String, String> result = new LinkedHashMap<>();
                result.put("title", String.valueOf(paper.getOrDefault("title", "")));
                String abstractText = paper.get("abstract") != null ? paper.get("abstract").toString() : "";
                result.put("content", abstractText.substring(0, Math.min(500, abstractText.length())));
                result.put("url", String.valueOf(paper.getOrDefault("url", "")));
                results.add(result);
            }
            return results;
        } catch (Exception e) {
            log.debug("[search_router] semantic_scholar失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** Semantic Scholar搜索（默认5条） */
    public List<Map<String, String>> semanticScholarSearch(String query) {
        return semanticScholarSearch(query, 5);
    }

    /**
     * GitHub Code/Repo搜索（零key 60次/小时，有token 5000次/小时）
     * @param query 搜索词
     * @param maxResults 最大结果数
     * @return 搜索结果列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> githubSearch(String query, int maxResults) {
        try {
            String encodedQuery = java.net.URLEncoder.encode(query, "UTF-8");
            String url = String.format(
                "https://api.github.com/search/repositories?q=%s&sort=stars&per_page=%d",
                encodedQuery, maxResults);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
                .timeout(Duration.ofSeconds((long) TIMEOUT));
            if (!githubToken.isEmpty()) {
                reqBuilder.header("Authorization", "token " + githubToken);
            }

            HttpResponse<String> resp = httpClient.send(reqBuilder.GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return List.of();

            Map<String, Object> data = MAPPER.readValue(resp.body(), Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.getOrDefault("items", List.of());
            List<Map<String, String>> results = new ArrayList<>();
            for (Map<String, Object> repo : items) {
                Map<String, String> result = new LinkedHashMap<>();
                result.put("title", String.valueOf(repo.getOrDefault("full_name", "")));
                String desc = repo.get("description") != null ? repo.get("description").toString() : "";
                result.put("content", desc.substring(0, Math.min(300, desc.length())));
                result.put("url", String.valueOf(repo.getOrDefault("html_url", "")));
                results.add(result);
            }
            return results;
        } catch (Exception e) {
            log.debug("[search_router] github失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** GitHub搜索（默认5条） */
    public List<Map<String, String>> githubSearch(String query) {
        return githubSearch(query, 5);
    }

    /**
     * PyPI JSON API搜索（零key，包信息查询）
     * @param query 搜索词
     * @return 搜索结果列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> pypiSearch(String query) {
        try {
            String packageName = query.strip().split("\\s+")[0].toLowerCase().replace(" ", "-");
            String url = "https://pypi.org/pypi/" + packageName + "/json";
            String body = httpGet(url);
            if (body == null) return List.of();

            Map<String, Object> data = MAPPER.readValue(body, Map.class);
            Map<String, Object> info = (Map<String, Object>) data.getOrDefault("info", Map.of());

            Map<String, String> result = new LinkedHashMap<>();
            result.put("title", info.getOrDefault("name", "") + " " + info.getOrDefault("version", ""));
            String summary = String.valueOf(info.getOrDefault("summary", ""));
            String description = info.get("description") != null ? info.get("description").toString() : "";
            result.put("content", (summary + "\n" + description).substring(0, Math.min(500, (summary + "\n" + description).length())));
            result.put("url", String.valueOf(info.getOrDefault("package_url", info.getOrDefault("project_url", ""))));

            return List.of(result);
        } catch (Exception e) {
            log.debug("[search_router] pypi失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Open-Meteo天气API搜索（零key，完全免费）
     * @param query 天气查询
     * @return 格式化的天气文本
     */
    @SuppressWarnings("unchecked")
    public String openMeteoSearch(String query) {
        try {
            // 从query提取城市名
            String city = query.replaceAll("(天气|气温|温度|weather|forecast|的|查|看)", "").strip();
            if (city.isEmpty()) city = "Beijing";

            // Geocoding
            String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh";
            String geoBody = httpGet(geoUrl);
            if (geoBody == null) return "";

            Map<String, Object> geoData = MAPPER.readValue(geoBody, Map.class);
            List<Map<String, Object>> geoResults = (List<Map<String, Object>>) geoData.getOrDefault("results", List.of());
            if (geoResults.isEmpty()) return "未找到城市: " + city;

            double lat = ((Number) geoResults.get(0).get("latitude")).doubleValue();
            double lon = ((Number) geoResults.get(0).get("longitude")).doubleValue();
            String name = String.valueOf(geoResults.get(0).getOrDefault("name", city));

            // Weather
            String weatherUrl = String.format(
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=auto&forecast_days=3",
                lat, lon);
            String weatherBody = httpGet(weatherUrl);
            if (weatherBody == null) return "";

            Map<String, Object> wData = MAPPER.readValue(weatherBody, Map.class);
            Map<String, Object> current = (Map<String, Object>) wData.getOrDefault("current", Map.of());
            Map<String, Object> daily = (Map<String, Object>) wData.getOrDefault("daily", Map.of());

            List<String> lines = new ArrayList<>();
            lines.add("📍 " + name + " 天气");
            if (!current.isEmpty()) {
                lines.add(String.format("当前: %s°C, 湿度 %s%%, 风速 %skm/h",
                    current.getOrDefault("temperature_2m", "?"),
                    current.getOrDefault("relative_humidity_2m", "?"),
                    current.getOrDefault("wind_speed_10m", "?")));
            }
            if (daily != null && daily.containsKey("time")) {
                List<String> times = (List<String>) daily.get("time");
                List<Object> tmax = (List<Object>) daily.getOrDefault("temperature_2m_max", List.of());
                List<Object> tmin = (List<Object>) daily.getOrDefault("temperature_2m_min", List.of());
                lines.add("未来3天:");
                for (int i = 0; i < Math.min(3, times.size()); i++) {
                    lines.add(String.format("  %s: %s~%s°C",
                        times.get(i),
                        i < tmin.size() ? tmin.get(i) : "?",
                        i < tmax.size() ? tmax.get(i) : "?"));
                }
            }
            return String.join("\n", lines);
        } catch (Exception e) {
            log.debug("[search_router] open_meteo失败: {}", e.getMessage());
            return "";
        }
    }

    // ==================== Layer 1: DuckDuckGo ====================

    /**
     * DuckDuckGo搜索（复用DuckDuckGoSearch模块）
     * @param query 搜索词
     * @return 搜索结果列表
     */
    public List<Map<String, String>> duckduckgoSearch(String query) {
        // 委托给DuckDuckGoSearch类
        DuckDuckGoSearch ddgs = new DuckDuckGoSearch();
        return ddgs.search(query, 5);
    }

    // ==================== Layer 2: Tavily ====================

    /**
     * Tavily搜索（需要key，1000次/月免费）
     * @param query 搜索词
     * @return 格式化的搜索结果文本
     */
    @SuppressWarnings("unchecked")
    public String tavilySearch(String query) {
        try {
            String jsonBody = MAPPER.writeValueAsString(Map.of(
                "api_key", tavilyKey,
                "query", query,
                "max_results", 5,
                "include_answer", true
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds((long) TIMEOUT))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "";

            Map<String, Object> data = MAPPER.readValue(resp.body(), Map.class);
            List<String> parts = new ArrayList<>();
            if (data.get("answer") != null && !data.get("answer").toString().isEmpty()) {
                parts.add("[摘要] " + data.get("answer"));
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.getOrDefault("results", List.of());
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                Map<String, Object> r = results.get(i);
                String content = r.get("content") != null ? r.get("content").toString() : "";
                parts.add(String.format("[%s](%s)\n%s",
                    r.getOrDefault("title", ""),
                    r.getOrDefault("url", ""),
                    content.substring(0, Math.min(300, content.length()))));
            }
            return String.join("\n\n", parts);
        } catch (Exception e) {
            log.debug("[search_router] tavily失败: {}", e.getMessage());
            return "";
        }
    }

    // ==================== 工具方法 ====================

    /**
     * HTTP GET请求
     * @param url 目标URL
     * @return 响应体，失败返回null
     */
    private String httpGet(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(Duration.ofSeconds((long) TIMEOUT))
                .GET()
                .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 ? resp.body() : null;
        } catch (Exception e) {
            log.debug("[search_router] HTTP GET失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从XML文本中提取标签内容
     * @param xml XML片段
     * @param tagName 标签名
     * @return 标签内容，未找到返回null
     */
    private static String extractXmlTag(String xml, String tagName) {
        Pattern p = Pattern.compile("<" + tagName + ">(.*?)</" + tagName + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 格式化搜索结果为LLM可读文本
     * @param results 搜索结果列表
     * @return 格式化文本
     */
    public static String formatResults(List<Map<String, String>> results) {
        if (results == null || results.isEmpty()) return "";
        return results.stream().limit(3)
            .map(r -> String.format("[%s](%s)\n%s",
                r.getOrDefault("title", ""),
                r.getOrDefault("url", ""),
                r.getOrDefault("content", "").substring(0, Math.min(500, r.getOrDefault("content", "").length()))))
            .reduce((a, b) -> a + "\n\n" + b)
            .orElse("");
    }
}
