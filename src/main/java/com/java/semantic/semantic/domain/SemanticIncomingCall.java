package com.java.semantic.semantic.domain;

import org.springframework.util.Assert;

import java.util.List;
import java.util.Objects;

/** A locally resolvable caller returned by the semantic call hierarchy. */
public record SemanticIncomingCall(
        SemanticMethod caller,
        String rawSignature,
        List<SemanticRange> callSites) {

    public SemanticIncomingCall {
        caller = Objects.requireNonNull(caller, "caller is required");
        rawSignature = Objects.requireNonNull(rawSignature, "rawSignature is required");
        callSites = List.copyOf(Objects.requireNonNull(callSites, "callSites is required"));
        Assert.notEmpty(callSites, "callSites are required");
    }
}
