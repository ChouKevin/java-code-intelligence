package com.java.semantic.query.application;

import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class GitEvidenceReadServiceIT {
    private static final String CATALOG_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String HISTORY_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String REVISION = "1".repeat(40);

    @Test
    void reads_only_ready_repository_scoped_catalog_and_history_pages() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_evidence_read");
            seedRepository(template, "orders");
            seedCatalog(template, "orders", CATALOG_ID, "READY");
            seedHistory(template, "orders", "READY");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitBranchCollection branches = service.branches(
                    new SemanticQueryContract.GitBranchRequest("orders", Optional.of(CATALOG_ID), 1, 1));
            assertThat(branches.catalogId()).isEqualTo(CATALOG_ID);
            assertThat(branches.items()).extracting(SemanticQueryContract.GitBranchItem::branch).containsExactly("release");
            assertThat(branches.page().total()).isEqualTo(2);
            assertThat(branches.page().hasMore()).isFalse();

            SemanticQueryContract.GitCommitCollection commits = service.commits(
                    new SemanticQueryContract.GitCommitRequest("orders", HISTORY_ID, REVISION, 0, 20));
            assertThat(commits.items()).singleElement().satisfies(commit -> {
                assertThat(commit.revision()).isEqualTo(REVISION);
                assertThat(commit.parents()).containsExactly("2".repeat(40));
            });
            assertThatThrownBy(() -> service.commits(new SemanticQueryContract.GitCommitRequest("orders", HISTORY_ID,
                    "3".repeat(40), 0, 20))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hides_pending_cross_repository_and_policy_denied_evidence() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_evidence_hidden");
            seedRepository(template, "orders");
            seedRepository(template, "billing");
            seedCatalog(template, "billing", "cccccccc-cccc-cccc-cccc-cccccccccccc", "READY");
            seedHistory(template, "orders", "PREPARING");
            GitEvidenceReadService allowed = service(template, List.of("orders"));

            assertThatThrownBy(() -> allowed.branches(new SemanticQueryContract.GitBranchRequest("orders", Optional.of(CATALOG_ID), 0, 20)))
                    .isInstanceOf(GitEvidenceNotFoundException.class);
            assertThatThrownBy(() -> allowed.commits(new SemanticQueryContract.GitCommitRequest("orders", HISTORY_ID, REVISION, 0, 20)))
                    .isInstanceOf(GitEvidenceNotReadyException.class);
            GitEvidenceReadService denied = service(template, List.of());
            assertThatThrownBy(() -> denied.commits(new SemanticQueryContract.GitCommitRequest("orders", HISTORY_ID, REVISION, 0, 20)))
                    .isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void denies_repository_and_granular_policy_before_ready_evidence_and_rechecks_a_revoked_policy() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_evidence_policy");
            seedRepository(template, "orders");
            seedCatalog(template, "orders", CATALOG_ID, "READY");
            SemanticQueryContract.GitBranchRequest request = new SemanticQueryContract.GitBranchRequest("orders", Optional.of(CATALOG_ID), 0, 20);
            GitEvidenceReadService allowed = service(template, List.of("orders"));
            assertThat(allowed.branches(request).items()).hasSize(2);

            assertDenied(service(template, new ReadPolicyProperties(List.of("orders"), List.of("orders"), List.of(), List.of(), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of("orders"), List.of(),
                    List.of(new ReadPolicyProperties.PackageRule("orders", "example.private")), List.of(), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of("orders"), List.of(), List.of(),
                    List.of(new ReadPolicyProperties.ClassRule("orders", "example.private", "PrivateType")), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of(),
                    List.of(new ReadPolicyProperties.MethodRule("orders", "example.private", "PrivateType", "read", List.of())))), request);
            assertDenied(service(template, List.of()), request);
        }
    }

    @Test
    void rejects_malformed_ready_rows_as_a_shared_contract_mismatch() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_evidence_malformed");
            seedRepository(template, "orders");
            seedCatalog(template, "orders", CATALOG_ID, "READY");
            template.getCollection("git_branches").updateOne(new Document("repoId", "orders").append("catalogId", CATALOG_ID)
                    .append("ordinal", 0L), new Document("$set", new Document("head", 42)));

            assertThatThrownBy(() -> service(template, List.of("orders")).branches(
                    new SemanticQueryContract.GitBranchRequest("orders", Optional.of(CATALOG_ID), 0, 20)))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    private static GitEvidenceReadService service(MongoTemplate template, List<String> allowedRepositories) {
        ReadPolicyProperties properties = new ReadPolicyProperties(allowedRepositories, List.of(), List.of(), List.of(), List.of());
        return service(template, properties);
    }

    private static GitEvidenceReadService service(MongoTemplate template, ReadPolicyProperties properties) {
        return new GitEvidenceReadService(template, new ConfiguredReadPolicy(properties), Duration.ofSeconds(2));
    }

    private static void assertDenied(GitEvidenceReadService service, SemanticQueryContract.GitBranchRequest request) {
        assertThatThrownBy(() -> service.branches(request)).isInstanceOf(RepositoryNotFoundException.class);
    }

    private static void seedRepository(MongoTemplate template, String repositoryId) {
        template.getCollection("repositories").insertOne(new Document("repoId", repositoryId));
    }

    private static void seedCatalog(MongoTemplate template, String repositoryId, String catalogId, String state) {
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", catalogId).append("kind", "CATALOG").append("state", state)
                .append("gitEvidenceVersion", 1).append("observedAt", new Date()).append("total", 2L));
        template.getCollection("git_branches").insertMany(List.of(
                new Document("repoId", repositoryId).append("catalogId", catalogId).append("ordinal", 0L)
                        .append("branch", "main").append("head", REVISION),
                new Document("repoId", repositoryId).append("catalogId", catalogId).append("ordinal", 1L)
                        .append("branch", "release").append("head", "2".repeat(40))));
    }

    private static void seedHistory(MongoTemplate template, String repositoryId, String state) {
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", HISTORY_ID).append("kind", "HISTORY").append("state", state)
                .append("gitEvidenceVersion", 1).append("revision", REVISION).append("preparedAt", new Date()).append("total", 1L));
        template.getCollection("git_commits").insertOne(new Document("repoId", repositoryId).append("historyId", HISTORY_ID)
                .append("ordinal", 0L).append("revision", REVISION).append("parents", List.of("2".repeat(40)))
                .append("subject", "prepared commit").append("committedAt", new Date()));
    }
}
