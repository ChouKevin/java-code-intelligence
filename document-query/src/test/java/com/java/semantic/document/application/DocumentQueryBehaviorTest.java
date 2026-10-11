package com.java.semantic.document.application;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
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

}
