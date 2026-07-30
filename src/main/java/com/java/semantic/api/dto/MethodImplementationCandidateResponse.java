package com.java.semantic.api.dto;

import java.util.List;
import java.util.Objects;

/** 可作為後續外呼圖輸入的方法實作候選 */
public record MethodImplementationCandidateResponse(
        MethodTargetResponse target,
        boolean primary,
        List<String> qualifiers,
        List<String> profiles) {

    public MethodImplementationCandidateResponse {
        target = Objects.requireNonNull(target, "target is required");
        qualifiers = List.copyOf(Objects.requireNonNull(qualifiers, "qualifiers are required"));
        profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles are required"));
    }
}
