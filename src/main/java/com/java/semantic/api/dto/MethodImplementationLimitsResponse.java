package com.java.semantic.api.dto;

/** 方法實作探索套用後的候選數量限制 */
public record MethodImplementationLimitsResponse(
        int candidateLimit,
        int returnedCount,
        int totalCount,
        boolean truncated) {
}
