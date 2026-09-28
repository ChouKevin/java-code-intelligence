package com.java.semantic.indexer.review;

import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.job.MongoIndexJobStore;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitChangeKind;
import com.java.semantic.model.git.GitComparisonChange;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPublicationScope;
import com.java.semantic.model.git.GitFileContentStatus;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.PublishedGenerationPointer;
import com.java.semantic.model.index.SealedGeneration;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewId;
import com.java.semantic.model.review.ReviewManifestDocument;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ReviewBaselineRule;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.query.SemanticQueryApplication;
import com.mongodb.client.MongoClients;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Mongo review graph regressions driven through controlled semantic and Git preparation ports. */
@Tag("mongo-it")
class ReviewPreparationIT {
    private static final String REVIEW_SOURCE_PATH = "src/ReviewSource.java";
    private static final String REVIEW_SOURCE = "class ReviewSource { void stable() {} }\n";

    @Test
    void ready_manifest_published_before_the_owner_stage_cas_recovers_to_complete() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", prepared.job.id().value()),
                    new Document("$set", new Document("review.stage", "VALIDATING")));

            fixture.jobs.reconcileCommittedJobs();

            assertThat(fixture.jobs.find(prepared.job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.COMPLETE);
            assertThat(fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()).before().orElseThrow().generation().selected())
                    .isEqualTo(prepared.captured.selected());
        }
    }

    @Test
    void forged_ready_membership_is_unavailable_and_recovery_fails_its_owner() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateOne(new Document("reviewId", prepared.reviewId().value()),
                    new Document("$set", new Document("comparisonId", "f".repeat(36))));

            assertThatThrownBy(() -> fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()))
                    .isInstanceOf(IllegalStateException.class);
            fixture.jobs.reconcileCommittedJobs();
            fixture.jobs.failUnreconciledRunningJobs();

            assertThat(fixture.jobs.find(prepared.job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
        }
    }

    @Test
    void altered_analysis_inputs_remain_unavailable_and_fail_recovery_even_when_the_stored_fingerprint_is_unchanged() {
        assertSemanticTamperingIsRejected("analysisInputs.analyzerDigest", "f".repeat(64));
    }

    @Test
    void altered_analysis_project_proof_remains_unavailable_and_fails_recovery() {
        assertSemanticTamperingIsRejected("analysisEvidence.projects", List.of(new Document("projectPath", "forged")
                .append("imported", true).append("verifiedSourcePaths", List.of())));
    }

    @Test
    void altered_sealed_identity_digest_remains_unavailable_and_fails_recovery() {
        assertSemanticTamperingIsRejected("identityDigest", "e".repeat(64));
    }

    @Test
    void altered_source_artifact_bytes_with_unchanged_line_layout_remain_unavailable_and_fail_recovery() {
        assertPostReadyTampering((fixture, ignored) -> {
            Document artifact = fixture.template.getCollection(IndexCollections.SOURCE_ARTIFACTS).find().first();
            String replacement = artifact.getString("utf8Content").replace("stable", "forged");
            assertThat(replacement).isNotEqualTo(artifact.getString("utf8Content"));
            assertThat(fixture.template.getCollection(IndexCollections.SOURCE_ARTIFACTS).updateOne(
                    new Document("utf8Content", artifact.getString("utf8Content")),
                    new Document("$set", new Document("utf8Content", replacement))).getModifiedCount()).isEqualTo(1L);
        });
    }

    @Test
    void missing_snapshot_file_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES)
                .deleteOne(new Document("snapshotId", prepared.currentSnapshotId())));
    }

    @Test
    void missing_snapshot_chunk_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS)
                .deleteOne(new Document("snapshotId", prepared.currentSnapshotId())));
    }

    @Test
    void missing_comparison_patch_keeps_review_preparing_when_ready_publication_is_retried() {
        assertPublicationRefusesTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES)
                .deleteOne(new Document("comparisonId", prepared.comparisonId())));
    }

    @Test
    void range_uses_requested_before_after_current_moves_and_publishes_direct_git_evidence() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);

            assertThat(prepared.endpointA()).isEqualTo(prepared.captured);
            assertThat(prepared.endpointB().selected().revision()).isEqualTo(fixture.requestedRevision);
            assertThat(fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()).before().orElseThrow().generation().selected())
                    .isEqualTo(prepared.captured.selected());
            assertThat(fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()).resolvedEndpoints().orElseThrow().baselineRule())
                    .isEqualTo(ReviewBaselineRule.DIRECT_RANGE);
            assertThat(fixture.currentPointer().revision()).isEqualTo(fixture.movedCurrent.revision());
        }
    }

    @Test
    void root_review_without_current_publishes_only_after_semantics_and_empty_tree_additions() {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.ROOT);
            ReviewManifestDocument ready = fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId());

            assertThat(prepared.endpointA()).isNull();
            assertThat(ready.before()).isEmpty();
            assertThat(ready.after().orElseThrow().generation().selected().revision()).isEqualTo(fixture.requestedRevision);
            assertThat(ready.resolvedEndpoints().orElseThrow().baselineRule()).isEqualTo(ReviewBaselineRule.EMPTY_TREE);
            assertThat(fixture.jobs.publicationState(fixture.repositoryId).orElseThrow().currentPointer()).isEmpty();
            assertThat(fixture.template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES)
                    .find(new Document("comparisonId", prepared.comparisonId())).first().getString("kind")).isEqualTo("ADD");
        }
    }

    @Test
    void root_review_git_evidence_is_readable_over_real_mcp_transport_with_absent_previous() throws Exception {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.ROOT);
            String mongoUri = container.getConnectionString() + "/semantic";
            Path queryConfig = Path.of("../semantic-query/src/main/resources/application.yml").toAbsolutePath();
            try (ConfigurableApplicationContext query = new SpringApplicationBuilder(SemanticQueryApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--spring.config.location=" + queryConfig.toUri(), "--spring.mongodb.uri=" + mongoUri,
                            "--server.address=127.0.0.1", "--server.port=0", "--semantic.query.api-token=review-query-token",
                            "--semantic.query.git-evidence.allowed-repositories[0]=" + fixture.repositoryId.value(),
                            "--spring.main.banner-mode=off")) {
                int port = ((WebServerApplicationContext) query).getWebServer().getPort();
                JsonMapper mapper = JsonMapper.builder().build();
                HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port + "/mcp")
                        .jsonMapper(new JacksonMcpJsonMapper(mapper))
                        .httpRequestCustomizer((request, method, uri, body, context) -> request.header("X-Api-Token", "review-query-token"))
                        .build();
                try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30))
                        .initializationTimeout(Duration.ofSeconds(30)).build()) {
                    client.initialize();
                    McpSchema.Tool comparisonTool = client.listTools().tools().stream()
                            .filter(tool -> tool.name().equals("compare_revisions")).findFirst().orElseThrow();
                    McpSchema.Tool diffTool = client.listTools().tools().stream()
                            .filter(tool -> tool.name().equals("get_file_diff")).findFirst().orElseThrow();
                    Map<String, Object> arguments = Map.of("repositoryId", fixture.repositoryId.value(),
                            "comparisonId", prepared.comparisonId(), "current", fixture.requestedRevision.value());
                    Map<?, ?> comparison = callMcp(client, mapper, "compare_revisions", arguments);
                    assertThat(comparison.get("previous")).isNull();
                    assertThat(comparison.get("ancestry")).isEqualTo("EMPTY_TREE");
                    Map<?, ?> addition = ((List<Map<?, ?>>) comparison.get("items")).getFirst();
                    assertThat(addition.get("kind")).isEqualTo("ADD");
                    Map<?, ?> patch = callMcp(client, mapper, "get_file_diff", Map.of("repositoryId", fixture.repositoryId.value(),
                            "comparisonId", prepared.comparisonId(), "current", fixture.requestedRevision.value(),
                            "changeId", addition.get("changeId")));
                    assertThat(patch.get("previous")).isNull();
                    assertThat(patch.get("patch")).asString().contains("+class ReviewSource");
                    for (McpSchema.Tool tool : List.of(comparisonTool, diffTool)) {
                        assertThat(((List<?>) tool.inputSchema().get("required")).contains("previous")).isFalse();
                        Map<?, ?> properties = (Map<?, ?>) tool.outputSchema().get("properties");
                        Map<?, ?> previous = (Map<?, ?>) properties.get("previous");
                        assertThat((List<?>) previous.get("oneOf")).anySatisfy(option ->
                                assertThat(((Map<?, ?>) option).get("type")).isEqualTo("null"));
                    }
                }
            }
        }
    }

    private static Map<?, ?> callMcp(McpSyncClient client, JsonMapper mapper, String name, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        assertThat(result.isError()).as("%s failed: %s", name, result.content()).isFalse();
        return mapper.convertValue(result.structuredContent(), Map.class);
    }

    @Test
    void incompatible_captured_a_builds_reserved_target_while_compatible_same_sha_alternate_keeps_its_original_owner() {
        try (MongoDBContainer first = container(); MongoDBContainer second = container()) {
            Fixture rebuild = fixture(first);
            PreparedReview rebuilt = rebuild.prepare(Selection.RESERVED_TARGET);
            assertThat(rebuilt.endpointA().selected().generationId()).isEqualTo(rebuilt.reservedA());
            assertThat(rebuild.generationOwner(rebuilt.reservedA())).isEqualTo(rebuilt.job.id().value());

            Fixture alternate = fixture(second);
            PreparedReview reused = alternate.prepare(Selection.ALTERNATE);
            assertThat(reused.endpointA().selected().generationId()).isEqualTo(alternate.alternateA.selected().generationId());
            assertThat(alternate.generationOwner(alternate.alternateA.selected().generationId())).isEqualTo("alternate-owner");
        }
    }

    private static void assertPublicationRefusesTampering(Tampering tampering) {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            fixture.resetForPublishRetry(prepared);
            tampering.apply(fixture, prepared);

            assertThatThrownBy(() -> fixture.reviews.publishReady(fixture.jobs.find(prepared.job.id()).orElseThrow()))
                    .isInstanceOf(ReviewPreparationException.class);
            assertThat(fixture.reviewState(prepared.reviewId())).isEqualTo("PREPARING");
        }
    }

    private static void assertSemanticTamperingIsRejected(String field, Object value) {
        assertPostReadyTampering((fixture, prepared) -> fixture.template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(
                new Document("generationId", prepared.captured.selected().generationId().value()), new Document("$set", new Document(field, value))));
    }

    private static void assertPostReadyTampering(Tampering tampering) {
        try (MongoDBContainer container = container()) {
            Fixture fixture = fixture(container);
            PreparedReview prepared = fixture.prepare(Selection.CAPTURED);
            tampering.apply(fixture, prepared);
            fixture.resetForPublishRetry(prepared);
            assertThatThrownBy(() -> fixture.reviews.publishReady(fixture.jobs.find(prepared.job.id()).orElseThrow()))
                    .isInstanceOf(ReviewPreparationException.class);
            assertThat(fixture.reviewState(prepared.reviewId())).isEqualTo("PREPARING");
            assertThatThrownBy(() -> fixture.reviews.findReady(fixture.repositoryId, prepared.reviewId()))
                    .isInstanceOf(IllegalStateException.class);
            fixture.jobs.reconcileCommittedJobs();
            fixture.jobs.failUnreconciledRunningJobs();

            assertThat(fixture.jobs.find(prepared.job.id()).orElseThrow().phase()).isEqualTo(IndexJobPhase.FAILED);
        }
    }

    private static MongoDBContainer container() {
        MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
        container.start();
        return container;
    }

    private static Fixture fixture(MongoDBContainer container) {
        MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "semantic");
        new IndexSchemaBootstrap(template).bootstrap();
        return new Fixture(template);
    }

    private interface Tampering {
        void apply(Fixture fixture, PreparedReview prepared);
    }

    private enum Selection {
        CAPTURED, RESERVED_TARGET, ALTERNATE, ROOT
    }

    private static final class Fixture {
        private static final RepositoryRevision CAPTURED_REVISION = new RepositoryRevision("a".repeat(40));
        private static final ManifestDigest CAPTURED_DIGEST = new ManifestDigest("a".repeat(64));
        private static final RepositoryRevision REQUESTED_REVISION = new RepositoryRevision("b".repeat(40));
        private final MongoTemplate template;
        private final MongoIndexJobStore jobs;
        private final ReviewPublicationStore reviews;
        private final RepositoryId repositoryId = RepositoryId.of("orders");
        private final PublishedGenerationPointer movedCurrent = new PublishedGenerationPointer(new RepositoryRevision("c".repeat(40)),
                new GenerationId("g-moved"), new ManifestDigest("c".repeat(64)), "moved-owner", Instant.parse("2026-09-19T00:01:00Z"));
        private final SealedGeneration captured;
        private final SealedGeneration alternateA;
        private final RepositoryRevision requestedRevision = REQUESTED_REVISION;

        private Fixture(MongoTemplate template) {
            this.template = template;
            this.jobs = new MongoIndexJobStore(template);
            this.reviews = new ReviewPublicationStore(template, new ReviewReadinessValidator(template), jobs);
            captured = seedGeneration(CAPTURED_REVISION, new GenerationId("g-captured"), CAPTURED_DIGEST, "captured-owner");
            alternateA = seedGeneration(CAPTURED_REVISION, new GenerationId("g-alternate"), new ManifestDigest("d".repeat(64)), "alternate-owner");
            PublishedGenerationPointer capturedPointer = new PublishedGenerationPointer(captured.selected().revision(),
                    captured.selected().generationId(), captured.selected().manifestDigest(), "captured-owner",
                    Instant.parse("2026-09-19T00:00:00Z"));
            template.getCollection(IndexCollections.REPOSITORIES).insertOne(new Document("repoId", repositoryId.value())
                    .append("currentPointer", pointer(capturedPointer)));
        }

        private PreparedReview prepare(Selection selection) {
            ReviewSelection requested = selection == Selection.ROOT ? ReviewSelection.commit(requestedRevision)
                    : ReviewSelection.range(CAPTURED_REVISION, requestedRevision);
            if (selection == Selection.ROOT) {
                template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", repositoryId.value()),
                        new Document("$unset", new Document("currentPointer", "")));
            }
            IndexJob accepted = jobs.admitReview(repositoryId, requested);
            if (selection != Selection.ROOT) {
                template.getCollection(IndexCollections.REPOSITORIES).updateOne(new Document("repoId", repositoryId.value()),
                        new Document("$set", new Document("currentPointer", pointer(movedCurrent))));
            }
            IndexJob running = jobs.startNextAccepted().orElseThrow();
            AtomicReference<SealedGeneration> before = new AtomicReference<>();
            AtomicReference<SealedGeneration> after = new AtomicReference<>();
            ReviewEndpointPreparationPort endpoints = (job, side, candidates) -> {
                SealedGeneration selected;
                if (side == ReviewSide.BEFORE) {
                    selected = switch (selection) {
                        case CAPTURED -> candidates.stream().filter(candidate -> candidate.selected().equals(captured.selected())).findFirst().orElseThrow();
                        case RESERVED_TARGET -> reservedGeneration(job);
                        case ALTERNATE -> candidates.stream().filter(candidate -> candidate.selected().equals(alternateA.selected())).findFirst().orElseThrow();
                        case ROOT -> throw new AssertionError("root must not prepare before semantics");
                    };
                    before.set(selected);
                } else {
                    selected = reservedGeneration(job);
                    after.set(selected);
                }
                return selected;
            };
            ReviewGitEvidencePort git = new ReviewGitEvidencePort() {
                @Override
                public ResolvedReviewEndpoints resolve(IndexJob job) {
                    return selection == Selection.ROOT
                            ? new ResolvedReviewEndpoints(Optional.empty(), requestedRevision, ReviewBaselineRule.EMPTY_TREE)
                            : new ResolvedReviewEndpoints(Optional.of(CAPTURED_REVISION), requestedRevision, ReviewBaselineRule.DIRECT_RANGE);
                }

                @Override
                public void prepare(IndexJob job) {
                    new GitEvidencePublicationStore(template).publishComparison(job, comparison(selection), Instant.now(),
                            new GitEvidenceOwnership(GitPublicationScope.REVIEW, Optional.of(job.review().orElseThrow().reviewId())));
                }
            };
            new ReviewPreparationService(jobs, endpoints, git, reviews, template).prepare(running);
            IndexJob ready = jobs.find(accepted.id()).orElseThrow();
            return new PreparedReview(ready, ready.review().orElseThrow().reviewId(), before.get(), after.get(), captured,
                    ready.review().orElseThrow().comparisonId().orElseThrow().value(),
                    ready.review().orElseThrow().currentSnapshotId().orElseThrow().value());
        }

        private void resetForPublishRetry(PreparedReview prepared) {
            template.getCollection(IndexCollections.REVIEW_MANIFESTS).updateOne(new Document("reviewId", prepared.reviewId().value()),
                    new Document("$set", new Document("state", "PREPARING")));
            template.getCollection(IndexCollections.INDEX_JOBS).updateOne(new Document("jobId", prepared.job.id().value()),
                    new Document("$set", new Document("review.stage", "VALIDATING")));
        }

        private SealedGeneration reservedGeneration(IndexJob job) {
            IndexJobTarget target = job.target().orElseThrow();
            return seedGeneration(target.revision(), target.generationId(), new ManifestDigest(target.revision().value().substring(0, 1).repeat(64)), job.id().value());
        }

        private GitPreparedComparison comparison(Selection selection) {
            GitSnapshotEntry entry = new GitSnapshotEntry(REVIEW_SOURCE_PATH, "100644", "1".repeat(40), GitFileContentStatus.TEXT,
                    REVIEW_SOURCE.getBytes(StandardCharsets.UTF_8));
            if (selection == Selection.ROOT) {
                GitComparisonChange addition = new GitComparisonChange("change-0", GitChangeKind.ADD,
                        "", REVIEW_SOURCE_PATH, "0", "100644", "0".repeat(40), "1".repeat(40),
                        "@@ -0,0 +1 @@\n+" + REVIEW_SOURCE, "AVAILABLE");
                return new GitPreparedComparison(Optional.empty(), REQUESTED_REVISION, GitComparisonAncestry.EMPTY_TREE,
                        List.of(), List.of(entry), List.of(addition));
            }
            GitComparisonChange change = new GitComparisonChange("change-0", GitChangeKind.MODIFY,
                    REVIEW_SOURCE_PATH, REVIEW_SOURCE_PATH, "100644", "100644", "1".repeat(40), "2".repeat(40),
                    "@@ -1 +1 @@\n-review source\n+review source\n", "AVAILABLE");
            return new GitPreparedComparison(Optional.of(CAPTURED_REVISION), REQUESTED_REVISION, GitComparisonAncestry.PREVIOUS_ANCESTOR,
                    List.of(entry), List.of(entry), List.of(change));
        }

        private SealedGeneration seedGeneration(RepositoryRevision revision, GenerationId generationId, ManifestDigest digest, String ownerJobId) {
            AnalysisInputs inputs = new AnalysisInputs(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, "e".repeat(64), "e".repeat(64),
                    "e".repeat(64), "e".repeat(64), List.of());
            AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
            SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(IndexSchemaContract.ANALYSIS_EVIDENCE_VERSION, fingerprint.digest(), "SUCCESS",
                    List.of(), new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", repositoryId.value())
                    .append("sourceRevision", revision.value()).append("generationId", generationId.value()).append("identityDigest", digest.value())
                    .append("ownerJobId", ownerJobId).append("writeState", "SEALED_VALID").append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                    .append("projectionVersions", projectionVersions()).append("sealedCollectionCounts", sealedCounts())
                    .append("analysisFingerprint", fingerprint.digest()).append("analysisInputs", template.getConverter().convertToMongoType(inputs))
                    .append("analysisEvidence", template.getConverter().convertToMongoType(evidence)));
            seedSourceArtifact(generationId);
            SelectedGeneration initial = new SelectedGeneration(repositoryId, revision, generationId, digest);
            GenerationValidator.ValidationResult initialValidation = new GenerationValidator(template).validatePersistedSealed(initial);
            ManifestDigest persistedDigest = initialValidation.identityDigest();
            template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(new Document("generationId", generationId.value()),
                    new Document("$set", new Document("identityDigest", persistedDigest.value())
                            .append("sealedCollectionCounts", new Document(initialValidation.collectionCounts()))));
            SelectedGeneration selected = new SelectedGeneration(repositoryId, revision, generationId, persistedDigest);
            GenerationValidator.ValidationResult validation = new GenerationValidator(template).validatePersistedSealed(selected);
            assertThat(validation.valid()).as("seed validation issues: %s", validation.issues()).isTrue();
            return new SealedGeneration(selected, fingerprint, evidence);
        }

        private void seedSourceArtifact(GenerationId generationId) {
            SourceArtifactDocument artifact = SourceArtifactDocument.create(REVIEW_SOURCE);
            if (template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                    .countDocuments(new Document("sourceArtifactId", artifact.id().value())) == 0L) {
                Document storedArtifact = new Document();
                template.getConverter().write(artifact, storedArtifact);
                storedArtifact.put("sourceArtifactId", artifact.id().value());
                storedArtifact.put("contentHash", artifact.contentHash());
                template.getCollection(IndexCollections.SOURCE_ARTIFACTS).insertOne(storedArtifact);
            }
            GenerationFileDocument source = new GenerationFileDocument(repositoryId, generationId, REVIEW_SOURCE_PATH, artifact.id(),
                    artifact.contentHash(), "", new SourceIndexScope(false, List.of(), List.of(), List.of()));
            Document storedSource = new Document();
            template.getConverter().write(source, storedSource);
            storedSource.put("repoId", repositoryId.value());
            storedSource.put("generationId", generationId.value());
            storedSource.put("sourcePath", REVIEW_SOURCE_PATH);
            template.getCollection(IndexCollections.GENERATION_FILES).insertOne(storedSource);
        }

        private static Document sealedCounts() {
            Document counts = new Document(IndexCollections.SOURCE_ARTIFACTS, 1L);
            for (ProjectionName projection : GenerationValidator.validatedCountedAndDigestedProjections()) {
                counts.append(IndexSchemaContract.projectionCollection(projection), projection == ProjectionName.SOURCES ? 1L : 0L);
            }
            return counts;
        }

        private String reviewState(ReviewId reviewId) {
            return template.getCollection(IndexCollections.REVIEW_MANIFESTS).find(new Document("reviewId", reviewId.value())).first().getString("state");
        }

        private PublishedGenerationPointer currentPointer() {
            Document repository = template.getCollection(IndexCollections.REPOSITORIES).find(new Document("repoId", repositoryId.value())).first();
            Document pointer = repository.get("currentPointer", Document.class);
            return new PublishedGenerationPointer(new RepositoryRevision(pointer.getString("revision")), new GenerationId(pointer.getString("generationId")),
                    new ManifestDigest(pointer.getString("manifestDigest")), pointer.getString("committedJobId"), pointer.getDate("publishedAt").toInstant());
        }

        private String generationOwner(GenerationId generationId) {
            return template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("generationId", generationId.value())).first()
                    .getString("ownerJobId");
        }

    }

    private record PreparedReview(IndexJob job, ReviewId reviewId, SealedGeneration endpointA,
                                  SealedGeneration endpointB, SealedGeneration captured, String comparisonId, String currentSnapshotId) {
        private GenerationId reservedA() {
            return job.review().orElseThrow().reservedTargets().orElseThrow().before().orElseThrow().generationId();
        }
    }

    private static Document pointer(PublishedGenerationPointer pointer) {
        return new Document("revision", pointer.revision().value()).append("generationId", pointer.generationId().value())
                .append("manifestDigest", pointer.manifestDigest().value()).append("committedJobId", pointer.committedJobId())
                .append("publishedAt", Date.from(pointer.publishedAt()));
    }

    private static List<Document> projectionVersions() {
        return IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList();
    }
}
