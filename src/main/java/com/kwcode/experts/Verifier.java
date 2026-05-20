package com.kwcode.experts;

import com.kwcode.core.context.TaskContext;
import com.kwcode.core.gap.GapDetector;
import com.kwcode.tools.ToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 验证器专家 - 语法检查+测试执行
 * <p>
 * 1. 应用补丁（通过ToolGateway）
 * 2. 运行语法检查
 * 3. 运行测试
 * 4. 用GapDetector分析测试结果
 * </p>
 * @origin Python: experts.verifier.Verifier
 */
public class Verifier {

    private static final Logger log = LoggerFactory.getLogger(Verifier.class);

    private final ToolGateway tools;
    private final GapDetector gapDetector;

    public Verifier(ToolGateway tools, GapDetector gapDetector) {
        this.tools = tools;
        this.gapDetector = gapDetector;
    }

    /**
     * 验证补丁
     * <p>
     * 应用补丁 → 语法检查 → 运行测试 → 分析结果
     * </p>
     * @origin Python: experts.verifier.Verifier.verify(ctx) -> dict
     */
    private static final Map<String, String> LANG_DEFAULT_TEST_CMD = Map.of(
        "python", "python -m pytest -x -q",
        "java", "mvn test -q",
        "go", "go test ./...",
        "rust", "cargo test",
        "typescript", "npx jest --passWithNoTests",
        "javascript", "npx jest --passWithNoTests"
    );

    public TaskContext.VerifierResult verify(TaskContext ctx) {
        tools.setExpert("verifier");

        int patchesApplied = 0;
        int patchesFailed = 0;
        if (ctx.generatorOutput != null) {
            for (TaskContext.Patch patch : ctx.generatorOutput.patches()) {
                boolean ok = tools.applyPatch(patch.file(), patch.original(), patch.modified());
                if (ok) patchesApplied++;
                else patchesFailed++;
            }
        }

        boolean syntaxOk = true;
        String testCmd = resolveTestCmd(ctx);

        int testsPassed = 0;
        int testsTotal = 0;
        String testOutput = "";
        String errorType = "";

        if (testCmd != null && !testCmd.isEmpty()) {
            var result = tools.runBash(testCmd, ctx.projectRoot, 120);
            testOutput = result.stdout() + "\n" + result.stderr();

            if (testOutput.contains("passed")) {
                testsPassed = extractNumber(testOutput, "(\\d+) passed");
                testsTotal = testsPassed + extractNumber(testOutput, "(\\d+) failed");
            } else if (testOutput.contains("Tests run:") || testOutput.contains("tests run:")) {
                testsTotal = extractNumber(testOutput, "Tests run: (\\d+)");
                if (testsTotal == 0) testsTotal = extractNumber(testOutput, "tests run: (\\d+)");
                int failures = extractNumber(testOutput, "Failures: (\\d+)");
                testsPassed = testsTotal - failures;
            } else if (result.returnCode() == 0) {
                testsPassed = 1; testsTotal = 1;
            } else {
                testsTotal = 1;
            }
        }

        boolean passed = false;
        if (testOutput.isEmpty() && patchesFailed == 0 && patchesApplied > 0) {
            passed = true;
        } else {
            GapDetector.Gap gap = gapDetector.compute(testOutput, ctx.projectRoot);
            passed = gap.gapType() == GapDetector.GapType.NONE;
            errorType = gap.gapType().getKey();
        }

        if (patchesFailed > 0 && syntaxOk) {
            syntaxOk = false;
            errorType = "patch_apply";
        }

        log.info("[verifier] passed={}, syntax={}, tests={}/{}, patches={}/{}",
            passed, syntaxOk, testsPassed, testsTotal, patchesApplied, patchesApplied + patchesFailed);

        return new TaskContext.VerifierResult(
            passed, syntaxOk, testsPassed, testsTotal,
            errorType, testOutput.length() > 500 ? testOutput.substring(0, 500) : testOutput
        );
    }

    private String resolveTestCmd(TaskContext ctx) {
        if (ctx.confirmedTestCmd != null && !ctx.confirmedTestCmd.isEmpty()) {
            return ctx.confirmedTestCmd;
        }
        String lang = ctx.projectLang;
        if (lang != null && !lang.isEmpty() && !"unknown".equals(lang)) {
            String cmd = LANG_DEFAULT_TEST_CMD.get(lang);
            if (cmd != null) {
                if ("java".equals(lang) && cmd.startsWith("mvn")) {
                    if (!Files.exists(Path.of(ctx.projectRoot, "pom.xml"))) {
                        log.info("[verifier] Java project has no pom.xml, skipping mvn test cmd");
                        return "";
                    }
                }
                log.info("[verifier] using lang-based default test cmd: lang={} cmd={}", lang, cmd);
                return cmd;
            }
        }
        return "";
    }

    /** 从文本中提取数字 */
    private int extractNumber(String text, String pattern) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
