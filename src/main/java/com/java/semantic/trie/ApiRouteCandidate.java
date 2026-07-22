package com.java.semantic.trie;

import java.util.Objects;

import com.java.semantic.syntax.domain.MethodTargetResolution;

/** 服務邊界的 API 路由候選資料，欄位順序刻意與 {@link ApiEntryPointRef} 不同 */
public record ApiRouteCandidate(
        String repoId,
        String analyzedRevision,
        String httpMethod,
        String routeTemplate,
        String packageName,
        String className,
        String methodName,
        MethodTargetResolution analysisTarget) {

    public ApiRouteCandidate {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }

    public static ApiRouteCandidate from(ApiEntryPointRef ref) {
        Objects.requireNonNull(ref, "ref is required");
        return new ApiRouteCandidate(
                ref.repoId(),
                ref.analyzedRevision(),
                ref.httpMethod(),
                ref.routeTemplate(),
                ref.packageName(),
                ref.className(),
                ref.methodName(),
                ref.analysisTarget());
    }
}
