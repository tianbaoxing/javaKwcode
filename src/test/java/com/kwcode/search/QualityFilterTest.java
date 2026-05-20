package com.kwcode.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QualityFilter单元测试
 * @origin kaiwu/search/quality_filter.py::QualityFilter
 */
class QualityFilterTest {

    private QualityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new QualityFilter();
    }

    @Test
    @DisplayName("空结果列表过滤后应为空")
    void testEmptyInput() {
        List<Map<String, String>> results = filter.filterResults(Collections.emptyList());
        assertNotNull(results, "过滤结果不应为null");
        assertTrue(results.isEmpty(), "空输入应返回空列表");
    }

    @Test
    @DisplayName("高质量域名结果应保留")
    void testHighQualityDomain() {
        List<Map<String, String>> input = new ArrayList<>();
        Map<String, String> item = new HashMap<>();
        item.put("url", "https://docs.oracle.com/javase/17/");
        item.put("title", "Java Documentation");
        item.put("snippet", "Official Java docs");
        input.add(item);

        List<Map<String, String>> results = filter.filterResults(input);
        assertFalse(results.isEmpty(), "高质量域名结果应保留");
    }

    @Test
    @DisplayName("filterResults带maxFetch参数应正常工作")
    void testFilterWithMaxFetch() {
        List<Map<String, String>> input = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Map<String, String> item = new HashMap<>();
            item.put("url", "https://example.com/page" + i);
            item.put("title", "Page " + i);
            input.add(item);
        }

        List<Map<String, String>> results = filter.filterResults(input, 5);
        assertTrue(results.size() <= 5, "结果不应超过maxFetch");
    }
}
