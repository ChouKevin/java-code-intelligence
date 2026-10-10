package com.java.semantic.indexer.index;

import com.java.semantic.model.index.RelationContract;
import java.util.Objects;

/** Known resolution uncertainty, distinct from unexpected worker failure. */
public final class UnresolvedRelationException extends RuntimeException {
    private final RelationContract.Reason reason;

    public UnresolvedRelationException(RelationContract.Reason reason) {
        super(Objects.requireNonNull(reason, "resolution reason").name());
        this.reason = reason;
    }

    public UnresolvedRelationException(RelationContract.Reason reason, Throwable cause) {
        super(Objects.requireNonNull(reason, "resolution reason").name(), cause);
        this.reason = reason;
    }

    public RelationContract.Reason reason() { return reason; }
}
