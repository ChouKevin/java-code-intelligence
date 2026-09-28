package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.ReviewPreparationStage;
import com.java.semantic.model.index.IndexCollections;
import java.util.List;
import java.util.Objects;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Authorizes every mutable generation transition against its live job ownership. */
public final class GenerationBuildOwnership {
    private final MongoTemplate template;

    public GenerationBuildOwnership(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
    }

    public void require(GenerationWriteContext context) {
        GenerationWriteContext requiredContext = Objects.requireNonNull(context, "generation write context is required");
        if (Objects.isNull(template.getCollection(IndexCollections.INDEX_JOBS).find(activeFilter(requiredContext)).first())) {
            throw new IllegalStateException("generation write context is not owned by an active build");
        }
    }

    public Document activeFilter(GenerationWriteContext context) {
        GenerationWriteContext requiredContext = Objects.requireNonNull(context, "generation write context is required");
        Document common = new Document("jobId", requiredContext.jobId()).append("repoId", requiredContext.repositoryId().value())
                .append("active", true).append("phase", "RUNNING");
        Document build = new Document("operation", "BUILD").append("target.generationId", requiredContext.generationId().value());
        Document reviewBefore = reviewTargetFilter(requiredContext, ReviewPreparationStage.BUILDING_BEFORE, "before");
        Document reviewAfter = reviewTargetFilter(requiredContext, ReviewPreparationStage.BUILDING_AFTER, "after");
        common.append("$or", List.of(build, reviewBefore, reviewAfter));
        return common;
    }

    private static Document reviewTargetFilter(GenerationWriteContext context, ReviewPreparationStage stage, String side) {
        return new Document("operation", "REVIEW").append("review.stage", stage.name())
                .append("target.generationId", context.generationId().value())
                .append("review.reservedTargets." + side + ".generationId", context.generationId().value())
                .append("$expr", new Document("$and", List.of(
                        new Document("$eq", List.of("$target.revision", "$review.reservedTargets." + side + ".revision")),
                        new Document("$eq", List.of("$target.generation", "$review.reservedTargets." + side + ".generation")))));
    }
}
