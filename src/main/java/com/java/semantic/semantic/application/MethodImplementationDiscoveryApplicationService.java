package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.CanonicalTargetProjection;
import com.java.semantic.callgraph.application.ImplementationCandidate;
import com.java.semantic.callgraph.application.ImplementationCandidateFactory;
import com.java.semantic.callgraph.application.RepositorySyntaxIndex;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticBindingAmbiguousException;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticImplementationIssueReason;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticTargetNotFoundException;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 固定 revision 的單次記憶體實作探索，不建立 continuation snapshot */
public final class MethodImplementationDiscoveryApplicationService {

    private static final int MIN_CANDIDATE_LIMIT = 1;
    private static final int MAX_CANDIDATE_LIMIT = 1000;
    private static final Comparator<MethodTarget> TARGET_ORDER = Comparator
            .comparing(MethodTarget::sourceFile)
            .thenComparing(MethodTarget::packageName)
            .thenComparing(MethodTarget::className)
            .thenComparing(MethodTarget::methodName)
            .thenComparing(MethodTarget::parameterTypes, MethodImplementationDiscoveryApplicationService::compareParameters);

    private final RepositoryApplicationService repositoryApplicationService;
    private final SyntaxExtractionService syntaxExtractionService;
    private final CanonicalMethodDeclarationResolver declarationResolver;
    private final JavaSemanticService semanticService;
    private final CanonicalTargetProjection canonicalTargetProjection;
    private final ImplementationCandidateFactory candidateFactory;
    private final int candidateLimit;

    public MethodImplementationDiscoveryApplicationService(
            RepositoryApplicationService repositoryApplicationService,
            SyntaxExtractionService syntaxExtractionService,
            CanonicalMethodDeclarationResolver declarationResolver,
            JavaSemanticService semanticService,
            CanonicalTargetProjection canonicalTargetProjection,
            ImplementationCandidateFactory candidateFactory,
            int candidateLimit) {
        this.repositoryApplicationService = Objects.requireNonNull(
                repositoryApplicationService, "repositoryApplicationService is required");
        this.syntaxExtractionService = Objects.requireNonNull(
                syntaxExtractionService, "syntaxExtractionService is required");
        this.declarationResolver = Objects.requireNonNull(declarationResolver, "declarationResolver is required");
        this.semanticService = Objects.requireNonNull(semanticService, "semanticService is required");
        this.canonicalTargetProjection = Objects.requireNonNull(
                canonicalTargetProjection, "canonicalTargetProjection is required");
        this.candidateFactory = Objects.requireNonNull(candidateFactory, "candidateFactory is required");
        if (candidateLimit < MIN_CANDIDATE_LIMIT || candidateLimit > MAX_CANDIDATE_LIMIT) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and 1000");
        }
        this.candidateLimit = candidateLimit;
    }

    public RevisionBoundMethodImplementations discover(MethodImplementationDiscoveryQuery query) {
        Objects.requireNonNull(query, "query is required");
        return repositoryApplicationService.withSnapshot(
                query.repositoryId(),
                Optional.of(query.expectedRevision()),
                snapshot -> discoverSnapshot(snapshot, query));
    }

    private RevisionBoundMethodImplementations discoverSnapshot(
            RepositorySnapshot snapshot,
            MethodImplementationDiscoveryQuery query) {
        RepositorySyntax syntax = syntaxExtractionService.extract(snapshot.root());
        RepositorySyntaxIndex index = new RepositorySyntaxIndex(snapshot.repositoryId().value(), syntax);
        MethodTargetResolution resolution = declarationResolver.resolve(syntax, query.declarationTarget());
        SemanticDeclarationAnchor anchor = declarationAnchor(syntax, query.declarationTarget(), resolution);
        assertEligible(index, query.declarationTarget());
        SemanticMethod declaration = semanticService.resolveExactMethod(snapshot, anchor);
        SemanticImplementationResult implementationResult = semanticService.implementations(snapshot, declaration);
        CandidateProjection projection = projectCandidates(snapshot, index, query.declarationTarget(), implementationResult.methods());
        List<MethodImplementationIssueReason> issues = new ArrayList<>(mapAdapterIssues(implementationResult.issues()));
        issues.addAll(projection.issues());
        List<ImplementationCandidate> candidates = projection.candidates();
        int totalCount = candidates.size();
        List<ImplementationCandidate> returned = candidates.stream().limit(candidateLimit).toList();
        MethodImplementationLimits limits = new MethodImplementationLimits(
                candidateLimit,
                returned.size(),
                totalCount,
                totalCount > returned.size());
        return new RevisionBoundMethodImplementations(
                snapshot.repositoryId(),
                snapshot.revision(),
                query.declarationTarget(),
                returned,
                limits,
                issues);
    }

    private void assertEligible(RepositorySyntaxIndex index, MethodTarget target) {
        SourceMethodMetadata declaration = index.method(target)
                .orElseThrow(() -> new ImplementationDiscoveryContractException(target));
        SourceTypeMetadata metadata = index.sourceType(target)
                .orElseThrow(() -> new ImplementationDiscoveryContractException(target));
        boolean abstractDeclaration = declaration.abstractDeclaration()
                && !declaration.executableDeclaration();
        boolean abstractInterfaceMethod = SourceTypeKind.INTERFACE.equals(metadata.declaration().kind())
                && abstractDeclaration;
        boolean abstractClassMethod = SourceTypeKind.CLASS.equals(metadata.declaration().kind())
                && metadata.declaration().abstractType()
                && abstractDeclaration;
        if (!abstractInterfaceMethod && !abstractClassMethod) {
            throw new ImplementationTargetUnsupportedException(target);
        }
    }

    private SemanticDeclarationAnchor declarationAnchor(
            RepositorySyntax syntax,
            MethodTarget target,
            MethodTargetResolution resolution) {
        return switch (resolution.status()) {
            case RESOLVED -> syntax.sourceTypes().stream()
                    .flatMap(metadata -> metadata.members().methods().stream())
                    .filter(method -> method.analysisTarget().target().filter(target::equals).isPresent())
                    .map(method -> new SemanticDeclarationAnchor(
                            target,
                            new SemanticPosition(method.namePosition().line(), method.namePosition().character())))
                    .findFirst()
                    .orElseThrow(() -> new SemanticTargetNotFoundException(target));
            case UNRESOLVED -> throw new SemanticTargetNotFoundException(target);
            case AMBIGUOUS -> throw new SemanticBindingAmbiguousException(target, resolution.candidates());
        };
    }

    private CandidateProjection projectCandidates(
            RepositorySnapshot snapshot,
            RepositorySyntaxIndex index,
            MethodTarget requestedTarget,
            List<SemanticMethod> methods) {
        Map<MethodTarget, SemanticMethod> distinct = new LinkedHashMap<>();
        List<MethodImplementationIssueReason> issues = new ArrayList<>();
        for (SemanticMethod method : methods) {
            Optional<MethodTarget> target = canonicalTargetProjection.project(
                    semanticService.classifySource(snapshot, method), index, method);
            if (target.isEmpty()) {
                issues.add(MethodImplementationIssueReason.CANONICAL_TARGET_UNRESOLVED);
                continue;
            }
            MethodTarget canonicalTarget = target.orElseThrow();
            if (requestedTarget.equals(canonicalTarget)) {
                continue;
            }
            distinct.putIfAbsent(canonicalTarget, method);
        }
        List<ImplementationCandidate> candidates = new ArrayList<>();
        for (Map.Entry<MethodTarget, SemanticMethod> entry : distinct.entrySet()) {
            SourceMethodMetadata declaration = index.method(entry.getKey())
                    .orElseThrow(() -> new ImplementationDiscoveryContractException(entry.getKey()));
            if (!declaration.executableDeclaration()) {
                issues.add(MethodImplementationIssueReason.NON_EXECUTABLE_TARGET);
                continue;
            }
            ImplementationCandidate candidate = candidateFactory.create(index, entry.getValue(), entry.getKey())
                    .orElseThrow(() -> new ImplementationDiscoveryContractException(entry.getKey()));
            candidates.add(candidate);
        }
        return new CandidateProjection(candidates.stream()
                .sorted(Comparator.comparing(ImplementationCandidate::target, TARGET_ORDER))
                .toList(), issues);
    }

    private List<MethodImplementationIssueReason> mapAdapterIssues(
            List<SemanticImplementationIssueReason> adapterIssues) {
        return adapterIssues.stream().map(this::mapAdapterIssue).toList();
    }

    private MethodImplementationIssueReason mapAdapterIssue(SemanticImplementationIssueReason issue) {
        return switch (issue) {
            case LOCAL_CONVERSION_FAILED -> MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED;
            case EXTERNAL_TARGET -> MethodImplementationIssueReason.EXTERNAL_TARGET;
        };
    }

    private static int compareParameters(List<String> left, List<String> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int comparison = left.get(index).compareTo(right.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private record CandidateProjection(
            List<ImplementationCandidate> candidates,
            List<MethodImplementationIssueReason> issues) {

        private CandidateProjection {
            candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates are required"));
            issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
        }
    }
}
