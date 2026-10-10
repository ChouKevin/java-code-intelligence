package com.java.semantic.mcp;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.RelationContract;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideFreshness;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceReadContract.SemanticStatus;
import com.java.semantic.model.source.SourceReadContract.SourceStatus;
import com.java.semantic.model.repository.RepositoryId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit transport schemas; the shared application binder remains authoritative. */
public final class SemanticMcpSchemaCatalog {
    private SemanticMcpSchemaCatalog() { }

    public static Map<String, Object> inputSchema(String operation) {
        return switch (operation) {
            case "list_repositories" -> input(properties(
                    "nameFilter", field(string(), "Optional Unicode display-name filter; an empty string is allowed."),
                    "limit", integer(1, 100, 20, "Maximum repositories returned in this page; default 20, range 1–100."),
                    "cursor", field(text(), "Opaque continuation cursor; pass it unchanged with the same request filters.")), List.of(),
                    "Query-allowlisted repository discovery only; unknown properties are rejected.");
            case "get_context" -> input(properties(
                    "repositoryId", field(repositoryId(), "Exact configured repository identifier; this is not a registration field."),
                    "revision", field(revision(), "Optional exact 40-character lowercase commit SHA; omission intentionally discovers current.")),
                    List.of("repositoryId"), "Resolve one published source identity; short SHAs and branch names are invalid.");
            case "list_files" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "directory", field(string(), "Repository-relative directory and descendants; empty string selects the root."),
                    "depth", integer(1, 2, 1, "One level or a two-level preorder preview; each direct-child directory previews at most 12 children."),
                    "limit", integer(1, 100, 20, "Maximum entries returned; default 20, range 1–100."),
                    "cursor", field(text(), "Opaque continuation cursor bound to the exact source context and directory.")),
                    List.of("context"), "Browse one exact published source revision; unknown properties are rejected.");
            case "search_text" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "query", field(literalQuery(), "Required literal, case-sensitive source text; 1–256 Unicode code points, with no CR, LF, or NUL."),
                    "directory", field(string(), "Optional repository-relative directory subtree; empty string selects the root."),
                    "filePattern", field(text(), "Optional positive repository-relative file glob; negated patterns are not accepted."),
                    "limit", integer(1, 100, 20, "Maximum matches returned; default 20, range 1–100.")),
                    List.of("context", "query"), "Search source occurrences, not declaration names or syntax; unknown properties are rejected.");
            case "read_source" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "path", field(text(), "Required repository-relative file path in that published revision."),
                    "view", field(Map.of("type", "string", "enum", List.of("auto", "raw", "summary"), "default", "auto"),
                            "auto may use prepared Java folds; raw never accesses an index; summary requires whole-file fold capability."),
                    "startLine", field(integerValue(1, Integer.MAX_VALUE, "One-based first line through EOF."),
                            "Optional open-ended selection, mutually exclusive with ranges and tailLines; omitted bare reads may summarize."),
                    "ranges", field(Map.of("type", "array", "minItems", 1, "maxItems", 20, "items", lineRange()),
                            "Inclusive requested windows; overlaps are merged after context expansion. Mutually exclusive with startLine/tailLines."),
                    "tailLines", field(integerValue(1, 500, "Number of final existing content lines."),
                            "Select the last N lines, not a byte tail; mutually exclusive with startLine/ranges."),
                    "maxLines", integer(1, 500, 200, "Maximum returned source lines per page; selection may continue over more pages."),
                    "contextBefore", field(integerValue(0, 20, "Leading context lines for non-raw ranges."),
                            "Single auto range defaults to 1; multiple ranges default to 0. Raw requires 0."),
                    "contextAfter", field(integerValue(0, 20, "Trailing context lines for non-raw ranges."),
                            "Single auto range defaults to 3; multiple ranges default to 0. Raw requires 0."),
                    "indexGeneration", field(generation(), "Only with explicit summary; pins this exact generation without fallback."),
                    "cursor", field(text(), "Continue the identical request; authenticates source, effective view, selection and generation. Valid only in the issuing Query process; after restart begin a new read with the same exact pins.")),
                    List.of("context", "path"), "Read bounded source text from one exact revision; unknown properties are rejected.");
            case "search_code" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "query", field(literalQuery(), "Required declaration name; 1–256 Unicode code points, single-line and literal."),
                    "match", field(enums(IndexContract.Match.values(), "Required exact complete-name or prefix beginning-of-name match; case-sensitive, no contains/regex/wildcards."),
                            "Required mode: exact compares the entire declaration name; prefix compares its beginning. No implicit default."),
                    "kinds", field(array(enums(IndexContract.DeclarationKind.values(), "Declaration kind.")),
                            "Optional declaration-kind allowlist; values are exact DeclarationKind enum names."),
                    "owner", field(unicodeString(), "Optional exact lexical owner label; this is not type resolution or substring matching."),
                    "directory", field(string(), "Optional repository-relative directory subtree; empty string selects the root."),
                    "limit", integer(1, 100, 20, "Maximum declarations returned; default 20, range 1–100."),
                    "cursor", field(text(), "Opaque cursor bound to exact context, generation, query, match and every filter; pass unchanged."),
                    "indexGeneration", field(generation(), "Optional canonical UUID pin for the published index generation; never substitute another generation.")),
                    List.of("context", "query", "match"), "Search indexed Java declaration identifiers in one exact context; unknown properties are rejected.");
            case "get_outline" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "path", field(text(), "Required existing repository-relative file path; missing files are an explicit error."),
                    "limit", integer(1, 100, 20, "Maximum declarations returned; default 20, range 1–100."),
                    "cursor", field(text(), "Opaque cursor bound to exact context, path and generation; pass unchanged."),
                    "indexGeneration", field(generation(), "Optional canonical UUID pin for the published index generation.")),
                    List.of("context", "path"), "Return syntax declarations and parse outcome for one indexed file; unknown properties are rejected.");
            case "list_entry_points" -> input(properties(
                    "context", field(context(), "Exact repositoryId and full lowercase commit SHA returned by get_context."),
                    "candidateKinds", field(array(enums(IndexContract.CandidateKind.values(), "Candidate kind.")),
                            "Optional candidate-kind allowlist; values are exact CandidateKind enum names."),
                    "directory", field(string(), "Optional repository-relative directory subtree; empty string selects the root."),
                    "limit", integer(1, 100, 20, "Maximum candidates returned; default 20, range 1–100."),
                    "cursor", field(text(), "Opaque cursor bound to exact context, generation, candidate kinds and directory; pass unchanged."),
                    "indexGeneration", field(generation(), "Optional canonical UUID pin for the published index generation.")),
                    List.of("context"), "List syntax-recognized entry candidates in one exact indexed revision; unknown properties are rejected.");
            case "find_relations" -> relationInput();
            default -> throw new IllegalArgumentException("unknown query operation");
        };
    }

    public static Map<String, Object> outputSchema(String operation) {
        Map<String, Object> result = switch (operation) {
            case "list_repositories" -> object(properties(
                    "items", field(array(repositoryItem()), "Allowed repository summaries in stable page order."),
                    "page", field(page(), "Page count and continuation state.")), List.of("items", "page"), "Repository discovery result.");
            case "get_context" -> object(properties(
                    "repositoryId", field(repositoryId(), "Exact configured repository identifier."),
                    "defaultBranch", field(text(), "Configured default branch; not a substitute for an explicit requested revision."),
                    "sourceStatus", field(enums(SourceStatus.values(), "Source publication state."), "Published source readiness; READY means a context is present."),
                    "semanticStatus", field(enums(SemanticStatus.values(), "Independent semantic-index state: NOT_READY, READY or UNAVAILABLE."), "Index readiness is independent of source readiness."),
                    "context", nullable(context(), "Exact published source identity; null when source is not READY."),
                    "projectGuide", nullable(guide(), "Optional guide metadata; availability does not mean its content is verified."),
                    "coverage", nullable(sourceCoverage(), "Published source coverage; this never represents Java index coverage."),
                    "index", nullable(indexSummary(), "Optional independent syntax-index summary; null unless semanticStatus is READY.")),
                    List.of("repositoryId", "defaultBranch", "sourceStatus", "semanticStatus"), "Source and index discovery result for one repository.");
            case "list_files" -> object(properties(
                    "context", field(context(), "The exact source identity used for this page."),
                    "items", field(array(fileEntry()), "File and directory entries in this page."),
                    "page", field(page(), "Page count and cursor bound to this source context and directory.")),
                    List.of("context", "items", "page"), "Published source directory page.");
            case "search_text" -> object(properties(
                    "context", field(context(), "The exact source identity searched."),
                    "matches", field(array(textMatch()), "Literal source occurrences, not declaration-name matches."),
                    "truncated", field(bool("Whether further matches were excluded by the result bound."), "True means the returned set is incomplete."),
                    "scanComplete", field(bool("Whether the complete authorized source scope was scanned."), "False when the result was truncated or scanning could not complete.")),
                    List.of("context", "matches", "truncated", "scanComplete"), "Literal source-text search result.");
            case "read_source" -> object(properties(
                    "context", field(context(), "The exact source identity read."),
                    "path", field(text(), "Literal repository-relative path."),
                    "blobId", field(revision(), "Exact Git blob SHA-1."),
                    "contentDigest", field(sha256(), "Sealed raw source SHA-256."),
                    "byteLength", field(count("Complete raw source byte length."), "Original file size."),
                    "requestedView", field(enums(SourceReadContract.View.values(), "Requested view."), "Requested reader mode."),
                    "effectiveView", field(enums(SourceReadContract.View.values(), "Effective view."), "Frozen RAW or SUMMARY view actually returned."),
                    "requestedSelection", field(sourceSelection(), "Original requested windows and context."),
                    "effectiveSelection", field(array(lineRange()), "Normalized, merged, EOF-clipped inclusive source windows."),
                    "summaryOutcome", field(enums(SourceReadContract.SummaryOutcome.values(), "Summary capability outcome."),
                            "Distinguishes prepared elisions, no elisions, ineligible text and unavailable metadata."),
                    "indexGeneration", nullable(generation(), "Generation used for the structural view; cursor pins it."),
                    "profile", nullable(profile(), "Known parser/fold profile when structural metadata is used."),
                    "segments", field(Map.of("type", "array", "maxItems", 500, "items", sourceSegment()),
                            "Ordered exact source chunks or intentional elisions; never concatenate elisions as source."),
                    "hasMore", field(bool("Whether this frozen selection/view has another page."), "Intentional folds alone do not imply another page."),
                    "nextCursor", nullable(text(), "Authenticated process-lifetime fixed-view token; null when complete. Modification/restart returns INVALID_ARGUMENT."),
                    "stopReason", field(enums(SourceReadContract.StopReason.values(), "Completion or page budget."),
                            "Page bounding is distinct from intentional elision."),
                    "navigationHint", field(bool("Whether this file is a navigation hint."), "Untrusted navigation content.")),
                    List.of("context", "path", "blobId", "contentDigest", "byteLength", "requestedView", "effectiveView",
                            "requestedSelection", "effectiveSelection", "summaryOutcome", "segments", "hasMore", "stopReason",
                            "navigationHint"), "Lossless segmented exact-revision reading and structural navigation.");
            case "search_code" -> indexResult(declaration(), false);
            case "get_outline" -> indexResult(declaration(), true);
            case "list_entry_points" -> indexResult(entryCandidate(), false);
            case "find_relations" -> relationResult();
            default -> throw new IllegalArgumentException("unknown query operation");
        };
        return Map.of("oneOf", List.of(result, errorSchema()),
                "description", "Successful operation result or fixed safe application error.");
    }

    public static Map<String, Object> errorSchema() {
        return object(properties(
                "code", field(Map.of("type", "string", "enum", List.of(
                        "INVALID_ARGUMENT", "REPOSITORY_NOT_FOUND", "SOURCE_NOT_PREPARED", "REVISION_NOT_PREPARED",
                        "SOURCE_NOT_FOUND", "SOURCE_UNSUPPORTED", "SOURCE_SUMMARY_UNAVAILABLE", "SOURCE_RESULT_TOO_LARGE", "SOURCE_BUSY", "SOURCE_UNAVAILABLE", "SOURCE_TIMEOUT",
                        "INDEX_NOT_PREPARED", "INDEX_NOT_CURRENT", "INDEX_UNAVAILABLE", "INDEX_INCOMPATIBLE", "INDEX_GENERATION_EXPIRED",
                        "INDEX_RESULT_TOO_LARGE", "INDEX_BUSY", "INDEX_TIMEOUT", "INDEX_CAPABILITY_UNAVAILABLE", "INDEX_SYMBOL_NOT_FOUND")),
                        "Stable safe domain or request error code."),
                "message", field(text(), "Fixed safe recovery advice; never contains raw exception text or echoed input."),
                "reason", field(enums(SourceReadContract.SummaryOutcome.values(), "Safe summary-unavailability reason."),
                        "Optional bounded reason for SOURCE_SUMMARY_UNAVAILABLE; no internal diagnostics.")),
                List.of("code", "message"), "Safe HTTP and MCP application error.");
    }

    private static Map<String, Object> indexResult(Map<String, Object> item, boolean outline) {
        Map<String, Object> fields = properties(
                "context", field(context(), "Exact source repository and revision for this result."),
                "indexGeneration", field(generation(), "Immutable published index generation used for this result."),
                "profile", field(profile(), "Parser/schema provenance for the indexed declarations."),
                "capabilities", capabilities(),
                "coverage", field(indexCoverage(), "Independent Java-file and declaration coverage; incomplete coverage is explicit."),
                "analysisLevel", field(enums(IndexContract.AnalysisLevel.values(), "Analysis level."), "Always SYNTAX; no type resolution or runtime conclusion."),
                "items", field(array(item), outline ? "Declarations in the requested file." : "Complete syntax result items in stable order."),
                "page", field(page(), "Returned count and generation/filter-bound continuation state."),
                "file", nullable(fileOutcome(), "Optional outline file outcome, including parse status and safe diagnostics; null for non-outline operations."));
        return object(fields, List.of("context", "indexGeneration", "profile", "capabilities", "coverage", "analysisLevel", "items", "page"),
                "Bounded syntax-index result; source identity and index generation remain explicit.");
    }

    private static Map<String, Object> repositoryItem() {
        return object(properties(
                "repositoryId", field(repositoryId(), "Exact configured repository identifier."),
                "displayName", field(text(), "Configured display name."),
                "defaultBranch", field(text(), "Configured default branch."),
                "projectGuidePath", nullable(text(), "Optional configured project-guide path."),
                "sourceStatus", field(enums(SourceStatus.values(), "Source publication state."), "Source readiness only."),
                "semanticStatus", field(enums(SemanticStatus.values(), "Independent syntax-index state."), "Index readiness is not inferred from source status."),
                "revision", nullable(revision(), "Exact published source SHA; null when no source is READY."),
                "navigationHint", nullable(repositoryNavigationHint(), "Optional untrusted business-discovery hint; never source evidence or preparation authority.")),
                List.of("repositoryId", "displayName", "defaultBranch", "sourceStatus", "semanticStatus"), "Allowed repository summary.");
    }

    private static Map<String, Object> repositoryNavigationHint() {
        return object(properties(
                "summary", field(Map.of("type", "string", "minLength", 1, "maxLength", 240), "Human-reviewed business navigation summary."),
                "keywords", field(Map.of("type", "array", "minItems", 1, "maxItems", 12, "uniqueItems", true,
                        "items", Map.of("type", "string", "minLength", 1, "maxLength", 32)), "Ordered navigation keywords, not a server-side search filter."),
                "analyzedRevision", field(revision(), "Description basis only; may differ from readable source revision."),
                "reviewStatus", field(Map.of("type", "string", "enum", List.of("APPROVED")), "Operator review statement."),
                "trust", field(Map.of("type", "string", "enum", List.of("NOT_VERIFIED")), "Content remains unverified.")),
                List.of("summary", "keywords", "analyzedRevision", "reviewStatus", "trust"), "Untrusted repository navigation metadata.");
    }

    private static Map<String, Object> fileEntry() {
        return object(properties(
                "path", field(text(), "Repository-relative path."),
                "kind", field(enums(EntryKind.values(), "Entry kind."), "FILE or DIRECTORY."),
                "status", nullable(enums(EntryStatus.values(), "Readable source status."), "File status; null for a directory."),
                "byteLength", nullable(count("Source byte length."), "File size in UTF-8/source bytes; null for a directory."),
                "navigationHint", field(bool("Whether this entry is suitable as a navigation hint."), "Navigation metadata only."),
                "depth", field(integerValue(1, 2, "Depth relative to requested directory."), "Preorder node depth."),
                "childPreview", nullable(enums(SourceReadContract.ChildPreview.values(), "Directory child-preview policy state."),
                        "ELIDED requires explicitly listing that directory; separate from global page continuation.")),
                List.of("path", "kind", "navigationHint", "depth"), "Published source inventory node.");
    }

    private static Map<String, Object> textMatch() {
        return object(properties(
                "path", field(text(), "Repository-relative source path."),
                "line", field(count("One-based LF line number."), "One-based source line."),
                "column", field(count("One-based source column."), "One-based source column."),
                "matchedText", field(stringWithDescription("Literal matched source text."), "Matched text excerpt."),
                "navigationHint", field(bool("Whether this match is suitable as a navigation hint."), "Navigation metadata only.")),
                List.of("path", "line", "column", "matchedText", "navigationHint"), "Literal source occurrence.");
    }

    private static Map<String, Object> lineRange() {
        return object(properties(
                "startLine", integerValue(1, Integer.MAX_VALUE, "Inclusive one-based first line."),
                "endLine", integerValue(1, Integer.MAX_VALUE, "Inclusive last line, not before startLine.")),
                List.of("startLine", "endLine"), "Inclusive original LF line range.");
    }

    private static Map<String, Object> sourceSelection() {
        return object(properties(
                "startLine", nullable(integerValue(1, Integer.MAX_VALUE, "First line through EOF."), "Optional open-ended selector."),
                "ranges", field(array(lineRange()), "Requested inclusive ranges, empty for another selector."),
                "tailLines", nullable(integerValue(1, 500, "Final content line count."), "Optional tail selector."),
                "contextBefore", integerValue(0, 20, "Effective requested leading context."),
                "contextAfter", integerValue(0, 20, "Effective requested trailing context.")),
                List.of("ranges", "contextBefore", "contextAfter"), "Original validated selection.");
    }

    private static Map<String, Object> sourceSegment() {
        return object(properties(
                "kind", field(enums(SourceReadContract.SegmentKind.values(), "SOURCE or ELISION."), "Chunk or intentional unread span."),
                "startByte", count("Inclusive raw UTF-8 start byte."),
                "endByte", count("Exclusive raw UTF-8 end byte."),
                "startLine", integerValue(1, Integer.MAX_VALUE, "First original LF line."),
                "endLine", integerValue(1, Integer.MAX_VALUE, "Last original LF line."),
                "content", nullable(string(), "Exact SOURCE text, preserving CRLF; absent for ELISION."),
                "startsMidLine", bool("Source chunk starts within its first line."),
                "endsMidLine", bool("Source chunk ends within its last line."),
                "foldKind", nullable(text(), "Typed intentional elision reason."),
                "declarationId", nullable(sha256(), "Associated declaration identity for an elision.")),
                List.of("kind", "startByte", "endByte", "startLine", "endLine", "startsMidLine", "endsMidLine"),
                "Raw evidence chunk; presentation ellipses and line labels are never source text.");
    }

    private static Map<String, Object> page() {
        return object(properties(
                "returned", field(count("Number of items in this page."), "Returned item count."),
                "hasMore", field(bool("Whether another page is available."), "Continuation indicator."),
                "nextCursor", nullable(text(), "Opaque context/filter/generation-bound cursor; pass it unchanged.")),
                List.of("returned", "hasMore"), "Bounded page metadata.");
    }

    private static Map<String, Object> guide() {
        return object(properties(
                "state", field(enums(GuideState.values(), "Guide state."), "Availability of the configured guide."),
                "path", nullable(text(), "Optional repository-relative guide path."),
                "digest", nullable(sha256(), "Optional content digest."),
                "freshness", field(enums(GuideFreshness.values(), "Guide freshness."), "NOT_VERIFIED is navigation metadata, not verified guidance.")),
                List.of("state", "freshness"), "Project guide metadata.");
    }

    private static Map<String, Object> sourceCoverage() {
        return object(properties(
                "readableFileCount", field(count("Count of readable source files."), "Readable source file count."),
                "excludedFileCount", field(count("Count of excluded files."), "Excluded source file count."),
                "unsupportedCounts", field(Map.of("type", "object", "description", "Counts by safe unsupported-source category.",
                        "additionalProperties", count("Number of files in this category.")), "Per-status unsupported-source counts.")),
                List.of("readableFileCount", "excludedFileCount", "unsupportedCounts"), "Published source coverage; distinct from syntax-index coverage.");
    }

    private static Map<String, Object> indexSummary() {
        return object(properties(
                "indexGeneration", field(generation(), "Selected immutable index generation."),
                "profile", field(profile(), "Parser and schema profile."),
                "coverage", field(indexCoverage(), "Java-file and declaration coverage."),
                "capabilities", capabilities()),
                List.of("indexGeneration", "profile", "coverage", "capabilities"), "Independent index discovery summary.");
    }

    private static Map<String, Object> profile() {
        return object(properties(
                "schemaVersion", field(Map.of("type", "integer", "enum", List.of(1, 2, 3)), "Known paired profiles: 1/1 syntax, 2/2 with folds, 3/3 with static Relations."),
                "policyVersion", field(Map.of("type", "integer", "enum", List.of(1, 2, 3)), "Known paired profile policy version; sealed generations are not upgraded."),
                "engine", field(text(), "Parser engine name."),
                "version", field(text(), "Parser engine version."),
                "languageLevel", field(text(), "Configured Java language level."),
                "unicodeEscapes", field(bool("Whether Java Unicode escapes are interpreted for syntax."), "Unicode escape parsing setting."),
                "tabSize", field(integerValue(1, Integer.MAX_VALUE, "Tab width used by the parser profile."), "Parser tab width in columns.")),
                List.of("schemaVersion", "policyVersion", "engine", "version", "languageLevel", "unicodeEscapes", "tabSize"), "Index parser profile.");
    }

    private static Map<String, Object> indexCoverage() {
        return object(properties(
                "javaFiles", field(count("Number of case-sensitive .java inventory files."), "Java files in the authorized inventory."),
                "parsedFiles", field(count("Java files parsed successfully."), "Successfully parsed Java files."),
                "failedFiles", field(count("Java files whose syntax parse failed."), "Parse-failed Java files."),
                "unsupportedFiles", field(count("Java files not supported by the parser profile."), "Unsupported Java files."),
                "tooLargeFiles", field(count("Java files over the 1,048,576-byte input limit."), "Oversized Java files."),
                "declarations", field(count("Declarations indexed; maximum 200,000 per generation."), "Indexed declaration count."),
                "complete", field(bool("True exactly when every Java inventory file was parsed."), "Whether all Java inventory files were parsed.")),
                List.of("javaFiles", "parsedFiles", "failedFiles", "unsupportedFiles", "tooLargeFiles", "declarations", "complete"), "Syntax-index coverage; non-Java files do not reduce Java completeness.");
    }

    private static Map<String, Object> fileOutcome() {
        return object(properties(
                "path", field(text(), "Repository-relative file path."),
                "javaFile", field(bool("Whether the case-sensitive path ends in .java."), "Whether this inventory entry is a Java source file."),
                "status", field(enums(IndexContract.ParseStatus.values(), "File parse status."), "PARSED, PARSE_FAILED, UNSUPPORTED or TOO_LARGE."),
                "byteLength", field(count("Original source byte length."), "File length in source bytes."),
                "blobId", nullable(revision(), "Optional Git blob SHA-1."),
                "contentDigest", nullable(sha256(), "Optional SHA-256 of original source bytes."),
                "diagnostics", field(array(diagnostic()), "At most 100 bounded safe diagnostic codes and source ranges.")),
                List.of("path", "javaFile", "status", "byteLength", "diagnostics"), "File inventory and syntax parse outcome.");
    }

    private static Map<String, Object> diagnostic() {
        return object(properties(
                "code", field(text(), "Bounded safe diagnostic code; parser raw messages are not exposed."),
                "range", nullable(sourceSpan(), "Optional raw-source byte and line range.")),
                List.of("code"), "Safe syntax diagnostic.");
    }

    private static Map<String, Object> declaration() {
        return object(properties(
                "symbolId", field(sha256(), "Stable lowercase SHA-256 declaration identity scoped to this generation."),
                "name", field(unicodeString(), "Recorded Java declaration identifier; no normalization or case folding."),
                "kind", field(enums(IndexContract.DeclarationKind.values(), "Declaration kind."), "Exact DeclarationKind enum."),
                "owner", field(unicodeString(), "Lexical owner label; syntax-only, not resolved type identity."),
                "parentSymbolId", nullable(sha256(), "Optional nearest indexed enclosing declaration identity."),
                "path", field(text(), "Repository-relative source path."),
                "blobId", field(revision(), "Git blob SHA-1 for the original source bytes."),
                "contentDigest", field(sha256(), "SHA-256 of the original source bytes."),
                "byteLength", field(count("Original source length in bytes."), "Source file byte length."),
                "range", field(sourceSpan(), "Declaration range in the original source."),
                "nameRange", field(sourceSpan(), "Identifier range in the original source."),
                "signature", field(unicodeString(), "Written syntax signature; not type-resolved.")),
                List.of("symbolId", "name", "kind", "owner", "path", "blobId", "contentDigest", "byteLength", "range", "nameRange", "signature"),
                "Java declaration syntax evidence.");
    }

    private static Map<String, Object> entryCandidate() {
        return object(properties(
                "candidateId", field(sha256(), "Stable lowercase SHA-256 candidate identity scoped to this generation."),
                "kind", field(enums(IndexContract.CandidateKind.values(), "Entry candidate kind."), "MAIN, HTTP_MAPPING, SCHEDULED or RABBIT_LISTENER."),
                "declaration", field(declaration(), "The indexed declaration associated with this candidate."),
                "evidence", field(evidence(), "Syntax-only evidence and uncertainty for candidate classification.")),
                List.of("candidateId", "kind", "declaration", "evidence"), "Syntax-recognized entry candidate, not a confirmed runtime endpoint.");
    }

    private static Map<String, Object> evidence() {
        return object(properties(
                "kind", field(enums(IndexContract.EvidenceKind.values(), "Evidence kind."), "Evidence provenance classification."),
                "annotation", nullable(unicodeString(), "Optional exact annotation spelling; absent for MAIN_SIGNATURE."),
                "literalValues", field(Map.of("type", "object", "description", "Recognized literal annotation member values.",
                        "additionalProperties", array(unicodeString())), "Map from annotation member names to literal values only."),
                "unresolvedMembers", field(array(text()), "Annotation members whose values are expressions rather than recognized literals."),
                "range", field(sourceSpan(), "Original-source range of the evidence.")),
                List.of("kind", "literalValues", "unresolvedMembers", "range"), "Syntax evidence; unresolved or ambiguous syntax is not resolved at runtime.");
    }

    private static Map<String, Object> sourceSpan() {
        return object(properties(
                "startByte", field(count("Zero-based byte offset in original UTF-8 source."), "Inclusive start offset in raw UTF-8 bytes."),
                "endByte", field(count("Exclusive byte offset in original UTF-8 source."), "Exclusive end offset; range is [startByte, endByte)."),
                "startLine", field(integerValue(1, Integer.MAX_VALUE, "One-based LF-counted source line."), "One-based inclusive start line, counted using LF; CRLF bytes are not normalized."),
                "endLine", field(integerValue(1, Integer.MAX_VALUE, "One-based LF-counted source line."), "One-based inclusive end line, counted using LF; CRLF bytes are not normalized.")),
                List.of("startByte", "endByte", "startLine", "endLine"), "Source evidence range: zero-based raw UTF-8 byte offsets [startByte,endByte), plus one-based inclusive LF lines.");
    }
    private static Map<String, Object> relationInput() {
        Map<String, Object> schema = new LinkedHashMap<>(input(properties(
                "context", context(), "indexGeneration", generation(), "anchor", relationAnchor(),
                "direction", enums(RelationContract.Direction.values(), "Case-sensitive uppercase INCOMING or OUTGOING; lowercase is invalid."),
                "relationKinds", array(enums(RelationContract.Kind.values(), "Absent or empty means all kinds; duplicates normalize.")),
                "resolutionStates", array(enums(RelationContract.ResolutionState.values(), "Absent or empty means all states; duplicates normalize.")),
                "directory", field(string(), "Filter occurrence paths, not target paths."),
                "limit", integer(1, 100, 20, "Maximum whole facts."),
                "cursor", field(text(), "Continuation bound to exact context, generation, anchor, direction, all filters and limit.")),
                List.of("context", "indexGeneration", "anchor", "direction"),
                "Exact-generation static evidence, not a runtime call graph."));
        schema.put("allOf", List.of(anchorDirection("FILE", "OUTGOING"), anchorDirection("EXTERNAL_TARGET", "INCOMING")));
        return Map.copyOf(schema);
    }

    private static Map<String, Object> anchorDirection(String kind, String direction) {
        return Map.of("if", Map.of("properties", Map.of("anchor",
                        Map.of("properties", Map.of("kind", Map.of("const", kind))))),
                "then", Map.of("properties", Map.of("direction", Map.of("const", direction))));
    }


    private static Map<String, Object> capabilities() {
        return object(properties("syntax", bool("Syntax navigation."), "folds", bool("Structural folds."),
                "relations", bool("Static relation evidence.")), List.of("syntax", "folds", "relations"), "Profile capabilities.");
    }

    private static Map<String, Object> relationAnchor() {
        return Map.of("oneOf", List.of(
                object(properties("kind", Map.of("const", "SOURCE_SYMBOL"), "symbolId", sha256()), List.of("kind", "symbolId"), "Source symbol."),
                object(properties("kind", Map.of("const", "EXTERNAL_TARGET"), "targetId", sha256()), List.of("kind", "targetId"), "External target."),
                object(properties("kind", Map.of("const", "FILE"), "path", text()), List.of("kind", "path"), "File occurrences.")));
    }

    private static Map<String, Object> sourceSite() {
        return object(properties("path", text(), "blobId", revision(), "contentDigest", sha256(), "byteLength", count("Source bytes."),
                "range", sourceSpan(), "containerSymbolId", nullable(sha256(), "Navigation container, not runtime caller."),
                "executionScope", object(properties("kind", enums(RelationContract.ScopeKind.values(), "Written execution scope."),
                        "range", sourceSpan()), List.of("kind", "range"), "Original source execution scope.")),
                List.of("path", "blobId", "contentDigest", "byteLength", "range", "executionScope"), "Raw occurrence provenance.");
    }

    private static Map<String, Object> externalTarget() {
        return object(properties("targetId", sha256(), "artifactId", text(), "artifactDigest", sha256(), "module", nullable(text(), "Platform module."),
                "declarationKind", enums(IndexContract.DeclarationKind.values(), "External declaration kind."),
                "qualifiedOwner", text(), "name", text(), "signature", text()),
                List.of("targetId", "artifactId", "artifactDigest", "declarationKind", "qualifiedOwner", "name", "signature"), "Generation-bound external declaration.");
    }

    private static Map<String, Object> relationFact() {
        Map<String, Object> sourceTarget = object(properties("declaration", declaration(), "qualifiedSignature", nullable(text(), "Canonical static signature.")),
                List.of("declaration"), "Written source endpoint.");
        Map<String, Object> target = Map.of("oneOf", List.of(
                object(properties("kind", Map.of("const", "SOURCE_SYMBOL"), "source", sourceTarget, "external", Map.of("type", "null")), List.of("kind", "source"), "Source target."),
                object(properties("kind", Map.of("const", "EXTERNAL_TARGET"), "external", externalTarget(), "source", Map.of("type", "null")), List.of("kind", "external"), "External target.")));
        Map<String, Object> framework = object(properties("recognizerId", text(), "spelling", text(), "recognitionBasis", text(),
                "literalValues", Map.of("type", "object", "additionalProperties", array(unicodeString())),
                "unresolvedMembers", array(text()), "bindingUncertainties", array(text()), "supportingEvidence", array(sourceSite())),
                List.of("recognizerId", "spelling", "recognitionBasis", "literalValues", "unresolvedMembers", "bindingUncertainties", "supportingEvidence"), "Candidate framework interpretation.");
        return object(properties("relationId", sha256(), "kind", enums(RelationContract.Kind.values(), "Static kind."), "source", sourceSite(),
                "resolutionState", enums(RelationContract.ResolutionState.values(), "Static precision."), "target", nullable(target, "No target for unresolved facts."),
                "unresolvedReason", nullable(enums(RelationContract.Reason.values(), "Safe reason."), "Unresolved reason."),
                "framework", nullable(framework, "Framework candidate evidence.")), List.of("relationId", "kind", "source", "resolutionState"), "Whole static fact.");
    }

    private static Map<String, Object> relationCoverage() {
        return object(properties("scopeFiles", count("Root-scope Java files."), "processedFiles", count("Analyzed files."),
                "skippedFiles", count("Skipped files."), "outsideScopeJavaFiles", count("Java files outside roots."),
                "fileCounts", enumCounts(RelationContract.FileStatus.values()), "kindCounts", enumCounts(RelationContract.Kind.values()),
                "stateCounts", enumCounts(RelationContract.ResolutionState.values()), "reasonCounts", enumCounts(RelationContract.Reason.values()),
                "callOccurrences", count("Distinct call occurrences, not associations."), "resolvedCallOccurrences", count("Statically resolved call occurrences."),
                "extractionComplete", bool("Every scope file was processed.")),
                List.of("scopeFiles", "processedFiles", "skippedFiles", "outsideScopeJavaFiles", "fileCounts", "kindCounts", "stateCounts", "reasonCounts", "callOccurrences", "resolvedCallOccurrences", "extractionComplete"), "Explicit partial static coverage.");
    }

    private static Map<String, Object> enumCounts(Enum<?>[] values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Enum<?> value : values) fields.put(value.name(), count("Fact or file count."));
        return object(fields, List.of(), "Counts by known enum; absent means zero.");
    }

    private static Map<String, Object> resolutionProvenance() {
        Map<String, Object> platform = object(properties("javaVersion", text(), "vendor", text(), "releaseDigest", sha256(), "modulesDigest", sha256()),
                List.of("javaVersion", "vendor", "releaseDigest", "modulesDigest"), "Fixed Java platform identity.");
        Map<String, Object> artifact = object(properties("id", text(), "digest", sha256(), "byteLength", count("Artifact bytes."), "order", count("Approved order.")),
                List.of("id", "digest", "byteLength", "order"), "Approved artifact identity, no private paths.");
        return object(properties("analysisPlanDigest", sha256(), "platform", platform, "sourceRoots", array(string()), "artifacts", array(artifact)),
                List.of("analysisPlanDigest", "platform", "sourceRoots", "artifacts"), "Immutable approved resolution inputs.");
    }

    private static Map<String, Object> relationResult() {
        return object(properties("context", context(), "indexGeneration", generation(), "profile", profile(), "capabilities", capabilities(),
                "coverage", indexCoverage(), "relationCoverage", relationCoverage(), "resolutionProvenance", resolutionProvenance(),
                "analysisLevel", Map.of("const", "STATIC"), "anchor", relationAnchor(), "direction", enums(RelationContract.Direction.values(), "Direction."),
                "items", array(relationFact()), "page", page(),
                "file", nullable(object(properties("file", fileOutcome(), "status", enums(RelationContract.FileStatus.values(), "Analysis outcome.")),
                        List.of("file", "status"), "Explicit file analysis."), "Present for FILE anchor, even when empty.")),
                List.of("context", "indexGeneration", "profile", "capabilities", "coverage", "relationCoverage", "resolutionProvenance", "analysisLevel", "anchor", "direction", "items", "page"),
                "Static associations; empty results and partial coverage never prove no runtime callers.");
    }

    private static Map<String, Object> input(Map<String, Object> properties, List<String> required, String description) {
        return object(properties, required, description);
    }

    private static Map<String, Object> context() {
        return object(properties(
                "repositoryId", field(repositoryId(), "Exact allowlisted repository identifier."),
                "revision", field(revision(), "Exact full 40-character lowercase commit SHA; branch names and short SHAs are invalid.")),
                List.of("repositoryId", "revision"), "Exact source identity; never inferred from the current session.");
    }

    private static Map<String, Object> repositoryId() {
        return Map.of("type", "string", "pattern", RepositoryId.PATTERN,
                "description", "Repository identifier matching the configured repository-ID syntax.");
    }

    private static Map<String, Object> revision() {
        return Map.of("type", "string", "pattern", "^[a-f0-9]{40}$",
                "description", "Full lowercase 40-character Git commit SHA-1.");
    }

    private static Map<String, Object> generation() {
        return Map.of("type", "string", "format", "uuid",
                "pattern", "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                "description", "Canonical lowercase UUID identifying one immutable index generation.");
    }

    private static Map<String, Object> sha256() {
        return Map.of("type", "string", "pattern", "^[0-9a-f]{64}$",
                "description", "Lowercase SHA-256 digest.");
    }

    private static Map<String, Object> literalQuery() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 256,
                "pattern", "^[^\\r\\n\\u0000]+$",
                "description", "Nonempty single-line literal of at most 256 Unicode code points; CR, LF and NUL are forbidden.");
    }

    private static Map<String, Object> text() {
        return Map.of("type", "string", "minLength", 1, "description", "Nonempty string.");
    }

    private static Map<String, Object> unicodeString() {
        return Map.of("type", "string", "description", "Unicode string; matching is exact and case-sensitive where used as a filter.");
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string", "description", "String value.");
    }

    private static Map<String, Object> stringWithDescription(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> count(String description) {
        return Map.of("type", "integer", "minimum", 0, "description", description);
    }

    private static Map<String, Object> integerValue(int minimum, int maximum, String description) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "description", description);
    }

    private static Map<String, Object> integer(int minimum, int maximum, int fallback, String description) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "default", fallback,
                "description", description);
    }

    private static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> enums(Enum<?>[] values, String description) {
        return Map.of("type", "string", "enum", Arrays.stream(values).map(Enum::name).toList(),
                "description", description);
    }

    private static Map<String, Object> array(Map<String, Object> item) {
        return Map.of("type", "array", "items", item, "description", "Array of the described item type.");
    }

    private static Map<String, Object> nullable(Map<String, Object> value, String description) {
        return Map.of("anyOf", List.of(value, Map.of("type", "null")), "description", description);
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
