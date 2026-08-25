package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.bson.Document;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedSourceToolContractIT extends PublishedMongoITSupport {
    @Test
    void slices_only_selected_generation_stored_source_with_utf16_crlf_boundaries() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(com.mongodb.client.MongoClients.create(container.getConnectionString()), "published_source");
            seedCurrent(template, "orders");
            String path = "src/main/java/example/video/VideoListener.java";
            String content = "a\r\n😀method\r\n";
            SourceTypeIdentity type = new SourceTypeIdentity(new com.java.semantic.model.codefact.JavaTypeIdentity("example.video", "VideoListener"), path);
            com.java.semantic.model.index.SourceArtifactDocument artifact = seedSource(template, path, content);
            seedType(template, type, artifact.id());
            CodeFactIdentity method = methodIdentity("example.video", "VideoListener", "onReady", path);
            seedMethod(template, method, List.of());
            PublishedSourceToolService service = new PublishedSourceToolService(new CurrentSourceQueryService(template, selector(template, policy()), Duration.ofSeconds(2)),
                    new CodeFactReadService(template, selector(template, policy()), Duration.ofSeconds(2)));

            assertThat(service.methodSource("orders", REVISION, method).content()).isEqualTo("😀method");
            SourceRange emojiAndMethod = new SourceRange(path, new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 8)));
            assertThat(service.sourceSegment(new SourceSegmentQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), type, emojiAndMethod)).content()).isEqualTo("😀method");
            SourceRange callerCrossLine = new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 2), new SyntaxPosition(1, 0)));
            assertThatThrownBy(() -> service.sourceSegment(new SourceSegmentQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), type, callerCrossLine))).isInstanceOf(IndexContractMismatchException.class);
            SourceRange callerCrLf = new SourceRange(path, new SyntaxRange(new SyntaxPosition(1, 8), new SyntaxPosition(1, 9)));
            assertThatThrownBy(() -> service.sourceSegment(new SourceSegmentQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), type, callerCrLf))).isInstanceOf(IndexContractMismatchException.class);
            Document malformedRange = new Document("sourceFile", path).append("range", new Document("start",
                    new Document("line", 0).append("character", 2)).append("end",
                    new Document("line", 1).append("character", 0)));
            template.getCollection("symbols").updateOne(new Document("symbolId", CodeFactId.from(method).value()),
                    new Document("$set", new Document("range", malformedRange)));
            assertThatThrownBy(() -> service.methodSource("orders", REVISION, method))
                    .isInstanceOf(IndexContractMismatchException.class);
            SourceRange invalid = new SourceRange(path, new SyntaxRange(new SyntaxPosition(9, 0), new SyntaxPosition(9, 1)));
            assertThatThrownBy(() -> service.sourceSegment(new SourceSegmentQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), type, invalid)))
                    .isInstanceOf(IndexContractMismatchException.class);
            String mapperPath = "src/main/resources/VideoMapper.xml";
            com.java.semantic.model.index.SourceArtifactDocument mapperArtifact = seedSource(template, mapperPath, "<select>");
            CodeFactIdentity mapper = seedMapper(template, mapperPath, mapperArtifact.id());
            com.java.semantic.model.codefact.PublishedSourceSegment mapperEvidence = service.evidenceSource("orders", REVISION, mapper);
            assertThat(mapperEvidence.content()).isEqualTo("<select>");
            assertThat(mapperEvidence.location().sourceFile()).isEqualTo(mapperPath);
            assertThat(mapperEvidence.location().range()).isEqualTo(new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 8)));
            assertThat(mapperEvidence.generation().repositoryId()).isEqualTo(new RepositoryId("orders"));
            assertThat(mapperEvidence.generation().revision()).isEqualTo(new RepositoryRevision(REVISION));
            assertThat(mapperEvidence.evidenceIdentity()).contains(mapper);
        }
    }

    @Test
    void rejects_denied_source_before_any_generation_or_artifact_reader_is_invoked() {
        try (MongoDBContainer container = new MongoDBContainer(DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_source_denied");
            seedCurrent(template, "orders");
            CopyOnWriteArrayList<String> reads = new CopyOnWriteArrayList<>();
            CommandListener listener = new CommandListener() {
                @Override
                public void commandStarted(CommandStartedEvent event) {
                    if ("find".equals(event.getCommandName()) && event.getCommand().containsKey("find")) {
                        reads.add(event.getCommand().getString("find").getValue());
                    }
                }
            };
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(container.getConnectionString()))
                    .addCommandListener(listener)
                    .build();
            try (MongoClient observedClient = MongoClients.create(settings)) {
                MongoTemplate observedTemplate = new MongoTemplate(observedClient, "published_source_denied");
                ConfiguredReadPolicy deniedPolicy = policy(new ReadPolicyProperties.PackageRule("orders", "example.video"));
                CurrentGenerationSelector deniedSelector = selector(observedTemplate, deniedPolicy);
                PublishedSourceToolService service = new PublishedSourceToolService(
                        new CurrentSourceQueryService(observedTemplate, deniedSelector, Duration.ofSeconds(2)),
                        new CodeFactReadService(observedTemplate, deniedSelector, Duration.ofSeconds(2)));
                String path = "src/main/java/example/video/VideoListener.java";
                SourceTypeIdentity type = new SourceTypeIdentity(
                        new com.java.semantic.model.codefact.JavaTypeIdentity("example.video", "VideoListener"), path);
                SourceRange range = new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));

                assertThatThrownBy(() -> service.sourceSegment(new SourceSegmentQuery(
                        new RepositoryId("orders"), new RepositoryRevision(REVISION), type, range)))
                        .isInstanceOf(RepositoryNotFoundException.class);
            }

            assertThat(reads).doesNotContain("repositories", "generation_manifests", "symbols", "generation_files", "source_artifacts");
        }
    }
}
