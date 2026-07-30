package com.java.semantic.api.dto;

/** 事件監聽器候選項的分頁回應 */
public record CandidatePageResponse(
        int offset,
        int limit,
        int returnedCount,
        long totalCount,
        boolean hasMore) {
}
