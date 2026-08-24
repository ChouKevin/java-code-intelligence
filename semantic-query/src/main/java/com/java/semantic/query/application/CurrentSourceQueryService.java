package com.java.semantic.query.application;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.query.CurrentGeneration;
import com.mongodb.MongoException;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

public final class CurrentSourceQueryService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector selector;

    public CurrentSourceQueryService(MongoTemplate template, CurrentGenerationSelector selector) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
    }

    public PublishedSource getSource(String repositoryId, String revision, String sourcePath) {
        CurrentGeneration current = selector.select(repositoryId, revision, CurrentGenerationSelector.SOURCES);
        Assert.hasText(sourcePath, "source path is required");
        try {
            Document mapping = Optional.ofNullable(template.getCollection(IndexCollections.GENERATION_FILES).find(Filters.and(
                            Filters.eq("repoId", current.repositoryId().value()),
                            Filters.eq("generationId", current.generationId().value()),
                            Filters.eq("sourcePath", sourcePath))).first())
                    .orElseThrow(IndexNotReadyException::new);
            String sourceArtifactId = mapping.getString("sourceArtifactId");
            if (!StringUtils.hasText(sourceArtifactId)) {
                throw new IndexContractMismatchException();
            }
            Document artifact = Optional.ofNullable(template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                            .find(Filters.eq("sourceArtifactId", sourceArtifactId)).first())
                    .orElseThrow(IndexNotReadyException::new);
            String content = Optional.ofNullable(artifact.getString("utf8Content"))
                    .orElseThrow(IndexContractMismatchException::new);
            return new PublishedSource(current, sourcePath, content);
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }
}
