package com.java.semantic.model.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.support.ModelValidation;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Explicit persisted source metadata shape; Optional values never reach a framework mapper. */
public final class SourceEvidenceDocumentCodec {
    private SourceEvidenceDocumentCodec() { }

    public static Map<String, Object> encodePolicy(SourceEvidencePolicy policy) {
        Objects.requireNonNull(policy, "source policy is required");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("version", policy.version());
        fields.put("includedRoots", policy.includedRoots());
        fields.put("selectedCodePaths", policy.selectedCodePaths().stream().sorted().toList());
        policy.projectGuidePath().ifPresent(path -> fields.put("projectGuidePath", path));
        return Collections.unmodifiableMap(fields);
    }

    public static SourceEvidencePolicy decodePolicy(Map<String, ?> fields) {
        Objects.requireNonNull(fields, "source policy document is required");
        List<String> paths = strings(fields, "selectedCodePaths");
        Set<String> selected = Set.copyOf(paths);
        if (selected.size() != paths.size()) {
            throw new IllegalArgumentException("selected code paths must be distinct");
        }
        return new SourceEvidencePolicy(integer(fields, "version"), strings(fields, "includedRoots"),
                selected, optionalString(fields, "projectGuidePath"));
    }

    public static Map<String, Object> encodeGuide(ProjectGuideMembership guide) {
        Objects.requireNonNull(guide, "project guide is required");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", guide.state().name());
        fields.put("freshness", guide.freshness());
        if (guide.state() == ProjectGuideState.AVAILABLE) {
            String path = guide.path().orElseThrow();
            validateGuidePath(path);
            fields.put("path", path);
            fields.put("digest", ModelValidation.sha256(guide.digest().orElseThrow(), "guide digest"));
            fields.put("importedRevision", guide.importedRevision().orElseThrow().value());
            fields.put("provenance", encodeProvenance(guide.provenance().orElseThrow()));
        }
        return Collections.unmodifiableMap(fields);
    }

    public static ProjectGuideMembership decodeGuide(Map<String, ?> fields) {
        Objects.requireNonNull(fields, "project guide document is required");
        ProjectGuideState state = ProjectGuideState.valueOf(string(fields, "state"));
        String freshness = string(fields, "freshness");
        if (state != ProjectGuideState.AVAILABLE) {
            if (fields.containsKey("path") || fields.containsKey("digest") || fields.containsKey("importedRevision")
                    || fields.containsKey("provenance")) {
                throw new IllegalArgumentException("unavailable guide must omit readable metadata");
            }
            return new ProjectGuideMembership(state, Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), freshness);
        }
        String path = string(fields, "path");
        validateGuidePath(path);
        Map<?, ?> provenance = map(fields, "provenance");
        Map<?, ?> scope = map(provenance, "sourceScope");
        return new ProjectGuideMembership(state, Optional.of(path),
                Optional.of(ModelValidation.sha256(string(fields, "digest"), "guide digest")),
                Optional.of(RepositoryRevision.ofSha(string(fields, "importedRevision"))),
                Optional.of(new ProjectGuideProvenance(integer(provenance, "formatVersion"),
                        integer(provenance, "promptVersion"), RepositoryId.of(string(provenance, "repositoryId")),
                        RepositoryRevision.ofSha(string(provenance, "analyzedRevision")),
                        Instant.parse(string(provenance, "generatedAt")),
                        new ProjectGuideProvenance.SourceScope(strings(scope, "includedPaths"),
                                strings(scope, "excludedPaths"), strings(scope, "limitations")))), freshness);
    }

    public static SourceStructure decodeStructure(Map<String, ?> fields) {
        Objects.requireNonNull(fields, "source structure is required");
        return new SourceStructure(strings(fields, "importedSourceRoots"),
                counts(fields, "packageCounts"), counts(fields, "entryPointKindCounts"));
    }

    private static Map<String, Long> counts(Map<String, ?> fields, String key) {
        Map<?, ?> values = map(fields, key);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getValue() instanceof Long count)) {
                throw new IllegalArgumentException("source metadata requires long counts in " + key);
            }
            counts.put((String) entry.getKey(), count);
        }
        return counts;
    }

    private static Map<String, Object> encodeProvenance(ProjectGuideProvenance provenance) {
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("includedPaths", provenance.sourceScope().includedPaths());
        scope.put("excludedPaths", provenance.sourceScope().excludedPaths());
        scope.put("limitations", provenance.sourceScope().limitations());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("formatVersion", provenance.formatVersion());
        fields.put("promptVersion", provenance.promptVersion());
        fields.put("repositoryId", provenance.repositoryId().value());
        fields.put("analyzedRevision", provenance.analyzedRevision().value());
        fields.put("generatedAt", provenance.generatedAt().toString());
        fields.put("sourceScope", Collections.unmodifiableMap(scope));
        return Collections.unmodifiableMap(fields);
    }

    private static void validateGuidePath(String path) {
        if (!SourceEvidencePolicy.validPath(path) || !path.endsWith(".md")) {
            throw new IllegalArgumentException("guide path must be repository-relative Markdown");
        }
    }

    private static String string(Map<?, ?> fields, String key) {
        if (!(fields.get(key) instanceof String value)) {
            throw new IllegalArgumentException("source metadata requires string " + key);
        }
        return value;
    }

    private static int integer(Map<?, ?> fields, String key) {
        if (!(fields.get(key) instanceof Integer value)) {
            throw new IllegalArgumentException("source metadata requires integer " + key);
        }
        return value;
    }

    private static Optional<String> optionalString(Map<?, ?> fields, String key) {
        return fields.containsKey(key) ? Optional.of(string(fields, key)) : Optional.empty();
    }

    private static List<String> strings(Map<?, ?> fields, String key) {
        if (!(fields.get(key) instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw new IllegalArgumentException("source metadata requires string list " + key);
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static Map<?, ?> map(Map<?, ?> fields, String key) {
        if (!(fields.get(key) instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("source metadata requires document " + key);
        }
        if (values.keySet().stream().anyMatch(name -> !(name instanceof String))) {
            throw new IllegalArgumentException("source metadata document keys must be strings");
        }
        return values;
    }
}
