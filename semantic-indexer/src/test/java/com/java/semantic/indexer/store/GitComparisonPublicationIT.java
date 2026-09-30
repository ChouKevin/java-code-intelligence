package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.GitEvidenceJob;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
import com.java.semantic.indexer.build.FullIndexPlan;
import com.java.semantic.indexer.build.FullIndexPlanner;
import com.java.semantic.indexer.build.SourceSnapshotPublication;
import com.java.semantic.model.git.GitComparisonAncestry;
import com.java.semantic.model.git.GitEvidenceOwnership;
import com.java.semantic.model.git.GitPreparedComparison;
import com.java.semantic.model.git.GitSnapshotEntry;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideProvenance;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.repository.adapter.jgit.JGitRepositoryAdapter;
import com.java.semantic.repository.config.RepositoryProperties;
import com.java.semantic.support.JdtLsTestProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.types.Binary;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.mongodb.MongoDBContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class GitComparisonPublicationIT {
    private static final RepositoryId REPOSITORY = RepositoryId.of("orders");
    private static final String JAVA = "src/main/java/orders/Order.java";
    private static final String MAPPER = "src/main/resources/mapper/OrderMapper.xml";
    private static final String GUIDE = "docs/codebase/overview.md";

    @TempDir
    Path repositoryDirectory;

    @Test
    void excludes_each_forbidden_marker_from_chunks_and_patches_while_preserving_java_mapper_and_guide() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            Files.createDirectories(repositoryDirectory.resolve(MAPPER).getParent());
            Files.createDirectories(repositoryDirectory.resolve(GUIDE).getParent());
            Files.writeString(repositoryDirectory.resolve(JAVA), "class Order {}\n");
            Files.writeString(repositoryDirectory.resolve(MAPPER), "<mapper namespace=\"orders.Order\"/>\n");
            Files.writeString(repositoryDirectory.resolve("src/main/resources/application.properties"), "SECRET_MARKER_A\n");
            Files.writeString(repositoryDirectory.resolve("README.md"), "SECRET_MARKER_B\n");
            Files.writeString(repositoryDirectory.resolve(GUIDE), "```json\n{\"formatVersion\":1,\"promptVersion\":1," +
                    "\"repositoryId\":\"orders\",\"analyzedRevision\":\"" + "1".repeat(40) + "\"," +
                    "\"generatedAt\":\"2026-09-28T12:30:00.123456789Z\",\"sourceScope\":{\"includedPaths\":[]," +
                    "\"excludedPaths\":[],\"limitations\":[]}}\n```\nGuide\n");
            git.add().addFilepattern(".").call();
            RevCommit beforeCommit = git.commit().setMessage("before").setAuthor("tester", "tester@example.test").call();
            Files.move(repositoryDirectory.resolve(JAVA), repositoryDirectory.resolve("src/main/resources/application-renamed.properties"));
            git.rm().addFilepattern(JAVA).call();
            git.add().addFilepattern("src/main/resources/application-renamed.properties").call();
            RevCommit afterCommit = git.commit().setMessage("after").setAuthor("tester", "tester@example.test").call();
            RepositoryRevision before = RepositoryRevision.ofSha(beforeCommit.name());
            RepositoryRevision after = RepositoryRevision.ofSha(afterCommit.name());
            SourceEvidencePolicy beforePolicy = policy(Set.of(JAVA, MAPPER));
            SourceEvidencePolicy afterPolicy = policy(Set.of(MAPPER));
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid());
            ProjectGuideMembership beforeGuide = guide(before);
            ProjectGuideMembership afterGuide = guide(after);
            GitPreparedComparison comparison = adapter.prepareComparison(repositoryDirectory, Optional.of(before), after,
                    beforePolicy, afterPolicy, Set.of(GUIDE), Set.of(GUIDE));
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            store.publishComparison(comparisonJob(before, after), comparison, Instant.now(), GitEvidenceOwnership.standalone(),
                    Optional.of(source(template, store, before, beforePolicy, beforeGuide, comparison.previousEntries())),
                    source(template, store, after, afterPolicy, afterGuide, comparison.currentEntries()));

            List<String> chunks = template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find()
                    .map(chunk -> new String(chunk.get("bytes", Binary.class).getData(), StandardCharsets.UTF_8))
                    .into(new ArrayList<>());
            assertThat(chunks).anySatisfy(text -> assertThat(text).contains("class Order"));
            assertThat(chunks).anySatisfy(text -> assertThat(text).contains("<mapper"));
            assertThat(chunks).anySatisfy(text -> assertThat(text).contains("Guide"));
            assertThat(chunks).noneSatisfy(text -> assertThat(text).contains("SECRET_MARKER_A"));
            assertThat(chunks).noneSatisfy(text -> assertThat(text).contains("SECRET_MARKER_B"));
            List<String> patches = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find()
                    .map(patch -> patch.getString("patch")).into(new ArrayList<>());
            assertThat(patches).noneSatisfy(patch -> assertThat(patch).contains("SECRET_MARKER_A"));
            assertThat(patches).noneSatisfy(patch -> assertThat(patch).contains("SECRET_MARKER_B"));
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).countDocuments()).isZero();
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES)
                    .countDocuments(new Document("path", "src/main/resources/application.properties"))).isZero();
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES)
                    .countDocuments(new Document("contentKind", "PROJECT_GUIDE"))).isEqualTo(4L);
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .find(new Document("kind", "SNAPSHOT")).into(new ArrayList<>()))
                    .allSatisfy(snapshot -> {
                        Document provenance = snapshot.get("projectGuide", Document.class).get("provenance", Document.class);
                        assertThat(provenance.getString("generatedAt")).isEqualTo("2026-09-28T12:30:00.123456789Z");
                        assertThat(provenance.getString("analyzedRevision")).isEqualTo("1".repeat(40));
                        assertThat(snapshot.get("projectGuide", Document.class).getString("importedRevision"))
                                .isEqualTo(snapshot.getString("revision"));
                    });
        }
    }

    @Test
    void refuses_unbound_comparison_without_persisting_a_ready_manifest() {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryRevision before = RepositoryRevision.ofSha("1".repeat(40));
            RepositoryRevision after = RepositoryRevision.ofSha("2".repeat(40));
            GitPreparedComparison comparison = new GitPreparedComparison(Optional.of(before), after,
                    GitComparisonAncestry.PREVIOUS_ANCESTOR, List.of(), List.of(), List.of());
            SourceEvidencePolicy policy = policy(Set.of());
            ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            GitEvidencePublicationStore.PreparedSource beforeSource = source(template, store, before, policy, guide, List.of());
            GitEvidencePublicationStore.PreparedSource afterSource = source(template, store, after, policy, guide, List.of());
            GitEvidencePublicationStore.PreparedSource wrongGeneration = new GitEvidencePublicationStore.PreparedSource(
                    afterSource.policy(), afterSource.guide(), afterSource.snapshot(), beforeSource.sourceGenerationId());
            assertThatThrownBy(() -> store.publishComparison(comparisonJob(before, after), comparison, Instant.now(),
                    GitEvidenceOwnership.standalone(), Optional.of(beforeSource), wrongGeneration))
                    .isInstanceOf(PublicationConflictException.class);
            template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).deleteOne(
                    new Document("evidenceId", beforeSource.snapshot().snapshotId().value()));

            assertThatThrownBy(() -> store.publishComparison(comparisonJob(before, after), comparison, Instant.now(),
                    GitEvidenceOwnership.standalone(), Optional.of(beforeSource), afterSource))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .countDocuments(new Document("kind", "COMPARISON").append("state", "READY"))).isZero();
        }
    }

    @Test
    void rejects_guide_import_identity_drift_before_persisting_source_evidence() throws Exception {
        try (MongoDBContainer container = MongoSchemaTestSupport.container()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryRevision revision = RepositoryRevision.ofSha("2".repeat(40));
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            ProjectGuideMembership wrongRevision = guide(RepositoryRevision.ofSha("3".repeat(40)));
            ProjectGuideMembership valid = guide(revision);
            ProjectGuideProvenance original = valid.provenance().orElseThrow();
            ProjectGuideMembership wrongRepository = new ProjectGuideMembership(ProjectGuideState.AVAILABLE,
                    valid.path(), valid.digest(), valid.importedRevision(),
                    Optional.of(new ProjectGuideProvenance(1, 1, RepositoryId.of("other"),
                            original.analyzedRevision(), original.generatedAt(), original.sourceScope())), "NOT_VERIFIED");

            assertThatThrownBy(() -> source(template, store, revision, policy(Set.of()), wrongRevision, List.of()))
                    .isInstanceOf(PublicationConflictException.class);
            assertThatThrownBy(() -> source(template, store, revision, policy(Set.of()), wrongRepository, List.of()))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS).countDocuments()).isZero();
        }
    }

    @Test
    void admitted_guide_and_code_must_still_fit_the_configured_total_budget() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            Files.createDirectories(repositoryDirectory.resolve(GUIDE).getParent());
            Files.writeString(repositoryDirectory.resolve(JAVA), "//" + "x".repeat(898) + "\n");
            Files.writeString(repositoryDirectory.resolve(GUIDE), "```json\n{\"formatVersion\":1,\"promptVersion\":1,"
                    + "\"repositoryId\":\"orders\",\"analyzedRevision\":\"" + "1".repeat(40)
                    + "\",\"generatedAt\":\"2026-09-28T12:30:00Z\",\"sourceScope\":{\"includedPaths\":[],"
                    + "\"excludedPaths\":[],\"limitations\":[]}}\n```\nGuide\n");
            RepositoryProperties properties = new RepositoryProperties();
            properties.setGitEvidenceFileTextBytes(1024L);
            properties.setGitEvidenceSnapshotTextBytes(1024L);

            assertThatThrownBy(() -> publishConfiguredGuide(template, git, properties))
                    .isInstanceOf(PublicationConflictException.class);
            assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                    .countDocuments(new Document("kind", "SNAPSHOT").append("state", "READY"))).isZero();
        }
    }

    @Test
    void multichunk_utf8_source_round_trips_and_missing_chunk_or_checkpoint_blocks_comparison() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryRevision before = RepositoryRevision.ofSha(git.commit().setAllowEmpty(true).setMessage("baseline")
                    .setAuthor("Test", "test@example.test").call().name());
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            String text = "// é😀\n".repeat(12000);
            Files.writeString(repositoryDirectory.resolve(JAVA), text);
            git.add().addFilepattern(".").call();
            RepositoryRevision revision = RepositoryRevision.ofSha(git.commit().setMessage("UTF-8 source")
                    .setAuthor("Test", "test@example.test").call().name());
            SourceEvidencePolicy policy = policy(Set.of(JAVA));
            ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.ABSENT);
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid());
            List<GitSnapshotEntry> entries = adapter.prepareSnapshot(repositoryDirectory, revision, policy).candidates();
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            GitEvidencePublicationStore.PreparedSource prepared = source(template, store, revision, policy, guide, entries);
            GitEvidencePublicationStore.PreparedSource previous = source(template, store, before, policy(Set.of()), guide, List.of());
            GitPreparedComparison comparison = adapter.prepareComparison(repositoryDirectory, Optional.of(before), revision,
                    policy(Set.of()), policy, Set.of(), Set.of());
            store.publishComparison(comparisonJob(before, revision), comparison, Instant.now(), GitEvidenceOwnership.standalone(),
                    Optional.of(previous), prepared);
            List<String> patches = template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find()
                    .sort(new Document("ordinal", 1)).map(row -> row.getString("patch")).into(new ArrayList<>());
            assertThat(String.join("", patches).lines().filter(line -> line.equals("+// é😀")).count()).isEqualTo(12000L);
            assertThat(patches).allSatisfy(patch -> assertThat(patch.getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(64 * 1024));
            Document chunks = new Document("snapshotId", prepared.snapshot().snapshotId().value());
            List<Document> rows = template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find(chunks)
                    .sort(new Document("ordinal", 1)).into(new ArrayList<>());
            String recovered = rows.stream().map(row -> new String(row.get("bytes", Binary.class).getData(), StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining());
            assertThat(recovered).isEqualTo(text);
            assertThat(rows).allSatisfy(row -> assertThat(row.get("bytes", Binary.class).getData().length).isLessThanOrEqualTo(64 * 1024));
            Document first = rows.get(0);
            Document firstSelection = new Document("_id", first.get("_id"));
            template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).updateOne(firstSelection,
                    new Document("$unset", new Document("line", "")));
            assertComparisonRejected(store, template, comparison, previous, prepared);
            template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).replaceOne(firstSelection, first);
            template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).deleteOne(firstSelection);
            assertComparisonRejected(store, template, comparison, previous, prepared);
        }
    }

    @Test
    void real_tracked_symlink_never_persists_source_chunks_target_bytes_or_patches() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            RepositoryRevision before = RepositoryRevision.ofSha(git.commit().setAllowEmpty(true).setMessage("baseline")
                    .setAuthor("Test", "test@example.test").call().name());
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            Files.writeString(repositoryDirectory.resolve(JAVA), "class Order {}\n");
            Files.writeString(repositoryDirectory.resolve("target.txt"), "SYMLINK_TARGET_MARKER\n");
            String link = "src/main/java/Linked.java";
            Files.createSymbolicLink(repositoryDirectory.resolve(link), Path.of("../../../target.txt"));
            git.add().addFilepattern(".").call();
            RepositoryRevision revision = RepositoryRevision.ofSha(git.commit().setMessage("regular and symlink")
                    .setAuthor("Test", "test@example.test").call().name());
            SourceEvidencePolicy policy = policy(Set.of(JAVA));
            SourceEvidencePolicy candidates = policy(Set.of(JAVA, link));
            JGitRepositoryAdapter adapter = new JGitRepositoryAdapter(new RepositoryProperties(), JdtLsTestProperties.linuxUid());
            List<GitSnapshotEntry> entries = adapter.prepareSnapshot(repositoryDirectory, revision, candidates).candidates();
            GitEvidencePublicationStore store = new GitEvidencePublicationStore(template);
            GitEvidencePublicationStore.PreparedSource prepared = source(template, store, revision, policy,
                    ProjectGuideMembership.unavailable(ProjectGuideState.ABSENT), entries);
            GitEvidencePublicationStore.PreparedSource previous = source(template, store, before, policy(Set.of()),
                    ProjectGuideMembership.unavailable(ProjectGuideState.ABSENT), List.of());
            GitPreparedComparison comparison = adapter.prepareComparison(repositoryDirectory, Optional.of(before), revision,
                    policy(Set.of()), candidates, Set.of(), Set.of());
            store.publishComparison(comparisonJob(before, revision), comparison, Instant.now(), GitEvidenceOwnership.standalone(),
                    Optional.of(previous), prepared);
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find().into(new ArrayList<>()))
                    .allSatisfy(file -> assertThat(file.getString("path")).isEqualTo(JAVA));
            assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find().into(new ArrayList<>()))
                    .allSatisfy(chunk -> assertThat(new String(chunk.get("bytes", Binary.class).getData(), StandardCharsets.UTF_8))
                            .isEqualTo("class Order {}\n"));
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_CHANGES).find().into(new ArrayList<>()))
                    .singleElement().satisfies(change -> assertThat(change.getString("newPath")).isEqualTo(JAVA));
            assertThat(template.getCollection(IndexCollections.GIT_COMPARISON_PATCHES).find().into(new ArrayList<>()))
                    .singleElement().satisfies(patch -> assertThat(patch.getString("patch"))
                            .contains("+class Order {}").doesNotContain(link, "target.txt", "SYMLINK_TARGET_MARKER"));
        }
    }

    private static void assertComparisonRejected(GitEvidencePublicationStore store, MongoTemplate template,
            GitPreparedComparison comparison, GitEvidencePublicationStore.PreparedSource previous, GitEvidencePublicationStore.PreparedSource source) {
        long ready = template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                .countDocuments(new Document("kind", "COMPARISON").append("state", "READY"));
        assertThatThrownBy(() -> store.publishComparison(comparisonJob(previous.snapshot().revision(), source.snapshot().revision()),
                comparison, Instant.now(), GitEvidenceOwnership.standalone(), Optional.of(previous), source))
                .isInstanceOf(PublicationConflictException.class);
        assertThat(template.getCollection(IndexCollections.GIT_EVIDENCE_MANIFESTS)
                .countDocuments(new Document("kind", "COMPARISON").append("state", "READY"))).isEqualTo(ready);
    }

    @Test
    void malformed_guide_does_not_consume_the_admitted_code_snapshot_budget() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            Files.createDirectories(repositoryDirectory.resolve(GUIDE).getParent());
            Files.writeString(repositoryDirectory.resolve(JAVA), "class Order {}\n");
            Files.writeString(repositoryDirectory.resolve(GUIDE), "INVALID_GUIDE_MARKER".repeat(12) + "invalid");
            RepositoryProperties properties = boundedGuideProperties();

            SourceSnapshotPublication.PublishedSource published = publishConfiguredGuide(template, git, properties);

            assertThat(published.guide().state()).isEqualTo(ProjectGuideState.INVALID);
            assertReadableCodeWithoutGuidePayload(template, published);
        }
    }

    @Test
    void configured_guide_directory_is_invalid_but_an_absent_tree_entry_is_absent() throws Exception {
        JdtLsTestProperties.prepareSafeCheckoutRoot(repositoryDirectory);
        try (MongoDBContainer container = MongoSchemaTestSupport.container();
                Git git = Git.init().setDirectory(repositoryDirectory.toFile()).call()) {
            MongoTemplate template = MongoSchemaTestSupport.template(container);
            new IndexSchemaBootstrap(template).bootstrap();
            Files.createDirectories(repositoryDirectory.resolve(JAVA).getParent());
            Files.createDirectories(repositoryDirectory.resolve(GUIDE));
            Files.writeString(repositoryDirectory.resolve(JAVA), "class Order {}\n");
            Files.writeString(repositoryDirectory.resolve(GUIDE).resolve("hidden.txt"), "INVALID_GUIDE_MARKER");
            RepositoryProperties properties = boundedGuideProperties();

            SourceSnapshotPublication.PublishedSource directory = publishConfiguredGuide(template, git, properties);

            assertThat(directory.guide().state()).isEqualTo(ProjectGuideState.INVALID);
            assertReadableCodeWithoutGuidePayload(template, directory);
            git.rm().addFilepattern(GUIDE + "/hidden.txt").call();

            SourceSnapshotPublication.PublishedSource absent = publishConfiguredGuide(template, git, properties);

            assertThat(absent.guide().state()).isEqualTo(ProjectGuideState.ABSENT);
            assertReadableCodeWithoutGuidePayload(template, absent);
        }
    }

    private static RepositoryProperties boundedGuideProperties() {
        RepositoryProperties properties = new RepositoryProperties();
        properties.setGitEvidenceFileTextBytes(256L);
        properties.setGitEvidenceSnapshotTextBytes(256L);
        return properties;
    }

    private SourceSnapshotPublication.PublishedSource publishConfiguredGuide(MongoTemplate template, Git git,
            RepositoryProperties properties) throws Exception {
        git.add().addFilepattern(".").call();
        RepositoryRevision revision = RepositoryRevision.ofSha(git.commit().setMessage("guide admission fixture")
                .setAuthor("Test", "test@example.test").setCommitter("Test", "test@example.test").call().name());
        FullIndexPlan plan = new FullIndexPlanner().plan(repositoryDirectory,
                List.of(repositoryDirectory.resolve("src/main/java")));
        IndexJob job = new IndexJob(IndexJobId.create(), REPOSITORY,
                Optional.of(new IndexJobTarget(revision, new GenerationId("source-" + revision.value()), 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
        return new SourceSnapshotPublication(new JGitRepositoryAdapter(properties, JdtLsTestProperties.linuxUid()),
                new GitEvidencePublicationStore(template, properties), properties.getGitEvidenceFileTextBytes())
                .publish(job, repositoryDirectory, revision, plan, Optional.of(GUIDE));
    }

    private static void assertReadableCodeWithoutGuidePayload(MongoTemplate template,
            SourceSnapshotPublication.PublishedSource source) {
        Document selection = new Document("repoId", REPOSITORY.value()).append("snapshotId", source.snapshot().snapshotId().value());
        assertThat(template.getCollection(IndexCollections.GIT_SNAPSHOT_FILES).find(selection).into(new ArrayList<>()))
                .singleElement().satisfies(file -> {
                    assertThat(file.getString("path")).isEqualTo(JAVA);
                    assertThat(file.getString("contentKind")).isEqualTo("CODE");
                });
        List<String> chunks = template.getCollection(IndexCollections.GIT_SNAPSHOT_CHUNKS).find(selection)
                .map(chunk -> new String(chunk.get("bytes", Binary.class).getData(), StandardCharsets.UTF_8))
                .into(new ArrayList<>());
        assertThat(chunks).containsExactly("class Order {}\n");
        assertThat(chunks).noneSatisfy(text -> assertThat(text).contains("INVALID_GUIDE_MARKER"));
    }

    private static SourceEvidencePolicy policy(Set<String> selected) {
        return new SourceEvidencePolicy(1, List.of("src/main/java", "src/main/resources/mapper"), selected, Optional.of(GUIDE));
    }

    private static ProjectGuideMembership guide(RepositoryRevision revision) throws Exception {
        String contents = "```json\n{\"formatVersion\":1,\"promptVersion\":1," +
                "\"repositoryId\":\"orders\",\"analyzedRevision\":\"" + "1".repeat(40) + "\"," +
                "\"generatedAt\":\"2026-09-28T12:30:00.123456789Z\",\"sourceScope\":{\"includedPaths\":[]," +
                "\"excludedPaths\":[],\"limitations\":[]}}\n```\nGuide\n";
        return new ProjectGuideMembership(ProjectGuideState.AVAILABLE, Optional.of(GUIDE),
                Optional.of(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents.getBytes(StandardCharsets.UTF_8)))),
                Optional.of(revision), Optional.of(new ProjectGuideProvenance(1, 1, REPOSITORY,
                        RepositoryRevision.ofSha("1".repeat(40)), Instant.parse("2026-09-28T12:30:00.123456789Z"),
                        new ProjectGuideProvenance.SourceScope(List.of(), List.of(), List.of()))), "NOT_VERIFIED");
    }

    private static GitEvidencePublicationStore.PreparedSource source(MongoTemplate template, GitEvidencePublicationStore store,
            RepositoryRevision revision, SourceEvidencePolicy policy, ProjectGuideMembership guide, List<GitSnapshotEntry> entries) {
        IndexJob job = new IndexJob(IndexJobId.create(), REPOSITORY,
                Optional.of(new IndexJobTarget(revision, new GenerationId("source-" + revision.value()), 1L)),
                IndexJobPhase.RUNNING, true, Optional.empty(), false, IndexJobOperation.BUILD);
        SourceSnapshotMembership membership = store.publishSourceSnapshot(job, revision, entries, policy, guide, Instant.now());
        GenerationId generationId = job.target().orElseThrow().generationId();
        template.getCollection(IndexCollections.GENERATION_MANIFESTS).insertOne(new Document("repoId", REPOSITORY.value())
                .append("generationId", generationId.value()).append("sourceRevision", revision.value())
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION).append("writeState", "SEALED_VALID")
                .append("sourceSnapshot", template.getConverter().convertToMongoType(membership))
                .append("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(policy)))
                .append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide))));
        return new GitEvidencePublicationStore.PreparedSource(policy, guide, membership, generationId);
    }

    private static IndexJob comparisonJob(RepositoryRevision before, RepositoryRevision after) {
        return new IndexJob(IndexJobId.create(), REPOSITORY, Optional.empty(), IndexJobPhase.RUNNING, true,
                Optional.empty(), false, IndexJobOperation.GIT_COMPARISON,
                Optional.of(GitEvidenceJob.comparison(before, after)));
    }
}
