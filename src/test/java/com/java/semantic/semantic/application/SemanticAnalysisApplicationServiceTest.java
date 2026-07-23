package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.IncomingGraphProperties;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticAnalysisApplicationServiceTest {

    @TempDir
    private Path root;

    @Mock
    private RepositoryApplicationService repositoryApplicationService;
    @Mock
    private SyntaxExtractionService syntaxExtractionService;
    @Mock
    private JavaSemanticService semanticService;
    @Mock
    private SemanticCallGraphBuilder builder;
    @Mock
    private IncomingSemanticCallGraphBuilder incomingBuilder;

    @Test
    void should_hold_one_revision_locked_snapshot_for_the_exact_outgoing_sequence() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, revision);
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", java.util.List.of());
        RepositorySyntax syntax = RepositorySyntax.empty();
        SemanticMethod method = new SemanticMethod(
                "com.acme", "OrderService", "place", java.util.List.of(), "void",
                new SemanticLocation(root.resolve("OrderService.java").toUri().toString(),
                        new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0)),
                        new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(0, 1))));

        when(repositoryApplicationService.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, OutgoingGraphFragment> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(syntaxExtractionService.extract(root)).thenReturn(syntax);
        ExactMethodDeclarationResolver declarationResolver = mock(ExactMethodDeclarationResolver.class);
        when(declarationResolver.resolve(syntax, target)).thenReturn(new SemanticDeclarationAnchor(
                target, new SemanticPosition(0, 0)));
        when(semanticService.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class))).thenReturn(method);
        when(builder.build(eq(snapshot), eq(syntax), eq(target), eq(method), eq(2), eq(7))).thenReturn(null);

        SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                declarationResolver,
                semanticService,
                builder,
                incomingBuilder,
                new OutgoingGraphProperties(7),
                new IncomingGraphProperties(11));
        service.analyzeOutgoing(repositoryId, revision, target, 2);

        InOrder calls = inOrder(repositoryApplicationService, syntaxExtractionService, semanticService, builder);
        calls.verify(repositoryApplicationService).withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any());
        calls.verify(syntaxExtractionService).extract(root);
        calls.verify(semanticService).resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class));
        calls.verify(builder).build(snapshot, syntax, target, method, 2, 7);
    }

    @Test
    void should_hold_one_revision_locked_snapshot_for_the_exact_incoming_sequence() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.fixture();
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, revision);
        MethodTarget target = new MethodTarget("OrderService.java", "com.acme", "OrderService", "place", java.util.List.of());
        RepositorySyntax syntax = RepositorySyntax.empty();
        SemanticMethod method = new SemanticMethod(
                "com.acme", "OrderService", "place", java.util.List.of(), "void",
                new SemanticLocation(root.resolve("OrderService.java").toUri().toString(),
                        new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0)),
                        new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(0, 1))));
        IncomingGraphFragment result = mock(IncomingGraphFragment.class);

        when(repositoryApplicationService.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, IncomingGraphFragment> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(syntaxExtractionService.extract(root)).thenReturn(syntax);
        ExactMethodDeclarationResolver declarationResolver = mock(ExactMethodDeclarationResolver.class);
        when(declarationResolver.resolve(syntax, target)).thenReturn(new SemanticDeclarationAnchor(
                target, new SemanticPosition(0, 0)));
        when(semanticService.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class))).thenReturn(method);
        when(incomingBuilder.build(eq(snapshot), eq(syntax), eq(target), eq(method), eq(2), eq(11))).thenReturn(result);

        SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                declarationResolver,
                semanticService,
                builder,
                incomingBuilder,
                new OutgoingGraphProperties(7),
                new IncomingGraphProperties(11));

        service.analyzeIncoming(repositoryId, revision, target, 2);

        InOrder calls = inOrder(repositoryApplicationService, syntaxExtractionService, semanticService, incomingBuilder);
        calls.verify(repositoryApplicationService).withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any());
        calls.verify(syntaxExtractionService).extract(root);
        calls.verify(semanticService).resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class));
        calls.verify(incomingBuilder).build(snapshot, syntax, target, method, 2, 11);
    }
}
