package com.java.semantic.indexer.build;

import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticCallResolutionStatus;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SyntaxInvocation;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JdtLsSemanticCallTargetResolverTest {

    @Test
    void should_leave_calls_unresolved_when_the_syntax_caller_has_no_canonical_target() {
        JavaSemanticService semanticService = mock(JavaSemanticService.class);
        SourceMethodMetadata caller = mock(SourceMethodMetadata.class);
        when(caller.analysisTarget()).thenReturn(MethodTargetResolution.unresolved("METHOD_PARAMETER_BINDING_UNRESOLVED"));
        JdtLsSemanticCallTargetResolver resolver = new JdtLsSemanticCallTargetResolver(semanticService);

        assertThat(resolver.resolve(snapshot(), caller, invocation(), false).status())
                .isEqualTo(SemanticCallResolutionStatus.UNRESOLVED);
        assertThat(resolver.snapshot().unresolved()).isEqualTo(1);
        verifyNoInteractions(semanticService);
    }

    private static RepositorySnapshot snapshot() {
        return new RepositorySnapshot(RepositoryId.of("actual-review"), Path.of("/repository"),
                RepositoryRevision.ofSha("a".repeat(40)));
    }

    private static SyntaxInvocation invocation() {
        SyntaxPosition position = new SyntaxPosition(0, 0);
        return new SyntaxInvocation(
                SyntaxInvocation.InvocationKind.METHOD,
                new SyntaxRange(position, position),
                "call()",
                "",
                "",
                "",
                Optional.empty(),
                position,
                List.of());
    }
}
