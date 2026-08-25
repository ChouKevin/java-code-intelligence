package com.java.semantic.api.dto;

import com.java.semantic.model.codefact.CodeFactDetails;

/** Exact fact response always echoes the repository identity and selected revision. */
public record GetCodeFactResponse(String repositoryId, String revision, CodeFactDetails result) {
    public static GetCodeFactResponse from(CodeFactDetails result) {
        return new GetCodeFactResponse(result.generation().repositoryId().value(), result.generation().revision().value(), result);
    }
}
