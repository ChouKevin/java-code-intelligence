package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.query.CurrentGeneration;
import com.java.semantic.model.query.PublishedCallGraphQuery;
import com.java.semantic.model.query.PublishedCallGraphResult;
import com.java.semantic.model.query.PublishedGraphDirection;
import com.java.semantic.model.query.PublishedGraphNode;
import com.mongodb.MongoException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Query boundary for graphs derived from published CALLS relations. */
public final class PublishedCallGraphService {
    private final MongoTemplate template;
    private final CurrentGenerationSelector generationSelector;
    private final Duration storageTimeout;

    public PublishedCallGraphService(MongoTemplate template, CurrentGenerationSelector generationSelector, Duration storageTimeout) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        this.generationSelector = Objects.requireNonNull(generationSelector, "generation selector is required");
        this.storageTimeout = Objects.requireNonNull(storageTimeout, "storage timeout is required");
    }

    public PublishedCallGraphResult outgoing(PublishedCallGraphQuery query) {
        return traverse(query, PublishedGraphDirection.OUTGOING);
    }

    public PublishedCallGraphResult incoming(PublishedCallGraphQuery query) {
        return traverse(query, PublishedGraphDirection.INCOMING);
    }

    private PublishedCallGraphResult traverse(PublishedCallGraphQuery query, PublishedGraphDirection direction) {
        PublishedCallGraphQuery request = Objects.requireNonNull(query, "call graph query is required");
        CodeFactIdentity rootIdentity = new CodeFactIdentity(request.repositoryId(), request.revision(), CodeFactKind.METHOD, request.root());
        CurrentGeneration current = generationSelector.selectCodeFact(request.repositoryId().value(), request.revision().value(), rootIdentity,
                CurrentGenerationSelector.RELATIONS);
        PublishedRelationQueryService relations = new PublishedRelationQueryService(template, generationSelector, storageTimeout);
        relations.ensureSymbol(current, rootIdentity);
        GraphState state = new GraphState(rootIdentity, request.depth(), request.depthTwoNodeBudget());
        List<RelationDocument> direct = readCalls(current, rootIdentity, direction, relations);
        state.addEdges(direct, direction, 1);
        if (request.depth() == 2) {
            List<CodeFactIdentity> frontier = state.frontier(direction);
            for (CodeFactIdentity identity : frontier) {
                List<RelationDocument> descendants = readCalls(current, identity, direction, relations);
                state.addEdges(descendants, direction, 2);
            }
            state.applyDepthTwoBudget();
        }
        return state.result(current, direction, request.root());
    }

    private List<RelationDocument> readCalls(CurrentGeneration current, CodeFactIdentity pivot, PublishedGraphDirection direction,
                                             PublishedRelationQueryService relations) {
        try {
            String field = PublishedGraphDirection.OUTGOING.equals(direction) ? "from" : "target";
            String value = PublishedGraphDirection.OUTGOING.equals(direction) ? pivot.canonicalForm()
                    : new RelationTarget.Internal(pivot).canonicalForm();
            Bson filter = Filters.and(Filters.eq("repoId", current.repositoryId().value()),
                    Filters.eq("generationId", current.generationId().value()), Filters.eq(field, value),
                    Filters.eq("kind", RelationKind.CALLS.name()));
            FindIterable<Document> rows = template.getCollection(IndexCollections.RELATIONS).find(filter)
                    .sort(Sorts.ascending("from", "target", "sourcePath", "relationId"))
                    .maxTime(storageTimeout.toMillis(), TimeUnit.MILLISECONDS);
            List<RelationDocument> calls = new ArrayList<>();
            for (Document row : rows) {
                RelationDocument relation = relations.decode(row);
                if (relations.isVisible(current, relation)) {
                    calls.add(relation);
                }
            }
            return calls.stream().sorted(GRAPH_EDGE_ORDER).toList();
        } catch (MongoException | DataAccessException exception) {
            throw new SemanticIndexUnavailableException(exception);
        }
    }

    private static final Comparator<RelationDocument> GRAPH_EDGE_ORDER = Comparator
            .comparing((RelationDocument relation) -> relation.from().canonicalForm())
            .thenComparing(relation -> relation.range().sourceFile())
            .thenComparingInt(relation -> relation.range().range().start().line())
            .thenComparingInt(relation -> relation.range().range().start().character())
            .thenComparing(relation -> relation.target().canonicalForm())
            .thenComparing(relation -> relation.fact().id().value());

    private static final class GraphState {
        private final CodeFactIdentity root;
        private final int requestedDepth;
        private final int budget;
        private final Map<String, NodeState> nodes = new LinkedHashMap<>();
        private final List<RelationDocument> edges = new ArrayList<>();

        private GraphState(CodeFactIdentity root, int requestedDepth, int budget) {
            this.root = root;
            this.requestedDepth = requestedDepth;
            this.budget = budget;
            nodes.put(new RelationTarget.Internal(root).canonicalForm(), new NodeState(new RelationTarget.Internal(root), 0, true));
        }

        private void addEdges(List<RelationDocument> relations, PublishedGraphDirection direction, int nodeDepth) {
            for (RelationDocument relation : relations) {
                edges.add(relation);
                RelationTarget discovered = discoveredTarget(relation, direction);
                nodes.compute(discovered.canonicalForm(), (ignored, existing) -> selectNode(existing, discovered, nodeDepth));
            }
        }

        private List<CodeFactIdentity> frontier(PublishedGraphDirection direction) {
            return nodes.values().stream().filter(node -> node.depth == 1)
                    .map(NodeState::target).filter(RelationTarget.Internal.class::isInstance)
                    .map(RelationTarget.Internal.class::cast).map(RelationTarget.Internal::identity)
                    .sorted(Comparator.comparing(CodeFactIdentity::canonicalForm)).toList();
        }

        private void applyDepthTwoBudget() {
            List<NodeState> depthTwo = nodes.values().stream().filter(node -> node.depth == 2)
                    .filter(node -> node.target instanceof RelationTarget.Internal)
                    .sorted(Comparator.comparing(node -> node.target.canonicalForm())).toList();
            for (int index = 0; index < depthTwo.size(); index++) {
                depthTwo.get(index).expanded = index < budget;
            }
        }

        private PublishedCallGraphResult result(CurrentGeneration generation, PublishedGraphDirection direction, MethodTarget rootTarget) {
            List<PublishedGraphNode> resultNodes = nodes.values().stream()
                    .sorted(Comparator.comparing(node -> node.target.canonicalForm()))
                    .map(node -> new PublishedGraphNode(node.target, node.depth, node.expanded)).toList();
            int expanded = (int) nodes.values().stream().filter(node -> node.depth == 2 && node.expanded).count();
            boolean budgetReached = nodes.values().stream().anyMatch(node -> node.depth == 2 && !node.expanded
                    && node.target instanceof RelationTarget.Internal);
            return new PublishedCallGraphResult(generation, direction, rootTarget, requestedDepth, budget, expanded, budgetReached,
                    resultNodes, List.copyOf(edges));
        }

        private static RelationTarget discoveredTarget(RelationDocument relation, PublishedGraphDirection direction) {
            if (PublishedGraphDirection.OUTGOING.equals(direction)) {
                return relation.target();
            }
            return new RelationTarget.Internal(relation.from());
        }

        private static NodeState selectNode(NodeState existing, RelationTarget target, int depth) {
            if (Objects.nonNull(existing) && existing.depth <= depth) {
                return existing;
            }
            return new NodeState(target, depth, depth < 2);
        }

        private static final class NodeState {
            private final RelationTarget target;
            private final int depth;
            private boolean expanded;

            private NodeState(RelationTarget target, int depth, boolean expanded) {
                this.target = target;
                this.depth = depth;
                this.expanded = expanded;
            }

            private RelationTarget target() {
                return target;
            }
        }
    }
}
