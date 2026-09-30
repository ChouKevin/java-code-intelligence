package com.java.semantic.indexer.application;

import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.PreparationRequestId;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSelection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Transport-neutral strict input contract shared by HTTP and MCP. */
public final class IndexerPreparationContract {
    public static final Set<String> OPERATIONS = Set.of("refresh_repository_metadata", "prepare_codebase", "prepare_review", "get_job");
    private IndexerPreparationContract() { }

    public record Input(RepositoryId repositoryId, Optional<PreparationRequestId> requestId,
            Optional<IndexJobId> jobId, Optional<String> branch, Optional<ReviewSelection> selection) { }

    public static Input input(String operation, Map<String, ?> fields) {
        if (!OPERATIONS.contains(operation) || Objects.isNull(fields)) {
            throw new IllegalArgumentException("invalid preparation operation or input");
        }
        Set<String> allowed = switch (operation) {
            case "refresh_repository_metadata" -> Set.of("repositoryId", "requestId", "branch");
            case "prepare_review" -> Set.of("repositoryId", "requestId", "selection");
            case "get_job" -> Set.of("repositoryId", "requestId", "jobId");
            default -> Set.of("repositoryId", "requestId");
        };
        if (!allowed.containsAll(fields.keySet())) {
            throw new IllegalArgumentException("request contains an unknown field");
        }
        RepositoryId repositoryId = RepositoryId.of(text(fields, "repositoryId"));
        Optional<PreparationRequestId> requestId = fields.containsKey("requestId")
                ? Optional.of(new PreparationRequestId(text(fields, "requestId"))) : Optional.empty();
        Optional<IndexJobId> jobId = fields.containsKey("jobId")
                ? Optional.of(new IndexJobId(text(fields, "jobId"))) : Optional.empty();
        if (operation.equals("get_job")) {
            if (requestId.isPresent() == jobId.isPresent()) {
                throw new IllegalArgumentException("exactly one of jobId or requestId is required");
            }
        } else if (requestId.isEmpty()) {
            throw new IllegalArgumentException("requestId is required");
        }
        Optional<String> branch = fields.containsKey("branch") ? Optional.of(text(fields, "branch")) : Optional.empty();
        Optional<ReviewSelection> selection = operation.equals("prepare_review")
                ? Optional.of(selection(fields.get("selection"))) : Optional.empty();
        return new Input(repositoryId, requestId, jobId, branch, selection);
    }

    private static ReviewSelection selection(Object value) {
        if (!(value instanceof Map<?, ?> fields)) {
            throw new IllegalArgumentException("selection is required");
        }
        String kind = text(fields, "kind");
        if (kind.equals("COMMIT") && fields.keySet().equals(Set.of("kind", "revision"))) {
            return ReviewSelection.commit(RepositoryRevision.ofSha(text(fields, "revision")));
        }
        if (kind.equals("RANGE") && fields.keySet().equals(Set.of("kind", "beforeRevision", "afterRevision"))) {
            return ReviewSelection.range(RepositoryRevision.ofSha(text(fields, "beforeRevision")),
                    RepositoryRevision.ofSha(text(fields, "afterRevision")));
        }
        throw new IllegalArgumentException("selection must be a strict COMMIT or RANGE union");
    }

    private static String text(Map<?, ?> fields, String name) {
        if (!(fields.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be a nonblank string");
        }
        return value;
    }
}
