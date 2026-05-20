package com.kwcode.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IntentClassifier单元测试
 * @origin kaiwu/search/intent_classifier.py::IntentClassifier
 */
class IntentClassifierTest {

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new IntentClassifier();
    }

    @Test
    @DisplayName("错误修复意图应被识别为debug")
    void testRepairIntent() {
        String intent = classifier.classify("修复这个空指针异常");
        assertEquals("debug", intent, "错误修复应识别为debug");
    }

    @Test
    @DisplayName("代码生成意图应被识别为general")
    void testGenerateIntent() {
        String intent = classifier.classify("如何实现一个快速排序");
        assertEquals("general", intent, "代码生成应识别为general");
    }

    @Test
    @DisplayName("解释类意图应被识别为general")
    void testExplainIntent() {
        String intent = classifier.classify("解释一下这段代码的含义");
        assertEquals("general", intent, "解释应识别为general");
    }

    @Test
    @DisplayName("空输入应返回默认意图")
    void testEmptyInput() {
        String intent = classifier.classify("");
        assertNotNull(intent, "空输入应返回非null意图");
    }
}
