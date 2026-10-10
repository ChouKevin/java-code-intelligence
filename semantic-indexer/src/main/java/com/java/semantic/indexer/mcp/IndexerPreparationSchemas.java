package com.java.semantic.indexer.mcp;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.repository.RepositoryId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit private preparation schemas; the shared application binder owns strict validation. */
public final class IndexerPreparationSchemas {
    private IndexerPreparationSchemas() { }

    public static Map<String, Object> input(String operation) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("repositoryId", field(repositoryId(), "Configured repository identifier; MCP callers provide it here, HTTP callers use the path."));
        if (operation.equals("prepare_source")) {
            fields.put("requestId", field(uuid(), "Canonical UUID for this durable source preparation intent; persist it before calling."));
            fields.put("revision", field(sha(), "Optional exact 40-character lowercase SHA: explicit revision prepares membership only and never changes current. Omission fetches the configured default-branch HEAD and promotes that pinned SHA only after successful publication."));
            return object(fields, List.of("repositoryId", "requestId"),
                    "Explicit source preparation. An uncertain response must be recovered with get_job using the same requestId.");
        }
        if (operation.equals("prepare_semantic_index")) {
            fields.put("requestId", field(uuid(), "Canonical UUID for this durable semantic-index intent; persist it before calling."));
            fields.put("revision", field(sha(), "Required exact already-published current SHA. Historical or absent-current requests are rejected with INDEX_NOT_CURRENT; no fetch, automatic SHA substitution or source-current switch occurs."));
            return object(fields, List.of("repositoryId", "requestId", "revision"),
                    "Explicit asynchronous index preparation for current only, using a new saved UUID after source COMPLETE. Retained historical indexes remain readable; this tool does not rebuild them.");
        }
        if (operation.equals("get_job")) {
            fields.put("jobId", field(text(), "Repository-scoped durable job identity."));
            fields.put("requestId", field(uuid(), "Original canonical request UUID used to recover acceptance or locate the job."));
            Map<String, Object> schema = new LinkedHashMap<>(object(fields, List.of("repositoryId"),
                    "Look up a source or semantic preparation job within one repository."));
            schema.put("oneOf", List.of(
                    Map.of("required", List.of("jobId"), "not", Map.of("required", List.of("requestId"))),
                    Map.of("required", List.of("requestId"), "not", Map.of("required", List.of("jobId")))));
            return Map.copyOf(schema);
        }
        throw new IllegalArgumentException("unknown preparation operation");
    }

    public static Map<String, Object> output(String operation) {
        Map<String, Object> failure = failure();
        return switch (operation) {
            case "prepare_source" -> Map.of("oneOf", List.of(sourceJob(), failure),
                    "description", "Durable source-job acceptance or a safe failure; acceptance is not publication.");
            case "prepare_semantic_index" -> Map.of("oneOf", List.of(semanticJob(), failure),
                    "description", "Durable semantic-job acceptance or a safe failure; acceptance is not index readiness.");
            case "get_job" -> Map.of("oneOf", List.of(sourceJob(), semanticJob(), failure),
                    "description", "Repository-scoped source or semantic job result, discriminated by operation, or a safe lookup failure.");
            default -> throw new IllegalArgumentException("unknown preparation operation");
        };
    }

    private static Map<String, Object> sourceJob() {
        return object(properties(
                "formatVersion", field(Map.of("type", "integer", "const", 1), "Public source-preparation result format; private persisted source job format is not exposed."),
                "operation", field(Map.of("type", "string", "const", "prepare_source"), "Discriminator identifying a source preparation job."),
                "jobId", field(text(), "Durable source job identity."),
                "repositoryId", field(repositoryId(), "Configured repository identifier."),
                "requestId", field(uuid(), "Original canonical request UUID."),
                "defaultBranch", field(text(), "Configured branch used when revision was omitted."),
                "phase", field(phases(), "Durable source job phase; ACCEPTED/RUNNING are not publication readiness."),
                "acceptedAt", field(dateTime(), "Durable acceptance timestamp in ISO-8601 date-time form."),
                "requestedRevision", nullable(sha(), "Optional explicitly requested full commit SHA."),
                "resolvedRevision", nullable(sha(), "Optional exact revision resolved by the source job."),
                "expectedCurrent", nullable(currentPublication(), "Optional source-current snapshot captured for publication safety."),
                "publication", nullable(sourceReceipt(), "Optional source publication receipt; present only after successful completion."),
                "failureCode", nullable(safeCode(), "Optional safe terminal failure code; raw failure text is never returned.")),
                List.of("formatVersion", "operation", "jobId", "repositoryId", "requestId", "defaultBranch", "phase", "acceptedAt"),
                "Source preparation result; operation is a required discriminator.");
    }

    private static Map<String, Object> semanticJob() {
        return object(properties(
                "formatVersion", field(Map.of("type", "integer", "const", 1), "Semantic-job result format version."),
                "operation", field(Map.of("type", "string", "const", "prepare_semantic_index"), "Discriminator identifying semantic-index preparation."),
                "jobId", field(text(), "Durable semantic job identity."),
                "repositoryId", field(repositoryId(), "Configured repository identifier."),
                "requestId", field(uuid(), "Original canonical request UUID."),
                "revision", field(sha(), "Exact already-published source revision being indexed."),
                "indexGeneration", field(generation(), "New immutable index generation assigned to this build."),
                "phase", field(phases(), "Durable semantic job phase; ACCEPTED/RUNNING do not mean READY."),
                "acceptedAt", field(dateTime(), "Durable acceptance timestamp in ISO-8601 date-time form."),
                "publication", nullable(indexReceipt(), "Optional published-generation receipt; present only after successful completion."),
                "failureCode", nullable(safeCode(), "Optional safe terminal failure code; raw failure text is never returned.")),
                List.of("formatVersion", "operation", "jobId", "repositoryId", "requestId", "revision", "indexGeneration", "phase", "acceptedAt"),
                "Semantic-index result; HTTP 202/MCP success represents durable acceptance only, never READY.");
    }

    private static Map<String, Object> failure() {
        return object(properties(
                "code", field(Map.of("type", "string", "enum", List.of(
                        "INVALID_ARGUMENT", "REQUEST_ID_REUSED", "REQUEST_NOT_FOUND", "JOB_NOT_FOUND",
                        "REPOSITORY_NOT_FOUND", "REPOSITORY_ACTIVE", "SOURCE_UNAVAILABLE", "SOURCE_NOT_PREPARED",
                        "INDEX_NOT_PREPARED", "INDEX_NOT_CURRENT", "INDEX_UNAVAILABLE", "INDEX_INCOMPATIBLE",
                        "INDEX_CAPABILITY_UNAVAILABLE", "INDEX_GENERATION_EXPIRED", "INDEX_RESULT_TOO_LARGE", "INDEX_BUSY", "INDEX_TIMEOUT")),
                        "Stable safe error code; no internal exception text is returned."),
                "message", field(text(), "Fixed recovery advice without secrets, filesystem paths or echoed arguments."),
                "retryable", field(Map.of("type", "boolean"), "Whether the transport marks this failure as potentially retryable."),
                "jobId", field(text(), "Optional existing job identity supplied for request-ID reuse recovery."),
                "requestId", field(uuid(), "Optional original request identity relevant to acceptance recovery.")),
                List.of("code", "message", "retryable"), "Safe preparation or query-domain transport failure.");
    }

    private static Map<String, Object> currentPublication() {
        return object(properties(
                "revision", field(sha(), "Exact previously selected source revision."),
                "manifestDigest", field(sha256(), "Source manifest SHA-256 digest."),
                "publicationJobId", field(text(), "Source job that committed the current publication."),
                "publishedAt", field(dateTime(), "Publication timestamp in ISO-8601 date-time form.")),
                List.of("revision", "manifestDigest", "publicationJobId", "publishedAt"), "Captured source-current publication pointer.");
    }

    private static Map<String, Object> sourceReceipt() {
        return object(properties(
                "context", field(context(), "Exact repository and revision committed to source publication."),
                "manifestDigest", field(sha256(), "Source manifest SHA-256 digest."),
                "firstPublishedJobId", field(text(), "Source job that first published this revision."),
                "preparedAt", field(dateTime(), "Preparation completion timestamp in ISO-8601 date-time form.")),
                List.of("context", "manifestDigest", "firstPublishedJobId", "preparedAt"), "Committed source publication receipt.");
    }

    private static Map<String, Object> indexReceipt() {
        return object(properties(
                "manifest", field(indexManifest(), "Immutable index-manifest provenance for the committed generation."),
                "manifestDigest", field(sha256(), "Index manifest SHA-256 digest."),
                "jobId", field(uuid(), "Semantic job identity recorded by the publication receipt."),
                "requestId", field(uuid(), "Original request identity recorded by the publication receipt.")),
                List.of("manifest", "manifestDigest", "jobId", "requestId"), "Committed semantic-index publication receipt.");
    }

    private static Map<String, Object> indexManifest() {
        return object(properties(
                "formatVersion", field(Map.of("type", "integer", "minimum", 1), "Index manifest format version."),
                "context", field(context(), "Exact source repository and revision indexed."),
                "originFingerprint", field(sha256(), "One-way approved-origin identity fingerprint; not a Git URL or credential."),
                "indexGeneration", field(generation(), "Immutable published index generation."),
                "sourceManifestDigest", field(sha256(), "Digest of the exact published source manifest."),
                "inventoryDigest", field(sha256(), "Digest of the authorized source inventory."),
                "profile", field(profile(), "Parser and schema profile used to build this generation."),
                "coverage", field(coverage(), "Java syntax-index coverage for the generation."),
                "databaseBytes", field(Map.of("type", "integer", "minimum", 1, "maximum", IndexContract.MAX_DATABASE_BYTES), "Sealed SQLite database size in bytes; maximum 67,108,864."),
                "databaseDigest", field(sha256(), "SHA-256 digest of the sealed database artifact."),
                "preparedAt", field(dateTime(), "Index build publication timestamp in ISO-8601 date-time form."),
                "analysisPlanDigest", nullable(sha256(), "Frozen analysis-plan identity: required for profile 3, absent/null for profiles 1/2; no private paths.")),
                List.of("formatVersion", "context", "originFingerprint", "indexGeneration", "sourceManifestDigest", "inventoryDigest",
                        "profile", "coverage", "databaseBytes", "databaseDigest", "preparedAt"), "Immutable index publication manifest.");
    }

    private static Map<String, Object> profile() {
        return object(properties(
                "schemaVersion", field(Map.of("type", "integer", "enum", List.of(1, 2, 3)), "Known paired profiles: 1/1 syntax, 2/2 with folds, 3/3 with static Relations."),
                "policyVersion", field(Map.of("type", "integer", "enum", List.of(1, 2, 3)), "Known paired profile policy version; Relations is opt-in and never upgrades sealed generations."),
                "engine", field(text(), "Parser engine name."),
                "version", field(text(), "Parser engine version."),
                "languageLevel", field(text(), "Configured Java language level."),
                "unicodeEscapes", field(Map.of("type", "boolean"), "Whether Java Unicode escapes are interpreted."),
                "tabSize", field(Map.of("type", "integer", "minimum", 1), "Parser tab width in columns.")),
                List.of("schemaVersion", "policyVersion", "engine", "version", "languageLevel", "unicodeEscapes", "tabSize"), "Index parser profile.");
    }

    private static Map<String, Object> coverage() {
        return object(properties(
                "javaFiles", field(count("Case-sensitive .java files in the authorized inventory."), "Java file count."),
                "parsedFiles", field(count("Java files successfully parsed."), "Parsed file count."),
                "failedFiles", field(count("Java files whose parse failed."), "Parse failure count."),
                "unsupportedFiles", field(count("Java files unsupported by the parser profile."), "Unsupported file count."),
                "tooLargeFiles", field(count("Java files over 1,048,576 bytes."), "Oversized Java file count."),
                "declarations", field(count("Declarations indexed; at most 200,000 per generation."), "Indexed declaration count."),
                "complete", field(Map.of("type", "boolean"), "True exactly when every Java inventory file parsed successfully.")),
                List.of("javaFiles", "parsedFiles", "failedFiles", "unsupportedFiles", "tooLargeFiles", "declarations", "complete"), "Java syntax-index coverage.");
    }

    private static Map<String, Object> context() {
        return object(properties(
                "repositoryId", field(repositoryId(), "Exact configured repository identifier."),
                "revision", field(sha(), "Exact full lowercase 40-character source commit SHA.")),
                List.of("repositoryId", "revision"), "Exact source identity.");
    }

    private static Map<String, Object> repositoryId() {
        return Map.of("type", "string", "pattern", RepositoryId.PATTERN,
                "description", "Identifier matching configured repository-ID syntax.");
    }

    private static Map<String, Object> uuid() {
        return Map.of("type", "string", "format", "uuid",
                "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                "description", "Canonical lowercase UUID.");
    }

    private static Map<String, Object> generation() { return uuid(); }

    private static Map<String, Object> sha() {
        return Map.of("type", "string", "pattern", "^[0-9a-f]{40}$",
                "description", "Exact full 40-character lowercase Git commit SHA-1.");
    }

    private static Map<String, Object> sha256() {
        return Map.of("type", "string", "pattern", "^[0-9a-f]{64}$",
                "description", "Lowercase SHA-256 digest.");
    }

    private static Map<String, Object> safeCode() {
        return Map.of("type", "string", "pattern", "^[A-Z][A-Z0-9_]{0,63}$",
                "description", "Bounded uppercase safe failure code; no raw failure detail.");
    }

    private static Map<String, Object> phases() {
        return Map.of("type", "string", "enum", List.of("ACCEPTED", "RUNNING", "COMPLETE", "FAILED"),
                "description", "Durable asynchronous job phase.");
    }

    private static Map<String, Object> dateTime() {
        return Map.of("type", "string", "format", "date-time", "description", "ISO-8601 date-time.");
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "minLength", 1, "description", "Nonempty string.");
    }

    private static Map<String, Object> count(String description) {
        return Map.of("type", "integer", "minimum", 0, "description", description);
    }

    private static Map<String, Object> nullable(Map<String, Object> schema, String description) {
        return Map.of("anyOf", List.of(schema, Map.of("type", "null")), "description", description);
    }

    private static Map<String, Object> field(Map<String, Object> schema, String description) {
        Map<String, Object> result = new LinkedHashMap<>(schema);
        result.put("description", description);
        return Map.copyOf(result);
    }

    private static Map<String, Object> properties(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("schema properties require key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return Map.copyOf(result);
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required, String description) {
        return Map.of("type", "object", "description", description, "properties", properties,
                "required", required, "additionalProperties", false);
    }
}
