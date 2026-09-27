package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexPublicationState;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.ReviewJobPayload;
import com.java.semantic.model.git.GitComparisonId;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewComparisonType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Indexer-admin mutation API; responses never disclose a source path or credential. */
@RestController
@RequestMapping("/index/repositories/{repoId}")
public final class IndexRepositoryController {
    private final IndexRequestService requests;

    public IndexRepositoryController(IndexRequestService requests) {
        this.requests = Objects.requireNonNull(requests, "requests is required");
    }

    @PostMapping("/ensure")
    public ResponseEntity<IndexJobResponse> ensure(@PathVariable String repoId) {
        return accepted(requests.ensure(RepositoryId.of(repoId)));
    }

    @PostMapping("/sync")
    public ResponseEntity<IndexJobResponse> sync(@PathVariable String repoId, @RequestBody(required = false) SyncIndexRequest request) {
        String branch = Objects.requireNonNullElse(request, new SyncIndexRequest("")).branch();
        return accepted(requests.sync(RepositoryId.of(repoId), Optional.ofNullable(branch).filter(StringUtils::hasText)));
    }

    @PostMapping("/checkout")
    public ResponseEntity<IndexJobResponse> checkout(@PathVariable String repoId, @Valid @RequestBody CheckoutIndexRequest request) {
        return accepted(requests.checkout(RepositoryId.of(repoId), request.revision()));
    }

    @PostMapping("/git/refs")
    public ResponseEntity<IndexJobResponse> gitRefs(@PathVariable String repoId) {
        return accepted(requests.prepareGitRefs(RepositoryId.of(repoId)));
    }

    @PostMapping("/git/history")
    public ResponseEntity<IndexJobResponse> gitHistory(@PathVariable String repoId, @Valid @RequestBody GitHistoryIndexRequest request) {
        return accepted(requests.prepareGitHistory(RepositoryId.of(repoId), request.catalogId(), request.branch(), request.revision()));
    }

    @PostMapping("/git/comparisons")
    public ResponseEntity<IndexJobResponse> gitComparison(@PathVariable String repoId, @Valid @RequestBody GitComparisonIndexRequest request) {
        return accepted(requests.prepareGitComparison(RepositoryId.of(repoId), request.previous(), request.current()));
    }

    @PostMapping("/reviews")
    public ResponseEntity<IndexJobResponse> review(@PathVariable String repoId, @Valid @RequestBody ReviewIndexRequest request) {
        return accepted(requests.review(RepositoryId.of(repoId), RepositoryRevision.ofSha(request.revision())));
    }

    @PostMapping("/rebuild")
    public ResponseEntity<IndexJobResponse> rebuild(@PathVariable String repoId, @Valid @RequestBody RebuildIndexRequest request) {
        return accepted(requests.rebuild(RepositoryId.of(repoId), Objects.requireNonNull(request, "rebuild request is required")
                .authorizeIncompatibleSchema(), request.expectedCurrent().toPointer()));
    }

    @PostMapping("/rollback")
    public ResponseEntity<IndexJobResponse> rollback(@PathVariable String repoId, @Valid @RequestBody RollbackIndexRequest request) {
        return accepted(requests.rollback(RepositoryId.of(repoId), request.expectedCurrent().toPointer(),
                request.expectedRollback().toPointer()));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<IndexJobStatusResponse> job(@PathVariable String repoId, @PathVariable String jobId) {
        RepositoryId repositoryId = RepositoryId.of(repoId);
        IndexJob job = requests.job(new IndexJobId(jobId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "index job was not found"));
        if (!job.repositoryId().equals(repositoryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "index job was not found");
        }
        return ResponseEntity.ok(IndexJobStatusResponse.from(job, requests.currentPointer(repositoryId)));
    }

    @GetMapping("/publication")
    public ResponseEntity<IndexPublicationResponse> publication(@PathVariable String repoId) {
        IndexPublicationState state = requests.publicationState(RepositoryId.of(repoId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "repository publication was not found"));
        return ResponseEntity.ok(IndexPublicationResponse.from(state));
    }

    private static ResponseEntity<IndexJobResponse> accepted(IndexJob job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(IndexJobResponse.from(job));
    }

    public record IndexJobResponse(String jobId, String repositoryId, IndexJobTargetResponse target, String phase,
                                   String failureCategory, ReviewJobResponse review) {
        public static IndexJobResponse from(IndexJob job) {
            return new IndexJobResponse(job.id().value(), job.repositoryId().value(), job.target().map(IndexJobTargetResponse::from).orElse(null),
                    job.phase().name(), job.failureCategory().map(Enum::name).orElse(null),
                    job.review().map(ReviewJobResponse::from).orElse(null));
        }
    }

    public record IndexJobStatusResponse(String jobId, String repositoryId, IndexJobTargetResponse target,
                                         String operation, String phase, boolean active, String failureCategory,
                                         GenerationPointerResponse currentPointer, GitEvidenceResultResponse gitEvidence,
                                         ReviewJobResponse review) {
        static IndexJobStatusResponse from(IndexJob job, Optional<PublishedGenerationPointer> currentPointer) {
            return new IndexJobStatusResponse(job.id().value(), job.repositoryId().value(), job.target().map(IndexJobTargetResponse::from).orElse(null),
                    job.operation().name(), job.phase().name(), job.active(),
                    job.failureCategory().map(Enum::name).orElse(null),
                    currentPointer.map(GenerationPointerResponse::from).orElse(null),
                    job.gitEvidence().flatMap(payload -> payload.evidenceId().map(id -> new GitEvidenceResultResponse(id.value(), payload.branch().orElse(null),
                            payload.revision().map(RepositoryRevision::value).orElse(null),
                            payload.previousRevision().isPresent() ? id.value() : null,
                            payload.previousSnapshotId().map(GitSnapshotId::value).orElse(null),
                            payload.currentSnapshotId().map(GitSnapshotId::value).orElse(null))))
                            .orElse(null),
                    job.review().map(ReviewJobResponse::from).orElse(null));
        }
    }

    public record GitEvidenceResultResponse(String evidenceId, String branch, String revision, String comparisonId,
                                            String previousSnapshotId, String currentSnapshotId) { }

    public record ReviewJobResponse(String reviewId, ReviewComparisonType comparisonType, GenerationPointerResponse capturedBaseline,
                                    String requestedRevision, String stage, String aGenerationId, String bGenerationId,
                                    String comparisonId, String previousSnapshotId, String currentSnapshotId) {
        static ReviewJobResponse from(ReviewJobPayload review) {
            return new ReviewJobResponse(review.reviewId().value(), ReviewComparisonType.CURRENT_TO_COMMIT,
                    GenerationPointerResponse.from(review.baseline().pointer()), review.requestedRevision().value(), review.stage().name(),
                    review.a().map(generation -> generation.selected().generationId().value()).orElse(null),
                    review.b().map(generation -> generation.selected().generationId().value()).orElse(null),
                    review.comparisonId().map(GitComparisonId::value).orElse(null),
                    review.previousSnapshotId().map(GitSnapshotId::value).orElse(null),
                    review.currentSnapshotId().map(GitSnapshotId::value).orElse(null));
        }
    }

    public record IndexJobTargetResponse(String revision, String generationId, long generation) {
        static IndexJobTargetResponse from(IndexJobTarget target) {
            return new IndexJobTargetResponse(target.revision().value(), target.generationId().value(), target.generation());
        }
    }

    public record GenerationPointerResponse(String revision, String generationId, String manifestDigest, String committedJobId,
                                            Instant publishedAt) {
        static GenerationPointerResponse from(PublishedGenerationPointer pointer) {
            return new GenerationPointerResponse(pointer.revision().value(), pointer.generationId().value(),
                    pointer.manifestDigest().value(), pointer.committedJobId(), pointer.publishedAt());
        }
    }

    public record IndexPublicationResponse(GenerationPointerResponse currentPointer,
                                           GenerationPointerResponse rollbackPointer) {
        static IndexPublicationResponse from(IndexPublicationState state) {
            return new IndexPublicationResponse(state.currentPointer().map(GenerationPointerResponse::from).orElse(null), // cs-allow
                    state.rollbackPointer().map(GenerationPointerResponse::from).orElse(null)); // cs-allow
        }
    }
}
