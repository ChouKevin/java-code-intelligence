package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.AnnotationFact;
import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactId;
import com.java.semantic.model.codefact.CodeFactIdentity;
import com.java.semantic.model.codefact.CodeFactKind;
import com.java.semantic.model.codefact.DeclaredType;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.GenerationId;
import com.java.semantic.model.index.SymbolDocument;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.domain.AnnotationEvidence;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import java.util.ArrayList;
import java.util.List;

/** Projects only syntactically owned declarations; no inferred business vocabulary is admitted. */
public final class SyntaxSymbolProjector {

    public List<SymbolDocument> project(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                        RepositorySyntax syntax, String sourcePath, com.java.semantic.model.index.SourceArtifactDocument artifact) {
        List<SymbolDocument> documents = new ArrayList<>();
        for (SourceTypeMetadata metadata : syntax.sourceTypes()) {
            SourceTypeIdentity type = metadata.declaration().identity();
            if (!sourcePath.equals(type.sourceFile())) {
                continue;
            }
            documents.add(typeDocument(repositoryId, revision, generationId, metadata, artifact));
            for (SourceMethodMetadata method : metadata.members().methods()) {
                documents.add(methodDocument(repositoryId, revision, generationId, type, method, artifact));
            }
            for (com.java.semantic.syntax.domain.SourceFieldMetadata field : metadata.members().fields()) {
                if (!sourcePath.equals(field.declarationLocation().sourceFile())) {
                    continue;
                }
                documents.add(memberDocument(repositoryId, revision, generationId, type, field, artifact));
            }
        }
        return documents.stream().sorted(java.util.Comparator.comparing(document -> document.fact().id().value())).toList();
    }

    private SymbolDocument memberDocument(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                          SourceTypeIdentity type, com.java.semantic.syntax.domain.SourceFieldMetadata member,
                                          com.java.semantic.model.index.SourceArtifactDocument artifact) {
        MemberIdentity identity = new MemberIdentity(type, member.name());
        CodeFact fact = fact(repositoryId, revision, member.declarationKind(), identity);
        return new SymbolDocument(repositoryId, generationId, fact, member.declarationKind(), type.fullyQualifiedName(),
                member.name(), identity.canonicalForm(), new DeclaredType(member.type()), java.util.Set.of(),
                annotations(member.annotationEvidence()), artifact.id(), member.declarationLocation());
    }

    public List<SymbolDocument> projectMapperStatements(RepositoryId repositoryId, RepositoryRevision revision,
                                                        GenerationId generationId, RepositorySyntax syntax, String sourcePath,
                                                        com.java.semantic.model.index.SourceArtifactDocument artifact) {
        return syntax.mapperEvidenceIndex().stream().flatMap(index -> index.statements().stream())
                .filter(statement -> sourcePath.equals(statement.identity().resourcePath()))
                .map(statement -> mapperStatementDocument(repositoryId, revision, generationId, statement, artifact))
                .sorted(java.util.Comparator.comparing(document -> document.fact().id().value()))
                .toList();
    }

    private SymbolDocument typeDocument(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                        SourceTypeMetadata metadata, com.java.semantic.model.index.SourceArtifactDocument artifact) {
        SourceTypeIdentity type = metadata.declaration().identity();
        CodeFact fact = fact(repositoryId, revision, CodeFactKind.TYPE, type);
        List<AnnotationFact> annotations = annotations(metadata.frameworkFacts().annotations());
        return new SymbolDocument(repositoryId, generationId, fact, CodeFactKind.TYPE, type.fullyQualifiedName(),
                type.javaType().className(), type.fullyQualifiedName(), new DeclaredType(type.fullyQualifiedName()),
                metadata.declaration().abstractType() ? java.util.Set.of("abstract") : java.util.Set.of(), annotations,
                artifact.id(), metadata.declaration().declarationLocation());
    }

    private SymbolDocument methodDocument(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                          SourceTypeIdentity type, SourceMethodMetadata method,
                                          com.java.semantic.model.index.SourceArtifactDocument artifact) {
        MethodTarget target = method.analysisTarget().target().orElseGet(
                () -> new MethodTarget(type, method.name(), method.paramTypes()));
        CodeFact fact = fact(repositoryId, revision, CodeFactKind.METHOD, target);
        String signature = target.fullyQualifiedClassName() + "#" + target.methodName() + "(" + String.join(",", target.parameterTypes()) + ")";
        String returnType = method.returnType().flatMap(reference -> reference.resolvedTypeName()).orElse("void");
        return new SymbolDocument(repositoryId, generationId, fact, CodeFactKind.METHOD, type.fullyQualifiedName(),
                method.name(), signature, new DeclaredType(returnType), java.util.Set.of(), annotations(method.annotationEvidence()),
                artifact.id(), method.declarationLocation());
    }

    private SymbolDocument mapperStatementDocument(RepositoryId repositoryId, RepositoryRevision revision, GenerationId generationId,
                                                   com.java.semantic.syntax.domain.MapperStatementEvidence statement,
                                                   com.java.semantic.model.index.SourceArtifactDocument artifact) {
        MapperStatementIdentity identity = new MapperStatementIdentity(statement.identity().statementKey().namespace(),
                statement.identity().statementKey().statementId(), statement.identity().resourcePath());
        CodeFact fact = fact(repositoryId, revision, CodeFactKind.MAPPER_STATEMENT, identity);
        return new SymbolDocument(repositoryId, generationId, fact, CodeFactKind.MAPPER_STATEMENT,
                statement.identity().statementKey().namespace(), statement.identity().statementKey().statementId(),
                identity.canonicalForm(), new DeclaredType("mapper-statement"), java.util.Set.of(), List.of(), artifact.id(),
                statement.location());
    }

    static CodeFact fact(RepositoryId repositoryId, RepositoryRevision revision, CodeFactKind kind,
                         com.java.semantic.model.codefact.CanonicalIdentity identity) {
        CodeFactIdentity factIdentity = new CodeFactIdentity(repositoryId, revision, kind, identity);
        return new CodeFact(CodeFactId.from(factIdentity), factIdentity);
    }

    private static List<AnnotationFact> annotations(List<AnnotationEvidence> annotations) {
        return annotations.stream().map(annotation -> new AnnotationFact(annotation.resolvedType()
                .map(com.java.semantic.model.codefact.JavaTypeIdentity::fullyQualifiedName).orElse(annotation.writtenName()))).toList();
    }
}
