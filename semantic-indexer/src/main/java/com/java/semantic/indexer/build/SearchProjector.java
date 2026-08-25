package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.CodeFact;
import com.java.semantic.model.codefact.CodeFactTokenizer;
import com.java.semantic.model.codefact.CanonicalIdentity;
import com.java.semantic.model.codefact.EntryPointIdentity;
import com.java.semantic.model.codefact.MapperStatementIdentity;
import com.java.semantic.model.codefact.MemberIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.index.EntryPointDocument;
import com.java.semantic.model.index.ProjectionName;
import com.java.semantic.model.index.RelationDocument;
import com.java.semantic.model.index.SearchDocument;
import com.java.semantic.model.index.SymbolDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Builds search records exclusively from canonical identities and stored code-derived fields. */
public final class SearchProjector {

    public List<SearchDocument> project(List<SymbolDocument> symbols, List<RelationDocument> relations,
                                        List<EntryPointDocument> entryPoints) {
        List<SearchDocument> documents = new ArrayList<>();
        symbols.forEach(document -> documents.add(search(document.repositoryId(), document.generationId(), document.fact(),
                ProjectionName.SYMBOLS, packageName(document.fact()))));
        relations.forEach(document -> documents.add(search(document.repositoryId(), document.generationId(), document.fact(),
                ProjectionName.RELATIONS, packageName(document.fact()))));
        entryPoints.forEach(document -> documents.add(search(document.repositoryId(), document.generationId(), document.fact(),
                ProjectionName.ENTRY_POINTS, packageName(document.fact()))));
        return documents.stream().sorted(java.util.Comparator.comparing(document -> document.factId().value())).toList();
    }

    private static SearchDocument search(com.java.semantic.model.repository.RepositoryId repositoryId,
                                         com.java.semantic.model.index.GenerationId generationId, CodeFact fact,
                                         ProjectionName projection, Optional<String> packageName) {
        return new SearchDocument(repositoryId, generationId, fact.id(), fact.identity().kind(), tokens(fact.identity().canonicalForm()),
                packageName, projection, fact.identity());
    }

    private static List<String> tokens(String canonicalForm) {
        return CodeFactTokenizer.tokenize(canonicalForm);
    }

    private static Optional<String> packageName(CodeFact fact) {
        return packageName(fact.identity().canonicalIdentity());
    }

    private static Optional<String> packageName(CanonicalIdentity identity) {
        if (identity instanceof SourceTypeIdentity type) {
            return optionalPackage(type.javaType().packageName());
        }
        if (identity instanceof MethodTarget method) {
            return optionalPackage(method.packageName());
        }
        if (identity instanceof MemberIdentity member) {
            return optionalPackage(member.owner().javaType().packageName());
        }
        if (identity instanceof MapperStatementIdentity statement) {
            return packageOfQualifiedName(statement.namespace());
        }
        if (identity instanceof EntryPointIdentity entryPoint) {
            return optionalPackage(entryPoint.method().packageName());
        }
        if (identity instanceof RelationIdentity relation) {
            return packageName(relation.from().canonicalIdentity());
        }
        return Optional.empty();
    }

    private static Optional<String> packageOfQualifiedName(String qualifiedName) {
        int separator = qualifiedName.lastIndexOf('.');
        return separator > 0 ? Optional.of(qualifiedName.substring(0, separator)) : Optional.empty();
    }

    private static Optional<String> optionalPackage(String packageName) {
        return packageName.isBlank() ? Optional.empty() : Optional.of(packageName);
    }
}
