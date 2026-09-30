package com.java.semantic.indexer.api;

import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.application.IndexerPreparationFacade;
import com.java.semantic.indexer.job.IndexPublicationState;
import com.java.semantic.indexer.job.IndexRequestService;
import com.java.semantic.indexer.job.IndexJobTarget;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.util.MultiValueMap;
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

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Indexer-admin mutation API; responses never disclose a source path or credential. */
@RestController
@RequestMapping("/index/repositories/{repoId}")
public final class IndexRepositoryController {
    private final IndexRequestService requests;
    private final IndexerPreparationFacade preparation;

    public IndexRepositoryController(IndexRequestService requests, IndexerPreparationFacade preparation) {
        this.requests = Objects.requireNonNull(requests, "requests is required");
        this.preparation = Objects.requireNonNull(preparation, "preparation is required");
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

    @PostMapping("/metadata")
    public ResponseEntity<Map<String, Object>> metadata(@PathVariable String repoId, @RequestBody Map<String, Object> body) {
        return acceptedPreparation(preparation.refreshRepositoryMetadata(inputs(repoId, body)));
    }

    @PostMapping("/codebase")
    public ResponseEntity<Map<String, Object>> codebase(@PathVariable String repoId, @RequestBody Map<String, Object> body) {
        return acceptedPreparation(preparation.prepareCodebase(inputs(repoId, body)));
    }

    @PostMapping("/reviews")
    public ResponseEntity<Map<String, Object>> review(@PathVariable String repoId, @RequestBody Map<String, Object> body) {
        return acceptedPreparation(preparation.prepareReview(inputs(repoId, body)));
    }

    private static Map<String, Object> inputs(String repoId, Map<String, Object> body) {
        if (body.containsKey("repositoryId")) {
            throw new IllegalArgumentException("repositoryId belongs in the HTTP path");
        }
        Map<String, Object> fields = new java.util.LinkedHashMap<>(body);
        fields.put("repositoryId", repoId);
        return fields;
    }

    private static ResponseEntity<Map<String, Object>> acceptedPreparation(Map<String, Object> result) {
        java.net.URI location = java.net.URI.create("/index/repositories/" + result.get("repositoryId")
                + "/jobs?jobId=" + result.get("jobId"));
        return ResponseEntity.accepted().location(location).body(result);
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

    @GetMapping("/jobs")
    public ResponseEntity<Map<String, Object>> job(@PathVariable String repoId,
            @RequestParam MultiValueMap<String, String> selectors) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        selectors.forEach((key, values) -> {
            if (values.size() != 1) {
                throw new IllegalArgumentException("a job selector must occur exactly once");
            }
            fields.put(key, values.getFirst());
        });
        return ResponseEntity.ok(preparation.getJob(inputs(repoId, fields)));
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
                                   String failureCategory, Map<String, Object> review) {
        public static IndexJobResponse from(IndexJob job) {
            return new IndexJobResponse(job.id().value(), job.repositoryId().value(), job.target().map(IndexJobTargetResponse::from).orElse(null),
                    job.phase().name(), job.failureCategory().map(Enum::name).orElse(null), null);
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
