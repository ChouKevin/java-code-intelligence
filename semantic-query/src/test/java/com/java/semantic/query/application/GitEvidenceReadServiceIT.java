package com.java.semantic.query.application;

import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class GitEvidenceReadServiceIT {
    private static final String CATALOG_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String HISTORY_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String COMPARISON_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
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
    void reports_absent_git_evidence_without_requiring_a_semantic_repository_row() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_evidence_absent");
            GitEvidenceReadService service = service(template, List.of("orders"));

            assertThatThrownBy(() -> service.branches(new SemanticQueryContract.GitBranchRequest("orders", Optional.of(CATALOG_ID), 0, 20)))
                    .isInstanceOf(GitEvidenceNotFoundException.class);
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

    @Test
    void reads_only_a_ready_exact_comparison_and_returns_bounded_patch_continuations() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_read");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitComparisonRequest comparisonRequest = new SemanticQueryContract.GitComparisonRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), 0, 20);

            SemanticQueryContract.GitComparisonCollection comparison = service.comparisons(comparisonRequest);
            SemanticQueryContract.GitFileDiffResult first = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), "change-0", Optional.empty()));
            SemanticQueryContract.GitFileDiffResult second = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), "change-0", first.nextCursor()));

            assertThat(comparison.items()).singleElement().satisfies(change -> assertThat(change.changeId()).isEqualTo("change-0"));
            assertThat(first.patch()).isEqualTo("first\n");
            assertThat(first.nextCursor()).isPresent();
            assertThat(second.patch()).isEqualTo("second\n");
            assertThat(second.nextCursor()).isEmpty();
            assertThatThrownBy(() -> service.comparisons(new SemanticQueryContract.GitComparisonRequest("orders", COMPARISON_ID,
                    "3".repeat(40), "2".repeat(40), 0, 20))).isInstanceOf(IllegalArgumentException.class);
            template.getCollection("git_comparison_patches").deleteOne(new Document("comparisonId", COMPARISON_ID).append("ordinal", 1L));
            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), "change-0", first.nextCursor()))).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_comparison_changes").deleteOne(new Document("comparisonId", COMPARISON_ID).append("changeId", "change-0"));
            assertThatThrownBy(() -> service.comparisons(comparisonRequest)).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                    new Document("$set", new Document("state", "PREPARING")));
            assertThatThrownBy(() -> service.comparisons(comparisonRequest)).isInstanceOf(GitEvidenceNotReadyException.class);
        }
    }

    @Test
    void rejects_a_bounded_diff_cursor_reused_for_a_different_persisted_change_scope() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_cursor_scope");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", COMPARISON_ID), new Document("$set", new Document("total", 2L)));
            template.getCollection("git_comparison_changes").insertOne(new Document("repoId", "orders").append("comparisonId", COMPARISON_ID)
                    .append("ordinal", 1L).append("changeId", "change-1").append("kind", "MODIFY").append("oldPath", "OTHER.md")
                    .append("newPath", "OTHER.md").append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "5".repeat(40))
                    .append("newBlobId", "6".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L)
                    .append("oldRawPath", rawPath("OTHER.md")).append("newRawPath", rawPath("OTHER.md"))
                    .append("oldPathKey", rawPathKey("OTHER.md")).append("newPathKey", rawPathKey("OTHER.md")));
            template.getCollection("git_comparison_patches").insertMany(List.of(
                    new Document("repoId", "orders").append("comparisonId", COMPARISON_ID).append("changeId", "change-1").append("ordinal", 0L).append("patch", "other-first\n"),
                    new Document("repoId", "orders").append("comparisonId", COMPARISON_ID).append("changeId", "change-1").append("ordinal", 1L).append("patch", "other-second\n")));
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitFileDiffResult first = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), "change-0", Optional.empty()));

            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION,
                    "2".repeat(40), "change-1", first.nextCursor()))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cursor");
            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION,
                    "2".repeat(40), "change-0", Optional.of("x".repeat(2049))))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cursor");
        }
    }

    @Test
    void rejects_a_ready_comparison_change_with_an_unknown_kind() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_invalid_kind");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("kind", "BROKEN")));

            assertThatThrownBy(() -> service(template, List.of("orders")).comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 20))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void rejects_a_ready_comparison_change_with_an_invalid_status_or_endpoint_combination() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_invalid_shape");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("diffStatus", "BROKEN")));

            assertThatThrownBy(() -> service(template, List.of("orders")).comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 20))).isInstanceOf(IndexContractMismatchException.class);

            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("diffStatus", "AVAILABLE").append("oldPath", "")));

            assertThatThrownBy(() -> service(template, List.of("orders")).comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 20))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void rejects_a_ready_mode_change_with_identical_modes() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_strict_shape");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("kind", "MODE")));

            assertThatThrownBy(() -> service(template, List.of("orders")).comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 20))).isInstanceOf(IndexContractMismatchException.class);

        }
    }

    @Test
    void rejects_a_ready_comparison_change_with_a_fractional_ordinal() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_fractional_ordinal");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("ordinal", 0.5D)));
            assertThatThrownBy(() -> service(template, List.of("orders")).comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 20))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void rejects_out_of_range_and_unavailable_patch_cursors_as_invalid_arguments() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_invalid_cursor");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitFileDiffResult first = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID,
                    REVISION, "2".repeat(40), "change-0", Optional.empty()));
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("patchChunkCount", 1L)));

            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION,
                    "2".repeat(40), "change-0", first.nextCursor()))).isInstanceOf(IllegalArgumentException.class);

            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("diffStatus", "BINARY").append("patchChunkCount", 0L)));
            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION,
                    "2".repeat(40), "change-0", first.nextCursor()))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void comparison_page_does_not_read_patch_rows_for_changes_outside_the_requested_page() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoCommandRecorder recorder = new MongoCommandRecorder();
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(recorder).build();
            try (MongoClient client = MongoClients.create(settings)) {
                MongoTemplate template = new MongoTemplate(client, "git_comparison_page_bound");
                seedRepository(template, "orders");
                seedComparison(template, "orders", "READY");
                seedAdditionalChanges(template, 40);
                int patchReadsBefore = recorder.patchReadCommands();
                int scopedCountsBefore = recorder.scopedCountCommands();

                SemanticQueryContract.GitComparisonCollection page = service(template, List.of("orders")).comparisons(
                        new SemanticQueryContract.GitComparisonRequest("orders", COMPARISON_ID, REVISION, "2".repeat(40), 0, 1));

                assertThat(page.items()).hasSize(1);
                assertThat(recorder.patchReadCommands() - patchReadsBefore).isZero();
                assertThat(recorder.scopedCountCommands() - scopedCountsBefore).isZero();
            }
        }
    }

    @Test
    void file_diff_reads_patch_rows_only_for_the_requested_change() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoCommandRecorder recorder = new MongoCommandRecorder();
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(recorder).build();
            try (MongoClient client = MongoClients.create(settings)) {
                MongoTemplate template = new MongoTemplate(client, "git_comparison_diff_bound");
                seedRepository(template, "orders");
                seedComparison(template, "orders", "READY");
                seedAdditionalChanges(template, 40);
                int patchReadsBefore = recorder.patchReadCommands();
                int scopedCountsBefore = recorder.scopedCountCommands();

                SemanticQueryContract.GitFileDiffResult result = service(template, List.of("orders")).fileDiff(
                        new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION, "2".repeat(40), "change-0", Optional.empty()));

                assertThat(result.patch()).isEqualTo("first\n");
                assertThat(recorder.patchReadCommands() - patchReadsBefore).isLessThanOrEqualTo(4);
                assertThat(recorder.scopedCountCommands() - scopedCountsBefore).isZero();
            }
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

    private static void seedComparison(MongoTemplate template, String repositoryId, String state) {
        String current = "2".repeat(40);
        template.getCollection("git_evidence_manifests").insertMany(List.of(
                new Document("repoId", repositoryId).append("evidenceId", "dddddddd-dddd-dddd-dddd-dddddddddddd").append("kind", "SNAPSHOT")
                        .append("state", "READY").append("gitEvidenceVersion", 1).append("revision", REVISION),
                new Document("repoId", repositoryId).append("evidenceId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee").append("kind", "SNAPSHOT")
                        .append("state", "READY").append("gitEvidenceVersion", 1).append("revision", current),
                new Document("repoId", repositoryId).append("evidenceId", COMPARISON_ID).append("kind", "COMPARISON").append("state", state)
                        .append("gitEvidenceVersion", 1).append("previous", REVISION).append("current", current).append("previousSnapshotId", "dddddddd-dddd-dddd-dddd-dddddddddddd")
                        .append("currentSnapshotId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee").append("ancestry", "PREVIOUS_ANCESTOR").append("total", 1L)));
        template.getCollection("git_comparison_changes").insertOne(new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID)
                .append("ordinal", 0L).append("changeId", "change-0").append("kind", "MODIFY").append("oldPath", "README.md")
                .append("newPath", "README.md").append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "3".repeat(40))
                .append("newBlobId", "4".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L)
                .append("oldRawPath", rawPath("README.md")).append("newRawPath", rawPath("README.md"))
                .append("oldPathKey", rawPathKey("README.md")).append("newPathKey", rawPathKey("README.md")));
        template.getCollection("git_comparison_patches").insertMany(List.of(
                new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID).append("changeId", "change-0").append("ordinal", 0L).append("patch", "first\n"),
                new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID).append("changeId", "change-0").append("ordinal", 1L).append("patch", "second\n")));
    }

    private static void seedAdditionalChanges(MongoTemplate template, int count) {
        template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", COMPARISON_ID),
                new Document("$set", new Document("total", (long) count + 1L)));
        for (int ordinal = 1; ordinal <= count; ordinal++) {
            String changeId = "change-" + ordinal;
            template.getCollection("git_comparison_changes").insertOne(new Document("repoId", "orders").append("comparisonId", COMPARISON_ID)
                    .append("ordinal", (long) ordinal).append("changeId", changeId).append("kind", "MODIFY").append("oldPath", "file-" + ordinal)
                    .append("newPath", "file-" + ordinal).append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "5".repeat(40))
                    .append("newBlobId", "6".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 1L)
                    .append("oldRawPath", rawPath("file-" + ordinal)).append("newRawPath", rawPath("file-" + ordinal))
                    .append("oldPathKey", rawPathKey("file-" + ordinal)).append("newPathKey", rawPathKey("file-" + ordinal)));
            template.getCollection("git_comparison_patches").insertOne(new Document("repoId", "orders").append("comparisonId", COMPARISON_ID)
                    .append("changeId", changeId).append("ordinal", 0L).append("patch", "patch-" + ordinal + "\n"));
        }
    }

    private static byte[] rawPath(String path) {
        return path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String rawPathKey(String path) {
        return java.util.HexFormat.of().formatHex(rawPath(path));
    }

    private static final class MongoCommandRecorder implements CommandListener {
        private final AtomicInteger patchReadCommands = new AtomicInteger();
        private final AtomicInteger scopedCountCommands = new AtomicInteger();

        @Override
        public void commandStarted(CommandStartedEvent event) {
            String collection = event.getCommand().containsKey("find") ? event.getCommand().getString("find").getValue()
                    : event.getCommand().containsKey("aggregate") ? event.getCommand().getString("aggregate").getValue() : "";
            if (collection.equals("git_comparison_patches")) {
                patchReadCommands.incrementAndGet();
            }
            if ((collection.equals("git_comparison_changes") || collection.equals("git_comparison_patches"))
                    && (event.getCommand().containsKey("count") || event.getCommand().containsKey("aggregate"))) {
                scopedCountCommands.incrementAndGet();
            }
        }

        private int patchReadCommands() {
            return patchReadCommands.get();
        }

        private int scopedCountCommands() {
            return scopedCountCommands.get();
        }
    }
}
