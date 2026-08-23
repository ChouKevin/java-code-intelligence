package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.model.repository.RepositoryId;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<IndexJobResponse> rebuild(@PathVariable String repoId, @RequestBody RebuildIndexRequest request) {
        return accepted(requests.rebuild(RepositoryId.of(repoId), Objects.requireNonNull(request, "rebuild request is required")
                .authorizeIncompatibleSchema()));
    }

    @PostMapping("/rollback")
    public ResponseEntity<IndexJobResponse> rollback(@PathVariable String repoId, @Valid @RequestBody RollbackIndexRequest request) {
        return accepted(requests.rollback(RepositoryId.of(repoId), request.expectedCurrent().toPointer(),
                request.expectedRollback().toPointer()));
    }

    private static ResponseEntity<IndexJobResponse> accepted(IndexJob job) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(IndexJobResponse.from(job));
    }

    public record IndexJobResponse(String jobId, String repositoryId, String revision, String generationId, String phase,
                                   String failureCategory) {
        static IndexJobResponse from(IndexJob job) {
            return new IndexJobResponse(job.id().value(), job.repositoryId().value(), job.revision().value(), job.generationId().value(),
                    job.phase().name(), job.failureCategory().map(Enum::name).orElse(null)); // cs-allow
        }
    }
}
