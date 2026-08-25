package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.query.PublishedRelationQuery;
import com.java.semantic.model.query.PublishedRelationResult;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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

    private static SourceRange range(CodeFactIdentity identity, int line) {
        com.java.semantic.model.codefact.MethodTarget method = (com.java.semantic.model.codefact.MethodTarget) identity.canonicalIdentity();
        return new SourceRange(method.sourceFile(), new SyntaxRange(new SyntaxPosition(line, 1), new SyntaxPosition(line, 5)));
    }
}
