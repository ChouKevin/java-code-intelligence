package com.java.semantic.indexer.job;

import com.java.semantic.indexer.build.RepositoryBuildRunner;
import com.java.semantic.indexer.config.ConfiguredRepositoryPublisher;
import com.java.semantic.indexer.review.ReviewPreparationService;
import com.java.semantic.indexer.review.ReviewPublicationStore;
import com.java.semantic.indexer.review.ReviewReadinessValidator;
import com.java.semantic.indexer.review.ReviewGitEvidencePort;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.model.review.ResolvedReviewEndpoints;
import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.indexer.build.GenerationValidator;
import com.java.semantic.indexer.build.SourceSnapshotPublication;
import com.java.semantic.indexer.build.SourceIndexBatch;
import com.java.semantic.indexer.build.SourceIndexBatchDocumentMapper;
import com.java.semantic.indexer.build.SyntaxSymbolProjector;
import com.java.semantic.indexer.build.SearchProjector;
import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.build.MongoIndexBatchWriter;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.indexer.store.GitEvidencePublicationStore;
import com.java.semantic.indexer.store.IndexSchemaBootstrap;
import com.java.semantic.indexer.store.PublicationPort;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.application.CurrentGenerationSelector;
import com.java.semantic.query.application.GitEvidenceReadService;
import com.java.semantic.query.application.CodeFactReadService;
import com.java.semantic.query.application.ContextDiscoveryService;
import com.java.semantic.query.application.ReadContextSelector;
import com.java.semantic.query.application.ReviewManifestReadService;
import com.java.semantic.query.application.SelectedGenerationGuard;
import com.java.semantic.model.review.ReviewSide;
import com.java.semantic.query.application.SemanticQueryContract;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.application.SelectedSemanticQueryService;
import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.GitEvidenceProperties;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.support.JdtLsTestProperties;

import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.repository.port.RepositoryMutationListener;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** A real JGit/Mongo slice of the normal dispatcher, reusable by later transport journeys. */
@Tag("mongo-it")
class DispatchedGitEvidenceIT {
    private static final String SOURCE_ROOT = "src/main/java";
    private static final String EVIDENCE_SOURCE = SOURCE_ROOT + "/Evidence.java";
    private static final String ADDED_SOURCE = SOURCE_ROOT + "/Added.java";

    @TempDir
    Path temporaryDirectory;

    @Test
    void dispatches_admitted_catalog_and_history_that_remain_pinned_after_the_remote_moves() throws Exception {
        Path remotePath = temporaryDirectory.resolve("remote.git");
        Path seedPath = temporaryDirectory.resolve("seed");
        Files.createDirectories(temporaryDirectory.resolve("checkouts"));
        try (MongoDBContainer container = new MongoDBContainer("mongo:8.0.4");
             Git remote = Git.init().setBare(true).setDirectory(remotePath.toFile()).call();
             Git seed = Git.init().setInitialBranch("main").setDirectory(seedPath.toFile()).call()) {
            RepositoryRevision first = commit(seed, seedPath, "", "class Evidence { }");
            seed.remoteAdd().setName("origin").setUri(new URIish(remotePath.toUri().toString())).call();
            push(seed, "main");
            remote.getRepository().updateRef("HEAD", true).link("refs/heads/main");
            RepositoryRevision head = commit(seed, seedPath, "second", "class Evidence { int version; }");
            push(seed, "main");
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "dispatched_git_evidence");
            new IndexSchemaBootstrap(template).bootstrap();
            MongoIndexJobStore jobs = new MongoIndexJobStore(template);
            RepositoryRuntimeRegistry repositories = registry(remotePath);
            GitEvidenceJobHandler handler = new GitEvidenceJobHandler(repositories, new JGitRepositoryAdapter(
                    properties(remotePath), JdtLsTestProperties.linuxUid()),
                    new GitEvidencePublicationStore(template), mock(RepositoryMutationListener.class));
            ReviewPreparationService reviews = new ReviewPreparationService(jobs,
                    (job, side, candidates) -> candidates.stream().filter(candidate ->
                            new GenerationValidator(template).validatePersistedSealed(candidate.selected()).valid())
                            .findFirst().orElseThrow(), new ReviewGitEvidencePort() {
                        @Override
                        public ResolvedReviewEndpoints resolve(IndexJob job) { return handler.resolveReview(job); }
                        @Override
                        public void prepare(IndexJob job) { handler.prepareReview(job); }
                    }, new ReviewPublicationStore(template, new ReviewReadinessValidator(template), jobs), template);
            IndexJobExecutor executor = new IndexJobExecutor(jobs, mock(RepositoryBuildRunner.class), mock(PublicationPort.class),
                    Optional.empty(), Optional.of(handler), Optional.of(reviews));
            IndexJobDispatcher dispatcher = new IndexJobDispatcher(jobs, executor, new IndexJobProperties(Duration.ofMillis(5)));
            IndexRequestService requests = new IndexRequestService(mock(RepositoryRevisionResolver.class), repositories, jobs);
            new ConfiguredRepositoryPublisher(template, repositories).publish();

            IndexJob catalogJob = requests.refreshRepositoryMetadata(RepositoryId.of("orders"), requestId(), Optional.empty());
            dispatcher.dispatchOnce();
            IndexJob catalogComplete = jobs.find(catalogJob.id()).orElseThrow();
            assertThat(catalogComplete.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            String catalogId = catalogComplete.gitEvidence().orElseThrow().catalogId().orElseThrow().value();
            GitEvidenceReadService reader = reader(template);
            SemanticQueryContract.GitBranchCollection catalog = reader.listGitBranches(
                    new SemanticQueryContract.GitBranchRequest("orders", page()));
            assertThat(catalog.items()).extracting(SemanticQueryContract.GitBranchItem::headRevision).containsExactly(head.value());
            httpReader(template, reader).perform(post("/api/v1/git/branches").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"repositoryId\":\"orders\",\"limit\":20}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.metadata.catalogId").value(catalogId))
                    .andExpect(jsonPath("$.items[0].headRevision").value(head.value()));

            IndexJob historyComplete = catalogComplete;
            assertThat(historyComplete.gitEvidence().orElseThrow().metadataResult().orElseThrow().total()).isEqualTo(2L);
            assertThat(new GitEvidencePublicationStore(template).metadataReady(catalogJob)).isTrue();
            SemanticQueryContract.GitCommitCollection history = reader.listGitCommits(
                    new SemanticQueryContract.GitCommitRequest("orders", "main", page()));
            assertThat(history.items()).extracting(SemanticQueryContract.GitCommitItem::revision).containsExactly(head.value(), first.value());
            assertThat(history.items().getFirst().parents()).containsExactly(first.value());
            assertThat(history.items().get(1).subject()).isEmpty();

            String unicodeComment = "//" + "😀".repeat(17_000) + "\r\n";
            String declarationPrefix = "class Added { String marker = \"";
            String addedSource = unicodeComment + declarationPrefix + "😀needle\"; }";
            RepositoryRevision added = commitAdditional(seed, seedPath, "add", ADDED_SOURCE, addedSource);
            push(seed, "main");
            seedPreparedSource(template, jobs, seedPath, head, List.of(EVIDENCE_SOURCE));
            seedPreparedSource(template, jobs, seedPath, added, List.of(ADDED_SOURCE, EVIDENCE_SOURCE));
            IndexJob addComparisonJob = requests.prepareReview(RepositoryId.of("orders"), requestId(), ReviewSelection.range(head, added));
            dispatcher.dispatchOnce();
            IndexJob addComparisonComplete = jobs.find(addComparisonJob.id()).orElseThrow();
            assertThat(addComparisonComplete.phase()).isEqualTo(IndexJobPhase.COMPLETE);
            String addComparisonId = addComparisonComplete.review().orElseThrow().comparisonId().orElseThrow().value();
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES)
                    .find(new Document("comparisonId", addComparisonId)).into(new java.util.ArrayList<>()))
                    .anySatisfy(change -> assertThat(change.getString("kind")).isEqualTo("ADD"));
            String snapshotId = addComparisonComplete.review().orElseThrow().currentSnapshotId().orElseThrow().value();
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES)
                    .find(new Document("snapshotId", snapshotId)).into(new java.util.ArrayList<>()))
                    .extracting(file -> file.getString("path")).contains(ADDED_SOURCE, EVIDENCE_SOURCE);
            SemanticQueryContract.ReadContext context = SemanticQueryContract.ReadContext.review("orders",
                    addComparisonComplete.review().orElseThrow().reviewId().value(), ReviewSide.AFTER, added.value());
            ReadContextSelector.AdmittedContext admitted = contexts(template).select(context,
                    ReadContextSelector.SOURCE_ONLY, ReadContextSelector.Access.WHOLE_SOURCE);
            SemanticQueryContract.SourceTarget target = new SemanticQueryContract.SourceTarget(SemanticQueryContract.SourceTargetKind.FILE,
                    Optional.empty(), Optional.of(ADDED_SOURCE), Optional.of(1), Optional.empty());
            SemanticQueryContract.SourceResult unicodeFirst = reader.readSource(admitted,
                    new SemanticQueryContract.SourceRequest(context, target, 1, Optional.empty()));
            SemanticQueryContract.SourceResult unicodeSecond = reader.readSource(admitted,
                    new SemanticQueryContract.SourceRequest(context, target, 1, unicodeFirst.nextCursor()));
            assertThat(unicodeFirst.endLineComplete()).isFalse();
            assertThat(unicodeSecond.startLineComplete()).isFalse();
            assertThat(unicodeFirst.content().orElseThrow() + unicodeSecond.content().orElseThrow()).isEqualTo(unicodeComment);
            SemanticQueryContract.SourceResult declaration = reader.readSource(admitted,
                    new SemanticQueryContract.SourceRequest(context, target, 1, unicodeSecond.nextCursor()));
            assertThat(declaration.content()).contains(declarationPrefix + "😀needle\"; }");
            assertThat(declaration.nextCursor()).isEmpty();
            assertThat(reader.searchText(admitted, new SemanticQueryContract.TextSearchRequest(
                    context, "needle", "", new SemanticQueryContract.PageRequest(Optional.empty(), 1))).items())
                    .singleElement().satisfies(match -> {
                        assertThat(match.path()).isEqualTo(ADDED_SOURCE);
                        assertThat(match.range().start().line()).isEqualTo(1);
                        assertThat(match.range().start().character()).isEqualTo(declarationPrefix.length() + 2);
                    });

            RepositoryRevision deleted = deleteAdditional(seed, seedPath, "delete", ADDED_SOURCE);
            push(seed, "main");
            seedPreparedSource(template, jobs, seedPath, deleted, List.of(EVIDENCE_SOURCE));
            IndexJob deleteComparisonJob = requests.prepareReview(RepositoryId.of("orders"), requestId(), ReviewSelection.range(added, deleted));
            dispatcher.dispatchOnce();
            String deleteComparisonId = jobs.find(deleteComparisonJob.id()).orElseThrow().review().orElseThrow().comparisonId().orElseThrow().value();
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES)
                    .find(new Document("comparisonId", deleteComparisonId)).into(new java.util.ArrayList<>()))
                    .anySatisfy(change -> assertThat(change.getString("kind")).isEqualTo("DELETE"));

            RepositoryRevision modeAndContent = commitModeAndContent(seed, seedPath);
            push(seed, "main");
            seedPreparedSource(template, jobs, seedPath, modeAndContent, List.of(EVIDENCE_SOURCE));
            IndexJob modeComparisonJob = requests.prepareReview(RepositoryId.of("orders"), requestId(), ReviewSelection.range(deleted, modeAndContent));
            dispatcher.dispatchOnce();
            String modeComparisonId = jobs.find(modeComparisonJob.id()).orElseThrow().review().orElseThrow().comparisonId().orElseThrow().value();
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES)
                    .find(new Document("comparisonId", modeComparisonId)).into(new java.util.ArrayList<>()))
                    .anySatisfy(change -> assertThat(change.getString("kind")).isEqualTo("MODE"));

            commit(seed, seedPath, "third", "class Evidence { int version; int later; }");
            push(seed, "main");
            assertThat(reader.listGitBranches(new SemanticQueryContract.GitBranchRequest("orders", page())).items())
                    .extracting(SemanticQueryContract.GitBranchItem::headRevision).containsExactly(head.value());
            assertThat(reader.listGitCommits(new SemanticQueryContract.GitCommitRequest("orders", "main", page())).items())
                    .extracting(SemanticQueryContract.GitCommitItem::revision).containsExactly(head.value(), first.value());
        }
    }

    private static PreparationRequestId requestId() {
        return new PreparationRequestId(java.util.UUID.randomUUID().toString());
    }

    private RepositoryRuntimeRegistry registry(Path remotePath) {
        return new RepositoryRuntimeRegistry(properties(remotePath));
    }

    private RepositoryProperties properties(Path remotePath) {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setDataRoot(temporaryDirectory.resolve("checkouts").toString());
        RepositoryProperties.RepositoryConfig config = new RepositoryProperties.RepositoryConfig();
        config.setUrl(remotePath.toUri().toString());
        config.setDefaultBranch("main");
        properties.setRepositories(Map.of("orders", config));
        return properties;
    }

    private static SemanticQueryContract.PageRequest page() {
        return new SemanticQueryContract.PageRequest(Optional.empty(), 20);
    }

    private static ConfiguredReadPolicy policy() {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()),
                new GitEvidenceProperties(List.of("orders")));
    }

    private static GitEvidenceReadService reader(MongoTemplate template) {
        SelectedGenerationGuard guard = new SelectedGenerationGuard(template, policy(), Duration.ofSeconds(2));
        return new GitEvidenceReadService(template, policy(), Duration.ofSeconds(2),
                new CodeFactReadService(template, guard, Duration.ofSeconds(2)));
    }

    private static ReadContextSelector contexts(MongoTemplate template) {
        SelectedGenerationGuard guard = new SelectedGenerationGuard(template, policy(), Duration.ofSeconds(2));
        return new ReadContextSelector(new CurrentGenerationSelector(template, policy(), Duration.ofSeconds(2)),
                new ReviewManifestReadService(template, policy(), Duration.ofSeconds(2)), guard, policy());
    }

    private static MockMvc httpReader(MongoTemplate template, GitEvidenceReadService reader) {
        SelectedGenerationGuard guard = new SelectedGenerationGuard(template, policy(), Duration.ofSeconds(2));
        ReadContextSelector contexts = contexts(template);
        SemanticQueryFacade facade = new SemanticQueryFacade(new ContextDiscoveryService(template, policy(), Duration.ofSeconds(2),
                new CurrentGenerationSelector(template, policy(), Duration.ofSeconds(2)), contexts,
                new ReviewManifestReadService(template, policy(), Duration.ofSeconds(2))), contexts,
                new SelectedSemanticQueryService(template, guard, Duration.ofSeconds(2),
                        new CodeFactReadService(template, guard, Duration.ofSeconds(2))), reader);
        return standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler()).build();
    }

    private static void seedPreparedSource(MongoTemplate template, MongoIndexJobStore jobs, Path fixture,
            RepositoryRevision revision, List<String> selectedPaths) throws Exception {
        try (Git git = Git.open(fixture.toFile())) {
            git.checkout().setName(revision.value()).call();
        }
        JdtLsTestProperties.prepareSafeCheckoutRoot(fixture);
        RepositoryId repository = RepositoryId.of("orders");
        FullIndexPlan plan = new FullIndexPlanner().plan(fixture, List.of(fixture.resolve(SOURCE_ROOT)));
        assertThat(plan.sources()).extracting(FullIndexPlan.SourceInput::sourcePath)
                .containsExactlyElementsOf(selectedPaths);
        jobs.admit(repository, revision, false);
        IndexJob job = jobs.startNextAccepted().orElseThrow();
        IndexJobTarget target = job.target().orElseThrow();
        GenerationWriteContext context = new GenerationWriteContext(repository, target.generationId(), job.id().value());
        MongoGenerationWriter writer = new MongoGenerationWriter(template);
        writer.insertManifest(context, new Document("repoId", repository.value())
                .append("generationId", target.generationId().value()).append("sourceRevision", revision.value())
                .append("ownerJobId", job.id().value()).append("writeState", "WRITING").append("writeEpoch", 0L)
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue())).toList())
                .append("identityDigest", "0".repeat(64)).append("outstandingBatches", List.of())
                .append("acknowledgedBatches", List.of()).append("failedOrAmbiguousBatches", List.of()));
        AnalysisInputs inputs = new AnalysisInputs(1, "e".repeat(64), "e".repeat(64), "e".repeat(64), "e".repeat(64),
                List.of(new AnalysisInputs.Project(".", "e".repeat(64), Map.of(), List.of(),
                        List.of(new AnalysisInputs.Root(SOURCE_ROOT, "MAIN", true, List.of())), List.of(), List.of())));
        AnalysisFingerprint fingerprint = AnalysisFingerprint.from(inputs);
        SemanticAnalysisEvidence evidence = new SemanticAnalysisEvidence(1, fingerprint.digest(), "SUCCESS",
                List.of(new SemanticAnalysisEvidence.ProjectProof(".", true, List.of(SOURCE_ROOT))),
                new SemanticAnalysisEvidence.ResolutionCoverage(0, 0, 0, 0, 0), List.of());
        writer.recordAnalysis(context, fingerprint, evidence);
        RepositorySyntax syntax = new JdtSyntaxExtractionService().extract(fixture);
        MongoIndexBatchWriter batches = new MongoIndexBatchWriter(writer, context,
                new SourceIndexBatchDocumentMapper(template.getConverter()));
        for (FullIndexPlan.SourceInput source : plan.sources()) {
            List<SymbolDocument> symbols = new SyntaxSymbolProjector().project(repository, revision, target.generationId(),
                    syntax, source.sourcePath(), source.contentArtifact());
            batches.write(new SourceIndexBatch(repository, target.generationId(), source.sourcePath(), 0,
                    source.contentArtifact(), Optional.empty(), symbols, List.of(), List.of(),
                    new SearchProjector().project(symbols, List.of(), List.of())));
        }
        RepositoryProperties properties = new RepositoryProperties();
        SourceSnapshotPublication.PublishedSource source = new SourceSnapshotPublication(
                new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid()),
                new GitEvidencePublicationStore(template), properties.getGitEvidenceFileTextBytes())
                .publish(job, fixture, revision, plan, Optional.empty());
        MongoGenerationWriter.SourceOverview overview = writer.sourceOverview(context, source.policy().includedRoots(),
                source.excludedOrUnsupported());
        writer.recordSourceMembership(context, source.snapshot(), source.guide(), source.policy(),
                overview.coverage(), overview.structure());
        GenerationValidator validator = new GenerationValidator(template);
        GenerationValidator.ValidationResult result = validator.validate(context, revision, revision, plan);
        assertThat(result.valid()).as("prepared source validation: %s", result.issues()).isTrue();
        validator.recordValid(context, result);
        writer.seal(context, result.identityDigest().value());
        assertThat(jobs.complete(job.id())).isTrue();
        try (Git git = Git.open(fixture.toFile())) {
            git.checkout().setName("main").call();
        }
    }

    private static RepositoryRevision commit(Git seed, Path seedPath, String subject, String source) throws Exception {
        Files.createDirectories(seedPath.resolve(SOURCE_ROOT));
        Files.writeString(seedPath.resolve(EVIDENCE_SOURCE), source);
        seed.add().addFilepattern(".").call();
        return RepositoryRevision.ofSha(seed.commit().setMessage(subject).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getId().name());
    }

    private static RepositoryRevision commitAdditional(Git seed, Path seedPath, String subject, String filename, String source) throws Exception {
        Files.writeString(seedPath.resolve(filename), source);
        seed.add().addFilepattern(filename).call();
        return RepositoryRevision.ofSha(seed.commit().setMessage(subject).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getId().name());
    }

    private static RepositoryRevision deleteAdditional(Git seed, Path seedPath, String subject, String filename) throws Exception {
        Files.delete(seedPath.resolve(filename));
        seed.rm().addFilepattern(filename).call();
        return RepositoryRevision.ofSha(seed.commit().setMessage(subject).setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getId().name());
    }

    private static RepositoryRevision commitModeAndContent(Git seed, Path seedPath) throws Exception {
        Path evidence = seedPath.resolve(EVIDENCE_SOURCE);
        Files.writeString(evidence, "class Evidence { int version; int modeChanged; }");
        Files.setPosixFilePermissions(evidence, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE));
        seed.add().addFilepattern(EVIDENCE_SOURCE).call();
        return RepositoryRevision.ofSha(seed.commit().setMessage("mode and content").setAuthor("Test", "test@example.test")
                .setCommitter("Test", "test@example.test").call().getId().name());
    }

    private static void push(Git seed, String branch) throws Exception {
        seed.push().setRemote("origin").setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch)).call();
    }
}
