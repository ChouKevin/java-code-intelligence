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
    void packagedLocalUpdatesRetainPinnedReadsAndRollbackWithoutMovingHead() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(root.resolve("work"));
        Path artifacts = Files.createDirectories(root.resolve("artifacts"));
        String token = UUID.randomUUID().toString();
        String firstBody = "# HIT First\r\nLocal guide A\r\n# HIT Second\r\nRetained evidence";
        String secondBody = "# HIT Updated\nLocal guide B";
        try (DocumentFixture fixture = new DocumentFixture(work.resolve("local-publication/documents"));
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Local operator fixture");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), firstBody);
            Files.writeString(fixture.root.resolve("unrelated.txt"), "never included");
            assertThat(command(jar, fixture, work, "--help").exit()).isZero();
            JsonNode firstUpdate = success(command(jar, fixture, work, "update", "--source",
                    "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"));
            String first = firstUpdate.path("activeRevision").stringValue();
            assertThat(firstUpdate.path("serviceVerified").booleanValue()).isFalse();
            assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(first);
            try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("local-reader.log"))) {
                service.awaitReady(client);
                initialize(client, service.uri, token);
                JsonNode searchA = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1));
                assertSuccess(searchA);
                String cursor = searchA.path("structuredContent").path("page").path("nextCursor").stringValue();
                assertThat(searchA.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), secondBody);
                JsonNode secondUpdate = success(command(jar, fixture, work, "update", "--source",
                        "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"));
                String second = secondUpdate.path("activeRevision").stringValue();
                assertThat(second).isNotEqualTo(first);
                JsonNode searchB = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(searchB);
                assertThat(searchB.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(second);
                JsonNode readB = call(client, service.uri, token, "read_document", readArguments(second, 1));
                assertSuccess(readB);
                assertThat(readB.path("structuredContent").path("content").stringValue()).isEqualTo(secondBody);
                assertThat(readB.path("structuredContent").path("primaryContext").path("revision").stringValue())
                        .isEqualTo(DocumentFixture.SOURCE_REVISION);
                JsonNode continued = call(client, service.uri, token, "search_documents", Map.of("query", "HIT", "limit", 1, "cursor", cursor));
                assertSuccess(continued);
                assertThat(continued.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                assertThat(continued.path("structuredContent").path("items").get(0).path("section").path("startLine").intValue()).isEqualTo(3);
                JsonNode readA = call(client, service.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(readA);
                assertThat(readA.path("structuredContent").path("content").stringValue()).isEqualTo(firstBody);
                success(command(jar, fixture, work, "activate", "--revision", first));
                assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(second);
                JsonNode rolledBack = call(client, service.uri, token, "search_documents", Map.of("query", "HIT"));
                assertSuccess(rolledBack);
                assertThat(rolledBack.path("structuredContent").path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "x".repeat(65537));
                CommandResult rejected = command(jar, fixture, work, "update", "--source",
                        "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md");
                assertThat(rejected.exit()).isEqualTo(2);
                assertThat(mapper.readTree(rejected.output()).path("code").stringValue()).isEqualTo("DOCUMENT_CAPACITY_EXCEEDED");
                assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(second);
                JsonNode stillA = call(client, service.uri, token, "read_document", readArguments(first, 1));
                assertSuccess(stillA);
                assertThat(stillA.path("structuredContent").path("content").stringValue()).isEqualTo(firstBody);
                JsonNode status = success(command(jar, fixture, work, "status"));
                assertThat(status.path("activeRevision").stringValue()).isEqualTo(first);
                assertThat(status.path("candidateRevision").stringValue()).isEqualTo(second);
                success(command(jar, fixture, work, "activate", "--revision", first, "--withdraw", second));
                assertError(call(client, service.uri, token, "read_document", readArguments(second, 1)), "DOCUMENT_REVISION_NOT_AVAILABLE");
                assertThat(fixture.git.getRepository().resolve(second + "^{commit}").name()).isEqualTo(second);
                assertThat(Files.readString(fixture.root.resolve("unrelated.txt"))).isEqualTo("never included");
                assertThat(fixture.git.status().call().getUntracked()).contains("unrelated.txt");
                Files.write(artifacts.resolve("document-local-native-acceptance.json"), mapper.writeValueAsBytes(Map.of(
                        "status", "PASS", "firstRevision", first, "secondRevision", second,
                        "checks", List.of("real packaged offline CLI without token", "selected content preserved",
                                "new search B, old A cursor/read", "source SHA unchanged", "rollback without HEAD movement",
                                "oversized candidate preserves A", "explicit withdrawal preserves Git history", "unrelated work preserved"),
                        "agentAcceptance", "NOT_EXECUTED_BY_THIS_JOURNEY")));
            }
        }
    }

    @Test
    void packagedCandidateSurvivesSelectionFailureAndExplicitActivationRecovers() throws Exception {
        Path root = Path.of(Objects.requireNonNull(System.getProperty("document.journey.root")));
        Path jar = Path.of(Objects.requireNonNull(System.getProperty("document.journey.jar")));
        Path work = Files.createDirectories(root.resolve("work"));
        String token = UUID.randomUUID().toString();
        try (DocumentFixture fixture = new DocumentFixture(work.resolve("partial-publication/documents"));
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Local operator fixture");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Before\nStill readable\n");
            String first = success(command(jar, fixture, work, "update", "--source",
                    "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md"))
                    .path("activeRevision").stringValue();
            byte[] selection = Files.readAllBytes(fixture.publication);
            try (OwnedService service = new OwnedService(jar, fixture, token, work.resolve("partial-reader.log"))) {
                service.awaitReady(client);
                initialize(client, service.uri, token);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Candidate\nRecover explicitly\n");
                Path parent = fixture.publication.getParent();
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(parent);
                CommandResult failed;
                try {
                    Files.setPosixFilePermissions(parent, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
                    org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(parent),
                            "Selection permission fault requires an unprivileged POSIX test user");
                    failed = command(jar, fixture, work, "update", "--source",
                            "sample@" + DocumentFixture.SOURCE_REVISION, "--file", "guide.md");
                } finally {
                    Files.setPosixFilePermissions(parent, permissions);
                }
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
                assertThat(status.path("activeRevision").stringValue()).isEqualTo(first);
                assertThat(status.path("candidateRevision").stringValue()).isEqualTo(candidate);
                JsonNode activated = success(command(jar, fixture, work, "activate", "--revision", candidate));
                assertThat(activated.path("commitCreated").booleanValue()).isFalse();
                assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(candidate);
                JsonNode newRead = call(client, service.uri, token, "read_document", readArguments(candidate, 1));
                assertSuccess(newRead);
                assertThat(newRead.path("structuredContent").path("content").stringValue()).isEqualTo("# Candidate\nRecover explicitly\n");
                Files.write(root.resolve("artifacts/document-local-recovery-acceptance.json"),
                        mapper.writeValueAsBytes(Map.of("status", "PASS", "priorRevision", first,
                                "candidateRevision", candidate, "failurePhase", "ACTIVATE",
                                "recovery", "explicit packaged status/activate; MCP read exact candidate")));
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
