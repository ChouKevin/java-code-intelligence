package com.java.semantic.api;

import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.callgraph.domain.CallNodeId;
import com.java.semantic.callgraph.domain.CallSiteRange;
import com.java.semantic.callgraph.domain.GraphAnalysisStatus;
import com.java.semantic.callgraph.domain.GraphLimitReason;
import com.java.semantic.callgraph.domain.GraphNode;
import com.java.semantic.callgraph.domain.GraphTraversal;
import com.java.semantic.callgraph.domain.NodeContentState;
import com.java.semantic.callgraph.domain.NodeTraversalState;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
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
        CallNodeId root = new CallNodeId("node-0000");
        return new OutgoingGraphFragment(
                GraphAnalysisStatus.SUCCESS,
                REVISION,
                root,
                new GraphTraversal(2, 0, 40, true, GraphLimitReason.NONE),
                List.of(new GraphNode(
                        root,
                        Optional.of(TARGET),
                        "",
                        NodeContentState.FULL_SOURCE,
                        NodeTraversalState.EXPANDED,
                        Optional.of("void place() {}"),
                        Optional.of(new CallSiteRange(TARGET.sourceFile(), 0, 0, 1, 0)))),
                List.of(),
                List.of(),
                List.of());
    }
}
