package com.java.semantic.indexer.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.codefact.ExternalTarget;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SemanticRelationProjectorTest {

    @Test
    void should_keep_a_blank_head_invocation_external_with_a_nonblank_unresolved_name() {
        SyntaxPosition position = new SyntaxPosition(0, 0);
        SyntaxInvocation invocation = new SyntaxInvocation(SyntaxInvocation.InvocationKind.METHOD,
                new SyntaxRange(position, position), "()", "", "", "", Optional.empty(), position, List.of());

        ExternalTarget.UnresolvedCall target = SemanticRelationProjector.unresolvedCall(invocation);

        assertThat(target.expression()).isEqualTo("()");
        assertThat(target.methodName()).isEqualTo("unknownMethod");
        assertThat(target.arity()).isZero();
    }
}
