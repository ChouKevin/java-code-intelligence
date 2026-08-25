package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedRelationContractIT extends PublishedMongoITSupport {

    @Test
    void returns_deterministically_paged_references_and_implementation_facts_from_the_current_generation() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_relations");
            seedCurrent(template, "orders");
            CodeFactIdentity declaration = methodIdentity("example.api", "Port", "handle", "src/main/java/example/api/Port.java");
            CodeFactIdentity alpha = methodIdentity("example.service", "AlphaService", "handle", "src/main/java/example/service/AlphaService.java");
            CodeFactIdentity beta = methodIdentity("example.service", "BetaService", "handle", "src/main/java/example/service/BetaService.java");
            seedMethod(template, declaration, List.of());
            seedMethod(template, alpha, List.of());
            seedMethod(template, beta, List.of());
            SourceRange alphaRange = range(alpha, 8);
            SourceRange betaRange = range(beta, 3);
            seedRelation(template, beta, RelationKind.REFERENCES, new RelationTarget.Internal(declaration), betaRange);
            seedRelation(template, alpha, RelationKind.REFERENCES, new RelationTarget.Internal(declaration), alphaRange);
            seedRelation(template, beta, RelationKind.IMPLEMENTS, new RelationTarget.Internal(declaration), betaRange);
            seedRelation(template, alpha, RelationKind.OVERRIDES, new RelationTarget.Internal(declaration), alphaRange);
            PublishedRelationQueryService service = new PublishedRelationQueryService(template, selector(template, policy()), Duration.ofSeconds(2));
            PublishedRelationQuery query = new PublishedRelationQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION), declaration, 0, 1);

            PublishedRelationResult references = service.findReferences(query);
            PublishedRelationResult implementations = service.findImplementations(new PublishedRelationQuery(
                    new RepositoryId("orders"), new RepositoryRevision(REVISION), declaration, 0, 20));

            assertThat(references.generation().generationId().value()).isEqualTo("g1");
            assertThat(references.relations()).extracting(relation -> relation.from().canonicalForm())
                    .containsExactly(alpha.canonicalForm());
            assertThat(references.page().totalCount()).isEqualTo(2);
            assertThat(references.page().hasMore()).isTrue();
            assertThat(implementations.relations()).extracting(relation -> relation.kind().name(), relation -> relation.from().canonicalForm())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("OVERRIDES", alpha.canonicalForm()),
                            org.assertj.core.groups.Tuple.tuple("IMPLEMENTS", beta.canonicalForm()));
        }
    }

    @Test
    void omits_forbidden_relation_sources_without_disclosing_them() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_relation_authorization");
            seedCurrent(template, "orders");
            CodeFactIdentity declaration = methodIdentity("example.api", "Port", "handle", "src/main/java/example/api/Port.java");
            CodeFactIdentity privateSource = methodIdentity("example.privatecode", "PrivateService", "handle",
                    "src/main/java/example/privatecode/PrivateService.java");
            seedMethod(template, declaration, List.of());
            seedMethod(template, privateSource, List.of());
            seedRelation(template, privateSource, RelationKind.REFERENCES, new RelationTarget.Internal(declaration), range(privateSource, 4));
            PublishedRelationQueryService service = new PublishedRelationQueryService(template,
                    selector(template, policy(new com.java.semantic.query.config.ReadPolicyProperties.PackageRule("orders", "example.privatecode"))),
                    Duration.ofSeconds(2));

            PublishedRelationResult result = service.findReferences(new PublishedRelationQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), declaration, 0, 20));

            assertThat(result.relations()).isEmpty();
            assertThat(result.page().totalCount()).isZero();
            assertThat(result.page().hasMore()).isFalse();
        }
    }

    @Test
    void fails_closed_before_disclosing_a_relation_with_inconsistent_flattened_fields() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_relation_contract");
            seedCurrent(template, "orders");
            CodeFactIdentity declaration = methodIdentity("example.api", "Port", "handle", "src/main/java/example/api/Port.java");
            CodeFactIdentity source = methodIdentity("example.service", "Service", "handle", "src/main/java/example/service/Service.java");
            seedMethod(template, declaration, List.of());
            seedMethod(template, source, List.of());
            seedRelation(template, source, RelationKind.REFERENCES, new RelationTarget.Internal(declaration), range(source, 4));
            template.getCollection("relations").updateOne(new Document("from", source.canonicalForm()),
                    new Document("$set", new Document("canonical", "forged-canonical")));
            PublishedRelationQueryService service = new PublishedRelationQueryService(template, selector(template, policy()), Duration.ofSeconds(2));

            assertThatThrownBy(() -> service.findReferences(new PublishedRelationQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), declaration, 0, 20)))
                    .isInstanceOf(IndexContractMismatchException.class)
                    .hasMessage("INDEX_CONTRACT_MISMATCH");
            template.getCollection("relations").updateOne(new Document("from", source.canonicalForm()),
                    new Document("$set", new Document("canonical", seedRelationCanonical(source, declaration))));
            Document stored = template.getCollection("relations").find().first();
            CurrentGeneration current = selector(template, policy()).selectCodeFact("orders", REVISION, declaration,
                    CurrentGenerationSelector.RELATIONS);

            for (Document mutation : List.of(
                    new Document("repoId", "billing"),
                    new Document("generationId", "g2"),
                    new Document("relationId", "forged-relation-id"),
                    new Document("canonical", "forged-canonical"),
                    new Document("from", "forged-from"),
                    new Document("target", "forged-target"),
                    new Document("kind", RelationKind.CALLS.name()),
                    new Document("sourcePath", "forged-source-path"))) {
                template.getCollection("relations").updateOne(new Document("_id", stored.get("_id")),
                        new Document("$set", mutation));
                Document mutated = template.getCollection("relations").find().first();

                assertThatThrownBy(() -> service.decode(mutated, current))
                        .isInstanceOf(IndexContractMismatchException.class)
                        .hasMessage("INDEX_CONTRACT_MISMATCH");

                String field = mutation.keySet().iterator().next();
                template.getCollection("relations").updateOne(new Document("_id", stored.get("_id")),
                        new Document("$set", new Document(field, stored.get(field))));
            }
        }
    }

    private static String seedRelationCanonical(CodeFactIdentity source, CodeFactIdentity declaration) {
        SourceRange range = range(source, 4);
        return new RelationIdentity(source, RelationKind.REFERENCES,
                new RelationTarget.Internal(declaration), range).canonicalForm();
    }

    private static SourceRange range(CodeFactIdentity identity, int line) {
        com.java.semantic.model.codefact.MethodTarget method = (com.java.semantic.model.codefact.MethodTarget) identity.canonicalIdentity();
        return new SourceRange(method.sourceFile(), new SyntaxRange(new SyntaxPosition(line, 1), new SyntaxPosition(line, 5)));
    }
}
