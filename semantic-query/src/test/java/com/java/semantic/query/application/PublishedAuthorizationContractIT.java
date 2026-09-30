package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.assertj.core.api.Assertions.*;

@Tag("mongo-it")
class PublishedAuthorizationContractIT extends PublishedMongoITSupport {
    @Test
    void compact_fact_visibility_does_not_grant_whole_source_when_a_colocated_handler_is_forbidden() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders"); String path = "src/main/java/example/Shared.java"; seedSource(template, path, "class Shared {}\n");
            CodeFactIdentity visible = methodIdentity("example", "Shared", "visible", path);
            CodeFactIdentity hidden = methodIdentity("example.privatecode", "Hidden", "hidden", path);
            seedMethod(template, visible, List.of()); seedMethod(template, hidden, List.of()); seedSearch(template, visible, "SYMBOLS", List.of("visible"));
            // Even an incomplete-but-readable scope summary must not bypass the authoritative symbol gate.
            template.getCollection("generation_files").updateOne(new org.bson.Document("sourcePath", path),
                    new org.bson.Document("$set", new org.bson.Document("scopeUsable", true).append("scopePackages", List.of("example"))));
            ConfiguredReadPolicy policy = policy(new ReadPolicyProperties.PackageRule("orders", "example.privatecode"));
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy), Duration.ofSeconds(2));
            SelectedGenerationGuard.SourceContext source = admitted(template, policy, SelectedGenerationGuard.SEARCH_WITH_SOURCES).source();
            assertThat(reader.get(source, CodeFactId.from(visible)).fact().identity()).isEqualTo(visible);
            assertThatThrownBy(() -> reader.requireWholeSourceVisible(source, path)).isInstanceOf(RepositoryNotFoundException.class);
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(hidden))).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void denied_repository_is_rejected_even_when_its_storage_evidence_exists() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            ConfiguredReadPolicy denied = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of("orders"), List.of(), List.of(), List.of()));
            assertThatThrownBy(() -> admitted(template, denied, SelectedGenerationGuard.SEARCH_WITH_SOURCES)).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void normalized_forbidden_overload_and_mapper_namespace_method_cannot_use_exact_identity_lookup() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            String path = "src/main/java/example/Service.java"; seedSource(template, path, "class Service {}\n");
            CodeFactIdentity forbidden = methodIdentity("example", "Service", "handle", path);
            com.java.semantic.model.codefact.MethodTarget original = (com.java.semantic.model.codefact.MethodTarget) forbidden.canonicalIdentity();
            CodeFactIdentity otherOverload = new CodeFactIdentity(forbidden.repositoryId(), forbidden.repositoryRevision(), forbidden.kind(),
                    new com.java.semantic.model.codefact.MethodTarget(original.sourceType(), original.methodName(), List.of("java.lang.String")));
            seedMethod(template, forbidden, List.of()); seedMethod(template, otherOverload, List.of());
            String mapperPath = "src/main/resources/mapper/VideoMapper.xml";
            CodeFactIdentity mapper = seedMapper(template, mapperPath, seedSource(template, mapperPath, "<mapper/>").id(),
                    com.java.semantic.model.codefact.MapperStatementKind.SELECT);
            ConfiguredReadPolicy policy = new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of(
                    new ReadPolicyProperties.MethodRule("orders", "example", "Service", "handle", List.of("VideoReady")),
                    new ReadPolicyProperties.MethodRule("orders", "example.mapper", "VideoMapper", "find", List.of("String")))));
            SelectedGenerationGuard.SourceContext source = admitted(template, policy, SelectedGenerationGuard.SOURCES).source();
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy), Duration.ofSeconds(2));
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(forbidden))).isInstanceOf(RepositoryNotFoundException.class);
            assertThat(reader.getAllByIdentity(source, Set.of(otherOverload)).get(otherOverload).fact().identity()).isEqualTo(otherOverload);
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(mapper))).isInstanceOf(RepositoryNotFoundException.class);
        }
    }

    @Test
    void shared_content_hash_does_not_replace_selected_repository_file_membership() {
        try (PublishedMongoLifecycle lifecycle = PublishedMongoLifecycle.start(); PublishedMongoLifecycle.Invocation invocation = lifecycle.openInvocation()) {
            MongoTemplate template = invocation.template(); seedCurrent(template, "orders");
            String path = "src/main/java/example/Service.java"; seedSource(template, path, "class Service {}\n");
            CodeFactIdentity identity = methodIdentity("example", "Service", "handle", path); seedMethod(template, identity, List.of());
            CodeFactReadService reader = new CodeFactReadService(template, guard(template, policy()), Duration.ofSeconds(2));
            SelectedGenerationGuard.SourceContext source = admitted(template, policy(), SelectedGenerationGuard.SOURCES).source();
            template.getCollection("generation_files").updateOne(new org.bson.Document("sourcePath", path),
                    new org.bson.Document("$set", new org.bson.Document("repoId", "other")));
            assertThatThrownBy(() -> reader.getAllByIdentity(source, Set.of(identity))).isInstanceOf(CodeFactNotFoundException.class);
        }
    }
}
