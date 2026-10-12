package com.java.semantic.document.uat;

import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
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
    void packagedStatelessServiceIsolatesOwnersAndExpiresChangedScopesAcrossRestart() throws Exception {
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
        String globalListCursor;
        String aListCursor;
        String bListCursor;
        String body = "# HIT A\r\n舊規則\r\n# HIT B\r\n尾端";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                DocumentFixture fixture = new DocumentFixture(work.resolve("documents"))) {
            String aOverviewPath = "repositories/sample/description.md";
            String bPath = "repositories/b/guide.md";
            String bOverviewPath = "repositories/b/overview.md";
            byte[] aBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] aOverviewBytes = "# HIT A overview\r\nA metadata".getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = "# HIT B1\r\nB evidence\r\n# HIT More\r\nB continuation".getBytes(StandardCharsets.UTF_8);
            byte[] bOverviewBytes = "# HIT B overview\r\nB summary".getBytes(StandardCharsets.UTF_8);
            first = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, aBytes, aOverviewPath, aOverviewBytes,
                    bPath, bBytes, bOverviewPath, bOverviewBytes),
                    List.of(fixture.row(DocumentFixture.GUIDE, aBytes), fixture.row(aOverviewPath, aOverviewBytes),
                            fixture.row(bPath, bBytes, new SourceContext("b", DocumentFixture.SOURCE_REVISION)),
                            fixture.row(bOverviewPath, bOverviewBytes, new SourceContext("b", DocumentFixture.SOURCE_REVISION))));
            fixture.publish(Map.of("sample", first, "b", first));
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
                assertThat(names).contains("search_documents", "read_document", "list_documents");
                checks.add("required real read-only MCP tools listed");
                JsonNode page = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(page);
                JsonNode pageData = page.path("structuredContent");
                assertThat(pageData.path("items").get(0).path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                oldCursor = pageData.path("page").path("nextCursor").stringValue();
                assertThat(pageData.path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(1);
                JsonNode globalList = call(client, service.uri, token, "list_documents", Map.of("limit", 1));
                assertSuccess(globalList);
                JsonNode globalListData = globalList.path("structuredContent");
                JsonNode globalListItem = globalListData.path("items").get(0);
                assertThat(globalListItem.path("path").stringValue()).isEqualTo(bPath);
                assertThat(globalListItem.path("documentContext").path("repositoryId").stringValue()).isEqualTo("b");
                assertThat(globalListItem.has("content")).isFalse();
                assertThat(globalListData.path("page").path("hasMore").booleanValue()).isTrue();
                globalListCursor = globalListData.path("page").path("nextCursor").stringValue();
                JsonNode aList = call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "sample", "limit", 1));
                assertSuccess(aList);
                assertThat(aList.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(aOverviewPath);
                aListCursor = aList.path("structuredContent").path("page").path("nextCursor").stringValue();
                JsonNode bList = call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1));
                assertSuccess(bList);
                assertThat(bList.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(bPath);
                bListCursor = bList.path("structuredContent").path("page").path("nextCursor").stringValue();
                assertError(call(client, service.uri, token, "list_documents", Map.of("limit", 1, "cursor", oldCursor)),
                        "INVALID_ARGUMENT");
                assertError(call(client, service.uri, token, "search_documents",
                        Map.of("query", "HIT", "limit", 1, "cursor", globalListCursor)), "INVALID_ARGUMENT");
                JsonNode listRead = call(client, service.uri, token, "read_document",
                        readArguments("b", first, bPath, globalListItem.path("section").path("startLine").intValue()));
                assertSuccess(listRead);
                assertThat(listRead.path("structuredContent").path("content").stringValue().getBytes(StandardCharsets.UTF_8))
                        .isEqualTo(bBytes);
                checks.add("keyword-free global/scoped listing pages list each document and open a returned full-read entry");

                JsonNode bPage = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "b"));
                assertSuccess(bPage);
                String bCursor = bPage.path("structuredContent").path("page").path("nextCursor").stringValue();
                JsonNode aPage = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "sample"));
                String aCursor = aPage.path("structuredContent").path("page").path("nextCursor").stringValue();
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
                second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# HIT Updated\n新規則",
                        aOverviewPath, "# HIT overview updated\nNew metadata"));
                fixture.publish(Map.of("sample", second, "b", first));
                JsonNode current = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "repositoryId", "sample"));
                assertSuccess(current);
                assertThat(current.path("structuredContent").path("items").get(0).path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                assertError(call(client, service.uri, token, "list_documents",
                        Map.of("limit", 1, "cursor", globalListCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "sample", "limit", 1, "cursor", aListCursor)),
                        "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode bListContinued = call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1, "cursor", bListCursor));
                assertSuccess(bListContinued);
                assertThat(bListContinued.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(bOverviewPath);
                assertThat(bListContinued.path("structuredContent").path("items").get(0).path("documentContext")
                        .path("documentRevision").stringValue()).isEqualTo(first);
                JsonNode mixedList = call(client, service.uri, token, "list_documents", Map.of());
                assertSuccess(mixedList);
                JsonNode mixedListData = mixedList.path("structuredContent");
                assertThat(mixedListData.has("documentRevision")).isFalse();
                List<String> mixedPaths = new ArrayList<>();
                List<String> mixedRevisions = new ArrayList<>();
                for (JsonNode item : mixedListData.path("items")) {
                    mixedPaths.add(item.path("path").stringValue());
                    mixedRevisions.add(item.path("documentContext").path("documentRevision").stringValue());
                }
                assertThat(mixedPaths).containsExactly(bPath, bOverviewPath, aOverviewPath, DocumentFixture.GUIDE);
                assertThat(mixedRevisions).containsExactly(first, first, second, second);

                JsonNode freshGlobalList = call(client, service.uri, token, "list_documents", Map.of("limit", 1));
                JsonNode freshAList = call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "sample", "limit", 1));
                JsonNode freshBList = call(client, service.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1));
                fixture.publish(Map.of("sample", second, "b", first));
                JsonNode noOpGlobal = call(client, service.uri, token, "list_documents", Map.of("limit", 1,
                        "cursor", freshGlobalList.path("structuredContent").path("page").path("nextCursor").stringValue()));
                JsonNode noOpA = call(client, service.uri, token, "list_documents", Map.of("repositoryId", "sample", "limit", 1,
                        "cursor", freshAList.path("structuredContent").path("page").path("nextCursor").stringValue()));
                JsonNode noOpB = call(client, service.uri, token, "list_documents", Map.of("repositoryId", "b", "limit", 1,
                        "cursor", freshBList.path("structuredContent").path("page").path("nextCursor").stringValue()));
                assertSuccess(noOpGlobal);
                assertSuccess(noOpA);
                assertSuccess(noOpB);
                assertThat(noOpGlobal.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(bOverviewPath);
                assertThat(noOpA.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(DocumentFixture.GUIDE);
                assertThat(noOpB.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(bOverviewPath);
                checks.add("owner-scoped list cursors retain B and a no-op selection preserves all fresh list cursors");

                assertError(call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "sample", "cursor", aCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode continued = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "b", "cursor", bCursor));
                assertSuccess(continued);
                assertThat(continued.path("structuredContent").path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(3);
                assertError(call(client, service.uri, token, "read_document", readArguments(first, 3)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode bRead = call(client, service.uri, token, "read_document", readArguments("b", first, bPath, 1));
                assertSuccess(bRead);
                assertThat(bRead.path("structuredContent").path("content").stringValue().getBytes(StandardCharsets.UTF_8)).isEqualTo(bBytes);
                assertError(call(client, service.uri, token, "read_document", readArguments("b", first, DocumentFixture.GUIDE, 1)), "DOCUMENT_NOT_FOUND");
                checks.add("A update expires A/global while B scoped continuation and exact bytes survive; foreign-path substitution refuses");
                byte[] badBytes = "# HIT Broken\n不應啟用".getBytes(StandardCharsets.UTF_8);
                Map<String, Object> badRow = fixture.row(DocumentFixture.GUIDE, badBytes);
                badRow.put("contentDigest", "0".repeat(64));
                rejected = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, badBytes), List.of(badRow));
                fixture.publish(Map.of("sample", rejected, "b", first));
                JsonNode preserved = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "repositoryId", "sample"));
                assertSuccess(preserved);
                assertThat(preserved.path("structuredContent").path("items").get(0).path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                assertThat(preserved.path("structuredContent").path("publicationStatus").stringValue()).isEqualTo("UPDATE_REJECTED");
                assertThat(preserved.path("structuredContent").path("publicationErrorCode").stringValue()).isEqualTo("DOCUMENT_CATALOG_INVALID");
                JsonNode preservedList = call(client, service.uri, token, "list_documents", Map.of("limit", 10));
                assertSuccess(preservedList);
                JsonNode lastGoodList = preservedList.path("structuredContent");
                assertThat(lastGoodList.path("publicationStatus").stringValue()).isEqualTo("UPDATE_REJECTED");
                assertThat(lastGoodList.path("publicationErrorCode").stringValue()).isEqualTo("DOCUMENT_CATALOG_INVALID");
                List<String> lastGoodRevisions = new ArrayList<>();
                for (JsonNode item : lastGoodList.path("items")) {
                    lastGoodRevisions.add(item.path("documentContext").path("documentRevision").stringValue());
                }
                assertThat(lastGoodRevisions).containsExactly(first, first, second, second);
                checks.add("keyword-free listing preserves rejected-publication status and last-good owner revisions");
                checks.add("invalid D3 preserves actual D2 identity with explicit rejected status");
            }
            try (OwnedService invalidRestart = new OwnedService(jar, fixture, token, work.resolve("invalid-restart.log"))) {
                assertThat(invalidRestart.process.waitFor(30, TimeUnit.SECONDS)).as("Invalid startup must exit, not recover unconfigured memory").isTrue();
                assertThat(invalidRestart.process.exitValue()).isNotZero();
            }
            checks.add("restart with invalid selection fails closed");
            fixture.publish(Map.of("sample", second, "b", first));
            try (OwnedService restarted = new OwnedService(jar, fixture, token, work.resolve("restarted.log"))) {
                restarted.awaitReady(client);
                initialize(client, restarted.uri, token);
                JsonNode staleCursor = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", oldCursor));
                assertError(staleCursor, "INVALID_ARGUMENT");
                assertError(call(client, restarted.uri, token, "list_documents",
                        Map.of("limit", 1, "cursor", globalListCursor)), "INVALID_ARGUMENT");
                JsonNode exact = call(client, restarted.uri, token, "read_document", readArguments("b", first, bPath, 1));
                assertSuccess(exact);
                assertThat(exact.path("structuredContent").path("content").stringValue().getBytes(StandardCharsets.UTF_8)).isEqualTo(bBytes);
                assertError(call(client, restarted.uri, token, "read_document", readArguments(first, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                checks.add("valid restart restores current B exact bytes but rejects old process cursor and old A locator");
                JsonNode fresh = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                String pinned = fresh.path("structuredContent").path("page").path("nextCursor").stringValue();
                JsonNode beforeOwnerRemovalGlobal = call(client, restarted.uri, token, "list_documents", Map.of("limit", 1));
                String beforeOwnerRemovalCursor = beforeOwnerRemovalGlobal.path("structuredContent").path("page")
                        .path("nextCursor").stringValue();
                JsonNode beforeOwnerRemovalB = call(client, restarted.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1));
                String beforeOwnerRemovalBCursor = beforeOwnerRemovalB.path("structuredContent").path("page")
                        .path("nextCursor").stringValue();
                fixture.publish(Map.of("b", first));
                assertError(call(client, restarted.uri, token, "read_document", readArguments(second, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", pinned)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertSuccess(call(client, restarted.uri, token, "read_document", readArguments("b", first, bPath, 1)));
                assertError(call(client, restarted.uri, token, "list_documents",
                        Map.of("limit", 1, "cursor", beforeOwnerRemovalCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode continuedBList = call(client, restarted.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1, "cursor", beforeOwnerRemovalBCursor));
                assertSuccess(continuedBList);
                assertThat(continuedBList.path("structuredContent").path("items").get(0).path("path").stringValue())
                        .isEqualTo(bOverviewPath);
                JsonNode beforeLastOwnerRemoval = call(client, restarted.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1));
                String lastOwnerCursor = beforeLastOwnerRemoval.path("structuredContent").path("page")
                        .path("nextCursor").stringValue();
                fixture.publish(Map.of());
                JsonNode empty = call(client, restarted.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(empty);
                assertThat(empty.path("structuredContent").path("items").isEmpty()).isTrue();
                assertError(call(client, restarted.uri, token, "list_documents",
                        Map.of("repositoryId", "b", "limit", 1, "cursor", lastOwnerCursor)),
                        "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode emptyList = call(client, restarted.uri, token, "list_documents", Map.of());
                assertSuccess(emptyList);
                assertThat(emptyList.path("structuredContent").path("items").isEmpty()).isTrue();
                assertThat(emptyList.path("structuredContent").path("page").path("returned").intValue()).isZero();
                assertThat(call(client, restarted.uri, token, "list_documents",
                        Map.of("repositoryId", "b")).path("structuredContent").path("items").isEmpty()).isTrue();
                checks.add("A removal preserves B list continuation; last-owner removal expires B and leaves a successful empty list");
                checks.add("owner removal and empty selection expire locators without deleting historical Git objects");
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
