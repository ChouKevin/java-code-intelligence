package com.java.semantic.document.uat;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.document.support.DocumentFixture;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Operator executable writes real Git; independent running MCP verifies its adoption. */
@Tag("deployed-it")
class DocumentLocalPublicationJourneyIT extends DocumentJourneySupport {
    @Test
    void packagedLocalUpdatesIsolateOwnersAndNamedRemovalPublishesEmptySelection() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(root.resolve("work"));
        Path artifacts = Files.createDirectories(root.resolve("artifacts"));
        String token = UUID.randomUUID().toString();
        String firstBody = "# HIT First\r\nLocal guide A\r\n# HIT Second\r\nExact evidence";
        String secondBody = "# HIT Updated\nLocal guide A2";
        String bBody = "# HIT B\nOther owner\n# HIT More\nOther owner continuation";
        try (DocumentFixture fixture = new DocumentFixture(work.resolve("local-publication/documents"));
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Local operator fixture");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.createDirectories(fixture.root.resolve("repositories/b"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), firstBody);
            Files.writeString(fixture.root.resolve("repositories/b/guide.md"), bBody);
            Files.writeString(fixture.root.resolve("unrelated.txt"), "never included");
            assertThat(command(jar, fixture, work, "--help").exit()).isZero();
            JsonNode firstUpdate = success(command(jar, fixture, work, "update", "--source",
                    "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"));
            String first = firstUpdate.path("activeRevisions").path("sample").stringValue();
            assertThat(firstUpdate.path("serviceVerified").booleanValue()).isFalse();
            String b = success(command(jar, fixture, work, "update", "--source",
                    "b@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md")).path("activeRevisions").path("b").stringValue();
            try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("local-reader.log"))) {
                service.awaitReady(client);
                initialize(client, service.uri, token);
                JsonNode global = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                JsonNode aPage = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "sample"));
                JsonNode bPage = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "b"));
                String globalCursor = global.path("structuredContent").path("page").path("nextCursor").stringValue();
                String aCursor = aPage.path("structuredContent").path("page").path("nextCursor").stringValue();
                String bCursor = bPage.path("structuredContent").path("page").path("nextCursor").stringValue();
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), secondBody);
                JsonNode update = success(command(jar, fixture, work, "update", "--source",
                        "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"));
                String second = update.path("activeRevisions").path("sample").stringValue();
                assertThat(second).isNotEqualTo(first);
                assertThat(update.path("activeRevisions").path("b").stringValue()).isEqualTo(b);
                assertError(call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", globalCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertError(call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "sample", "cursor", aCursor)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertSuccess(call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "repositoryId", "b", "cursor", bCursor)));
                assertError(call(client, service.uri, token, "read_document", readArguments(first, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                JsonNode bRead = call(client, service.uri, token, "read_document", readArguments("b", b, "repositories/b/guide.md", 1));
                assertSuccess(bRead);
                assertThat(bRead.path("structuredContent").path("content").stringValue()).isEqualTo(bBody);
                byte[] selection = Files.readAllBytes(fixture.publication);
                for (String[] obsolete : List.of(new String[] {"activate", "--revision", first},
                        new String[] {"update", "--source", "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md", "--withdraw", first})) {
                    assertThat(command(jar, fixture, work, obsolete).exit()).isEqualTo(2);
                    assertThat(Files.readAllBytes(fixture.publication)).isEqualTo(selection);
                    assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(second);
                }
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "x".repeat(65537));
                assertThat(command(jar, fixture, work, "update", "--source", "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md").exit()).isEqualTo(2);
                assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(second);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), firstBody);
                String forward = success(command(jar, fixture, work, "update", "--source", "sample@" + "c".repeat(40),
                        "--file", "guide.md")).path("activeRevisions").path("sample").stringValue();
                assertThat(forward).isNotIn(first, second);
                JsonNode restored = call(client, service.uri, token, "read_document", readArguments(forward, 1));
                assertSuccess(restored);
                assertThat(restored.path("structuredContent").path("content").stringValue()).isEqualTo(firstBody);
                JsonNode removed = success(command(jar, fixture, work, "remove", "--repository-id", "sample", "--file", "guide.md"));
                assertThat(removed.path("activeRevisions").has("sample")).isFalse();
                assertThat(removed.path("activeRevisions").path("b").stringValue()).isEqualTo(b);
                assertError(call(client, service.uri, token, "read_document", readArguments(forward, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertSuccess(call(client, service.uri, token, "read_document", readArguments("b", b, "repositories/b/guide.md", 1)));
                success(command(jar, fixture, work, "remove", "--repository-id", "b", "--file", "guide.md"));
                for (Map<String, Object> arguments : List.<Map<String, Object>>of(Map.of("query", "HIT"), Map.of("query", "HIT", "repositoryId", "b"))) {
                    JsonNode empty = call(client, service.uri, token, "search_documents", arguments);
                    assertSuccess(empty);
                    assertThat(empty.path("structuredContent").path("items").isEmpty()).isTrue();
                }
                assertThat(new String(fixture.git.getRepository().open(fixture.git.getRepository().resolve(first + ":" + DocumentFixture.GUIDE)).getBytes(), StandardCharsets.UTF_8)).isEqualTo(firstBody);
                assertThat(Files.readString(fixture.root.resolve("unrelated.txt"))).isEqualTo("never included");
                assertThat(fixture.git.status().call().getUntracked()).contains("unrelated.txt");
                Files.write(artifacts.resolve("document-local-native-acceptance.json"), mapper.writeValueAsBytes(Map.of(
                        "status", "PASS", "firstRevision", first, "secondRevision", second,
                        "checks", List.of("real packaged offline CLI", "A/global expire while B cursor/read survive", "forward target rewrite",
                                "oversized candidate preserves map", "explicit removal and empty selection", "Git history and unrelated work preserved"),
                        "agentAcceptance", "NOT_EXECUTED_BY_THIS_JOURNEY")));
            }
        }
    }

    @Test
    void packagedUpdateAndPartialAndLastRemovalCandidatesRecoverByRepeatingSameOperation() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(root.resolve("work"));
        for (String operation : List.of("update", "partial-remove", "final-remove")) {
            String token = UUID.randomUUID().toString();
            try (DocumentFixture fixture = new DocumentFixture(work.resolve("partial-" + operation + "/documents"));
                    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                fixture.git.getRepository().getConfig().setString("user", null, "name", "Local operator fixture");
                fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
                fixture.git.getRepository().getConfig().save();
                Files.createDirectories(fixture.root.resolve("repositories/sample"));
                Files.createDirectories(fixture.root.resolve("repositories/b"));
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Before\nStill readable\n");
                String first = success(command(jar, fixture, work, "update", "--source",
                        "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md")).path("activeRevisions").path("sample").stringValue();
                if (operation.equals("partial-remove")) {
                    Files.writeString(fixture.root.resolve("repositories/sample/other.md"), "# Other\nStill readable\n");
                    first = success(command(jar, fixture, work, "update", "--source", "sample@" + DocumentFixture.SOURCE_REVISION,
                            "--file", "other.md")).path("activeRevisions").path("sample").stringValue();
                }
                Files.writeString(fixture.root.resolve("repositories/b/guide.md"), "# B\nUnchanged owner\n");
                String b = success(command(jar, fixture, work, "update", "--source", "b@" + DocumentFixture.SOURCE_REVISION,
                        "--file", "guide.md")).path("activeRevisions").path("b").stringValue();
                byte[] selection = Files.readAllBytes(fixture.publication);
                try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("partial-" + operation + "-reader.log"))) {
                    service.awaitReady(client);
                    initialize(client, service.uri, token);
                    if (operation.equals("update")) { Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Candidate\nRecover explicitly\n"); }
                    String[] arguments = operation.equals("update") ? new String[] {"update", "--source", "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"}
                            : new String[] {"remove", "--repository-id", "sample", "--file", "guide.md"};
                    Path parent = fixture.publication.getParent();
                    Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
                    CommandResult failed;
                    try {
                        Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(parent),
                                "Selection permission fault requires an unprivileged POSIX test user");
                        failed = command(jar, fixture, work, arguments);
                    } finally { Files.setPosixFilePermissions(parent, permissions); }
                    assertThat(failed.exit()).isEqualTo(4);
                    JsonNode error = mapper.readTree(failed.output());
                    assertThat(error.path("code").stringValue()).isEqualTo("LOCAL_DOCUMENT_IO_FAILURE");
                    assertThat(error.path("phase").stringValue()).isEqualTo("ACTIVATE");
                    String candidate = error.path("candidateRevision").stringValue();
                    assertThat(candidate).isNotEqualTo(first);
                    assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(candidate);
                    assertThat(Files.readAllBytes(fixture.publication)).isEqualTo(selection);
                    JsonNode oldRead = call(client, service.uri, token, "read_document", readArguments(first, 1));
                    assertSuccess(oldRead);
                    assertThat(oldRead.path("structuredContent").path("content").stringValue()).isEqualTo("# Before\nStill readable\n");
                    JsonNode status = success(command(jar, fixture, work, "status"));
                    assertThat(status.path("activeRevisions").path("sample").stringValue()).isEqualTo(first);
                    assertThat(status.path("activeRevisions").path("b").stringValue()).isEqualTo(b);
                    assertThat(status.path("candidateRevision").stringValue()).isEqualTo(candidate);
                    JsonNode recovered = success(command(jar, fixture, work, arguments));
                    assertThat(recovered.path("commitCreated").booleanValue()).isFalse();
                    assertThat(recovered.path("selectionChanged").booleanValue()).isTrue();
                    assertThat(recovered.path("activeRevisions").path("b").stringValue()).isEqualTo(b);
                    assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(candidate);
                    assertError(call(client, service.uri, token, "read_document", readArguments(first, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                    if (operation.equals("update")) {
                        JsonNode newRead = call(client, service.uri, token, "read_document", readArguments(candidate, 1));
                        assertSuccess(newRead);
                        assertThat(newRead.path("structuredContent").path("content").stringValue()).isEqualTo("# Candidate\nRecover explicitly\n");
                    } else if (operation.equals("final-remove")) {
                        assertThat(recovered.path("activeRevisions").has("sample")).isFalse();
                    } else { assertThat(recovered.path("activeRevisions").path("sample").stringValue()).isEqualTo(candidate); }
                    assertSuccess(call(client, service.uri, token, "read_document", readArguments("b", b, "repositories/b/guide.md", 1)));
                    Files.write(root.resolve("artifacts/document-local-" + operation + "-recovery-acceptance.json"),
                            mapper.writeValueAsBytes(Map.of("status", "PASS", "priorRevision", first, "candidateRevision", candidate,
                                    "failurePhase", "ACTIVATE", "recovery", "explicit packaged status and same-operation repeat; unchanged B verified through MCP")));
                }
            }
        }
    }

    private JsonNode success(CommandResult result) {
        assertThat(result.exit()).as("Actual offline CLI exit").isZero();
        return mapper.readTree(result.output());
    }

    private CommandResult command(Path jar, DocumentFixture fixture, Path work, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xms32m", "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Dserver.port=0", "-jar", jar.toString(), "--document-local"));
        command.addAll(List.of(arguments));
        Path stdout = work.resolve("local-command-" + UUID.randomUUID() + ".stdout");
        Path stderr = stdout.resolveSibling(stdout.getFileName() + ".stderr");
        Path marker = stdout.resolveSibling(stdout.getFileName() + ".running");
        ProcessBuilder builder = new ProcessBuilder(command).directory(fixture.root.toFile());
        builder.environment().remove("DOCUMENT_QUERY_API_TOKEN");
        builder.environment().putAll(Map.of("DOCUMENT_REPOSITORY_ID", DocumentFixture.DOCUMENT_REPOSITORY,
                "DOCUMENT_GIT_DIRECTORY", fixture.root.resolve(".git").toString(),
                "DOCUMENT_PUBLICATION_FILE", fixture.publication.toString(), "DOCUMENT_COLLECTION_PURPOSE", "TEST_ONLY"));
        Files.writeString(marker, "STARTING");
        Process process = null;
        try {
            process = builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Offline CLI exits instead of launching a web service").isTrue();
            assertThat(Files.size(stdout)).isLessThanOrEqualTo(16 * 1024);
            return new CommandResult(process.exitValue(), Files.readString(stdout, StandardCharsets.UTF_8));
        } finally {
            if (Objects.nonNull(process)) { reap(process, marker); }
            else { Files.deleteIfExists(marker); }
        }
    }

    private record CommandResult(int exit, String output) { }
}
