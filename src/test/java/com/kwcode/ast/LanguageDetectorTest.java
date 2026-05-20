package com.kwcode.ast;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LanguageDetector单元测试
 * @origin kaiwu/ast/language.py::detect_language
 */
class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    @Test
    @DisplayName("Language枚举fromExtension应正确识别.py")
    void testPythonByExtension() {
        Language lang = Language.fromExtension(".py");
        assertEquals(Language.PYTHON, lang, ".py应识别为Python");
    }

    @Test
    @DisplayName("Language枚举fromExtension应正确识别.java")
    void testJavaByExtension() {
        Language lang = Language.fromExtension(".java");
        assertEquals(Language.JAVA, lang, ".java应识别为Java");
    }

    @Test
    @DisplayName("Language枚举fromExtension应正确识别.js")
    void testJsByExtension() {
        Language lang = Language.fromExtension(".js");
        assertEquals(Language.JAVASCRIPT, lang, ".js应识别为JavaScript");
    }

    @Test
    @DisplayName("Language枚举fromExtension应正确识别.ts")
    void testTsByExtension() {
        Language lang = Language.fromExtension(".ts");
        assertEquals(Language.TYPESCRIPT, lang, ".ts应识别为TypeScript");
    }

    @Test
    @DisplayName("detectProjectLanguages应返回非null列表")
    void testDetectProjectLanguages() {
        var langs = detector.detectProjectLanguages(".");
        assertNotNull(langs, "检测结果不应为null");
    }

    @Test
    @DisplayName("getPrimaryLanguage应返回非null")
    void testGetPrimaryLanguage() {
        Language lang = detector.getPrimaryLanguage(".");
        assertNotNull(lang, "主语言不应为null");
    }
}
