package com.java.semantic.indexer.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.java.semantic.indexer.store.MongoGenerationWriter.StoredDocument;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.EntryPointKind;
import com.java.semantic.model.codefact.EntryPointTrigger;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationKind;
import com.java.semantic.model.codefact.RelationTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.bson.Document;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class SourceIndexBatchDocumentMapperTest {

    @ParameterizedTest
    @MethodSource("entryPoints")
    void round_trips_all_entry_point_trigger_shapes_through_converter_safe_storage(EntryPointDocument entryPoint) {
        SourceIndexBatch batch = new SourceIndexBatch(entryPoint.repositoryId(), entryPoint.generationId(),
                "src/main/java/example/api/InvoiceController.java", 0, SourceArtifactDocument.create("class InvoiceController {}"),
                List.of(), List.of(), List.of(entryPoint), List.of());

        SourceIndexBatchDocumentMapper mapper = new SourceIndexBatchDocumentMapper(converter());
        List<StoredDocument> documents = mapper.map(batch);
        Document document = documents.stream().filter(stored -> IndexCollections.ENTRY_POINTS.equals(stored.collection()))
                .findFirst().orElseThrow().document();
        Document storage = assertInstanceOf(Document.class, document.get("entryPoint"));
        assertInstanceOf(Document.class, storage.get("fact"));
        Document trigger = assertInstanceOf(Document.class, storage.get("trigger"));
        Document range = assertInstanceOf(Document.class, storage.get("range"));
        assertFalse(document.toJson().contains("Optional"));
        assertEquals(entryPoint, mapper.reconstructEntryPoint(document));
        assertEquals(entryPoint.trigger().httpMethod().isPresent(), trigger.getBoolean("hasHttpMethod"));
        assertEquals(entryPoint.trigger().httpPath().isPresent(), trigger.getBoolean("hasHttpPath"));
        assertEquals(entryPoint.trigger().destination().isPresent(), trigger.getBoolean("hasDestination"));
        assertEquals(entryPoint.trigger().schedule().isPresent(), trigger.getBoolean("hasSchedule"));
        assertEquals(entryPoint.range().sourceFile(), range.getString("sourceFile"));
        assertEquals(entryPoint.range().range().start().line(), range.getInteger("startLine"));
        assertEquals(entryPoint.range().range().start().character(), range.getInteger("startCharacter"));
        assertEquals(entryPoint.range().range().end().line(), range.getInteger("endLine"));
        assertEquals(entryPoint.range().range().end().character(), range.getInteger("endCharacter"));
        assertEquals(entryPoint.method().canonicalForm(), document.getString("method"));
        assertEquals(batch.sourcePath(), document.getString("sourcePath"));
        if (entryPoint.kind() == EntryPointKind.HTTP) {
            assertEquals(entryPoint.trigger().httpPath().orElseThrow(), document.getString("path"));
        } else {
            assertFalse(document.containsKey("path"));
        }
    }

    @ParameterizedTest
    @MethodSource("searchDocuments")
    void round_trips_typed_search_authority_without_canonical_string_substitutes(SearchDocument search) {
        SourceIndexBatch batch = new SourceIndexBatch(search.repositoryId(), search.generationId(),
                "src/main/java/example/api/InvoiceController.java", 0, SourceArtifactDocument.create("class InvoiceController {}"),
                List.of(), List.of(), List.of(), List.of(search));

        SourceIndexBatchDocumentMapper mapper = new SourceIndexBatchDocumentMapper(converter());
        Document document = mapper.map(batch).stream().filter(stored -> IndexCollections.SEARCH.equals(stored.collection()))
                .findFirst().orElseThrow().document();

        Document storage = assertInstanceOf(Document.class, document.get("search"));
        assertInstanceOf(Document.class, storage.get("authoritativeIdentity"));
        assertFalse(document.toJson().contains("Optional"));
        assertEquals(search, mapper.reconstructSearch(document));
        assertEquals(search.factId().value(), document.getString("factId"));
        assertEquals(search.kind().name(), document.getString("kind"));
        assertEquals(search.normalizedTokens(), document.getList("tokens", String.class));
        assertEquals(search.packageName().orElse(""), document.getString("package"));
        assertEquals(search.packageName().isPresent(), storage.getBoolean("hasPackageName"));
        assertEquals(search.authoritativeProjection().name(), document.getString("authority"));
        assertEquals(batch.sourcePath(), document.getString("sourcePath"));
        if (search.authoritativeIdentity().canonicalIdentity() instanceof RelationIdentity relation) {
            Document authoritativeIdentity = assertInstanceOf(Document.class, storage.get("authoritativeIdentity"));
            Document canonicalIdentity = assertInstanceOf(Document.class, authoritativeIdentity.get("canonicalIdentity"));
            Document source = assertInstanceOf(Document.class, canonicalIdentity.get("from"));
            Document target = assertInstanceOf(Document.class, canonicalIdentity.get("target"));
            Document occurrence = assertInstanceOf(Document.class, canonicalIdentity.get("occurrence"));
            assertInstanceOf(Document.class, source.get("canonicalIdentity"));
            assertEquals(relation.relationKind().name(), canonicalIdentity.getString("relationKind"));
            assertEquals(relation.occurrence().sourceFile(), occurrence.getString("sourceFile"));
            assertEquals(relation.occurrence().range().start().line(), occurrence.getInteger("startLine"));
            assertFalse(target.isEmpty());
        }
    }

    private static Stream<EntryPointDocument> entryPoints() {
        return Stream.of(httpEntryPoint(), mqEntryPoint(), scheduledEntryPoint());
    }

    private static Stream<SearchDocument> searchDocuments() {
        CodeFactIdentity method = methodIdentity();
        CodeFactIdentity internalTarget = new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.METHOD,
                new MethodTarget(sourceType(), "find", List.of()));
        return Stream.of(
                search(new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.TYPE, sourceType()), Optional.of("example.api")),
                search(method, Optional.of("example.api")),
                search(new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.FIELD,
                        new MemberIdentity(sourceType(), "status")), Optional.of("example.api")),
                search(new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.MAPPER_STATEMENT,
                        new MapperStatementIdentity("InvoiceMapper", "find", "src/main/resources/mapper/InvoiceMapper.xml")), Optional.empty()),
                search(httpEntryPoint().fact().identity(), Optional.of("example.api")),
                relationSearch(new RelationTarget.Internal(internalTarget)),
                relationSearch(new RelationTarget.External(new ExternalTarget.NominalType(new com.java.semantic.model.codefact.DeclaredType("java.time.Instant")))),
                relationSearch(new RelationTarget.External(new ExternalTarget.Endpoint("GET", "https://catalog.example/invoices"))),
                relationSearch(new RelationTarget.External(new ExternalTarget.Destination("kafka", "invoice-events"))),
                relationSearch(new RelationTarget.External(new ExternalTarget.ConfigurationKey("invoice.retry-limit"))),
                relationSearch(new RelationTarget.External(new ExternalTarget.SqlIdentifier("invoice_line"))),
                relationSearch(new RelationTarget.External(new ExternalTarget.UnresolvedCall("client.send(invoice)", "client", "send", 1))));
    }

    private static SearchDocument relationSearch(RelationTarget target) {
        CodeFactIdentity identity = new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.TYPE_USAGE,
                new RelationIdentity(methodIdentity(), RelationKind.USES_TYPE, target, sourceRange()));
        return search(identity, Optional.of("example.api"));
    }

    private static SearchDocument search(CodeFactIdentity identity, Optional<String> packageName) {
        ProjectionName projection = switch (identity.kind()) {
            case API_ROUTE, MQ_DESTINATION, SCHEDULE -> ProjectionName.ENTRY_POINTS;
            case TYPE_USAGE, SQL_IDENTIFIER, CONFIGURATION_KEY, OUTBOUND_API, MQ_PUBLISHER, ERROR_CONTRACT,
                    ANNOTATION_USAGE -> ProjectionName.RELATIONS;
            default -> ProjectionName.SYMBOLS;
        };
        return new SearchDocument(repositoryId(), generationId(), CodeFactId.from(identity), identity.kind(),
                List.of("example", "invoice"), packageName, projection, identity);
    }

    private static EntryPointDocument httpEntryPoint() {
        return entryPoint(EntryPointKind.HTTP, new EntryPointTrigger(Optional.of("POST"), Optional.of("/invoices"), Optional.empty(), Optional.empty()));
    }

    private static EntryPointDocument mqEntryPoint() {
        return entryPoint(EntryPointKind.MQ, new EntryPointTrigger(Optional.empty(), Optional.empty(),
                Optional.of(new ExternalTarget.Destination("kafka", "invoice-events")), Optional.empty()));
    }

    private static EntryPointDocument scheduledEntryPoint() {
        return entryPoint(EntryPointKind.SCHEDULE, new EntryPointTrigger(Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("0 */5 * * * *")));
    }

    private static EntryPointDocument entryPoint(EntryPointKind kind, EntryPointTrigger trigger) {
        MethodTarget method = methodTarget();
        EntryPointIdentity entryPointIdentity = new EntryPointIdentity(kind, method, trigger);
        CodeFactKind factKind = switch (kind) {
            case HTTP -> CodeFactKind.API_ROUTE;
            case MQ -> CodeFactKind.MQ_DESTINATION;
            case SCHEDULE -> CodeFactKind.SCHEDULE;
        };
        CodeFactIdentity identity = new CodeFactIdentity(repositoryId(), revision(), factKind, entryPointIdentity);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        return new EntryPointDocument(repositoryId(), generationId(), fact, kind, method, trigger, sourceRange());
    }

    private static CodeFactIdentity methodIdentity() {
        return new CodeFactIdentity(repositoryId(), revision(), CodeFactKind.METHOD, methodTarget());
    }

    private static MethodTarget methodTarget() {
        return new MethodTarget(sourceType(), "create", List.of("example.api.CreateInvoiceRequest"));
    }

    private static SourceTypeIdentity sourceType() {
        return new SourceTypeIdentity(new JavaTypeIdentity("example.api", "InvoiceController"),
                "src/main/java/example/api/InvoiceController.java");
    }

    private static SourceRange sourceRange() {
        return new SourceRange(sourceType().sourceFile(), new SyntaxRange(new SyntaxPosition(3, 4), new SyntaxPosition(3, 22)));
    }

    private static RepositoryId repositoryId() {
        return new RepositoryId("invoices");
    }

    private static RepositoryRevision revision() {
        return new RepositoryRevision("a".repeat(40));
    }

    private static GenerationId generationId() {
        return new GenerationId("invoice-generation");
    }

    private static MappingMongoConverter converter() {
        MongoMappingContext mappingContext = new MongoMappingContext();
        mappingContext.afterPropertiesSet();
        MappingMongoConverter converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, mappingContext);
        converter.afterPropertiesSet();
        return converter;
    }
}
