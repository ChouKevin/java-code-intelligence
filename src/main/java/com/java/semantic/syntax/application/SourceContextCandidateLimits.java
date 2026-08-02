package com.java.semantic.syntax.application;

/** bounded context retry collection 的完整計數 */
public record SourceContextCandidateLimits(
        int limit,
        int returnedCount,
        int totalCount,
        boolean truncated) {

    public SourceContextCandidateLimits {
        if (limit < 2 || returnedCount < 0 || totalCount < returnedCount
                || truncated != (totalCount > limit)) {
            throw new IllegalArgumentException("context candidate limits are inconsistent");
        }
    }
}
