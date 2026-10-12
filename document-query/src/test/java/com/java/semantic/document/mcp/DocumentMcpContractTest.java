package com.java.semantic.document.mcp;

import com.java.semantic.document.DocumentQueryApplication;
import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.support.DocumentFixture;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

/** Real SDK routing, real catalog and query service; no mock JSON-RPC handlers. */
class DocumentMcpContractTest {
    @TempDir Path temporary;
    private DocumentFixture fixture;
    private ConfigurableApplicationContext application;
    private final HttpClient client = HttpClient.newHttpClient();
    private URI endpoint;
    private String revision;
    private String content;

    @BeforeEach
    void startOwnedApplication() throws Exception {
        fixture = new DocumentFixture(temporary.resolve("git"));
        content = "# Evidence\r\nneedle literal.* 文\r\nQualification: source must be verified.\r\n"
                + "# Other\r\nneedle historical context\r\n";
        revision = fixture.commit(Map.of(DocumentFixture.GUIDE, content));
        fixture.publish(Map.of("sample", revision));
        application = new SpringApplicationBuilder(DocumentQueryApplication.class).run(
                "--server.port=0", "--document.repository-id=" + DocumentFixture.DOCUMENT_REPOSITORY,
                "--document.git-directory=" + fixture.root.resolve(".git"),
                "--document.publication-file=" + fixture.publication,
                "--document.collection-purpose=TEST_ONLY", "--document.api-token=fixture-read-token");
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        endpoint = URI.create("http://127.0.0.1:" + port + "/mcp");
    }

    @AfterEach
    void closeOwnedResources() {
        client.close();
        if (Objects.nonNull(application)) { application.close(); }
        if (Objects.nonNull(fixture)) { fixture.close(); }
    }

    @Test
    void keyword_free_listing_returns_one_document_with_a_usable_read_entry() throws Exception {
        JsonNode listing = result("list_documents", Map.of()).path("structuredContent");
        assertThat(listing.path("items").size()).isEqualTo(1);
        JsonNode item = listing.path("items").get(0);
        assertThat(item.path("path").stringValue()).isEqualTo(DocumentFixture.GUIDE);
        assertThat(item.path("documentContext").path("documentRevision").stringValue()).isEqualTo(revision);
        assertThat(item.has("content")).isFalse();
        JsonNode read = result("read_document", Map.of("documentContext", item.path("documentContext"),
                "path", item.path("path").stringValue(), "sectionStartLine", item.path("section").path("startLine").asInt()));
        assertThat(read.path("structuredContent").path("content").stringValue()).isEqualTo(content);
    }

    @Test
    void listDocumentsExposeCompleteMetadataAndRejectNonListArguments() throws Exception {
        JsonNode response = result("list_documents", Map.of());
        assertRepresentations(response);
        assertThat(response.path("isError").asBoolean()).isFalse();
        JsonNode listing = response.path("structuredContent");
        assertThat(listing.path("documentRepositoryId").stringValue()).isEqualTo(DocumentFixture.DOCUMENT_REPOSITORY);
        assertThat(listing.has("repositoryId")).isFalse();
        assertThat(listing.path("collectionPurpose").stringValue()).isEqualTo("TEST_ONLY");
        assertThat(listing.path("trust").stringValue()).isEqualTo("NOT_VERIFIED");
        assertThat(listing.path("publicationStatus").stringValue()).isEqualTo("READY");
        assertThat(listing.path("page").path("returned").intValue()).isEqualTo(1);
        assertThat(listing.path("page").path("hasMore").booleanValue()).isFalse();
        assertThat(listing.path("items").size()).isEqualTo(1);
        JsonNode item = listing.path("items").get(0);
        assertThat(item.path("documentContext").path("documentRevision").stringValue()).isEqualTo(revision);
        assertThat(item.path("path").stringValue()).isEqualTo(DocumentFixture.GUIDE);
        assertThat(item.path("contentDigest").stringValue()).hasSize(64);
        assertThat(item.path("byteLength").intValue()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(item.path("section").path("startLine").intValue()).isEqualTo(1);
        assertThat(item.path("section").path("title").stringValue()).isEqualTo("Evidence");
        assertThat(item.path("primaryContext").path("repositoryId").stringValue()).isEqualTo("sample");
        assertThat(item.path("relatedContexts").isArray()).isTrue();
        assertThat(item.path("reviewStatus").stringValue()).isEqualTo("DRAFT");
        assertThat(item.has("content")).isFalse();
        assertThat(item.has("snippet")).isFalse();

        JsonNode tools = json(rpc("tools/list", Map.of(), true)).path("result").path("tools");
        JsonNode listTool = tool(tools, "list_documents");
        JsonNode inputSchema = listTool.path("inputSchema");
        assertThat(inputSchema.path("additionalProperties").booleanValue()).isFalse();
        assertThat(inputSchema.path("required").size()).isZero();
        assertThat(inputSchema.path("properties").has("repositoryId")).isTrue();
        assertThat(inputSchema.path("properties").has("limit")).isTrue();
        assertThat(inputSchema.path("properties").has("cursor")).isTrue();
        assertThat(inputSchema.path("properties").has("query")).isFalse();
        assertThat(inputSchema.path("properties").has("documentContext")).isFalse();
        JsonNode listSchema = listTool.path("outputSchema");
        JsonNode successSchema = listSchema.path("oneOf").get(0);
        assertSchemaFields(listing, successSchema);
        assertSchemaFields(item, listSchema.path("$defs").path("listItem"));
        assertSchemaFields(item.path("documentContext"), listSchema.path("$defs").path("documentContext"));
        assertSchemaFields(item.path("section"), listSchema.path("$defs").path("section"));
        assertSchemaFields(item.path("primaryContext"), listSchema.path("$defs").path("sourceContext"));

        JsonNode unknown = result("list_documents", Map.of("repositoryId", "absent"));
        assertRepresentations(unknown);
        assertThat(unknown.path("isError").asBoolean()).isFalse();
        assertThat(unknown.path("structuredContent").path("repositoryId").stringValue()).isEqualTo("absent");
        assertThat(unknown.path("structuredContent").path("items").isEmpty()).isTrue();

        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("limit", null);
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("query", "needle"),
                Map.of("includeUnpublished", true), Map.of("limit", 1.5), explicitNull,
                Map.of("repositoryId", "Invalid"))) {
            assertError(result("list_documents", invalid), "INVALID_ARGUMENT");
        }
    }

    @Test
    void statelessInitializeDiscoveryAndCompleteReadPreserveIndependentIdentities() throws Exception {
        HttpResponse<String> initialized = rpc("initialize", Map.of("protocolVersion", "2025-03-26",
                "capabilities", Map.of(), "clientInfo", Map.of("name", "contract", "version", "1")), true);
        assertThat(initialized.statusCode()).isEqualTo(200);
        assertThat(initialized.headers().firstValue("Mcp-Session-Id")).isEmpty();
        JsonNode capabilities = json(initialized).path("result").path("capabilities");
        assertThat(capabilities.has("tools")).isTrue();
        assertThat(capabilities.has("resources")).isFalse();
        assertThat(capabilities.has("prompts")).isFalse();
        JsonNode tools = json(rpc("tools/list", Map.of(), true)).path("result").path("tools");
        assertThat(tools.get(0).path("inputSchema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(tools.get(0).has("outputSchema")).isTrue();
        JsonNode search = result("search_documents", Map.of("query", "literal.* 文"));
        assertRepresentations(search);
        JsonNode hit = search.path("structuredContent").path("items").get(0);
        assertThat(hit.path("documentContext").path("documentRevision").stringValue()).isEqualTo(revision);
        assertThat(hit.path("primaryContext").path("revision").stringValue()).isEqualTo(DocumentFixture.SOURCE_REVISION);
        assertThat(hit.path("trust").stringValue()).isEqualTo("NOT_VERIFIED");
        JsonNode read = result("read_document", Map.of("documentContext", hit.path("documentContext"),
                "path", hit.path("path").stringValue(), "sectionStartLine", hit.path("section").path("startLine").asInt()));
        assertRepresentations(read);
        assertThat(read.path("structuredContent").path("content").stringValue()).isEqualTo(content);
        assertThat(read.path("structuredContent").path("scope").stringValue()).isEqualTo("FULL_DOCUMENT");
        assertThat(read.path("structuredContent").path("byteLength").asInt()).isEqualTo(content.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result("search_documents", Map.of("query", "NEEDLE")).path("structuredContent").path("items").isEmpty()).isTrue();
    }

    @Test
    void scopedSearchAcceptsBusinessRepositoryAndReturnsOnlyItsHits() throws Exception {
        byte[] sample = content.getBytes(StandardCharsets.UTF_8);
        String otherPath = "repositories/billing/guide.md";
        byte[] billing = "# Billing\nneedle billing-only\n".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> billingRow = fixture.row(otherPath, billing);
        billingRow.put("primaryContext", Map.of("repositoryId", "billing", "revision", "b".repeat(40)));
        String selected = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, sample, otherPath, billing),
                List.of(fixture.row(DocumentFixture.GUIDE, sample), billingRow));
        fixture.publish(Map.of("sample", selected, "billing", selected));

        JsonNode response = result("search_documents", Map.of("query", "needle", "repositoryId", "sample"));

        assertRepresentations(response);
        assertThat(response.path("isError").asBoolean()).isFalse();
        JsonNode search = response.path("structuredContent");
        assertThat(search.path("repositoryId").stringValue()).isEqualTo("sample");
        assertThat(search.path("items").size()).isEqualTo(2);
        for (JsonNode hit : search.path("items")) {
            assertThat(hit.path("path").stringValue()).isEqualTo(DocumentFixture.GUIDE);
            assertThat(hit.path("documentContext").path("repositoryId").stringValue()).isEqualTo("sample");
            assertThat(hit.path("primaryContext").path("repositoryId").stringValue()).isEqualTo("sample");
        }
    }

    @Test
    void mixedOwnerWireResultsExpireGlobalAndChangedOwnerButKeepOtherOwnerReadable() throws Exception {
        String billingPath = "repositories/billing/guide.md";
        byte[] billing = "# Billing\nneedle B1\n# More\nneedle B1 more\n".getBytes(StandardCharsets.UTF_8);
        byte[] sample = content.getBytes(StandardCharsets.UTF_8);
        Map<String, Object> billingRow = fixture.row(billingPath, billing);
        billingRow.put("primaryContext", Map.of("repositoryId", "billing", "revision", "b".repeat(40)));
        String first = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, sample, billingPath, billing),
                List.of(fixture.row(DocumentFixture.GUIDE, sample), billingRow));
        fixture.publish(Map.of("sample", first, "billing", first));
        JsonNode global = result("search_documents", Map.of("query", "needle", "limit", 1)).path("structuredContent");
        JsonNode b = result("search_documents", Map.of("query", "needle", "limit", 1, "repositoryId", "billing")).path("structuredContent");
        JsonNode a = result("search_documents", Map.of("query", "needle", "limit", 1, "repositoryId", "sample")).path("structuredContent");
        assertError(result("search_documents", Map.of("query", "needle", "limit", 1, "repositoryId", "billing",
                "cursor", global.path("page").path("nextCursor").stringValue())), "INVALID_ARGUMENT");
        assertError(result("search_documents", Map.of("query", "needle", "limit", 1,
                "cursor", b.path("page").path("nextCursor").stringValue())), "INVALID_ARGUMENT");
        assertError(result("search_documents", Map.of("query", "different", "limit", 1, "repositoryId", "billing",
                "cursor", b.path("page").path("nextCursor").stringValue())), "INVALID_ARGUMENT");
        assertError(result("search_documents", Map.of("query", "needle", "limit", 2, "repositoryId", "billing",
                "cursor", b.path("page").path("nextCursor").stringValue())), "INVALID_ARGUMENT");
        assertThat(global.has("documentContext")).isFalse();
        assertThat(global.path("documentRepositoryId").stringValue()).isEqualTo(DocumentFixture.DOCUMENT_REPOSITORY);
        JsonNode bHit = global.path("items").get(0);
        byte[] updated = "# New\nneedle A2\n".getBytes(StandardCharsets.UTF_8);
        String second = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, updated, billingPath, billing),
                List.of(fixture.row(DocumentFixture.GUIDE, updated), billingRow));
        fixture.publish(Map.of("sample", second, "billing", first));
        assertError(result("search_documents", Map.of("query", "needle", "limit", 1,
                "cursor", global.path("page").path("nextCursor").stringValue())), "DOCUMENT_REVISION_NOT_AVAILABLE");
        assertError(result("search_documents", Map.of("query", "needle", "limit", 1, "repositoryId", "sample",
                "cursor", a.path("page").path("nextCursor").stringValue())), "DOCUMENT_REVISION_NOT_AVAILABLE");
        JsonNode continued = result("search_documents", Map.of("query", "needle", "limit", 1, "repositoryId", "billing",
                "cursor", b.path("page").path("nextCursor").stringValue()));
        assertRepresentations(continued);
        assertThat(continued.path("isError").asBoolean()).isFalse();
        JsonNode read = result("read_document", Map.of("documentContext", bHit.path("documentContext"), "path", billingPath, "sectionStartLine", 1));
        assertThat(read.path("structuredContent").path("content").stringValue()).isEqualTo(new String(billing, StandardCharsets.UTF_8));
        assertError(result("read_document", Map.of("documentContext", a.path("items").get(0).path("documentContext"),
                "path", DocumentFixture.GUIDE, "sectionStartLine", 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
        JsonNode mixed = result("search_documents", Map.of("query", "needle"));
        assertRepresentations(mixed);
        assertThat(mixed.path("structuredContent").path("items").size()).isEqualTo(3);
        assertThat(fixture.mapper.writeValueAsBytes(mixed).length).isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
        JsonNode tools = json(rpc("tools/list", Map.of(), true)).path("result").path("tools");
        JsonNode searchSchema = tools.get(0).path("outputSchema");
        JsonNode actual = mixed.path("structuredContent");
        assertSchemaFields(actual, searchSchema.path("oneOf").get(0));
        assertThat(actual.has("repositoryId")).isFalse();
        for (JsonNode hit : actual.path("items")) {
            assertSchemaFields(hit, searchSchema.path("$defs").path("hit"));
            assertSchemaFields(hit.path("documentContext"), searchSchema.path("$defs").path("documentContext"));
            assertSchemaFields(hit.path("documentContext"), tools.get(1).path("inputSchema").path("properties").path("documentContext"));
            assertThat(hit.path("documentContext").path("repositoryId").stringValue()).isEqualTo(hit.path("primaryContext").path("repositoryId").stringValue());
        }
        assertSchemaFields(read.path("structuredContent"), tools.get(1).path("outputSchema").path("oneOf").get(0));
    }

    @Test
    void strictArgumentsAndUnavailableSelectorsReturnSafeMatchingRepresentations() throws Exception {
        Map<String, Object> explicitNull = new LinkedHashMap<>();
        explicitNull.put("query", "needle"); explicitNull.put("limit", null);
        Map<String, Object> nullOwner = new LinkedHashMap<>();
        nullOwner.put("query", "needle"); nullOwner.put("repositoryId", null);
        assertError(result("search_documents", nullOwner), "INVALID_ARGUMENT");
        for (String invalidOwner : List.of("", "../sample", "Sample", "a".repeat(65))) {
            assertError(result("search_documents", Map.of("query", "needle", "repositoryId", invalidOwner)), "INVALID_ARGUMENT");
        }
        JsonNode absent = result("search_documents", Map.of("query", "needle", "repositoryId", "absent"));
        assertRepresentations(absent);
        assertThat(absent.path("isError").asBoolean()).isFalse();
        assertThat(absent.path("structuredContent").path("repositoryId").stringValue()).isEqualTo("absent");
        assertThat(absent.path("structuredContent").path("items").isEmpty()).isTrue();
        assertError(result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "documentRevision", revision), "path", DocumentFixture.GUIDE, "sectionStartLine", 1)), "INVALID_ARGUMENT");
        for (Map<String, Object> invalid : List.<Map<String, Object>>of(explicitNull, Map.of("query", "needle", "limit", 1.5),
                Map.of("query", "needle", "regex", true), Map.of("query", " "), Map.of("query", 7),
                Map.of("query", "needle", "cursor", "not-a-cursor"))) {
            assertError(result("search_documents", invalid), "INVALID_ARGUMENT");
        }
        assertError(result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", "sample", "documentRevision", revision, "sourceRevision", DocumentFixture.SOURCE_REVISION),
                "path", DocumentFixture.GUIDE, "sectionStartLine", 1)), "INVALID_ARGUMENT");
        assertError(result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", "sample", "documentRevision", revision), "path", DocumentFixture.GUIDE, "sectionStartLine", 1.5)), "INVALID_ARGUMENT");
        assertError(result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", "sample", "documentRevision", revision), "path", "navigation-catalog.json", "sectionStartLine", 1)), "DOCUMENT_NOT_FOUND");
        assertError(result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", "sample", "documentRevision", revision), "path", DocumentFixture.GUIDE, "sectionStartLine", 2)), "DOCUMENT_SECTION_NOT_FOUND");
    }

    @Test
    void authenticationPrecedesPublicationAccessAndOtherRoutesExposeNoData() throws Exception {
        Files.delete(fixture.publication);
        assertThat(rpc("tools/call", Map.of("name", "search_documents", "arguments", Map.of("query", "needle")), false).statusCode()).isEqualTo(401);
        assertThat(client.send(HttpRequest.newBuilder(endpoint).header("X-Api-Token", "indexer-token").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        HttpResponse<String> health = client.send(HttpRequest.newBuilder(endpoint.resolve("/actuator/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(fixture.mapper.readTree(health.body()).size()).isEqualTo(1);
        assertThat(client.send(HttpRequest.newBuilder(endpoint.resolve("/actuator/env")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
    }

    @Test
    void fixedAndChunkedOversizedBodiesAreRejectedBeforeSdkParsing() throws Exception {
        byte[] oversized = "x".repeat(DocumentContract.MAX_REQUEST_BYTES + 1).getBytes(StandardCharsets.UTF_8);
        for (HttpRequest.BodyPublisher publisher : List.of(HttpRequest.BodyPublishers.ofByteArray(oversized),
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)))) {
            HttpResponse<String> response = client.send(request().POST(publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.body()).doesNotContain(fixture.root.toString(), "fixture-read-token");
        }
        HttpResponse<String> unsupported = client.send(HttpRequest.newBuilder(endpoint).header("X-Api-Token", "fixture-read-token")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(unsupported.statusCode()).isEqualTo(415);
    }

    @Test
    void escapedFullDocumentIsMeasuredAsCompleteWireResultAndCancellationHasNoSession() throws Exception {
        String escaped = "# Budget\n" + "\"\\\t文".repeat(7000);
        byte[] bytes = escaped.getBytes(StandardCharsets.UTF_8);
        Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, bytes);
        String reference = "review-\"\\".repeat(2000);
        row.put("reviewReference", reference);
        String selected = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, bytes), List.of(row));
        fixture.publish(Map.of("sample", selected));
        JsonNode read = result("read_document", Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "repositoryId", "sample", "documentRevision", selected), "path", DocumentFixture.GUIDE, "sectionStartLine", 1));
        assertRepresentations(read);
        assertThat(read.path("structuredContent").path("content").stringValue()).isEqualTo(escaped);
        assertThat(read.path("structuredContent").path("reviewReference").stringValue()).isEqualTo(reference);
        assertThat(fixture.mapper.writeValueAsBytes(read).length).isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
        HttpResponse<String> notification = client.send(request().POST(HttpRequest.BodyPublishers.ofString(fixture.mapper.writeValueAsString(
                Map.of("jsonrpc", "2.0", "method", "notifications/cancelled", "params", Map.of("requestId", 1, "reason", "client stopped"))))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(notification.statusCode()).isBetween(200, 299);
        assertThat(notification.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(result("search_documents", Map.of("query", "Budget")).path("isError").asBoolean()).isFalse();
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(endpoint).header("X-Api-Token", "fixture-read-token")
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-03-26");
    }
    private HttpResponse<String> rpc(String method, Map<String, Object> params, boolean authenticated) throws Exception {
        HttpRequest.Builder builder = authenticated ? request() : HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json");
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(fixture.mapper.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params)))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        if (body.startsWith("event:") || body.startsWith("data:")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).stripLeading()).findFirst().orElseThrow();
        }
        return fixture.mapper.readTree(body);
    }
    private JsonNode tool(JsonNode tools, String name) {
        for (JsonNode candidate : tools) {
            if (name.equals(candidate.path("name").stringValue())) { return candidate; }
        }
        throw new AssertionError("MCP tool not registered: " + name);
    }

    private JsonNode result(String name, Map<String, Object> arguments) throws Exception {
        JsonNode response = json(rpc("tools/call", Map.of("name", name, "arguments", arguments), true));
        assertThat(response.has("error")).isFalse();
        return response.path("result");
    }
    private void assertSchemaFields(JsonNode actual, JsonNode schema) {
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        for (String name : actual.propertyNames()) {
            assertThat(schema.path("properties").has(name)).as("serialized field %s is allowed by live schema", name).isTrue();
        }
        for (JsonNode required : schema.path("required")) {
            assertThat(actual.has(required.stringValue())).as("serialized required field %s", required.stringValue()).isTrue();
        }
    }

    private void assertRepresentations(JsonNode result) {
        assertThat(fixture.mapper.readTree(result.path("content").get(0).path("text").stringValue())).isEqualTo(result.path("structuredContent"));
    }
    private void assertError(JsonNode result, String code) {
        assertRepresentations(result);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").size()).isEqualTo(2);
        assertThat(result.path("structuredContent").path("code").stringValue()).isEqualTo(code);
        assertThat(result.toString()).doesNotContain(fixture.root.toString(), "fixture-read-token");
    }
}
