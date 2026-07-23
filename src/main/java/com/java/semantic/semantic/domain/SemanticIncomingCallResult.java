package com.java.semantic.semantic.domain;

import java.util.List;
import java.util.Objects;

/** Immutable result of an incoming call hierarchy query. */
public record SemanticIncomingCallResult(
        List<SemanticIncomingCall> calls,
        List<SemanticIncomingCallIssue> issues) {

    public SemanticIncomingCallResult {
        calls = List.copyOf(Objects.requireNonNull(calls, "calls are required"));
        issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
    }

    public static SemanticIncomingCallResult empty() {
        return new SemanticIncomingCallResult(List.of(), List.of());
    }
}
