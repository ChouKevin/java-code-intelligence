package com.java.semantic.api;

import com.java.semantic.api.dto.GraphEdgeResponse;
import com.java.semantic.api.dto.GraphErrorResponse;
import com.java.semantic.api.dto.GraphNodeResponse;
import com.java.semantic.api.dto.GraphTraversalResponse;
import com.java.semantic.api.dto.GraphWarningResponse;
import com.java.semantic.api.dto.IncomingCallGraphResponse;
import com.java.semantic.api.dto.MethodTargetResponse;
import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.api.dto.PositionResponse;
import com.java.semantic.api.dto.SourceRangeResponse;
import com.java.semantic.callgraph.domain.CallSiteRange;
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
import com.java.semantic.identity.MethodTarget;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Maps the domain-only normalized fragment through the API-owned Task 3 response contract. */
@Component
public final class AnalysisResponseMapper {

    public OutgoingCallGraphResponse toResponse(OutgoingGraphFragment fragment) {
        Objects.requireNonNull(fragment, "fragment is required");
        return new OutgoingCallGraphResponse(
                status(fragment.status()),
                fragment.analyzedRevision().value(),
                fragment.rootNodeId().value(),
                traversal(fragment.traversal()),
                fragment.nodes().stream().map(this::node).toList(),
                fragment.edges().stream().map(this::edge).toList(),
                fragment.warnings().stream().map(this::warning).toList(),
                fragment.errors().stream().map(this::error).toList());
    }

    public IncomingCallGraphResponse toResponse(IncomingGraphFragment fragment) {
        Objects.requireNonNull(fragment, "fragment is required");
        return new IncomingCallGraphResponse(
                status(fragment.status()),
                fragment.analyzedRevision().value(),
                fragment.rootNodeId().value(),
                traversal(fragment.traversal()),
                fragment.nodes().stream().map(this::node).toList(),
                fragment.edges().stream().map(this::edge).toList(),
                fragment.warnings().stream().map(this::warning).toList(),
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

    private GraphNodeResponse node(GraphNode node) {
        return new GraphNodeResponse(
                node.nodeId().value(),
                node.target().map(this::target).orElse(null),
                NodeContentState.EXTERNAL.equals(node.contentState()) ? node.externalSymbol() : null,
                contentState(node.contentState()),
                traversalState(node.traversalState()),
                node.methodBody().orElse(null),
                node.declarationRange().map(this::range).orElse(null));
    }

    private GraphEdgeResponse edge(GraphEdge edge) {
        return new GraphEdgeResponse(
                edge.callerNodeId().value(),
                edge.calleeNodeId().value(),
                range(edge.callSite()),
                edge.callExpression(),
                resolutionStrategy(edge.resolutionStrategy()),
                ResolutionStrategyPartition.categoryOf(edge.resolutionStrategy()).name(),
                edge.confidence(),
                edge.evidence());
    }

    private GraphWarningResponse warning(GraphWarning warning) {
        return new GraphWarningResponse(
                warning.code(),
                warning.message(),
                warning.nodeId().value(),
                warning.callExpression().orElse(null),
                warning.callSite().map(this::range).orElse(null),
                warning.candidates().stream().map(this::target).toList());
    }

    private GraphErrorResponse error(GraphError error) {
        return new GraphErrorResponse(error.code(), error.message(), error.nodeId().value());
    }

    private MethodTargetResponse target(MethodTarget target) {
        return new MethodTargetResponse(
                target.sourceFile(),
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes());
    }

    private SourceRangeResponse range(CallSiteRange range) {
        return new SourceRangeResponse(
                range.sourceFile(),
                new PositionResponse(range.startLine(), range.startCharacter()),
                new PositionResponse(range.endLine(), range.endCharacter()));
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
}
