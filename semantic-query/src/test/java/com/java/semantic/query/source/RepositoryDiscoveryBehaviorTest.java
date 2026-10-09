package com.java.semantic.query.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.java.semantic.api.QueryApiExceptionHandler;
import com.java.semantic.api.SemanticQueryController;
import com.java.semantic.mcp.QueryMcpToolCatalogConfiguration;
import com.java.semantic.model.source.RepositoryNavigationHint;
import com.java.semantic.model.source.RepositoryNavigationHint.ReviewStatus;
import com.java.semantic.model.source.RepositoryNavigationHint.Trust;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.query.application.SemanticQueryFacade;
import com.java.semantic.query.config.SourceAccessProperties;
import com.java.semantic.query.index.LocalIndexReader;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RepositoryDiscoveryBehaviorTest {
    @TempDir Path temp;
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void approved_unprepared_hint_is_allowlisted_navigation_not_source_admission_or_keyword_filter() throws Exception {
        SourceRepositoryDescriptor allowed = descriptor("sample", "Member service", hint("VIP calculation"));
        SourceRepositoryDescriptor denied = descriptor("private", "Private secret", hint("Secret navigation"));
        LocalSourceRevisionCatalog catalog = catalog(List.of(allowed, denied), List.of("sample"));
        RepositoryCollection result = catalog.listRepositories(request(20, Optional.empty()));
        assertThat(result.items()).hasSize(1);
        RepositoryItem item = result.items().getFirst();
        assertThat(item.navigationHint()).contains(allowed.navigationHint().orElseThrow());
        assertThat(item.sourceStatus()).isEqualTo(SourceStatus.NOT_PREPARED);
        assertThat(item.semanticStatus()).isEqualTo(SemanticStatus.NOT_READY);
        assertThat(item.revision()).isEmpty();
        assertThat(catalog.listRepositories(new RepositoryRequest(Optional.of("VIP"), 20, Optional.empty())).items()).isEmpty();
        assertThat(catalog.listRepositories(new RepositoryRequest(Optional.of("Member"), 20, Optional.empty())).items()).containsExactly(item);
        ContextResult context = catalog.getContext(new ContextRequest("sample", Optional.empty()));
        assertThat(context.sourceStatus()).isEqualTo(SourceStatus.NOT_PREPARED);
        assertThat(context.context()).isEmpty();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(context)).has("navigationHint")).isFalse();
        assertThatThrownBy(() -> catalog.admit(new SourceContext("sample", SourceFilesystemFixture.SHA)))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_NOT_PREPARED));
        assertThatThrownBy(() -> catalog.getContext(new ContextRequest("private", Optional.empty())))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.REPOSITORY_NOT_FOUND));
        assertThat(Files.exists(temp.resolve("published/sample/state.json"))).isFalse();
    }

    @Test
    void updating_then_removing_hint_invalidates_registry_bound_cursors() throws Exception {
        SourceRepositoryDescriptor first = descriptor("sample", "Member", hint("Old summary"));
        SourceRepositoryDescriptor second = descriptor("zeta", "Other", Optional.empty());
        LocalSourceRevisionCatalog catalog = catalog(List.of(first, second), List.of("sample", "zeta"));
        Optional<String> old = catalog.listRepositories(request(1, Optional.empty())).page().nextCursor();
        assertThat(old).isPresent();
        publish(List.of(descriptor("sample", "Member", hint("Updated summary")), second));
        assertInvalidCursor(catalog, old);
        RepositoryCollection updated = catalog.listRepositories(request(1, Optional.empty()));
        assertThat(updated.items().getFirst().navigationHint().orElseThrow().summary()).isEqualTo("Updated summary");
        publish(List.of(descriptor("sample", "Member", Optional.empty()), second));
        assertInvalidCursor(catalog, updated.page().nextCursor());
        assertThat(catalog.listRepositories(request(1, Optional.empty())).items().getFirst().navigationHint()).isEmpty();
    }

    @Test
    void source_and_index_status_and_exact_sha_remain_independent_of_description_revision() throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp);
        fixture.file("Member.java", "class Member {}\n");
        fixture.publish(Optional.empty());
        RepositoryNavigationHint navigation = new RepositoryNavigationHint("Business", List.of("VIP"),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", ReviewStatus.APPROVED, Trust.NOT_VERIFIED);
        Files.write(fixture.root.resolve("repositories.json"), mapper.writeValueAsBytes(List.of(
                descriptor("sample", "Member", Optional.of(navigation)))));
        RepositoryItem item = fixture.catalog().listRepositories(request(20, Optional.empty())).items().getFirst();
        assertThat(item.sourceStatus()).isEqualTo(SourceStatus.READY);
        assertThat(item.semanticStatus()).isEqualTo(SemanticStatus.NOT_READY);
        assertThat(item.revision()).contains(SourceFilesystemFixture.SHA);
        assertThat(item.navigationHint()).contains(navigation);
        assertThat(fixture.catalog().getContext(new ContextRequest("sample", Optional.empty())).context()).contains(fixture.context);
        assertThatThrownBy(() -> fixture.catalog().getContext(new ContextRequest("sample", Optional.of(navigation.analyzedRevision()))))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.REVISION_NOT_PREPARED));
    }

    @Test
    void mixed_utf8_and_escaping_pages_preserve_whole_rows_and_both_transport_results() throws Exception {
        List<SourceRepositoryDescriptor> descriptors = new ArrayList<>();
        for (int number = 0; number < 80; number++) {
            descriptors.add(descriptor("repo" + String.format("%03d", number),
                    "中😀\\\"\t".repeat(600), hint("中😀\\\"".repeat(40))));
        }
        LocalSourceRevisionCatalog catalog = catalog(descriptors,
                descriptors.stream().map(SourceRepositoryDescriptor::repositoryId).toList());
        SemanticQueryFacade facade = facade(catalog);
        MockMvc http = http(facade);
        McpServerFeatures.SyncToolSpecification tool = tool(facade);
        List<RepositoryItem> received = new ArrayList<>();
        Optional<String> cursor = Optional.empty();
        int pages = 0;
        do {
            Map<String, Object> arguments = new HashMap<>(Map.of("limit", 100));
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query = get("/api/v1/repositories").param("limit", "100");
            cursor.ifPresent(value -> { arguments.put("cursor", value); query.param("cursor", value); });
            byte[] body = http.perform(query).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertThat(body.length).isLessThanOrEqualTo(SourceReadContract.MAX_REPOSITORY_RESPONSE_BYTES);
            McpSchema.CallToolResult mcp = tool.callHandler().apply(null,
                    McpSchema.CallToolRequest.builder("list_repositories").arguments(arguments).build());
            assertThat(mcp.isError()).isFalse();
            assertThat(mapper.writeValueAsBytes(mcp).length).isLessThanOrEqualTo(512 * 1024);
            JsonNode json = mapper.readTree(body);
            assertThat(mapper.readTree(mapper.writeValueAsBytes(mcp.structuredContent()))).isEqualTo(json);
            assertThat(mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(json);
            RepositoryCollection page = mapper.treeToValue(json, RepositoryCollection.class);
            assertThat(page.items()).isNotEmpty();
            assertThat(page.page().returned()).isEqualTo(page.items().size());
            assertThat(page.page().hasMore()).isEqualTo(page.page().nextCursor().isPresent());
            received.addAll(page.items());
            cursor = page.page().nextCursor();
            assertThat(++pages).isLessThanOrEqualTo(descriptors.size());
        } while (cursor.isPresent());
        assertThat(pages).isGreaterThan(1);
        assertThat(received).extracting(RepositoryItem::repositoryId)
                .containsExactlyElementsOf(descriptors.stream().map(SourceRepositoryDescriptor::repositoryId).toList());
        for (int number = 0; number < descriptors.size(); number++) {
            assertThat(received.get(number).displayName()).isEqualTo(descriptors.get(number).displayName());
            assertThat(received.get(number).navigationHint()).isEqualTo(descriptors.get(number).navigationHint());
        }
    }

    @Test
    void oversized_first_or_continued_item_returns_safe_http413_and_identical_mcp_error_without_skipping() throws Exception {
        SourceRepositoryDescriptor small = descriptor("alpha", "Small", Optional.empty());
        SourceRepositoryDescriptor huge = descriptor("zeta", "\\\"".repeat(85_000), hint("Oversized"));
        LocalSourceRevisionCatalog catalog = catalog(List.of(small, huge), List.of("alpha", "zeta"));
        RepositoryCollection first = catalog.listRepositories(request(20, Optional.empty()));
        assertThat(first.items()).extracting(RepositoryItem::repositoryId).containsExactly("alpha");
        assertThat(first.page().hasMore()).isTrue();
        SemanticQueryFacade facade = facade(catalog);
        assertOversizedParity(facade, first.page().nextCursor());
        publish(List.of(huge));
        assertOversizedParity(facade, Optional.empty());
    }

    @Test
    void byte_budget_includes_the_collection_and_page_not_just_the_item() throws Exception {
        RepositoryItem base = new RepositoryItem("sample", "A", "main", Optional.empty(),
                SourceStatus.NOT_PREPARED, SemanticStatus.NOT_READY, Optional.empty(), Optional.empty());
        int baseBytes = mapper.writeValueAsBytes(new RepositoryCollection(List.of(base),
                new Page(1, false, Optional.empty()))).length;
        String name = "A".repeat(SourceReadContract.MAX_REPOSITORY_RESPONSE_BYTES - baseBytes + 2);
        RepositoryItem item = new RepositoryItem("sample", name, "main", Optional.empty(),
                SourceStatus.NOT_PREPARED, SemanticStatus.NOT_READY, Optional.empty(), Optional.empty());
        assertThat(mapper.writeValueAsBytes(item).length).isLessThanOrEqualTo(SourceReadContract.MAX_REPOSITORY_RESPONSE_BYTES);
        assertThat(mapper.writeValueAsBytes(new RepositoryCollection(List.of(item),
                new Page(1, false, Optional.empty()))).length).isEqualTo(SourceReadContract.MAX_REPOSITORY_RESPONSE_BYTES + 1);
        LocalSourceRevisionCatalog catalog = catalog(List.of(descriptor("sample", name, Optional.empty())), List.of("sample"));
        assertThatThrownBy(() -> catalog.listRepositories(request(20, Optional.empty())))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.SOURCE_RESULT_TOO_LARGE));
    }

    private void assertOversizedParity(SemanticQueryFacade facade, Optional<String> cursor) throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder query = get("/api/v1/repositories");
        cursor.ifPresent(value -> { arguments.put("cursor", value); query.param("cursor", value); });
        byte[] body = http(facade).perform(query).andExpect(status().is(413)).andReturn().getResponse().getContentAsByteArray();
        McpSchema.CallToolResult mcp = tool(facade).callHandler().apply(null,
                McpSchema.CallToolRequest.builder("list_repositories").arguments(arguments).build());
        JsonNode error = mapper.readTree(body);
        assertThat(error.get("code").asString()).isEqualTo("SOURCE_RESULT_TOO_LARGE");
        assertThat(error.get("message").asString()).isEqualTo("A complete source result exceeds the response budget");
        assertThat(mcp.isError()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsBytes(mcp.structuredContent()))).isEqualTo(error);
        assertThat(mapper.readTree(((McpSchema.TextContent) mcp.content().getFirst()).text())).isEqualTo(error);
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("zeta", "Oversized", temp.toString());
    }

    private LocalSourceRevisionCatalog catalog(List<SourceRepositoryDescriptor> descriptors, List<String> allowlist) throws Exception {
        Path root = Files.createDirectories(temp.resolve("published"));
        for (String id : allowlist) {
            Files.createDirectories(root.resolve(id));
            Files.createFile(root.resolve(id).resolve("read.lock"));
        }
        publish(descriptors);
        SourceAccessProperties properties = new SourceAccessProperties(root,
                Path.of(System.getProperty("source.test.rg", "/usr/bin/rg")), allowlist, 65_536,
                Duration.ofSeconds(5), Duration.ofSeconds(2), Duration.ofSeconds(2), 2);
        return new LocalSourceRevisionCatalog(properties, mapper, new SourceReadLocks(root),
                new LocalIndexReader(temp.resolve("index-published"), mapper));
    }

    private void publish(List<SourceRepositoryDescriptor> descriptors) throws Exception {
        Files.write(temp.resolve("published/repositories.json"), mapper.writeValueAsBytes(descriptors));
    }

    private SemanticQueryFacade facade(LocalSourceRevisionCatalog catalog) throws Exception {
        SourceFilesystemFixture fixture = new SourceFilesystemFixture(temp.resolve("source-service"));
        return new SemanticQueryFacade(catalog, fixture.service(), fixture.index, 2);
    }

    private MockMvc http(SemanticQueryFacade facade) {
        return standaloneSetup(new SemanticQueryController(facade)).setControllerAdvice(new QueryApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper)).build();
    }

    private McpServerFeatures.SyncToolSpecification tool(SemanticQueryFacade facade) {
        return new QueryMcpToolCatalogConfiguration().mcpQueryToolSpecifications(facade, mapper).stream()
                .filter(value -> value.tool().name().equals("list_repositories")).findFirst().orElseThrow();
    }

    private static RepositoryRequest request(int limit, Optional<String> cursor) {
        return new RepositoryRequest(Optional.empty(), limit, cursor);
    }

    private static void assertInvalidCursor(LocalSourceRevisionCatalog catalog, Optional<String> cursor) {
        assertThat(cursor).isPresent();
        assertThatThrownBy(() -> catalog.listRepositories(request(1, cursor)))
                .isInstanceOfSatisfying(SourceQueryException.class,
                        failure -> assertThat(failure.code()).isEqualTo(SourceQueryException.Code.INVALID_ARGUMENT));
    }

    private static SourceRepositoryDescriptor descriptor(String id, String name, Optional<RepositoryNavigationHint> hint) {
        return new SourceRepositoryDescriptor(id, name, "main", Optional.empty(), hint);
    }

    private static Optional<RepositoryNavigationHint> hint(String summary) {
        return Optional.of(new RepositoryNavigationHint(summary, List.of("VIP", "中😀\\\""),
                SourceFilesystemFixture.SHA, ReviewStatus.APPROVED, Trust.NOT_VERIFIED));
    }
}
