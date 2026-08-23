package com.java.semantic.indexer.api;

import com.java.semantic.api.dto.ApiErrorResponse;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** Administrative job status endpoint. */
@RestController
@RequestMapping("/index/jobs")
public final class IndexJobController {
    private final IndexRequestService requests;

    public IndexJobController(IndexRequestService requests) {
        this.requests = Objects.requireNonNull(requests, "requests is required");
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<?> job(@PathVariable String jobId) {
        return requests.job(new IndexJobId(jobId)).map(IndexRepositoryController.IndexJobResponse::from)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        ApiErrorResponse.of("INDEX_JOB_NOT_FOUND", "index job was not found", "")));
    }
}
