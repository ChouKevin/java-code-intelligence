package com.java.semantic.indexer.store;

import com.java.semantic.indexer.job.GitEvidenceJob;
import com.java.semantic.indexer.job.IndexJob;
import com.java.semantic.indexer.job.IndexJobId;
import com.java.semantic.indexer.job.IndexJobOperation;
import com.java.semantic.indexer.job.IndexJobPhase;
import com.java.semantic.indexer.job.IndexJobTarget;
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
