package com.java.semantic.syntax.application;

import com.java.semantic.syntax.application.concept.ConceptPage;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;

import java.util.List;
import java.util.Objects;

/** 綁定來源型別 identity 與固定版本的成員探索結果 */
public record TypeMemberResult(
        RepositoryId repositoryId,
        RepositoryRevision analyzedRevision,
        SourceTypeIdentity sourceType,
        SourceTypeKind typeKind,
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
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
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
                    || availableFollowUps.getFirst().operation() != DiscoveryFollowUp.Operation.DISCOVER_TYPE_MEMBERS) {
                throw new IllegalArgumentException("non-final page requires one type member continuation follow-up");
            }
        } else if (availableFollowUps.size() > 0) {
            throw new IllegalArgumentException("final page must not contain a page follow-up");
        }
    }
}
