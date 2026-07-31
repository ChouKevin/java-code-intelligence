package com.java.semantic.syntax.application;

import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.syntax.domain.ClassMetadata.TypeKind;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;

import java.util.List;
import java.util.Objects;

/** 重複攜帶型別屬性與固定版本合併成員頁的探索結果 */
public record TypeMemberResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        String sourceFile,
        String fullyQualifiedName,
        TypeKind typeKind,
        List<String> annotations,
        List<String> implementedTypes,
        List<String> extendedTypes,
        List<TypeMember> members,
        ConceptPage page,
        List<SourceExtractionOutcome> coverage,
        List<DiscoveryFollowUp> availableFollowUps) {

    public TypeMemberResult {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        analyzedRevision = Objects.requireNonNull(analyzedRevision, "analyzedRevision is required");
        sourceFile = Objects.requireNonNull(sourceFile, "sourceFile is required");
        fullyQualifiedName = Objects.requireNonNull(fullyQualifiedName, "fullyQualifiedName is required");
        typeKind = Objects.requireNonNull(typeKind, "typeKind is required");
        annotations = List.copyOf(Objects.requireNonNull(annotations, "annotations are required"));
        implementedTypes = List.copyOf(Objects.requireNonNull(
                implementedTypes, "implementedTypes are required"));
        extendedTypes = List.copyOf(Objects.requireNonNull(extendedTypes, "extendedTypes are required"));
        members = List.copyOf(Objects.requireNonNull(members, "members are required"));
        page = Objects.requireNonNull(page, "page is required");
        coverage = List.copyOf(Objects.requireNonNull(coverage, "coverage is required"));
        availableFollowUps = List.copyOf(Objects.requireNonNull(
                availableFollowUps, "availableFollowUps are required"));
        if (members.size() != page.returnedCount()) {
            throw new IllegalArgumentException("page returnedCount must match members");
        }
        if (page.hasMore()) {
            if (availableFollowUps.size() != 1
                    || availableFollowUps.getFirst().operation() != DiscoveryFollowUp.Operation.GET_NEXT_PAGE) {
                throw new IllegalArgumentException("non-final page requires one GET_NEXT_PAGE follow-up");
            }
        } else if (availableFollowUps.size() > 0) {
            throw new IllegalArgumentException("final page must not contain a page follow-up");
        }
    }
}
