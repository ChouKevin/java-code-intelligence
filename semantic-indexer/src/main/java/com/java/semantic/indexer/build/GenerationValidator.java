package com.java.semantic.indexer.build;

import com.java.semantic.indexer.store.GenerationWriteContext;
import com.java.semantic.indexer.store.GenerationBuildOwnership;
import com.java.semantic.model.index.AnalysisFingerprint;
import com.java.semantic.model.index.AnalysisInputs;
import com.java.semantic.model.index.SemanticAnalysisEvidence;
import com.java.semantic.indexer.store.MongoIndexDefinitionMatcher;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.IndexCollections;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.ManifestDigest;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.SourceArtifactId;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.query.SelectedGeneration;
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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Validates one unpublished generation without ever returning stored source content. */
public final class GenerationValidator {
    private static final List<ValidatedProjection> VALIDATION_DISPATCH = List.of(
            new ValidatedProjection(ProjectionName.SOURCES, "sourcePath"),
            new ValidatedProjection(ProjectionName.SYMBOLS, "canonical"),
            new ValidatedProjection(ProjectionName.RELATIONS, "relationId"),
            new ValidatedProjection(ProjectionName.ENTRY_POINTS, "entryPointId"),
            new ValidatedProjection(ProjectionName.SEARCH, "factId"));

    private final MongoTemplate template;
    private final SourceIndexBatchDocumentMapper projectionMapper;
    private final GenerationBuildOwnership ownership;

    public GenerationValidator(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "mongo template is required");
        projectionMapper = new SourceIndexBatchDocumentMapper(template.getConverter());
        ownership = new GenerationBuildOwnership(template);
    }

    public ValidationResult validate(GenerationWriteContext context, RepositoryRevision requestedRevision,
                                     RepositoryRevision checkedOutRevision) {
        Objects.requireNonNull(context, "generation write context is required");
        Objects.requireNonNull(requestedRevision, "requested revision is required");
        Objects.requireNonNull(checkedOutRevision, "checked out revision is required");
        List<GenerationValidationIssue> issues = new ArrayList<>();
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(manifestFilter(context)).first();
        if (Objects.isNull(manifest)) {
            issues.add(issue("MISSING_MANIFEST", "generation manifest is not owned by this job"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        if (!runningBuildOwns(context)) {
            issues.add(issue("JOB_NOT_RUNNING", "generation job is not the active running build owner"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        manifest = freezeForValidation(context);
        if (Objects.isNull(manifest)) {
            issues.add(issue("VALIDATION_FREEZE_FAILED", "generation changed or has unfinished batches before validation"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        validateManifest(manifest, requestedRevision, issues);
        if (!requestedRevision.equals(checkedOutRevision)) {
            issues.add(issue("CHECKOUT_CHANGED", "checked-out revision no longer matches the requested revision"));
        }
        validateOwnership(context, issues);
        validateRequiredIndexes(issues);
        return validatePersistedGraph(context, requestedRevision, manifest, issues, true);
    }

    /**
     * Revalidates a sealed generation from its persisted graph before that graph is reused by another workflow.
     * The check deliberately shares the same projection, evidence, accounting, count, and identity computation
     * that seals a build generation.
     */
    public ValidationResult validatePersistedSealed(SelectedGeneration selected) {
        Objects.requireNonNull(selected, "selected generation is required");
        List<GenerationValidationIssue> issues = new ArrayList<>();
        Document manifest = template.getCollection(IndexCollections.GENERATION_MANIFESTS).find(new Document("repoId",
                selected.repositoryId().value()).append("generationId", selected.generationId().value())
                .append("sourceRevision", selected.revision().value()).append("writeState", "SEALED_VALID")).first();
        if (Objects.isNull(manifest)) {
            issues.add(issue("MISSING_SEALED_MANIFEST", "selected generation is not a sealed persisted manifest"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        String ownerJobId = manifest.getString("ownerJobId");
        if (Objects.isNull(ownerJobId)) {
            issues.add(issue("MISSING_SEALED_OWNER", "sealed generation has no owner"));
            return new ValidationResult(new ManifestDigest("0".repeat(64)), Map.of(), issues);
        }
        GenerationWriteContext context = new GenerationWriteContext(selected.repositoryId(), selected.generationId(), ownerJobId);
        validateManifest(manifest, selected.revision(), issues);
        validateRequiredIndexes(issues);
        ValidationResult result = validatePersistedGraph(context, selected.revision(), manifest, issues, false);
        if (!selected.manifestDigest().value().equals(manifest.getString("identityDigest"))
                || !selected.manifestDigest().equals(result.identityDigest())) {
            issues.add(issue("SEALED_IDENTITY_MISMATCH", "sealed manifest identity does not match its persisted graph"));
        }
        validateSealedCollectionCounts(manifest, result.collectionCounts(), issues);
        return new ValidationResult(result.identityDigest(), result.collectionCounts(), issues);
    }

    private ValidationResult validatePersistedGraph(GenerationWriteContext context, RepositoryRevision requestedRevision,
                                                    Document manifest, List<GenerationValidationIssue> issues,
                                                    boolean requireActiveOwner) {
        Map<ProjectionName, List<Document>> persistedProjections = projectionDocuments(context);
        List<Document> files = persistedProjections.get(ProjectionName.SOURCES);
        List<Document> symbols = persistedProjections.get(ProjectionName.SYMBOLS);
        List<Document> relations = persistedProjections.get(ProjectionName.RELATIONS);
        List<Document> entryPoints = persistedProjections.get(ProjectionName.ENTRY_POINTS);
        List<Document> search = persistedProjections.get(ProjectionName.SEARCH);
        Map<String, Document> artifacts = artifactsById(files, issues);
        validateAnalysisEvidence(manifest, files, issues);
        validateCanonicalIdentities(symbols, issues);
        validateRelations(symbols, relations, issues);
        validateEntryPoints(symbols, entryPoints, issues);
        validateRanges(symbols, relations, entryPoints, files, artifacts, issues);
        ProjectionDocuments projections = validateProjectionDocuments(context, requestedRevision, symbols, relations, entryPoints, search,
                issues);
        validateProjectionArtifacts(files, projections.symbols(), projections.relations(), issues);
        validateSearchCoverage(projections, search, issues);
        if (requireActiveOwner) {
            validateOwnership(context, issues);
        }
        Map<String, Long> counts = collectionCounts(persistedProjections, artifacts);
        ManifestDigest digest = digest(persistedProjections, manifest);
        return new ValidationResult(digest, counts, issues);
    }

    private static void validateSealedCollectionCounts(Document manifest, Map<String, Long> counts,
                                                       List<GenerationValidationIssue> issues) {
        Document sealedCounts = manifest.get("sealedCollectionCounts", Document.class);
        if (Objects.isNull(sealedCounts) || !sealedCounts.keySet().equals(counts.keySet())) {
            issues.add(issue("SEALED_COUNT_MISMATCH", "sealed collection counts are absent or incomplete"));
            return;
        }
        for (Map.Entry<String, Long> expected : counts.entrySet()) {
            Number actual = sealedCounts.get(expected.getKey(), Number.class);
            if (Objects.isNull(actual) || actual.longValue() != expected.getValue()) {
                issues.add(issue("SEALED_COUNT_MISMATCH", "sealed collection counts differ from persisted projections"));
                return;
            }
        }
    }

    /** Keys of the production dispatch that reads, validates, counts, and digests persisted projections. */
    public static Set<ProjectionName> validatedCountedAndDigestedProjections() {
        return VALIDATION_DISPATCH.stream().map(ValidatedProjection::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Records the exact values that publication re-checks before the manifest is sealed. */
    public void recordValid(GenerationWriteContext context, ValidationResult result) {
        Objects.requireNonNull(context, "generation write context is required");
        Objects.requireNonNull(result, "validation result is required");
        if (!result.valid()) {
            throw new IllegalArgumentException("cannot record an invalid generation");
        }
        Document update = new Document("$set", new Document("identityDigest", result.identityDigest().value())
                .append("sealedCollectionCounts", new Document(result.collectionCounts()))
                .append("validationResult", "VALID").append("validatedAt", Date.from(Instant.now())));
        if (!runningBuildOwns(context)) {
            throw new IllegalStateException("generation validation record job is not running");
        }
        Document filter = manifestFilter(context).append("validationResult", "VALIDATING");
        long changed = template.getCollection(IndexCollections.GENERATION_MANIFESTS).updateOne(filter, update).getModifiedCount();
        if (changed != 1L) {
            throw new IllegalStateException("generation validation record lost its job ownership");
        }
    }
    private void validateAnalysisEvidence(Document manifest, List<Document> files, List<GenerationValidationIssue> issues) {
        Document inputsDocument = manifest.get("analysisInputs", Document.class);
        Document evidenceDocument = manifest.get("analysisEvidence", Document.class);
        String storedFingerprint = manifest.getString("analysisFingerprint");
        if (Objects.isNull(inputsDocument) || Objects.isNull(evidenceDocument) || Objects.isNull(storedFingerprint)) {
            issues.add(issue("MISSING_ANALYSIS_EVIDENCE", "manifest has no prepared semantic analysis evidence"));
            return;
        }
        try {
            AnalysisInputs inputs = template.getConverter().read(AnalysisInputs.class, inputsDocument);
            SemanticAnalysisEvidence evidence = template.getConverter().read(SemanticAnalysisEvidence.class, evidenceDocument);
            if (!AnalysisFingerprint.from(inputs).digest().equals(storedFingerprint)
                    || !storedFingerprint.equals(evidence.fingerprintDigest())) {
                issues.add(issue("ANALYSIS_FINGERPRINT_MISMATCH", "prepared inputs and export evidence have different fingerprints"));
            }
            SemanticAnalysisEvidence.ResolutionCoverage coverage = evidence.resolution();
            if (coverage.attempted() != coverage.resolved() + coverage.unresolved() + coverage.ambiguous()
                    || coverage.external() > coverage.resolved()) {
                issues.add(issue("INVALID_RESOLUTION_ACCOUNTING", "semantic resolution accounting is inconsistent"));
            }
            Map<String, SemanticAnalysisEvidence.ProjectProof> proofs = evidence.projects().stream()
                    .collect(java.util.stream.Collectors.toMap(SemanticAnalysisEvidence.ProjectProof::projectPath,
                            java.util.function.Function.identity(), (left, right) -> left));
            if (proofs.size() != inputs.projects().size()) {
                issues.add(issue("ANALYSIS_PROJECT_MISMATCH", "semantic evidence does not prove every prepared project"));
            }
            for (AnalysisInputs.Project project : inputs.projects()) {
                SemanticAnalysisEvidence.ProjectProof proof = proofs.get(project.projectPath());
                if (Objects.isNull(proof) || !proof.imported()) {
                    issues.add(issue("ANALYSIS_PROJECT_MISMATCH", "prepared project is absent from semantic evidence"));
                    continue;
                }
                Set<String> includedRoots = project.roots().stream().filter(AnalysisInputs.Root::included)
                        .map(AnalysisInputs.Root::path).collect(java.util.stream.Collectors.toSet());
                if (!proof.verifiedSourcePaths().containsAll(includedRoots)) {
                    issues.add(issue("ANALYSIS_ROOT_MISMATCH", "semantic evidence does not prove each included root"));
                }
            }
            if (!Set.of("SUCCESS", "WITH_ERROR").contains(evidence.buildStatus())) {
                issues.add(issue("INVALID_SEMANTIC_BUILD_STATUS",
                        "manifest semantic evidence has no successful JDT build status"));
            }
            Set<String> sourcePaths = files.stream().map(file -> file.getString("sourcePath"))
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (SemanticAnalysisEvidence.ProjectProof proof : evidence.projects()) {
                for (String root : proof.verifiedSourcePaths()) {
                    if (!root.equals(".") && sourcePaths.stream().noneMatch(path -> path.equals(root) || path.startsWith(root + "/"))) {
                        issues.add(issue("ANALYSIS_SOURCE_MISMATCH", "verified semantic root has no persisted source"));
                    }
                }
            }
        } catch (RuntimeException exception) {
            issues.add(issue("INVALID_ANALYSIS_EVIDENCE", "manifest semantic evidence cannot be reconstructed"));
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

    private void validateOwnership(GenerationWriteContext context, List<GenerationValidationIssue> issues) {
        if (!runningBuildOwns(context)) {
            issues.add(issue("JOB_NOT_RUNNING", "generation job is not the active running build owner"));
        }
    }

    private boolean runningBuildOwns(GenerationWriteContext context) {
        try {
            ownership.require(context);
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private Document freezeForValidation(GenerationWriteContext context) {
        Document filter = manifestFilter(context).append("validationResult", new Document("$exists", false))
                .append("$expr", new Document("$and", List.of(noBatches("outstandingBatches"), noBatches("failedOrAmbiguousBatches"))));
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
            String sourcePath;
            String artifactId;
            String contentHash;
            try {
                sourcePath = file.getString("sourcePath");
                artifactId = sourceArtifactId(file);
                contentHash = file.getString("contentHash");
            } catch (RuntimeException exception) {
                issues.add(issue("GENERATION_FILE_ARTIFACT_MISMATCH",
                        "generation file does not carry readable source artifact metadata"));
                continue;
            }
            if (Objects.isNull(sourcePath) || Objects.isNull(artifactId) || Objects.isNull(contentHash)
                    || !artifactId.equals(contentHash) || !validSourceArtifactId(artifactId)) {
                issues.add(issue("GENERATION_FILE_ARTIFACT_MISMATCH",
                        "generation file does not carry one valid immutable source artifact identity"));
                continue;
            }
            if (artifacts.containsKey(sourcePath)) {
                issues.add(issue("DUPLICATE_GENERATION_FILE", "generation has more than one source row for a path"));
                continue;
            }
            List<Document> matched = template.getCollection(IndexCollections.SOURCE_ARTIFACTS)
                    .find(new Document("sourceArtifactId", artifactId)).into(new ArrayList<>());
            if (matched.isEmpty()) {
                issues.add(issue("MISSING_ARTIFACT", "generation file references an absent source artifact"));
                continue;
            }
            if (matched.size() != 1) {
                issues.add(issue("DUPLICATE_SOURCE_ARTIFACT", "source artifact identity is not globally unique"));
                continue;
            }
            Document artifact = matched.getFirst();
            if (!validArtifact(artifactId, contentHash, artifact)) {
                issues.add(issue("INVALID_SOURCE_ARTIFACT",
                        "source artifact bytes, digest, identity, or line offsets do not agree"));
                continue;
            }
            artifacts.put(sourcePath, artifact);
        }
        return artifacts;
    }

    private static boolean validArtifact(String expectedId, String expectedHash, Document artifact) {
        try {
            String storedId = artifact.getString("sourceArtifactId");
            String storedHash = artifact.getString("contentHash");
            String source = artifact.getString("utf8Content");
            List<Integer> storedOffsets = artifact.getList("lineOffsets", Integer.class);
            if (Objects.isNull(storedId) || Objects.isNull(storedHash) || Objects.isNull(source) || Objects.isNull(storedOffsets)) {
                return false;
            }
            SourceArtifactDocument reconstructed = SourceArtifactDocument.create(source);
            return expectedId.equals(storedId) && expectedHash.equals(storedHash)
                    && expectedId.equals(reconstructed.id().value()) && expectedHash.equals(reconstructed.contentHash())
                    && storedOffsets.equals(reconstructed.lineOffsets());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean validSourceArtifactId(String value) {
        try {
            new SourceArtifactId(value);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
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

    private ProjectionDocuments validateProjectionDocuments(GenerationWriteContext context,
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
                validateScope(context, requestedRevision, symbol.repositoryId().value(), symbol.generationId().value(),
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
                validateScope(context, requestedRevision, relation.repositoryId().value(), relation.generationId().value(),
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
                validateScope(context, requestedRevision, entryPoint.repositoryId().value(), entryPoint.generationId().value(),
                        entryPoint.fact().identity().repositoryRevision(), issues);
                if (!entryPoint.fact().id().value().equals(document.getString("entryPointId"))
                        || !entryPoint.fact().identity().canonicalForm().equals(document.getString("canonical"))
                        || !entryPoint.method().canonicalForm().equals(document.getString("method"))
                        || !entryPoint.trigger().httpPath().equals(Optional.ofNullable(document.getString("path")))) {
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
                validateScope(context, requestedRevision, searchDocument.repositoryId().value(), searchDocument.generationId().value(),
                        searchDocument.authoritativeIdentity().repositoryRevision(), issues);
                search.add(new StoredSearch(document, searchDocument));
            } catch (RuntimeException exception) {
                issues.add(issue("INVALID_PROJECTION_DOCUMENT", "search projection cannot be reconstructed"));
            }
        }
        return new ProjectionDocuments(symbols, relations, entryPoints, search);
    }

    private static void validateScope(GenerationWriteContext context, RepositoryRevision requestedRevision,
                                      String repositoryId, String generationId, RepositoryRevision projectionRevision,
                                      List<GenerationValidationIssue> issues) {
        if (!context.repositoryId().value().equals(repositoryId) || !context.generationId().value().equals(generationId)) {
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
                    || !Objects.equals(authority.sourcePath(), document.getString("sourcePath"))
                    || !search.normalizedTokens().equals(document.getList("tokens", String.class))
                    || !search.packageName().orElse("").equals(document.getString("package"))) {
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

    private static Map<String, Long> collectionCounts(Map<ProjectionName, List<Document>> projections,
                                                       Map<String, Document> artifacts) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(IndexCollections.SOURCE_ARTIFACTS, (long) new LinkedHashSet<>(artifacts.values()).size());
        for (ValidatedProjection projection : VALIDATION_DISPATCH) {
            counts.put(IndexSchemaContract.projectionCollection(projection.name()), (long) projections.get(projection.name()).size());
        }
        return Map.copyOf(counts);
    }

    private static ManifestDigest digest(Map<ProjectionName, List<Document>> projections, Document manifest) {
        List<String> identities = new ArrayList<>();
        for (ValidatedProjection projection : VALIDATION_DISPATCH) {
            addIdentities(identities, IndexSchemaContract.projectionCollection(projection.name()),
                    projections.get(projection.name()), projection.identityField());
        }
        identities.add("analysisFingerprint|" + Objects.toString(manifest.getString("analysisFingerprint"), ""));
        Document inputs = manifest.get("analysisInputs", Document.class);
        Document evidence = manifest.get("analysisEvidence", Document.class);
        identities.add("analysisInputs|" + canonicalAnalysisValue(inputs, "analysisInputs"));
        identities.add("analysisEvidence|" + canonicalAnalysisValue(evidence, "analysisEvidence"));
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

    private static String canonicalAnalysisValue(Object value, String field) {
        if (Objects.isNull(value)) {
            return "null";
        }
        if (value instanceof Document document) {
            return canonicalAnalysisValue(new LinkedHashMap<>(document), field);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> Map.entry(String.valueOf(entry.getKey()),
                            canonicalAnalysisValue(entry.getValue(), String.valueOf(entry.getKey()))))
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            List<String> values = list.stream().map(item -> canonicalAnalysisValue(item, field)).toList();
            if (Set.of("projects", "roots", "verifiedSourcePaths", "limitations").contains(field)) {
                values = values.stream().sorted().toList();
            }
            return values.stream().collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }

    private static void addIdentities(List<String> identities, String collection, List<Document> documents, String field) {
        for (Document document : documents) {
            identities.add(collection + "|" + Objects.toString(document.getString(field), ""));
        }
    }

    private List<Document> documents(String collection, GenerationWriteContext context) {
        return template.getCollection(collection).find(Filters.and(Filters.eq("repoId", context.repositoryId().value()),
                Filters.eq("generationId", context.generationId().value()))).into(new ArrayList<>());
    }

    private Map<ProjectionName, List<Document>> projectionDocuments(GenerationWriteContext context) {
        Map<ProjectionName, List<Document>> projections = new LinkedHashMap<>();
        for (ValidatedProjection projection : VALIDATION_DISPATCH) {
            projections.put(projection.name(), documents(IndexSchemaContract.projectionCollection(projection.name()), context));
        }
        return Map.copyOf(projections);
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

    private record ValidatedProjection(ProjectionName name, String identityField) {
        private ValidatedProjection {
            name = Objects.requireNonNull(name, "projection name is required");
            identityField = Objects.requireNonNull(identityField, "projection identity field is required");
        }
    }

    private static boolean isExplicitTarget(String target) {
        return Objects.nonNull(target) && (target.startsWith("internal[") || target.startsWith("external-"));
    }

    private static Document manifestFilter(GenerationWriteContext context) {
        return new Document("repoId", context.repositoryId().value()).append("generationId", context.generationId().value())
                .append("ownerJobId", context.jobId())
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
