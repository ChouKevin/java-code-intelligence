package com.java.semantic.api;

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
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.semantic.application.SemanticAnalysisApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final MethodTarget TARGET = new MethodTarget(
            "src/main/java/com/acme/OrderService.java", "com.acme", "OrderService", "place", List.of());
    private static final MethodTarget CALLER_TARGET = new MethodTarget(
            "src/main/java/com/acme/CheckoutService.java", "com.acme", "CheckoutService", "checkout", List.of());
    private static final MethodTarget AMBIGUOUS_ALPHA = new MethodTarget(
            "src/main/java/com/acme/AlphaOrderService.java", "com.acme", "AlphaOrderService", "place", List.of());
    private static final MethodTarget AMBIGUOUS_ZETA = new MethodTarget(
            "src/main/java/com/acme/ZetaOrderService.java", "com.acme", "ZetaOrderService", "place", List.of());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SemanticAnalysisApplicationService semanticAnalysisApplicationService;

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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.rootNodeId").value("node-0000"))
                .andExpect(jsonPath("$.nodes[0].target.sourceFile").value(TARGET.sourceFile()))
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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
                                    "methodName":"place",
                                    "parameterTypes":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.rootNodeId").value("node-0000"))
                .andExpect(jsonPath("$.nodes[0].target.sourceFile").value(TARGET.sourceFile()))
                .andExpect(jsonPath("$.traversal.nodeBudget").value(40))
                .andExpect(jsonPath("$.edges[0].callerNodeId").value("node-caller"))
                .andExpect(jsonPath("$.edges[0].calleeNodeId").value("node-0000"))
                .andExpect(jsonPath("$.edges[0].callSite.sourceFile").value(CALLER_TARGET.sourceFile()))
                .andExpect(jsonPath("$.edges[0].callSite.start.line").value(12))
                .andExpect(jsonPath("$.edges[0].callSite.start.character").value(3))
                .andExpect(jsonPath("$.edges[0].callSite.end.line").value(12))
                .andExpect(jsonPath("$.edges[0].callSite.end.character").value(15))
                .andExpect(jsonPath("$.warnings[0].code").value("DESCENDANT_CALL_AMBIGUOUS"))
                .andExpect(jsonPath("$.warnings[0].candidates[0].sourceFile").value(AMBIGUOUS_ALPHA.sourceFile()))
                .andExpect(jsonPath("$.warnings[0].candidates[1].sourceFile").value(AMBIGUOUS_ZETA.sourceFile()));

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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService\\nFORGED_ANALYSIS_LOG",
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
                    "sourceFile":"%s",
                    "packageName":"com.acme",
                    "className":"OrderService",
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
                                    "sourceFile":"src/main/java/com/acme/OrderService.java",
                                    "packageName":"com.acme",
                                    "className":"OrderService",
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
                        List.of("incoming-call-hierarchy"))),
                List.of(new GraphWarning(
                        "DESCENDANT_CALL_AMBIGUOUS",
                        "multiple exact caller targets",
                        new CallNodeId("node-caller"),
                        Optional.of("place(orderId)"),
                        Optional.of(new CallSiteRange(CALLER_TARGET.sourceFile(), 12, 3, 12, 15)),
                        List.of(AMBIGUOUS_ALPHA, AMBIGUOUS_ZETA))),
                List.of());
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
                Optional.of("void place() {}"),
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
                Optional.of("void checkout() {}"),
                Optional.of(new CallSiteRange(CALLER_TARGET.sourceFile(), 0, 0, 1, 0))));
        return nodes;
    }
}
