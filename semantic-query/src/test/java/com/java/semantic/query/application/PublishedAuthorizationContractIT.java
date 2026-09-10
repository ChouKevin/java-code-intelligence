package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactSearchQuery;
import com.java.semantic.model.codefact.DeclarationResolutionQuery;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceSegmentQuery;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.codefact.TypeMemberQuery;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                CurrentGenerationSelector deniedSelector = selector(template, deniedPolicy);
                CodeFactSearchService search = new CodeFactSearchService(template, deniedSelector, Duration.ofSeconds(2));
                PublishedDiscoveryQueryService discovery = new PublishedDiscoveryQueryService(template, deniedSelector, Duration.ofSeconds(2));
                SourceSliceService source = new SourceSliceService(
                        new CurrentSourceQueryService(template, deniedSelector, Duration.ofSeconds(2)),
                        new CodeFactReadService(template, deniedSelector, Duration.ofSeconds(2)));
                String path = "src/main/java/example/payment/PaymentService.java";
                SourceTypeIdentity type = new SourceTypeIdentity(new com.java.semantic.model.codefact.JavaTypeIdentity("example.payment", "PaymentService"), path);
                CodeFactIdentity method = methodIdentity("example.payment", "PaymentService", "pay", path);
                SourceRange range = new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1)));

                assertThatThrownBy(() -> search.search(new CodeFactSearchQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                        "payment", Set.of(), Optional.of("example.payment"), 0, 20))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> discovery.resolveDeclaration(new DeclarationResolutionQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, "pay", Optional.empty()))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> discovery.discoverTypeMembers(new TypeMemberQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, Set.of(com.java.semantic.model.codefact.CodeFactKind.METHOD), 0, 20)))
                        .isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.methodSource("orders", REVISION, method)).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.sourceSegment(new SourceSegmentQuery(new RepositoryId("orders"),
                        new RepositoryRevision(REVISION), type, range))).isInstanceOf(RepositoryNotFoundException.class);
                assertThatThrownBy(() -> source.evidenceSource("orders", REVISION, method)).isInstanceOf(RepositoryNotFoundException.class);
            }
            assertThat(reads).isEmpty();
        }
    }
}
