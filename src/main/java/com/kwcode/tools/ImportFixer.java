package com.kwcode.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Import修复器 - 确定性修复缺失import，不调LLM
 * <p>
 * 当Verifier报告import错误时，尝试自动修复：
 * 从错误信息提取缺失模块名，在文件头部添加import语句。
 * </p>
 * @origin Python: tools.import_fixer
 */
public class ImportFixer {

    private static final Logger log = LoggerFactory.getLogger(ImportFixer.class);

    /**
     * 常见模块的正确import语句映射
     * @origin Python: tools.import_fixer.KNOWN_IMPORTS
     */
    public static final Map<String, String> KNOWN_IMPORTS = Map.ofEntries(
        Map.entry("json", "import json"),
        Map.entry("os", "import os"),
        Map.entry("sys", "import sys"),
        Map.entry("re", "import re"),
        Map.entry("time", "import time"),
        Map.entry("datetime", "from datetime import datetime"),
        Map.entry("pathlib", "from pathlib import Path"),
        Map.entry("typing", "from typing import Optional, List, Dict"),
        Map.entry("logging", "import logging"),
        Map.entry("subprocess", "import subprocess"),
        Map.entry("shutil", "import shutil"),
        Map.entry("collections", "from collections import defaultdict"),
        Map.entry("dataclasses", "from dataclasses import dataclass, field"),
        Map.entry("hashlib", "import hashlib"),
        Map.entry("uuid", "import uuid"),
        Map.entry("math", "import math"),
        Map.entry("random", "import random"),
        Map.entry("traceback", "import traceback"),
        Map.entry("enum", "from enum import Enum"),
        Map.entry("glob", "import glob"),
        Map.entry("io", "import io"),
        Map.entry("copy", "import copy"),
        Map.entry("functools", "import functools"),
        Map.entry("itertools", "import itertools"),
        Map.entry("asyncio", "import asyncio"),
        Map.entry("contextlib", "import contextlib"),
        Map.entry("abc", "from abc import ABC, abstractmethod"),
        Map.entry("yaml", "import yaml"),
        Map.entry("httpx", "import httpx"),
        Map.entry("pytest", "import pytest")
    );

    /**
     * 尝试修复缺失的import
     * @origin Python: tools.import_fixer.fix_missing_import(content, error_message) -> str|None
     * @param content 文件内容
     * @param errorMessage 错误信息
     * @return 修复后的内容，无法修复返回null
     */
    public String fixMissingImport(String content, String errorMessage) {
        String module = extractModuleName(errorMessage);
        if (module == null) return null;

        if (alreadyImported(content, module)) return null;

        String importStmt = buildImportStatement(module);
        if (importStmt == null) return null;

        return insertImport(content, importStmt);
    }

    /**
     * 从错误信息中提取模块名
     * @origin Python: tools.import_fixer._extract_module_name(error_message) -> str|None
     */
    private String extractModuleName(String errorMessage) {
        Pattern[] patterns = {
            Pattern.compile("No module named '(\\S+?)'"),
            Pattern.compile("No module named \"(\\S+?)\""),
            Pattern.compile("ModuleNotFoundError:.*'(\\S+?)'"),
            Pattern.compile("NameError: name '(\\w+)' is not defined")
        };
        for (Pattern p : patterns) {
            Matcher m = p.matcher(errorMessage);
            if (m.find()) return m.group(1).split("\\.")[0];
        }
        return null;
    }

    /**
     * 检查模块是否已经被import
     * @origin Python: tools.import_fixer._already_imported(content, module) -> bool
     */
    private boolean alreadyImported(String content, String module) {
        Pattern[] patterns = {
            Pattern.compile("^import\\s+" + Pattern.quote(module) + "\\b", Pattern.MULTILINE),
            Pattern.compile("^from\\s+" + Pattern.quote(module) + "\\b", Pattern.MULTILINE)
        };
        for (Pattern p : patterns) {
            if (p.matcher(content).find()) return true;
        }
        return false;
    }

    /**
     * 生成import语句
     * @origin Python: tools.import_fixer._build_import_statement(module) -> str|None
     */
    private String buildImportStatement(String module) {
        if (KNOWN_IMPORTS.containsKey(module)) return KNOWN_IMPORTS.get(module);
        if (module.matches("^[a-zA-Z_]\\w*$")) return "import " + module;
        return null;
    }

    /**
     * 在文件合适位置插入import语句
     * @origin Python: tools.import_fixer._insert_import(content, import_stmt) -> str
     */
    private String insertImport(String content, String importStmt) {
        String[] lines = content.split("\n", -1);
        int lastImportIdx = -1;

        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].strip();
            if (stripped.startsWith("import ") || stripped.startsWith("from ")) {
                lastImportIdx = i;
            } else if (!stripped.isEmpty() && !stripped.startsWith("#") &&
                       !stripped.startsWith("\"\"\"") && !stripped.startsWith("'''")) {
                if (lastImportIdx >= 0) break;
            }
        }

        List<String> result = new ArrayList<>(Arrays.asList(lines));
        if (lastImportIdx >= 0) {
            result.add(lastImportIdx + 1, importStmt);
        } else {
            result.add(0, importStmt);
        }
        return String.join("\n", result);
    }
}
