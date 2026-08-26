package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexPublicationState;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.repository.RepositoryId;
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
                                   String failureCategory) {
        public static IndexJobResponse from(IndexJob job) {
            return new IndexJobResponse(job.id().value(), job.repositoryId().value(), job.target().map(IndexJobTargetResponse::from).orElse(null), // cs-allow
                    job.phase().name(), job.failureCategory().map(Enum::name).orElse(null)); // cs-allow
        }
    }

    public record IndexJobStatusResponse(String jobId, String repositoryId, IndexJobTargetResponse target,
                                         String operation, String phase, boolean active, String failureCategory,
                                         GenerationPointerResponse currentPointer) {
        static IndexJobStatusResponse from(IndexJob job, Optional<PublishedGenerationPointer> currentPointer) {
            return new IndexJobStatusResponse(job.id().value(), job.repositoryId().value(), job.target().map(IndexJobTargetResponse::from).orElse(null), // cs-allow
                    job.operation().name(), job.phase().name(), job.active(),
                    job.failureCategory().map(Enum::name).orElse(null), // cs-allow
                    currentPointer.map(GenerationPointerResponse::from).orElse(null)); // cs-allow
        }
    }

    public record IndexJobTargetResponse(String revision, String generationId, long generation) {
        static IndexJobTargetResponse from(com.java.semantic.indexer.job.IndexJobTarget target) {
            return new IndexJobTargetResponse(target.revision().value(), target.generationId().value(), target.generation());
        }
    }

    public record GenerationPointerResponse(String revision, String generationId, String manifestDigest, String committedJobId,
                                            java.time.Instant publishedAt) {
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
