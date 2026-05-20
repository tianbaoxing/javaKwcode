package com.kwcode.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hashline锚点编辑 - 内容哈希锚定的编辑方式
 * <p>
 * 每行获得6字符MD5哈希锚点，模型引用锚点而非复制文本。
 * 文件修改后哈希不匹配则编辑被拒绝，避免损坏。
 * 理论来源：oh-my-pi的Hashline方法，61%输出token减少。
 * </p>
 * @origin Python: tools.hashline
 */
public class Hashline {

    private static final Logger log = LoggerFactory.getLogger(Hashline.class);

    /**
     * 给文件内容的每一行加上行号和内容哈希锚点
     * <p>
     * 格式: {line_num}|{hash6}| {content}
     * </p>
     * @origin Python: tools.hashline.add_anchors(content) -> str
     */
    public String addAnchors(String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String anchor = lineHash(lines[i]);
            sb.append(i + 1).append("|").append(anchor).append("| ").append(lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 从锚点格式还原为原始代码
     * @origin Python: tools.hashline.strip_anchors(anchored_content) -> str
     */
    public String stripAnchors(String anchoredContent) {
        Pattern p = Pattern.compile("^\\d+\\|[a-f0-9]{6}\\| (.*)$");
        String[] lines = anchoredContent.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            sb.append(m.find() ? m.group(1) : lines[i]);
            if (i < lines.length - 1) sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 解析模型的锚点编辑指令
     * <p>
     * 支持三种格式：EDIT, DELETE, INSERT_AFTER
     * </p>
     * @origin Python: tools.hashline.parse_anchor_edits(model_output) -> list[dict]
     */
    public List<AnchorEdit> parseAnchorEdits(String modelOutput) {
        List<AnchorEdit> edits = new ArrayList<>();

        Pattern editPat = Pattern.compile("^EDIT\\s+(\\d+)\\|([a-f0-9]{6})\\|\\s*→\\s*(.+)$");
        Pattern deletePat = Pattern.compile("^DELETE\\s+(\\d+)\\|([a-f0-9]{6})\\|");
        Pattern insertPat = Pattern.compile("^INSERT_AFTER\\s+(\\d+)\\|([a-f0-9]{6})\\|\\s*→\\s*(.+)$");

        for (String line : modelOutput.strip().split("\n")) {
            line = line.strip();
            if (line.isEmpty()) continue;

            Matcher m;
            if ((m = editPat.matcher(line)).matches()) {
                edits.add(new AnchorEdit("edit", Integer.parseInt(m.group(1)), m.group(2), m.group(3)));
            } else if ((m = deletePat.matcher(line)).matches()) {
                edits.add(new AnchorEdit("delete", Integer.parseInt(m.group(1)), m.group(2), ""));
            } else if ((m = insertPat.matcher(line)).matches()) {
                edits.add(new AnchorEdit("insert_after", Integer.parseInt(m.group(1)), m.group(2), m.group(3)));
            }
        }
        return edits;
    }

    /**
     * 将锚点编辑指令应用到文件内容
     * <p>
     * 任何哈希不匹配的编辑被拒绝，其余正常应用。
     * 按行号逆序应用以保持行号有效。
     * </p>
     * @origin Python: tools.hashline.apply_anchor_edits(content, edits) -> tuple[str, list[str]]
     */
    public ApplyResult applyAnchorEdits(String content, List<AnchorEdit> edits) {
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));
        List<String> errors = new ArrayList<>();

        // 先验证所有哈希
        for (AnchorEdit edit : edits) {
            int idx = edit.line - 1;
            if (idx < 0 || idx >= lines.size()) {
                errors.add("Line " + edit.line + " out of range (file has " + lines.size() + " lines)");
                continue;
            }
            String expected = lineHash(lines.get(idx));
            if (!edit.hash.equals(expected)) {
                errors.add("Hash mismatch at line " + edit.line + ": expected " + expected + ", got " + edit.hash);
            }
        }
        if (!errors.isEmpty()) return new ApplyResult(content, errors);

        // 逆序应用编辑
        List<AnchorEdit> sorted = new ArrayList<>(edits);
        sorted.sort((a, b) -> {
            int lineCmp = Integer.compare(b.line, a.line); // 逆序
            if (lineCmp != 0) return lineCmp;
            return a.action.equals("insert_after") ? 1 : -1;
        });

        for (AnchorEdit edit : sorted) {
            int idx = edit.line - 1;
            switch (edit.action) {
                case "edit" -> {
                    String origIndent = getIndent(lines.get(idx));
                    String newContent = edit.content;
                    if (!newContent.startsWith(" ") && !newContent.startsWith("\t")) {
                        newContent = origIndent + newContent;
                    }
                    lines.set(idx, newContent);
                }
                case "delete" -> lines.remove(idx);
                case "insert_after" -> {
                    String origIndent = getIndent(lines.get(idx));
                    String stripped = lines.get(idx).stripTrailing();
                    boolean endsColon = stripped.endsWith(":");
                    String newContent = edit.content;
                    if (!newContent.startsWith(" ") && !newContent.startsWith("\t")) {
                        newContent = endsColon ? origIndent + "    " + newContent : origIndent + newContent;
                    } else {
                        String contentIndent = getIndent(newContent);
                        if (endsColon && contentIndent.length() <= origIndent.length()) {
                            newContent = origIndent + "    " + newContent.stripLeading();
                        }
                    }
                    lines.add(idx + 1, newContent);
                }
            }
        }

        return new ApplyResult(String.join("\n", lines), List.of());
    }

    /** 计算单行内容的6字符MD5哈希锚点 */
    private String lineHash(String line) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(line.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 6);
        } catch (Exception e) {
            return "000000";
        }
    }

    /** 提取行首缩进 */
    private String getIndent(String line) {
        int len = line.length();
        int stripped = line.stripLeading().length();
        return line.substring(0, len - stripped);
    }

    /** 锚点编辑指令 */
    public record AnchorEdit(String action, int line, String hash, String content) {}

    /** 应用结果 */
    public record ApplyResult(String content, List<String> errors) {}
}
