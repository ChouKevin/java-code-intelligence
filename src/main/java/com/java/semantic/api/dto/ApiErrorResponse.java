package com.java.semantic.api.dto;

import java.util.Optional;

/** 穩定且不洩漏內部例外的 API 錯誤 */
public record ApiErrorResponse(
        String errorCode,
        String message,
        Optional<String> currentRevision,
        Optional<String> expectedRevision) {

    public static ApiErrorResponse of(String errorCode, String message) {
        return new ApiErrorResponse(errorCode, message, Optional.empty(), Optional.empty());
    }

    public static ApiErrorResponse revisionMismatch(
            String currentRevision,
            String expectedRevision) {
        return new ApiErrorResponse(
                "REPOSITORY_REVISION_MISMATCH",
                "expected revision does not match current revision",
                Optional.of(currentRevision),
                Optional.of(expectedRevision));
    }
}
