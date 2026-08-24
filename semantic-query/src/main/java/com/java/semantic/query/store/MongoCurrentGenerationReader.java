package com.java.semantic.query.store;

import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Storage adapter retained for projection readers. Selection is delegated to the
 * Query policy boundary; it does not maintain an independent pointer algorithm.
 */
public final class MongoCurrentGenerationReader {

    private final CurrentGenerationSelector selector;

    public MongoCurrentGenerationReader(MongoTemplate template) {
        this(new CurrentGenerationSelector(Objects.requireNonNull(template, "mongo template is required"),
                new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of())), Duration.ofSeconds(2)));
    }

    MongoCurrentGenerationReader(CurrentGenerationSelector selector) {
        this.selector = Objects.requireNonNull(selector, "current generation selector is required");
    }

    public CurrentGeneration read(RepositoryId repositoryId, RepositoryRevision expectedRevision) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        Objects.requireNonNull(expectedRevision, "expected revision is required");
        try {
            return selector.select(repositoryId.value(), expectedRevision.value(), CurrentGenerationSelector.ALL_PROJECTIONS);
        } catch (com.java.semantic.query.application.RevisionOutdatedException exception) {
            CurrentGeneration current = selector.currentRepository(repositoryId.value());
            throw new RevisionOutdatedException(expectedRevision, current);
        } catch (com.java.semantic.query.application.IndexNotReadyException | com.java.semantic.query.application.IndexContractMismatchException exception) {
            throw new IndexNotReadyException("INDEX_NOT_READY");
        } catch (com.java.semantic.query.application.SemanticIndexUnavailableException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }

    /** Catalog reads expose only a sealed, compatible current generation. */
    public List<CurrentGeneration> list() {
        try {
            List<CurrentGeneration> compatible = new ArrayList<>();
            for (CurrentGeneration current : selector.listCurrentRepositories()) {
                try {
                    compatible.add(selector.select(current.repositoryId().value(), current.revision().value(),
                            CurrentGenerationSelector.ALL_PROJECTIONS));
                } catch (com.java.semantic.query.application.IndexNotReadyException
                         | com.java.semantic.query.application.IndexContractMismatchException exception) {
                    // Catalog omits corrupt or incomplete published rows.
                }
            }
            return List.copyOf(compatible);
        } catch (com.java.semantic.query.application.SemanticIndexUnavailableException exception) {
            throw new SemanticIndexUnavailableException("SEMANTIC_INDEX_UNAVAILABLE", exception);
        }
    }
}
