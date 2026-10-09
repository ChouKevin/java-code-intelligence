package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.support.ModelValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Framework-neutral syntax evidence and exact-generation query values. */
public final class IndexContract {
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_FILE_BYTES = 1_048_576;
    public static final int MAX_DECLARATIONS = 200_000;
    public static final long MAX_DATABASE_BYTES = 67_108_864L;
    public static final int MAX_RESULT_BYTES = 163_840;
    public static final int MAX_TRANSPORT_BYTES = 524_288;
    public static final int MAX_DIAGNOSTICS = 100;
    private static final Profile LEGACY_PROFILE = new Profile(1, 1, "JavaParser", "3.28.2", "JAVA_21", true, 1);
    private static final Profile CURRENT_PROFILE = new Profile(2, 2, "JavaParser", "3.28.2", "JAVA_21", true, 1);

    private IndexContract() { }

    public enum DeclarationKind { CLASS, INTERFACE, RECORD, ENUM, ANNOTATION_TYPE, METHOD, CONSTRUCTOR, FIELD, RECORD_COMPONENT, ENUM_CONSTANT, ANNOTATION_MEMBER }
    public enum ParseStatus { PARSED, PARSE_FAILED, UNSUPPORTED, TOO_LARGE }
    public enum Match { exact, prefix }
    public enum CandidateKind { MAIN, HTTP_MAPPING, SCHEDULED, RABBIT_LISTENER }
    public enum EvidenceKind { MAIN_SIGNATURE, FULLY_QUALIFIED, DIRECT_IMPORT, AMBIGUOUS_SIMPLE }
    public enum AnalysisLevel { SYNTAX }

    public static Profile currentProfile() { return CURRENT_PROFILE; }
    public static boolean supportedProfile(Profile profile) {
        return CURRENT_PROFILE.equals(profile) || LEGACY_PROFILE.equals(profile);
    }

    public record Profile(int schemaVersion, int policyVersion, String engine, String version,
            String languageLevel, boolean unicodeEscapes, int tabSize) {
        public Profile {
            ModelValidation.require(schemaVersion > 0 && policyVersion > 0 && tabSize > 0, "invalid index profile version or tab size");
            engine = ModelValidation.requiredText(engine, "parser engine");
            version = ModelValidation.requiredText(version, "parser version");
            languageLevel = ModelValidation.requiredText(languageLevel, "language profile");
        }
    }

    public record SourceSpan(long startByte, long endByte, int startLine, int endLine) {
        public SourceSpan {
            ModelValidation.require(startByte >= 0 && endByte >= startByte && startLine >= 1 && endLine >= startLine,
                    "invalid raw source span");
        }
        public boolean contains(SourceSpan other) {
            return startByte <= other.startByte && endByte >= other.endByte
                    && startLine <= other.startLine && endLine >= other.endLine;
        }
    }

    public record Diagnostic(String code, Optional<SourceSpan> range) {
        public Diagnostic {
            code = ModelValidation.requiredText(code, "diagnostic code");
            ModelValidation.require(code.matches("[A-Z][A-Z0-9_]{0,63}"), "diagnostics require a bounded safe code");
            range = Objects.requireNonNull(range, "diagnostic range");
        }
    }

    public record FileOutcome(String path, boolean javaFile, ParseStatus status, long byteLength,
            Optional<String> blobId, Optional<String> contentDigest, List<Diagnostic> diagnostics) {
        public FileOutcome {
            path = SourcePathPolicy.requireFile(path);
            ModelValidation.require(javaFile == path.endsWith(".java"), "Java scope must match the case-sensitive file suffix");
            status = Objects.requireNonNull(status, "parse status");
            ModelValidation.require(byteLength >= 0, "file byte length must not be negative");
            blobId = Objects.requireNonNull(blobId, "blob identity");
            blobId.ifPresent(RepositoryRevision::ofSha);
            contentDigest = Objects.requireNonNull(contentDigest, "content digest");
            contentDigest.ifPresent(value -> ModelValidation.sha256(value, "content digest"));
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
            ModelValidation.require(diagnostics.size() <= MAX_DIAGNOSTICS, "diagnostics exceed their bound");
            for (Diagnostic diagnostic : diagnostics) {
                diagnostic.range().ifPresent(range -> ModelValidation.require(range.endByte() <= byteLength, "diagnostic exceeds source"));
            }
            if (status == ParseStatus.PARSED || status == ParseStatus.PARSE_FAILED) {
                ModelValidation.require(javaFile && byteLength <= MAX_FILE_BYTES && blobId.isPresent() && contentDigest.isPresent(),
                        "parsed Java requires bounded source identity");
            }
            ModelValidation.require(status != ParseStatus.TOO_LARGE || javaFile && byteLength > MAX_FILE_BYTES,
                    "too-large status requires an oversized Java file");
            ModelValidation.require(status != ParseStatus.PARSED || diagnostics.isEmpty(), "parsed files cannot hide parser diagnostics");
        }
    }

    public record Declaration(String symbolId, String name, DeclarationKind kind, String owner,
            Optional<String> parentSymbolId, String path, String blobId, String contentDigest,
            long byteLength, SourceSpan range, SourceSpan nameRange, String signature) {
        public Declaration {
            symbolId = ModelValidation.sha256(symbolId, "symbol identity");
            name = unicode(ModelValidation.requiredText(name, "declaration name"));
            kind = Objects.requireNonNull(kind, "declaration kind");
            owner = unicode(Objects.requireNonNull(owner, "lexical owner"));
            parentSymbolId = Objects.requireNonNull(parentSymbolId, "parent identity");
            parentSymbolId.ifPresent(value -> ModelValidation.sha256(value, "parent identity"));
            ModelValidation.require(parentSymbolId.isEmpty() || !parentSymbolId.orElseThrow().equals(symbolId), "declaration cannot parent itself");
            path = SourcePathPolicy.requireFile(path);
            blobId = RepositoryRevision.ofSha(blobId).value();
            contentDigest = ModelValidation.sha256(contentDigest, "content digest");
            range = Objects.requireNonNull(range, "declaration range");
            nameRange = Objects.requireNonNull(nameRange, "name range");
            ModelValidation.require(byteLength >= 0 && byteLength <= MAX_FILE_BYTES && range.endByte() <= byteLength
                    && range.endByte() > range.startByte() && nameRange.endByte() > nameRange.startByte()
                    && range.contains(nameRange), "declaration/name range must identify bounded source bytes");
            signature = unicode(Objects.requireNonNull(signature, "written signature"));
        }
    }

    public record Evidence(EvidenceKind kind, Optional<String> annotation,
            Map<String, List<String>> literalValues, List<String> unresolvedMembers, SourceSpan range) {
        public Evidence {
            kind = Objects.requireNonNull(kind, "evidence kind");
            annotation = Objects.requireNonNull(annotation, "annotation spelling");
            annotation.ifPresent(value -> unicode(ModelValidation.requiredText(value, "annotation spelling")));
            ModelValidation.require((kind == EvidenceKind.MAIN_SIGNATURE) == annotation.isEmpty(), "annotation evidence must preserve its spelling");
            Map<String, List<String>> copied = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : Objects.requireNonNull(literalValues, "literal annotation values").entrySet()) {
                ModelValidation.requiredText(entry.getKey(), "annotation member");
                List<String> values = List.copyOf(entry.getValue());
                values.forEach(IndexContract::unicode);
                copied.put(entry.getKey(), values);
            }
            literalValues = Map.copyOf(copied);
            unresolvedMembers = List.copyOf(Objects.requireNonNull(unresolvedMembers, "unresolved members"));
            for (String member : unresolvedMembers) {
                ModelValidation.requiredText(member, "unresolved member");
                ModelValidation.require(!literalValues.containsKey(member), "annotation member cannot be resolved and unresolved");
            }
            range = Objects.requireNonNull(range, "evidence range");
        }
    }

    public record EntryCandidate(String candidateId, CandidateKind kind, Declaration declaration, Evidence evidence) {
        public EntryCandidate {
            candidateId = ModelValidation.sha256(candidateId, "candidate identity");
            kind = Objects.requireNonNull(kind, "candidate kind");
            declaration = Objects.requireNonNull(declaration, "candidate declaration");
            evidence = Objects.requireNonNull(evidence, "candidate evidence");
            ModelValidation.require(declaration.range().contains(evidence.range()), "candidate evidence must belong to its declaration");
            ModelValidation.require((kind == CandidateKind.MAIN) == (evidence.kind() == EvidenceKind.MAIN_SIGNATURE), "main candidate requires main-signature evidence");
        }
    }

    public record Fold(String declarationId, String kind, SourceSpan interior) {
        public Fold {
            declarationId = ModelValidation.sha256(declarationId, "fold declaration identity");
            ModelValidation.require("METHOD".equals(kind) || "CONSTRUCTOR".equals(kind), "unsupported fold kind");
            interior = Objects.requireNonNull(interior, "fold interior");
            ModelValidation.require(interior.endByte() > interior.startByte(), "empty bodies cannot be folded");
        }
    }

    public record FoldResult(String indexGeneration, Profile profile, FileOutcome file,
            boolean foldCapable, List<Fold> folds) {
        public FoldResult {
            indexGeneration = requireGeneration(indexGeneration);
            profile = Objects.requireNonNull(profile, "index profile");
            file = Objects.requireNonNull(file, "file outcome");
            folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
            ModelValidation.require(supportedProfile(profile) && foldCapable == profile.equals(CURRENT_PROFILE),
                    "fold capability must match a supported profile");
            ModelValidation.require(folds.size() <= MAX_DECLARATIONS
                    && (foldCapable && file.status() == ParseStatus.PARSED || folds.isEmpty()),
                    "folds require capable parsed evidence");
            for (Fold fold : folds) {
                ModelValidation.require(fold.interior().endByte() <= file.byteLength(), "fold exceeds source");
            }
        }
    }

    public record ParsedFile(FileOutcome file, List<Declaration> declarations, List<EntryCandidate> candidates, List<Fold> folds) {
        public ParsedFile {
            file = Objects.requireNonNull(file, "file outcome");
            declarations = List.copyOf(Objects.requireNonNull(declarations, "declarations"));
            candidates = List.copyOf(Objects.requireNonNull(candidates, "entry candidates"));
            folds = List.copyOf(Objects.requireNonNull(folds, "folds"));
            ModelValidation.require(file.status() == ParseStatus.PARSED || declarations.isEmpty() && candidates.isEmpty() && folds.isEmpty(),
                    "unparsed files cannot publish recovered declarations or folds");
            for (Declaration declaration : declarations) {
                ModelValidation.require(declaration.path().equals(file.path()) && declaration.byteLength() == file.byteLength()
                        && file.blobId().filter(declaration.blobId()::equals).isPresent()
                        && file.contentDigest().filter(declaration.contentDigest()::equals).isPresent(), "declaration source identity mismatch");
            }
            if (!folds.isEmpty()) {
                Map<String, Declaration> byId = new HashMap<>();
                for (Declaration declaration : declarations) byId.put(declaration.symbolId(), declaration);
                Set<String> folded = new HashSet<>();
                for (Fold fold : folds) {
                    Declaration declaration = byId.get(fold.declarationId());
                    ModelValidation.require(Objects.nonNull(declaration) && folded.add(fold.declarationId())
                            && declaration.kind().name().equals(fold.kind()) && declaration.range().contains(fold.interior()),
                            "fold must identify its enclosing declaration");
                }
            }
        }
    }

    public record Coverage(long javaFiles, long parsedFiles, long failedFiles, long unsupportedFiles,
            long tooLargeFiles, long declarations, boolean complete) {
        public Coverage {
            ModelValidation.require(javaFiles >= 0 && parsedFiles >= 0 && failedFiles >= 0 && unsupportedFiles >= 0
                    && tooLargeFiles >= 0 && declarations >= 0 && declarations <= MAX_DECLARATIONS, "invalid index coverage count");
            long total = Math.addExact(Math.addExact(parsedFiles, failedFiles), Math.addExact(unsupportedFiles, tooLargeFiles));
            ModelValidation.require(javaFiles == total && complete == (parsedFiles == javaFiles), "coverage must describe every Java file honestly");
        }
    }

    public record IndexSummary(String indexGeneration, Profile profile, Coverage coverage) {
        public IndexSummary {
            indexGeneration = requireGeneration(indexGeneration);
            profile = Objects.requireNonNull(profile, "index profile");
            coverage = Objects.requireNonNull(coverage, "index coverage");
        }
    }

    public record Discovery(SourceReadContract.SemanticStatus status, Optional<IndexSummary> index) {
        public Discovery {
            status = Objects.requireNonNull(status, "index availability");
            index = Objects.requireNonNull(index, "index summary");
            ModelValidation.require((status == SourceReadContract.SemanticStatus.READY) == index.isPresent(), "ready index discovery requires a summary");
        }
    }

    public record SearchCodeRequest(SourceContext context, String query, Match match,
            List<DeclarationKind> kinds, Optional<String> owner, String directory, int limit,
            Optional<String> cursor, Optional<String> indexGeneration) {
        public SearchCodeRequest {
            context = Objects.requireNonNull(context, "source context");
            query = unicode(Objects.requireNonNull(query, "query"));
            ModelValidation.require(!query.isEmpty() && query.codePointCount(0, query.length()) <= SourceReadContract.MAX_QUERY_CODE_POINTS
                    && query.indexOf('\n') < 0 && query.indexOf('\r') < 0 && query.indexOf('\0') < 0,
                    "query must be a nonempty single-line literal of at most 256 Unicode code points");
            match = Objects.requireNonNull(match, "exact or prefix match is required");
            kinds = Objects.requireNonNull(kinds, "declaration kinds").stream().distinct().sorted().toList();
            owner = Objects.requireNonNull(owner, "lexical owner filter");
            owner.ifPresent(IndexContract::unicode);
            directory = SourcePathPolicy.requireDirectory(directory);
            requirePage(limit, cursor, indexGeneration);
        }
    }

    public record OutlineRequest(SourceContext context, String path, int limit,
            Optional<String> cursor, Optional<String> indexGeneration) {
        public OutlineRequest {
            context = Objects.requireNonNull(context, "source context");
            path = SourcePathPolicy.requireFile(path);
            requirePage(limit, cursor, indexGeneration);
        }
    }

    public record EntryPointsRequest(SourceContext context, List<CandidateKind> candidateKinds,
            String directory, int limit, Optional<String> cursor, Optional<String> indexGeneration) {
        public EntryPointsRequest {
            context = Objects.requireNonNull(context, "source context");
            candidateKinds = Objects.requireNonNull(candidateKinds, "candidate kinds").stream().distinct().sorted().toList();
            directory = SourcePathPolicy.requireDirectory(directory);
            requirePage(limit, cursor, indexGeneration);
        }
    }

    public record Result<T>(SourceContext context, String indexGeneration, Profile profile,
            Coverage coverage, AnalysisLevel analysisLevel, List<T> items,
            SourceReadContract.Page page, Optional<FileOutcome> file) {
        public Result {
            context = Objects.requireNonNull(context, "source context");
            indexGeneration = requireGeneration(indexGeneration);
            profile = Objects.requireNonNull(profile, "index profile");
            coverage = Objects.requireNonNull(coverage, "index coverage");
            analysisLevel = Objects.requireNonNull(analysisLevel, "analysis level");
            items = List.copyOf(Objects.requireNonNull(items, "index result items"));
            page = Objects.requireNonNull(page, "result page");
            file = Objects.requireNonNull(file, "outline file outcome");
            ModelValidation.require(items.size() == page.returned(), "result page must describe returned items");
        }
    }

    public static String requireGeneration(String value) {
        Objects.requireNonNull(value, "index generation");
        ModelValidation.require(UUID.fromString(value).toString().equals(value), "index generation must be a canonical UUID");
        return value;
    }

    public static String identity(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field, "identity field").getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("required SHA-256 unavailable", exception);
        }
    }

    private static void requirePage(int limit, Optional<String> cursor, Optional<String> generation) {
        ModelValidation.require(limit >= 1 && limit <= SourceReadContract.MAX_PAGE_LIMIT, "page limit must be between 1 and 100");
        Objects.requireNonNull(cursor, "cursor").ifPresent(value -> ModelValidation.requiredText(value, "cursor"));
        Objects.requireNonNull(generation, "index generation").ifPresent(IndexContract::requireGeneration);
    }

    private static String unicode(String value) {
        Objects.requireNonNull(value, "Unicode text");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new InvalidUnicodeTextException();
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new InvalidUnicodeTextException();
            }
        }
        return value;
    }
    /** A public Unicode value cannot losslessly represent an unpaired UTF-16 code unit. */
    public static final class InvalidUnicodeTextException extends IllegalArgumentException {
        private InvalidUnicodeTextException() {
            super("text must contain valid Unicode");
        }
    }
}
