package com.java.semantic.syntax.application;

/** canonical source segment 的安全檔案或內容不存在 */
public final class SourceSegmentNotFoundException extends RuntimeException {

    public SourceSegmentNotFoundException() {
        super("Source segment was not found");
    }
}
