package com.java.semantic.api.dto;

/** source-symbol candidate 的 closed wire variant */
public sealed interface SourceSymbolCandidateResponse permits
        VariableLikeSourceSymbolCandidateResponse,
        StaticConstantSourceSymbolCandidateResponse,
        MethodSourceSymbolCandidateResponse,
        SourceTypeSymbolCandidateResponse {
}
