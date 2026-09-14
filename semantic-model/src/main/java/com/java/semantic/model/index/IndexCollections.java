package com.java.semantic.model.index;

import java.util.List;
import java.util.Map;

public final class IndexCollections {

    public static final String REPOSITORIES = "repositories";
    public static final String GENERATION_MANIFESTS = "generation_manifests";
    public static final String INDEX_JOBS = "index_jobs";
    public static final String GENERATION_FILES = "generation_files";
    public static final String SOURCE_ARTIFACTS = "source_artifacts";
    public static final String SYMBOLS = "symbols";
    public static final String RELATIONS = "relations";
    public static final String ENTRY_POINTS = "entry_points";
    public static final String SEARCH = "search";
    public static final String GIT_EVIDENCE_MANIFESTS = "git_evidence_manifests";
    public static final String GIT_BRANCHES = "git_branches";
    public static final String GIT_COMMITS = "git_commits";
    public static final String GIT_SNAPSHOT_FILES = "git_snapshot_files";
    public static final String GIT_SNAPSHOT_CHUNKS = "git_snapshot_chunks";
    public static final String GIT_COMPARISON_CHANGES = "git_comparison_changes";
    public static final String GIT_COMPARISON_PATCHES = "git_comparison_patches";
    public static final Map<ProjectionName, List<String>> PROJECTION_COLLECTIONS = Map.of(
            ProjectionName.SOURCES, List.of(GENERATION_FILES, SOURCE_ARTIFACTS),
            ProjectionName.SYMBOLS, List.of(SYMBOLS),
            ProjectionName.RELATIONS, List.of(RELATIONS),
            ProjectionName.ENTRY_POINTS, List.of(ENTRY_POINTS),
            ProjectionName.SEARCH, List.of(SEARCH));

    private IndexCollections() {
        throw new UnsupportedOperationException("utility class");
    }
}
