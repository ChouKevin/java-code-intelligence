package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.DeclarationResolutionQuery;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.TypeMemberQuery;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.query.SelectedGeneration;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import org.bson.Document;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.git.GitSnapshotId;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedAuthorizationContractIT extends PublishedMongoITSupport {
    @Test
    void rejects_known_denied_scopes_before_pointer_manifest_or_projection_reads() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            CopyOnWriteArrayList<String> reads = new CopyOnWriteArrayList<>();
            CommandListener listener = new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName())) {
                        reads.add(event.getCommand().getString("find").getValue());
                    }
                }
            };
            MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(listener).build();
            try (MongoClient client = MongoClients.create(settings)) {
                MongoTemplate template = new MongoTemplate(client, "published_authorization");
                ConfiguredReadPolicy deniedPolicy = policy(new ReadPolicyProperties.PackageRule("orders", "example.payment"));
                SelectedGenerationGuard deniedGuard = guard(template, deniedPolicy);
                SelectedGeneration context = new SelectedGeneration(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                        new GenerationId("g1"), new ManifestDigest(DIGEST));
                CodeFactSearchService search = new CodeFactSearchService(template, deniedGuard, Duration.ofSeconds(2));
                PublishedDiscoveryQueryService discovery = new PublishedDiscoveryQueryService(template, deniedGuard, Duration.ofSeconds(2));
                SourceSliceService source = new SourceSliceService(
                        new CurrentSourceQueryService(template, deniedGuard, Duration.ofSeconds(2)),
                        new CodeFactReadService(template, deniedGuard, Duration.ofSeconds(2)));
                String path = "src/main/java/example/payment/PaymentService.java";
                SourceTypeIdentity type = new SourceTypeIdentity(new com.java.semantic.model.codefact.JavaTypeIdentity("example.payment", "PaymentService"), path);
                CodeFactIdentity method = methodIdentity("example.payment", "PaymentService", "pay", path);
                SourceRange range = new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));
                assertThatThrownBy(() -> search.search(context, new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                        "payment", Set.of(), Optional.of("example.payment"), 0, 20))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> discovery.resolveDeclaration(context, new DeclarationResolutionQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, "pay", Optional.empty()))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> discovery.discoverTypeMembers(context, new TypeMemberQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, Set.of(com.java.semantic.model.codefact.CodeFactKind.METHOD), 0, 20)))
                        .isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.methodSource(context, method)).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.sourceSegment(context, new SourceSegmentQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, range))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.evidenceSource(context, method)).isInstanceOf(RepositoryNotFoundException.class);
            }
            assertThat(reads).isEmpty();
        }
    }

    @Test
    void reads_sealed_code_with_invalid_guide_but_rejects_a_reclassified_source_row() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_source_membership");
            seedCurrent(template, "orders");
            String javaPath = "src/main/java/example/payment/PaymentService.java";
            String mapperPath = "src/main/resources/PaymentMapper.xml";
            seedSource(template, javaPath, "class PaymentService {}");
            seedSource(template, mapperPath, """
                    <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                    <mapper namespace="example.payment.PaymentMapper"><select id="find">SELECT 1</select></mapper>
                    """);
            Document sealed = template.getCollection("generation_manifests").find(new Document("repoId", "orders")).first();
            SourceEvidencePolicy original = SourceEvidenceDocumentCodec.decodePolicy(
                    sealed.get("sourcePolicy", Document.class));
            SourceEvidencePolicy configuredGuide = new SourceEvidencePolicy(SourceEvidencePolicy.VERSION,
                    original.includedRoots(), original.selectedCodePaths(), Optional.of("docs/codebase/overview.md"));
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(configuredGuide)))
                            .append("sourceSnapshot", template.getConverter().convertToMongoType(new SourceSnapshotMembership(
                                    new GitSnapshotId(SOURCE_SNAPSHOT), new RepositoryRevision(REVISION),
                                    configuredGuide.fingerprint(), SOURCE_DIGEST)))));
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SOURCE_SNAPSHOT),
                    new Document("$set", new Document("policyFingerprint", configuredGuide.fingerprint())));
            template.getCollection("git_snapshot_files").updateMany(new Document("snapshotId", SOURCE_SNAPSHOT),
                    new Document("$set", new Document("policyFingerprint", configuredGuide.fingerprint())));
            Document invalidGuide = new Document(SourceEvidenceDocumentCodec.encodeGuide(
                    ProjectGuideMembership.unavailable(ProjectGuideState.INVALID)));
            template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders"),
                    new Document("$set", new Document("projectGuide", invalidGuide)));
            template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SOURCE_SNAPSHOT),
                    new Document("$set", new Document("projectGuide", invalidGuide)));
            ConfiguredReadPolicy visible = policy();
            CurrentSourceQueryService source = new CurrentSourceQueryService(template, guard(template, visible), Duration.ofSeconds(2));
            SelectedGeneration selected = selected(template, visible, "orders", REVISION, SelectedGenerationGuard.SOURCES);

            assertThat(source.getSource(selected, javaPath).utf8Content()).contains("PaymentService");
            assertThat(source.getSource(selected, mapperPath).utf8Content()).contains("<mapper");
            template.getCollection("git_snapshot_files").updateOne(new Document("snapshotId", SOURCE_SNAPSHOT).append("path", javaPath),
                    new Document("$set", new Document("contentKind", "PROJECT_GUIDE")));
            assertThatThrownBy(() -> source.getSource(selected, javaPath)).isInstanceOf(IndexContractMismatchException.class);
        }
    }
}
