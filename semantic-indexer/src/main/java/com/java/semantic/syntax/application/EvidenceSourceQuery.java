package com.java.semantic.syntax.application;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.syntax.domain.MapperFragmentIdentity;
import com.java.semantic.syntax.domain.MapperStatementIdentity;

import java.util.Objects;

/** 固定 revision 以既有 typed evidence identity 讀取原始證據 */
public record EvidenceSourceQuery(RepositoryId repositoryId, RepositoryRevision expectedRevision, EvidenceIdentity identity) {

    public EvidenceSourceQuery {
        repositoryId = Objects.requireNonNull(repositoryId, "repositoryId is required");
        expectedRevision = Objects.requireNonNull(expectedRevision, "expectedRevision is required");
        identity = Objects.requireNonNull(identity, "identity is required");
    }

    /** 僅允許三種已抽取的 evidence identity */
    public sealed interface EvidenceIdentity permits AnnotationSql, MapperStatement, MapperFragment {
    }

    public record AnnotationSql(MapperStatementIdentity identity) implements EvidenceIdentity {
        public AnnotationSql {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    public record MapperStatement(MapperStatementIdentity identity) implements EvidenceIdentity {
        public MapperStatement {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }

    public record MapperFragment(MapperFragmentIdentity identity) implements EvidenceIdentity {
        public MapperFragment {
            identity = Objects.requireNonNull(identity, "identity is required");
        }
    }
}
