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
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
            fixture.publish(revision, revision);
            try (QueryFixture query = new QueryFixture(fixture)) {
                MaterializedSearch materialized = query.service.search(new SearchRequest("C++ (λ)", 10,
                        Optional.empty()), result -> new MaterializedSearch(result, query.writer.success(result)));
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

                SearchResult caseSensitive = query.service.search(new SearchRequest("c++ (λ)", 10, Optional.empty()),
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
            fixture.publish(revision, revision);
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult result = query.service.search(new SearchRequest(queryText, 10, Optional.empty()),
                        value -> value);

                assertThat(result.documentContext().documentRevision()).isEqualTo(revision);
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
    void cursorsBindQueryLimitAndProcessSecretAndRemainPinnedUntilWithdrawal() throws Exception {
        Map<String, String> firstDocuments = Map.of(DocumentFixture.GUIDE,
                "# First\nneedle\n## Second\nneedle\n## Third\nneedle");
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String firstRevision = fixture.commit(firstDocuments);
            fixture.publish(firstRevision, firstRevision);
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchResult firstPage = query.service.search(new SearchRequest("needle", 1, Optional.empty()),
                        value -> value);
                String cursor = firstPage.page().nextCursor().orElseThrow();
                assertThat(firstPage.documentContext().documentRevision()).isEqualTo(firstRevision);
                assertThat(firstPage.page().returned()).isEqualTo(1);
                assertThat(firstPage.page().hasMore()).isTrue();

                assertThatThrownBy(() -> query.service.search(new SearchRequest("changed", 1, Optional.of(cursor)),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 2, Optional.of(cursor)),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));
                int signatureStart = cursor.indexOf('.') + 1;
                char signatureCharacter = cursor.charAt(signatureStart);
                String tampered = cursor.substring(0, signatureStart)
                        + (signatureCharacter == 'A' ? 'B' : 'A') + cursor.substring(signatureStart + 1);
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 1, Optional.of(tampered)),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));

                DocumentQueryService restarted = new DocumentQueryService(query.catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate());
                assertThatThrownBy(() -> restarted.search(new SearchRequest("needle", 1, Optional.of(cursor)),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.INVALID_ARGUMENT));

                String secondRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, "# New\nneedle from D2"));
                fixture.publish(secondRevision, secondRevision, firstRevision);
                SearchResult secondPage = query.service.search(new SearchRequest("needle", 1, Optional.of(cursor)),
                        value -> value);
                SearchResult thirdPage = query.service.search(new SearchRequest("needle", 1,
                        secondPage.page().nextCursor()), value -> value);
                assertThat(secondPage.documentContext().documentRevision()).isEqualTo(firstRevision);
                assertThat(secondPage.items().getFirst().section().startLine()).isEqualTo(3);
                assertThat(thirdPage.documentContext().documentRevision()).isEqualTo(firstRevision);
                assertThat(thirdPage.items().getFirst().section().startLine()).isEqualTo(5);
                assertThat(thirdPage.page().hasMore()).isFalse();

                SearchResult current = query.service.search(new SearchRequest("needle", 1, Optional.empty()),
                        value -> value);
                assertThat(current.documentContext().documentRevision()).isEqualTo(secondRevision);

                fixture.publish(secondRevision, secondRevision);
                assertThatThrownBy(() -> query.service.search(new SearchRequest("needle", 1, Optional.of(cursor)),
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
            fixture.publish(revision, revision);
            try (QueryFixture query = new QueryFixture(fixture)) {
                SearchRequest request = new SearchRequest("needle", 4, Optional.empty());
                SearchResult untrimmed = query.service.search(request, result -> result);
                assertThat(untrimmed.items()).extracting(hit -> hit.section().startLine()).containsExactly(1, 3, 5, 7);
                assertThat(untrimmed.items().getFirst().reviewReference().orElseThrow()).isEqualTo(reviewReference);
                assertThatThrownBy(() -> query.writer.success(untrimmed))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_RESULT_TOO_LARGE));

                ReadResult fullDocument = query.service.read(new ReadRequest(untrimmed.documentContext(),
                        DocumentFixture.GUIDE, 1), result -> result);
                McpSchema.CallToolResult fullDocumentWire = query.writer.success(fullDocument);
                assertThat(fullDocument.reviewReference().orElseThrow()).isEqualTo(reviewReference);
                assertThat(fixture.mapper.writeValueAsBytes(fullDocumentWire).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);

                MaterializedSearch oneHit = query.service.search(new SearchRequest("needle", 1, Optional.empty()),
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
                        firstPage.result().page().nextCursor());
                MaterializedSearch continuedPage = query.service.search(continuationRequest,
                        result -> new MaterializedSearch(result, query.writer.success(result)));
                assertThat(continuedPage.wireResult().isError()).isFalse();
                assertThat(fixture.mapper.writeValueAsBytes(continuedPage.wireResult()).length)
                        .isLessThanOrEqualTo(DocumentContract.MAX_RESULT_BYTES);
                assertThat(continuedPage.result().documentContext().documentRevision()).isEqualTo(revision);
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
            fixture.publish(revision, revision);
            try (QueryFixture query = new QueryFixture(fixture)) {
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext("other", revision),
                        DocumentFixture.GUIDE, 3), value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_REPOSITORY_NOT_FOUND));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, DocumentFixture.SOURCE_REVISION), DocumentFixture.GUIDE, 3),
                        value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, revision), "missing/guide.md", 3), value -> value))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_NOT_FOUND));
                assertThatThrownBy(() -> query.service.read(new ReadRequest(new DocumentContext(
                        DocumentFixture.DOCUMENT_REPOSITORY, revision), DocumentFixture.GUIDE, 2), value -> value))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(DocumentFailure.Code.DOCUMENT_SECTION_NOT_FOUND));
            }
        }
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
            this.loader = new GitDocumentLoader(properties, writer);
            this.catalog = new DocumentCatalog(properties, loader);
            this.service = new DocumentQueryService(catalog, new DocumentCursorCodec(fixture.mapper), operations);
        }

        @Override
        public void close() {
            loader.close();
        }
    }
    private record MaterializedSearch(SearchResult result, McpSchema.CallToolResult wireResult) { }

}
