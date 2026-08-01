package com.java.semantic.syntax.adapter.jdt;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 單次 resolver batch parse 的 source-root partition authority */
record JdtParseContext(
        List<Path> sourceRoots,
        List<SourceFile> files,
        Set<String> collidingRootPaths,
        Map<String, List<String>> sourcepathEntriesBySourceFile) {

    JdtParseContext {
        sourceRoots = List.copyOf(Objects.requireNonNull(sourceRoots, "sourceRoots are required"));
        files = List.copyOf(Objects.requireNonNull(files, "files are required"));
        collidingRootPaths = Set.copyOf(Objects.requireNonNull(
                collidingRootPaths, "collidingRootPaths are required"));
        Map<String, List<String>> entries = new LinkedHashMap<>();
        Objects.requireNonNull(sourcepathEntriesBySourceFile, "sourcepathEntriesBySourceFile is required")
                .forEach((sourceFile, sourcepathEntries) ->
                        entries.put(sourceFile, List.copyOf(sourcepathEntries)));
        sourcepathEntriesBySourceFile = Map.copyOf(entries);
    }

    List<String> sourcepathEntriesFor(SourceFile sourceFile) {
        String absolutePath = sourceFile.path().toAbsolutePath().normalize().toString();
        List<String> entries = sourcepathEntriesBySourceFile.get(absolutePath);
        if (Objects.isNull(entries)) {
            throw new IllegalArgumentException("source file does not belong to parse context");
        }
        return entries;
    }
}
