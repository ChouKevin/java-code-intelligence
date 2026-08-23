package com.java.semantic.semantic.application;

/** exact target 在指定 snapshot 找不到宣告 */
public final class SourceDeclarationNotFoundException extends RuntimeException {

    public SourceDeclarationNotFoundException() {
        super("Exact source declaration was not found");
    }
}
