package com.java.semantic.document.mcp;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentContract.SearchRequest;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.application.DocumentQueryService;
import io.modelcontextprotocol.spec.McpSchema;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.math.BigInteger;
import java.util.function.Function;

/** The only untyped tool boundary; materialization stays inside the service's lease. */
public final class DocumentQueryFacade {
    private final DocumentQueryService service;
    private final DocumentResultWriter writer;
    private final Function<Object, McpSchema.CallToolResult> materializer;

    public DocumentQueryFacade(DocumentQueryService service, DocumentResultWriter writer) {
        this(service, writer, writer::success);
    }

    DocumentQueryFacade(DocumentQueryService service, DocumentResultWriter writer,
            Function<Object, McpSchema.CallToolResult> materializer) {
        this.service = service;
        this.writer = writer;
        this.materializer = Objects.requireNonNull(materializer, "materializer");
    }

    public McpSchema.CallToolResult execute(String tool, Map<String, Object> arguments) {
        try {
            if (Objects.isNull(arguments)) { throw invalid(); }
            if ("search_documents".equals(tool)) {
                fields(arguments, Set.of("query", "limit", "cursor"), Set.of("query"));
                String query = text(arguments.get("query"));
                int limit = arguments.containsKey("limit") ? integer(arguments.get("limit")) : DocumentContract.DEFAULT_LIMIT;
                Optional<String> cursor = arguments.containsKey("cursor") ? Optional.of(text(arguments.get("cursor"))) : Optional.empty();
                return service.search(new SearchRequest(query, limit, cursor), materializer::apply);
            }
            if ("read_document".equals(tool)) {
                fields(arguments, Set.of("documentContext", "path", "sectionStartLine"), Set.of("documentContext", "path", "sectionStartLine"));
                if (!(arguments.get("documentContext") instanceof Map<?, ?> context)) { throw invalid(); }
                fields(context, Set.of("documentRepositoryId", "documentRevision"), Set.of("documentRepositoryId", "documentRevision"));
                String repositoryId = text(context.get("documentRepositoryId"));
                String revision = text(context.get("documentRevision"));
                DocumentContext identity = new DocumentContext(repositoryId, revision);
                if (!repositoryId.equals(identity.documentRepositoryId()) || !revision.equals(identity.documentRevision())) { throw invalid(); }
                return service.read(new ReadRequest(identity, text(arguments.get("path")), integer(arguments.get("sectionStartLine"))), materializer::apply);
            }
            throw invalid();
        } catch (DocumentFailure failure) {
            return writer.failure(failure);
        } catch (IllegalArgumentException failure) {
            return writer.failure(invalid());
        } catch (RuntimeException failure) {
            return writer.failure(new DocumentFailure(Code.DOCUMENT_UNAVAILABLE));
        }
    }

    private static void fields(Map<?, ?> values, Set<String> allowed, Set<String> required) {
        if (!allowed.containsAll(values.keySet()) || !values.keySet().containsAll(required)) { throw invalid(); }
    }
    private static String text(Object value) {
        if (value instanceof String text) { return text; }
        throw invalid();
    }
    private static int integer(Object value) {
        // JSON integers may arrive as any Number implementation; reject fractional values without coercion.
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof BigInteger)) { throw invalid(); }
        Number number = (Number) value;
        try { return new BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException | NumberFormatException failure) { throw invalid(); }
    }
    private static DocumentFailure invalid() { return new DocumentFailure(Code.INVALID_ARGUMENT); }
}
