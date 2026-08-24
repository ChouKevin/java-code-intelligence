package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.MongoGenerationWriter;
import com.java.semantic.indexer.store.MongoIndexDefinitionMatcher;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryRevision;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReturnDocument;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Validates one unpublished generation without ever returning stored source content. */
public final class GenerationValidator {
    private static final List<String> GENERATION_COLLECTIONS = List.of(
            IndexCollections.GENERATION_FILES, IndexCollections.SYMBOLS, IndexCollections.RELATIONS,
            IndexCollections.ENTRY_POINTS, IndexCollections.SEARCH);

    private final MongoTemplate template;
    private final SourceIndexBatchDocumentMapper projectionMapper;

    public GenerationValidator(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        projectionMapper = new SourceIndexBatchDocumentMapper(template.getConverter());
    }

    public ValidationResult validate(MongoGenerationWriter.GenerationLease lease, RepositoryRevision requestedRevision,
                                     RepositoryRevision checkedOutRevision) {
        Objects.requireNonNull(lease, "generation lease is required");
        Objects.requireNonNull(requestedRevision, "requested revision is required");
        Objects.requireNonNull(checkedOutRevision, "checked out revision is required");
        List<GenerationValidationIssue> issues = new ArrayList<>();
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(manifestFilter(lease)).first();
        if (Objects.isNull(manifest)) {
            issues.add(issue("MISSING_MANIFEST", "generation manifest is not owned by this claim"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        if (!claimActive(lease)) {
            issues.add(issue("CLAIM_LOST", "repository claim is no longer active for this worker and fence"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        manifest = freezeForValidation(lease);
        if (Objects.isNull(manifest)) {
            issues.add(issue("VALIDATION_FREEZE_FAILED", "generation changed or has unfinished batches before validation"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        validateManifest(manifest, requestedRevision, issues);
        if (!requestedRevision.equals(checkedOutRevision)) {
            issues.add(issue("CHECKOUT_CHANGED", "checked-out revision no longer matches the requested revision"));
        }
        validateClaim(lease, issues);
        validateRequiredIndexes(issues);

        List<Document> files = documents(IndexCollections.GENERATION_FILES, lease);
        List<Document> symbols = documents(IndexCollections.SYMBOLS, lease);
        List<Document> relations = documents(IndexCollections.RELATIONS, lease);
        List<Document> entryPoints = documents(IndexCollections.ENTRY_POINTS, lease);
        List<Document> search = documents(IndexCollections.SEARCH, lease);
        Map<String, Document> artifacts = artifactsById(files, issues);
        validateCanonicalIdentities(symbols, issues);
        validateRelations(symbols, relations, issues);
        validateEntryPoints(symbols, entryPoints, issues);
        validateRanges(symbols, relations, entryPoints, files, artifacts, issues);
        ProjectionDocuments projections = validateProjectionDocuments(lease, requestedRevision, symbols, relations, entryPoints, search,
                issues);
        validateProjectionArtifacts(files, projections.symbols(), projections.relations(), issues);
        validateSearchCoverage(projections, search, issues);
        validateClaim(lease, issues);
        Map<String, Long> counts = collectionCounts(files, artifacts, symbols, relations, entryPoints, search);
        ManifestDigest digest = digest(files, symbols, relations, entryPoints, search);
        return new ValidationResult(digest, counts, issues);
    }

    /** Records the exact values that publication re-checks before the manifest is sealed. */
    public void recordValid(MongoGenerationWriter.GenerationLease lease, ValidationResult result) {
        Objects.requireNonNull(lease, "generation lease is required");
        Objects.requireNonNull(result, "validation result is required");
        if (!result.valid()) {
            throw new IllegalArgumentException("cannot record an invalid generation");
        }
        Document update = new Document("$set", new Document("identityDigest", result.identityDigest().value())
                .append("sealedCollectionCounts", new Document(result.collectionCounts()))
                .append("validationResult", "VALID").append("validatedAt", Date.from(Instant.now())));
        Document filter = manifestFilter(lease).append("validationResult", "VALIDATING")
                .append("$expr", new Document("$gt", List.of("$sealUntil", "$$NOW")));
        long changed = template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(filter, update).getModifiedCount();
        if (changed != 1L) {
            throw new IllegalStateException("generation validation record lost its claim");
        }
    }

    private void validateManifest(Document manifest, RepositoryRevision requestedRevision, List<GenerationValidationIssue> issues) {
        if (!requestedRevision.value().equals(manifest.getString("sourceRevision"))) {
            issues.add(issue("SOURCE_REVISION_MISMATCH", "manifest source revision differs from the job revision"));
        }
        Number schemaVersion = manifest.get("schemaVersion", Number.class);
        if (Objects.isNull(schemaVersion) || schemaVersion.intValue() != IndexSchemaContract.SCHEMA_VERSION) {
            issues.add(issue("UNSUPPORTED_SCHEMA", "manifest schema version is unsupported"));
        }
        if (!IndexSchemaContract.requiredProjectionVersions().equals(projectionVersions(manifest))) {
            issues.add(issue("INCOMPLETE_PROJECTION_VERSIONS", "manifest projection versions do not match the indexer contract"));
        }
    }

    private void validateClaim(MongoGenerationWriter.GenerationLease lease, List<GenerationValidationIssue> issues) {
        if (!claimActive(lease)) {
            issues.add(issue("CLAIM_LOST", "repository claim is no longer active for this worker and fence"));
        }
    }

    private boolean claimActive(MongoGenerationWriter.GenerationLease lease) {
        Document repositoryFilter = new Document("repoId", lease.repositoryId().value()).append("activeJobId", lease.jobId())
                .append("activeWorkerId", lease.workerId()).append("activeGenerationId", lease.generationId().value())
                .append("fence", lease.fence()).append("$expr", new Document("$gt", List.of("$claimUntil", "$$NOW")));
        return Objects.nonNull(template.getCollection(IndexCollections.REPOSITORIES).find(repositoryFilter).first());
    }

    private Document freezeForValidation(MongoGenerationWriter.GenerationLease lease) {
        Document filter = manifestFilter(lease).append("validationResult", new Document("$exists", false))
                .append("$expr", new Document("$and", List.of(
                        new Document("$gt", List.of("$sealUntil", "$$NOW")),
                        noBatches("outstandingBatches"), noBatches("failedOrAmbiguousBatches"))));
        return template.getCollection(IndexCollections.GENERATION_MANIFESTS).findOneAndUpdate(filter,
                new Document("$set", new Document("validationResult", "VALIDATING")),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    private void validateRequiredIndexes(List<GenerationValidationIssue> issues) {
        for (IndexSchemaContract.CollectionSpec collectionSpec : IndexSchemaContract.collections()) {
            Map<String, Document> installed = new LinkedHashMap<>();
            MongoCollection<Document> collection = template.getCollection(collectionSpec.name());
            for (Document index : collection.listIndexes()) {
                installed.put(index.getString("name"), index);
            }
            for (IndexSchemaContract.IndexSpec required : collectionSpec.indexes()) {
                Document actual = installed.get(required.name());
                if (Objects.isNull(actual)) {
                    issues.add(issue("MISSING_REQUIRED_INDEX", "required index " + required.name() + " is absent"));
                } else if (!MongoIndexDefinitionMatcher.matches(actual, required)) {
                    issues.add(issue("INVALID_REQUIRED_INDEX", "required index " + required.name() + " has an incompatible definition"));
                }
            }
        }
    }

    private Map<String, Document> artifactsById(List<Document> files, List<GenerationValidationIssue> issues) {
        Map<String, Document> artifacts = new LinkedHashMap<>();
        for (Document file : files) {
            String artifactId = sourceArtifactId(file);
            if (Objects.isNull(artifactId)) {
                issues.add(issue("MISSING_ARTIFACT", "generation file does not name a source artifact"));
                continue;
            }
            Document artifact = template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                    .find(new Document("sourceArtifactId", artifactId)).first();
            if (Objects.isNull(artifact)) {
                issues.add(issue("MISSING_ARTIFACT", "generation file references an absent source artifact"));
            } else {
                artifacts.put(file.getString("sourcePath"), artifact);
            }
        }
        return artifacts;
    }

    /** Generation files use the converter's SourceArtifactId value-object shape. */
    private static String sourceArtifactId(Document file) {
        Object value = file.get("sourceArtifactId");
        if (value instanceof Document artifact) {
            return artifact.getString("value");
        }
        return null; // cs-allow
    }

    private static void validateCanonicalIdentities(List<Document> symbols, List<GenerationValidationIssue> issues) {
        Set<String> seen = new LinkedHashSet<>();
        for (Document symbol : symbols) {
            String canonical = symbol.getString("canonical");
            if (Objects.isNull(canonical) || !seen.add(canonical)) {
                issues.add(issue("DUPLICATE_CANONICAL", "symbol canonical identity is absent or duplicated"));
            }
        }
    }

    private static void validateRelations(List<Document> symbols, List<Document> relations, List<GenerationValidationIssue> issues) {
        Set<String> canonicalSymbols = symbols.stream().map(document -> document.getString("canonical"))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (Document relation : relations) {
            String from = relation.getString("from");
            if (!canonicalSymbols.contains(from)) {
                issues.add(issue("DANGLING_INTERNAL_RELATION", "relation source does not identify a generation symbol"));
            }
            String target = relation.getString("target");
            if (!isExplicitTarget(target)) {
                issues.add(issue("UNCLASSIFIED_RELATION_TARGET", "unresolved relation target is not explicitly external"));
            }
            String internalTarget = internalTarget(target);
            if (Objects.nonNull(internalTarget) && !canonicalSymbols.contains(internalTarget)) {
                issues.add(issue("DANGLING_INTERNAL_RELATION", "internal relation target does not identify a generation symbol"));
            }
        }
    }

    private static void validateEntryPoints(List<Document> symbols, List<Document> entryPoints, List<GenerationValidationIssue> issues) {
        Set<String> canonicalSymbols = symbols.stream().map(document -> document.getString("canonical"))
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        for (Document entryPoint : entryPoints) {
            String method = entryPoint.getString("method");
            if (!containsMethodSymbol(canonicalSymbols, method)) {
                issues.add(issue("MISSING_ENTRY_POINT_METHOD", "entry point does not identify a generation method"));
            }
        }
    }

    private static boolean containsMethodSymbol(Set<String> canonicalSymbols, String method) {
        if (Objects.isNull(method)) {
            return false;
        }
        String methodSuffix = "|METHOD|" + method;
        return canonicalSymbols.contains(method) || canonicalSymbols.stream().anyMatch(candidate -> candidate.endsWith(methodSuffix));
    }

    private static void validateRanges(List<Document> symbols, List<Document> relations, List<Document> entryPoints,
                                       List<Document> files, Map<String, Document> artifacts, List<GenerationValidationIssue> issues) {
        Map<String, Document> generationFiles = new LinkedHashMap<>();
        for (Document file : files) {
            generationFiles.put(file.getString("sourcePath"), file);
        }
        Stream.concat(Stream.concat(symbols.stream(), relations.stream()), entryPoints.stream()).forEach(document -> {
            String sourcePath = document.getString("sourcePath");
            Document artifact = artifacts.get(sourcePath);
            Document range = rangeDocument(document);
            if (!generationFiles.containsKey(sourcePath) || Objects.isNull(artifact)
                    || !Objects.equals(sourcePath, rangeSourceFile(range)) || !rangeFits(range, artifact)) {
                issues.add(issue("INVALID_RANGE", "a projection range does not fit its generation source artifact"));
            }
        });
    }

    private static String rangeSourceFile(Document range) {
        return Objects.isNull(range) ? null : range.getString("sourceFile"); // cs-allow
    }

    private static boolean rangeFits(Document range, Document artifact) {
        if (Objects.isNull(range)) {
            return false;
        }
        Integer storedStartLine = range.getInteger("startLine");
        Integer storedStartCharacter = range.getInteger("startCharacter");
        Integer storedEndLine = range.getInteger("endLine");
        Integer storedEndCharacter = range.getInteger("endCharacter");
        if (Objects.nonNull(storedStartLine) && Objects.nonNull(storedStartCharacter)
                && Objects.nonNull(storedEndLine) && Objects.nonNull(storedEndCharacter)) {
            return offsetsFit(storedStartLine, storedStartCharacter, storedEndLine, storedEndCharacter, artifact);
        }
        Document syntaxRange = range.get("range", Document.class);
        if (Objects.isNull(syntaxRange)) {
            return false;
        }
        Document start = syntaxRange.get("start", Document.class);
        Document end = syntaxRange.get("end", Document.class);
        if (Objects.isNull(start) || Objects.isNull(end)) {
            return false;
        }
        Integer startLine = start.getInteger("line");
        Integer startCharacter = start.getInteger("character");
        Integer endLine = end.getInteger("line");
        Integer endCharacter = end.getInteger("character");
        if (Objects.isNull(startLine) || Objects.isNull(startCharacter) || Objects.isNull(endLine) || Objects.isNull(endCharacter)
                ) {
            return false;
        }
        return offsetsFit(startLine, startCharacter, endLine, endCharacter, artifact);
    }

    private static Document rangeDocument(Document projection) {
        Document directRange = projection.get("range", Document.class);
        if (Objects.nonNull(directRange)) {
            return directRange;
        }
        Document entryPoint = projection.get("entryPoint", Document.class);
        return Objects.isNull(entryPoint) ? null : entryPoint.get("range", Document.class); // cs-allow
    }

    private static boolean offsetsFit(int startLine, int startCharacter, int endLine, int endCharacter, Document artifact) {
        List<Integer> offsets = artifact.getList("lineOffsets", Integer.class);
        String source = artifact.getString("utf8Content");
        if (Objects.isNull(offsets) || Objects.isNull(source) || startLine < 0 || startCharacter < 0 || endLine < startLine
                || endCharacter < 0 || startLine >= offsets.size() || endLine >= offsets.size()
                || startCharacter > lineLength(offsets, source, startLine) || endCharacter > lineLength(offsets, source, endLine)
                || (startLine == endLine && startCharacter > endCharacter)) {
            return false;
        }
        int startOffset = offsets.get(startLine) + startCharacter;
        int endOffset = offsets.get(endLine) + endCharacter;
        return startOffset >= 0 && endOffset >= startOffset && endOffset <= source.length();
    }

    private static int lineLength(List<Integer> offsets, String source, int line) {
        int start = offsets.get(line);
        int end = line + 1 < offsets.size() ? offsets.get(line + 1) : source.length();
        if (end > start && source.charAt(end - 1) == '\n') {
            end--;
        }
        if (end > start && source.charAt(end - 1) == '\r') {
            end--;
        }
        return end - start;
    }

    private ProjectionDocuments validateProjectionDocuments(MongoGenerationWriter.GenerationLease lease,
                                                              RepositoryRevision requestedRevision,
                                                              List<Document> symbolDocuments,
                                                              List<Document> relationDocuments,
                                                              List<Document> entryPointDocuments,
                                                              List<Document> searchDocuments,
                                                              List<GenerationValidationIssue> issues) {
        List<StoredSymbol> symbols = new ArrayList<>();
        for (Document document : symbolDocuments) {
            try {
                SymbolDocument symbol = template.getConverter().read(SymbolDocument.class, document);
                validateScope(lease, requestedRevision, symbol.repositoryId().value(), symbol.generationId().value(),
                        symbol.fact().identity().repositoryRevision(), issues);
                if (!symbol.fact().id().value().equals(document.getString("symbolId"))
                        || !symbol.fact().identity().canonicalForm().equals(document.getString("canonical"))) {
                    issues.add(issue("PROJECTION_IDENTITY_MISMATCH", "symbol storage identity differs from its authoritative fact"));
                }
                symbols.add(new StoredSymbol(document, symbol));
            } catch (RuntimeException exception) {
                issues.add(issue("INVALID_PROJECTION_DOCUMENT", "symbol projection cannot be reconstructed"));
            }
        }
        List<StoredRelation> relations = new ArrayList<>();
        for (Document document : relationDocuments) {
            try {
                RelationDocument relation = projectionMapper.reconstructRelation(document);
                validateScope(lease, requestedRevision, relation.repositoryId().value(), relation.generationId().value(),
                        relation.fact().identity().repositoryRevision(), issues);
                if (!relation.fact().id().value().equals(document.getString("relationId"))
                        || !relation.from().canonicalForm().equals(document.getString("from"))
                        || !relation.target().canonicalForm().equals(document.getString("target"))) {
                    issues.add(issue("PROJECTION_IDENTITY_MISMATCH", "relation storage identity differs from its authoritative fact"));
                }
                relations.add(new StoredRelation(document, relation));
            } catch (RuntimeException exception) {
                issues.add(issue("INVALID_PROJECTION_DOCUMENT", "relation projection cannot be reconstructed"));
            }
        }
        List<StoredEntryPoint> entryPoints = new ArrayList<>();
        for (Document document : entryPointDocuments) {
            try {
                EntryPointDocument entryPoint = projectionMapper.reconstructEntryPoint(document);
                validateScope(lease, requestedRevision, entryPoint.repositoryId().value(), entryPoint.generationId().value(),
                        entryPoint.fact().identity().repositoryRevision(), issues);
                if (!entryPoint.fact().id().value().equals(document.getString("entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(document.getString("canonical"))
                        || !entryPoint.method().canonicalForm().equals(document.getString("method"))) {
                    issues.add(issue("PROJECTION_IDENTITY_MISMATCH", "entry-point storage identity differs from its authoritative fact"));
                }
                entryPoints.add(new StoredEntryPoint(document, entryPoint));
            } catch (RuntimeException exception) {
                issues.add(issue("INVALID_PROJECTION_DOCUMENT", "entry-point projection cannot be reconstructed"));
            }
        }
        List<StoredSearch> search = new ArrayList<>();
        for (Document document : searchDocuments) {
            try {
                SearchDocument searchDocument = projectionMapper.reconstructSearch(document);
                validateScope(lease, requestedRevision, searchDocument.repositoryId().value(), searchDocument.generationId().value(),
                        searchDocument.authoritativeIdentity().repositoryRevision(), issues);
                search.add(new StoredSearch(document, searchDocument));
            } catch (RuntimeException exception) {
                issues.add(issue("INVALID_PROJECTION_DOCUMENT", "search projection cannot be reconstructed"));
            }
        }
        return new ProjectionDocuments(symbols, relations, entryPoints, search);
    }

    private static void validateScope(MongoGenerationWriter.GenerationLease lease, RepositoryRevision requestedRevision,
                                      String repositoryId, String generationId, RepositoryRevision projectionRevision,
                                      List<GenerationValidationIssue> issues) {
        if (!lease.repositoryId().value().equals(repositoryId) || !lease.generationId().value().equals(generationId)) {
            issues.add(issue("PROJECTION_SCOPE_MISMATCH", "projection repository or generation differs from the build"));
        }
        if (!requestedRevision.equals(projectionRevision)) {
            issues.add(issue("PROJECTION_REVISION_MISMATCH", "projection revision differs from the requested revision"));
        }
    }

    private static void validateProjectionArtifacts(List<Document> files, List<StoredSymbol> symbols,
                                                    List<StoredRelation> relations,
                                                    List<GenerationValidationIssue> issues) {
        Map<String, String> artifactIdsByPath = new LinkedHashMap<>();
        for (Document file : files) {
            artifactIdsByPath.put(file.getString("sourcePath"), sourceArtifactId(file));
        }
        for (StoredSymbol stored : symbols) {
            String expected = artifactIdsByPath.get(stored.document().getString("sourcePath"));
            if (!stored.symbol().sourceArtifactId().value().equals(expected)) {
                issues.add(issue("PROJECTION_ARTIFACT_MISMATCH", "symbol source artifact differs from its generation file"));
            }
        }
        for (StoredRelation stored : relations) {
            String expected = artifactIdsByPath.get(stored.document().getString("sourcePath"));
            if (!stored.relation().sourceArtifactId().value().equals(expected)) {
                issues.add(issue("PROJECTION_ARTIFACT_MISMATCH", "relation source artifact differs from its generation file"));
            }
        }
    }

    private static void validateSearchCoverage(ProjectionDocuments projections, List<Document> storedSearch,
                                               List<GenerationValidationIssue> issues) {
        Map<String, ExpectedSearch> expected = new LinkedHashMap<>();
        for (StoredSymbol stored : projections.symbols()) {
            SymbolDocument symbol = stored.symbol();
            expected.put(symbol.fact().id().value(), new ExpectedSearch(ProjectionName.SYMBOLS,
                    symbol.fact().identity().kind(), symbol.fact().identity().canonicalForm(),
                    stored.document().getString("sourcePath")));
        }
        for (StoredRelation stored : projections.relations()) {
            RelationDocument relation = stored.relation();
            expected.put(relation.fact().id().value(), new ExpectedSearch(ProjectionName.RELATIONS,
                    relation.fact().identity().kind(), relation.fact().identity().canonicalForm(),
                    stored.document().getString("sourcePath")));
        }
        for (StoredEntryPoint stored : projections.entryPoints()) {
            EntryPointDocument entryPoint = stored.entryPoint();
            expected.put(entryPoint.fact().id().value(), new ExpectedSearch(ProjectionName.ENTRY_POINTS,
                    entryPoint.fact().identity().kind(), entryPoint.fact().identity().canonicalForm(),
                    stored.document().getString("sourcePath")));
        }
        Set<String> found = new LinkedHashSet<>();
        for (StoredSearch stored : projections.search()) {
            Document document = stored.document();
            SearchDocument search = stored.search();
            String factId = document.getString("factId");
            ExpectedSearch authority = expected.get(factId);
            if (!search.factId().value().equals(factId) || Objects.isNull(authority) || !found.add(factId)) {
                issues.add(issue("ORPHAN_SEARCH", "search projection has no unique authoritative fact"));
                continue;
            }
            if (search.authoritativeProjection() != authority.projection()
                    || search.kind() != authority.kind()
                    || !search.authoritativeIdentity().canonicalForm().equals(authority.canonical())
                    || !authority.projection().name().equals(document.getString("authority"))
                    || !authority.kind().name().equals(document.getString("kind"))
                    || !authority.canonical().equals(document.getString("canonical"))
                    || !authority.sourcePath().equals(document.getString("sourcePath"))) {
                issues.add(issue("SEARCH_AUTHORITY_MISMATCH", "search projection differs from its authoritative fact"));
            }
        }
        if (!found.containsAll(expected.keySet()) || storedSearch.size() != projections.search().size()) {
            issues.add(issue("INCOMPLETE_SEARCH", "not every authoritative fact has one valid search projection"));
        }
    }

    private static Document noBatches(String field) {
        return new Document("$eq", List.of(new Document("$size", new Document("$ifNull", List.of("$" + field, List.of()))), 0));
    }

    private static Map<String, Long> collectionCounts(List<Document> files, Map<String, Document> artifacts, List<Document> symbols,
                                                       List<Document> relations, List<Document> entryPoints, List<Document> search) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(IndexCollections.GENERATION_FILES, (long) files.size());
        counts.put(IndexCollections.SOURCE_ARTIFACTS, (long) new LinkedHashSet<>(artifacts.values()).size());
        counts.put(IndexCollections.SYMBOLS, (long) symbols.size());
        counts.put(IndexCollections.RELATIONS, (long) relations.size());
        counts.put(IndexCollections.ENTRY_POINTS, (long) entryPoints.size());
        counts.put(IndexCollections.SEARCH, (long) search.size());
        return Map.copyOf(counts);
    }

    private static ManifestDigest digest(List<Document> files, List<Document> symbols, List<Document> relations,
                                         List<Document> entryPoints, List<Document> search) {
        List<String> identities = new ArrayList<>();
        addIdentities(identities, IndexCollections.GENERATION_FILES, files, "sourcePath");
        addIdentities(identities, IndexCollections.SYMBOLS, symbols, "canonical");
        addIdentities(identities, IndexCollections.RELATIONS, relations, "relationId");
        addIdentities(identities, IndexCollections.ENTRY_POINTS, entryPoints, "entryPointId");
        addIdentities(identities, IndexCollections.SEARCH, search, "factId");
        identities.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String identity : identities) {
                digest.update(identity.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return new ManifestDigest(java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private static void addIdentities(List<String> identities, String collection, List<Document> documents, String field) {
        for (Document document : documents) {
            identities.add(collection + "|" + Objects.toString(document.getString(field), ""));
        }
    }

    private List<Document> documents(String collection, MongoGenerationWriter.GenerationLease lease) {
        return template.getCollection(collection).find(Filters.and(Filters.eq("repoId", lease.repositoryId().value()),
                Filters.eq("generationId", lease.generationId().value()))).into(new ArrayList<>());
    }

    private static Map<String, Integer> projectionVersions(Document manifest) {
        List<Document> versions = manifest.getList("projectionVersions", Document.class);
        if (Objects.isNull(versions)) {
            return Map.of();
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Document version : versions) {
            String name = version.getString("name");
            Number number = version.get("version", Number.class);
            if (Objects.isNull(name) || Objects.isNull(number)) {
                return Map.of();
            }
            result.put(name, number.intValue());
        }
        return Map.copyOf(result);
    }

    private static String internalTarget(String target) {
        if (Objects.isNull(target) || !target.startsWith("internal[")) {
            return null; // cs-allow
        }
        int closing = target.indexOf(']');
        if (closing < 9) {
            return "";
        }
        try {
            int length = Integer.parseInt(target.substring("internal[".length(), closing));
            int start = closing + 1;
            return start + length == target.length() ? target.substring(start) : "";
        } catch (NumberFormatException exception) {
            return "";
        }
    }

    private static boolean isExplicitTarget(String target) {
        return Objects.nonNull(target) && (target.startsWith("internal[") || target.startsWith("external-"));
    }

    private static Document manifestFilter(MongoGenerationWriter.GenerationLease lease) {
        return new Document("repoId", lease.repositoryId().value()).append("generationId", lease.generationId().value())
                .append("ownerJobId", lease.jobId()).append("ownerWorkerId", lease.workerId()).append("fence", lease.fence())
                .append("writeState", "WRITING");
    }

    private static GenerationValidationIssue issue(String code, String detail) {
        return new GenerationValidationIssue(code, detail);
    }

    private record StoredSymbol(Document document, SymbolDocument symbol) { }

    private record StoredRelation(Document document, RelationDocument relation) { }

    private record StoredEntryPoint(Document document, EntryPointDocument entryPoint) { }

    private record StoredSearch(Document document, SearchDocument search) { }

    private record ExpectedSearch(ProjectionName projection, CodeFactKind kind, String canonical, String sourcePath) { }

    private record ProjectionDocuments(List<StoredSymbol> symbols, List<StoredRelation> relations,
                                       List<StoredEntryPoint> entryPoints, List<StoredSearch> search) {
        private ProjectionDocuments {
            symbols = List.copyOf(symbols);
            relations = List.copyOf(relations);
            entryPoints = List.copyOf(entryPoints);
            search = List.copyOf(search);
        }
    }

    public record ValidationResult(ManifestDigest identityDigest, Map<String, Long> collectionCounts,
                                   List<GenerationValidationIssue> issues) {
        public ValidationResult {
            identityDigest = Objects.requireNonNull(identityDigest, "identity digest is required");
            collectionCounts = Map.copyOf(Objects.requireNonNull(collectionCounts, "collection counts are required"));
            issues = List.copyOf(Objects.requireNonNull(issues, "issues are required"));
        }

        public boolean valid() {
            return issues.isEmpty();
        }
    }
}
