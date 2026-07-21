package com.java.semantic.syntax.domain;

import java.io.Serial;

/** 語法層抽取失敗 */
public class SyntaxExtractionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SyntaxExtractionException(String message) {
        super(message);
    }

    public SyntaxExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
