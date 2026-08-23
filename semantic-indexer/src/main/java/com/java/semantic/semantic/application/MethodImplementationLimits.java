package com.java.semantic.semantic.application;

/** 固定 revision 單次探索的候選數量界限 */
public record MethodImplementationLimits(
        int limit,
        int returnedCount,
        int totalCount,
        boolean truncated) {

    public MethodImplementationLimits {
        if (limit < 0) {
            throw new IllegalArgumentException("candidateLimit must not be negative");
        }
        if (returnedCount < 0) {
            throw new IllegalArgumentException("returnedCount must not be negative");
        }
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative");
        }
        if (returnedCount > limit) {
            throw new IllegalArgumentException("returnedCount must not exceed candidateLimit");
        }
        if (returnedCount > totalCount) {
            throw new IllegalArgumentException("returnedCount must not exceed totalCount");
        }
        if (truncated != (totalCount > returnedCount)) {
            throw new IllegalArgumentException("truncated must exactly reflect omitted candidates");
        }
    }
}
