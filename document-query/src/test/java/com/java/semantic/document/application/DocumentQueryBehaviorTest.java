package com.java.semantic.document.application;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.ListRequest;
import com.java.semantic.document.application.DocumentContract.ListResult;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentContract.ReadResult;
import com.java.semantic.document.application.DocumentContract.SearchRequest;
import com.java.semantic.document.application.DocumentContract.SearchResult;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.catalog.GitDocumentLoader;
import com.java.semantic.document.config.DocumentQueryProperties;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.document.catalog.LocalDocumentPublisher;
import java.nio.file.Files;
import java.util.ArrayList;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentQueryBehaviorTest {
    private static final String ALPHA_GUIDE = "repositories/sample/a-guide.md";
    private static final String REGEX_GUIDE = "repositories/sample/m-guide.md";
    private static final String ZETA_GUIDE = "repositories/sample/z-guide.md";
    @TempDir Path root;

    @Test
    void literalSearchIsOrderedAndReadReturnsTheEntireOriginalDocument() throws Exception {
        String tail = "😀".repeat(260);
        String alpha = "# Alpha\r\nC++ (λ) first occurrence then C++ (λ) again\r\n"
                + "## Beta\r\nC++ (λ) first in Beta then C++ (λ) again. Evidence and qualification retained.\r\n"
                + tail;
        String zeta = "# Zeta\nC++ (λ) at the end";
        String regexOnly = "# Regex only\nCC λ";
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put(ZETA_GUIDE, zeta);
        documents.put(REGEX_GUIDE, regexOnly);
        documents.put(ALPHA_GUIDE, alpha);

        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String revision = fixture.commit(documents);
            fixture.publish(Map.of("sample", revision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                MaterializedSearch materialized = query.service.search(new SearchRequest("C++ (λ)", 10,
                        Optional.empty(), Optional.empty()), result -> new MaterializedSearch(result, query.writer.success(result)));
                McpSchema.CallToolResult wireResult = materialized.wireResult();
                SearchResult result = materialized.result();

                assertThat(wireResult.isError()).isFalse();
                assertThat(result.items()).extracting(hit -> hit.path())
                        .containsExactly(ALPHA_GUIDE, ALPHA_GUIDE, ZETA_GUIDE);
                assertThat(result.items()).extracting(hit -> hit.section().startLine()).containsExactly(1, 3, 1);
                assertThat(result.items()).extracting(hit -> hit.section().title())
                        .containsExactly("Alpha", "Beta", "Zeta");
                assertThat(result.items().getFirst().snippet()).startsWith("C++ (λ) first occurrence");
                assertThat(result.items().get(1).snippet()).startsWith("C++ (λ) first in Beta");
                assertThat(result.items().get(1).snippet().codePointCount(0,
                        result.items().get(1).snippet().length())).isEqualTo(DocumentContract.MAX_SNIPPET_CODE_POINTS);
                assertThat(result.items().get(1).snippetTruncated()).isTrue();
                assertThat(result.items().getFirst().snippetTruncated()).isFalse();

                SearchResult caseSensitive = query.service.search(new SearchRequest("c++ (λ)", 10, Optional.empty(), Optional.empty()),
                        value -> value);
                assertThat(caseSensitive.items()).isEmpty();

                ReadResult read = query.service.read(new ReadRequest(result.items().get(1).documentContext(),
                        ALPHA_GUIDE, 3), value -> value);
                assertThat(read.content()).isEqualTo(alpha);
                assertThat(read.byteLength()).isEqualTo(alpha.getBytes(StandardCharsets.UTF_8).length);
                assertThat(read.startLine()).isEqualTo(1);
                assertThat(read.endLine()).isEqualTo(5);
                assertThat(read.section().startLine()).isEqualTo(3);
                assertThat(read.section().title()).isEqualTo("Beta");
                assertThat(read.scope()).isEqualTo(DocumentContract.Scope.FULL_DOCUMENT);
                assertThat(read.documentContext().documentRevision()).isEqualTo(revision);
                assertThat(read.documentContext().documentRevision()).isNotEqualTo(DocumentFixture.SOURCE_REVISION);
                assertThat(read.contentDigest()).hasSize(64);
                assertThat(read.primaryContext().revision()).isEqualTo(DocumentFixture.SOURCE_REVISION);
                assertThat(read.contentDigest()).isEqualTo(result.items().get(1).contentDigest());
                assertThat(read.contentDigest()).isNotEqualTo(read.primaryContext().revision());
            }
        }
    }

    @Test
    void multilineLiteralMatchBelongsToSectionWhereItStarts() throws Exception {
        String queryText = "needle\n## Two";
        String content = "# One\nneedle\n## Two\nbody";
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, content));
            fixture.publish(Map.of("sample", revision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult result = query.service.search(new SearchRequest(queryText, 10, Optional.empty(), Optional.empty()),
                        value -> value);

                assertThat(result.items().getFirst().documentContext().documentRevision()).isEqualTo(revision);
                assertThat(result.items()).hasSize(1);
                assertThat(result.items()).extracting(hit -> hit.path()).containsExactly(DocumentFixture.GUIDE);
                assertThat(result.items()).extracting(hit -> hit.section().startLine()).containsExactly(1);
                assertThat(result.items().getFirst().section().title()).isEqualTo("One");
                assertThat(result.items().getFirst().snippet()).contains(queryText);
                assertThat(result.items().getFirst().snippet().codePointCount(0,
                        result.items().getFirst().snippet().length()))
                        .isLessThanOrEqualTo(DocumentContract.MAX_SNIPPET_CODE_POINTS);
                assertThat(result.items().getFirst().snippetTruncated()).isFalse();
            }
        }
    }

    @Test
    void cursorsBindQueryLimitAndProcessSecretAndExpireOnOwnerChange() throws Exception {
        Map<String, String> firstDocuments = Map.of(DocumentFixture.GUIDE,
                "# First\nneedle\n## Second\nneedle\n## Third\nneedle");
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String firstRevision = fixture.commit(firstDocuments);
            fixture.publish(Map.of("sample", firstRevision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult firstPage = query.service.search(new SearchRequest("needle", 1, Optional.empty(), Optional.empty()),
                        value -> value);
                String cursor = firstPage.page().nextCursor().orElseThrow();
                assertThat(firstPage.items().getFirst().documentContext().documentRevision()).isEqualTo(firstRevision);
                assertThat(firstPage.page().returned()).isEqualTo(1);
                assertThat(firstPage.page().hasMore()).isTrue();

                assertThatThrownBy(() -> query.service.search(new SearchRequest("changed", 1, Optional.of(cursor), Optional.empty()),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 2, Optional.of(cursor), Optional.empty()),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
                int signatureStart = cursor.indexOf('.') + 1;
                char signatureCharacter = cursor.charAt(signatureStart);
                String tampered = cursor.substring(0, signatureStart)
                        + (signatureCharacter == 'A' ? 'B' : 'A') + cursor.substring(signatureStart + 1);
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 1, Optional.of(tampered), Optional.empty()),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));

                DocumentQueryService restarted = new DocumentQueryService(query.catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate());
                assertThatThrownBy(() -> restarted.search(new SearchRequest("needle", 1, Optional.of(cursor), Optional.empty()),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));

                SearchResult secondPage = query.service.search(new SearchRequest("needle", 1, Optional.of(cursor), Optional.empty()),
                        value -> value);
                SearchResult thirdPage = query.service.search(new SearchRequest("needle", 1,
                        secondPage.page().nextCursor(), Optional.empty()), value -> value);
                assertThat(secondPage.items().getFirst().section().startLine()).isEqualTo(3);
                assertThat(thirdPage.items().getFirst().section().startLine()).isEqualTo(5);
                assertThat(thirdPage.page().hasMore()).isFalse();
                String secondRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# New\nneedle from D2"));
                fixture.publish(Map.of("sample", secondRevision));
                SearchResult current = query.service.search(new SearchRequest("needle", 1, Optional.empty(), Optional.empty()),
                        value -> value);
                assertThat(current.items().getFirst().documentContext().documentRevision()).isEqualTo(secondRevision);
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 1, Optional.of(cursor), Optional.empty()),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE));
            }
        }
    }

    @Test
    void realWriterTrimsOversizedSearchPagesAndContinuesWithoutDroppingHits() throws Exception {
        String content = "# One\nneedle\n## Two\nneedle\n## Three\nneedle\n## Four\nneedle";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        String reviewReference = "\\".repeat(40_000);
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, contentBytes);
            row.put("reviewReference", reviewReference);
            String revision = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, contentBytes), List.of(row));
            fixture.publish(Map.of("sample", revision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchRequest request = new SearchRequest("needle", 4, Optional.empty(), Optional.empty());
                SearchResult untrimmed = query.service.search(request, result -> result);
                assertThat(untrimmed.items()).extracting(hit -> hit.section().startLine()).containsExactly(1, 3, 5, 7);
                assertThat(untrimmed.items().getFirst().reviewReference().orElseThrow()).isEqualTo(reviewReference);
                assertThatThrownBy(() -> query.writer.success(untrimmed))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));

                ReadResult fullDocument = query.service.read(new ReadRequest(untrimmed.items().getFirst().documentContext(),
                        DocumentFixture.GUIDE, 1), result -> result);
                McpSchema.CallToolResult fullDocumentWire = query.writer.success(fullDocument);
                assertThat(fullDocument.reviewReference().orElseThrow()).isEqualTo(reviewReference);
                assertThat(fixture.mapper.writeValueAsBytes(fullDocumentWire).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);

                MaterializedSearch oneHit = query.service.search(new SearchRequest("needle", 1, Optional.empty(), Optional.empty()),
                        result -> new MaterializedSearch(result, query.writer.success(result)));
                assertThat(oneHit.result().items()).hasSize(1);
                assertThat(fixture.mapper.writeValueAsBytes(oneHit.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);

                MaterializedSearch firstPage = query.service.search(request,
                        result -> new MaterializedSearch(result, query.writer.success(result)));
                assertThat(firstPage.wireResult().isError()).isFalse();
                assertThat(fixture.mapper.writeValueAsBytes(firstPage.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThat(firstPage.result().items()).extracting(hit -> hit.path() + "#" + hit.section().startLine())
                        .containsExactly(DocumentFixture.GUIDE + "#1", DocumentFixture.GUIDE + "#3");
                assertThat(firstPage.result().page().returned()).isEqualTo(firstPage.result().items().size());
                assertThat(firstPage.result().page().hasMore()).isTrue();
                assertThat(firstPage.result().items())
                        .allSatisfy(hit -> assertThat(hit.reviewReference().orElseThrow()).isEqualTo(reviewReference));
                assertThat(firstPage.result().page().nextCursor()).isPresent();

                SearchRequest continuationRequest = new SearchRequest(request.query(), request.limit(),
                        firstPage.result().page().nextCursor(), Optional.empty());
                MaterializedSearch continuedPage = query.service.search(continuationRequest,
                        result -> new MaterializedSearch(result, query.writer.success(result)));
                assertThat(continuedPage.wireResult().isError()).isFalse();
                assertThat(fixture.mapper.writeValueAsBytes(continuedPage.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThat(continuedPage.result().items().getFirst().documentContext().documentRevision()).isEqualTo(revision);
                assertThat(continuedPage.result().items()).extracting(hit -> hit.path() + "#" + hit.section().startLine())
                        .containsExactly(DocumentFixture.GUIDE + "#5", DocumentFixture.GUIDE + "#7");
                assertThat(continuedPage.result().items())
                        .allSatisfy(hit -> assertThat(hit.reviewReference().orElseThrow()).isEqualTo(reviewReference));
                assertThat(continuedPage.result().page().returned()).isEqualTo(continuedPage.result().items().size());
                assertThat(continuedPage.result().page().hasMore()).isFalse();
                assertThat(continuedPage.result().page().nextCursor()).isEmpty();
                assertThat(List.of(firstPage.result().items().get(0), firstPage.result().items().get(1),
                        continuedPage.result().items().get(0), continuedPage.result().items().get(1)))
                        .extracting(hit -> hit.path() + "#" + hit.section().startLine())
                        .containsExactly(DocumentFixture.GUIDE + "#1", DocumentFixture.GUIDE + "#3",
                                DocumentFixture.GUIDE + "#5", DocumentFixture.GUIDE + "#7");
            }
        }
    }

    @Test
    void readsRequireExactRepositoryRevisionPathAndSectionLocators() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# First\nbody\n## Second\nselected"));
            fixture.publish(Map.of("sample", revision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext("other", "sample", revision),
                        DocumentFixture.GUIDE, 3), value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_REPOSITORY_NOT_FOUND));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, "sample", DocumentFixture.SOURCE_REVISION), DocumentFixture.GUIDE, 3),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, "sample", revision), "missing/guide.md", 3), value -> value))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_NOT_FOUND));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, "sample", revision), DocumentFixture.GUIDE, 2), value -> value))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_SECTION_NOT_FOUND));
            }
        }
    }

    @Test
    void mixedCommitOwnerEnvelopeTrimsRealWirePagesWithoutDroppingOrDuplicatingHits() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("mixed-wire-budget"))) {
            byte[] body = "# One\nneedle\n# Two\nneedle\n".getBytes(StandardCharsets.UTF_8);
            String bPath = "repositories/b/guide.md";
            Map<String, Object> bRow = fixture.row(bPath, body, new SourceContext("b", DocumentFixture.SOURCE_REVISION));
            bRow.put("reviewReference", "\\".repeat(40_000));
            String b = fixture.commitBytes(Map.of(bPath, body), List.of(bRow));
            Map<String, Object> aRow = fixture.row(DocumentFixture.GUIDE, body);
            aRow.put("reviewReference", "\\".repeat(40_000));
            String a = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, body), List.of(aRow));
            fixture.publish(Map.of("sample", a, "b", b));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchRequest request = new SearchRequest("needle", 4, Optional.empty(), Optional.empty());
                SearchResult all = query.service.search(request, value -> value);
                assertThatThrownBy(() -> query.writer.success(all)).isInstanceOfSatisfying(DocumentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));
                List<String> positions = new ArrayList<>();
                Optional<String> cursor = Optional.empty();
                do {
                    MaterializedSearch page = query.service.search(new SearchRequest("needle", 4, cursor, Optional.empty()),
                            value -> new MaterializedSearch(value, query.writer.success(value)));
                    assertThat(fixture.mapper.writeValueAsBytes(page.wireResult()).length).isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                    assertThat(page.result().documentRepositoryId()).isEqualTo(DocumentFixture.DOCUMENT_REPOSITORY);
                    for (DocumentContract.SearchHit hit : page.result().items()) {
                        positions.add(hit.path() + "#" + hit.section().startLine());
                        assertThat(hit.documentContext().documentRevision()).isEqualTo(hit.primaryContext().repositoryId().equals("b") ? b : a);
                    }
                    cursor = page.result().page().nextCursor();
                } while (cursor.isPresent());
                assertThat(positions).containsExactly(bPath + "#1", bPath + "#3", DocumentFixture.GUIDE + "#1", DocumentFixture.GUIDE + "#3");
            }
        }
    }

    @Test
    void ownerReplacementExpiresOnlyItsLocatorsAndScopedCursorAndGlobalCursor() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("isolation"))) {
            String aPath = "repositories/a/guide.md";
            String bPath = "repositories/b/guide.md";
            String a1 = "# A1\nneedle A1\n# More\nneedle A1 more\n";
            String b1 = "# B1\r\nneedle B1\r\n# More\r\nneedle B1 more\r\n";
            String first = commitOwners(fixture, Map.of(aPath, a1, bPath, b1));
            fixture.publish(Map.of("a", first, "b", first));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult global = search(query, Optional.empty(), Optional.empty(), 1);
                SearchResult a = search(query, Optional.of("a"), Optional.empty(), 1);
                SearchResult b = search(query, Optional.of("b"), Optional.empty(), 1);
                DocumentContract.SearchHit bGlobal = search(query, Optional.empty(), Optional.empty(), 10).items().get(2);
                String second = commitOwners(fixture, Map.of(aPath, "# A2\nneedle A2\n", bPath, b1));
                fixture.publish(Map.of("a", second, "b", first));
                SearchResult latest = search(query, Optional.empty(), Optional.empty(), 10);
                assertThat(latest.items()).extracting(hit -> hit.documentContext().documentRevision()).containsExactly(second, first, first);
                assertUnavailable(() -> search(query, Optional.empty(), global.page().nextCursor(), 1));
                assertUnavailable(() -> search(query, Optional.of("a"), a.page().nextCursor(), 1));
                assertUnavailable(() -> query.service.read(new ReadRequest(a.items().getFirst().documentContext(), aPath, 1), value -> value));
                assertThat(search(query, Optional.of("b"), b.page().nextCursor(), 1).items().getFirst().section().startLine()).isEqualTo(3);
                assertThat(query.service.read(new ReadRequest(bGlobal.documentContext(), bPath, 1), value -> value).content()).isEqualTo(b1);
                assertThatThrownBy(() -> query.service.read(new ReadRequest(bGlobal.documentContext(), aPath, 1), value -> value))
                        .isInstanceOfSatisfying(DocumentFailure.class, failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_NOT_FOUND));
                assertThat(new String(fixture.git.getRepository().open(fixture.git.getRepository().resolve(first + ":" + aPath)).getBytes(),
                        StandardCharsets.UTF_8)).isEqualTo(a1);
            }
        }
    }

    @Test
    void globalPagingUsesFullPathOrderAndAuthenticatedScopeAndSemanticVersions() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("paging"))) {
            String first = commitOwners(fixture, Map.of("repositories/a/guide.md", "# One\nneedle\n# Two\nneedle\n",
                    "repositories/a-b/guide.md", "# One\nneedle\n# Two\nneedle\n",
                    "repositories/b/guide.md", "# One\nneedle\n# Two\nneedle\n"));
            fixture.publish(Map.of("a", first, "a-b", first, "b", first));
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult page = search(query, Optional.empty(), Optional.empty(), 1);
                String cursor = page.page().nextCursor().orElseThrow();
                for (SearchRequest invalid : List.of(new SearchRequest("needle", 1, Optional.of(cursor), Optional.of("a")),
                        new SearchRequest("other", 1, Optional.of(cursor), Optional.empty()),
                        new SearchRequest("needle", 2, Optional.of(cursor), Optional.empty()))) {
                    assertThatThrownBy(() -> query.service.search(invalid, value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
                }
                List<String> positions = new ArrayList<>();
                while (true) {
                    for (DocumentContract.SearchHit hit : page.items()) {
                        positions.add(hit.path() + "#" + hit.section().startLine());
                        assertThat(hit.documentContext().repositoryId()).isEqualTo(hit.primaryContext().repositoryId());
                        assertThat(query.service.read(new ReadRequest(hit.documentContext(), hit.path(), hit.section().startLine()), value -> value).content())
                                .isEqualTo("# One\nneedle\n# Two\nneedle\n");
                    }
                    assertThat(fixture.mapper.writeValueAsBytes(query.writer.success(page)).length).isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                    if (!page.page().hasMore()) { break; }
                    page = search(query, Optional.empty(), page.page().nextCursor(), 1);
                }
                assertThat(positions).containsExactly("repositories/a-b/guide.md#1", "repositories/a-b/guide.md#3",
                        "repositories/a/guide.md#1", "repositories/a/guide.md#3", "repositories/b/guide.md#1", "repositories/b/guide.md#3");
                Files.write(fixture.publication, fixture.mapper.writeValueAsBytes(Map.of("documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY,
                        "repositories", List.of(Map.of("documentRevision", first, "repositoryId", "b"),
                                Map.of("documentRevision", first, "repositoryId", "a"), Map.of("documentRevision", first, "repositoryId", "a-b")), "formatVersion", 2)));
                assertThat(search(query, Optional.empty(), Optional.of(cursor), 1).items().getFirst().section().startLine()).isEqualTo(3);
                assertThat(search(query, Optional.of("unknown"), Optional.empty(), 1).items()).isEmpty();
                String next = commitOwners(fixture, Map.of("repositories/new/guide.md", "# New\nneedle\n"));
                fixture.publish(Map.of("a", first, "a-b", first, "b", first, "new", next));
                assertUnavailable(() -> search(query, Optional.empty(), Optional.of(cursor), 1));
            }
        }
    }

    @Test
    void ownerNoopAfterAnotherOwnerAdvancesHeadKeepsFreshCursorsWhileMetadataChangeExpiresOnlyItsScope() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("noop"))) {
            fixture.git.getRepository().getConfig().setString("user", null, "name", "Fixture");
            fixture.git.getRepository().getConfig().setString("user", null, "email", "fixture@example.test");
            fixture.git.getRepository().getConfig().save();
            String first = commitOwners(fixture, Map.of("repositories/a/guide.md", "# A\nneedle\n# More\nneedle\n",
                    "repositories/b/guide.md", "# B\nneedle\n# More\nneedle\n"));
            fixture.publish(Map.of("a", first, "b", first));
            try (QueryFixture query = new QueryFixture(fixture);
                    LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                SearchResult beforeB = search(query, Optional.empty(), Optional.empty(), 1);
                SearchResult a = search(query, Optional.of("a"), Optional.empty(), 1);
                Files.writeString(fixture.root.resolve("repositories/b/guide.md"), "# B2\nneedle\n# More\nneedle\n");
                String b = publisher.update(new LocalDocumentPublisher.UpdateRequest(new SourceContext("b", DocumentFixture.SOURCE_REVISION),
                        List.of(), List.of("guide.md"), Optional.empty())).activeRevisions().get("b");
                assertUnavailable(() -> search(query, Optional.empty(), beforeB.page().nextCursor(), 1));
                SearchResult freshGlobal = search(query, Optional.empty(), Optional.empty(), 1);
                SearchResult freshB = search(query, Optional.of("b"), Optional.empty(), 1);
                LocalDocumentPublisher.Result noop = publisher.update(new LocalDocumentPublisher.UpdateRequest(new SourceContext("a", DocumentFixture.SOURCE_REVISION),
                        List.of(), List.of("guide.md"), Optional.empty()));
                assertThat(noop.commitCreated()).isFalse();
                assertThat(noop.selectionChanged()).isFalse();
                assertThat(noop.activeRevisions()).containsEntry("a", first).containsEntry("b", b);
                assertThat(search(query, Optional.empty(), freshGlobal.page().nextCursor(), 1).items()).hasSize(1);
                assertThat(search(query, Optional.of("b"), freshB.page().nextCursor(), 1).items()).hasSize(1);
                assertThat(search(query, Optional.of("a"), a.page().nextCursor(), 1).items()).hasSize(1);
                publisher.update(new LocalDocumentPublisher.UpdateRequest(new SourceContext("a", "c".repeat(40)),
                        List.of(), List.of("guide.md"), Optional.empty()));
                assertUnavailable(() -> search(query, Optional.empty(), freshGlobal.page().nextCursor(), 1));
                assertUnavailable(() -> search(query, Optional.of("a"), a.page().nextCursor(), 1));
                assertThat(search(query, Optional.of("b"), freshB.page().nextCursor(), 1).items()).hasSize(1);
            }
        }
    }

    @Test
    void listsDocumentsWithoutQueryAndReadsReturnedEntry() throws Exception {
        String aGuide = "Summary before the first heading\r\n# Alpha\r\nComplete evidence\r\n## Details\r\nMore evidence";
        String aOverview = "# Overview\nA second selected document";
        String bGuide = "Preamble before the first heading\r\n# Billing\r\nComplete billing evidence\r\n## Details\r\nQualification";
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("listing"))) {
            String aRevision = fixture.commit(Map.of("repositories/sample/guide.md", aGuide,
                    "repositories/sample/overview.md", aOverview));
            String bRevision = commitOwners(fixture, Map.of("repositories/b/guide.md", bGuide));
            fixture.publish(Map.of("sample", aRevision, "b", bRevision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                ListResult result = list(query, Optional.empty(), Optional.empty(), 10);

                assertThat(result.documentRepositoryId()).isEqualTo(DocumentFixture.DOCUMENT_REPOSITORY);
                assertThat(result.repositoryId()).isEmpty();
                assertThat(result.collectionPurpose()).isEqualTo(CollectionPurpose.TEST_ONLY);
                assertThat(result.trust()).isEqualTo(DocumentContract.Trust.NOT_VERIFIED);
                assertThat(result.publicationStatus()).isEqualTo(DocumentContract.PublicationStatus.READY);
                assertThat(result.publicationErrorCode()).isEmpty();
                assertThat(result.items()).extracting(item -> item.path()).containsExactly(
                        "repositories/b/guide.md", "repositories/sample/guide.md", "repositories/sample/overview.md");
                assertThat(result.items()).hasSize(3);
                assertThat(result.page().returned()).isEqualTo(3);
                assertThat(result.page().hasMore()).isFalse();
                assertThat(result.page().nextCursor()).isEmpty();
                assertThat(result.items().get(0).documentContext().documentRevision()).isEqualTo(bRevision);
                assertThat(result.items().get(1).documentContext().documentRevision()).isEqualTo(aRevision);
                assertThat(result.items().get(0).section())
                        .isEqualTo(new DocumentContract.Section(1, "", false));
                assertThat(result.items().get(1).section())
                        .isEqualTo(new DocumentContract.Section(1, "", false));
                assertThat(result.items().get(0).primaryContext().repositoryId()).isEqualTo("b");
                assertThat(result.items().get(0).relatedContexts()).isEmpty();
                assertThat(result.items().get(0).reviewStatus()).isEqualTo(DocumentContract.ReviewStatus.DRAFT);

                String serialized = fixture.mapper.writeValueAsString(result);
                assertThat(serialized).doesNotContain("\"content\"", "\"snippet\"");
                DocumentContract.DocumentListItem entry = result.items().get(0);
                ReadResult read = query.service.read(new ReadRequest(entry.documentContext(), entry.path(),
                        entry.section().startLine()), value -> value);
                assertThat(read.content()).isEqualTo(bGuide);
                assertThat(read.section()).isEqualTo(entry.section());
                assertThat(read.byteLength()).isEqualTo(bGuide.getBytes(StandardCharsets.UTF_8).length);

                ListResult unknownOwner = list(query, Optional.of("unknown"), Optional.empty(), 10);
                assertThat(unknownOwner.repositoryId()).contains("unknown");
                assertThat(unknownOwner.items()).isEmpty();
                assertThat(unknownOwner.page().returned()).isZero();
                assertThat(unknownOwner.page().hasMore()).isFalse();

                fixture.publish(Map.of());
                ListResult emptyCatalog = list(query, Optional.empty(), Optional.empty(), 10);
                assertThat(emptyCatalog.items()).isEmpty();
                assertThat(emptyCatalog.page().returned()).isZero();
                assertThat(emptyCatalog.page().hasMore()).isFalse();
                assertThat(emptyCatalog.page().nextCursor()).isEmpty();
            }
        }
    }

    @Test
    void ownerIsolationAndOperationBoundCursors() throws Exception {
        String aGuide = "repositories/a/guide.md";
        String aOverview = "repositories/a/overview.md";
        String bGuide = "repositories/b/guide.md";
        String bOverview = "repositories/b/overview.md";
        String aBody = "# A\nneedle one\n## More\nneedle two";
        String aOverviewBody = "# A overview\nneedle overview\n## More\nneedle detail";
        String bBody = "# B\nneedle one\n## More\nneedle two";
        String bOverviewBody = "# B overview\nneedle overview\n## More\nneedle detail";
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("cursor-isolation"))) {
            String aRevision = commitOwners(fixture, Map.of(aGuide, aBody, aOverview, aOverviewBody));
            String bRevision = commitOwners(fixture, Map.of(bGuide, bBody, bOverview, bOverviewBody));
            fixture.publish(Map.of("a", aRevision, "b", bRevision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                ListResult initialGlobal = list(query, Optional.empty(), Optional.empty(), 1);
                ListResult initialA = list(query, Optional.of("a"), Optional.empty(), 1);
                ListResult initialB = list(query, Optional.of("b"), Optional.empty(), 1);
                SearchResult initialSearch = search(query, Optional.empty(), Optional.empty(), 1);
                String globalCursor = initialGlobal.page().nextCursor().orElseThrow();
                String aCursor = initialA.page().nextCursor().orElseThrow();
                String bCursor = initialB.page().nextCursor().orElseThrow();
                String searchCursor = initialSearch.page().nextCursor().orElseThrow();

                assertInvalid(() -> list(query, Optional.empty(), Optional.of(globalCursor), 2));
                assertInvalid(() -> list(query, Optional.of("a"), Optional.of(globalCursor), 1));
                assertInvalid(() -> query.service.search(new SearchRequest("needle", 1,
                        Optional.of(globalCursor), Optional.empty()), value -> value));
                assertInvalid(() -> query.service.list(new ListRequest(Optional.empty(), 1,
                        initialSearch.page().nextCursor()), value -> value));
                int signatureStart = globalCursor.indexOf('.') + 1;
                char signatureCharacter = globalCursor.charAt(signatureStart);
                String tampered = globalCursor.substring(0, signatureStart)
                        + (signatureCharacter == 'A' ? 'B' : 'A') + globalCursor.substring(signatureStart + 1);
                assertInvalid(() -> list(query, Optional.empty(), Optional.of(tampered), 1));
                DocumentQueryService restarted = new DocumentQueryService(query.catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate());
                assertInvalid(() -> restarted.list(new ListRequest(Optional.empty(), 1, Optional.of(globalCursor)),
                        value -> value));

                fixture.publish(Map.of("a", aRevision, "b", bRevision));
                assertThat(list(query, Optional.empty(), Optional.of(globalCursor), 1).items())
                        .extracting(item -> item.path()).containsExactly(aOverview);
                assertThat(list(query, Optional.of("a"), Optional.of(aCursor), 1).items())
                        .extracting(item -> item.path()).containsExactly(aOverview);
                assertThat(list(query, Optional.of("b"), Optional.of(bCursor), 1).items())
                        .extracting(item -> item.path()).containsExactly(bOverview);
                assertThat(search(query, Optional.empty(), Optional.of(searchCursor), 1).items().getFirst()
                        .section().startLine()).isEqualTo(3);

                ListResult staleGlobal = list(query, Optional.empty(), Optional.empty(), 1);
                ListResult staleA = list(query, Optional.of("a"), Optional.empty(), 1);
                ListResult stableB = list(query, Optional.of("b"), Optional.empty(), 1);
                SearchResult staleSearch = search(query, Optional.empty(), Optional.empty(), 1);
                DocumentContract.DocumentListItem bEntry = stableB.items().getFirst();
                String updatedA = commitOwners(fixture, Map.of(aGuide, "# A updated\nnew evidence\n## More\nnew detail",
                        aOverview, "# A overview updated\nnew summary"));
                fixture.publish(Map.of("a", updatedA, "b", bRevision));

                assertUnavailable(() -> list(query, Optional.empty(), staleGlobal.page().nextCursor(), 1));
                assertUnavailable(() -> list(query, Optional.of("a"), staleA.page().nextCursor(), 1));
                assertUnavailable(() -> search(query, Optional.empty(), staleSearch.page().nextCursor(), 1));
                ListResult continuedB = list(query, Optional.of("b"), stableB.page().nextCursor(), 1);
                assertThat(continuedB.items()).extracting(item -> item.path()).containsExactly(bOverview);
                assertThat(continuedB.items().getFirst().documentContext().documentRevision()).isEqualTo(bRevision);
                ReadResult bRead = query.service.read(new ReadRequest(bEntry.documentContext(), bEntry.path(),
                        bEntry.section().startLine()), value -> value);
                assertThat(bRead.content()).isEqualTo(bBody);

                ListResult lastBPage = list(query, Optional.of("b"), Optional.empty(), 1);
                fixture.publish(Map.of());
                assertUnavailable(() -> list(query, Optional.of("b"), lastBPage.page().nextCursor(), 1));
                assertThat(list(query, Optional.empty(), Optional.empty(), 1).items()).isEmpty();
                assertThat(list(query, Optional.of("b"), Optional.empty(), 1).items()).isEmpty();
            }
        }
    }

    @Test
    void realMetadataPagesFitBudgetAndAnOversizedFirstRowFails() throws Exception {
        String firstPath = "repositories/sample/a.md";
        // A long direct filename increases the cursor while remaining an admissible document path.
        String secondPath = "repositories/sample/" + "b".repeat(250) + ".md";
        byte[] firstBody = "# One\nbody".getBytes(StandardCharsets.UTF_8);
        byte[] secondBody = "# Two\nbody".getBytes(StandardCharsets.UTF_8);
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("first-row-budget"))) {
            Map<String, byte[]> bodies = Map.of(firstPath, firstBody, secondPath, secondBody);
            String baselineRevision = fixture.commitBytes(bodies,
                    List.of(fixture.row(firstPath, firstBody), fixture.row(secondPath, secondBody)));
            fixture.publish(Map.of("sample", baselineRevision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                ListRequest request = new ListRequest(Optional.empty(), 1, Optional.empty());
                ListResult templateList = query.service.list(request, value -> value);
                assertThat(templateList.items()).extracting(item -> item.path())
                        .containsExactly(firstPath);
                assertThat(templateList.page().hasMore()).isTrue();
                DocumentContract.DocumentListItem firstItem = templateList.items().getFirst();
                ReadResult templateRead = query.service.read(new ReadRequest(firstItem.documentContext(),
                        firstItem.path(), firstItem.section().startLine()), value -> value);

                // Size against the real admission envelope; the same metadata plus a list cursor must exceed it.
                int low = 0;
                int high = DocumentContract.MAX_CATALOG_BYTES / 2 - 4096;
                while (low < high) {
                    int middle = low + (high - low + 1) / 2;
                    ReadResult candidate = admissionRead(templateRead,
                            Optional.of("\\".repeat(middle)));
                    try {
                        query.writer.success(candidate);
                        low = middle;
                    } catch (DocumentFailure failure) {
                        assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE);
                        high = middle - 1;
                    }
                }
                assertThat(low).isPositive();
                String reviewReference = "\\".repeat(low);
                ReadResult fittingAdmissionEnvelope = admissionRead(templateRead, Optional.of(reviewReference));
                McpSchema.CallToolResult admissionEnvelope = query.writer.success(fittingAdmissionEnvelope);
                assertThat(fixture.mapper.writeValueAsBytes(admissionEnvelope).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThatThrownBy(() -> query.writer.success(listWithReference(templateList, reviewReference)))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));

                Map<String, Object> largeRow = fixture.row(firstPath, firstBody);
                largeRow.put("reviewReference", reviewReference);
                String admittedRevision = fixture.commitBytes(bodies,
                        List.of(largeRow, fixture.row(secondPath, secondBody)));
                fixture.publish(Map.of("sample", admittedRevision));
                ReadResult admittedRead = query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, "sample", admittedRevision), firstPath, 1), value -> value);
                assertThat(admittedRead.documentContext().documentRevision()).isEqualTo(admittedRevision);
                assertThat(admittedRead.reviewReference()).contains(reviewReference);
                assertThat(fixture.mapper.writeValueAsBytes(query.writer.success(admittedRead)).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);

                ListResult oversizedList = query.service.list(request, value -> value);
                assertThat(oversizedList.items()).extracting(item -> item.path()).containsExactly(firstPath);
                assertThat(oversizedList.items().getFirst().reviewReference()).contains(reviewReference);
                assertThat(oversizedList.page().hasMore()).isTrue();
                assertThatThrownBy(() -> query.writer.success(oversizedList))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));
                assertThatThrownBy(() -> query.service.list(request, query.writer::success))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));
            }
        }

        try (DocumentFixture fixture = new DocumentFixture(root.resolve("paged-budget"))) {
            byte[] body = "# One\nbody".getBytes(StandardCharsets.UTF_8);
            String reviewReference = "\\".repeat(50_000);
            Map<String, Object> firstRow = fixture.row("repositories/sample/a.md", body);
            Map<String, Object> secondRow = fixture.row("repositories/sample/b.md", body);
            firstRow.put("reviewReference", reviewReference);
            secondRow.put("reviewReference", reviewReference);
            String revision = fixture.commitBytes(Map.of("repositories/sample/a.md", body,
                    "repositories/sample/b.md", body), List.of(firstRow, secondRow));
            fixture.publish(Map.of("sample", revision));
            try (QueryFixture query = new QueryFixture(fixture)) {
                ListRequest request = new ListRequest(Optional.empty(), 2, Optional.empty());
                ListResult untrimmed = query.service.list(request, value -> value);
                assertThatThrownBy(() -> query.writer.success(untrimmed))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));

                MaterializedList firstPage = query.service.list(request,
                        value -> new MaterializedList(value, query.writer.success(value)));
                assertThat(firstPage.wireResult().isError()).isFalse();
                assertThat(fixture.mapper.writeValueAsBytes(firstPage.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThat(firstPage.result().items()).extracting(item -> item.path())
                        .containsExactly("repositories/sample/a.md");
                assertThat(firstPage.result().page().hasMore()).isTrue();
                assertThat(firstPage.result().items().getFirst().reviewReference()).contains(reviewReference);

                ListRequest continuation = new ListRequest(Optional.empty(), 2,
                        firstPage.result().page().nextCursor());
                MaterializedList secondPage = query.service.list(continuation,
                        value -> new MaterializedList(value, query.writer.success(value)));
                assertThat(fixture.mapper.writeValueAsBytes(secondPage.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThat(secondPage.result().items()).extracting(item -> item.path())
                        .containsExactly("repositories/sample/b.md");
                assertThat(secondPage.result().page().hasMore()).isFalse();
                assertThat(secondPage.result().items().getFirst().documentContext().documentRevision())
                        .isEqualTo(revision);
                assertThat(List.of(firstPage.result().items().getFirst(), secondPage.result().items().getFirst()))
                        .extracting(item -> item.path())
                        .containsExactly("repositories/sample/a.md", "repositories/sample/b.md");
            }
        }
    }

    private static ReadResult admissionRead(ReadResult template, Optional<String> reviewReference) {
        return new ReadResult(template.documentContext(), template.path(), template.blobId(), template.contentDigest(),
                template.byteLength(), template.section(), template.primaryContext(), template.relatedContexts(),
                template.reviewStatus(), reviewReference, template.collectionPurpose(), template.trust(),
                DocumentContract.PublicationStatus.UPDATE_REJECTED,
                Optional.of(DocumentFailure.Code.DOCUMENT_CAPACITY_EXCEEDED), template.scope(), template.startLine(),
                template.endLine(), template.content());
    }

    private static ListResult listWithReference(ListResult template, String reviewReference) {
        DocumentContract.DocumentListItem original = template.items().getFirst();
        DocumentContract.DocumentListItem item = new DocumentContract.DocumentListItem(original.documentContext(),
                original.path(), original.contentDigest(), original.byteLength(), original.section(),
                original.primaryContext(), original.relatedContexts(), original.reviewStatus(),
                Optional.of(reviewReference));
        return new ListResult(template.documentRepositoryId(), template.repositoryId(), template.collectionPurpose(),
                template.trust(), template.publicationStatus(), template.publicationErrorCode(), List.of(item),
                template.page());
    }

    private static String commitOwners(DocumentFixture fixture, Map<String, String> bodies) throws Exception {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            byte[] body = entry.getValue().getBytes(StandardCharsets.UTF_8);
            bytes.put(entry.getKey(), body);
            rows.add(fixture.row(entry.getKey(), body, new SourceContext(entry.getKey().split("/")[1], DocumentFixture.SOURCE_REVISION)));
        }
        return fixture.commitBytes(bytes, rows);
    }
    private static SearchResult search(QueryFixture query, Optional<String> owner, Optional<String> cursor, int limit) {
        return query.service.search(new SearchRequest("needle", limit, cursor, owner), value -> value);
    }
    private static ListResult list(QueryFixture query, Optional<String> owner, Optional<String> cursor, int limit) {
        return query.service.list(new ListRequest(owner, limit, cursor), value -> value);
    }
    private static void assertInvalid(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DocumentFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
    }
    private static void assertUnavailable(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(DocumentFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE));
    }

    private static final class QueryFixture implements AutoCloseable {
        private final DocumentResultWriter writer;
        private final GitDocumentLoader loader;
        private final DocumentCatalog catalog;
        private final DocumentQueryService service;

        private QueryFixture(DocumentFixture fixture) {
            this(fixture, new DocumentOperationGate());
        }

        private QueryFixture(DocumentFixture fixture, DocumentOperationGate operations) {
            DocumentQueryProperties properties = fixture.properties(CollectionPurpose.TEST_ONLY);
            this.writer = new DocumentResultWriter(fixture.mapper);
            this.loader = new GitDocumentLoader(properties.storage(), writer);
            this.catalog = new DocumentCatalog(properties.storage(), loader);
            this.service = new DocumentQueryService(catalog, new DocumentCursorCodec(fixture.mapper), operations);
        }

        @Override
        public void close() {
            loader.close();
        }
    }
    private record MaterializedSearch(SearchResult result, McpSchema.CallToolResult wireResult) { }
    private record MaterializedList(ListResult result, McpSchema.CallToolResult wireResult) { }

}
