package com.java.semantic.api.dto;

import com.java.semantic.model.codefact.CodeFactSearchResult;

/** Search response always echoes the repository identity and selected revision. */
public record SearchCodeFactsResponse(String repositoryId, String revision, CodeFactSearchResult result) {
    public static SearchCodeFactsResponse from(CodeFactSearchResult result) {
        return new SearchCodeFactsResponse(result.generation().repositoryId().value(), result.generation().revision().value(), result);
    }
}
