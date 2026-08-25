package com.java.semantic.query.application;

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
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.index.SourceArtifactDocument;
import com.java.semantic.model.index.GenerationFileDocument;
import com.java.semantic.model.index.SourceIndexScope;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.query.config.ConfiguredReadPolicy;
import com.java.semantic.query.config.ReadPolicyProperties;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Duration;
import java.util.List;

abstract class PublishedMongoITSupport {
    static final String REVISION = "1".repeat(40);
    static final String DIGEST = "2".repeat(64);

    static ConfiguredReadPolicy policy() {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(), List.of(), List.of()));
    }

    static ConfiguredReadPolicy policy(ReadPolicyProperties.PackageRule rule) {
        return new ConfiguredReadPolicy(new ReadPolicyProperties(List.of(), List.of(rule), List.of(), List.of()));
    }

    static CurrentGenerationSelector selector(MongoTemplate template, ConfiguredReadPolicy policy) {
        return new CurrentGenerationSelector(template, policy, Duration.ofSeconds(2));
    }

    static void seedCurrent(MongoTemplate template, String repositoryId) {
        template.getCollection("repositories").replaceOne(new Document("repoId", repositoryId),
                new Document("repoId", repositoryId).append("revision", REVISION).append("generationId", "g1")
                        .append("manifestDigest", DIGEST).append("committedJobId", "job-g1").append("publishedAt", new java.util.Date()),
                new ReplaceOptions().upsert(true));
        template.getCollection("generation_manifests").insertOne(new Document("repoId", repositoryId).append("sourceRevision", REVISION)
                .append("generationId", "g1").append("identityDigest", DIGEST).append("writeState", "SEALED_VALID").append("schemaVersion", 1)
                .append("projectionVersions", List.of(new Document("name", "SOURCES").append("version", 2),
                        new Document("name", "SYMBOLS").append("version", 2), new Document("name", "RELATIONS").append("version", 2),
                        new Document("name", "ENTRY_POINTS").append("version", 2), new Document("name", "SEARCH").append("version", 2))));
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
                new com.java.semantic.model.index.SourceArtifactId("a".repeat(64)), new SourceRange(method.sourceFile(),
                new SyntaxRange(new SyntaxPosition(1, 0), new SyntaxPosition(1, 8))));
        Document stored = new Document();
        template.getConverter().write(symbol, stored);
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
                new com.java.semantic.model.index.SourceArtifactId("a".repeat(64)), range);
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
        return artifact;
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
                new SourceRange(type.sourceFile(), new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 1))));
        Document stored = new Document(); template.getConverter().write(symbol, stored); CodeFactScope scope = CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value()); stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", type.sourceFile());
        stored.put("scopePackage", scope.packageName()); stored.put("scopeClass", scope.className()); stored.put("scopeMethod", ""); stored.put("scopeParameters", List.of()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
    }

    static CodeFactIdentity seedMapper(MongoTemplate template, String path, com.java.semantic.model.index.SourceArtifactId artifactId) {
        com.java.semantic.model.codefact.MapperStatementIdentity mapper = new com.java.semantic.model.codefact.MapperStatementIdentity("example.mapper.VideoMapper", "find", path);
        CodeFactIdentity identity = new CodeFactIdentity(new RepositoryId("orders"), new RepositoryRevision(REVISION), CodeFactKind.MAPPER_STATEMENT, mapper);
        CodeFact fact = new CodeFact(CodeFactId.from(identity), identity);
        SymbolDocument symbol = new SymbolDocument(identity.repositoryId(), new GenerationId("g1"), fact, CodeFactKind.MAPPER_STATEMENT,
                mapper.namespace(), mapper.statementId(), mapper.canonicalForm(), new DeclaredType("mapper-statement"), java.util.Set.of(), List.of(), artifactId,
                new SourceRange(path, new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(0, 8))));
        Document stored = new Document(); template.getConverter().write(symbol, stored); CodeFactScope scope = CodeFactScope.from(identity);
        stored.put("repoId", "orders"); stored.put("generationId", "g1"); stored.put("symbolId", fact.id().value()); stored.put("canonical", identity.canonicalForm()); stored.put("sourcePath", path);
        stored.put("scopePackage", scope.packageName()); stored.put("scopeClass", scope.className()); stored.put("scopeMethod", scope.methodName().orElse("")); stored.put("scopeParameters", scope.parameterTypes()); stored.put("scopePath", scope.sourcePath().orElse(""));
        template.getCollection("symbols").insertOne(stored);
        return identity;
    }
}
