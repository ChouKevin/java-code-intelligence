package com.java.semantic.trie;

import com.java.semantic.syntax.domain.MethodTargetResolution;

import java.util.Objects;

/** 單一儲存庫 API handler 的 trie 內部資料 */
public record ApiEntryPointRef(
        String repoId,
        String analyzedRevision,
        String packageName,
        String className,
        String methodName,
        String httpMethod,
        String routeTemplate,
        MethodTargetResolution analysisTarget) {

    public ApiEntryPointRef {
        analysisTarget = Objects.requireNonNull(analysisTarget, "analysisTarget is required");
    }

}
