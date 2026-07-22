package com.java.semantic.api.dto;

import com.java.semantic.syntax.domain.EntryPointType;

import java.util.List;
import java.util.Objects;

public record ApiEntryPointMethodResponse(
        String name,
        String description,
        EntryPointType type,
        String apiUrl,
        List<String> httpMethods,
        List<String> swaggerDescriptions,
        MethodTargetResolutionResponse analysisTarget) implements EntryPointMethodResponse {

    public ApiEntryPointMethodResponse {
        type = Objects.requireNonNull(type, "type is required");
        if (!EntryPointType.API.equals(type)) {
            throw new IllegalArgumentException("API response requires API type");
        }
        httpMethods = List.copyOf(httpMethods);
        swaggerDescriptions = List.copyOf(swaggerDescriptions);
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }
}
