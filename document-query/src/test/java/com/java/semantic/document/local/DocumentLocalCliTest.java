package com.java.semantic.document.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.catalog.GitDocumentLoader;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
import java.util.Set;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

class DocumentLocalCliTest {
    @TempDir Path temporary;

    @Test
    void updateCommitsNamedContentWithSeparatePrimaryAndRelatedContexts() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("documents"))) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Document operator");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            String body = "# Member guide\nSource navigation only.\n";
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), body);
            Files.writeString(fixture.root.resolve("unrelated.txt"), "keep this draft");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int exit = DocumentLocalMain.run(new String[] {"update", "--source", "sample@" + "a".repeat(40),
                    "--file", "guide.md", "--related", "billing@" + "b".repeat(40)}, environment(fixture),
                    new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
            assertThat(exit).isZero();
            JsonNode result = fixture.mapper.readTree(output.toByteArray());
            String revision = result.path("activeRevisions").path("sample").stringValue();
            assertThat(result.path("serviceVerified").booleanValue()).isFalse();
            assertThat(result.path("repositoryId").stringValue()).isEqualTo("sample");
            assertThat(result.has("activeRevision")).isFalse();
            assertThat(result.has("retainedRevisions")).isFalse();
            ByteArrayOutputStream statusOutput = new ByteArrayOutputStream();
            assertThat(DocumentLocalMain.run(new String[] {"status"}, environment(fixture),
                    new PrintStream(statusOutput, true, StandardCharsets.UTF_8), System.err)).isZero();
            JsonNode status = fixture.mapper.readTree(statusOutput.toByteArray());
            assertThat(status.has("repositoryId")).isFalse();
            assertThat(status.path("activeRevisions").path("sample").stringValue()).isEqualTo(revision);
            assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(revision);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(),
                    new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog.RepositorySnapshot snapshot = loader.load(revision, Set.of("sample"), DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT),
                        DocumentContract.MAX_DOCUMENTS, DocumentContract.MAX_TOTAL_BYTES, DocumentContract.MAX_TOTAL_SECTIONS).get("sample");
                DocumentCatalog.Document document = snapshot.documents().get(DocumentFixture.GUIDE);
                assertThat(document.content()).isEqualTo(body);
                assertThat(document.primaryContext()).isEqualTo(new SourceContext("sample", "a".repeat(40)));
                assertThat(document.relatedContexts()).containsExactly(new SourceContext("billing", "b".repeat(40)));
                assertThat(snapshot.documents()).containsOnlyKeys(DocumentFixture.GUIDE);
            }
            assertThat(Files.readString(fixture.root.resolve("unrelated.txt"))).isEqualTo("keep this draft");
            assertThat(fixture.git.status().call().getUntracked()).contains("unrelated.txt");
        }
    }

    @Test
    void explicitRemoveDeletesNamedDocumentAndCatalogMembership() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("removal"))) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Document operator");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            String otherPath = "repositories/sample/other.md";
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Remove me\n",
                    otherPath, "# Keep me\n"));
            fixture.publish(Map.of("sample", revision));
            Files.writeString(fixture.root.resolve(otherPath), "unrelated dirty document bytes");
            Files.writeString(fixture.root.resolve("unrelated.txt"), "untracked draft bytes");
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            int exit = DocumentLocalMain.run(new String[] {"remove", "--repository-id", "sample",
                    "--file", "guide.md"}, environment(fixture),
                    new PrintStream(output, true, StandardCharsets.UTF_8), System.err);

            assertThat(exit).isZero();
            JsonNode removal = fixture.mapper.readTree(output.toByteArray());
            assertThat(removal.path("repositoryId").stringValue()).isEqualTo("sample");
            assertThat(removal.path("activeRevisions").path("sample").stringValue()).hasSize(40);
            assertThat(removal.path("serviceVerified").booleanValue()).isFalse();
            assertThat(removal.has("activeRevision")).isFalse();
            assertThat(removal.has("retainedRevisions")).isFalse();
            assertThat(Files.exists(fixture.root.resolve(DocumentFixture.GUIDE))).isFalse();
            JsonNode catalog = fixture.mapper.readTree(Files.readAllBytes(fixture.root.resolve("navigation-catalog.json")));
            assertThat(catalog.path("documents").size()).isEqualTo(1);
            assertThat(catalog.path("documents").get(0).path("path").stringValue()).isEqualTo(otherPath);
            assertThat(fixture.git.getRepository().readDirCache().getEntry(DocumentFixture.GUIDE)).isNull();
            assertThat(Files.readString(fixture.root.resolve(otherPath))).isEqualTo("unrelated dirty document bytes");
            assertThat(Files.readString(fixture.root.resolve("unrelated.txt"))).isEqualTo("untracked draft bytes");
        }
    }

    @Test
    void malformedCommandsLeaveHeadIndexAndPublicationUnchanged() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("documents"))) {
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Guide\nOriginal\n"));
            fixture.publish(Map.of("sample", revision));
            byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
            byte[] selection = Files.readAllBytes(fixture.publication);
            List<String[]> commands = List.of(
                    new String[] {"update", "--source", "sample@abc123", "--file", "guide.md"},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md", "--file", "guide.md"},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md",
                            "--related", "billing@" + "b".repeat(40), "--related", "billing@" + "c".repeat(40)},
                    new String[] {"activate", "--revision", revision, "--force"},
                    new String[] {"activate", "--revision", revision},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md", "--withdraw", revision},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md", "--revision", revision},
                    new String[] {"status", "--source", "sample@" + "a".repeat(40)});
            for (String[] command : commands) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                int exit = DocumentLocalMain.run(command, environment(fixture),
                        new PrintStream(output, true, StandardCharsets.UTF_8), System.err);
                assertThat(exit).isEqualTo(2);
                assertThat(fixture.mapper.readTree(output.toByteArray()).path("code").stringValue())
                        .isEqualTo("LOCAL_DOCUMENT_INVALID_ARGUMENT");
                assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(revision);
                assertThat(Files.readAllBytes(fixture.root.resolve(".git/index"))).isEqualTo(index);
                assertThat(Files.readAllBytes(fixture.publication)).isEqualTo(selection);
            }
        }
    }

    @Test
    void helpNeedsNoConfigurationAndMissingSettingsCannotCreatePublication() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(DocumentLocalMain.run(new String[] {"--help"}, Map.of(),
                new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isZero();
        output.reset();
        assertThat(DocumentLocalMain.run(new String[] {"status"},
                Map.of("DOCUMENT_PUBLICATION_FILE", temporary.resolve("missing.json").toString()),
                new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isEqualTo(2);
        try (Stream<Path> paths = Files.list(temporary)) {
            assertThat(paths.toList()).isEmpty();
        }
    }

    @Test
    void approvedCollectionRequiresExplicitReviewBeforeAnyCommit() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("documents"))) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Test operator");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "operator@example.test");
            fixture.git.getRepository().getConfig().save();
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Approved fixture\n");
            Map<String, String> env = new java.util.HashMap<>(environment(fixture));
            env.put("DOCUMENT_COLLECTION_PURPOSE", "APPROVED");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            String[] unreviewed = {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md"};
            assertThat(DocumentLocalMain.run(unreviewed, env,
                    new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isEqualTo(2);
            assertThat(fixture.git.getRepository().resolve("HEAD")).isNull();
            assertThat(Files.exists(fixture.publication)).isFalse();
            assertThat(Files.exists(fixture.root.resolve(".git/index"))).isFalse();
            output.reset();
            assertThat(DocumentLocalMain.run(new String[] {"update", "--source", "sample@" + "a".repeat(40),
                    "--file", "guide.md", "--review-reference", "TEST_ONLY review record"}, env,
                    new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isZero();
            String revision = fixture.mapper.readTree(output.toByteArray()).path("activeRevisions").path("sample").stringValue();
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.APPROVED).storage(),
                    new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog.Document document = loader.load(revision, Set.of("sample"),
                        DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT), DocumentContract.MAX_DOCUMENTS,
                        DocumentContract.MAX_TOTAL_BYTES, DocumentContract.MAX_TOTAL_SECTIONS).get("sample").documents().get(DocumentFixture.GUIDE);
                assertThat(document.reviewStatus()).isEqualTo(DocumentContract.ReviewStatus.APPROVED);
                assertThat(document.reviewReference()).contains("TEST_ONLY review record");
                assertThat(document.content()).isEqualTo("# Approved fixture\n");
            }
            output.reset();
            assertThat(DocumentLocalMain.run(new String[] {"remove", "--repository-id", "sample", "--file", "guide.md"},
                    env, new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isEqualTo(2);
            assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(revision);
            assertThat(Files.readString(fixture.root.resolve(DocumentFixture.GUIDE))).isEqualTo("# Approved fixture\n");
            output.reset();
            assertThat(DocumentLocalMain.run(new String[] {"remove", "--repository-id", "sample", "--file", "guide.md",
                    "--review-reference", "TEST_ONLY removal review"}, env,
                    new PrintStream(output, true, StandardCharsets.UTF_8), System.err)).isZero();
            assertThat(fixture.mapper.readTree(output.toByteArray()).path("activeRevisions").isEmpty()).isTrue();
            assertThat(Files.exists(fixture.root.resolve(DocumentFixture.GUIDE))).isFalse();
        }
    }

    private Map<String, String> environment(DocumentFixture fixture) {
        return Map.of("DOCUMENT_REPOSITORY_ID", DocumentFixture.DOCUMENT_REPOSITORY,
                "DOCUMENT_GIT_DIRECTORY", fixture.root.resolve(".git").toString(),
                "DOCUMENT_PUBLICATION_FILE", fixture.publication.toString(),
                "DOCUMENT_COLLECTION_PURPOSE", "TEST_ONLY");
    }
}
