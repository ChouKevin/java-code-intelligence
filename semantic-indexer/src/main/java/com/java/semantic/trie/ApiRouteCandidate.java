package com.java.semantic.trie;

import java.util.List;
import java.util.Objects;

import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.syntax.domain.MethodTargetResolution;

/** 服務邊界的 API 路由候選資料，欄位順序刻意與 {@link ApiEntryPointRef} 不同 */
public record ApiRouteCandidate(
        String repoId,
        String analyzedRevision,
        String httpMethod,
        String routeTemplate,
        SourceTypeIdentity sourceType,
        String methodName,
        MethodTargetResolution analysisTarget,
        List<ApiRouteMatchReason> matchReasons) {

    public ApiRouteCandidate {
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
        matchReasons = List.copyOf(Objects.requireNonNull(matchReasons, "matchReasons are required"));
        if (analysisTarget.target().isPresent()) {
            requireMatchingResolvedTarget(sourceType, methodName, analysisTarget.target().orElseThrow());
        }
    }

    public static ApiRouteCandidate from(ApiRouteMatch match) {
        Objects.requireNonNull(match, "match is required");
        ApiEntryPointRef ref = match.ref();
        return new ApiRouteCandidate(
                ref.repoId(),
                ref.analyzedRevision(),
                ref.httpMethod(),
                ref.routeTemplate(),
                ref.sourceType(),
                ref.methodName(),
                ref.analysisTarget(),
                match.matchReasons());
    }

    private static void requireMatchingResolvedTarget(
            SourceTypeIdentity sourceType,
            String methodName,
            MethodTarget target) {
        if (!sourceType.equals(target.sourceType()) || !target.methodName().equals(methodName)) {
            throw new IllegalArgumentException("resolved target must match route source type and method name");
        }
    }
}
