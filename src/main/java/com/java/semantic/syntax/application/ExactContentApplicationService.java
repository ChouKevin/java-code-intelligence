package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.concept.MapperConceptIdentity.MapperStatementConceptIdentity;
import com.java.semantic.syntax.application.concept.MapperStatementMethodMapping;
import com.java.semantic.syntax.domain.MapperStatementKey;
import com.java.semantic.config.ExactContentProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.MapperEvidenceIndex;
import com.java.semantic.syntax.domain.MapperFragmentEvidence;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementEvidence;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** 在固定 repository snapshot 以 canonical identity 讀取 exact source 與 mapper 證據 */
public final class ExactContentApplicationService {

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final CanonicalMethodDeclarationResolver declarationResolver;
    private final ExactContentProperties properties;
    private final Function<String, String> contentReferenceFactory;

    public ExactContentApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            CanonicalMethodDeclarationResolver declarationResolver,
            ExactContentProperties properties) {
        this(
                repositoryApplicationService,
                syntaxExtractionService,
                declarationResolver,
                properties,
                ExactContentApplicationService::defaultContentReference);
    }

    ExactContentApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            CanonicalMethodDeclarationResolver declarationResolver,
            ExactContentProperties properties,
            Function<String, String> contentReferenceFactory) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.declarationResolver = Objects.requireNonNull(declarationResolver, "declarationResolver is required");
        this.properties = Objects.requireNonNull(properties, "properties are required");
        this.contentReferenceFactory = Objects.requireNonNull(
                contentReferenceFactory, "contentReferenceFactory is required");
    }

    /** 在 expected revision 的讀鎖內回傳完整 inline 或 segment-authorized content */
    public ExactContentResult retrieve(ExactContentQuery query) {
        ExactContentQuery exactQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                exactQuery.repositoryId(),
                Optional.of(exactQuery.expectedRevision()),
                snapshot -> retrieveSnapshot(snapshot, exactQuery));
    }

    /** 在 expected revision 的讀鎖內回傳指定的 Unicode code-point-safe segment */
    public ExactContentSegment readSegment(ExactContentSegmentQuery query) {
        ExactContentSegmentQuery segmentQuery = Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                segmentQuery.contentQuery().repositoryId(),
                Optional.of(segmentQuery.contentQuery().expectedRevision()),
                snapshot -> readSegmentSnapshot(snapshot, segmentQuery));
    }

    private ExactContentResult retrieveSnapshot(RepositorySnapshot snapshot, ExactContentQuery query) {
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        assertNoContentReferenceCollision(syntax);
        List<RawContent> contents = contentsFor(syntax, query);
        List<ExactContentResult.ContentVariant> variants = contents.stream()
                .map(this::contentVariant)
                .toList();
        return new ExactContentResult(snapshot.repositoryId(), snapshot.revision(), variants);
    }

    private ExactContentSegment readSegmentSnapshot(RepositorySnapshot snapshot, ExactContentSegmentQuery query) {
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        assertNoContentReferenceCollision(syntax);
        RawContent content = contentsFor(syntax, query.contentQuery()).stream()
                .filter(candidate -> contentReference(candidate.content()).equals(query.contentRef()))
                .findFirst()
                .orElseThrow(() -> new ExactContentNotFoundException(query.contentQuery()));
        List<String> segments = segments(content.content());
        if (query.segmentIndex() >= segments.size()) {
            throw new ExactContentNotFoundException(query.contentQuery());
        }
        String segment = segments.get(query.segmentIndex());
        Optional<ExactContentSegmentQuery> next = query.segmentIndex() + 1 < segments.size()
                ? Optional.of(new ExactContentSegmentQuery(
                        query.contentQuery(), query.contentRef(), query.segmentIndex() + 1))
                : Optional.empty();
        return new ExactContentSegment(
                query.contentRef(),
                query.segmentIndex(),
                segments.size(),
                utf8ByteCount(segment),
                segment,
                next);
    }

    private List<RawContent> contentsFor(RepositorySyntax syntax, ExactContentQuery query) {
        return switch (query) {
            case ExactContentQuery.MethodSource methodSource -> List.of(methodSource(syntax, methodSource.target()));
            case ExactContentQuery.MapperStatement mapperStatement -> mapperStatements(syntax, mapperStatement.target());
            case ExactContentQuery.MapperFragment mapperFragment -> List.of(mapperFragment(syntax, mapperFragment.fragmentIdentity()));
        };
    }

    private RawContent methodSource(RepositorySyntax syntax, MethodTarget target) {
        MethodTarget resolvedTarget = resolvedTarget(syntax, target);
        SourceMethodMetadata signature = syntax.sourceTypes().stream()
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> method.analysisTarget().target().filter(resolvedTarget::equals).isPresent())
                .findFirst()
                .orElseThrow(() -> new SemanticTargetNotFoundException(target));
        return RawContent.method(signature.source().text());
    }

    private List<RawContent> mapperStatements(RepositorySyntax syntax, MethodTarget target) {
        MethodTarget resolvedTarget = resolvedTarget(syntax, target);
        MapperEvidenceIndex index = syntax.mapperEvidenceIndex()
                .orElseThrow(() -> new SemanticTargetNotFoundException(target));
        String namespace = syntax.sourceTypes().stream()
                .filter(metadata -> metadata.declaration().identity().sourceFile().equals(resolvedTarget.sourceFile()))
                .filter(metadata -> metadata.declaration().identity().javaType().packageName()
                        .equals(resolvedTarget.packageName()))
                .filter(metadata -> metadata.declaration().identity().javaType().className()
                        .equals(resolvedTarget.className()))
                .map(metadata -> metadata.declaration().identity().fullyQualifiedName())
                .findFirst()
                .orElseThrow(() -> new SemanticTargetNotFoundException(target));
        MapperStatementMethodMapping mapping = MapperStatementMethodMapping.fromSyntax(
                new MapperStatementConceptIdentity(
                        new MapperStatementKey(namespace, resolvedTarget.methodName())), syntax);
        if (mapping.status() != MapperStatementMethodMapping.Status.RESOLVED
                || !mapping.targets().getFirst().equals(resolvedTarget)) {
            throw new SemanticTargetNotFoundException(target);
        }
        List<RawContent> contents = index.statements(namespace, resolvedTarget.methodName()).stream()
                .map(evidence -> RawContent.statement(
                        evidence,
                        includeResolutions(index, evidence)))
                .toList();
        if (contents.isEmpty()) {
            throw new SemanticTargetNotFoundException(target);
        }
        return contents;
    }

    /**
     * 直接由 production mapper evidence/index 解析 include
     * 不重解析回應 XML 且 ambiguous 時保留全部 canonical ordered identities
     */
    private List<ExactContentResult.IncludeResolution> includeResolutions(
            MapperEvidenceIndex index,
            MapperStatementEvidence evidence) {
        return evidence.includeRefIds().stream()
                .map(refId -> includeResolution(
                        refId,
                        index.fragmentIdentitiesForInclude(evidence.identity(), refId)))
                .toList();
    }

    private ExactContentResult.IncludeResolution includeResolution(
            String refId,
            List<MapperFragmentIdentity> fragmentIdentities) {
        ExactContentResult.IncludeResolutionStatus status;
        if (fragmentIdentities.size() == 0) {
            status = ExactContentResult.IncludeResolutionStatus.UNRESOLVED;
        } else if (fragmentIdentities.size() == 1) {
            status = ExactContentResult.IncludeResolutionStatus.RESOLVED;
        } else {
            status = ExactContentResult.IncludeResolutionStatus.AMBIGUOUS;
        }
        return new ExactContentResult.IncludeResolution(refId, status, fragmentIdentities);
    }

    private RawContent mapperFragment(RepositorySyntax syntax, MapperFragmentIdentity identity) {
        MapperEvidenceIndex index = syntax.mapperEvidenceIndex()
                .orElseThrow(() -> new ExactContentNotFoundException(identity));
        MapperFragmentEvidence evidence = index.fragment(identity)
                .orElseThrow(() -> new ExactContentNotFoundException(identity));
        return RawContent.fragment(evidence.identity(), evidence.content());
    }

    private MethodTarget resolvedTarget(RepositorySyntax syntax, MethodTarget target) {
        MethodTargetResolution resolution = declarationResolver.resolve(syntax, target);
        return switch (resolution.status()) {
            case RESOLVED -> resolution.target().orElseThrow(() -> new SemanticTargetNotFoundException(target));
            case UNRESOLVED -> throw new SemanticTargetNotFoundException(target);
            case AMBIGUOUS -> throw new SemanticBindingAmbiguousException(target, resolution.candidates());
        };
    }

    private ExactContentResult.ContentVariant contentVariant(RawContent rawContent) {
        int byteCount = utf8ByteCount(rawContent.content());
        ExactContentResult.Content content = byteCount <= properties.inlineUtf8Bytes()
                ? new ExactContentResult.Content(Optional.of(rawContent.content()), Optional.empty(), byteCount, 0)
                : new ExactContentResult.Content(
                        Optional.empty(),
                        Optional.of(contentReference(rawContent.content())),
                        byteCount,
                        segments(rawContent.content()).size());
        return new ExactContentResult.ContentVariant(
                rawContent.statementIdentity(),
                rawContent.fragmentIdentity(),
                rawContent.includeResolutions(),
                content);
    }

    private void assertNoContentReferenceCollision(RepositorySyntax syntax) {
        Map<String, String> contentByReference = new LinkedHashMap<>();
        for (RawContent rawContent : allSegmentLookupContent(syntax)) {
            String reference = contentReference(rawContent.content());
            String existing = contentByReference.putIfAbsent(reference, rawContent.content());
            if (Objects.nonNull(existing) && !existing.equals(rawContent.content())) {
                throw new ContentReferenceCollisionException(reference);
            }
        }
    }

    private List<RawContent> allSegmentLookupContent(RepositorySyntax syntax) {
        List<RawContent> contents = new ArrayList<>();
        for (SourceMethodMetadata method : syntax.sourceTypes().stream()
                .flatMap(metadata -> metadata.members().methods().stream())
                .toList()) {
            RawContent content = RawContent.method(method.source().text());
            contents.add(content);
        }
        syntax.mapperEvidenceIndex().ifPresent(index -> {
            index.statements().stream()
                    .map(evidence -> RawContent.statement(evidence, List.of()))
                    .forEach(contents::add);
            index.fragments().stream()
                    .map(evidence -> RawContent.fragment(evidence.identity(), evidence.content()))
                    .forEach(contents::add);
        });
        return List.copyOf(contents);
    }

    private List<String> segments(String content) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentByteCount = 0;
        for (int index = 0; index < content.length();) {
            int codePoint = content.codePointAt(index);
            String codePointText = new String(Character.toChars(codePoint));
            int codePointByteCount = utf8ByteCount(codePointText);
            if (currentByteCount + codePointByteCount > properties.segmentUtf8Bytes() && current.length() > 0) {
                segments.add(current.toString());
                current = new StringBuilder();
                currentByteCount = 0;
            }
            current.append(codePointText);
            currentByteCount += codePointByteCount;
            index += Character.charCount(codePoint);
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return List.copyOf(segments);
    }

    private String contentReference(String content) {
        String reference = Objects.requireNonNull(contentReferenceFactory.apply(content), "content reference is required");
        if (reference.isBlank()) {
            throw new IllegalStateException("content reference must not be blank");
        }
        return reference;
    }

    private static int utf8ByteCount(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String defaultContentReference(String content) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** exact identity 已通過驗證但對應內容不存在時的 typed failure */
    public static final class ExactContentNotFoundException extends RuntimeException {

        private ExactContentNotFoundException(Object identity) {
            super("exact content was not found");
            Objects.requireNonNull(identity, "identity is required");
        }
    }

    /** 不同內容產生相同 content reference 時的 fail-closed contract failure */
    public static final class ContentReferenceCollisionException extends IllegalStateException {

        private ContentReferenceCollisionException(String reference) {
            super("exact content reference collision");
            Objects.requireNonNull(reference, "reference is required");
        }
    }

    /** 服務內部保留既有 mapper identity 與未轉送內容的載體 */
    private record RawContent(
            Optional<MapperStatementIdentity> statementIdentity,
            Optional<MapperFragmentIdentity> fragmentIdentity,
            List<ExactContentResult.IncludeResolution> includeResolutions,
            String content) {

        private RawContent {
            statementIdentity = Objects.requireNonNull(statementIdentity, "statementIdentity is required");
            fragmentIdentity = Objects.requireNonNull(fragmentIdentity, "fragmentIdentity is required");
            includeResolutions = List.copyOf(Objects.requireNonNull(
                    includeResolutions, "includeResolutions are required"));
            content = Objects.requireNonNull(content, "content is required");
        }

        private static RawContent method(String content) {
            return new RawContent(Optional.empty(), Optional.empty(), List.of(), content);
        }

        private static RawContent statement(
                MapperStatementEvidence evidence,
                List<ExactContentResult.IncludeResolution> includeResolutions) {
            return new RawContent(
                    Optional.of(evidence.identity()),
                    Optional.empty(),
                    includeResolutions,
                    evidence.content());
        }

        private static RawContent fragment(MapperFragmentIdentity identity, String content) {
            return new RawContent(Optional.empty(), Optional.of(identity), List.of(), content);
        }
    }
}
