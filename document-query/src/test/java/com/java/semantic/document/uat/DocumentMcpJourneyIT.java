package com.java.semantic.document.uat;

import com.java.semantic.document.support.DocumentFixture;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

/** Real executable/HTTP proof, separate from external-agent source conclusions. */
@Tag("deployed-it")
class DocumentMcpJourneyIT extends DocumentJourneySupport {
    @Test
    void packagedStatelessServicePreservesExactDocumentsAcrossUpdatesWithdrawalAndRestart() throws Exception {
        Path runRoot = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(runRoot.resolve("work"));
        Path artifacts = Files.createDirectories(runRoot.resolve("artifacts"));
        String token = UUID.randomUUID().toString();
        List<String> checks = new ArrayList<>();
        String first;
        String second;
        String rejected;
        String oldCursor;
        String body = "# HIT A\r\n舊規則\r\n# HIT B\r\n尾端";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                DocumentFixture fixture = new DocumentFixture(work.resolve("documents"))) {
            first = fixture.commit(Map.of(DocumentFixture.GUIDE, body));
            fixture.publish(first, first);
            ObjectId firstBlob = Objects.requireNonNull(fixture.git.getRepository().resolve(first + ":" + DocumentFixture.GUIDE));
            byte[] original = fixture.git.getRepository().open(firstBlob).getBytes();
            try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("first.log"))) {
                service.awaitReady(client);
                assertThat(send(client, service.uri, "", "tools/list", Map.of()).statusCode()).isEqualTo(401);
                assertThat(send(client, service.uri, "wrong", "tools/list", Map.of()).statusCode()).isEqualTo(401);
                checks.add("missing/wrong document token rejected before tool access");
                initialize(client, service.uri, token);
                JsonNode tools = rpc(client, service.uri, token, "tools/list", Map.of()).path("tools");
                List<String> names = new ArrayList<>();
                for (JsonNode tool : tools) {
                    names.add(tool.path("name").stringValue());
                    assertThat(tool.path("annotations").path("readOnlyHint").booleanValue()).isTrue();
                }
                assertThat(names).containsExactlyInAnyOrder("search_documents", "read_document");
                checks.add("only two real read-only MCP tools listed");
                JsonNode page = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(page);
                JsonNode pageData = page.path("structuredContent");
                assertThat(pageData.path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                oldCursor = pageData.path("page").path("nextCursor").stringValue();
                assertThat(pageData.path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(1);
                JsonNode read = call(client, service.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(read);
                JsonNode data = read.path("structuredContent");
                assertThat(data.path("content").stringValue().getBytes(StandardCharsets.UTF_8)).isEqualTo(original);
                assertThat(data.path("blobId").stringValue()).isEqualTo(firstBlob.name());
                assertThat(data.path("contentDigest").stringValue()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
                assertThat(data.path("byteLength").intValue()).isEqualTo(original.length);
                assertThat(data.path("trust").stringValue()).isEqualTo("NOT_VERIFIED");
                assertThat(data.path("reviewStatus").stringValue()).isEqualTo("DRAFT");
                assertThat(data.path("collectionPurpose").stringValue()).isEqualTo("TEST_ONLY");
                checks.add("full document bytes/CRLF/EOF/blob/digest and test-only provenance match real Git");
                second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# HIT Updated\n新規則"));
                fixture.publish(second, first, second);
                JsonNode current = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(current);
                assertThat(current.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                JsonNode continued = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor));
                assertSuccess(continued);
                assertThat(continued.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                assertThat(continued.path("structuredContent").path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(3);
                JsonNode oldRead = call(client, service.uri, token, "read_document", readArguments(first, 3));
                assertSuccess(oldRead);
                assertThat(oldRead.path("structuredContent").path("content").stringValue()).isEqualTo(body);
                checks.add("new search uses D2; D1 continuation and explicit full read remain pinned");
                byte[] badBytes = "# HIT Broken\n不應啟用".getBytes(StandardCharsets.UTF_8);
                Map<String, Object> badRow = fixture.row(DocumentFixture.GUIDE, badBytes);
                badRow.put("contentDigest", "0".repeat(64));
                rejected = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, badBytes), List.of(badRow));
                fixture.publish(rejected, first, second, rejected);
                JsonNode preserved = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(preserved);
                assertThat(preserved.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                assertThat(preserved.path("structuredContent").path("publicationStatus").stringValue()).isEqualTo("UPDATE_REJECTED");
                assertThat(preserved.path("structuredContent").path("publicationErrorCode").stringValue()).isEqualTo("DOCUMENT_CATALOG_INVALID");
                checks.add("invalid D3 preserves actual D2 identity with explicit rejected status");
            }
            try (OwnedService invalidRestart = new OwnedService(jar, fixture, token, work.resolve("invalid-restart.log"))) {
                assertThat(invalidRestart.process.waitFor(30, TimeUnit.SECONDS)).as("Invalid startup must exit, not recover unconfigured memory").isTrue();
                assertThat(invalidRestart.process.exitValue()).isNotZero();
            }
            checks.add("restart with invalid selection fails closed");
            fixture.publish(second, first, second);
            try (OwnedService restarted = new OwnedService(jar, fixture, token, work.resolve("restarted.log"))) {
                restarted.awaitReady(client);
                initialize(client, restarted.uri, token);
                JsonNode staleCursor = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor));
                assertError(staleCursor, "INVALID_ARGUMENT");
                JsonNode exact = call(client, restarted.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(exact);
                assertThat(exact.path("structuredContent").path("content").stringValue()).isEqualTo(body);
                checks.add("valid restart restores retained exact reads but rejects old process cursor");
                fixture.publish(first, first, second);
                JsonNode firstAgain = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(firstAgain);
                String pinned = firstAgain.path("structuredContent").path("page").path("nextCursor").stringValue();
                fixture.publish(second, second);
                assertError(call(client, restarted.uri, token, "read_document", readArguments(first, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", pinned)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode remaining = call(client, restarted.uri, token, "read_document", readArguments(second, 1));
                assertSuccess(remaining);
                assertThat(remaining.path("structuredContent").path("content").stringValue()).isEqualTo("# HIT Updated\n新規則");
                checks.add("withdrawal fails explicitly without retargeting while D2 remains readable");
            }
            OwnedService interruptedOwner = new OwnedService(jar, fixture, token, work.resolve("interrupted-owner.log"));
            try {
                interruptedOwner.awaitReady(client);
                Thread.currentThread().interrupt();
                interruptedOwner.close();
                assertThat(interruptedOwner.process.isAlive()).as("Interrupted teardown still reaps its child").isFalse();
                assertThat(Files.exists(interruptedOwner.ownershipMarker)).as("Cleanup marker removed only after reaping").isFalse();
                assertThat(Thread.currentThread().isInterrupted()).as("Owner interruption is restored after reaping").isTrue();
                checks.add("interrupted owner reaps actual packaged child before restoring interruption");
            } finally {
                Thread.interrupted();
                interruptedOwner.close();
            }
        }
        Files.write(artifacts.resolve("document-native-acceptance.json"), mapper.writeValueAsBytes(Map.of(
                "status", "PASS", "documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                "firstRevision", first, "secondRevision", second, "rejectedRevision", rejected,
                "checks", checks, "agentAcceptance", "NOT_EXECUTED_BY_THIS_JOURNEY")));
    }

}
