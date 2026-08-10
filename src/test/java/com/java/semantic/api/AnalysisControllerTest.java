package com.java.semantic.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.DispatchKind;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphEdge;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.GraphWarning;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.callgraph.domain.ResolutionStrategy;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import com.java.semantic.syntax.application.AnnotationMatchKind;
import com.java.semantic.syntax.application.CandidatePage;
import com.java.semantic.syntax.application.EventListenerCandidate;
import com.java.semantic.syntax.application.EventListenerDiscoveryApplicationService;
import com.java.semantic.syntax.application.EventListenerDiscoveryPage;
import com.java.semantic.syntax.application.ListenerAnnotationEvidence;
import com.java.semantic.syntax.application.ListenerAnnotationKind;
import com.java.semantic.syntax.application.RevisionBoundEventListenerDiscovery;
import com.java.semantic.syntax.domain.SourceRange;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "semantic.api.api-token=test-token")
@AutoConfigureMockMvc
class AnalysisControllerTest {

    private static final String TOKEN = "test-token";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final MethodTarget TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        "src/main/java/com/acme/OrderService.java"),
                "place",
                List.of());
    private static final MethodTarget CALLER_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "CheckoutService"),
                        "src/main/java/com/acme/CheckoutService.java"),
                "checkout",
                List.of());
    private static final MethodTarget DECLARATION_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderPort"),
                        "src/main/java/com/acme/OrderPort.java"),
                "place",
                List.of("com.acme.OrderRequest"));
    private static final MethodTarget AMBIGUOUS_ALPHA = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "AlphaOrderService"),
                        "src/main/java/com/acme/AlphaOrderService.java"),
                "place",
                List.of());
    private static final MethodTarget AMBIGUOUS_ZETA = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "ZetaOrderService"),
                        "src/main/java/com/acme/ZetaOrderService.java"),
                "place",
                List.of());
    private static final MethodTarget DISCOVERED_TARGET = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderListener"),
                        "src/main/java/com/acme/OrderListener.java"),
                "onOrder",
                List.of("com.acme.OrderPlaced"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticAnalysisApplicationService semanticAnalysisApplicationService;

    @MockitoBean
    private EventListenerDiscoveryApplicationService eventListenerDiscoveryApplicationService;

    @Test
    void should_map_only_the_outgoing_graph_contract() throws Exception {
        given(semanticAnalysisApplicationService.analyzeOutgoing(
                eq(RepositoryId.of("orders")), eq(REVISION), eq(TARGET), eq(2))).willReturn(fragment());

        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "depth":2,
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rootNodeId").value("node-0000"))
                .andExpect(jsonPath("$.nodes[0].target.sourceType.sourceFile").value(TARGET.sourceFile()))
                .andExpect(jsonPath("$.nodes[0].target.sourceType.javaType.packageName")
                        .value(TARGET.packageName()))
                .andExpect(jsonPath("$.nodes[0].target.sourceType.javaType.className")
                        .value(TARGET.className()))
                .andExpect(jsonPath("$.nodes[0].target.methodName").value(TARGET.methodName()))
                .andExpect(jsonPath("$.nodes[0].target.parameterTypes").isEmpty())
                .andExpect(jsonPath("$.traversal.nodeBudget").value(40));

        then(semanticAnalysisApplicationService).should().analyzeOutgoing(
                RepositoryId.of("orders"), REVISION, TARGET, 2);
    }

    @Test
    void should_map_the_incoming_graph_contract() throws Exception {
        given(semanticAnalysisApplicationService.analyzeIncoming(
                eq(RepositoryId.of("orders")), eq(REVISION), eq(TARGET), eq(2))).willReturn(incomingFragment());

        mockMvc.perform(post("/v1/analyses/call-graphs/incoming")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "depth":2,
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.rootNodeId").value("node-0000"))
                .andExpect(jsonPath("$.nodes[0].target.sourceType.sourceFile").value(TARGET.sourceFile()))
                .andExpect(jsonPath("$.traversal.nodeBudget").value(40))
                .andExpect(jsonPath("$.edges[0].callerNodeId").value("node-caller"))
                .andExpect(jsonPath("$.edges[0].calleeNodeId").value("node-0000"))
                .andExpect(jsonPath("$.edges[0].callSite.sourceFile").value(CALLER_TARGET.sourceFile()))
                .andExpect(jsonPath("$.edges[0].callSite.range.start.line").value(12))
                .andExpect(jsonPath("$.edges[0].callSite.range.start.character").value(3))
                .andExpect(jsonPath("$.edges[0].callSite.range.end.line").value(12))
                .andExpect(jsonPath("$.edges[0].callSite.range.end.character").value(15))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].operation")
                        .value("DISCOVER_METHOD_IMPLEMENTATIONS"))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].api.method").value("POST"))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].api.path")
                        .value("/v1/discovery/method-implementations"))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].api.operationId")
                        .value("discoverMethodImplementations"))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.repoId").value("orders"))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.expectedRevision")
                        .value(REVISION.value()))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.declarationTarget.sourceType.sourceFile")
                        .value(DECLARATION_TARGET.sourceFile()))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.declarationTarget.sourceType.javaType.packageName")
                        .value(DECLARATION_TARGET.packageName()))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.declarationTarget.sourceType.javaType.className")
                        .value(DECLARATION_TARGET.className()))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.declarationTarget.methodName")
                        .value(DECLARATION_TARGET.methodName()))
                .andExpect(jsonPath("$.edges[0].availableFollowUps[0].request.declarationTarget.parameterTypes[0]")
                        .value("com.acme.OrderRequest"))
                .andExpect(jsonPath("$.warnings[0].code").value("DESCENDANT_CALL_AMBIGUOUS"))
                .andExpect(jsonPath("$.warnings[0].candidates[0].sourceType.sourceFile")
                        .value(AMBIGUOUS_ALPHA.sourceFile()))
                .andExpect(jsonPath("$.warnings[0].candidates[1].sourceType.sourceFile")
                        .value(AMBIGUOUS_ZETA.sourceFile()));

        then(semanticAnalysisApplicationService).should().analyzeIncoming(
                RepositoryId.of("orders"), REVISION, TARGET, 2);
    }

    @Test
    void should_reject_an_invalid_incoming_request_before_analysis() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graphs/incoming")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "depth":3,
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_client_node_budget() throws Exception {
        given(semanticAnalysisApplicationService.analyzeOutgoing(any(), any(), any(), eq(2))).willReturn(fragment());
        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "depth":2,
                                  "nodeBudget":1,
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_unknown_target_properties() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "depth":2,
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[],
                                    "nodeBudget":1
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));
        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_forged_target_identifiers_before_analysis() throws Exception {
        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "target":{
                                    "sourceType":{
                                      "javaType":{
                                        "packageName":"com.acme",
                                        "className":"OrderService\\nFORGED_ANALYSIS_LOG"
                                      },
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_reject_oversized_target_source_before_analysis() throws Exception {
        String oversizedSource = "a".repeat(1025);
        String requestBody = """
                {
                  "repoId":"orders",
                  "expectedRevision":"1111111111111111111111111111111111111111",
                  "target":{
                    "sourceType":{
                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                      "sourceFile":"%s"
                    },
                    "methodName":"place",
                    "parameterTypes":[]
                  }
                }
                """.formatted(oversizedSource);

        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_INVALID"));

        then(semanticAnalysisApplicationService).shouldHaveNoInteractions();
    }

    @Test
    void should_default_an_omitted_depth_to_two() throws Exception {
        given(semanticAnalysisApplicationService.analyzeOutgoing(any(), any(), any(), eq(2))).willReturn(fragment());

        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "expectedRevision":"1111111111111111111111111111111111111111",
                                  "target":{
                                    "sourceType":{
                                      "javaType":{"packageName":"com.acme","className":"OrderService"},
                                      "sourceFile":"src/main/java/com/acme/OrderService.java"
                                    },
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traversal.requestedDepth").value(2));

        then(semanticAnalysisApplicationService).should().analyzeOutgoing(
                RepositoryId.of("orders"), REVISION, TARGET, 2);
    }

    @Test
    void should_accept_an_unmodified_discovery_target_for_outgoing_analysis() throws Exception {
        given(eventListenerDiscoveryApplicationService.discover(any())).willReturn(discoveryResult());
        given(semanticAnalysisApplicationService.analyzeOutgoing(
                eq(RepositoryId.of("orders")), eq(REVISION), eq(DISCOVERED_TARGET), eq(2))).willReturn(fragment());

        String discoveryResponse = mockMvc.perform(post("/v1/discovery/event-listeners")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"orders",
                                  "eventType":"com.acme.OrderPlaced",
                                  "expectedRevision":"1111111111111111111111111111111111111111"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode discovery = OBJECT_MAPPER.readTree(discoveryResponse);
        String discoveredTarget = OBJECT_MAPPER.writeValueAsString(discovery.path("candidates").get(0).path("target"));

        mockMvc.perform(post("/v1/analyses/call-graphs/outgoing")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "repoId":"%s",
                                  "expectedRevision":"%s",
                                  "depth":2,
                                  "target":%s
                                }
                                """.formatted(
                                discovery.path("repoId").asText(),
                                discovery.path("analyzedRevision").asText(),
                                discoveredTarget)))
                .andExpect(status().isOk());

        then(semanticAnalysisApplicationService).should().analyzeOutgoing(
                RepositoryId.of("orders"), REVISION, DISCOVERED_TARGET, 2);
    }

    private OutgoingGraphFragment fragment() {
        return new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                REVISION,
                root(),
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                nodes(),
                List.of(),
                List.of(),
                List.of());
    }

    private IncomingGraphFragment incomingFragment() {
        return new IncomingGraphFragment(
                GraphAnalysisStatus.PARTIAL,
                REVISION,
                root(),
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                incomingNodes(),
                List.of(new GraphEdge(
                        new CallNodeId("node-caller"),
                        root(),
                        new CallSiteRange(CALLER_TARGET.sourceFile(), 12, 3, 12, 15),
                        "place(orderId)",
                        ResolutionStrategy.JDT_CALL_HIERARCHY,
                        List.of("incoming-call-hierarchy"),
                        List.of(),
                        Optional.of(DECLARATION_TARGET))),
                List.of(new GraphWarning(
                        "DESCENDANT_CALL_AMBIGUOUS",
                        "multiple exact caller targets",
                        new CallNodeId("node-caller"),
                        Optional.of("place(orderId)"),
                        Optional.of(new CallSiteRange(CALLER_TARGET.sourceFile(), 12, 3, 12, 15)),
                        List.of(AMBIGUOUS_ALPHA, AMBIGUOUS_ZETA))),
                List.of());
    }

    private RevisionBoundEventListenerDiscovery discoveryResult() {
        SourceRange declarationRange = new SourceRange(
                DISCOVERED_TARGET.sourceFile(), new SyntaxRange(
                        new SyntaxPosition(6, 4), new SyntaxPosition(8, 5)));
        EventListenerCandidate candidate = new EventListenerCandidate(
                DISCOVERED_TARGET,
                declarationRange,
                List.of(new ListenerAnnotationEvidence(
                        ListenerAnnotationKind.EVENT_LISTENER, AnnotationMatchKind.RESOLVED_IDENTITY)));
        return new RevisionBoundEventListenerDiscovery(
                RepositoryId.of("orders"),
                REVISION,
                "com.acme.OrderPlaced",
                new EventListenerDiscoveryPage(
                        new CandidatePage(List.of(candidate), 0, 50, 1, 1, false),
                        List.of()));
    }

    private CallNodeId root() {
        return new CallNodeId("node-0000");
    }

    private List<GraphNode> nodes() {
        CallNodeId root = new CallNodeId("node-0000");
        return List.of(new GraphNode(
                root,
                Optional.of(TARGET),
                "",
                NodeContentState.FULL_SOURCE,
                NodeTraversalState.EXPANDED,
                DispatchKind.SYNCHRONOUS,
                Optional.of(new CallSiteRange(TARGET.sourceFile(), 0, 0, 1, 0))));
    }

    private List<GraphNode> incomingNodes() {
        List<GraphNode> nodes = new ArrayList<>(nodes());
        nodes.add(new GraphNode(
                new CallNodeId("node-caller"),
                Optional.of(CALLER_TARGET),
                "",
                NodeContentState.FULL_SOURCE,
                NodeTraversalState.EXPANDED,
                DispatchKind.SYNCHRONOUS,
                Optional.of(new CallSiteRange(CALLER_TARGET.sourceFile(), 0, 0, 1, 0))));
        return nodes;
    }
}
