package com.kwcode.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 搜索结果质量过滤器：基于域名的优先级排序 + 黑名单过滤
 * 优先域名排前面，屏蔽域名直接移除，最多返回maxFetch条
 * @origin Python: search/quality_filter.py
 */
public class QualityFilter {

    private static final Logger log = LoggerFactory.getLogger(QualityFilter.class);

    /** 高质量域名（匹配顺序即优先级） */
    private static final List<String> PRIORITY_DOMAINS = List.of(
        "github.com",
        "stackoverflow.com",
        "docs.python.org",
        "pytorch.org",
        "huggingface.co",
        "arxiv.org",
        "pypi.org",
        "developer.mozilla.org",
        "learn.microsoft.com",
        "numpy.org",
        "pandas.pydata.org",
        "docs.rs"
    );

    /** 屏蔽域名（低质量/社交媒体/内容农场） */
    private static final List<String> BLOCKED_DOMAINS = List.of(
        "csdn.net",
        "baidu.com",
        "zhihu.com",
        "weibo.com",
        "twitter.com",
        "x.com",
        "facebook.com",
        "reddit.com",
        "tiktok.com",
        "pinterest.com",
        "medium.com",
        "quora.com"
    );

    /**
     * 过滤并排序搜索结果
     * @param results 搜索结果列表，每项包含url/title/snippet
     * @param maxFetch 最多返回条数
     * @return 过滤+排序后的结果列表
     */
    public List<Map<String, String>> filterResults(List<Map<String, String>> results, int maxFetch) {
        List<Map.Entry<Map<String, String>, Integer>> filtered = new ArrayList<>();

        for (Map<String, String> r : results) {
            String url = r.getOrDefault("url", "");
            String domain = extractDomain(url);
            if (domain.isEmpty() || isBlocked(domain)) {
                continue;
            }
            filtered.add(Map.entry(r, priorityScore(domain)));
        }

        // 按优先级排序（分数相同保持原始顺序）
        filtered.sort(Comparator.comparingInt(Map.Entry::getValue));

        List<Map<String, String>> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxFetch, filtered.size()); i++) {
            result.add(filtered.get(i).getKey());
        }
        return result;
    }

    /**
     * 过滤搜索结果（默认最多3条）
     * @param results 搜索结果列表
     * @return 过滤后的结果
     */
    public List<Map<String, String>> filterResults(List<Map<String, String>> results) {
        return filterResults(results, 3);
    }

    /**
     * 提取URL的主域名（去掉www.前缀）
     * @param url 完整URL
     * @return 主域名
     */
    private static String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return "";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 检查域名是否在黑名单中（支持子域名匹配）
     * @param domain 待检查域名
     * @return 是否被屏蔽
     */
    private static boolean isBlocked(String domain) {
        for (String bd : BLOCKED_DOMAINS) {
            if (domain.equals(bd) || domain.endsWith("." + bd)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 优先域名返回其索引（越小越优先），非优先域名返回大值
     * @param domain 待评分域名
     * @return 优先级分数
     */
    private static int priorityScore(String domain) {
        for (int i = 0; i < PRIORITY_DOMAINS.size(); i++) {
            String pd = PRIORITY_DOMAINS.get(i);
            if (domain.equals(pd) || domain.endsWith("." + pd)) {
                return i;
            }
        }
        return PRIORITY_DOMAINS.size();
    }
}
