package com.java.semantic.api;

import com.java.semantic.api.dto.DiscoverMethodImplementationsResponse;
import com.java.semantic.api.dto.MethodImplementationCandidateResponse;
import com.java.semantic.api.dto.MethodImplementationLimitsResponse;
import com.java.semantic.api.dto.MethodImplementationResolutionResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.api.dto.SemanticImplementationIssueSummaryResponse;
import com.java.semantic.callgraph.application.ImplementationCandidate;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.semantic.application.MethodImplementationIssueReason;
import com.java.semantic.semantic.application.MethodImplementationLimits;
import com.java.semantic.semantic.application.RevisionBoundMethodImplementations;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 將方法實作探索結果轉換為穩定 HTTP 回應 */
@Component
public final class MethodImplementationDiscoveryResponseMapper {

    public DiscoverMethodImplementationsResponse toResponse(RevisionBoundMethodImplementations discovery) {
        Objects.requireNonNull(discovery, "discovery is required");
        return new DiscoverMethodImplementationsResponse(
                discovery.repositoryId().value(),
                discovery.revision().value(),
                target(discovery.requestedTarget()),
                discovery.candidates().stream().map(this::candidate).toList(),
                limits(discovery.limits()),
                resolution(discovery.issues()));
    }

    private MethodImplementationCandidateResponse candidate(ImplementationCandidate candidate) {
        return new MethodImplementationCandidateResponse(
                target(candidate.target()),
                candidate.primary(),
                candidate.qualifiers(),
                candidate.profiles());
    }

    private MethodImplementationLimitsResponse limits(MethodImplementationLimits limits) {
        return new MethodImplementationLimitsResponse(
                limits.candidateLimit(),
                limits.returnedCount(),
                limits.totalCount(),
                limits.truncated());
    }

    private MethodImplementationResolutionResponse resolution(List<MethodImplementationIssueReason> issues) {
        Map<MethodImplementationIssueReason, Integer> counts = new EnumMap<>(MethodImplementationIssueReason.class);
        for (MethodImplementationIssueReason issue : issues) {
            counts.merge(issue, 1, Integer::sum);
        }
        List<SemanticImplementationIssueSummaryResponse> summaries = Arrays.stream(MethodImplementationIssueReason.values())
                .filter(counts::containsKey)
                .map(issue -> new SemanticImplementationIssueSummaryResponse(issue.name(), counts.get(issue)))
                .toList();
        boolean partial = counts.containsKey(MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED)
                || counts.containsKey(MethodImplementationIssueReason.CANONICAL_TARGET_UNRESOLVED);
        MethodImplementationResolutionResponse.Status status = partial
                ? MethodImplementationResolutionResponse.Status.PARTIAL
                : MethodImplementationResolutionResponse.Status.COMPLETE;
        return new MethodImplementationResolutionResponse(status, summaries);
    }

    private MethodTargetResponse target(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }
}
