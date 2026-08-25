package com.java.semantic.model.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Framework-neutral Mongo collection and index contract. */
public final class IndexSchemaContract {

    public static final int SCHEMA_VERSION = 1;
    private static final Map<String, Integer> REQUIRED_PROJECTION_VERSIONS = Map.of(
            "SOURCES", 2, "SYMBOLS", 2, "RELATIONS", 2, "ENTRY_POINTS", 2, "SEARCH", 2);
    private static final List<ImmutablePayloadCollectionSpec> IMMUTABLE_PAYLOAD_COLLECTIONS = List.of(
            payload(IndexCollections.GENERATION_FILES, PayloadScope.GENERATION, "repoId", "generationId", "sourcePath"),
            payload(IndexCollections.SOURCE_ARTIFACTS, PayloadScope.GLOBAL, "sourceArtifactId"),
            payload(IndexCollections.SYMBOLS, PayloadScope.GENERATION, "repoId", "generationId", "symbolId"),
            payload(IndexCollections.RELATIONS, PayloadScope.GENERATION, "repoId", "generationId", "relationId"),
            payload(IndexCollections.ENTRY_POINTS, PayloadScope.GENERATION, "repoId", "generationId", "entryPointId"),
            payload(IndexCollections.SEARCH, PayloadScope.GENERATION, "repoId", "generationId", "factId"));

    private static final List<CollectionSpec> COLLECTIONS = List.of(
            collection(IndexCollections.REPOSITORIES, index("repository_id_unique", keys("repoId", 1), true, Map.of())),
            collection(IndexCollections.GENERATION_MANIFESTS, index("repository_generation_unique", keys("repoId", 1, "generationId", 1), true, Map.of())),
            collection(IndexCollections.INDEX_JOBS, index("job_id_unique", keys("jobId", 1), true, Map.of()),
                    index("one_active_job_per_repository", keys("repoId", 1), true, Map.of("active", true))),
            collection(IndexCollections.GENERATION_FILES, index("generation_file_unique", keys("repoId", 1, "generationId", 1, "sourcePath", 1), true, Map.of())),
            collection(IndexCollections.SOURCE_ARTIFACTS, index("source_artifact_id_unique", keys("sourceArtifactId", 1), true, Map.of()),
                    index("content_hash_unique", keys("contentHash", 1), true, Map.of())),
            collection(IndexCollections.SYMBOLS, index("symbol_unique", keys("repoId", 1, "generationId", 1, "symbolId", 1), true, Map.of()),
                    index("symbol_canonical", keys("repoId", 1, "generationId", 1, "canonical", 1), false, Map.of()),
                    index("symbol_owner_name_source", keys("repoId", 1, "generationId", 1, "scopePackage", 1, "scopeClass", 1, "scopeMethod", 1, "scopeParameters", 1, "owner", 1, "name", 1, "sourcePath", 1), false, Map.of())),
            collection(IndexCollections.RELATIONS, index("relation_unique", keys("repoId", 1, "generationId", 1, "relationId", 1), true, Map.of()),
                    index("relation_from_target_source", keys("repoId", 1, "generationId", 1, "from", 1, "target", 1, "sourcePath", 1), false, Map.of()),
                    index("relation_target_kind_source", keys("repoId", 1, "generationId", 1, "target", 1, "kind", 1,
                            "from", 1, "sourcePath", 1, "relationId", 1), false, Map.of())),
            collection(IndexCollections.ENTRY_POINTS, index("entry_point_unique", keys("repoId", 1, "generationId", 1, "entryPointId", 1), true, Map.of()),
                    index("entry_point_route", keys("repoId", 1, "generationId", 1, "scopePackage", 1, "scopeClass", 1, "scopeMethod", 1, "scopeParameters", 1, "httpMethod", 1, "path", 1), false, Map.of()),
                    index("entry_point_method_kind", keys("repoId", 1, "generationId", 1, "method", 1, "kind", 1), false, Map.of())),
            collection(IndexCollections.SEARCH, index("search_unique", keys("repoId", 1, "generationId", 1, "factId", 1), true, Map.of()),
                    // SEARCH v2 reads authoritative rows by immutable generation/fact identity.
                    index("search_generation_fact_lookup", keys("repoId", 1, "generationId", 1, "factId", 1), false, Map.of()),
                    index("search_tokens_by_generation_kind_package", keys("repoId", 1, "generationId", 1, "kind", 1,
                            "package", 1, "authority", 1, "tokens", 1), false, Map.of()),
                    index("search_scope_by_generation_authority", keys("repoId", 1, "generationId", 1,
                            "scopePackage", 1, "scopeClass", 1, "scopeMethod", 1, "scopeParameters", 1,
                            "kind", 1, "package", 1, "authority", 1), false, Map.of())));

    private IndexSchemaContract() { }

    public static List<CollectionSpec> collections() { return COLLECTIONS; }

    public static Map<String, Integer> requiredProjectionVersions() { return REQUIRED_PROJECTION_VERSIONS; }

    /** Canonical payload identity and scope for documents written into immutable generations. */
    public static ImmutablePayloadCollectionSpec immutablePayloadCollection(String collectionName) {
        return IMMUTABLE_PAYLOAD_COLLECTIONS.stream().filter(collection -> collection.name().equals(collectionName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported immutable payload collection " + collectionName));
    }

    public static String fingerprint() {
        StringBuilder canonical = new StringBuilder();
        for (CollectionSpec collection : COLLECTIONS) {
            canonical.append(collection.name()).append('\n');
            for (IndexSpec index : collection.indexes()) {
                canonical.append(index.name()).append('|').append(index.keys()).append('|').append(index.unique())
                        .append('|').append(new TreeMap<>(index.partialFilter())).append('\n');
            }
        }
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static CollectionSpec collection(String name, IndexSpec... indexes) { return new CollectionSpec(name, List.of(indexes)); }
    private static ImmutablePayloadCollectionSpec payload(String name, PayloadScope scope, String... identityFields) {
        return new ImmutablePayloadCollectionSpec(name, scope, List.of(identityFields));
    }
    private static IndexSpec index(String name, LinkedHashMap<String, Integer> keys, boolean unique, Map<String, Object> filter) {
        return new IndexSpec(name, keys, unique, filter);
    }
    private static LinkedHashMap<String, Integer> keys(Object... values) {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) { result.put((String) values[index], (Integer) values[index + 1]); }
        return result;
    }

    public record CollectionSpec(String name, List<IndexSpec> indexes) {
        public CollectionSpec { name = Objects.requireNonNull(name, "collection name is required"); indexes = List.copyOf(indexes); }
    }
    public record IndexSpec(String name, LinkedHashMap<String, Integer> keys, boolean unique, Map<String, Object> partialFilter) {
        public IndexSpec { name = Objects.requireNonNull(name, "index name is required"); keys = new LinkedHashMap<>(keys); partialFilter = Map.copyOf(partialFilter); }
        @Override public LinkedHashMap<String, Integer> keys() { return new LinkedHashMap<>(keys); }
    }
    public record ImmutablePayloadCollectionSpec(String name, PayloadScope scope, List<String> identityFields) {
        public ImmutablePayloadCollectionSpec {
            name = Objects.requireNonNull(name, "collection name is required");
            scope = Objects.requireNonNull(scope, "payload scope is required");
            identityFields = List.copyOf(identityFields);
        }
    }
    public enum PayloadScope { GENERATION, GLOBAL }
}
