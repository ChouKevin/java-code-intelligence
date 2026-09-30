package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.GitEvidenceProperties;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceStructure;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.source.ProjectGuideProvenance;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@Tag("mongo-it")
class GitEvidenceReadServiceIT {
    private static final String CATALOG_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String HISTORY_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String COMPARISON_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String REVISION = "1".repeat(40);
    private static final String SNAPSHOT_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String ALTERNATE_SNAPSHOT_ID = "99999999-9999-9999-9999-999999999999";
    private static final String ALTERNATE_REVISION = "2".repeat(40);

    @Test
    void denies_configuration_rename_endpoints_even_with_a_persisted_patch_and_cursor() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_rename_policy");
            seedComparison(template, "orders", "READY");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitFileDiffResult first = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest(
                    "orders", COMPARISON_ID, REVISION, "2".repeat(40), "change-0", Optional.empty()));
            String forbidden = "application.properties";
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID),
                    new Document("$set", new Document("kind", "RENAME").append("newPath", forbidden)
                            .append("newRawPath", rawPath(forbidden)).append("newPathKey", pathKey(forbidden))));
            for (Optional<String> cursor : List.of(Optional.<String>empty(), first.nextCursor())) {
                assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest(
                        "orders", COMPARISON_ID, REVISION, "2".repeat(40), "change-0", cursor)))
                        .isInstanceOf(IndexContractMismatchException.class);
            }
            assertThatThrownBy(() -> service.comparisons(new SemanticQueryContract.GitComparisonRequest(
                    "orders", COMPARISON_ID, REVISION, "2".repeat(40), 0, 20)))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void rejects_missing_or_rebound_source_generation_instead_of_using_a_same_revision_candidate() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_exact_source_generation");
            seedSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));
            Document original = template.getCollection("generation_manifests")
                    .find(new Document("generationId", "source-" + SNAPSHOT_ID)).first();
            Document equivalent = new Document(original);
            equivalent.remove("_id");
            equivalent.put("generationId", "equivalent");
            equivalent.put("identityDigest", digest("equivalent"));
            template.getCollection("generation_manifests").insertOne(equivalent);
            assertThat(service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.empty())).content()).isEqualTo("a😀\r\n");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID),
                    new Document("$unset", new Document("sourceGenerationId", "")));
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.empty()))).isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID),
                    new Document("$set", new Document("sourceGenerationId", "source-" + SNAPSHOT_ID)));
            template.getCollection("generation_manifests").deleteOne(new Document("generationId", "source-" + SNAPSHOT_ID));
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.empty(), Optional.empty(), 1))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void guide_search_exclusion_and_invalid_membership_apply_to_files_diffs_and_continuations_without_blocking_code() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_guide_boundaries");
            seedSnapshot(template, "orders");
            String guidePath = "docs/codebase/overview.md";
            String guideText = """
                    guide-only-marker
                    ```json
                    {"formatVersion":1,"promptVersion":1,"repositoryId":"orders","analyzedRevision":"%s",
                     "generatedAt":"2026-09-28T00:00:00Z","sourceScope":{"includedPaths":["src"],"excludedPaths":[],"limitations":["author navigation"]}}
                    ```
                    second guide line
                    """.formatted("2".repeat(40));
            byte[] guideBytes = guideText.getBytes(StandardCharsets.UTF_8);
            String guideDigest = SourceArtifactDocument.create(guideText).contentHash();
            insertTextSnapshotFile(template, "orders", 1L, guidePath, guideBytes);
            String beforeId = siblingSnapshotId("orders", "job-snapshot");
            ProjectGuideProvenance provenance = new ProjectGuideProvenance(1, 1, new RepositoryId("orders"),
                    new RepositoryRevision("2".repeat(40)), java.time.Instant.parse("2026-09-28T00:00:00Z"),
                    new ProjectGuideProvenance.SourceScope(List.of("src"), List.of(), List.of("author navigation")));
            ProjectGuideMembership available = new ProjectGuideMembership(ProjectGuideState.AVAILABLE, Optional.of(guidePath),
                    Optional.of(guideDigest), Optional.of(new RepositoryRevision(REVISION)), Optional.of(provenance), "NOT_VERIFIED");
            configureFixtureGuide(template, SNAPSHOT_ID, available);
            configureFixtureGuide(template, beforeId, ProjectGuideMembership.unavailable(ProjectGuideState.ABSENT));
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("path", guidePath),
                    new Document("$set", new Document("contentKind", "PROJECT_GUIDE").append("checksum", guideDigest)));
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID),
                    new Document("$set", new Document("total", 2L).append("contentCoverage.entryCount", 2L)
                            .append("contentCoverage.textEntries", 2L).append("contentCoverage.textBytes", 27L + guideBytes.length)));
            String reviewId = "11111111-2222-3333-4444-555555555555";
            markSnapshotComparisonReviewOwned(template, "orders", reviewId, "READY");
            template.getCollection("repositories").insertOne(new Document("repoId", "orders").append("currentPointer",
                    new Document("revision", REVISION).append("generationId", "source-" + SNAPSHOT_ID)
                            .append("manifestDigest", digest("source-" + SNAPSHOT_ID))));
            Document comparison = template.getCollection("git_evidence_manifests").find(new Document("kind", "COMPARISON")).first();
            String comparisonId = comparison.getString("evidenceId");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", comparisonId),
                    new Document("$set", new Document("total", 1L)));
            template.getCollection("git_comparison_changes").insertOne(new Document("repoId", "orders")
                    .append("comparisonId", comparisonId).append("ordinal", 0L).append("changeId", "guide-add").append("kind", "ADD")
                    .append("oldPath", "").append("newPath", guidePath).append("oldMode", "0").append("newMode", "100644")
                    .append("oldBlobId", "0".repeat(40)).append("newBlobId", "2".repeat(40))
                    .append("oldRawPath", rawPath("")).append("newRawPath", rawPath(guidePath))
                    .append("oldPathKey", "").append("newPathKey", pathKey(guidePath))
                    .append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L));
            template.getCollection("git_comparison_patches").insertMany(List.of(
                    new Document("repoId", "orders").append("comparisonId", comparisonId).append("changeId", "guide-add")
                            .append("ordinal", 0L).append("patch", "+guide-only-marker\n"),
                    new Document("repoId", "orders").append("comparisonId", comparisonId).append("changeId", "guide-add")
                            .append("ordinal", 1L).append("patch", "+second guide line\n")));
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitFileContent guide = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, guidePath, Optional.empty(), 1, Optional.empty()));
            assertThat(guide.content()).isEqualTo("guide-only-marker\n");
            assertThat(service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "guide-only-marker", Optional.empty(), Optional.empty(), 20)).items()).isEmpty();
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "guide-only-marker", Optional.empty(), Optional.of(cursor("git-search", "orders", SNAPSHOT_ID, REVISION,
                            "guide-only-marker", "", "1", "0", "0")), 20))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", beforeId, "2".repeat(40),
                    guidePath, Optional.empty(), 1, Optional.empty()))).isInstanceOf(GitEvidenceNotFoundException.class);
            SemanticQueryContract.GitFileDiffResult diff = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest(
                    "orders", comparisonId, "2".repeat(40), REVISION, "guide-add", Optional.empty()));
            assertThat(diff.patch()).isEqualTo("+guide-only-marker\n");
            configureFixtureGuide(template, SNAPSHOT_ID, ProjectGuideMembership.unavailable(ProjectGuideState.INVALID));
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, guidePath, Optional.empty(), 1, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, guidePath, Optional.empty(), 1, guide.nextCursor())))
                    .isInstanceOf(IndexContractMismatchException.class);
            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest(
                    "orders", comparisonId, "2".repeat(40), REVISION, "guide-add", diff.nextCursor())))
                    .isInstanceOf(IndexContractMismatchException.class);
            assertThat(service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.empty())).content()).isEqualTo("a😀\r\n");
            assertThat(service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "src", 0, 20)).items())
                    .extracting(SemanticQueryContract.GitFileItem::path).containsExactly("src/demo.java");
            assertThat(service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.empty(), Optional.empty(), 20)).items())
                    .extracting(SemanticQueryContract.GitTextMatch::column).containsExactly(1, 8);
        }
    }

    private static void configureFixtureGuide(MongoTemplate template, String snapshotId, ProjectGuideMembership guide) {
        Document snapshot = template.getCollection("git_evidence_manifests").find(new Document("evidenceId", snapshotId)).first();
        String generationId = snapshot.getString("sourceGenerationId");
        Document generation = template.getCollection("generation_manifests").find(new Document("generationId", generationId)).first();
        SourceEvidencePolicy original = SourceEvidenceDocumentCodec.decodePolicy(generation.get("sourcePolicy", Document.class));
        SourceEvidencePolicy policy = new SourceEvidencePolicy(SourceEvidencePolicy.VERSION, original.includedRoots(),
                original.selectedCodePaths(), Optional.of("docs/codebase/overview.md"));
        Document membership = new Document(generation.get("sourceSnapshot", Document.class));
        membership.put("policyFingerprint", policy.fingerprint());
        template.getCollection("generation_manifests").updateOne(new Document("generationId", generationId),
                new Document("$set", new Document("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(policy)))
                        .append("sourceSnapshot", membership).append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))));
        template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", snapshotId),
                new Document("$set", new Document("policyFingerprint", policy.fingerprint())
                        .append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))));
        template.getCollection("git_snapshot_files").updateMany(new Document("snapshotId", snapshotId),
                new Document("$set", new Document("policyFingerprint", policy.fingerprint())));
    }

    @Test
    void rejects_old_git_contract_instead_of_defaulting_standalone_ownership() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            try (MongoClient client = MongoClients.create(container.getConnectionString())) {
                MongoTemplate template = new MongoTemplate(client, "git_cutover_no_default");
                seedRepository(template, "orders");
                seedCatalog(template, "orders", CATALOG_ID, "READY");
                template.getCollection("git_evidence_manifests").updateOne(
                        new Document("repoId", "orders").append("evidenceId", CATALOG_ID),
                        new Document("$set", new Document("gitEvidenceVersion", 1))
                                .append("$unset", new Document("scope", "")));
                GitEvidenceReadService service = service(template, List.of("orders"));

                assertThatThrownBy(() -> service.branches(new SemanticQueryContract.GitBranchRequest(
                        "orders", Optional.of(CATALOG_ID), 0, 20))).isInstanceOf(IndexContractMismatchException.class);
            }
        }
    }

    @Test
    void denies_review_owned_snapshot_evidence_while_its_review_is_preparing() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_review_owner_gate");
            seedSnapshot(template, "orders");
            markSnapshotComparisonReviewOwned(template, "orders", "review-owner-gate", "PREPARING");
            GitEvidenceReadService service = service(template, List.of("orders"));

            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, REVISION, "", 0, 20))).isInstanceOf(GitEvidenceNotReadyException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty())))
                    .isInstanceOf(GitEvidenceNotReadyException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(), Optional.empty(), 1)))
                    .isInstanceOf(GitEvidenceNotReadyException.class);
        }
    }

    @Test
    void rejects_ready_review_owned_comparison_when_a_snapshot_owner_is_mutated() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_review_owner_mutation");
            seedSnapshot(template, "orders");
            markSnapshotComparisonReviewOwned(template, "orders", "11111111-2222-3333-4444-555555555555", "READY");
            GitEvidenceReadService service = service(template, List.of("orders"));
            Document parent = template.getCollection("git_evidence_manifests").find(new Document("kind", "COMPARISON")).first();
            assertThat(service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 20)).items())
                    .isNotEmpty();
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", parent.getString("previousSnapshotId")),
                    new Document("$set", new Document("ownerJobId", "mutated-owner")));

            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 20)))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void revalidates_complete_review_ownership_for_direct_and_cursor_git_evidence_reads() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_review_all_entries");
            seedSnapshot(template, "orders");
            String reviewId = "11111111-2222-3333-4444-555555555555";
            markSnapshotComparisonReviewOwned(template, "orders", reviewId, "READY");
            Document comparison = completeReviewComparison(template, "orders");
            String comparisonId = comparison.getString("evidenceId");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitFileContent file = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty()));
            SemanticQueryContract.GitTextSearchResult search = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.of("src"), Optional.empty(), 1));
            SemanticQueryContract.GitFileDiffResult diff = service.fileDiff(new SemanticQueryContract.GitFileDiffRequest(
                    "orders", comparisonId, "2".repeat(40), REVISION, "change-0", Optional.empty()));

            for (String state : List.of("PREPARING", "FAILED")) {
                template.getCollection("review_manifests").updateOne(new Document("reviewId", reviewId), new Document("$set", new Document("state", state)));
                assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 20)))
                        .isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                        "src/demo.java", Optional.empty(), 1, Optional.empty()))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                        "needle", Optional.of("src"), Optional.empty(), 1))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.comparisons(new SemanticQueryContract.GitComparisonRequest("orders", comparisonId,
                        "2".repeat(40), REVISION, 0, 20))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", comparisonId,
                        "2".repeat(40), REVISION, "change-0", Optional.empty()))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                        "src/demo.java", Optional.empty(), 1, file.nextCursor()))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                        "needle", Optional.of("src"), search.nextCursor(), 1))).isInstanceOf(GitEvidenceNotReadyException.class);
                assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", comparisonId,
                        "2".repeat(40), REVISION, "change-0", diff.nextCursor()))).isInstanceOf(GitEvidenceNotReadyException.class);
            }
            template.getCollection("review_manifests").updateOne(new Document("reviewId", reviewId), new Document("$set", new Document("state", "READY")));

            assertThat(service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, file.nextCursor())).content()).isEqualTo("needle needle\n");
            assertThat(service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", comparisonId, "2".repeat(40), REVISION,
                    "change-0", diff.nextCursor())).patch()).isEqualTo("second\n");
        }
    }

    @Test
    void reads_snapshot_files_and_text_through_checkpoint_derived_cursors() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_read");
            seedSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitFileCollection listing = service.listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, REVISION, "", 0, 20));
            SemanticQueryContract.GitFileContent first = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty()));
            SemanticQueryContract.GitFileContent second = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, first.nextCursor()));
            SemanticQueryContract.GitFileContent partialLine = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1,
                    Optional.of(cursor("git-file", "orders", SNAPSHOT_ID, REVISION, pathKey("src/demo.java"), "1", "11"))));
            SemanticQueryContract.GitTextSearchResult firstSearch = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.of("src"), Optional.empty(), 1));
            SemanticQueryContract.GitTextSearchResult secondSearch = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.of("src"), firstSearch.nextCursor(), 1));

            assertThat(listing.items()).extracting(SemanticQueryContract.GitFileItem::path).containsExactly("src");
            assertThat(listing.coverage().readableTextCount()).isEqualTo(1L);
            assertThat(first.content()).isEqualTo("a😀\r\n");
            assertThat(second.content()).isEqualTo("needle needle\n");
            assertThat(partialLine.startLine()).isEqualTo(2);
            assertThat(partialLine.startLineComplete()).isFalse();
            assertThat(firstSearch.items()).singleElement().satisfies(match -> {
                assertThat(match.line()).isEqualTo(2);
                assertThat(match.column()).isEqualTo(1);
                assertThat(match.snippet()).isEqualTo("needle needle");
                assertThat(match.snippetTruncated()).isFalse();
            });
            assertThat(secondSearch.items()).singleElement().satisfies(match -> {
                assertThat(match.column()).isEqualTo(8);
                assertThat(match.snippetTruncated()).isTrue();
            });

            String tamperedLegacyPosition = cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "src", "0", "1", "7", "999", "999");
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("src"), Optional.of(tamperedLegacyPosition), 1))).isInstanceOf(IllegalArgumentException.class);
            String multibyteSeek = cursor("git-file", "orders", SNAPSHOT_ID, REVISION, pathKey("src/demo.java"), "0", "2");
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(multibyteSeek)))).isInstanceOf(IllegalArgumentException.class);
            String outOfRangeChunk = cursor("git-file", "orders", SNAPSHOT_ID, REVISION, pathKey("src/demo.java"), "3", "0");
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(outOfRangeChunk)))).isInstanceOf(IllegalArgumentException.class);
            template.getCollection("git_snapshot_chunks").deleteOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 1L));
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, first.nextCursor()))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void r2_rejects_a_search_cursor_replayed_with_a_distinct_lone_high_surrogate_query() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r2_surrogate_cursor");
            seedSnapshot(template, "orders");
            byte[] questionMarks = "??".getBytes(StandardCharsets.UTF_8);
            insertTextSnapshotFile(template, "orders", 1L, "src/question.java", questionMarks);
            template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", "orders").append("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 2L).append("contentCoverage.entryCount", 2L).append("contentCoverage.textEntries", 2L)
                            .append("contentCoverage.textBytes", 27L + questionMarks.length)));
            sealAfterFixtureMutation(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitTextSearchResult first = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "?", Optional.empty(), Optional.empty(), 1));

            assertThat(first.nextCursor()).isPresent();
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "\uD800", Optional.empty(), first.nextCursor(), 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void r2_owner_comparison_lookup_uses_the_unhinted_owner_job_index() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r2_owner_lookup");
            createManifestOwnerLookupIndex(template);
            seedSnapshot(template, "orders");
            seedUnrelatedComparisonOwners(template, "orders", 256);
            GitEvidenceReadService service = service(template, List.of("orders"));

            assertThat(service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)).items()).isNotEmpty();
            Document find = ownerComparisonFind("orders", "job-snapshot");
            Document explain = template.getDb().runCommand(new Document("explain", find).append("verbosity", "executionStats"));
            Document statistics = explain.get("executionStats", Document.class);

            assertThat(find.containsKey("hint")).isFalse();
            assertThat(number(statistics, "nReturned")).isEqualTo(1L);
            assertThat(number(statistics, "totalKeysExamined")).isEqualTo(1L);
            assertThat(number(statistics, "totalDocsExamined")).isEqualTo(1L);
            assertThat(containsDocumentValue(statistics.get("executionStages"), "stage", "IXSCAN")).isTrue();
            assertThat(containsDocumentValue(statistics.get("executionStages"), "indexName", "git_evidence_manifest_owner_lookup")).isTrue();
        }
    }

    @Test
    void requires_a_complete_ready_comparison_owner_for_snapshot_rows() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_comparison_owner");
            seedSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));
            String siblingSnapshotId = siblingSnapshotId("orders", "job-snapshot");
            template.getCollection("git_evidence_manifests").deleteOne(new Document("evidenceId", siblingSnapshotId));
            template.getCollection("git_evidence_manifests").updateMany(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("state", "PREPARING")));

            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(GitEvidenceNotReadyException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.empty()))).isInstanceOf(GitEvidenceNotReadyException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.empty(), Optional.empty(), 1))).isInstanceOf(GitEvidenceNotReadyException.class);
            template.getCollection("git_evidence_manifests").updateMany(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("state", "FAILED")));
            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(GitEvidenceNotReadyException.class);
            template.getCollection("git_evidence_manifests").updateMany(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("state", "READY")));

            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateMany(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("state", "UNKNOWN")));
            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateMany(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("state", "READY")));
            Document restoredSibling = new Document(template.getCollection("git_evidence_manifests")
                    .find(new Document("evidenceId", SNAPSHOT_ID)).first());
            restoredSibling.remove("_id");
            restoredSibling.put("evidenceId", siblingSnapshotId);
            restoredSibling.put("revision", "2".repeat(40));
            restoredSibling.put("contentDigest", digest(siblingSnapshotId));
            restoredSibling.put("sourceGenerationId", "source-" + siblingSnapshotId);
            restoredSibling.put("ownerJobId", "wrong-owner");
            template.getCollection("git_evidence_manifests").insertOne(restoredSibling);
            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", siblingSnapshotId),
                    new Document("$set", new Document("ownerJobId", "job-snapshot")));
            assertThat(service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)).items()).isNotEmpty();
            template.getCollection("git_evidence_manifests").updateOne(new Document("kind", "COMPARISON"),
                    new Document("$set", new Document("previousSnapshotId", "a".repeat(36))));
            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 1)))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void reconstructs_long_path_read_cursors_with_fixed_identity_and_rejects_another_file_replay() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_long_path_cursor");
            seedSnapshot(template, "orders");
            String path = "nested/".repeat(10_000) + "evidence.java";
            byte[] bytes = "first\nsecond\n".getBytes(StandardCharsets.UTF_8);
            insertTextSnapshotFile(template, "orders", 1L, path, bytes);
            insertTextSnapshotFile(template, "orders", 2L, path + ".other.java", bytes);
            template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", "orders").append("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 3L).append("contentCoverage.entryCount", 3L).append("contentCoverage.textEntries", 3L)
                            .append("contentCoverage.textBytes", 27L + 2L * bytes.length)));
            sealAfterFixtureMutation(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitFileContent first = service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    path, Optional.empty(), 1, Optional.empty()));
            SemanticQueryContract.GitFileContent second = service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    path, Optional.empty(), 1, first.nextCursor()));

            assertThat(path).hasSizeGreaterThan(65_536);
            assertThat(first.nextCursor()).isPresent();
            assertThat(second.content()).isEqualTo("second\n");
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    path + ".other.java", Optional.empty(), 1, first.nextCursor()))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void final003_does_not_replay_a_terminal_admitted_single_code_point_match_after_budget_exhaustion() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "final003_kmp_green");
            seedBudgetDuplicateSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitTextSearchResult first = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "a", Optional.empty(), Optional.empty(), 100));
            SemanticQueryContract.GitTextSearchResult replay = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "a", Optional.empty(), first.nextCursor(), 100));

            assertThat(first.items()).hasSize(1);
            assertThat(first.scanComplete()).isFalse();
            assertThat(replay.items()).isEmpty();
        }
    }

    @Test
    void r1_rejects_revision_mismatch_empty_text_unknown_status_and_corrupt_checkpoint() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_contract");
            seedSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, "2".repeat(40), "", 0, 20))).isInstanceOf(IllegalArgumentException.class);
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 0L),
                    new Document("$set", new Document("line", 2L)));
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void r1_reports_empty_text_as_an_empty_complete_range() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_empty");
            seedReaderContinuationSnapshot(template, "orders");
            SemanticQueryContract.GitFileContent empty = service(template, List.of("orders")).readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/empty.java", Optional.empty(), 1, Optional.empty()));

            assertThat(empty.content()).isEmpty();
            assertThat(empty.startLine()).isZero();
            assertThat(empty.endLine()).isZero();
            assertThat(empty.startLineComplete()).isTrue();
            assertThat(empty.endLineComplete()).isTrue();
        }
    }

    @Test
    void r1_reconstructs_unicode_crlf_and_long_unterminated_text_across_read_pages() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_read_pages");
            seedSnapshot(template, "orders");
            seedLongLineSnapshot(template, "orders");
            sealAfterFixtureMutation(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitFileContent unicodeFirst = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty()));
            SemanticQueryContract.GitFileContent unicodeSecond = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, unicodeFirst.nextCursor()));
            SemanticQueryContract.GitFileContent unicodeThird = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, unicodeSecond.nextCursor()));
            assertThat(unicodeFirst.content() + unicodeSecond.content() + unicodeThird.content()).isEqualTo("a😀\r\nneedle needle\n最後");
            assertThat(unicodeThird.endLineComplete()).isTrue();
            assertThat(unicodeThird.nextCursor()).isEmpty();

            SemanticQueryContract.GitFileContent longFirst = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/long-line.java", Optional.empty(), 500, Optional.empty()));
            SemanticQueryContract.GitFileContent longSecond = service.readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/long-line.java", Optional.empty(), 500, longFirst.nextCursor()));
            assertThat(longFirst.content().getBytes(StandardCharsets.UTF_8)).hasSize(64 * 1024);
            assertThat(longFirst.nextCursor()).isPresent();
            assertThat(longFirst.content() + longSecond.content()).isEqualTo("😀".repeat(20_000));
            assertThat(longSecond.endLineComplete()).isTrue();
            assertThat(longSecond.nextCursor()).isEmpty();
        }
    }

    @Test
    void r1_rejects_read_and_search_cursors_when_any_valid_authorized_scope_changes() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_cursor_scopes");
            seedSnapshot(template, "orders");
            seedSnapshot(template, "invoices");
            insertTextSnapshotFile(template, "orders", 1L, "src/other.java", "needle".getBytes(StandardCharsets.UTF_8));
            insertTextSnapshotFile(template, "orders", 2L, "lib/other.java", "needle".getBytes(StandardCharsets.UTF_8));
            template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", "orders").append("evidenceId", SNAPSHOT_ID),
                    new Document("$set", new Document("total", 3L).append("contentCoverage.entryCount", 3L).append("contentCoverage.textEntries", 3L)
                            .append("contentCoverage.textBytes", 34L)));
            sealAfterFixtureMutation(template, "orders");
            copySnapshot(template, "orders", SNAPSHOT_ID, ALTERNATE_SNAPSHOT_ID, REVISION);
            copySnapshot(template, "orders", SNAPSHOT_ID, "88888888-8888-8888-8888-888888888888", ALTERNATE_REVISION);
            GitEvidenceReadService service = service(template, List.of("orders", "invoices"));
            String readCursor = cursor("git-file", "orders", SNAPSHOT_ID, REVISION, pathKey("src/demo.java"), "0", "0");
            String alternateRevisionCursor = cursor("git-file", "orders", "88888888-8888-8888-8888-888888888888", ALTERNATE_REVISION,
                    pathKey("src/demo.java"), "0", "0");
            String searchCursor = cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "src", "0", "0", "0");
            String alternateRevisionSearchCursor = cursor("git-search", "orders", "88888888-8888-8888-8888-888888888888", ALTERNATE_REVISION,
                    "needle", "src", "0", "0", "0");

            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("src"), Optional.of(readCursor), 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("invoices", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(readCursor)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", ALTERNATE_SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(readCursor)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(alternateRevisionCursor)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/other.java", Optional.empty(), 1, Optional.of(readCursor)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                    "src/demo.java", Optional.empty(), 1, Optional.of(searchCursor)))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("invoices", SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("src"), Optional.of(searchCursor), 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", ALTERNATE_SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("src"), Optional.of(searchCursor), 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("src"), Optional.of(alternateRevisionSearchCursor), 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "need", Optional.of("src"), Optional.of(searchCursor), 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.of("lib"), Optional.of(searchCursor), 1))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void r1_accepts_whitespace_literal_search_queries() {
        assertThat(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION, " ", Optional.empty(), Optional.empty(), 1).query())
                .isEqualTo(" ");
        assertThatThrownBy(() -> new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION, null,
                Optional.empty(), Optional.empty(), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void r1_rejects_corrupt_status_and_missing_cursor_target_and_stops_at_total_limit() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_search_contract");
            seedReaderContinuationSnapshot(template, "orders");
            insertTextSnapshotFile(template, "orders", 4L, "src/after.java", "Zneedle-more".getBytes(StandardCharsets.UTF_8));
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 5L).append("contentCoverage.entryCount", 5L).append("contentCoverage.textEntries", 5L)
                            .append("contentCoverage.textBytes", 229L)));
            sealAfterFixtureMutation(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitTextSearchResult limited = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "Z", Optional.empty(), Optional.empty(), 1));
            assertThat(limited.items()).singleElement().extracting(SemanticQueryContract.GitTextMatch::path).isEqualTo("src/edge.java");
            assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                    "needle", Optional.empty(), Optional.of(cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "9", "0", "0")), 1)))
                    .isInstanceOf(IllegalArgumentException.class);
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 4L),
                    new Document("$set", new Document("contentStatus", "BROKEN")));
            assertThatThrownBy(() -> service.listFiles(new SemanticQueryContract.GitFileListRequest("orders", SNAPSHOT_ID, REVISION, "", 0, 20)))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void r1_rejects_a_missing_search_cursor_target_before_later_rows_are_read() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_cursor_target");
            seedReaderContinuationSnapshot(template, "orders");
            insertTextSnapshotFile(template, "orders", 5L, "src/later.java", "needle-more".getBytes(StandardCharsets.UTF_8));
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 5L).append("contentCoverage.entryCount", 5L).append("contentCoverage.textEntries", 5L)
                            .append("contentCoverage.textBytes", 228L)));

            sealAfterFixtureMutation(template, "orders");
            assertThatThrownBy(() -> service(template, List.of("orders")).searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(),
                    Optional.of(cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "4", "0", "0")), 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void r1_rejects_unknown_content_statuses_and_corrupt_chunk_zero_checkpoints() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_storage_contract");
            seedSnapshot(template, "orders");
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 0L),
                    new Document("$set", new Document("line", 2L)));
            assertThatThrownBy(() -> service(template, List.of("orders")).readFile(new SemanticQueryContract.GitFileReadRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src/demo.java", Optional.empty(), 1, Optional.empty())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void r2_rejects_direct_read_and_search_cursors_to_a_corrupt_nonzero_checkpoint() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r2_checkpoint_seek");
            seedSnapshot(template, "orders");
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 1L),
                    new Document("$set", new Document("line", 7L).append("column", 9L)));
            GitEvidenceReadService service = service(template, List.of("orders"));
            String path = pathKey("src/demo.java");

            assertAll(
                    () -> assertThatThrownBy(() -> service.readFile(new SemanticQueryContract.GitFileReadRequest("orders", SNAPSHOT_ID, REVISION,
                            "src/demo.java", Optional.empty(), 1, Optional.of(cursor("git-file", "orders", SNAPSHOT_ID, REVISION, path, "1", "11")))))
                            .isInstanceOf(IndexContractMismatchException.class),
                    () -> assertThatThrownBy(() -> service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                            "needle", Optional.empty(), Optional.of(cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "0", "1", "11")), 1)))
                            .isInstanceOf(IndexContractMismatchException.class));
        }
    }

    @Test
    void r1_rejects_unknown_content_statuses_in_snapshot_coverage() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_status_contract");
            seedSnapshot(template, "orders");
            insertSnapshotFile(template, "orders", 1L, "src/binary.bin".getBytes(StandardCharsets.UTF_8), "BINARY");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 2L).append("contentCoverage.entryCount", 2L)));
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 1L),
                    new Document("$set", new Document("contentStatus", "BROKEN")));

            assertThatThrownBy(() -> service(template, List.of("orders")).listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src", 0, 20))).isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void r1_reuses_the_budgeted_match_chunk_for_its_snippet() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_budget_snippet");
            seedSnippetBudgetSnapshot(template, "orders");

            SemanticQueryContract.GitTextSearchResult result = service(template, List.of("orders")).searchText(
                    new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(), Optional.empty(), 1));

            assertThat(result.items()).singleElement().satisfies(match -> assertThat(match.snippet()).startsWith("needle"));
        }
    }

    @Test
    void r1_reuses_cross_chunk_literal_source_after_the_search_budget_is_exhausted() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_r1_cross_chunk_snippet");
            seedCrossChunkSnippetBudgetSnapshot(template, "orders");

            SemanticQueryContract.GitTextSearchResult result = service(template, List.of("orders")).searchText(
                    new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION, "ab", Optional.empty(), Optional.empty(), 1));

            assertThat(result.items()).singleElement().satisfies(match -> assertThat(match.snippet()).startsWith("ab"));
        }
    }

    @Test
    void scopes_directory_listing_by_raw_path_and_uses_code_point_directory_offsets() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_raw_directory");
            seedSnapshot(template, "orders");
            insertTextSnapshotFile(template, "orders", 1L, "😀/file.java", new byte[0]);
            insertTextSnapshotFile(template, "orders", 2L, "😀/nested/file.java", new byte[0]);
            insertSnapshotFile(template, "orders", 3L, new byte[] {'s', 'r', 'c', '/', (byte) 0xff, 'x'}, "BINARY");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID), new Document("$set",
                    new Document("total", 4L).append("contentCoverage.entryCount", 4L).append("contentCoverage.textEntries", 3L)));
            sealAfterFixtureMutation(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitFileCollection unicode = service.listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, REVISION, "😀", 0, 20));
            SemanticQueryContract.GitFileCollection raw = service.listFiles(new SemanticQueryContract.GitFileListRequest(
                    "orders", SNAPSHOT_ID, REVISION, "src", 0, 20));

            assertThat(unicode.items()).extracting(SemanticQueryContract.GitFileItem::path).containsExactly("😀/file.java", "😀/nested");
            assertThat(raw.items()).extracting(SemanticQueryContract.GitFileItem::path)
                    .containsExactly("src/demo.java");
        }
    }

    @Test
    void does_not_decode_invalid_utf8_beyond_the_four_mebibyte_search_budget() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_search_budget");
            seedBudgetSnapshot(template, "orders", true);
            GitEvidenceReadService service = service(template, List.of("orders"));
            String seek = cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "0", "0", "1");

            SemanticQueryContract.GitTextSearchResult result = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(), Optional.of(seek), 1));

            assertThat(result.items()).isEmpty();
            assertThat(result.scanComplete()).isFalse();
            assertThat(result.nextCursor()).isPresent();
        }
    }

    @Test
    void preserves_a_valid_later_match_for_the_next_four_mebibyte_search_page() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_search_budget_valid");
            seedBudgetSnapshot(template, "orders", false);
            GitEvidenceReadService service = service(template, List.of("orders"));
            String seek = cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "0", "0", "1");

            SemanticQueryContract.GitTextSearchResult result = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(), Optional.of(seek), 1));

            assertThat(result.items()).isEmpty();
            assertThat(result.scanComplete()).isFalse();
            assertThat(result.nextCursor()).isPresent();
            SemanticQueryContract.GitTextSearchResult resumed = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(), result.nextCursor(), 1));
            assertThat(resumed.items()).singleElement().extracting(SemanticQueryContract.GitTextMatch::path).isEqualTo("src/budget.java");
        }
    }

    @Test
    void continues_after_empty_text_and_handles_same_chunk_snippets_and_end_matches() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_reader_continuations");
            seedReaderContinuationSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitTextSearchResult afterEmpty = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "needle", Optional.empty(),
                    Optional.of(cursor("git-search", "orders", SNAPSHOT_ID, REVISION, "needle", "", "0", "0", "0")), 1));
            SemanticQueryContract.GitTextSearchResult longSnippet = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "longneedle", Optional.empty(), Optional.empty(), 1));
            SemanticQueryContract.GitTextSearchResult atEnd = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "Z", Optional.empty(), Optional.empty(), 1));

            assertThat(afterEmpty.items()).singleElement().extracting(SemanticQueryContract.GitTextMatch::path).isEqualTo("src/searchable.java");
            assertThat(longSnippet.items()).singleElement().satisfies(match -> {
                assertThat(match.snippet()).contains("longneedle");
                assertThat(match.snippet().codePointCount(0, match.snippet().length())).isEqualTo(160);
                assertThat(match.snippetTruncated()).isTrue();
            });
            assertThat(longSnippet.scanComplete()).isFalse();
            assertThat(longSnippet.nextCursor()).isPresent();
            assertThat(atEnd.items()).singleElement().extracting(SemanticQueryContract.GitTextMatch::path).isEqualTo("src/edge.java");
            assertThat(atEnd.scanComplete()).isTrue();
            assertThat(atEnd.nextCursor()).isEmpty();
        }
    }

    @Test
    void replays_the_final_match_cursor_across_an_equivalent_four_mebibyte_multi_file_boundary() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_search_replay");
            seedReplaySearchSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitTextSearchRequest request = new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "stable-token", Optional.empty(), Optional.empty(), 1);

            SemanticQueryContract.GitTextSearchResult result = service.searchText(request);
            List<SemanticQueryContract.GitTextMatch> matches = new java.util.ArrayList<>();
            while (result.nextCursor().isPresent()) {
                matches.addAll(result.items());
                result = service.searchText(new SemanticQueryContract.GitTextSearchRequest("orders", SNAPSHOT_ID, REVISION,
                        "stable-token", Optional.empty(), result.nextCursor(), 1));
            }

            matches.addAll(result.items());
            assertThat(matches).hasSize(30);
            assertThat(result.scanComplete()).isTrue();
        }
    }

    @Test
    void canonicalizes_a_non_eof_chunk_boundary_for_the_next_search_page() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_snapshot_chunk_boundary");
            seedSnapshot(template, "orders");
            GitEvidenceReadService service = service(template, List.of("orders"));

            SemanticQueryContract.GitTextSearchResult first = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "d", Optional.of("src"), Optional.empty(), 1));
            SemanticQueryContract.GitTextSearchResult second = service.searchText(new SemanticQueryContract.GitTextSearchRequest(
                    "orders", SNAPSHOT_ID, REVISION, "d", Optional.of("src"), first.nextCursor(), 1));

            assertThat(first.items()).singleElement().satisfies(match -> assertThat(match.column()).isEqualTo(4));
            assertThat(first.scanComplete()).isFalse();
            assertThat(first.nextCursor()).isPresent();
            assertThat(second.items()).singleElement().satisfies(match -> assertThat(match.column()).isEqualTo(11));
        }
    }

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

            assertDenied(service(template, new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of(),
                    List.of(new ReadPolicyProperties.PackageRule("orders", "example.private")), List.of(), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of(), List.of(),
                    List.of(new ReadPolicyProperties.ClassRule("orders", "example.private", "PrivateType")), List.of())), request);
            assertDenied(service(template, new ReadPolicyProperties(List.of(), List.of(), List.of(),
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
                    .append("ordinal", 1L).append("changeId", "change-1").append("kind", "MODIFY").append("oldPath", "Other.java")
                    .append("newPath", "Other.java").append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "5".repeat(40))
                    .append("newBlobId", "6".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L)
                    .append("oldRawPath", rawPath("Other.java")).append("newRawPath", rawPath("Other.java"))
                    .append("oldPathKey", rawPathKey("Other.java")).append("newPathKey", rawPathKey("Other.java")));
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
    void treats_a_removed_previously_published_change_as_unavailable_evidence() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "git_comparison_removed_change");
            seedRepository(template, "orders");
            seedComparison(template, "orders", "READY");
            template.getCollection("git_comparison_changes").updateOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", "change-0"), new Document("$set", new Document("changeId", "c-0")));
            GitEvidenceReadService service = service(template, List.of("orders"));
            SemanticQueryContract.GitComparisonCollection comparison = service.comparisons(new SemanticQueryContract.GitComparisonRequest("orders",
                    COMPARISON_ID, REVISION, "2".repeat(40), 0, 1));
            String publishedChangeId = comparison.items().getFirst().changeId();
            template.getCollection("git_comparison_changes").deleteOne(new Document("comparisonId", COMPARISON_ID)
                    .append("changeId", publishedChangeId));

            assertThatThrownBy(() -> service.fileDiff(new SemanticQueryContract.GitFileDiffRequest("orders", COMPARISON_ID, REVISION,
                    "2".repeat(40), publishedChangeId, Optional.empty()))).isInstanceOf(IndexContractMismatchException.class);
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
        return new GitEvidenceReadService(template, new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()),
                new GitEvidenceProperties(allowedRepositories)), Duration.ofSeconds(2));
    }

    private static GitEvidenceReadService service(MongoTemplate template, ReadPolicyProperties properties) {
        return new GitEvidenceReadService(template, new ConfiguredReadPolicy(properties, new GitEvidenceProperties(List.of("orders"))), Duration.ofSeconds(2));
    }

    private static void assertDenied(GitEvidenceReadService service, SemanticQueryContract.GitBranchRequest request) {
        assertThatThrownBy(() -> service.branches(request)).isInstanceOf(RepositoryNotFoundException.class);
    }

    private static void insertSnapshotFile(MongoTemplate template, String repositoryId, long ordinal, byte[] rawPath, String status) {
        String path = displayPath(rawPath);
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", ordinal).append("path", path).append("rawPath", rawPath).append("pathKey", java.util.HexFormat.of().formatHex(rawPath))
                .append("mode", "100644").append("blobId", Long.toHexString(ordinal + 2L).repeat(40).substring(0, 40)).append("checksum", "c".repeat(64))
                .append("byteLength", 0L).append("contentStatus", status).append("chunkCount", 0L));
    }

    private static String displayPath(byte[] rawPath) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(rawPath)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            return "\u0000raw-path-hex:" + java.util.HexFormat.of().formatHex(rawPath);
        }
    }

    private static void seedBudgetSnapshot(MongoTemplate template, String repositoryId, boolean malformedFinalChunk) {
        String path = "src/budget.java";
        byte[] ordinary = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] finalChunk = new byte[64 * 1024];
        if (malformedFinalChunk) {
            java.util.Arrays.fill(finalChunk, (byte) 0xc3);
        } else {
            byte[] match = "needle".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(match, 0, finalChunk, 0, match.length);
            java.util.Arrays.fill(finalChunk, match.length, finalChunk.length, (byte) 'x');
        }
        long byteLength = (long) ordinary.length * 64L + finalChunk.length;
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-budget").append("revision", REVISION)
                .append("total", 1L).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", byteLength)
                .append("snapshotTextBytesLimit", byteLength).append("contentCoverage", new Document("entryCount", 1L).append("textEntries", 1L)
                        .append("textBytes", byteLength)));
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", 0L).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", "1".repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", byteLength)
                .append("contentStatus", "TEXT").append("chunkCount", 65L));
        List<Document> chunks = new java.util.ArrayList<>();
        for (int ordinal = 0; ordinal < 65; ordinal++) {
            byte[] bytes = ordinal == 64 ? finalChunk : ordinary;
            chunks.add(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", (long) ordinal)
                    .append("byteOffset", (long) ordinal * ordinary.length).append("line", 1L).append("column", (long) ordinal * ordinary.length + 1L)
                    .append("bytes", bytes));
        }
        template.getCollection("git_snapshot_chunks").insertMany(chunks);
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-budget");
    }

    private static void seedBudgetDuplicateSnapshot(MongoTemplate template, String repositoryId) {
        seedSnapshot(template, repositoryId);
        template.getCollection("git_snapshot_files").deleteMany(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID));
        template.getCollection("git_snapshot_chunks").deleteMany(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID));
        byte[] ordinary = "b".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] terminal = java.util.Arrays.copyOf(ordinary, ordinary.length);
        terminal[terminal.length - 1] = (byte) 'a';
        for (int file = 0; file < 2; file++) {
            String path = file == 0 ? "A.java" : "B.java";
            template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                    .append("ordinal", (long) file).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                    .append("blobId", Integer.toString(file + 1).repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", 2L * 1024L * 1024L)
                    .append("contentStatus", "TEXT").append("chunkCount", 32L));
            List<Document> chunks = new java.util.ArrayList<>();
            for (int ordinal = 0; ordinal < 32; ordinal++) {
                byte[] bytes = file == 1 && ordinal == 30 ? terminal : ordinary;
                chunks.add(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", (long) ordinal)
                        .append("byteOffset", (long) ordinal * ordinary.length).append("line", 1L).append("column", (long) ordinal * ordinary.length + 1L)
                        .append("bytes", bytes));
            }
            template.getCollection("git_snapshot_chunks").insertMany(chunks);
        }
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID), new Document("$set",
                new Document("total", 2L).append("fileTextBytesLimit", 2L * 1024L * 1024L).append("snapshotTextBytesLimit", 4L * 1024L * 1024L)
                        .append("contentCoverage.entryCount", 2L).append("contentCoverage.textEntries", 2L).append("contentCoverage.textBytes", 4L * 1024L * 1024L)));
        sealAfterFixtureMutation(template, repositoryId);
    }

    private static void seedSnippetBudgetSnapshot(MongoTemplate template, String repositoryId) {
        String path = "src/budget-snippet.java";
        byte[] ordinary = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] matched = ("needle" + "x".repeat(64 * 1024 - 6)).getBytes(StandardCharsets.UTF_8);
        long byteLength = (long) ordinary.length * 63L + matched.length;
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-snippet").append("revision", REVISION)
                .append("total", 1L).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", byteLength)
                .append("snapshotTextBytesLimit", byteLength).append("contentCoverage", new Document("entryCount", 1L).append("textEntries", 1L)
                        .append("textBytes", byteLength)));
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", 0L).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", "1".repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", byteLength)
                .append("contentStatus", "TEXT").append("chunkCount", 64L));
        List<Document> chunks = new java.util.ArrayList<>();
        for (int ordinal = 0; ordinal < 64; ordinal++) {
            byte[] bytes = ordinal == 63 ? matched : ordinary;
            chunks.add(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", (long) ordinal)
                    .append("byteOffset", (long) ordinal * ordinary.length).append("line", 1L).append("column", (long) ordinal * ordinary.length + 1L)
                    .append("bytes", bytes));
        }
        template.getCollection("git_snapshot_chunks").insertMany(chunks);
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-snippet");
    }

    private static void seedCrossChunkSnippetBudgetSnapshot(MongoTemplate template, String repositoryId) {
        String path = "src/cross-chunk-snippet.java";
        byte[] ordinary = "x".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);
        byte[] start = ("x".repeat(64 * 1024 - 1) + "a").getBytes(StandardCharsets.UTF_8);
        byte[] end = ("b" + "x".repeat(64 * 1024 - 1)).getBytes(StandardCharsets.UTF_8);
        long byteLength = (long) ordinary.length * 62L + start.length + end.length;
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-cross-chunk").append("revision", REVISION)
                .append("total", 1L).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", byteLength)
                .append("snapshotTextBytesLimit", byteLength).append("contentCoverage", new Document("entryCount", 1L).append("textEntries", 1L)
                        .append("textBytes", byteLength)));
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", 0L).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", "1".repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", byteLength)
                .append("contentStatus", "TEXT").append("chunkCount", 64L));
        List<Document> chunks = new java.util.ArrayList<>();
        for (int ordinal = 0; ordinal < 64; ordinal++) {
            byte[] bytes = ordinal == 62 ? start : ordinal == 63 ? end : ordinary;
            chunks.add(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", (long) ordinal)
                    .append("byteOffset", (long) ordinal * ordinary.length).append("line", 1L).append("column", (long) ordinal * ordinary.length + 1L)
                    .append("bytes", bytes));
        }
        template.getCollection("git_snapshot_chunks").insertMany(chunks);
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-cross-chunk");
    }

    private static void seedReaderContinuationSnapshot(MongoTemplate template, String repositoryId) {
        byte[] searchable = "needle".getBytes(StandardCharsets.UTF_8);
        byte[] longLine = ("longneedle" + "x".repeat(200)).getBytes(StandardCharsets.UTF_8);
        byte[] edge = "Z".getBytes(StandardCharsets.UTF_8);
        long textBytes = (long) searchable.length + longLine.length + edge.length;
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-reader").append("revision", REVISION)
                .append("total", 4L).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", 1_048_576L)
                .append("snapshotTextBytesLimit", 1_048_576L).append("contentCoverage", new Document("entryCount", 4L).append("textEntries", 4L)
                        .append("textBytes", textBytes)));
        insertTextSnapshotFile(template, repositoryId, 0L, "src/empty.java", new byte[0]);
        insertTextSnapshotFile(template, repositoryId, 1L, "src/searchable.java", searchable);
        insertTextSnapshotFile(template, repositoryId, 2L, "src/long.java", longLine);
        insertTextSnapshotFile(template, repositoryId, 3L, "src/edge.java", edge);
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-reader");
    }

    private static void insertTextSnapshotFile(MongoTemplate template, String repositoryId, long ordinal, String path, byte[] bytes) {
        long chunkCount = bytes.length == 0 ? 0L : 1L;
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", ordinal).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", Long.toHexString(ordinal + 1L).repeat(40).substring(0, 40)).append("checksum", "b".repeat(64))
                .append("byteLength", (long) bytes.length).append("contentStatus", "TEXT").append("chunkCount", chunkCount));
        if (bytes.length > 0) {
            template.getCollection("git_snapshot_chunks").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                    .append("pathKey", pathKey(path)).append("ordinal", 0L).append("byteOffset", 0L).append("line", 1L).append("column", 1L)
                    .append("bytes", bytes));
        }
    }

    private static void seedSnapshot(MongoTemplate template, String repositoryId) {
        String path = "src/demo.java";
        byte[] text = "a😀\r\nneedle needle\n最後".getBytes(StandardCharsets.UTF_8);
        byte[] first = "a😀\r\nneed".getBytes(StandardCharsets.UTF_8);
        byte[] second = java.util.Arrays.copyOfRange(text, first.length, text.length);
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-snapshot").append("revision", REVISION)
                .append("total", 1L).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", 1_048_576L)
                .append("snapshotTextBytesLimit", 1_048_576L).append("contentCoverage", new Document("entryCount", 1L).append("textEntries", 1L)
                        .append("textBytes", (long) text.length)));
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", 0L).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", "1".repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", (long) text.length)
                .append("contentStatus", "TEXT").append("chunkCount", 2L));
        template.getCollection("git_snapshot_chunks").insertMany(List.of(
                new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", 0L)
                        .append("byteOffset", 0L).append("line", 1L).append("column", 1L).append("bytes", first),
                new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", 1L)
                        .append("byteOffset", (long) first.length).append("line", 2L).append("column", 5L).append("bytes", second)));
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-snapshot");
    }

    private static void seedReplaySearchSnapshot(MongoTemplate template, String repositoryId) {
        String path = "src/Service.java";
        StringBuilder content = new StringBuilder("class Service {\n  static int version() { return 2; }\n");
        for (int index = 0; index < 30; index++) {
            content.append("  // stable-token current ").append(index).append("\n");
        }
        content.append("}\n");
        byte[] bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        byte[] noHit = ("// no matching text ".repeat(3_276)).getBytes(StandardCharsets.UTF_8);
        long noHitFileCount = 72L;
        long total = 7L + noHitFileCount;
        long textBytes = bytes.length + noHitFileCount * noHit.length;
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID)
                .append("kind", "SNAPSHOT").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", "job-replay").append("revision", REVISION)
                .append("total", total).append("contentDigest", "a".repeat(64)).append("fileTextBytesLimit", 1_048_576L)
                .append("snapshotTextBytesLimit", textBytes).append("contentCoverage", new Document("entryCount", total).append("textEntries", total)
                        .append("textBytes", textBytes)));
        for (int ordinal = 0; ordinal < 6; ordinal++) {
            insertSnapshotFile(template, repositoryId, ordinal, rawPath("src/empty" + ordinal + ".java"), "TEXT");
        }
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("ordinal", 6L).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path)).append("mode", "100644")
                .append("blobId", "1".repeat(40)).append("checksum", "b".repeat(64)).append("byteLength", (long) bytes.length)
                .append("contentStatus", "TEXT").append("chunkCount", 1L));
        template.getCollection("git_snapshot_chunks").insertOne(new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID)
                .append("pathKey", pathKey(path)).append("ordinal", 0L).append("byteOffset", 0L).append("line", 1L).append("column", 1L)
                .append("bytes", bytes));
        for (int index = 0; index < noHitFileCount; index++) {
            insertTextSnapshotFile(template, repositoryId, 7L + index, "src/nohit/NoHit" + index + ".java", noHit);
        }
        seedReadyComparisonOwner(template, repositoryId, SNAPSHOT_ID, REVISION, "job-replay");
    }

    private static void markSnapshotComparisonReviewOwned(MongoTemplate template, String repositoryId, String reviewId, String reviewState) {
        template.getCollection("git_evidence_manifests").updateMany(new Document("repoId", repositoryId), new Document("$set",
                new Document("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "REVIEW").append("reviewId", reviewId)));
        Document comparison = template.getCollection("git_evidence_manifests").find(new Document("repoId", repositoryId)
                .append("kind", "COMPARISON")).first();
        String beforeGenerationId = "source-" + comparison.getString("previousSnapshotId");
        String afterGenerationId = "source-" + comparison.getString("currentSnapshotId");
        SealedGeneration before = sealed("2".repeat(40), beforeGenerationId, digest(beforeGenerationId));
        SealedGeneration after = sealed(REVISION, afterGenerationId, digest(afterGenerationId));
        Document beforeEndpoint = new Document("generation", template.getConverter().convertToMongoType(before))
                .append("snapshotId", comparison.getString("previousSnapshotId"));
        Document afterEndpoint = new Document("generation", template.getConverter().convertToMongoType(after))
                .append("snapshotId", comparison.getString("currentSnapshotId"));
        Date now = new Date();
        template.getCollection("review_manifests").insertOne(new Document("repoId", repositoryId).append("reviewId", reviewId)
                .append("ownerJobId", "job-snapshot").append("reviewContractVersion", IndexSchemaContract.REVIEW_MANIFEST_VERSION)
                .append("state", reviewState)
                .append("selection", new Document("kind", "RANGE").append("beforeRevision", "2".repeat(40)).append("afterRevision", REVISION))
                .append("resolvedEndpoints", new Document("beforeRevision", "2".repeat(40)).append("afterRevision", REVISION)
                        .append("baselineRule", "DIRECT_RANGE"))
                .append("before", beforeEndpoint).append("after", afterEndpoint)
                .append("comparisonId", comparison.getString("evidenceId")).append("createdAt", now).append("publishedAt", now));
    }

    private static SealedGeneration sealed(String revision, String generationId, String digest) {
        SelectedGeneration selected = new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision(revision),
                new GenerationId(generationId), new ManifestDigest(digest));
        AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "e".repeat(64), "e".repeat(64),
                "e".repeat(64), "e".repeat(64), List.of());
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(),
                "SUCCESS", List.of(), new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        return new SealedGeneration(selected, fingerprint, evidence);
    }

    private static Document completeReviewComparison(MongoTemplate template, String repositoryId) {
        Document comparison = template.getCollection("git_evidence_manifests").find(new Document("repoId", repositoryId)
                .append("kind", "COMPARISON")).first();
        template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", comparison.getString("evidenceId")),
                new Document("$set", new Document("ancestry", "PREVIOUS_ANCESTOR").append("total", 1L)));
        template.getCollection("git_comparison_changes").insertOne(new Document("repoId", repositoryId)
                .append("comparisonId", comparison.getString("evidenceId")).append("ordinal", 0L).append("changeId", "change-0")
                .append("kind", "MODIFY").append("oldPath", "src/demo.java").append("newPath", "src/demo.java")
                .append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "2".repeat(40))
                .append("newBlobId", "1".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L)
                .append("oldRawPath", rawPath("src/demo.java")).append("newRawPath", rawPath("src/demo.java"))
                .append("oldPathKey", rawPathKey("src/demo.java")).append("newPathKey", rawPathKey("src/demo.java")));
        template.getCollection("git_comparison_patches").insertMany(List.of(
                new Document("repoId", repositoryId).append("comparisonId", comparison.getString("evidenceId")).append("changeId", "change-0")
                        .append("ordinal", 0L).append("patch", "first\n"),
                new Document("repoId", repositoryId).append("comparisonId", comparison.getString("evidenceId")).append("changeId", "change-0")
                        .append("ordinal", 1L).append("patch", "second\n")));
        return comparison;
    }

    private static void seedReadyComparisonOwner(MongoTemplate template, String repositoryId, String snapshotId, String revision, String ownerJobId) {
        String siblingSnapshotId = siblingSnapshotId(repositoryId, ownerJobId);
        Document afterSnapshot = template.getCollection("git_evidence_manifests")
                .find(new Document("repoId", repositoryId).append("evidenceId", snapshotId)).first();
        Document beforeSnapshot = new Document(afterSnapshot);
        beforeSnapshot.remove("_id");
        beforeSnapshot.put("evidenceId", siblingSnapshotId);
        beforeSnapshot.put("revision", "2".repeat(40));
        beforeSnapshot.put("contentDigest", digest(siblingSnapshotId));
        template.getCollection("git_evidence_manifests").insertOne(beforeSnapshot);
        for (Document source : template.getCollection("git_snapshot_files")
                .find(new Document("repoId", repositoryId).append("snapshotId", snapshotId))) {
            Document copy = new Document(source);
            copy.remove("_id");
            copy.put("snapshotId", siblingSnapshotId);
            if ("src/demo.java".equals(copy.getString("path"))) {
                copy.put("blobId", "2".repeat(40));
            }
            template.getCollection("git_snapshot_files").insertOne(copy);
        }
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", UUID.randomUUID().toString()).append("kind", "COMPARISON")
                .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION)
                .append("scope", "STANDALONE").append("ownerJobId", ownerJobId).append("previous", "2".repeat(40))
                .append("current", revision).append("previousSnapshotId", siblingSnapshotId)
                .append("currentSnapshotId", snapshotId).append("ancestry", "PREVIOUS_ANCESTOR").append("total", 0L));
        sealFixtureSource(template, repositoryId, snapshotId, revision, "source-" + snapshotId);
        sealFixtureSource(template, repositoryId, siblingSnapshotId, "2".repeat(40), "source-" + siblingSnapshotId);
    }

    private static void sealFixtureSource(MongoTemplate template, String repositoryId, String snapshotId,
            String revision, String generationId) {
        Set<String> paths = new java.util.HashSet<>();
        for (Document file : template.getCollection("git_snapshot_files").find(new Document("repoId", repositoryId)
                .append("snapshotId", snapshotId))) {
            String path = file.getString("path");
            if (path.endsWith(".java") || path.endsWith(".xml")) {
                paths.add(path);
            }
        }
        SourceEvidencePolicy policy = new SourceEvidencePolicy(SourceEvidencePolicy.VERSION,
                List.of("."), paths, Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        Document snapshot = template.getCollection("git_evidence_manifests")
                .find(new Document("repoId", repositoryId).append("evidenceId", snapshotId)).first();
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", repositoryId).append("evidenceId", snapshotId),
                new Document("$set", new Document("sourceGenerationId", generationId).append("policyFingerprint", policy.fingerprint())
                        .append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))));
        for (String path : paths) {
            template.getCollection("git_snapshot_files").updateOne(new Document("repoId", repositoryId)
                    .append("snapshotId", snapshotId).append("path", path),
                    new Document("$set", new Document("contentKind", "CODE")
                            .append("policyFingerprint", policy.fingerprint())));
        }
        template.getCollection("generation_manifests").replaceOne(new Document("repoId", repositoryId).append("generationId", generationId),
                new Document("repoId", repositoryId)
                .append("sourceRevision", revision).append("generationId", generationId)
                .append("identityDigest", digest(generationId)).append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("writeState", "SEALED_VALID")
                .append("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(policy)))
                .append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))
                .append("sourceSnapshot", template.getConverter().convertToMongoType(new SourceSnapshotMembership(
                        new GitSnapshotId(snapshotId), new RepositoryRevision(revision), policy.fingerprint(),
                        snapshot.getString("contentDigest"))))
                .append("coverage", template.getConverter().convertToMongoType(new SourceCoverage(paths.size(), 0, 0, 0)))
                .append("structure", template.getConverter().convertToMongoType(new SourceStructure(policy.includedRoots(),
                        Map.of(), Map.of())))
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList()),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }

    private static void sealAfterFixtureMutation(MongoTemplate template, String repositoryId) {
        sealFixtureSource(template, repositoryId, SNAPSHOT_ID, REVISION, "source-" + SNAPSHOT_ID);
    }

    private static String siblingSnapshotId(String repositoryId, String ownerJobId) {
        return java.util.UUID.nameUUIDFromBytes((repositoryId + ":" + ownerJobId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void createManifestOwnerLookupIndex(MongoTemplate template) {
        IndexSchemaContract.CollectionSpec manifests = IndexSchemaContract.collections().stream()
                .filter(collection -> collection.name().equals("git_evidence_manifests")).findFirst().orElseThrow();
        IndexSchemaContract.IndexSpec ownerLookup = manifests.indexes().stream()
                .filter(index -> index.name().equals("git_evidence_manifest_owner_lookup")).findFirst().orElseThrow();
        template.getCollection(manifests.name()).createIndex(new Document(ownerLookup.keys()), new IndexOptions().name(ownerLookup.name()).unique(ownerLookup.unique()));
    }

    private static void seedUnrelatedComparisonOwners(MongoTemplate template, String repositoryId, int count) {
        for (int index = 0; index < count; index++) {
            template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                    .append("evidenceId", java.util.UUID.nameUUIDFromBytes(("unrelated-" + index).getBytes(StandardCharsets.UTF_8)).toString())
                    .append("kind", "COMPARISON").append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE")
                    .append("ownerJobId", "unrelated-job-" + index).append("previous", "3".repeat(40)).append("current", "4".repeat(40))
                    .append("previousSnapshotId", java.util.UUID.nameUUIDFromBytes(("previous-" + index).getBytes(StandardCharsets.UTF_8)).toString())
                    .append("currentSnapshotId", java.util.UUID.nameUUIDFromBytes(("current-" + index).getBytes(StandardCharsets.UTF_8)).toString()));
        }
    }

    private static Document ownerComparisonFind(String repositoryId, String ownerJobId) {
        Document previousEndpoint = new Document("previousSnapshotId", SNAPSHOT_ID).append("previous", REVISION);
        Document currentEndpoint = new Document("currentSnapshotId", SNAPSHOT_ID).append("current", REVISION);
        Document filter = new Document("$and", List.of(new Document("repoId", repositoryId), new Document("kind", "COMPARISON"),
                new Document("ownerJobId", ownerJobId), new Document("$or", List.of(previousEndpoint, currentEndpoint))));
        return new Document("find", "git_evidence_manifests").append("filter", filter).append("limit", 2L).append("maxTimeMS", 2_000L);
    }

    private static long number(Document document, String field) {
        return document.get(field, Number.class).longValue();
    }

    private static boolean containsDocumentValue(Object value, String field, String expected) {
        if (value instanceof Document document) {
            if (expected.equals(document.getString(field))) {
                return true;
            }
            for (Object nested : document.values()) {
                if (containsDocumentValue(nested, field, expected)) {
                    return true;
                }
            }
        }
        if (value instanceof List<?> values) {
            for (Object nested : values) {
                if (containsDocumentValue(nested, field, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void seedLongLineSnapshot(MongoTemplate template, String repositoryId) {
        String path = "src/long-line.java";
        String content = "😀".repeat(20_000);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] first = java.util.Arrays.copyOfRange(bytes, 0, 64 * 1024);
        byte[] second = java.util.Arrays.copyOfRange(bytes, first.length, bytes.length);
        insertTextSnapshotFile(template, repositoryId, 1L, path, bytes);
        template.getCollection("git_snapshot_chunks").deleteOne(new Document("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", 0L));
        template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 1L),
                new Document("$set", new Document("chunkCount", 2L)));
        template.getCollection("git_snapshot_chunks").insertMany(List.of(
                new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", 0L)
                        .append("byteOffset", 0L).append("line", 1L).append("column", 1L).append("bytes", first),
                new Document("repoId", repositoryId).append("snapshotId", SNAPSHOT_ID).append("pathKey", pathKey(path)).append("ordinal", 1L)
                        .append("byteOffset", (long) first.length).append("line", 1L).append("column", 16_385L).append("bytes", second)));
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID),
                new Document("$set", new Document("total", 2L).append("contentCoverage.entryCount", 2L).append("contentCoverage.textEntries", 2L)
                        .append("contentCoverage.textBytes", (long) bytes.length + 27L)));
        sealAfterFixtureMutation(template, repositoryId);
    }

    private static void copySnapshot(MongoTemplate template, String repositoryId, String sourceSnapshotId, String targetSnapshotId, String revision) {
        String ownerJobId = "job-" + targetSnapshotId;
        copySnapshotRows(template, "git_evidence_manifests", new Document("repoId", repositoryId).append("evidenceId", sourceSnapshotId), row -> row
                .append("evidenceId", targetSnapshotId).append("revision", revision).append("ownerJobId", ownerJobId));
        copySnapshotRows(template, "git_snapshot_files", new Document("repoId", repositoryId).append("snapshotId", sourceSnapshotId), row -> row
                .append("snapshotId", targetSnapshotId));
        copySnapshotRows(template, "git_snapshot_chunks", new Document("repoId", repositoryId).append("snapshotId", sourceSnapshotId), row -> row
                .append("snapshotId", targetSnapshotId));
        seedReadyComparisonOwner(template, repositoryId, targetSnapshotId, revision, ownerJobId);
    }

    private static void copySnapshotRows(MongoTemplate template, String collection, Document filter,
                                         java.util.function.UnaryOperator<Document> change) {
        for (Document source : template.getCollection(collection).find(filter)) {
            Document copy = new Document(source);
            copy.remove("_id");
            template.getCollection(collection).insertOne(change.apply(copy));
        }
    }

    private static String cursor(String... values) {
        List<String> parts = new java.util.ArrayList<>(List.of(values));
        if ("git-file".equals(parts.getFirst())) {
            parts.set(4, digest(parts.get(4)));
            parts.add(5, "1");
        }
        if ("git-search".equals(parts.getFirst())) {
            parts.set(4, digest(parts.get(4)));
            parts.set(5, digest(parts.get(5)));
            parts.add(6, "1");
        }
        return parts.stream().map(value -> Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .collect(java.util.stream.Collectors.joining("."));
    }

    private static String digest(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < value.length(); index++) {
                char codeUnit = value.charAt(index);
                digest.update((byte) (codeUnit >>> 8));
                digest.update((byte) codeUnit);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String pathKey(String path) {
        return java.util.HexFormat.of().formatHex(path.getBytes(StandardCharsets.UTF_8));
    }

    private static void seedRepository(MongoTemplate template, String repositoryId) {
        template.getCollection("repositories").insertOne(new Document("repoId", repositoryId));
    }

    private static void seedCatalog(MongoTemplate template, String repositoryId, String catalogId, String state) {
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", catalogId).append("kind", "CATALOG").append("state", state).append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("observedAt", new Date()).append("total", 2L));
        template.getCollection("git_branches").insertMany(List.of(
                new Document("repoId", repositoryId).append("catalogId", catalogId).append("ordinal", 0L)
                        .append("branch", "main").append("head", REVISION),
                new Document("repoId", repositoryId).append("catalogId", catalogId).append("ordinal", 1L)
                        .append("branch", "release").append("head", "2".repeat(40))));
    }

    private static void seedHistory(MongoTemplate template, String repositoryId, String state) {
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", HISTORY_ID).append("kind", "HISTORY").append("state", state).append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("revision", REVISION).append("preparedAt", new Date()).append("total", 1L));
        template.getCollection("git_commits").insertOne(new Document("repoId", repositoryId).append("historyId", HISTORY_ID)
                .append("ordinal", 0L).append("revision", REVISION).append("parents", List.of("2".repeat(40)))
                .append("subject", "prepared commit").append("committedAt", new Date()));
    }

    private static void seedComparison(MongoTemplate template, String repositoryId, String state) {
        String current = "2".repeat(40);
        String ownerJobId = "comparison-job";
        template.getCollection("git_evidence_manifests").insertMany(List.of(
                new Document("repoId", repositoryId).append("evidenceId", "dddddddd-dddd-dddd-dddd-dddddddddddd").append("kind", "SNAPSHOT")
                        .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", ownerJobId).append("revision", REVISION),
                new Document("repoId", repositoryId).append("evidenceId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee").append("kind", "SNAPSHOT")
                        .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", ownerJobId).append("revision", current),
                new Document("repoId", repositoryId).append("evidenceId", COMPARISON_ID).append("kind", "COMPARISON").append("state", state).append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE").append("ownerJobId", ownerJobId).append("previous", REVISION).append("current", current)
                        .append("previousSnapshotId", "dddddddd-dddd-dddd-dddd-dddddddddddd")
                        .append("currentSnapshotId", "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee").append("ancestry", "PREVIOUS_ANCESTOR").append("total", 1L)));
        template.getCollection("git_comparison_changes").insertOne(new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID)
                .append("ordinal", 0L).append("changeId", "change-0").append("kind", "MODIFY").append("oldPath", "Example.java")
                .append("newPath", "Example.java").append("oldMode", "100644").append("newMode", "100644").append("oldBlobId", "3".repeat(40))
                .append("newBlobId", "4".repeat(40)).append("diffStatus", "AVAILABLE").append("patchChunkCount", 2L)
                .append("oldRawPath", rawPath("Example.java")).append("newRawPath", rawPath("Example.java"))
                .append("oldPathKey", rawPathKey("Example.java")).append("newPathKey", rawPathKey("Example.java")));
        template.getCollection("git_comparison_patches").insertMany(List.of(
                new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID).append("changeId", "change-0").append("ordinal", 0L).append("patch", "first\n"),
                new Document("repoId", repositoryId).append("comparisonId", COMPARISON_ID).append("changeId", "change-0").append("ordinal", 1L).append("patch", "second\n")));
        seedComparisonSource(template, repositoryId, "dddddddd-dddd-dddd-dddd-dddddddddddd", REVISION, "3", "5");
        seedComparisonSource(template, repositoryId, "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", current, "4", "6");
    }

    private static void seedComparisonSource(MongoTemplate template, String repositoryId, String snapshotId,
            String revision, String exampleBlob, String otherBlob) {
        byte[] bytes = "// source evidence\n".getBytes(StandardCharsets.UTF_8);
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", repositoryId).append("evidenceId", snapshotId),
                new Document("$set", new Document("total", 2L).append("contentDigest", digest(snapshotId))
                        .append("fileTextBytesLimit", 1_048_576L).append("snapshotTextBytesLimit", 1_048_576L)
                        .append("contentCoverage", new Document("entryCount", 2L).append("textEntries", 2L)
                                .append("textBytes", (long) bytes.length * 2L))));
        for (int ordinal = 0; ordinal < 2; ordinal++) {
            String path = ordinal == 0 ? "Example.java" : "Other.java";
            String blob = ordinal == 0 ? exampleBlob : otherBlob;
            template.getCollection("git_snapshot_files").insertOne(new Document("repoId", repositoryId).append("snapshotId", snapshotId)
                    .append("ordinal", (long) ordinal).append("path", path).append("rawPath", rawPath(path)).append("pathKey", pathKey(path))
                    .append("mode", "100644").append("blobId", blob.repeat(40)).append("checksum", digest(path))
                    .append("byteLength", (long) bytes.length).append("contentStatus", "TEXT").append("chunkCount", 1L));
            template.getCollection("git_snapshot_chunks").insertOne(new Document("repoId", repositoryId).append("snapshotId", snapshotId)
                    .append("pathKey", pathKey(path)).append("ordinal", 0L).append("byteOffset", 0L).append("line", 1L).append("column", 1L)
                    .append("bytes", bytes));
        }
        sealFixtureSource(template, repositoryId, snapshotId, revision, "source-" + snapshotId);
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
