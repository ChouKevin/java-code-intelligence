package com.java.semantic.api.dto;

import java.util.List;

public record EntryPointClassResponse(
        String className,
        String packageName,
        String packagePath,
        String description,
        List<String> basePaths,
        List<EntryPointMethodResponse> methods) {

    public EntryPointClassResponse {
        basePaths = List.copyOf(basePaths);
        methods = List.copyOf(methods);
    }
}
