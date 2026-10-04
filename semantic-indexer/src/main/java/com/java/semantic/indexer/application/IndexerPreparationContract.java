package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** One strict transport-neutral binder; unknown fields and selector ambiguity fail closed. */
public final class IndexerPreparationContract {
    public static final Set<String> OPERATIONS = Set.of("prepare_source", "get_job");
    private IndexerPreparationContract() { }

    public record Input(RepositoryId repositoryId, Optional<PreparationRequestId> requestId,
            Optional<IndexJobId> jobId, Optional<RepositoryRevision> revision) { }

    public static Input input(String operation, Map<String, ?> fields) {
        if (!OPERATIONS.contains(operation) || Objects.isNull(fields)) {
            throw new IllegalArgumentException("invalid preparation operation or input");
        }
        Set<String> allowed = operation.equals("prepare_source")
                ? Set.of("repositoryId", "requestId", "revision") : Set.of("repositoryId", "requestId", "jobId");
        if (!allowed.containsAll(fields.keySet())) {
            throw new IllegalArgumentException("request contains an unknown field");
        }
        RepositoryId repository = new RepositoryId(text(fields, "repositoryId"));
        Optional<PreparationRequestId> request = fields.containsKey("requestId")
                ? Optional.of(new PreparationRequestId(text(fields, "requestId"))) : Optional.empty();
        Optional<IndexJobId> job = fields.containsKey("jobId")
                ? Optional.of(new IndexJobId(text(fields, "jobId"))) : Optional.empty();
        if (operation.equals("get_job") && request.isPresent() == job.isPresent()) {
            throw new IllegalArgumentException("get_job requires exactly one selector");
        }
        if (operation.equals("prepare_source") && request.isEmpty()) {
            throw new IllegalArgumentException("prepare_source requires requestId");
        }
        Optional<RepositoryRevision> revision = fields.containsKey("revision")
                ? Optional.of(RepositoryRevision.ofSha(text(fields, "revision"))) : Optional.empty();
        return new Input(repository, request, job, revision);
    }

    private static String text(Map<String, ?> fields, String key) {
        if (!(fields.get(key) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("required string field is missing");
        }
        return value;
    }
}
