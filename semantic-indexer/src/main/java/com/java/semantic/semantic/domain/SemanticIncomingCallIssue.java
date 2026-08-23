package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.Objects;

/** A sanitized reason an incoming caller could not be represented in the domain. */
public record SemanticIncomingCallIssue(String code, String message) {

    private static final String CALLER_REJECTED = "CALLER_REJECTED";
    private static final String CALLER_REJECTED_MESSAGE = "incoming caller was rejected";

    public SemanticIncomingCallIssue {
        code = Objects.requireNonNull(code, "code is required");
        message = Objects.requireNonNull(message, "message is required");
        Assert.isTrue(CALLER_REJECTED.equals(code), "unsupported incoming call issue code");
    }

    public static SemanticIncomingCallIssue callerRejected() {
        return new SemanticIncomingCallIssue(CALLER_REJECTED, CALLER_REJECTED_MESSAGE);
    }
}
