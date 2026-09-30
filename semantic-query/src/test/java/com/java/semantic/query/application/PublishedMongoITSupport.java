package com.java.semantic.query.application;

import com.java.semantic.model.source.SourceEvidenceDocumentCodec;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.CodeFactScope;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.IndexSchemaContract;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.persistence.SymbolPersistence;
import com.java.semantic.model.codefact.MapperStatementKind;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.index.ProjectionRequirements;
import com.java.semantic.model.source.ProjectGuideMembership;
import com.java.semantic.model.source.ProjectGuideState;
import com.java.semantic.model.source.SourceEvidencePolicy;
import com.java.semantic.model.source.SourceSnapshotMembership;
import com.java.semantic.model.source.SourceCoverage;
import com.java.semantic.model.source.SourceStructure;
import com.java.semantic.model.git.GitSnapshotId;
import java.util.Optional;
import java.util.Set;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

abstract class PublishedMongoITSupport {
    static final String REVISION = "1".repeat(40);
    static final String DIGEST = "2".repeat(64);
    static final String SOURCE_SNAPSHOT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String SOURCE_DIGEST = "a".repeat(64);

    static ConfiguredReadPolicy policy() {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()));
    }

    static ConfiguredReadPolicy policy(ReadPolicyProperties.PackageRule rule) {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(rule), List.of(), List.of()));
    }

    static CurrentGenerationSelector selector(MongoTemplate template, ConfiguredReadPolicy policy) {
        return new CurrentGenerationSelector(template, policy, Duration.ofSeconds(2));
    }

    static SelectedGenerationGuard guard(MongoTemplate template, ConfiguredReadPolicy policy) {
        return new SelectedGenerationGuard(template, policy, Duration.ofSeconds(2));
    }

    static ReadContextSelector.AdmittedContext admitted(MongoTemplate template, ConfiguredReadPolicy policy,
            ProjectionRequirements requirements) {
        return new ReadContextSelector(selector(template, policy),
                new ReviewManifestReadService(template, policy, Duration.ofSeconds(2)), guard(template, policy), policy)
                .select(SemanticQueryContract.ReadContext.current("orders", REVISION), requirements, ReadContextSelector.Access.SEMANTIC);
    }

    static SelectedSemanticQueryService semantic(MongoTemplate template, ConfiguredReadPolicy policy) {
        SelectedGenerationGuard guard = guard(template, policy);
        return new SelectedSemanticQueryService(template, guard, Duration.ofSeconds(2),
                new CodeFactReadService(template, guard, Duration.ofSeconds(2)));
    }

    static void seedSearch(MongoTemplate template, CodeFactIdentity identity, String authority, List<String> tokens) {
        CodeFactScope scope = CodeFactScope.from(identity);
        String path = scope.sourcePath().orElseThrow();
        template.getCollection("search").insertOne(new Document("repoId", identity.repositoryId().value()).append("generationId", "g1")
                .append("factId", CodeFactId.from(identity).value()).append("kind", identity.kind().name())
                .append("authority", authority).append("canonical", identity.canonicalForm())
                .append("displayName", com.java.semantic.model.codefact.CodeFactDisplay.displayName(identity.canonicalIdentity()))
                .append("signature", com.java.semantic.model.codefact.CodeFactDisplay.signature(identity.canonicalIdentity()))
                .append("sourcePath", path).append("tokens", tokens).append("scopePackage", scope.packageName())
                .append("scopeClass", scope.className()).append("scopeMethod", scope.methodName().orElse(""))
                .append("scopeParameters", scope.parameterTypes()).append("scopePath", path));
    }

    static void seedSemanticIndexes(MongoTemplate template) {
        Set<String> ordered = Set.of("search_generation_order", "entry_point_kind_order", "symbol_file_range_order",
                "symbol_kind_canonical_order", "relation_target_kind_source", "relation_from_kind_source");
        for (IndexSchemaContract.CollectionSpec collection : IndexSchemaContract.collections()) {
            for (IndexSchemaContract.IndexSpec index : collection.indexes()) {
                if (ordered.contains(index.name())) template.getCollection(collection.name()).createIndex(new Document(index.keys()),
                        new com.mongodb.client.model.IndexOptions().name(index.name()));
            }
        }
    }

    static void seedCurrent(MongoTemplate template, String repositoryId) {
        seedSemanticIndexes(template);
        Document currentPointer = new Document("revision", REVISION).append("generationId", "g1")
                .append("manifestDigest", DIGEST).append("committedJobId", "job-g1").append("publishedAt", new java.util.Date());
        template.getCollection("repositories").replaceOne(new Document("repoId", repositoryId),
                new Document("repoId", repositoryId).append("currentPointer", currentPointer),
                new ReplaceOptions().upsert(true));
        SourceEvidencePolicy sourcePolicy = new SourceEvidencePolicy(SourceEvidencePolicy.VERSION,
                List.of("src/main/java", "src/main/resources"), Set.of(), Optional.empty());
        ProjectGuideMembership guide = ProjectGuideMembership.unavailable(ProjectGuideState.DISABLED);
        template.getCollection("git_evidence_manifests").insertOne(new Document("repoId", repositoryId)
                .append("evidenceId", SOURCE_SNAPSHOT).append("kind", "SNAPSHOT").append("state", "READY")
                .append("gitEvidenceVersion", IndexSchemaContract.GIT_EVIDENCE_VERSION).append("scope", "STANDALONE")
                .append("sourceGenerationId", "g1")
                .append("ownerJobId", "job-g1").append("total", 0L)
                .append("fileTextBytesLimit", 16L * 1024 * 1024).append("snapshotTextBytesLimit", 64L * 1024 * 1024)
                .append("contentCoverage", new Document("entryCount", 0L).append("textEntries", 0L).append("textBytes", 0L))
                .append("revision", REVISION).append("policyFingerprint", sourcePolicy.fingerprint())
                .append("contentDigest", SOURCE_DIGEST).append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide))));
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId).append("sourceRevision", REVISION)
                .append("generationId", "g1").append("identityDigest", DIGEST).append("writeState", "SEALED_VALID")
                .append("schemaVersion", IndexSchemaContract.SCHEMA_VERSION)
                .append("sourceSnapshot", bson(template, new SourceSnapshotMembership(
                        new GitSnapshotId(SOURCE_SNAPSHOT), new RepositoryRevision(REVISION), sourcePolicy.fingerprint(), SOURCE_DIGEST)))
                .append("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(sourcePolicy))).append("projectGuide", new Document(SourceEvidenceDocumentCodec.encodeGuide(guide)))
                .append("coverage", bson(template, new SourceCoverage(0, 0, 0, 0)))
                .append("structure", bson(template, new SourceStructure(sourcePolicy.includedRoots(), Map.of(), Map.of())))
                .append("projectionVersions", IndexSchemaContract.requiredProjectionVersions().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new Document("name", entry.getKey()).append("version", entry.getValue()))
                        .toList()));
    }

    private static Document bson(MongoTemplate template, Object value) {
        return (Document) template.getConverter().convertToMongoType(value);
    }

    static CodeFactIdentity methodIdentity(String packageName, String className, String methodName, String sourcePath) {
        SourceTypeIdentity type = new SourceTypeIdentity(new com.java.semantic.model.codefact.JavaTypeIdentity(packageName, className), sourcePath);
        return new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.METHOD,
                new MethodTarget(type, methodName, List.of("example.events.VideoReady")));
    }

    static SymbolDocument seedMethod(MongoTemplate template, CodeFactIdentity identity, List<com.java.semantic.model.codefact.AnnotationFact> annotations) {
        MethodTarget method = (MethodTarget) identity.canonicalIdentity();
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), fact, CodeFactKind.METHOD,
                method.fullyQualifiedClassName(), method.methodName(), method.canonicalForm(), new DeclaredType("void"), java.util.Set.of(), annotations,
                artifactFor(template, method.sourceFile()), new SourceRange(method.sourceFile(),
                new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 8))), Optional.empty());
        Document stored = new Document();
        template.getConverter().write(SymbolPersistence.from(symbol), stored);
        CodeFactScope scope = CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value());
        stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", method.sourceFile()); stored.put("scopePackage", scope.packageName());
        stored.put("scopeClass", scope.className()); stored.put("scopeMethod", scope.methodName().orElse(""));
        stored.put("scopeParameters", scope.parameterTypes()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
        return symbol;
    }

    static com.java.semantic.model.index.RelationDocument seedRelation(
            MongoTemplate template,
            CodeFactIdentity from,
            com.java.semantic.model.codefact.RelationKind kind,
            com.java.semantic.model.codefact.RelationTarget target,
            SourceRange range) {
        com.java.semantic.model.codefact.RelationIdentity relationIdentity = new com.java.semantic.model.codefact.RelationIdentity(from, kind, target, range);
        CodeFactIdentity relationFactIdentity = new CodeFactIdentity(from.repositoryId(), from.repositoryRevision(),
                CodeFactKind.TYPE_USAGE, relationIdentity);
        CodeFact fact = new CodeFact(CodeFactId.from(relationFactIdentity), relationFactIdentity);
        com.java.semantic.model.index.RelationDocument relation = new com.java.semantic.model.index.RelationDocument(
                from.repositoryId(), new com.java.semantic.model.index.GenerationId("g1"), fact, kind, from, target,
                artifactFor(template, range.sourceFile()), range);
        Document stored = new Document();
        template.getConverter().write(relation, stored);
        stored.put("repoId", "orders");
        stored.put("generationId", "g1");
        stored.put("relationId", fact.id().value());
        stored.put("canonical", fact.identity().canonicalForm());
        stored.put("from", from.canonicalForm());
        stored.put("target", target.canonicalForm());
        stored.put("sourcePath", range.sourceFile());
        template.getCollection("relations").insertOne(stored);
        return relation;
    }

    static SourceArtifactDocument seedSource(MongoTemplate template, String sourcePath, String content) {
        SourceArtifactDocument artifact = SourceArtifactDocument.create(content);
        template.getCollection("source_artifacts").insertOne(new Document("sourceArtifactId", artifact.id().value()).append("contentHash", artifact.contentHash()).append("utf8Content", content));
        GenerationFileDocument file = new GenerationFileDocument(new RepositoryId("orders"), new GenerationId("g1"), sourcePath, artifact.id(), artifact.contentHash(), "",
                new SourceIndexScope(false, java.util.List.of(), java.util.List.of(), java.util.List.of()));
        Document stored = new Document(); template.getConverter().write(file, stored); stored.put("repoId", "orders"); stored.put("generationId", "g1");
        stored.put("sourcePath", sourcePath); stored.put("extractionIssueCode", ""); stored.put("scopeUsable", false);
        stored.put("scopePackages", List.of()); stored.put("scopeClassKeys", List.of()); stored.put("scopeMethodKeys", List.of());
        template.getCollection("generation_files").insertOne(stored);
        seedCodeMembership(template, sourcePath, artifact.contentHash(), content);
        for (String collection : List.of("symbols", "relations", "entry_points")) {
            template.getCollection(collection).updateMany(new Document("repoId", "orders").append("generationId", "g1").append("sourcePath", sourcePath),
                    new Document("$set", new Document("sourceArtifactId", new Document("value", artifact.id().value()))));
        }
        return artifact;
    }

    private static void seedCodeMembership(MongoTemplate template, String path, String checksum, String content) {
        Document manifest = template.getCollection("generation_manifests").find(new Document("repoId", "orders")
                .append("generationId", "g1")).first();
        SourceEvidencePolicy existing = SourceEvidenceDocumentCodec.decodePolicy(
                manifest.get("sourcePolicy", Document.class));
        Set<String> selected = new java.util.HashSet<>(existing.selectedCodePaths());
        selected.add(path);
        SourceEvidencePolicy policy = new SourceEvidencePolicy(SourceEvidencePolicy.VERSION,
                existing.includedRoots(), selected, existing.projectGuidePath());
        template.getCollection("generation_manifests").updateOne(new Document("repoId", "orders").append("generationId", "g1"),
                new Document("$set", new Document("sourcePolicy", new Document(SourceEvidenceDocumentCodec.encodePolicy(policy)))
                        .append("sourceSnapshot", bson(template, new SourceSnapshotMembership(
                                new GitSnapshotId(SOURCE_SNAPSHOT), new RepositoryRevision(REVISION), policy.fingerprint(), SOURCE_DIGEST)))
                        .append("coverage", bson(template, new SourceCoverage(selected.size(), 0, 0, 0)))));
        template.getCollection("git_evidence_manifests").updateOne(new Document("repoId", "orders").append("evidenceId", SOURCE_SNAPSHOT),
                new Document("$set", new Document("policyFingerprint", policy.fingerprint())));
        template.getCollection("git_snapshot_files").updateMany(new Document("repoId", "orders").append("snapshotId", SOURCE_SNAPSHOT),
                new Document("$set", new Document("policyFingerprint", policy.fingerprint())));
        byte[] rawPath = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String pathKey = java.util.HexFormat.of().formatHex(rawPath);
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long total = template.getCollection("git_snapshot_files").countDocuments(new Document("snapshotId", SOURCE_SNAPSHOT));
        template.getCollection("git_snapshot_files").insertOne(new Document("repoId", "orders")
                .append("snapshotId", SOURCE_SNAPSHOT).append("path", path).append("pathKey", pathKey)
                .append("rawPath", new org.bson.types.Binary(rawPath)).append("ordinal", total)
                .append("blobId", "b".repeat(40)).append("byteLength", (long) bytes.length)
                .append("chunkCount", 0L).append("contentKind", "CODE")
                .append("mode", "100644").append("contentStatus", "TEXT").append("policyFingerprint", policy.fingerprint()).append("checksum", checksum));
        int offset = 0;
        long ordinal = 0;
        int line = 1;
        int column = 1;
        while (offset < bytes.length) {
            int end = Math.min(bytes.length, offset + 65536);
            while (end < bytes.length && (bytes[end] & 0xc0) == 0x80) end--;
            byte[] chunk = java.util.Arrays.copyOfRange(bytes, offset, end);
            template.getCollection("git_snapshot_chunks").insertOne(new Document("repoId", "orders").append("snapshotId", SOURCE_SNAPSHOT)
                    .append("pathKey", pathKey).append("ordinal", ordinal++).append("byteOffset", (long) offset)
                    .append("line", line).append("column", column).append("bytes", new org.bson.types.Binary(chunk)));
            String text = new String(chunk, java.nio.charset.StandardCharsets.UTF_8);
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) == '\n') { line++; column = 1; } else column++;
            }
            offset = end;
        }
        template.getCollection("git_snapshot_files").updateOne(new Document("repoId", "orders").append("snapshotId", SOURCE_SNAPSHOT).append("path", path),
                new Document("$set", new Document("chunkCount", ordinal)));
        Document header = template.getCollection("git_evidence_manifests").find(new Document("evidenceId", SOURCE_SNAPSHOT)).first();
        long textBytes = ((Number) header.get("contentCoverage", Document.class).get("textBytes")).longValue() + bytes.length;
        template.getCollection("git_evidence_manifests").updateOne(new Document("evidenceId", SOURCE_SNAPSHOT),
                new Document("$set", new Document("total", total + 1).append("contentCoverage",
                        new Document("entryCount", total + 1).append("textEntries", total + 1).append("textBytes", textBytes))));
    }

    private static com.java.semantic.model.index.SourceArtifactId artifactFor(MongoTemplate template, String path) {
        Document row = template.getCollection("generation_files").find(new Document("repoId", "orders").append("sourcePath", path)).first();
        return new com.java.semantic.model.index.SourceArtifactId(java.util.Objects.isNull(row) ? "a".repeat(64) : row.getString("contentHash"));
    }

    static void seedCoverageSource(MongoTemplate template, String sourcePath, String issueCode, SourceIndexScope scope) {
        SourceArtifactDocument artifact = SourceArtifactDocument.create("coverage " + sourcePath);
        GenerationFileDocument file = new GenerationFileDocument(new RepositoryId("orders"), new GenerationId("g1"), sourcePath,
                artifact.id(), artifact.contentHash(), issueCode, scope);
        Document stored = new Document();
        template.getConverter().write(file, stored);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("sourcePath", sourcePath);
        stored.put("extractionIssueCode", issueCode); stored.put("scopeUsable", scope.usableScopes());
        stored.put("scopePackages", scope.packages()); stored.put("scopeClassKeys", scope.classKeys());
        stored.put("scopeMethodKeys", scope.methodKeys());
        template.getCollection("generation_files").insertOne(stored);
    }

    static void seedType(MongoTemplate template, SourceTypeIdentity type, com.java.semantic.model.index.SourceArtifactId artifactId) {
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.TYPE, type);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), fact, CodeFactKind.TYPE,
                type.fullyQualifiedName(), type.javaType().className(), type.canonicalForm(), new DeclaredType(type.fullyQualifiedName()), java.util.Set.of(), List.of(), artifactId,
                new SourceRange(type.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))), Optional.empty());
        Document stored = new Document(); template.getConverter().write(SymbolPersistence.from(symbol), stored); CodeFactScope scope = CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value()); stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", type.sourceFile());
        stored.put("scopePackage", scope.packageName()); stored.put("scopeClass", scope.className()); stored.put("scopeMethod", ""); stored.put("scopeParameters", List.of()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
    }

    static CodeFactIdentity seedMapper(MongoTemplate template, String path, com.java.semantic.model.index.SourceArtifactId artifactId,
                                       MapperStatementKind operation) {
        com.java.semantic.model.codefact.MapperStatementIdentity mapper = new com.java.semantic.model.codefact.MapperStatementIdentity("example.mapper.VideoMapper", "find", path);
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.MAPPER_STATEMENT, mapper);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), fact, CodeFactKind.MAPPER_STATEMENT,
                mapper.namespace(), mapper.statementId(), mapper.canonicalForm(), new DeclaredType("mapper-statement"), java.util.Set.of(), List.of(), artifactId,
                new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 8))), Optional.of(operation));
        Document stored = new Document(); template.getConverter().write(SymbolPersistence.from(symbol), stored); CodeFactScope scope = CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value()); stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", path);
        stored.put("scopePackage", scope.packageName()); stored.put("scopeClass", scope.className()); stored.put("scopeMethod", scope.methodName().orElse("")); stored.put("scopeParameters", scope.parameterTypes()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
        return identity;
    }
}
