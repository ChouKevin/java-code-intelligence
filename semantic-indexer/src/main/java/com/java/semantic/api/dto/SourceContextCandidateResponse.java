package com.java.semantic.api.dto;

/** source context ambiguity 的 closed wire variant */
public sealed interface SourceContextCandidateResponse permits
        SourceTypeContextCandidateResponse,
        SourceMethodContextCandidateResponse {
}
