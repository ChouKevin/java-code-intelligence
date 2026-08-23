package com.java.semantic.api;

import com.java.semantic.api.dto.GraphEdgeResponse;
import com.java.semantic.api.dto.GraphErrorResponse;
import com.java.semantic.api.dto.GraphNodeResponse;
import com.java.semantic.api.dto.GraphTraversalResponse;
import com.java.semantic.api.dto.GraphWarningResponse;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphError;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.callgraph.domain.ResolutionStrategyPartition;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.List;

/** Maps the domain-only normalized fragment through the API-owned Task 3 response contract. */
@Component
public final class AnalysisResponseMapper {

    private final SourceLocationHttpMapper sourceLocationMapper;
    private final DiscoveryFollowUpFactory followUpFactory;
    private final StructuredDiscoveryResponseMapper followUpMapper;

    @Autowired
    public AnalysisResponseMapper(
            SourceLocationHttpMapper sourceLocationMapper,
            DiscoveryFollowUpFactory followUpFactory,
            StructuredDiscoveryResponseMapper followUpMapper) {
        this.sourceLocationMapper = Objects.requireNonNull(sourceLocationMapper, "sourceLocationMapper is required");
        this.followUpFactory = Objects.requireNonNull(followUpFactory, "followUpFactory is required");
        this.followUpMapper = Objects.requireNonNull(followUpMapper, "followUpMapper is required");
    }

    public AnalysisResponseMapper(SourceLocationHttpMapper sourceLocationMapper) {
        this(sourceLocationMapper, new DiscoveryFollowUpFactory(), followUpMapper(sourceLocationMapper));
    }

    private static StructuredDiscoveryResponseMapper followUpMapper(SourceLocationHttpMapper sourceLocationMapper) {
        MapperIdentityHttpMapper mapperIdentityHttpMapper = new MapperIdentityHttpMapper();
        return new StructuredDiscoveryResponseMapper(
                new DiscoveryFollowUpFactory(),
                new ConceptIdentityHttpMapper(sourceLocationMapper, mapperIdentityHttpMapper),
                mapperIdentityHttpMapper,
                sourceLocationMapper,
                new ExactSourceDeclarationTargetHttpMapper(sourceLocationMapper));
    }

    public OutgoingCallGraphResponse toResponse(RepositoryId repositoryId, OutgoingGraphFragment fragment) {
        Objects.requireNonNull(fragment, "fragment is required");
        RepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        return new OutgoingCallGraphResponse(
                status(fragment.status()),
                fragment.analyzedRevision().value(),
                fragment.rootNodeId().value(),
                traversal(fragment.traversal()),
                fragment.nodes().stream().map(node -> node(requiredRepositoryId, fragment.analyzedRevision(), node)).toList(),
                fragment.edges().stream().map(this::edge).toList(),
                fragment.warnings().stream().map(warning -> warning(requiredRepositoryId, fragment.analyzedRevision(), warning)).toList(),
                fragment.errors().stream().map(this::error).toList());
    }

    public IncomingCallGraphResponse toResponse(RepositoryId repositoryId, IncomingGraphFragment fragment) {
        Objects.requireNonNull(fragment, "fragment is required");
        RepositoryId requiredRepositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        return new IncomingCallGraphResponse(
                status(fragment.status()),
                fragment.analyzedRevision().value(),
                fragment.rootNodeId().value(),
                traversal(fragment.traversal()),
                fragment.nodes().stream().map(node -> node(requiredRepositoryId, fragment.analyzedRevision(), node)).toList(),
                fragment.edges().stream().map(this::edge).toList(),
                fragment.warnings().stream().map(warning -> warning(requiredRepositoryId, fragment.analyzedRevision(), warning)).toList(),
                fragment.errors().stream().map(this::error).toList());
    }

    private String status(GraphAnalysisStatus status) {
        return switch (status) {
            case SUCCESS -> "SUCCESS";
            case PARTIAL -> "PARTIAL";
        };
    }

    private GraphTraversalResponse traversal(GraphTraversal traversal) {
        return new GraphTraversalResponse(
                traversal.requestedDepth(),
                traversal.expandedNodeCount(),
                traversal.nodeBudget(),
                traversal.rootDirectCallsComplete(),
                limitReason(traversal.limitReason()));
    }

    private GraphNodeResponse node(RepositoryId repositoryId, RepositoryRevision revision, GraphNode node) {
        List<DiscoveryFollowUp> followUps = node.target()
                .filter(target -> !NodeContentState.EXTERNAL.equals(node.contentState()))
                .map(target -> followUpFactory.methodSourceOnly(repositoryId, revision, target))
                .orElseGet(List::of);
        return new GraphNodeResponse(
                node.nodeId().value(),
                node.target().map(JavaSourceIdentityHttpMapper::toPayload).orElse(null),
                NodeContentState.EXTERNAL.equals(node.contentState()) ? node.externalSymbol() : null,
                contentState(node.contentState()),
                traversalState(node.traversalState()),
                dispatchKind(node.dispatchKind()),
                node.declarationRange().map(sourceLocationMapper::toTextRange).orElse(null),
                followUps.stream().map(followUpMapper::followUp).toList());
    }

    private GraphEdgeResponse edge(GraphEdge edge) {
        return new GraphEdgeResponse(
                edge.callerNodeId().value(),
                edge.calleeNodeId().value(),
                sourceLocationMapper.toSourceRange(edge.callSite()),
                edge.callExpression(),
                resolutionStrategy(edge.resolutionStrategy()),
                ResolutionStrategyPartition.categoryOf(edge.resolutionStrategy()).name(),
                edge.evidence());
    }

    private GraphWarningResponse warning(RepositoryId repositoryId, RepositoryRevision revision, GraphWarning warning) {
        List<DiscoveryFollowUp> followUps = warning.callSite()
                .map(callSite -> followUpFactory.forSourceSegment(
                        repositoryId,
                        revision,
                        new SourceRange(
                                callSite.sourceFile(),
                                new SyntaxRange(
                                        new SyntaxPosition(callSite.startLine(), callSite.startCharacter()),
                                        new SyntaxPosition(callSite.endLine(), callSite.endCharacter()))),
                        0))
                .stream()
                .toList();
        return new GraphWarningResponse(
                warning.code(),
                warning.message(),
                warning.nodeId().value(),
                warning.callExpression().orElse(null),
                warning.callSite().map(sourceLocationMapper::toSourceRange).orElse(null),
                warning.candidates().stream().map(JavaSourceIdentityHttpMapper::toPayload).toList(),
                followUps.stream().map(followUpMapper::followUp).toList());
    }

    private GraphErrorResponse error(GraphError error) {
        return new GraphErrorResponse(error.code(), error.message(), error.nodeId().value());
    }

    private String limitReason(GraphLimitReason value) {
        return switch (value) {
            case NONE -> "NONE";
            case NODE_BUDGET -> "NODE_BUDGET";
        };
    }

    private String contentState(NodeContentState value) {
        return switch (value) {
            case FULL_SOURCE -> "FULL_SOURCE";
            case TARGET_ONLY -> "TARGET_ONLY";
            case EXTERNAL -> "EXTERNAL";
        };
    }

    private String traversalState(NodeTraversalState value) {
        return switch (value) {
            case EXPANDED -> "EXPANDED";
            case DEPTH_BOUNDARY -> "DEPTH_BOUNDARY";
            case BUDGET_CUTOFF -> "BUDGET_CUTOFF";
            case OPAQUE -> "OPAQUE";
            case EXTERNAL -> "EXTERNAL";
        };
    }

    private String resolutionStrategy(ResolutionStrategy value) {
        return switch (value) {
            case JDT_CALL_HIERARCHY -> "JDT_CALL_HIERARCHY";
            case JDT_DEFINITION_FALLBACK -> "JDT_DEFINITION_FALLBACK";
            case SPRING_BEAN_BY_QUALIFIER -> "SPRING_BEAN_BY_QUALIFIER";
            case SPRING_BEAN_BY_PRIMARY -> "SPRING_BEAN_BY_PRIMARY";
            case SPRING_SINGLE_IMPLEMENTATION -> "SPRING_SINGLE_IMPLEMENTATION";
            case MYBATIS_MAPPER -> "MYBATIS_MAPPER";
            case SPRING_DATA_REPOSITORY -> "SPRING_DATA_REPOSITORY";
            case LOMBOK_GENERATED -> "LOMBOK_GENERATED";
            case EXTERNAL_LIBRARY -> "EXTERNAL_LIBRARY";
            case FEIGN_CLIENT -> "FEIGN_CLIENT";
            case BUSINESS_READ_FORBIDDEN -> "BUSINESS_READ_FORBIDDEN";
            case SPRING_MULTIPLE_CANDIDATES -> "SPRING_MULTIPLE_CANDIDATES";
            case DATA_ACCESS_WITHOUT_EVIDENCE -> "DATA_ACCESS_WITHOUT_EVIDENCE";
        };
    }

    private String dispatchKind(DispatchKind value) {
        return switch (value) {
            case SYNCHRONOUS -> "SYNCHRONOUS";
            case ASYNC -> "ASYNC";
        };
    }
}
