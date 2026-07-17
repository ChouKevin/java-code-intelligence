package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

/** 工作區內找不到要解析的符號 */
public final class SemanticSymbolNotFoundException extends RuntimeException {

    public SemanticSymbolNotFoundException(String message) {
        super(message);
        Assert.hasText(message, "message is required");
    }
}
