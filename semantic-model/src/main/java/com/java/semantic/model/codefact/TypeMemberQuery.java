package com.java.semantic.model.codefact;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;

import java.util.Objects;
import java.util.Set;

/** Current-generation declaration member query; only persisted member symbols are eligible. */
public record TypeMemberQuery(RepositoryId repositoryId, RepositoryRevision revision, SourceTypeIdentity sourceType,
                              Set<CodeFactKind> kinds, int offset, int limit) {
    public static final Set<CodeFactKind> MEMBER_KINDS = Set.of(CodeFactKind.METHOD, CodeFactKind.FIELD,
            CodeFactKind.ENUM_CONSTANT, CodeFactKind.RECORD_COMPONENT);

    public TypeMemberQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repository id is required");
        revision = Objects.requireNonNull(revision, "revision is required");
        sourceType = Objects.requireNonNull(sourceType, "source type is required");
        kinds = Set.copyOf(Objects.requireNonNull(kinds, "member kinds are required"));
        if (kinds.isEmpty() || !MEMBER_KINDS.containsAll(kinds)) {
            throw new IllegalArgumentException("member kinds must be supported declarations");
        }
        if (kinds.contains(CodeFactKind.ENUM_CONSTANT) && kinds.size() > 1) {
            throw new IllegalArgumentException("enum constants must be queried separately from paged members");
        }
        if (offset < 0 || limit < 1 || limit > 100) { throw new IllegalArgumentException("invalid page"); }
    }
}
