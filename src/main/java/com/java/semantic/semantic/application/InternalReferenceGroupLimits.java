package com.java.semantic.semantic.application;

/** 單一 context 的 reference 代表樣本限制與完整計數 */
public record InternalReferenceGroupLimits(
        int limit,
        int returnedCount,
        int totalCount,
        boolean truncated) {

    public InternalReferenceGroupLimits {
        if (limit < 1 || returnedCount < 0 || totalCount < returnedCount || returnedCount > limit) {
            throw new IllegalArgumentException("reference group limits are invalid");
        }
        if (truncated != (totalCount > returnedCount)) {
            throw new IllegalArgumentException("truncated must match reference counts");
        }
    }
}
