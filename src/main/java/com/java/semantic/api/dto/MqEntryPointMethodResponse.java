package com.java.semantic.api.dto;

import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MqBroker;

import java.util.List;
import java.util.Objects;

public record MqEntryPointMethodResponse(
        String name,
        String description,
        EntryPointType type,
        MqBroker broker,
        List<String> destinations,
        MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public MqEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.MQ.equals(type)) {
            throw new IllegalArgumentException("MQ response requires MQ type");
        }
        destinations = List.copyOf(destinations);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
