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
            String revision = result.path("activeRevision").stringValue();
            assertThat(result.path("serviceVerified").booleanValue()).isFalse();
            assertThat(fixture.git.getRepository().resolve("HEAD").name()).isEqualTo(revision);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(),
                    new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog.RevisionSnapshot snapshot = loader.load(revision, DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT));
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
    void malformedCommandsLeaveHeadIndexAndPublicationUnchanged() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("documents"))) {
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Guide\nOriginal\n"));
            fixture.publish(revision, revision);
            byte[] index = Files.readAllBytes(fixture.root.resolve(".git/index"));
            byte[] selection = Files.readAllBytes(fixture.publication);
            List<String[]> commands = List.of(
                    new String[] {"update", "--source", "sample@abc123", "--file", "guide.md"},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md", "--file", "guide.md"},
                    new String[] {"update", "--source", "sample@" + "a".repeat(40), "--file", "guide.md",
                            "--related", "billing@" + "b".repeat(40), "--related", "billing@" + "c".repeat(40)},
                    new String[] {"activate", "--revision", revision, "--force"},
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
            String revision = fixture.mapper.readTree(output.toByteArray()).path("activeRevision").stringValue();
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.APPROVED).storage(),
                    new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog.Document document = loader.load(revision,
                        DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT)).documents().get(DocumentFixture.GUIDE);
                assertThat(document.reviewStatus()).isEqualTo(DocumentContract.ReviewStatus.APPROVED);
                assertThat(document.reviewReference()).contains("TEST_ONLY review record");
                assertThat(document.content()).isEqualTo("# Approved fixture\n");
            }
        }
    }

    private Map<String, String> environment(DocumentFixture fixture) {
        return Map.of("DOCUMENT_REPOSITORY_ID", DocumentFixture.DOCUMENT_REPOSITORY,
                "DOCUMENT_GIT_DIRECTORY", fixture.root.resolve(".git").toString(),
                "DOCUMENT_PUBLICATION_FILE", fixture.publication.toString(),
                "DOCUMENT_COLLECTION_PURPOSE", "TEST_ONLY");
    }
}
