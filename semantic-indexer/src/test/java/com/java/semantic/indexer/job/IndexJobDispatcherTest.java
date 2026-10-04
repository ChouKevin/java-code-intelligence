package com.java.semantic.indexer.job;

import com.java.semantic.indexer.source.DurableSourceFiles;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexJobDispatcherTest {
    @TempDir Path root;

    @Test
    void concurrent_admission_is_denied_until_the_original_job_is_terminal() throws Exception {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setSourceAdminRoot(root.resolve("source-admin").toString());
        properties.setSourcePublishedRoot(root.resolve("source-published").toString());
        try (DurableSourceFiles owner = new DurableSourceFiles(root.resolve("source-admin"))) {
            FileSourceJobStore jobs = new FileSourceJobStore(properties, JsonMapper.builder().build(), owner);
            RepositoryId repository = new RepositoryId("orders");
            PreparationRequestId firstRequest = new PreparationRequestId(UUID.randomUUID().toString());
            SourcePreparationJob first = jobs.admit(repository, firstRequest, Optional.empty(), "main",
                    "a".repeat(64), Optional.empty());
            assertThatThrownBy(() -> jobs.admit(repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.empty(), "main", "a".repeat(64), Optional.empty()))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            SourcePreparationJob claimed = jobs.claimNext().orElseThrow();
            assertThat(claimed.jobId()).isEqualTo(first.jobId());
            assertThat(jobs.claimNext()).isEmpty();
            assertThatThrownBy(() -> jobs.admit(repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.empty(), "main", "a".repeat(64), Optional.empty()))
                    .isInstanceOf(IndexJobAlreadyActiveException.class);
            jobs.fail(repository, new IndexJobId(claimed.jobId()), "PREPARATION_FAILED");
            SourcePreparationJob next = jobs.admit(repository, new PreparationRequestId(UUID.randomUUID().toString()),
                    Optional.empty(), "main", "a".repeat(64), Optional.empty());
            assertThat(jobs.claimNext().orElseThrow().jobId()).isEqualTo(next.jobId());
        }
    }
}
