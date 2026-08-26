package com.java.semantic.indexer.uat;

import com.java.semantic.config.JdtLsProperties;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobStore;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/** UAT-only admission and dispatcher-owned cleanup for one configured repository. */
@Service
@Profile("uat")
public final class UatRepositoryResetService {
    private static final String UAT_DATABASE = "semantic_uat";
    private static final List<String> REPOSITORY_COLLECTIONS = List.of(
            IndexCollections.GENERATION_MANIFESTS, IndexCollections.GENERATION_FILES, IndexCollections.SYMBOLS,
            IndexCollections.RELATIONS, IndexCollections.ENTRY_POINTS, IndexCollections.SEARCH);
    private final MongoTemplate template;
    private final IndexJobStore jobs;
    private final RepositoryProperties repositories;
    private final JdtLsProperties jdtLs;

    public UatRepositoryResetService(MongoTemplate template, IndexJobStore jobs, RepositoryProperties repositories,
                                     JdtLsProperties jdtLs) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.jobs = Objects.requireNonNull(jobs, "job store is required");
        this.repositories = Objects.requireNonNull(repositories, "repository properties are required");
        this.jdtLs = Objects.requireNonNull(jdtLs, "JDT properties are required");
    }

    public IndexJob admit(RepositoryId repositoryId) {
        requireSafeConfiguredRepository(repositoryId);
        requireUatDatabase();
        return jobs.admitReset(repositoryId);
    }

    public void reset(IndexJob job) {
        Objects.requireNonNull(job, "job is required");
        if (job.operation() != IndexJobOperation.RESET || job.phase() != IndexJobPhase.RUNNING || !job.active()) {
            throw new IllegalArgumentException("UAT reset requires an active RUNNING RESET job");
        }
        RepositoryId repositoryId = job.repositoryId();
        requireSafeConfiguredRepository(repositoryId);
        requireUatDatabase();
        template.getCollection(IndexCollections.REPOSITORIES).deleteOne(repositoryFilter(repositoryId));
        template.getCollection(IndexCollections.INDEX_JOBS).deleteMany(repositoryFilter(repositoryId)
                .append("jobId", new Document("$ne", job.id().value())));
        for (String collection : REPOSITORY_COLLECTIONS) {
            template.getCollection(collection).deleteMany(repositoryFilter(repositoryId));
        }
        deleteContained(repositoryRoot(repositoryId));
        deleteContained(jdtWorkspaceRoot(repositoryId));
    }

    private void requireSafeConfiguredRepository(RepositoryId repositoryId) {
        Objects.requireNonNull(repositoryId, "repository id is required");
        if (!repositories.getRepositories().containsKey(repositoryId.value())) {
            throw new IllegalArgumentException("UAT reset repository is not configured");
        }
        repositoryRoot(repositoryId);
        jdtWorkspaceRoot(repositoryId);
    }

    private void requireUatDatabase() {
        if (!UAT_DATABASE.equals(template.getDb().getName())) {
            throw new IllegalStateException("UAT reset requires the semantic_uat database");
        }
    }

    private Path repositoryRoot(RepositoryId repositoryId) {
        return containedChild(Path.of(repositories.getDataRoot()), repositoryId);
    }

    private Path jdtWorkspaceRoot(RepositoryId repositoryId) {
        return containedChild(jdtLs.workspaceDataRoot(), repositoryId);
    }

    private static Path containedChild(Path configuredRoot, RepositoryId repositoryId) {
        Path root = Objects.requireNonNull(configuredRoot, "configured data root is required").toAbsolutePath().normalize();
        Path child = root.resolve(repositoryId.value()).normalize();
        if (!child.startsWith(root) || child.equals(root)) {
            throw new IllegalArgumentException("UAT reset path is outside its configured root");
        }
        return child;
    }

    private static Document repositoryFilter(RepositoryId repositoryId) {
        return new Document("repoId", repositoryId.value());
    }

    private static void deleteContained(Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.sorted(Comparator.reverseOrder()).forEach(UatRepositoryResetService::deletePath);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to reset UAT repository workspace", exception);
        }
    }

    private static void deletePath(Path path) {
        try {
            Files.delete(path);
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to delete UAT repository workspace path", exception);
        }
    }
}
