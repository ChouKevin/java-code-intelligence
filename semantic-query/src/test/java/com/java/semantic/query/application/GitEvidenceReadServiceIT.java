package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
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
import com.java.semantic.model.source.ProjectGuideProvenance;
import com.java.semantic.model.source.SourceContentKind;
import com.java.semantic.model.git.GitSnapshotId;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.query.application.ReadContextSelector.AdmittedContext;
import com.java.semantic.query.application.ReadContextSelector.AdmittedComparison;
import com.java.semantic.query.application.SemanticQueryContract.*;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class GitEvidenceReadServiceIT {
    private static final String REVISION = "1".repeat(40);
    private static final String SNAPSHOT_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";
    private static final String REVIEW_ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void file_pages_preserve_crlf_utf16_ranges_and_literal_overlap_search() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_source_utf16");
            seedSnapshot(template, "orders"); publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            AdmittedContext admitted = current(template);
            SourceResult first = reader.readSource(admitted, source(admitted.context(), "src/demo.java", 1, Optional.empty()));
            SourceResult second = reader.readSource(admitted, source(admitted.context(), "src/demo.java", 1, first.nextCursor()));
            SourceResult last = reader.readSource(admitted, source(admitted.context(), "src/demo.java", 1, second.nextCursor()));
            assertThat(first.content()).contains("a😀\r\n");
            assertThat(first.pageRange()).contains(new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(1, 0)));
            assertThat(second.content()).contains("needle needle\n");
            assertThat(last.content()).contains("最後");
            assertThat(last.rangeComplete()).isTrue();
            TextSearchResult search = reader.searchText(admitted, text(admitted.context(), "needle", 1, Optional.empty()));
            assertThat(search.items().getFirst().range()).isEqualTo(new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 6)));
            TextSearchResult next = reader.searchText(admitted, text(admitted.context(), "needle", 1, search.page().nextCursor()));
            assertThat(next.items().getFirst().range()).isEqualTo(new SyntaxRange(new SyntaxPosition(1, 7), new SyntaxPosition(1, 13)));
            assertThatThrownBy(() -> reader.searchText(admitted, text(admitted.context(), "Needle", 1, search.page().nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void unicode_long_line_continuation_is_byte_safe_and_empty_file_is_complete() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_long_source");
            seedSnapshot(template, "orders"); seedLongLineSnapshot(template, "orders"); publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            AdmittedContext admitted = current(template);
            SourceResult first = reader.readSource(admitted, source(admitted.context(), "src/long-line.java", 200, Optional.empty()));
            SourceResult last = reader.readSource(admitted, source(admitted.context(), "src/long-line.java", 200, first.nextCursor()));
            assertThat(first.content().orElseThrow().getBytes(StandardCharsets.UTF_8).length).isEqualTo(65_536);
            assertThat(first.endLineComplete()).isFalse();
            assertThat(last.startLineComplete()).isFalse();
            assertThat(first.content().orElseThrow() + last.content().orElseThrow()).isEqualTo("😀".repeat(20_000));
            assertThat(last.pageRange().orElseThrow().end()).isEqualTo(new SyntaxPosition(0, 40_000));
            assertThat(last.rangeComplete()).isTrue();
            insertTextSnapshotFile(template, "orders", 2L, "src/empty.java", new byte[0]);
            growSnapshot(template, 3, 80_027); sealAfterFixtureMutation(template, "orders");
            SourceResult empty = reader.readSource(current(template), source(admitted.context(), "src/empty.java", 200, Optional.empty()));
            assertThat(empty.content()).contains("");
            assertThat(empty.rangeComplete()).isTrue();
            assertThat(empty.nextCursor()).isEmpty();
        }
    }
    @Test
    void literal_prefix_across_four_mib_request_boundary_survives_empty_page_and_overlaps() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_budget_prefix");
            seedBudgetSnapshot(template, "orders", false); publishCurrent(template);
            byte[] start = ("x".repeat(65_535) + "a").getBytes(StandardCharsets.UTF_8);
            byte[] end = ("baba" + "x".repeat(65_532)).getBytes(StandardCharsets.UTF_8);
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 63L),
                    new Document("$set", new Document("bytes", start)));
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 64L),
                    new Document("$set", new Document("bytes", end)));
            GitEvidenceReadService reader = service(template);
            AdmittedContext admitted = current(template);
            TextSearchResult first = reader.searchText(admitted, text(admitted.context(), "aba", 20, Optional.empty()));
            assertThat(first.items()).isEmpty();
            assertThat(first.scanComplete()).isFalse();
            assertThat(first.page().hasMore()).isTrue();
            TextSearchResult last = reader.searchText(admitted, text(admitted.context(), "aba", 20, first.page().nextCursor()));
            assertThat(last.items()).extracting(value -> value.range().start().character()).containsExactly(4_194_303, 4_194_305);
            assertThat(last.scanComplete()).isTrue();
        }
    }
    @Test
    void changed_line_limit_cursor_and_corrupt_checkpoint_are_rejected() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_cursor_integrity");
            seedSnapshot(template, "orders"); publishCurrent(template);
            AdmittedContext admitted = current(template);
            GitEvidenceReadService reader = service(template);
            SourceResult first = reader.readSource(admitted, source(admitted.context(), "src/demo.java", 1, Optional.empty()));
            assertThatThrownBy(() -> reader.readSource(admitted, source(admitted.context(), "src/demo.java", 2, first.nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
            template.getCollection("git_snapshot_chunks").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("ordinal", 1L),
                    new Document("$set", new Document("column", 6L)));
            assertThatThrownBy(() -> reader.readSource(admitted, source(admitted.context(), "src/demo.java", 1, first.nextCursor())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }
    @Test
    void directory_navigation_literal_filters_and_cursors_do_not_repeat_descendants() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_direct_children");
            seedSnapshot(template, "orders");
            insertTextSnapshotFile(template, "orders", 1L, "src/a/A.java", "A".getBytes(StandardCharsets.UTF_8));
            insertTextSnapshotFile(template, "orders", 2L, "src/a/B.java", "B".getBytes(StandardCharsets.UTF_8));
            insertTextSnapshotFile(template, "orders", 3L, "src/b/B.java", "B".getBytes(StandardCharsets.UTF_8));
            growSnapshot(template, 4, 30); sealAfterFixtureMutation(template, "orders"); publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            AdmittedContext admitted = current(template);
            FileListRequest request = files(admitted.context(), "src", Optional.empty(), Optional.empty(), 1, Optional.empty());
            FileCollection first = reader.listFiles(admitted, request);
            assertThat(first.items()).extracting(FileItem::path).containsExactly("src/a");
            assertThat(first.items().getFirst().contentKind()).isEmpty();
            FileCollection next = reader.listFiles(admitted, files(admitted.context(), "src", Optional.empty(), Optional.empty(), 1, first.page().nextCursor()));
            assertThat(next.items()).extracting(FileItem::path).containsExactly("src/b");
            FileCollection filtered = reader.listFiles(admitted, files(admitted.context(), "src", Optional.of("B.java"), Optional.of("src/a/"), 20, Optional.empty()));
            assertThat(filtered.items()).extracting(FileItem::path).containsExactly("src/a");
            assertThatThrownBy(() -> reader.listFiles(admitted, files(admitted.context(), "src", Optional.of("B"), Optional.empty(), 1, first.page().nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void immutable_review_pages_survive_pointer_movement_and_reject_other_side_replay() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_review_pinned");
            seedSnapshot(template, "orders"); markSnapshotComparisonReviewOwned(template, "orders", REVIEW_ID, "READY"); publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            AdmittedComparison comparison = review(template);
            AdmittedContext after = comparison.after();
            SourceResult first = reader.readSource(after, source(after.context(), "src/demo.java", 1, Optional.empty()));
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("currentPointer.revision", "3".repeat(40))));
            SourceResult next = reader.readSource(review(template).after(), source(after.context(), "src/demo.java", 1, first.nextCursor()));
            assertThat(next.content()).contains("needle needle\n");
            AdmittedContext before = comparison.before().orElseThrow();
            assertThatThrownBy(() -> reader.readSource(before, source(before.context(), "src/demo.java", 1, first.nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void diff_patch_continuation_rechecks_both_rename_endpoints() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_diff_policy");
            seedSnapshot(template, "orders"); completeReviewComparison(template, "orders");
            markSnapshotComparisonReviewOwned(template, "orders", REVIEW_ID, "READY");
            GitEvidenceReadService reader = service(template);
            AdmittedComparison admitted = review(template);
            ComparisonResult changes = reader.compareRevisions(admitted, new ComparisonRequest(admitted.comparisonContext(), page(20, Optional.empty())));
            assertThat(changes.items().getFirst().before().orElseThrow().contentKind()).isEqualTo(SourceContentKind.CODE);
            FileDiffResult first = reader.getFileDiff(admitted, new FileDiffRequest(admitted.comparisonContext(), "change-0", Optional.empty()));
            FileDiffResult last = reader.getFileDiff(admitted, new FileDiffRequest(admitted.comparisonContext(), "change-0", first.nextCursor()));
            assertThat(first.patch()).contains("first\n"); assertThat(last.patch()).contains("second\n");
            assertThat(last.complete()).isTrue();
            template.getCollection("git_comparison_changes").updateOne(new Document("changeId", "change-0"),
                    new Document("$set", new Document("kind", "RENAME").append("newPath", "application.properties")
                            .append("newRawPath", rawPath("application.properties")).append("newPathKey", pathKey("application.properties"))));
            assertThatThrownBy(() -> reader.getFileDiff(admitted, new FileDiffRequest(admitted.comparisonContext(), "change-0", first.nextCursor())))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }
    @Test
    void complete_metadata_pair_is_required_and_branch_history_is_exact_and_cursor_pinned() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_metadata_complete");
            template.getCollection("repositories").insertOne(new Document("repoId", "orders"));
            GitEvidenceReadService reader = service(template);
            assertThatThrownBy(() -> reader.listGitBranches(new GitBranchRequest("orders", page(1, Optional.empty()))))
                    .isInstanceOf(MetadataNotPreparedException.class);
            metadataFixture(template, "old", "release", REVISION, "COMPLETE", Instant.parse("2026-09-28T00:00:00Z"));
            GitCommitCollection first = reader.listGitCommits(new GitCommitRequest("orders", "release", page(1, Optional.empty())));
            assertThat(first.items().getFirst().shortRevision()).isEqualTo("1111111");
            assertThatThrownBy(() -> reader.listGitCommits(new GitCommitRequest("orders", "main", page(1, Optional.empty()))))
                    .isInstanceOf(MetadataNotPreparedException.class);
            metadataFixture(template, "new", "release", "3".repeat(40), "RUNNING", Instant.parse("2026-09-29T00:00:00Z"));
            GitBranchCollection branches = reader.listGitBranches(new GitBranchRequest("orders", page(1, Optional.empty())));
            assertThat(branches.metadata().jobId()).isEqualTo("old");
            template.getCollection("index_jobs").updateOne(new Document("jobId", "new"), new Document("$set", new Document("phase", "COMPLETE").append("active", false)));
            GitCommitCollection next = reader.listGitCommits(new GitCommitRequest("orders", "release", page(1, first.page().nextCursor())));
            assertThat(next.metadata()).isEqualTo(first.metadata());
            assertThat(next.items().getFirst().revision()).isEqualTo("2".repeat(40));
            assertThat(reader.listGitCommits(new GitCommitRequest("orders", "release", page(1, Optional.empty()))).metadata().jobId()).isEqualTo("new");
            assertThatThrownBy(() -> reader.listGitCommits(new GitCommitRequest("orders", "main", page(1, first.page().nextCursor()))))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test
    void metadata_missing_ordinal_and_rebound_complete_owner_are_corruption_not_partial_success() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_metadata_corruption");
            template.getCollection("repositories").insertOne(new Document("repoId", "orders"));
            metadataFixture(template, "owner", "release", REVISION, "COMPLETE", Instant.parse("2026-09-28T00:00:00Z"));
            GitEvidenceReadService reader = service(template);
            GitCommitCollection first = reader.listGitCommits(new GitCommitRequest("orders", "release", page(1, Optional.empty())));
            template.getCollection("git_commits").deleteOne(new Document("ordinal", 1L));
            assertThatThrownBy(() -> reader.listGitCommits(new GitCommitRequest("orders", "release", page(1, first.page().nextCursor()))))
                    .isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_evidence_manifests").updateOne(new Document("kind", "CATALOG"), new Document("$set", new Document("ownerJobId", "other")));
            assertThatThrownBy(() -> reader.listGitBranches(new GitBranchRequest("orders", page(1, Optional.empty()))))
                    .isInstanceOf(IndexContractMismatchException.class);
        }
    }

    @Test
    void current_movement_and_rollback_reject_stale_identity_and_select_actual_snapshot_content() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_current_rollback");
            seedSnapshot(template, "orders"); publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            ReadContext old = current(template).context();
            String replacement = "99999999-9999-9999-9999-999999999999";
            byte[] body = "changed\n".getBytes(StandardCharsets.UTF_8);
            Document manifest = new Document(template.getCollection("git_evidence_manifests").find(new Document("evidenceId", SNAPSHOT_ID)).first());
            manifest.remove("_id");
            manifest.put("evidenceId", replacement); manifest.put("revision", "3".repeat(40));
            manifest.put("contentDigest", "3".repeat(64));
            manifest.put("contentCoverage", new Document("entryCount", 1L).append("textEntries", 1L).append("textBytes", (long) body.length));
            template.getCollection("git_evidence_manifests").insertOne(manifest);
            Document file = new Document(template.getCollection("git_snapshot_files").find(new Document("snapshotId", SNAPSHOT_ID)).first());
            file.remove("_id"); file.put("snapshotId", replacement); file.put("byteLength", (long) body.length); file.put("chunkCount", 1L);
            template.getCollection("git_snapshot_files").insertOne(file);
            template.getCollection("git_snapshot_chunks").insertOne(new Document("repoId", "orders").append("snapshotId", replacement)
                    .append("pathKey", pathKey("src/demo.java")).append("ordinal", 0L).append("byteOffset", 0L)
                    .append("line", 1L).append("column", 1L).append("bytes", body));
            sealFixtureSource(template, "orders", replacement, "3".repeat(40), "replacement");
            Document replacementPointer = new Document("revision", "3".repeat(40)).append("generationId", "replacement")
                    .append("manifestDigest", digest("replacement")).append("committedJobId", manifest.getString("ownerJobId"))
                    .append("publishedAt", Date.from(Instant.EPOCH));
            template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("currentPointer", replacementPointer)));
            assertThatThrownBy(() -> selector(template).select(old, ReadContextSelector.SOURCE_ONLY, ReadContextSelector.Access.WHOLE_SOURCE))
                    .isInstanceOf(RevisionOutdatedException.class);
            AdmittedContext moved = selector(template).select(ReadContext.current("orders", "3".repeat(40)), ReadContextSelector.SOURCE_ONLY, ReadContextSelector.Access.WHOLE_SOURCE);
            assertThat(reader.readSource(moved, source(moved.context(), "src/demo.java", 200, Optional.empty())).content()).contains("changed\n");
            publishCurrent(template);
            assertThatThrownBy(() -> selector(template).select(moved.context(), ReadContextSelector.SOURCE_ONLY, ReadContextSelector.Access.WHOLE_SOURCE))
                    .isInstanceOf(RevisionOutdatedException.class);
            assertThat(reader.readSource(current(template), source(old, "src/demo.java", 1, Optional.empty())).content()).contains("a😀\r\n");
        }
    }

    @Test
    void guide_provenance_is_side_owned_and_search_excluded_while_unavailable_code_is_honest() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_guide_metadata");
            seedSnapshot(template, "orders");
            String path = "docs/codebase/overview.md";
            String body = "guide-only-marker\nsecond\n";
            insertTextSnapshotFile(template, "orders", 1L, path, body.getBytes(StandardCharsets.UTF_8));
            String checksum = com.java.semantic.model.index.SourceArtifactDocument.create(body).contentHash();
            ProjectGuideProvenance provenance = new ProjectGuideProvenance(1, 1, new RepositoryId("orders"),
                    new RepositoryRevision("2".repeat(40)), Instant.parse("2026-09-28T00:00:00Z"),
                    new ProjectGuideProvenance.SourceScope(List.of("src"), List.of(), List.of("author navigation")));
            ProjectGuideMembership membership = new ProjectGuideMembership(ProjectGuideState.AVAILABLE, Optional.of(path),
                    Optional.of(checksum), Optional.of(new RepositoryRevision(REVISION)), Optional.of(provenance), "NOT_VERIFIED");
            growSnapshot(template, 2, 27 + body.getBytes(StandardCharsets.UTF_8).length);
            configureFixtureGuide(template, SNAPSHOT_ID, membership);
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("path", path),
                    new Document("$set", new Document("contentKind", "PROJECT_GUIDE").append("checksum", checksum)));
            publishCurrent(template);
            GitEvidenceReadService reader = service(template);
            AdmittedContext admitted = current(template);
            SourceResult guide = reader.readSource(admitted, source(admitted.context(), path, 1, Optional.empty()));
            assertThat(guide.contentKind()).isEqualTo(SourceContentKind.PROJECT_GUIDE);
            assertThat(guide.projectGuide().orElseThrow().provenance().orElseThrow().analyzedRevision()).isEqualTo("2".repeat(40));
            assertThat(guide.projectGuide().orElseThrow().importedRevision()).contains(REVISION);
            assertThat(guide.projectGuide().orElseThrow().freshness()).isEqualTo("NOT_VERIFIED");
            assertThat(reader.searchText(admitted, text(admitted.context(), "guide-only-marker", 20, Optional.empty())).items()).isEmpty();
            FileCollection files = reader.listFiles(admitted, files(admitted.context(), "docs/codebase", Optional.empty(), Optional.empty(), 20, Optional.empty()));
            assertThat(files.items().getFirst().projectGuide()).isEqualTo(guide.projectGuide());
            configureFixtureGuide(template, SNAPSHOT_ID, ProjectGuideMembership.unavailable(ProjectGuideState.INVALID));
            assertThatThrownBy(() -> reader.readSource(current(template), source(admitted.context(), path, 1, guide.nextCursor())))
                    .isInstanceOf(IndexContractMismatchException.class);
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SNAPSHOT_ID).append("path", "src/demo.java"),
                    new Document("$set", new Document("contentStatus", "TOO_LARGE").append("chunkCount", 0L)));
            SourceResult unavailable = reader.readSource(current(template), source(admitted.context(), "src/demo.java", 200, Optional.empty()));
            assertThat(unavailable.contentStatus()).isEqualTo("TOO_LARGE");
            assertThat(unavailable.content()).isEmpty();
            assertThat(unavailable.pageRange()).isEmpty();
            assertThat(unavailable.nextCursor()).isEmpty();
        }
    }

    @Test
    void equal_sha_review_side_cursor_cannot_be_replayed_on_other_membership() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_equal_side_cursor");
            seedSnapshot(template, "orders"); markSnapshotComparisonReviewOwned(template, "orders", REVIEW_ID, "READY");
            String beforeSnapshot = siblingSnapshotId("orders", "job-snapshot");
            String beforeGeneration = "source-" + beforeSnapshot;
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", beforeSnapshot),
                    new Document("$set", new Document("revision", REVISION)));
            sealFixtureSource(template, "orders", beforeSnapshot, REVISION, beforeGeneration);
            template.getCollection("review_manifests").updateOne(new Document("reviewId", REVIEW_ID), new Document("$set",
                    new Document("selection.beforeRevision", REVISION).append("resolvedEndpoints.beforeRevision", REVISION)
                            .append("before.generation", template.getConverter().convertToMongoType(sealed(REVISION, beforeGeneration, digest(beforeGeneration))))));
            ComparisonContext context = new ComparisonContext("orders", REVIEW_ID,
                    new ComparisonEndpoint(EndpointKind.REVISION, Optional.of(REVISION)), new ComparisonEndpoint(EndpointKind.REVISION, Optional.of(REVISION)));
            AdmittedComparison comparison = selector(template).selectComparison(context);
            SourceResult first = service(template).readSource(comparison.after(), source(comparison.after().context(), "src/demo.java", 1, Optional.empty()));
            AdmittedContext before = comparison.before().orElseThrow();
            assertThatThrownBy(() -> service(template).readSource(before, source(before.context(), "src/demo.java", 1, first.nextCursor())))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void root_review_has_empty_before_and_reads_only_real_after_patch_endpoint() {
        try (MongoDBContainer container = container()) {
            container.start();
            MongoTemplate template = template(container, "git_root_review");
            seedSnapshot(template, "orders"); completeReviewComparison(template, "orders");
            markSnapshotComparisonReviewOwned(template, "orders", REVIEW_ID, "READY");
            String beforeId = siblingSnapshotId("orders", "job-snapshot");
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", beforeId),
                    new Document("$unset", new Document("revision", "").append("sourceGenerationId", ""))
                            .append("$set", new Document("total", 0L).append("contentCoverage", new Document("entryCount", 0L).append("textEntries", 0L).append("textBytes", 0L))));
            template.getCollection("git_snapshot_files").deleteMany(new Document("snapshotId", beforeId));
            template.getCollection("git_snapshot_chunks").deleteMany(new Document("snapshotId", beforeId));
            template.getCollection("git_evidence_manifests").updateOne(new Document("kind", "COMPARISON"),
                    new Document("$unset", new Document("previous", "")).append("$set", new Document("ancestry", "EMPTY_TREE")));
            template.getCollection("review_manifests").updateOne(new Document("reviewId", REVIEW_ID),
                    new Document("$unset", new Document("before", "")).append("$set",
                            new Document("selection", new Document("kind", "COMMIT").append("revision", REVISION))
                                    .append("resolvedEndpoints", new Document("afterRevision", REVISION).append("baselineRule", "EMPTY_TREE"))));
            template.getCollection("git_comparison_changes").updateOne(new Document("changeId", "change-0"), new Document("$set",
                    new Document("kind", "ADD").append("oldPath", "").append("oldRawPath", rawPath("")).append("oldPathKey", "")
                            .append("oldMode", "0").append("oldBlobId", "0".repeat(40))));
            ComparisonContext context = new ComparisonContext("orders", REVIEW_ID, new ComparisonEndpoint(EndpointKind.EMPTY_TREE, Optional.empty()),
                    new ComparisonEndpoint(EndpointKind.REVISION, Optional.of(REVISION)));
            AdmittedComparison admitted = selector(template).selectComparison(context);
            assertThat(admitted.before()).isEmpty();
            ComparisonResult result = service(template).compareRevisions(admitted, new ComparisonRequest(context, page(20, Optional.empty())));
            assertThat(result.items().getFirst().before()).isEmpty();
            assertThat(result.items().getFirst().after().orElseThrow().contentKind()).isEqualTo(SourceContentKind.CODE);
            assertThat(service(template).getFileDiff(admitted, new FileDiffRequest(context, "change-0", Optional.empty())).patch()).contains("first\n");
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

    private static PageRequest page(int limit, Optional<String> cursor) { return new PageRequest(cursor, limit); }
    private static SourceRequest source(ReadContext context, String path, int lines, Optional<String> cursor) {
        return new SourceRequest(context, new SourceTarget(SourceTargetKind.FILE, Optional.empty(), Optional.of(path), Optional.of(1), Optional.empty()), lines, cursor);
    }
    private static TextSearchRequest text(ReadContext context, String literal, int limit, Optional<String> cursor) {
        return new TextSearchRequest(context, literal, "", page(limit, cursor));
    }
    private static FileListRequest files(ReadContext context, String directory, Optional<String> name, Optional<String> path, int limit, Optional<String> cursor) {
        return new FileListRequest(context, directory, name, path, page(limit, cursor));
    }
    private static ConfiguredReadPolicy policy() {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()), new GitEvidenceProperties(List.of("orders")));
    }
    private static GitEvidenceReadService service(MongoTemplate template) {
        ConfiguredReadPolicy policy = policy();
        return new GitEvidenceReadService(template, policy, Duration.ofSeconds(2),
                new CodeFactReadService(template, new SelectedGenerationGuard(template, policy, Duration.ofSeconds(2)), Duration.ofSeconds(2)));
    }
    private static ReadContextSelector selector(MongoTemplate template) {
        ConfiguredReadPolicy policy = policy();
        return new ReadContextSelector(new CurrentGenerationSelector(template, policy, Duration.ofSeconds(2)),
                new ReviewManifestReadService(template, policy, Duration.ofSeconds(2)),
                new SelectedGenerationGuard(template, policy, Duration.ofSeconds(2)), policy);
    }
    private static AdmittedContext current(MongoTemplate template) {
        return selector(template).select(ReadContext.current("orders", REVISION), ReadContextSelector.SOURCE_ONLY, ReadContextSelector.Access.WHOLE_SOURCE);
    }
    private static AdmittedComparison review(MongoTemplate template) {
        ComparisonContext context = new ComparisonContext("orders", REVIEW_ID,
                new ComparisonEndpoint(EndpointKind.REVISION, Optional.of("2".repeat(40))),
                new ComparisonEndpoint(EndpointKind.REVISION, Optional.of(REVISION)));
        return selector(template).selectComparison(context);
    }
    private static void publishCurrent(MongoTemplate template) {
        Document snapshot = template.getCollection("git_evidence_manifests").find(new Document("evidenceId", SNAPSHOT_ID)).first();
        template.getCollection("repositories").replaceOne(new Document("repoId", "orders"), new Document("repoId", "orders")
                .append("currentPointer", new Document("revision", REVISION).append("generationId", "source-" + SNAPSHOT_ID)
                        .append("manifestDigest", digest("source-" + SNAPSHOT_ID)).append("committedJobId", snapshot.getString("ownerJobId"))
                        .append("publishedAt", Date.from(Instant.EPOCH))), new com.mongodb.client.model.ReplaceOptions().upsert(true));
    }
    private static void growSnapshot(MongoTemplate template, long total, long bytes) {
        template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SNAPSHOT_ID), new Document("$set",
                new Document("total", total).append("contentCoverage.entryCount", total).append("contentCoverage.textEntries", total).append("contentCoverage.textBytes", bytes)));
    }
    private static void metadataFixture(MongoTemplate template, String job, String branch, String revision, String phase, Instant observed) {
        String catalog = UUID.nameUUIDFromBytes((job + ":catalog").getBytes(StandardCharsets.UTF_8)).toString();
        String history = UUID.nameUUIDFromBytes((job + ":history").getBytes(StandardCharsets.UTF_8)).toString();
        Date time = Date.from(observed);
        Document result = new Document("repoId", "orders").append("historyId", history).append("catalogId", catalog)
                .append("branch", branch).append("revision", revision).append("preparedAt", time).append("state", "READY")
                .append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("total", 2L).append("publicationScope", "STANDALONE");
        template.getCollection("index_jobs").insertOne(new Document("repoId", "orders").append("jobId", job).append("operation", "GIT_METADATA")
                .append("phase", phase).append("active", !phase.equals("COMPLETE")).append("jobVersion", IndexSchemaContract.PERSISTED_JOB_VERSION).append("createdAt", time)
                .append("gitEvidence", new Document("branch", branch).append("revision", revision).append("catalogId", catalog).append("evidenceId", history).append("metadataResult", result)));
        Document common = new Document("repoId", "orders").append("state", "READY").append("scope", "STANDALONE")
                .append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("ownerJobId", job).append("contentDigest", "a".repeat(64));
        template.getCollection("git_evidence_manifests").insertMany(List.of(new Document(common).append("evidenceId", catalog).append("kind", "CATALOG")
                .append("observedAt", time).append("total", 1L), new Document(common).append("evidenceId", history).append("kind", "HISTORY")
                .append("catalogId", catalog).append("branch", branch).append("revision", revision).append("preparedAt", time).append("total", 2L)));
        template.getCollection("git_branches").insertOne(new Document("repoId", "orders").append("catalogId", catalog).append("ordinal", 0L).append("branch", branch).append("head", revision));
        template.getCollection("git_commits").insertMany(List.of(new Document("repoId", "orders").append("historyId", history).append("ordinal", 0L)
                .append("revision", revision).append("parents", List.of("2".repeat(40))).append("subject", "head").append("committedAt", time),
                new Document("repoId", "orders").append("historyId", history).append("ordinal", 1L).append("revision", "2".repeat(40))
                        .append("parents", List.of()).append("subject", "parent").append("committedAt", time)));
        template.getCollection("repositories").updateOne(new Document("repoId", "orders"), new Document("$set", new Document("metadataPointer",
                new Document("catalogId", catalog).append("historyId", history).append("branch", branch).append("headRevision", revision).append("observedAt", time))));
    }
    private static MongoDBContainer container() { return new MongoDBContainer(DockerImageName.parse("mongo:8.0.4")); }
    private static MongoTemplate template(MongoDBContainer container, String database) {
        return new MongoTemplate(MongoClients.create(container.getConnectionString()), database);
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
        for (Document source : template.getCollection("git_snapshot_chunks").find(new Document("snapshotId", snapshotId))) {
            Document copy = new Document(source); copy.remove("_id"); copy.put("snapshotId", siblingSnapshotId);
            template.getCollection("git_snapshot_chunks").insertOne(copy);
        }
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", UUID.randomUUID().toString()).append("kind", "COMPARISON")
                .append("state", "READY").append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION)
                .append("scope", "STANDALONE").append("ownerJobId", ownerJobId).append("previous", "2".repeat(40))
                .append("current", revision).append("previousSnapshotId", siblingSnapshotId)
                .append("currentSnapshotId", snapshotId).append("contentDigest", "c".repeat(64)).append("ancestry", "PREVIOUS_ANCESTOR").append("total", 0L));
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
                        .append("byteOffset", (long) first.length).append("line", 1L).append("column", 32_769L).append("bytes", second)));
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", repositoryId).append("evidenceId", SNAPSHOT_ID),
                new Document("$set", new Document("total", 2L).append("contentCoverage.entryCount", 2L).append("contentCoverage.textEntries", 2L)
                        .append("contentCoverage.textBytes", (long) bytes.length + 27L)));
        sealAfterFixtureMutation(template, repositoryId);
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
    private static byte[] rawPath(String path) {
        return path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String rawPathKey(String path) {
        return java.util.HexFormat.of().formatHex(rawPath(path));
    }
}
