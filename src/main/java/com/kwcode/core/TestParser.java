package com.kwcode.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试结果解析器 - 从pytest/jest/junit输出中提取结构化测试结果
 * <p>
 * 纯确定性解析，零LLM调用。
 * 支持 pytest、jest、mocha、junit 格式。
 * </p>
 * @origin Python: core.test_parser.TestParser
 */
public class TestParser {

    private static final Logger log = LoggerFactory.getLogger(TestParser.class);

    private static final Pattern PYTEST_PASSED = Pattern.compile("(\\d+) passed");
    private static final Pattern PYTEST_FAILED = Pattern.compile("(\\d+) failed");
    private static final Pattern PYTEST_ERROR = Pattern.compile("(\\d+) error");
    private static final Pattern PYTEST_SKIPPED = Pattern.compile("(\\d+) skipped");
    private static final Pattern PYTEST_FAILED_TEST = Pattern.compile("FAILED (.+?)(?: - |$)", Pattern.MULTILINE);
    private static final Pattern PYTEST_ERROR_BLOCK = Pattern.compile("__(.+?)__\\s*\\nE\\s+(.+?)(?:\\n|$)", Pattern.MULTILINE);

    private static final Pattern JEST_PASSED = Pattern.compile("Tests:\\s+(\\d+) passed");
    private static final Pattern JEST_FAILED = Pattern.compile("(\\d+) failed");
    private static final Pattern JEST_TEST_SUITE = Pattern.compile("Test Suites:\\s+(\\d+) passed.*?(\\d+) failed");

    private static final Pattern JUNIT_TESTCASE = Pattern.compile(
        "<testcase\\s+name=\"([^\"]+)\"\\s+classname=\"([^\"]+)\"[^>]*>(.*?)</testcase>",
        Pattern.DOTALL
    );
    private static final Pattern JUNIT_FAILURE = Pattern.compile("<failure[^>]*>(.*?)</failure>", Pattern.DOTALL);
    private static final Pattern JUNIT_ERROR = Pattern.compile("<error[^>]*>(.*?)</error>", Pattern.DOTALL);

    /**
     * 解析测试输出
     * <p>
     * 自动检测格式（pytest/jest/junit），提取结构化结果。
     * </p>
     * @origin Python: core.test_parser.TestParser.parse(output) -> TestResult
     * @param output 测试命令的stdout+stderr
     * @return 解析结果
     */
    public static TestResult parse(String output) {
        if (output == null || output.isEmpty()) {
            return new TestResult(0, 0, 0, List.of(), List.of(), "unknown", "");
        }

        if (output.contains("<testsuite") || output.contains("<testcase")) {
            return parseJUnit(output);
        }

        if (output.contains("FAIL  ") || output.contains("PASS  ") || output.contains("Test Suites:")) {
            return parseJest(output);
        }

        return parsePytest(output);
    }

    /**
     * 解析pytest输出
     * @origin Python: core.test_parser.TestParser._parse_pytest(output)
     */
    public static TestResult parsePytest(String output) {
        int passed = extractInt(output, PYTEST_PASSED);
        int failed = extractInt(output, PYTEST_FAILED);
        int errors = extractInt(output, PYTEST_ERROR);
        int skipped = extractInt(output, PYTEST_SKIPPED);

        List<String> failedTests = new ArrayList<>();
        Matcher m = PYTEST_FAILED_TEST.matcher(output);
        while (m.find()) {
            failedTests.add(m.group(1).trim());
        }

        List<String> errorMessages = new ArrayList<>();
        Matcher em = PYTEST_ERROR_BLOCK.matcher(output);
        while (em.find() && errorMessages.size() < 10) {
            errorMessages.add(em.group(1) + ": " + em.group(2).trim());
        }

        String errorType = classifyErrorType(output);

        return new TestResult(
            passed, failed + errors, skipped,
            failedTests, errorMessages, "pytest", errorType
        );
    }

    /**
     * 解析jest/mocha输出
     * @origin Python: core.test_parser.TestParser._parse_jest(output)
     */
    public static TestResult parseJest(String output) {
        int passed = extractInt(output, JEST_PASSED);
        int failed = extractInt(output, JEST_FAILED);

        List<String> failedTests = new ArrayList<>();
        Pattern failPattern = Pattern.compile("FAIL\\s+(.+?)(?:\\n|$)");
        Matcher m = failPattern.matcher(output);
        while (m.find()) {
            failedTests.add(m.group(1).trim());
        }

        String errorType = classifyErrorType(output);

        return new TestResult(
            passed, failed, 0,
            failedTests, List.of(), "jest", errorType
        );
    }

    /**
     * 解析JUnit XML输出
     * @origin Python: core.test_parser.TestParser._parse_junit(output)
     */
    public static TestResult parseJUnit(String output) {
        int passed = 0;
        int failed = 0;
        int errors = 0;
        List<String> failedTests = new ArrayList<>();
        List<String> errorMessages = new ArrayList<>();

        Matcher tc = JUNIT_TESTCASE.matcher(output);
        while (tc.find()) {
            String name = tc.group(1);
            String classname = tc.group(2);
            String body = tc.group(3);

            Matcher fm = JUNIT_FAILURE.matcher(body);
            Matcher em = JUNIT_ERROR.matcher(body);

            if (fm.find()) {
                failed++;
                failedTests.add(classname + "." + name);
                String msg = fm.group(1).trim();
                if (msg.length() > 200) msg = msg.substring(0, 200);
                if (errorMessages.size() < 10) errorMessages.add(msg);
            } else if (em.find()) {
                errors++;
                failedTests.add(classname + "." + name);
                String msg = em.group(1).trim();
                if (msg.length() > 200) msg = msg.substring(0, 200);
                if (errorMessages.size() < 10) errorMessages.add(msg);
            } else {
                passed++;
            }
        }

        String errorType = classifyErrorType(output);

        return new TestResult(
            passed, failed + errors, 0,
            failedTests, errorMessages, "junit", errorType
        );
    }

    /**
     * 分类错误类型
     */
    private static String classifyErrorType(String output) {
        String lower = output.toLowerCase();
        if (lower.contains("syntaxerror") || lower.contains("syntax error") || lower.contains("indentationerror")) {
            return "syntax";
        }
        if (lower.contains("importerror") || lower.contains("modulenotfounderror") || lower.contains("nomodule")) {
            return "import";
        }
        if (lower.contains("assertionerror") || lower.contains("assert") || lower.contains("expect(")) {
            return "assertion";
        }
        if (lower.contains("typeerror") || lower.contains("valueerror") || lower.contains("keyerror")
            || lower.contains("attributeerror") || lower.contains("nullpointer")) {
            return "runtime";
        }
        return "other";
    }

    private static int extractInt(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 测试解析结果
     */
    public record TestResult(
        int passed,
        int failed,
        int skipped,
        List<String> failedTests,
        List<String> errorMessages,
        String format,
        String errorType
    ) {
        public int total() { return passed + failed; }
        public boolean allPassed() { return failed == 0 && passed > 0; }
    }
}
