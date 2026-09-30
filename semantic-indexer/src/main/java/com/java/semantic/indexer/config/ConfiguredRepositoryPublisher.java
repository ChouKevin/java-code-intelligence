package com.java.semantic.indexer.config;

import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.domain.RepositoryRuntime;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.conversions.Bson;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** Publishes only non-sensitive configuration fields; evidence pointers are never replaced. */
@Component
public final class ConfiguredRepositoryPublisher {
    private final MongoTemplate template;
    private final RepositoryRuntimeRegistry repositories;

    public ConfiguredRepositoryPublisher(MongoTemplate template, RepositoryRuntimeRegistry repositories) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.repositories = Objects.requireNonNull(repositories, "repository registry is required");
    }

    public void publish() {
        List<RepositoryRuntime> configured = repositories.all();
        List<String> ids = configured.stream().map(runtime -> runtime.repositoryId().value()).toList();
        Date configuredAt = Date.from(Instant.now());
        for (RepositoryRuntime runtime : configured) {
            List<Bson> fields = new ArrayList<>();
            fields.add(Updates.set("configured", true));
            fields.add(Updates.set("displayName", runtime.status().displayName()));
            fields.add(Updates.set("defaultBranch", runtime.defaultBranch()));
            fields.add(Updates.set("configuredAt", configuredAt));
            fields.add(runtime.projectGuidePath().<Bson>map(path -> Updates.set("projectGuidePath", path))
                    .orElseGet(() -> Updates.unset("projectGuidePath")));
            template.getCollection(IndexCollections.REPOSITORIES).updateOne(
                    Filters.eq("repoId", runtime.repositoryId().value()), Updates.combine(fields), new UpdateOptions().upsert(true));
        }
        template.getCollection(IndexCollections.REPOSITORIES).updateMany(Filters.nin("repoId", ids),
                Updates.combine(Updates.set("configured", false), Updates.set("configuredAt", configuredAt)));
    }
}
