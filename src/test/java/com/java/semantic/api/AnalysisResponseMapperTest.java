package com.java.semantic.api;

import com.java.semantic.api.dto.OutgoingCallGraphResponse;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.EvidenceSourceIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementIdentityPayload;
import com.java.semantic.api.dto.identity.MapperStatementKeyPayload;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.MapperEvidenceRepresentation;
import com.java.semantic.syntax.domain.MapperStatementIdentity;
import com.java.semantic.syntax.domain.MapperStatementKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AnalysisResponseMapper} 之單元測試,聚焦解析策略對外部分類的映射 */
class AnalysisResponseMapperTest {

    private static final MethodTarget TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        "src/main/java/com/acme/OrderService.java"),
                "place",
                List.of());

    private final AnalysisResponseMapper mapper = new AnalysisResponseMapper(
            sourceLocationMapper(),
            new DiscoveryFollowUpFactory(),
            followUpMapper());

    @Test
    void should_offer_only_method_source_for_internal_graph_nodes_without_reading_graph_source() {
        CallNodeId localNode = new CallNodeId("node-local");
        CallNodeId externalNode = new CallNodeId("node-external");
        OutgoingGraphFragment fragment = new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                RepositoryRevision.fixture(),
                localNode,
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                List.of(
                        new GraphNode(
                                localNode,
                                Optional.of(TARGET),
                                "",
                                NodeContentState.TARGET_ONLY,
                                NodeTraversalState.BUDGET_CUTOFF,
                                DispatchKind.SYNCHRONOUS,
                                Optional.empty()),
                        new GraphNode(
                                externalNode,
                                Optional.empty(),
                                "java.util.List#size",
                                NodeContentState.EXTERNAL,
                                NodeTraversalState.EXTERNAL,
                                DispatchKind.SYNCHRONOUS,
                                Optional.empty())),
                List.of(),
                List.of(),
                List.of());

        OutgoingCallGraphResponse response = mapper.toResponse(RepositoryId.of("orders"), fragment);

        assertThat(response.nodes().get(0).availableFollowUps())
                .extracting(followUp -> followUp.operation())
                .containsExactly("GET_METHOD_SOURCE");
        assertThat(response.nodes().get(0).availableFollowUps().getFirst().request())
                .isInstanceOf(DiscoveryFollowUpResponse.GetMethodSourceRequestResponse.class);
        assertThat(response.nodes().get(1).availableFollowUps()).isEmpty();
    }

    @Test
    void should_map_resolution_strategy_to_its_resolution_category() {
        CallNodeId root = new CallNodeId("node-root");
        CallNodeId lombokCaller = new CallNodeId("node-lombok-caller");
        CallNodeId jdtCaller = new CallNodeId("node-jdt-caller");
        CallSiteRange callSite = new CallSiteRange(TARGET.sourceFile(), 12, 3, 12, 15);

        OutgoingGraphFragment fragment = new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                RepositoryRevision.fixture(),
                root,
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                List.of(new GraphNode(
                        root,
                        Optional.of(TARGET),
                        "",
                        NodeContentState.TARGET_ONLY,
                        NodeTraversalState.BUDGET_CUTOFF,
                        DispatchKind.SYNCHRONOUS,
                        Optional.empty())),
                List.of(
                        new GraphEdge(
                                lombokCaller,
                                root,
                                callSite,
                                "place(orderId)",
                                ResolutionStrategy.LOMBOK_GENERATED,
                                List.of("lombok-generated-accessor"),
                                List.of()),
                        new GraphEdge(
                                jdtCaller,
                                root,
                                callSite,
                                "place(orderId)",
                                ResolutionStrategy.JDT_CALL_HIERARCHY,
                                List.of("jdt-call-hierarchy"),
                                List.of())),
                List.of(),
                List.of());

        OutgoingCallGraphResponse response = mapper.toResponse(RepositoryId.of("orders"), fragment);

        assertThat(response.edges()).hasSize(2);
        assertThat(response.edges().get(0).resolutionStrategy()).isEqualTo("LOMBOK_GENERATED");
        assertThat(response.edges().get(0).category()).isEqualTo("RESOLVED_OPAQUE");
        assertThat(response.edges().get(1).resolutionStrategy()).isEqualTo("JDT_CALL_HIERARCHY");
        assertThat(response.edges().get(1).category()).isEqualTo("RESOLVED_ANALYZABLE");
        assertThat(response.nodes().get(0).dispatchKind()).isEqualTo("SYNCHRONOUS");
    }

    @Test
    void should_map_xml_mapper_edge_to_one_typed_evidence_source_follow_up() {
        CallNodeId root = new CallNodeId("node-root");
        MapperStatementIdentity identity = new MapperStatementIdentity(
                new MapperStatementKey("com.acme.OrderMapper", "findOrder"),
                "src/main/resources/mapper/OrderMapper.xml",
                Optional.of("postgres"),
                3,
                MapperEvidenceRepresentation.MAPPER_XML_ELEMENT);
        OutgoingGraphFragment fragment = new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                RepositoryRevision.fixture(),
                root,
                new GraphTraversal(1, 0, 40, true, GraphLimitReason.NONE),
                List.of(new GraphNode(
                        root,
                        Optional.of(TARGET),
                        "",
                        NodeContentState.TARGET_ONLY,
                        NodeTraversalState.BUDGET_CUTOFF,
                        DispatchKind.SYNCHRONOUS,
                        Optional.empty())),
                List.of(new GraphEdge(
                        root,
                        new CallNodeId("node-mapper"),
                        new CallSiteRange(TARGET.sourceFile(), 12, 3, 12, 15),
                        "findOrder(orderId)",
                        ResolutionStrategy.MYBATIS_MAPPER,
                        List.of("mapper evidence"),
                        List.of(identity))),
                List.of(),
                List.of());

        OutgoingCallGraphResponse response = mapper.toResponse(RepositoryId.of("orders"), fragment);

        assertThat(response.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.availableFollowUps()).singleElement().satisfies(followUp -> {
                assertThat(followUp.operation()).isEqualTo("GET_EVIDENCE_SOURCE");
                assertThat(followUp.api().method()).isEqualTo("POST");
                assertThat(followUp.api().path()).isEqualTo("/v1/discovery/evidence-source");
                assertThat(followUp.api().operationId()).isEqualTo("getEvidenceSource");
                assertThat(followUp.request())
                        .isInstanceOf(DiscoveryFollowUpResponse.GetEvidenceSourceRequestResponse.class);
                DiscoveryFollowUpResponse.GetEvidenceSourceRequestResponse request =
                        (DiscoveryFollowUpResponse.GetEvidenceSourceRequestResponse) followUp.request();
                assertThat(request.repoId()).isEqualTo("orders");
                assertThat(request.expectedRevision()).isEqualTo(fragment.analyzedRevision().value());
                assertThat(request.identity()).isEqualTo(new EvidenceSourceIdentityPayload(
                        "MAPPER_STATEMENT",
                        Optional.of(new MapperStatementIdentityPayload(
                                new MapperStatementKeyPayload("com.acme.OrderMapper", "findOrder"),
                                "src/main/resources/mapper/OrderMapper.xml",
                                Optional.of("postgres"),
                                3,
                                "MAPPER_XML_ELEMENT")),
                        Optional.empty()));
            });
        });
    }

    @Test
    void should_offer_exact_java_range_read_for_graph_warning_call_site() {
        CallNodeId root = new CallNodeId("node-root");
        CallSiteRange callSite = new CallSiteRange(TARGET.sourceFile(), 12, 3, 12, 15);
        OutgoingGraphFragment fragment = new OutgoingGraphFragment(
                GraphAnalysisStatus.PARTIAL,
                RepositoryRevision.fixture(),
                root,
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                List.of(new GraphNode(
                        root,
                        Optional.of(TARGET),
                        "",
                        NodeContentState.TARGET_ONLY,
                        NodeTraversalState.BUDGET_CUTOFF,
                        DispatchKind.SYNCHRONOUS,
                        Optional.empty())),
                List.of(),
                List.of(new GraphWarning(
                        "DESCENDANT_CALL_UNRESOLVED",
                        "unresolved",
                        root,
                        Optional.of("place(orderId)"),
                        Optional.of(callSite),
                        List.of())),
                List.of());

        OutgoingCallGraphResponse response = mapper.toResponse(RepositoryId.of("orders"), fragment);

        assertThat(response.warnings().getFirst().availableFollowUps()).singleElement().satisfies(followUp -> {
            assertThat(followUp.operation()).isEqualTo("GET_SOURCE_SEGMENT");
            assertThat(followUp.request())
                    .isInstanceOf(DiscoveryFollowUpResponse.GetSourceSegmentRequestResponse.class);
            DiscoveryFollowUpResponse.GetSourceSegmentRequestResponse request =
                    (DiscoveryFollowUpResponse.GetSourceSegmentRequestResponse) followUp.request();
            assertThat(request.location().sourceFile()).isEqualTo(TARGET.sourceFile());
            assertThat(request.contextLines()).isZero();
        });
    }

    private static SourceLocationHttpMapper sourceLocationMapper() {
        return new SourceLocationHttpMapper();
    }

    private static StructuredDiscoveryResponseMapper followUpMapper() {
        SourceLocationHttpMapper sourceLocationMapper = sourceLocationMapper();
        MapperIdentityHttpMapper mapperIdentityHttpMapper = new MapperIdentityHttpMapper();
        return new StructuredDiscoveryResponseMapper(
                new DiscoveryFollowUpFactory(),
                new ConceptIdentityHttpMapper(sourceLocationMapper, mapperIdentityHttpMapper),
                mapperIdentityHttpMapper,
                sourceLocationMapper,
                new ExactSourceDeclarationTargetHttpMapper(sourceLocationMapper));
    }
}
