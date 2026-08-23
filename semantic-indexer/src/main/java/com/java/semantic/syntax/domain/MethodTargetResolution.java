package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.model.codefact.MethodTarget;

/** 驗證單一方法宣告解析為 canonical 目標的結果 */
public record MethodTargetResolution(
        AnalysisTargetStatus status,
        Optional<MethodTarget> target,
        List<MethodTarget> candidates,
        String reasonCode) {

    public MethodTargetResolution {
        status = Objects.requireNonNull(status, "status is required");
        target = Objects.requireNonNull(target, "target is required");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
        reasonCode = Objects.requireNonNull(reasonCode, "reasonCode is required");
        switch (status) {
            case RESOLVED -> {
                require(target.isPresent(), "resolved target is required");
                require(candidates.isEmpty(), "resolved candidates must be empty");
                require(reasonCode.isEmpty(), "resolved reasonCode must be empty");
            }
            case UNRESOLVED -> {
                require(target.isEmpty(), "unresolved target must be empty");
                require(candidates.isEmpty(), "unresolved candidates must be empty");
                require(hasText(reasonCode), "unresolved reasonCode is required");
            }
            case AMBIGUOUS -> {
                require(target.isEmpty(), "ambiguous target must be empty");
                require(candidates.size() > 1, "ambiguous candidates must contain multiple targets");
                require(Set.copyOf(candidates).size() == candidates.size(),
                        "ambiguous candidates must be unique");
                require(hasText(reasonCode), "ambiguous reasonCode is required");
            }
        }
    }

    public static MethodTargetResolution resolved(MethodTarget target) {
        return new MethodTargetResolution(AnalysisTargetStatus.RESOLVED, Optional.of(target), List.of(), "");
    }

    public static MethodTargetResolution unresolved(String reasonCode) {
        return new MethodTargetResolution(AnalysisTargetStatus.UNRESOLVED, Optional.empty(), List.of(), reasonCode);
    }

    private static boolean hasText(String value) {
        return !Objects.requireNonNullElse(value, "").isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
