package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentContract.Trust;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.application.DocumentCursorCodec;
import com.java.semantic.document.application.DocumentOperationGate;
import com.java.semantic.document.application.DocumentQueryService;
import com.java.semantic.document.mcp.DocumentQueryFacade;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.assertThat;

class DocumentCatalogAdmissionTest {
    @TempDir Path root;
    private static final String STABLE = "# Stable\nlast good";
    private DocumentDeadline deadline() { return DocumentDeadline.after(Duration.ofSeconds(30)); }

    private void rejected(DocumentCatalog catalog, String stable, Code code) {
        try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
            assertThat(lease.view().activeRevision()).isEqualTo(stable);
            assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
            assertThat(lease.view().publicationErrorCode()).contains(code);
            assertThat(lease.view().revisions().get(stable).documents().get(DocumentFixture.GUIDE).content()).isEqualTo(STABLE);
        }
    }

    @Test
    void alternativeFilenameIsAllowedButDescendantDirectoryIsNot() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE, "repositories/sample/other.md", "# Other"));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().revisions().get(first).documents().get("repositories/sample/other.md").content()).isEqualTo("# Other");
                }
                String bad = fixture.commit(Map.of("repositories/sample/nested/guide.md", "# Nested"));
                fixture.publish(bad, bad);
                rejected(catalog, first, Code.DOCUMENT_CATALOG_INVALID);
            }
        }
    }

    @Test
    void distinctCatalogAndSelectionCapacitiesPreserveLastGood() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                Files.writeString(fixture.publication, " ".repeat(16_385));
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
                String[] revisions = new String[9];
                for (int index = 0; index < revisions.length; index++) { revisions[index] = String.format("%040x", index + 1); }
                fixture.publish(revisions[0], revisions);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
                String largeCatalog = replaceCatalog(fixture, " ".repeat(262_145));
                fixture.publish(largeCatalog, largeCatalog);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);

                byte[] content = STABLE.getBytes(StandardCharsets.UTF_8);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int index = 0; index < 129; index++) { rows.add(fixture.row("repositories/sample/doc" + index + ".md", content)); }
                String manyDocuments = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), rows);
                fixture.publish(manyDocuments, manyDocuments);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);

                Map<String, Object> longPath = fixture.row("repositories/sample/" + "x".repeat(490) + ".md", content);
                String pathRevision = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(longPath));
                fixture.publish(pathRevision, pathRevision);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);

                Map<String, Object> tooManyContexts = fixture.row(DocumentFixture.GUIDE, content);
                List<Map<String, String>> related = new ArrayList<>();
                for (int index = 0; index < 33; index++) { related.add(Map.of("repositoryId", "related" + index, "revision", "b".repeat(40))); }
                tooManyContexts.put("relatedContexts", related);
                String contextsRevision = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(tooManyContexts));
                fixture.publish(contextsRevision, contextsRevision);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
            }
        }
    }

    @Test
    void perDocumentAndAggregateSectionsAreBoundedIndependently() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String perDocument = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Section\n".repeat(257)));
                fixture.publish(perDocument, perDocument);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
                Map<String, String> documents = new LinkedHashMap<>();
                for (int index = 0; index < 33; index++) { documents.put("repositories/sample/doc" + index + ".md", "# Section\n".repeat(256)); }
                String aggregate = fixture.commit(documents);
                fixture.publish(aggregate, aggregate);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
            }
        }
    }

    @Test
    void retainedMarkdownTotalCannotExceedBoundEvenWhenEveryRevisionFits() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                Map<String, String> documents = new LinkedHashMap<>();
                for (int index = 0; index < 128; index++) { documents.put("repositories/sample/doc" + index + ".md", "x".repeat(65_536)); }
                String second = fixture.commit(documents);
                String third = fixture.commit(documents);
                fixture.publish(third, first, second, third);
                rejected(catalog, first, Code.DOCUMENT_CAPACITY_EXCEEDED);
            }
        }
    }

    @Test
    void identityPathStatusAndStrictJsonViolationsCannotReplacePublication() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                byte[] content = STABLE.getBytes(StandardCharsets.UTF_8);
                List<Consumer<Map<String, Object>>> mutations = List.of(
                        row -> row.put("unknown", true),
                        row -> row.put("primaryContext", Map.of("repositoryId", "sample", "revision", "main")),
                        row -> row.put("relatedContexts", List.of(Map.of("repositoryId", "sample", "revision", "b".repeat(40)))),
                        row -> row.put("relatedContexts", List.of(Map.of("repositoryId", "other", "revision", "b".repeat(40)), Map.of("repositoryId", "other", "revision", "c".repeat(40)))),
                        row -> row.put("path", "repositories/sample/../guide.md"),
                        row -> row.put("path", "repositories/sample/\\guide.md"),
                        row -> row.put("reviewStatus", "APPROVED"));
                for (Consumer<Map<String, Object>> mutation : mutations) {
                    Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, content);
                    mutation.accept(row);
                    String bad = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(row));
                    fixture.publish(bad, bad);
                    rejected(catalog, first, Code.DOCUMENT_CATALOG_INVALID);
                }
                String validCatalog = fixture.mapper.writeValueAsString(Map.of("formatVersion", 1,
                        "documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY, "documents", List.of(fixture.row(DocumentFixture.GUIDE, content))));
                for (String invalid : List.of(validCatalog.replace(DocumentFixture.DOCUMENT_REPOSITORY, "wrong-repository"),
                        validCatalog.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
                        validCatalog.substring(0, validCatalog.length() - 1) + ",\"unknown\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}")) {
                    String bad = replaceCatalog(fixture, invalid);
                    fixture.publish(bad, bad);
                    rejected(catalog, first, Code.DOCUMENT_CATALOG_INVALID);
                }
                fixture.publish(first, first);
                String selection = Files.readString(fixture.publication);
                Files.writeString(fixture.publication, selection.replace(DocumentFixture.DOCUMENT_REPOSITORY, "wrong-repository"));
                rejected(catalog, first, Code.DOCUMENT_CATALOG_INVALID);
            }
        }
    }

    @Test
    void gitlinkIsNotARegularMarkdownBlob() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String first = fixture.commit(Map.of(DocumentFixture.GUIDE, STABLE));
            fixture.publish(first, first);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                try (ObjectInserter inserter = fixture.git.getRepository().newObjectInserter()) {
                    byte[] catalogBytes = Files.readAllBytes(fixture.root.resolve("navigation-catalog.json"));
                    TreeFormatter sample = new TreeFormatter();
                    sample.append("guide.md", FileMode.GITLINK, ObjectId.fromString(first));
                    TreeFormatter repositories = new TreeFormatter();
                    repositories.append("sample", FileMode.TREE, inserter.insert(sample));
                    TreeFormatter tree = new TreeFormatter();
                    tree.append("navigation-catalog.json", FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, catalogBytes));
                    tree.append("repositories", FileMode.TREE, inserter.insert(repositories));
                    CommitBuilder commit = new CommitBuilder();
                    commit.setTreeId(inserter.insert(tree));
                    commit.setParentId(ObjectId.fromString(first));
                    PersonIdent author = new PersonIdent("Fixture", "fixture@example.test");
                    commit.setAuthor(author);
                    commit.setCommitter(author);
                    commit.setMessage("owned gitlink fixture");
                    String bad = inserter.insert(commit).name();
                    inserter.flush();
                    fixture.publish(bad, bad);
                    rejected(catalog, first, Code.DOCUMENT_CATALOG_INVALID);
                }
            }
        }
    }

    @Test
    void maximumSourceMetadataAndLargestEncodedSectionRemainUnverifiedAndBadEnvelopePreservesThem() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String body = "# " + "\\".repeat(160) + "\n# " + "😀".repeat(160) + "\n" + "x".repeat(32_000);
            byte[] content = body.getBytes(StandardCharsets.UTF_8);
            Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, content);
            row.put("reviewStatus", "APPROVED");
            row.put("reviewReference", "\\".repeat(40_000));
            List<Map<String, String>> related = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                related.add(Map.of("repositoryId", String.format("r%02d", index) + "x".repeat(61), "revision", "b".repeat(40)));
            }
            row.put("relatedContexts", related);
            String first = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(row));
            fixture.publish(first, first);
            DocumentResultWriter writer = new DocumentResultWriter(fixture.mapper);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.APPROVED).storage(), writer)) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.APPROVED).storage(), loader);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    DocumentCatalog.Document document = lease.view().revisions().get(first).documents().get(DocumentFixture.GUIDE);
                    assertThat(document.readResult(lease.view().context(first), document.sections().getLast().section(), CollectionPurpose.APPROVED,
                            PublicationStatus.READY, Optional.empty()).trust()).isEqualTo(Trust.NOT_VERIFIED);
                    assertThat(fixture.mapper.writeValueAsBytes(writer.success(document.readResult(lease.view().context(first),
                            document.sections().getLast().section(), CollectionPurpose.APPROVED, PublicationStatus.UPDATE_REJECTED,
                            Optional.of(Code.DOCUMENT_CAPACITY_EXCEEDED)))).length).isLessThanOrEqualTo(512 * 1024);
                }
                byte[] oversized = "\\".repeat(65_536).getBytes(StandardCharsets.UTF_8);
                row.put("contentDigest", fixture.row(DocumentFixture.GUIDE, oversized).get("contentDigest"));
                String bad = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, oversized), List.of(row));
                fixture.publish(bad, bad);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(first);
                    assertThat(lease.view().publicationErrorCode()).contains(Code.DOCUMENT_CAPACITY_EXCEEDED);
                    assertThat(lease.view().revisions().get(first).documents().get(DocumentFixture.GUIDE).content()).isEqualTo(body);
                }
                DocumentQueryFacade facade = new DocumentQueryFacade(new DocumentQueryService(catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate()), writer);
                try (InputStream schemas = Objects.requireNonNull(getClass().getResourceAsStream("/document-tools.json"))) {
                    JsonNode tools = fixture.mapper.readTree(schemas);
                    for (int index = 0; index < tools.size(); index++) {
                        JsonNode tool = tools.get(index);
                        String name = tool.path("name").stringValue();
                        Map<String, Object> arguments = "search_documents".equals(name) ? Map.of("query", "x")
                                : Map.of("documentContext", Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                                        "documentRevision", first), "path", DocumentFixture.GUIDE, "sectionStartLine", 1);
                        JsonNode wire = fixture.mapper.valueToTree(facade.execute(name, arguments));
                        assertThat(wire.path("isError").booleanValue()).isFalse();
                        JsonNode result = wire.path("structuredContent");
                        assertThat(fixture.mapper.readTree(wire.path("content").get(0).path("text").stringValue())).isEqualTo(result);
                        assertThat(result.path("documentContext").path("documentRevision").stringValue()).isEqualTo(first);
                        assertThat(result.path("publicationStatus").stringValue()).isEqualTo("UPDATE_REJECTED");
                        JsonNode allowed = tool.path("outputSchema").path("oneOf").get(0)
                                .path("properties").path("publicationErrorCode").path("enum");
                        List<String> allowedCodes = new ArrayList<>();
                        for (int codeIndex = 0; codeIndex < allowed.size(); codeIndex++) {
                            allowedCodes.add(allowed.get(codeIndex).stringValue());
                        }
                        assertThat(allowedCodes).contains(result.path("publicationErrorCode").stringValue());
                        if ("read_document".equals(name)) {
                            assertThat(result.path("content").stringValue()).isEqualTo(body);
                        } else {
                            assertThat(result.path("items").get(0).path("documentContext")
                                    .path("documentRevision").stringValue()).isEqualTo(first);
                        }
                    }
                }
            }
        }
    }

    private String replaceCatalog(DocumentFixture fixture, String json) throws Exception {
        Files.writeString(fixture.root.resolve("navigation-catalog.json"), json);
        fixture.git.add().addFilepattern("navigation-catalog.json").call();
        return fixture.git.commit().setMessage("owned invalid catalog").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").setAllowEmpty(true).call().name();
    }
}
