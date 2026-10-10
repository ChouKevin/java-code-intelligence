package com.java.semantic.model.index;

import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.support.ModelValidation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Exact-generation static evidence; never a runtime call graph. */
public final class RelationContract {
    public static final int MAX_FACTS = 200_000;
    public static final int MAX_SOURCE_ROOTS = 32;
    public static final int MAX_ARTIFACTS = 128;
    public static final long MAX_ARTIFACT_BYTES = 67_108_864L;
    public static final long MAX_TOTAL_ARTIFACT_BYTES = 268_435_456L;

    private RelationContract() { }

    public enum Kind { CALL, CONSTRUCT, METHOD_REFERENCE, EXTENDS, IMPLEMENTS, OVERRIDES,
        REFERENCE, HTTP_EVIDENCE, EVENT_EVIDENCE, DB_EVIDENCE }
    public enum ResolutionState { STATIC_RESOLVED, CANDIDATE, UNRESOLVED }
    public enum Direction { INCOMING, OUTGOING }
    public enum AnchorKind { SOURCE_SYMBOL, EXTERNAL_TARGET, FILE }
    public enum TargetKind { SOURCE_SYMBOL, EXTERNAL_TARGET }
    public enum Reason { MISSING_TYPE, AMBIGUOUS_TARGET, UNSUPPORTED_CONSTRUCT,
        DUPLICATE_SOURCE_TYPE, NO_WRITTEN_DECLARATION }
    public enum FileStatus { ANALYZED, OUT_OF_SCOPE, PARSE_FAILED, UNSUPPORTED, TOO_LARGE }
    public enum ScopeKind { DECLARATION, METHOD, CONSTRUCTOR, LAMBDA, ANONYMOUS_METHOD,
        STATIC_INITIALIZER, INSTANCE_INITIALIZER, FIELD_INITIALIZER, FILE }

    public sealed interface Anchor permits SymbolAnchor, ExternalAnchor, FileAnchor {
        AnchorKind kind();
    }
    public record SymbolAnchor(AnchorKind kind, String symbolId) implements Anchor {
        public SymbolAnchor {
            ModelValidation.require(kind == AnchorKind.SOURCE_SYMBOL, "invalid symbol anchor kind");
            symbolId = ModelValidation.sha256(symbolId, "symbol identity");
        }
    }
    public record ExternalAnchor(AnchorKind kind, String targetId) implements Anchor {
        public ExternalAnchor {
            ModelValidation.require(kind == AnchorKind.EXTERNAL_TARGET, "invalid external anchor kind");
            targetId = ModelValidation.sha256(targetId, "external identity");
        }
    }
    public record FileAnchor(AnchorKind kind, String path) implements Anchor {
        public FileAnchor {
            ModelValidation.require(kind == AnchorKind.FILE, "invalid file anchor kind");
            path = SourcePathPolicy.requireFile(path);
        }
    }
    public record Request(SourceContext context, String indexGeneration, Anchor anchor,
            Direction direction, List<Kind> relationKinds, List<ResolutionState> resolutionStates,
            String directory, int limit, Optional<String> cursor) {
        public Request {
            context = Objects.requireNonNull(context, "source context");
            indexGeneration = IndexContract.requireGeneration(indexGeneration);
            requireDirection(anchor, direction);
            relationKinds = List.copyOf(Objects.requireNonNull(relationKinds, "relation kinds")).stream().distinct().sorted().toList();
            resolutionStates = List.copyOf(Objects.requireNonNull(resolutionStates, "resolution states")).stream().distinct().sorted().toList();
            directory = SourcePathPolicy.requireDirectory(directory);
            ModelValidation.require(limit >= 1 && limit <= SourceReadContract.MAX_PAGE_LIMIT, "invalid page limit");
            cursor = Objects.requireNonNull(cursor, "cursor");
            cursor.ifPresent(value -> text(value, "cursor"));
        }
    }
    public record Scope(ScopeKind kind, IndexContract.SourceSpan range) {
        public Scope {
            kind = Objects.requireNonNull(kind, "scope kind");
            range = Objects.requireNonNull(range, "scope range");
        }
    }
    public record SourceSite(String path, String blobId, String contentDigest, long byteLength,
            IndexContract.SourceSpan range, Optional<String> containerSymbolId, Scope executionScope) {
        public SourceSite {
            path = SourcePathPolicy.requireFile(path);
            blobId = RepositoryRevision.ofSha(blobId).value();
            contentDigest = ModelValidation.sha256(contentDigest, "source content digest");
            range = Objects.requireNonNull(range, "occurrence range");
            containerSymbolId = Objects.requireNonNull(containerSymbolId, "navigation container");
            containerSymbolId.ifPresent(value -> ModelValidation.sha256(value, "container identity"));
            executionScope = Objects.requireNonNull(executionScope, "execution scope");
            ModelValidation.require(byteLength >= 0 && byteLength <= IndexContract.MAX_FILE_BYTES
                    && range.endByte() > range.startByte() && executionScope.range().endByte() <= byteLength
                    && executionScope.range().contains(range), "occurrence must identify bounded source bytes within scope");
        }
    }
    public record ExternalTarget(String targetId, String artifactId, String artifactDigest,
            Optional<String> module, IndexContract.DeclarationKind declarationKind,
            String qualifiedOwner, String name, String signature) {
        public ExternalTarget {
            targetId = ModelValidation.sha256(targetId, "external target identity");
            artifactId = requireArtifactId(artifactId);
            artifactDigest = ModelValidation.sha256(artifactDigest, "artifact digest");
            module = Objects.requireNonNull(module, "platform module");
            module.ifPresent(value -> text(value, "platform module"));
            declarationKind = Objects.requireNonNull(declarationKind, "target declaration kind");
            qualifiedOwner = text(qualifiedOwner, "qualified owner");
            name = text(name, "target name");
            signature = text(signature, "canonical declaration signature");
        }
    }
    public record SourceTarget(IndexContract.Declaration declaration, Optional<String> qualifiedSignature) {
        public SourceTarget {
            declaration = Objects.requireNonNull(declaration, "source declaration");
            qualifiedSignature = Objects.requireNonNull(qualifiedSignature, "canonical declaration signature");
            qualifiedSignature.ifPresent(value -> text(value, "canonical declaration signature"));
        }
    }
    public record Target(TargetKind kind, Optional<SourceTarget> source, Optional<ExternalTarget> external) {
        public Target {
            kind = Objects.requireNonNull(kind, "target kind");
            source = Objects.requireNonNull(source, "source target");
            external = Objects.requireNonNull(external, "external target");
            ModelValidation.require(source.isPresent() == (kind == TargetKind.SOURCE_SYMBOL)
                    && external.isPresent() == (kind == TargetKind.EXTERNAL_TARGET), "target must identify exactly one endpoint");
        }
    }
    public record FrameworkEvidence(String recognizerId, String spelling, String recognitionBasis,
            Map<String, List<String>> literalValues, List<String> unresolvedMembers,
            List<String> bindingUncertainties, List<SourceSite> supportingEvidence) {
        public FrameworkEvidence {
            recognizerId = text(recognizerId, "recognizer identity");
            spelling = text(spelling, "written spelling");
            recognitionBasis = text(recognitionBasis, "recognition basis");
            Map<String, List<String>> copied = new HashMap<>();
            for (Map.Entry<String, List<String>> entry : Objects.requireNonNull(literalValues, "literal values").entrySet()) {
                String member = text(entry.getKey(), "literal member");
                List<String> values = List.copyOf(entry.getValue());
                values.forEach(IndexContract::unicode);
                copied.put(member, values);
            }
            literalValues = Map.copyOf(copied);
            unresolvedMembers = List.copyOf(Objects.requireNonNull(unresolvedMembers, "unresolved members"));
            for (String member : unresolvedMembers) {
                text(member, "unresolved member");
                ModelValidation.require(!literalValues.containsKey(member), "member cannot be literal and unresolved");
            }
            bindingUncertainties = List.copyOf(Objects.requireNonNull(bindingUncertainties, "binding uncertainties"));
            bindingUncertainties.forEach(value -> text(value, "binding uncertainty"));
            supportingEvidence = List.copyOf(Objects.requireNonNull(supportingEvidence, "supporting evidence"));
        }
    }
    public record Fact(String relationId, Kind kind, SourceSite source, ResolutionState resolutionState,
            Optional<Target> target, Optional<Reason> unresolvedReason, Optional<FrameworkEvidence> framework) {
        public Fact {
            relationId = ModelValidation.sha256(relationId, "relation identity");
            kind = Objects.requireNonNull(kind, "relation kind");
            source = Objects.requireNonNull(source, "source occurrence");
            resolutionState = Objects.requireNonNull(resolutionState, "resolution state");
            target = Objects.requireNonNull(target, "target");
            unresolvedReason = Objects.requireNonNull(unresolvedReason, "unresolved reason");
            framework = Objects.requireNonNull(framework, "framework evidence");
            boolean frameworkKind = kind == Kind.HTTP_EVIDENCE || kind == Kind.EVENT_EVIDENCE || kind == Kind.DB_EVIDENCE;
            ModelValidation.require(frameworkKind == framework.isPresent(), "framework evidence must match kind");
            ModelValidation.require(!frameworkKind || resolutionState == ResolutionState.CANDIDATE,
                    "framework interpretations are candidates, not proven Java relations");
            ModelValidation.require((resolutionState == ResolutionState.UNRESOLVED) == unresolvedReason.isPresent(),
                    "only unresolved facts require a safe reason");
            ModelValidation.require(resolutionState != ResolutionState.UNRESOLVED || target.isEmpty(), "unresolved fact cannot claim a target");
            ModelValidation.require(resolutionState != ResolutionState.STATIC_RESOLVED || target.isPresent(), "resolved fact requires a target");
            if (resolutionState == ResolutionState.STATIC_RESOLVED && target.orElseThrow().source().isPresent()) {
                ModelValidation.require(target.orElseThrow().source().orElseThrow().qualifiedSignature().isPresent(),
                        "resolved source target requires its canonical declaration signature");
            }
        }
    }
    public record Coverage(long scopeFiles, long processedFiles, long skippedFiles, long outsideScopeJavaFiles,
            Map<FileStatus, Long> fileCounts, Map<Kind, Long> kindCounts,
            Map<ResolutionState, Long> stateCounts, Map<Reason, Long> reasonCounts,
            long callOccurrences, long resolvedCallOccurrences, boolean extractionComplete) {
        public Coverage {
            fileCounts = counts(fileCounts);
            kindCounts = counts(kindCounts);
            stateCounts = counts(stateCounts);
            reasonCounts = counts(reasonCounts);
            ModelValidation.require(scopeFiles >= 0 && processedFiles >= 0 && skippedFiles >= 0 && outsideScopeJavaFiles >= 0
                    && Math.addExact(processedFiles, skippedFiles) == scopeFiles
                    && extractionComplete == (processedFiles == scopeFiles), "invalid relation file coverage");
            ModelValidation.require(fileCounts.getOrDefault(FileStatus.ANALYZED, 0L) == processedFiles
                    && fileCounts.getOrDefault(FileStatus.OUT_OF_SCOPE, 0L) == outsideScopeJavaFiles
                    && total(fileCounts) == Math.addExact(scopeFiles, outsideScopeJavaFiles), "file counts must describe every Java file");
            long facts = total(kindCounts);
            ModelValidation.require(facts <= MAX_FACTS && facts == total(stateCounts)
                    && total(reasonCounts) == stateCounts.getOrDefault(ResolutionState.UNRESOLVED, 0L), "inconsistent fact counts");
            long calls = Math.addExact(kindCounts.getOrDefault(Kind.CALL, 0L), Math.addExact(
                    kindCounts.getOrDefault(Kind.CONSTRUCT, 0L), kindCounts.getOrDefault(Kind.METHOD_REFERENCE, 0L)));
            ModelValidation.require(callOccurrences >= 0 && callOccurrences <= calls && (callOccurrences == 0) == (calls == 0)
                    && resolvedCallOccurrences >= 0
                    && resolvedCallOccurrences <= callOccurrences
                    && resolvedCallOccurrences <= stateCounts.getOrDefault(ResolutionState.STATIC_RESOLVED, 0L), "invalid call occurrence coverage");
        }
    }
    public record Platform(String javaVersion, String vendor, String releaseDigest, String modulesDigest) {
        public Platform {
            javaVersion = text(javaVersion, "Java version");
            vendor = text(vendor, "Java vendor");
            releaseDigest = ModelValidation.sha256(releaseDigest, "platform release digest");
            modulesDigest = ModelValidation.sha256(modulesDigest, "platform modules digest");
        }
    }
    public record Artifact(String id, String digest, long byteLength, int order) {
        public Artifact {
            id = requireArtifactId(id);
            digest = ModelValidation.sha256(digest, "artifact digest");
            ModelValidation.require(byteLength > 0 && byteLength <= MAX_ARTIFACT_BYTES && order >= 0 && order < MAX_ARTIFACTS,
                    "invalid artifact bounds");
        }
    }
    public record Provenance(String analysisPlanDigest, Platform platform, List<String> sourceRoots, List<Artifact> artifacts) {
        public Provenance {
            analysisPlanDigest = ModelValidation.sha256(analysisPlanDigest, "analysis plan digest");
            platform = Objects.requireNonNull(platform, "platform");
            sourceRoots = requireSourceRoots(sourceRoots);
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            ModelValidation.require(artifacts.size() <= MAX_ARTIFACTS, "too many artifacts");
            Set<String> ids = new HashSet<>();
            long bytes = 0;
            for (int index = 0; index < artifacts.size(); index++) {
                Artifact artifact = artifacts.get(index);
                ModelValidation.require(artifact.order() == index && ids.add(artifact.id()), "artifact order/identity mismatch");
                bytes = Math.addExact(bytes, artifact.byteLength());
            }
            ModelValidation.require(bytes <= MAX_TOTAL_ARTIFACT_BYTES, "artifact input exceeds byte budget");
        }
    }
    public record FileAnalysis(IndexContract.FileOutcome file, FileStatus status) {
        public FileAnalysis {
            file = Objects.requireNonNull(file, "file outcome");
            status = Objects.requireNonNull(status, "relation file status");
            ModelValidation.require(file.javaFile() || status == FileStatus.UNSUPPORTED,
                    "non-Java files have unsupported relation analysis");
            if (status != FileStatus.OUT_OF_SCOPE) {
                String expected = file.status() == IndexContract.ParseStatus.PARSED ? FileStatus.ANALYZED.name() : file.status().name();
                ModelValidation.require(status.name().equals(expected), "relation status must preserve parse outcome");
            }
        }
    }
    public record Result(SourceContext context, String indexGeneration, IndexContract.Profile profile,
            IndexContract.Capabilities capabilities, IndexContract.Coverage coverage, Coverage relationCoverage,
            Provenance resolutionProvenance, IndexContract.AnalysisLevel analysisLevel, Anchor anchor,
            Direction direction, List<Fact> items, SourceReadContract.Page page, Optional<FileAnalysis> file) {
        public Result {
            context = Objects.requireNonNull(context, "source context");
            indexGeneration = IndexContract.requireGeneration(indexGeneration);
            ModelValidation.require(IndexContract.supportsRelations(profile)
                    && IndexContract.capabilities(profile).equals(capabilities), "relation result requires a capable profile");
            coverage = Objects.requireNonNull(coverage, "syntax coverage");
            relationCoverage = Objects.requireNonNull(relationCoverage, "relation coverage");
            resolutionProvenance = Objects.requireNonNull(resolutionProvenance, "resolution provenance");
            ModelValidation.require(analysisLevel == IndexContract.AnalysisLevel.STATIC, "relation analysis is static");
            requireDirection(anchor, direction);
            items = List.copyOf(Objects.requireNonNull(items, "relation items"));
            page = Objects.requireNonNull(page, "page");
            file = Objects.requireNonNull(file, "file analysis");
            ModelValidation.require(items.size() == page.returned() && items.size() <= SourceReadContract.MAX_PAGE_LIMIT,
                    "relation page must describe its bounded items");
            ModelValidation.require((anchor instanceof FileAnchor) == file.isPresent(), "file anchor requires its analysis outcome");
            if (anchor instanceof FileAnchor fileAnchor) {
                ModelValidation.require(file.orElseThrow().file().path().equals(fileAnchor.path()), "file anchor outcome mismatch");
            }
            ModelValidation.require(coverage.javaFiles() == Math.addExact(relationCoverage.scopeFiles(), relationCoverage.outsideScopeJavaFiles()),
                    "relation coverage must describe the syntax inventory");
        }
    }

    public static String requireArtifactId(String value) {
        ModelValidation.require(Objects.requireNonNull(value, "artifact identity").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"),
                "artifact identity must be safe bounded metadata");
        return value;
    }

    public static List<String> requireSourceRoots(List<String> roots) {
        List<String> result = List.copyOf(Objects.requireNonNull(roots, "source roots"));
        ModelValidation.require(!result.isEmpty() && result.size() <= MAX_SOURCE_ROOTS, "invalid source root count");
        for (int index = 0; index < result.size(); index++) {
            String root = SourcePathPolicy.requireDirectory(result.get(index));
            for (int previous = 0; previous < index; previous++) {
                String other = result.get(previous);
                ModelValidation.require(!root.equals(other) && !root.isEmpty() && !other.isEmpty()
                        && !root.startsWith(other + "/") && !other.startsWith(root + "/"), "source roots must not overlap");
            }
        }
        return result;
    }

    public static String targetIdentity(Target target) {
        return target.source().map(value -> value.declaration().symbolId()).orElseGet(() -> target.external().orElseThrow().targetId());
    }

    public static String factIdentity(String generation, Kind kind, SourceSite source, Optional<Target> target) {
        return IndexContract.identity(IndexContract.requireGeneration(generation), source.path(), Long.toString(source.range().startByte()),
                Long.toString(source.range().endByte()), kind.name(), target.map(RelationContract::targetIdentity).orElse(""));
    }

    public static String externalIdentity(String generation, String artifactId, String artifactDigest, Optional<String> module,
            IndexContract.DeclarationKind kind, String owner, String name, String signature) {
        return IndexContract.identity(IndexContract.requireGeneration(generation), artifactId, artifactDigest,
                module.orElse(""), kind.name(), owner, name, signature);
    }

    private static void requireDirection(Anchor anchor, Direction direction) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(direction, "direction");
        ModelValidation.require(!(anchor instanceof FileAnchor) || direction == Direction.OUTGOING, "file anchors are outgoing only");
        ModelValidation.require(!(anchor instanceof ExternalAnchor) || direction == Direction.INCOMING, "external anchors are incoming only");
    }

    private static String text(String value, String name) {
        return IndexContract.unicode(ModelValidation.requiredText(value, name));
    }

    private static <K extends Enum<K>> Map<K, Long> counts(Map<K, Long> values) {
        Map<K, Long> result = Map.copyOf(Objects.requireNonNull(values, "coverage counts"));
        for (long value : result.values()) ModelValidation.require(value >= 0, "negative coverage count");
        return result;
    }

    private static long total(Map<?, Long> values) {
        long sum = 0;
        for (long value : values.values()) sum = Math.addExact(sum, value);
        return sum;
    }
}
