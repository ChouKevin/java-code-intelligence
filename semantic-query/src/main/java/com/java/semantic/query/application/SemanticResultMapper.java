package com.java.semantic.query.application;

import com.java.semantic.model.codefact.CanonicalIdentity;
import com.java.semantic.model.codefact.CodeFactDetails;
import com.java.semantic.model.codefact.CodeFactDisplay;
import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.RelationIdentity;
import com.java.semantic.model.codefact.RelationTarget;
import java.util.Optional;

/** Compact semantic navigation never hydrates source content. */
final class SemanticResultMapper {
    private SemanticResultMapper() { }

    static SemanticQueryContract.CompactFact compact(CodeFactDetails details) {
        CanonicalIdentity identity = details.fact().identity().canonicalIdentity();
        String signature = CodeFactDisplay.signature(identity);
        boolean unresolved = identity instanceof RelationIdentity relation
                && relation.target() instanceof RelationTarget.External external
                && external.target() instanceof ExternalTarget.UnresolvedCall;
        return new SemanticQueryContract.CompactFact(details.fact().id().value(), details.fact().identity().kind(),
                CodeFactDisplay.displayName(identity),
                signature.isEmpty() ? Optional.empty() : Optional.of(signature), details.location().sourceFile(),
                details.location().range(), unresolved ? Optional.empty() : Optional.of(details.fact().identity().canonicalForm()),
                details.mapperStatementKind());
    }
}
