package com.kwcode.ast;

/**
 * 支持的编程语言枚举
 * <p>
 * 定义项目支持的所有编程语言，包含文件扩展名映射和显示名称。
 * </p>
 * @origin Python: ast_engine.language_detector.SUPPORTED_LANGUAGES
 */
public enum Language {

    PYTHON("python", new String[]{".py"}),
    JAVASCRIPT("javascript", new String[]{".js", ".mjs"}),
    TYPESCRIPT("typescript", new String[]{".ts", ".tsx"}),
    GO("go", new String[]{".go"}),
    RUST("rust", new String[]{".rs"}),
    JAVA("java", new String[]{".java"}),
    CSHARP("csharp", new String[]{".cs"});

    private final String key;
    private final String[] extensions;

    Language(String key, String[] extensions) {
        this.key = key;
        this.extensions = extensions;
    }

    public String getKey() {
        return key;
    }

    public String[] getExtensions() {
        return extensions;
    }

    /**
     * 根据文件扩展名检测语言类型
     * @origin Python: ast_engine.language_detector.detect_language_for_file(file_path: str) -> Optional[str]
     * @param extension 文件扩展名（如".py"、".java"）
     * @return 对应的语言枚举，未匹配返回null
     */
    public static Language fromExtension(String extension) {
        if (extension == null) return null;
        String ext = extension.toLowerCase();
        for (Language lang : values()) {
            for (String langExt : lang.extensions) {
                if (langExt.equals(ext)) {
                    return lang;
                }
            }
        }
        return null;
    }
}
