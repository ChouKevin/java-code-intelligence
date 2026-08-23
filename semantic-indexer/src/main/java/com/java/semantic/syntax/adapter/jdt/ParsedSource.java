package com.java.semantic.syntax.adapter.jdt;

import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * 已解析的編譯單元，連同它的來源檔案資訊
 *
 * @param source 原始檔
 * @param unit   JDT Core AST 根節點
 * @param text   解析時讀取的原始碼快照
 */
record ParsedSource(SourceFile source, CompilationUnit unit, String text) {
}
