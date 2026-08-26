package com.java.semantic.indexer.uat;

import com.java.semantic.indexer.api.IndexRepositoryController;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.model.repository.RepositoryId;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** UAT control surface; it is not registered in any production profile. */
@RestController
@Profile("uat")
@RequestMapping("/index/uat")
public final class UatIndexController {
    private final UatPublicationGate publicationGate;
    private final UatRepositoryResetService resets;

    public UatIndexController(UatPublicationGate publicationGate, UatRepositoryResetService resets) {
        this.publicationGate = Objects.requireNonNull(publicationGate, "publication gate is required");
        this.resets = Objects.requireNonNull(resets, "reset service is required");
    }

    @PostMapping("/publication/arm")
    public PublicationCycleResponse armPublication() {
        try {
            return new PublicationCycleResponse(publicationGate.arm());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "publication gate already has an active cycle", exception);
        }
    }

    @PostMapping("/publication/release")
    public void releasePublication() {
        publicationGate.release();
    }

    @GetMapping("/publication/await")
    public PublicationCycleResponse awaitPublication() {
        try {
            return new PublicationCycleResponse(publicationGate.awaitReachedPublication());
        } catch (UatPublicationGate.PublicationObservationUnavailableException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "publication gate has no active unreached cycle", exception);
        } catch (UatPublicationGate.PublicationObservationTimeoutException exception) {
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "publication gate was not reached before timeout", exception);
        } catch (UatPublicationGate.PublicationObservationInterruptedException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "publication observation was interrupted", exception);
        }
    }

    @PostMapping("/repositories/{repoId}/reset")
    public ResponseEntity<IndexRepositoryController.IndexJobResponse> reset(@PathVariable String repoId) {
        try {
            IndexJob job = resets.admit(RepositoryId.of(repoId));
            return ResponseEntity.accepted().body(IndexRepositoryController.IndexJobResponse.from(job));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UAT reset admission was rejected", exception);
        }
    }

    public record PublicationCycleResponse(long cycleId) { }
}
