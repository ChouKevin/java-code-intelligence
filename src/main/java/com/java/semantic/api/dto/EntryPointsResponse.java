package com.java.semantic.api.dto;

import java.util.List;

public record EntryPointsResponse(
        String repoId,
        String analyzedRevision,
        List<EntryPointClassResponse> entryPoints) {

    public EntryPointsResponse {
        entryPoints = List.copyOf(entryPoints);
    }
}
