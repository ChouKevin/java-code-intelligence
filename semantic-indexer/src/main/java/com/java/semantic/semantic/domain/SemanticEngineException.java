package com.java.semantic.semantic.domain;

import java.util.Objects;

/** 可安全跨越 semantic adapter 邊界的引擎失敗分類 */
public abstract class SemanticEngineException extends RuntimeException {

    private final String errorCode;

    protected SemanticEngineException(String errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode is required");
    }

    public final String errorCode() {
        return errorCode;
    }
}
