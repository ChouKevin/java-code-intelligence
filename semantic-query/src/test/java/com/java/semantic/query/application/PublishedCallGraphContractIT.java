package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.query.PublishedCallGraphQuery;
import com.java.semantic.model.query.PublishedCallGraphResult;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mongo-it")
class PublishedCallGraphContractIT extends PublishedMongoITSupport {

    @Test
    void assembles_bounded_deterministic_outgoing_and_incoming_graphs_from_stored_calls() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_call_graphs");
            seedCurrent(template, "orders");
            CodeFactIdentity root = methodIdentity("example.graph", "Root", "run", "src/main/java/example/graph/Root.java");
            CodeFactIdentity middle = methodIdentity("example.graph", "Middle", "run", "src/main/java/example/graph/Middle.java");
            CodeFactIdentity firstLeaf = methodIdentity("example.graph", "FirstLeaf", "run", "src/main/java/example/graph/FirstLeaf.java");
            CodeFactIdentity secondLeaf = methodIdentity("example.graph", "SecondLeaf", "run", "src/main/java/example/graph/SecondLeaf.java");
            seedMethod(template, root, List.of());
            seedMethod(template, middle, List.of());
            seedMethod(template, firstLeaf, List.of());
            seedMethod(template, secondLeaf, List.of());
            seedRelation(template, root, RelationKind.CALLS, new RelationTarget.Internal(middle), range(root, 10));
            seedRelation(template, root, RelationKind.CALLS, new RelationTarget.Internal(middle), range(root, 12));
            seedRelation(template, root, RelationKind.CALLS, new RelationTarget.External(
                    new ExternalTarget.UnresolvedCall("client.send()", "client", "send", 0)), range(root, 14));
            seedRelation(template, middle, RelationKind.CALLS, new RelationTarget.Internal(root), range(middle, 6));
            seedRelation(template, middle, RelationKind.CALLS, new RelationTarget.Internal(secondLeaf), range(middle, 7));
            seedRelation(template, middle, RelationKind.CALLS, new RelationTarget.Internal(firstLeaf), range(middle, 9));
            PublishedCallGraphService service = new PublishedCallGraphService(template, selector(template, policy()), Duration.ofSeconds(2));
            PublishedCallGraphQuery query = new PublishedCallGraphQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    (com.java.semantic.model.codefact.MethodTarget) root.canonicalIdentity(), 2, 1);

            PublishedCallGraphResult outgoing = service.outgoing(query);
            PublishedCallGraphResult incoming = service.incoming(new PublishedCallGraphQuery(new RepositoryId("orders"),
                    new RepositoryRevision(REVISION), (com.java.semantic.model.codefact.MethodTarget) firstLeaf.canonicalIdentity(), 2, 1));

            assertThat(outgoing.edges()).hasSize(6);
            assertThat(outgoing.edges()).extracting(relation -> relation.range().range().start().line()).containsExactly(10, 12, 14, 6, 7, 9);
            assertThat(outgoing.nodes()).extracting(node -> node.target().canonicalForm()).contains(
                    new RelationTarget.External(new ExternalTarget.UnresolvedCall("client.send()", "client", "send", 0)).canonicalForm());
            assertThat(outgoing.nodes()).filteredOn(node -> node.target().canonicalForm().equals(new RelationTarget.Internal(root).canonicalForm()))
                    .singleElement().satisfies(node -> assertThat(node.depth()).isZero());
            assertThat(outgoing.expandedDepthTwoNodeCount()).isEqualTo(1);
            assertThat(outgoing.nodeBudgetReached()).isTrue();
            assertThat(outgoing.nodes()).filteredOn(node -> node.target().canonicalForm().equals(new RelationTarget.Internal(firstLeaf).canonicalForm()))
                    .singleElement().satisfies(node -> assertThat(node.expanded()).isTrue());
            assertThat(outgoing.nodes()).filteredOn(node -> node.target().canonicalForm().equals(new RelationTarget.Internal(secondLeaf).canonicalForm()))
                    .singleElement().satisfies(node -> assertThat(node.expanded()).isFalse());
            assertThat(incoming.edges()).extracting(relation -> relation.from().canonicalForm(), relation -> relation.target().canonicalForm())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(middle.canonicalForm(), new RelationTarget.Internal(firstLeaf).canonicalForm()),
                            org.assertj.core.groups.Tuple.tuple(root.canonicalForm(), new RelationTarget.Internal(middle).canonicalForm()),
                            org.assertj.core.groups.Tuple.tuple(root.canonicalForm(), new RelationTarget.Internal(middle).canonicalForm()));
        }
    }

    @Test
    void fails_closed_when_a_published_internal_call_endpoint_has_no_symbol() {
        try (org.testcontainers.mongodb.MongoDBContainer container = new org.testcontainers.mongodb.MongoDBContainer(
                org.testcontainers.utility.DockerImageName.parse("mongo:8.0.4"))) {
            container.start();
            MongoTemplate template = new MongoTemplate(MongoClients.create(container.getConnectionString()), "published_call_graph_integrity");
            seedCurrent(template, "orders");
            CodeFactIdentity root = methodIdentity("example.graph", "Root", "run", "src/main/java/example/graph/Root.java");
            CodeFactIdentity missingTarget = methodIdentity("example.graph", "MissingTarget", "run", "src/main/java/example/graph/MissingTarget.java");
            CodeFactIdentity missingSource = methodIdentity("example.graph", "MissingSource", "run", "src/main/java/example/graph/MissingSource.java");
            seedMethod(template, root, List.of());
            seedRelation(template, root, RelationKind.CALLS, new RelationTarget.Internal(missingTarget), range(root, 10));
            seedRelation(template, missingSource, RelationKind.CALLS, new RelationTarget.Internal(root), range(missingSource, 11));
            PublishedCallGraphService service = new PublishedCallGraphService(template, selector(template, policy()), Duration.ofSeconds(2));
            PublishedCallGraphQuery query = new PublishedCallGraphQuery(new RepositoryId("orders"), new RepositoryRevision(REVISION),
                    (com.java.semantic.model.codefact.MethodTarget) root.canonicalIdentity(), 1, 0);

            assertThatThrownBy(() -> service.outgoing(query)).isInstanceOf(IndexContractMismatchException.class)
                    .hasMessage("INDEX_CONTRACT_MISMATCH");
            assertThatThrownBy(() -> service.incoming(query)).isInstanceOf(IndexContractMismatchException.class)
                    .hasMessage("INDEX_CONTRACT_MISMATCH");
        }
    }

    private static SourceRange range(CodeFactIdentity identity, int line) {
        com.java.semantic.model.codefact.MethodTarget method = (com.java.semantic.model.codefact.MethodTarget) identity.canonicalIdentity();
        return new SourceRange(method.sourceFile(), new SyntaxRange(new SyntaxPosition(line, 1), new SyntaxPosition(line, 5)));
    }
}
