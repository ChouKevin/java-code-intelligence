package com.java.semantic.syntax.application;

/** Java source segment 的安全檔案或內容不存在 */
public final class SourceSegmentNotFoundException extends RuntimeException {

    public SourceSegmentNotFoundException() {
        super("Java source segment was not found");
    }
}
