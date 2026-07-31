package com.java.semantic.syntax.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.java.semantic.identity.MethodTarget;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

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
                Assert.isTrue(target.isPresent(), "resolved target is required");
                Assert.isTrue(candidates.isEmpty(), "resolved candidates must be empty");
                Assert.isTrue(reasonCode.isEmpty(), "resolved reasonCode must be empty");
            }
            case UNRESOLVED -> {
                Assert.isTrue(target.isEmpty(), "unresolved target must be empty");
                Assert.isTrue(candidates.isEmpty(), "unresolved candidates must be empty");
                Assert.isTrue(StringUtils.hasText(reasonCode), "unresolved reasonCode is required");
            }
            case AMBIGUOUS -> {
                Assert.isTrue(target.isEmpty(), "ambiguous target must be empty");
                Assert.isTrue(candidates.size() > 1, "ambiguous candidates must contain multiple targets");
                Assert.isTrue(Set.copyOf(candidates).size() == candidates.size(),
                        "ambiguous candidates must be unique");
                Assert.isTrue(StringUtils.hasText(reasonCode), "ambiguous reasonCode is required");
            }
        }
    }

    public static MethodTargetResolution resolved(MethodTarget target) {
        return new MethodTargetResolution(AnalysisTargetStatus.RESOLVED, Optional.of(target), List.of(), "");
    }

    public static MethodTargetResolution unresolved(String reasonCode) {
        return new MethodTargetResolution(AnalysisTargetStatus.UNRESOLVED, Optional.empty(), List.of(), reasonCode);
    }
}
