package com.java.semantic.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.java.semantic.api.security.ApiTokenFilter;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.SourceSegmentResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** canonical source segment continuation 的 HTTP 與 application 邊界測試 */
@SpringBootTest(properties = {
        "semantic.api.api-token=test-token",
        "semantic.repositories.orders.url=https://example.invalid/orders.git"
})
@AutoConfigureMockMvc
class SourceSegmentContinuationControllerTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("1".repeat(40));
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderService.java";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RepositoryApplicationService repositoryApplicationService;

    @Test
    void should_repost_next_location_without_context_and_reconstruct_exact_source(@TempDir Path repositoryRoot)
            throws Exception {
        String segmentSource = IntStream.range(0, 1_100)
                .mapToObj(index -> "        // " + "x".repeat(64))
                .collect(Collectors.joining("\n"));
        String source = "class OrderService {\n"
                + "  void inspect() {\n"
                + segmentSource
                + "\n  }\n"
                + "}\n";
        Path sourcePath = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(sourcePath.getParent());
        Files.writeString(sourcePath, source);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, repositoryRoot, REVISION);
        given(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Function<RepositorySnapshot, SourceSegmentResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });

        ObjectNode initialRequest = request(location(2, 0, 1_101, 75));
        initialRequest.put("contextLines", 0);
        JsonNode response = postSegment(initialRequest);
        StringBuilder reconstructed = new StringBuilder(response.at("/segment/content").textValue());
        JsonNode nextLocation = response.at("/segment/nextLocation");
        assertThat(nextLocation.isMissingNode()).isFalse();
        assertThat(response.at("/availableFollowUps/0/operation").textValue()).isEqualTo("GET_SOURCE_SEGMENT");
        assertThat(response.at("/availableFollowUps/0/request/location/sourceFile").textValue())
                .isEqualTo(nextLocation.at("/sourceFile").textValue());

        while (!nextLocation.isMissingNode()) {
            JsonNode continuation = postSegment(request(nextLocation));
            reconstructed.append(continuation.at("/segment/content").textValue());
            nextLocation = continuation.at("/segment/nextLocation");
            if (nextLocation.isMissingNode()) {
                assertThat(continuation.at("/availableFollowUps").isEmpty()).isTrue();
            }
        }

        assertThat(reconstructed.toString()).isEqualTo(segmentSource);
    }

    private JsonNode postSegment(ObjectNode request) throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/discovery/source-segment")
                        .header(ApiTokenFilter.API_TOKEN_HEADER, "test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private ObjectNode request(JsonNode location) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("repoId", REPOSITORY_ID.value());
        request.put("expectedRevision", REVISION.value());
        request.set("location", location);
        return request;
    }

    private ObjectNode location(int startLine, int startCharacter, int endLine, int endCharacter) {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("line", startLine);
        start.put("character", startCharacter);
        ObjectNode end = objectMapper.createObjectNode();
        end.put("line", endLine);
        end.put("character", endCharacter);
        ObjectNode range = objectMapper.createObjectNode();
        range.set("start", start);
        range.set("end", end);
        ObjectNode location = objectMapper.createObjectNode();
        location.put("sourceFile", SOURCE_FILE);
        location.set("range", range);
        return location;
    }
}
