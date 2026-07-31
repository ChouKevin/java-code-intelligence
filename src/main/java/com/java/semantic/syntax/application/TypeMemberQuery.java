package com.java.semantic.syntax.application;

import com.java.semantic.identity.RepositoryRelativeSource;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;

import javax.lang.model.SourceVersion;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 以 repository-relative sourceFile 與完整型別名稱綁定固定版本的成員查詢 */
public record TypeMemberQuery(
        RepositoryId repositoryId,
        RepositoryRevision expectedRevision,
        String sourceFile,
        String fullyQualifiedName,
        Set<TypeMemberKind> memberKinds,
        Optional<String> namePrefix,
        int offset,
        int limit) {

    private static final int MAXIMUM_LIMIT = 100;

    public TypeMemberQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        sourceFile = RepositoryRelativeSource.requireValid(sourceFile);
        fullyQualifiedName = requiredTypeName(fullyQualifiedName);
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
                sourceFile,
                fullyQualifiedName,
                memberKinds,
                namePrefix,
                nextOffset,
                limit);
    }

    private static String requiredTypeName(String value) {
        String typeName = Objects.requireNonNull(value, "fullyQualifiedName is required");
        if (typeName.isBlank() || !SourceVersion.isName(typeName)
                || typeName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("fullyQualifiedName must be a canonical Java type name");
        }
        return typeName;
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
