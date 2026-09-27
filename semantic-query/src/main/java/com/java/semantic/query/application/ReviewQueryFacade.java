package com.java.semantic.query.application;

import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SourceIndexCoverage;
import com.java.semantic.model.index.SourceIndexIssue;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.CapturedReviewBaseline;
import com.java.semantic.model.review.ReviewEndpoint;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.config.SearchAccessPlan;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

/** Selects immutable READY review evidence once and delegates semantic work to the selected-generation service. */
public final class ReviewQueryFacade {
    private final ReviewGenerationSelector selector;
    private final ReviewManifestReadService manifests;
    private final SelectedSemanticQueryService selectedQueries;
    private final SelectedGenerationGuard guard;
    private final SourceIndexCoverageReader coverageReader;

    public ReviewQueryFacade(ReviewGenerationSelector selector, ReviewManifestReadService manifests,
                             SelectedSemanticQueryService selectedQueries, SelectedGenerationGuard guard,
                             SourceIndexCoverageReader coverageReader) {
        this.selector = Objects.requireNonNull(selector, "review generation selector is required");
        this.manifests = Objects.requireNonNull(manifests, "review manifest reader is required");
        this.selectedQueries = Objects.requireNonNull(selectedQueries, "selected semantic query service is required");
        this.guard = Objects.requireNonNull(guard, "selected generation guard is required");
        this.coverageReader = Objects.requireNonNull(coverageReader, "source coverage reader is required");
    }

    public ReviewQueryContract.ReviewDetails getReview(ReviewQueryContract.ReviewRequest request) {
        ReviewQueryContract.ReviewRequest requiredRequest = Objects.requireNonNull(request, "review request is required");
        RepositoryId repositoryId = new RepositoryId(requiredRequest.repositoryId());
        ReviewId reviewId = new ReviewId(requiredRequest.reviewId());
        ReviewManifestDocument manifest = manifests.requireReady(repositoryId, reviewId);
        ReviewEndpoint endpointA = manifest.a().orElseThrow(IndexContractMismatchException::new);
        ReviewEndpoint endpointB = manifest.b().orElseThrow(IndexContractMismatchException::new);
        ReviewSelection a = selector.select(repositoryId, reviewId, ReviewSide.A, endpointA.generation().selected().revision(),
                SelectedGenerationGuard.SOURCES);
        ReviewSelection b = selector.select(repositoryId, reviewId, ReviewSide.B, endpointB.generation().selected().revision(),
                SelectedGenerationGuard.SOURCES);
        CapturedReviewBaseline baseline = manifest.capturedBaseline();
        return new ReviewQueryContract.ReviewDetails(repositoryId.value(), reviewId.value(), manifest.comparisonType(),
                new ReviewQueryContract.CapturedBaselineDetails(baseline.pointer().revision().value(),
                        baseline.pointer().generationId().value(), baseline.pointer().manifestDigest().value(), baseline.capturedAt()),
                endpointDetails(endpointA, a.selected()),
                endpointDetails(endpointB, b.selected()),
                manifest.comparisonId().orElseThrow(IndexContractMismatchException::new).value(),
                manifest.publishedAt().orElseThrow(IndexContractMismatchException::new));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.SearchCodeResult> searchCode(
            ReviewQueryContract.ReviewSearchCodeRequest request) {
        ReviewQueryContract.ReviewSearchCodeRequest requiredRequest = Objects.requireNonNull(request, "review search request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.SEARCH_WITH_SOURCES);
        SemanticQueryContract.SearchCodeRequest operation = new SemanticQueryContract.SearchCodeRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.query(), requiredRequest.kinds(), requiredRequest.packagePrefix(),
                requiredRequest.offset(), requiredRequest.limit());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.searchCode(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.FactSourceResult> getFactSource(
            ReviewQueryContract.ReviewFactSourceRequest request) {
        ReviewQueryContract.ReviewFactSourceRequest requiredRequest = Objects.requireNonNull(request, "review fact source request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.SEARCH_WITH_SOURCES);
        SemanticQueryContract.FactSourceRequest operation = new SemanticQueryContract.FactSourceRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.factId(), requiredRequest.contextLines());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.getFactSource(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> listEntryPoints(
            ReviewQueryContract.ReviewEntryPointRequest request) {
        ReviewQueryContract.ReviewEntryPointRequest requiredRequest = Objects.requireNonNull(request, "review entry point request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.ENTRY_POINTS);
        SemanticQueryContract.EntryPointRequest operation = new SemanticQueryContract.EntryPointRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.kinds(), requiredRequest.offset(), requiredRequest.limit());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.listEntryPoints(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findApiRoutes(
            ReviewQueryContract.ReviewApiRouteRequest request) {
        ReviewQueryContract.ReviewApiRouteRequest requiredRequest = Objects.requireNonNull(request, "review API route request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.ENTRY_POINTS);
        SemanticQueryContract.ApiRouteRequest operation = new SemanticQueryContract.ApiRouteRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.httpMethod(), requiredRequest.path(), requiredRequest.offset(), requiredRequest.limit());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findApiRoutes(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findEventListeners(
            ReviewQueryContract.ReviewEventListenerRequest request) {
        ReviewQueryContract.ReviewEventListenerRequest requiredRequest = Objects.requireNonNull(request, "review event listener request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.SYMBOLS);
        SemanticQueryContract.EventListenerRequest operation = new SemanticQueryContract.EventListenerRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.eventType(), requiredRequest.offset(), requiredRequest.limit());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findEventListeners(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> listTypeMembers(
            ReviewQueryContract.ReviewTypeMemberRequest request) {
        ReviewQueryContract.ReviewTypeMemberRequest requiredRequest = Objects.requireNonNull(request, "review type member request is required");
        ReviewSelection selection = select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(),
                requiredRequest.revision(), SelectedGenerationGuard.SEARCH);
        SemanticQueryContract.TypeMemberRequest operation = new SemanticQueryContract.TypeMemberRequest(requiredRequest.repositoryId(),
                requiredRequest.revision(), requiredRequest.typeFactId(), requiredRequest.kinds(), requiredRequest.offset(), requiredRequest.limit());
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.listTypeMembers(selection.selected(), operation));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findMethodImplementations(
            ReviewQueryContract.ReviewRelationRequest request) {
        ReviewSelection selection = relationSelection(request);
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findMethodImplementations(selection.selected(), relationOperation(request)));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findReferences(
            ReviewQueryContract.ReviewRelationRequest request) {
        ReviewSelection selection = relationSelection(request);
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findReferences(selection.selected(), relationOperation(request)));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findCallers(
            ReviewQueryContract.ReviewRelationRequest request) {
        ReviewSelection selection = relationSelection(request);
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findCallers(selection.selected(), relationOperation(request)));
    }

    public ReviewQueryContract.ReviewResult<SemanticQueryContract.CollectionResult> findCallees(
            ReviewQueryContract.ReviewRelationRequest request) {
        ReviewSelection selection = relationSelection(request);
        return new ReviewQueryContract.ReviewResult<>(context(selection), selectedQueries.findCallees(selection.selected(), relationOperation(request)));
    }

    private ReviewSelection relationSelection(ReviewQueryContract.ReviewRelationRequest request) {
        ReviewQueryContract.ReviewRelationRequest requiredRequest = Objects.requireNonNull(request, "review relation request is required");
        return select(requiredRequest.repositoryId(), requiredRequest.reviewId(), requiredRequest.side(), requiredRequest.revision(),
                SelectedGenerationGuard.SEARCH);
    }

    private static SemanticQueryContract.RelationRequest relationOperation(ReviewQueryContract.ReviewRelationRequest request) {
        return new SemanticQueryContract.RelationRequest(request.repositoryId(), request.revision(), request.factId(), request.offset(), request.limit());
    }

    private ReviewSelection select(String repositoryId, String reviewId, ReviewSide side, String revision,
                                   ProjectionRequirements requirements) {
        return selector.select(new RepositoryId(repositoryId), new ReviewId(reviewId), side, new RepositoryRevision(revision), requirements);
    }


    private ReviewQueryContract.ReviewContext context(ReviewSelection selection) {
        SelectedGeneration selected = selection.selected();
        return new ReviewQueryContract.ReviewContext(selected.repositoryId().value(), selection.manifest().reviewId().value(), selection.side(),
                selected.revision().value(), selected.generationId().value(), coverage(selection));
    }

    private ReviewQueryContract.ReviewEndpointDetails endpointDetails(ReviewEndpoint endpoint, SelectedGeneration selected) {
        SealedGeneration generation = endpoint.generation();
        return new ReviewQueryContract.ReviewEndpointDetails(selected.revision().value(), selected.generationId().value(),
                selected.manifestDigest().value(), generation.fingerprint().digest(), endpoint.snapshotId().value(),
                coverage(selected, generation.analysisEvidence()));
    }

    private ReviewQueryContract.ReviewCoverage coverage(ReviewSelection selection) {
        ReviewEndpoint endpoint = selection.side() == ReviewSide.A ? selection.manifest().a().orElseThrow(IndexContractMismatchException::new)
                : selection.manifest().b().orElseThrow(IndexContractMismatchException::new);
        return coverage(selection.selected(), endpoint.generation().analysisEvidence());
    }

    private ReviewQueryContract.ReviewCoverage coverage(SelectedGeneration selected, SemanticAnalysisEvidence evidence) {
        SearchAccessPlan accessPlan = guard.searchAccessPlan(selected.repositoryId().value());
        SourceIndexCoverage sourceCoverage = coverageReader.coverage(selected, accessPlan, Optional.empty(), Optional.empty());
        SortedSet<String> limitations = new TreeSet<>();
        sourceCoverage.issues().stream().map(SourceIndexIssue::code).forEach(limitations::add);
        for (SemanticAnalysisEvidence.Limitation limitation : evidence.limitations()) {
            if (limitation.sourcePath().isEmpty() || coverageReader.coverage(selected, accessPlan, Optional.empty(),
                    limitation.sourcePath()).indexedSourceCount() > 0) {
                limitations.add(limitation.code());
            }
        }
        List<String> limitationCodes = List.copyOf(limitations);
        List<String> issueCodes = sourceCoverage.issues().stream().map(SourceIndexIssue::code).distinct().toList();
        return new ReviewQueryContract.ReviewCoverage(new SemanticQueryContract.SourceCoverage(sourceCoverage.indexedSourceCount(),
                sourceCoverage.issues().size(), issueCodes), limitationCodes);
    }
}
