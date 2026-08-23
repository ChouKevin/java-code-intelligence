package com.java.semantic.syntax.application;

import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 以 repository-relative sourceFile 與完整型別名稱綁定固定版本的成員查詢 */
public record TypeMemberQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        SourceTypeIdentity sourceType,
        Set<TypeMemberKind> memberKinds,
        Optional<String> namePrefix,
        int offset,
        int limit) {

    private static final int MAXIMUM_LIMIT = 100;

    public TypeMemberQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        sourceType = Objects.requireNonNull(sourceType, "sourceType is required");
        memberKinds = Set.copyOf(Objects.requireNonNull(memberKinds, "memberKinds are required"));
        namePrefix = normalizedPrefix(namePrefix);
        if (memberKinds.size() < 1) {
            throw new IllegalArgumentException("memberKinds are required");
        }
        if (memberKinds.size() > TypeMemberKind.values().length) {
            throw new IllegalArgumentException("memberKinds exceed supported values");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAXIMUM_LIMIT);
        }
    }

    /** 保留固定版本與所有篩選條件建立下一頁查詢 */
    public TypeMemberQuery nextPage(int nextOffset) {
        return new TypeMemberQuery(
                repositoryId,
                expectedRevision,
                sourceType,
                memberKinds,
                namePrefix,
                nextOffset,
                limit);
    }

    private static Optional<String> normalizedPrefix(Optional<String> value) {
        Optional<String> prefix = Objects.requireNonNull(value, "namePrefix is required");
        return prefix.map(candidate -> {
            String normalized = candidate.strip();
            if (normalized.isBlank() || normalized.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("namePrefix must be nonblank and control-character free");
            }
            return normalized;
        });
    }
}
