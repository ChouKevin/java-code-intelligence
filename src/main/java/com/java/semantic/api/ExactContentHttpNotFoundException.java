package com.java.semantic.api;

/** exact content HTTP 邊界使用且不攜帶敏感內容的 typed not-found */
public final class ExactContentHttpNotFoundException extends RuntimeException {

    public ExactContentHttpNotFoundException() {
        super("exact content was not found");
    }
}
