package com.java.semantic.semantic.application;

import com.java.semantic.syntax.domain.SourceTypeKind;

import com.java.semantic.syntax.domain.SourceMethodMetadata;

import com.java.semantic.callgraph.application.IncomingSemanticCallGraphBuilder;
import com.java.semantic.callgraph.application.SemanticCallGraphBuilder;
import com.java.semantic.callgraph.domain.IncomingGraphFragment;
import com.java.semantic.callgraph.domain.OutgoingGraphFragment;
import com.java.semantic.config.IncomingGraphProperties;
import com.java.semantic.config.OutgoingGraphProperties;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
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
    private RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    @Mock
    private JavaSemanticService semanticService;
    @Mock
    private SemanticCallGraphBuilder builder;
    @Mock
    private IncomingSemanticCallGraphBuilder incomingBuilder;

    @Test
    void should_hold_one_revision_locked_snapshot_for_the_exact_outgoing_sequence() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.ofSha("0".repeat(40));
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, revision);
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        "OrderService.java"),
                "place",
                java.util.List.of());
        RepositorySyntax syntax = syntax(target);
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
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(syntax);
        CanonicalMethodDeclarationResolver declarationResolver = mock(CanonicalMethodDeclarationResolver.class);
        when(declarationResolver.resolve(syntax, target)).thenReturn(MethodTargetResolution.resolved(target));
        when(semanticService.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class))).thenReturn(method);
        when(builder.build(eq(snapshot), eq(syntax), eq(target), eq(method), eq(2), eq(7))).thenReturn(null);

        SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                declarationResolver,
                semanticService,
                builder,
                incomingBuilder,
                new OutgoingGraphProperties(7),
                new IncomingGraphProperties(11));
        service.analyzeOutgoing(repositoryId, revision, target, 2);

        InOrder calls = inOrder(repositoryApplicationService, repositorySyntaxProvider, semanticService, builder);
        calls.verify(repositoryApplicationService).withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any());
        calls.verify(repositorySyntaxProvider).get(snapshot);
        calls.verify(semanticService).resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class));
        calls.verify(builder).build(snapshot, syntax, target, method, 2, 7);
    }

    @Test
    void should_hold_one_revision_locked_snapshot_for_the_exact_incoming_sequence() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.ofSha("0".repeat(40));
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, revision);
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", "OrderService"),
                        "OrderService.java"),
                "place",
                java.util.List.of());
        RepositorySyntax syntax = syntax(target);
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
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(syntax);
        CanonicalMethodDeclarationResolver declarationResolver = mock(CanonicalMethodDeclarationResolver.class);
        when(declarationResolver.resolve(syntax, target)).thenReturn(MethodTargetResolution.resolved(target));
        when(semanticService.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class))).thenReturn(method);
        when(incomingBuilder.build(eq(snapshot), eq(syntax), eq(target), eq(method), eq(2), eq(11))).thenReturn(result);

        SemanticAnalysisApplicationService service = new SemanticAnalysisApplicationService(
                repositoryApplicationService,
                repositorySyntaxProvider,
                declarationResolver,
                semanticService,
                builder,
                incomingBuilder,
                new OutgoingGraphProperties(7),
                new IncomingGraphProperties(11));

        service.analyzeIncoming(repositoryId, revision, target, 2);

        InOrder calls = inOrder(repositoryApplicationService, repositorySyntaxProvider, semanticService, incomingBuilder);
        calls.verify(repositoryApplicationService).withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any());
        calls.verify(repositorySyntaxProvider).get(snapshot);
        calls.verify(semanticService).resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class));
        calls.verify(incomingBuilder).build(snapshot, syntax, target, method, 2, 11);
    }

    private RepositorySyntax syntax(MethodTarget target) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(1, 0));
        SourceMethodMetadata method = new SourceMethodMetadata(
                target.methodName(),
                target.parameterTypes(),
                null,
                Optional.empty(),
                new SourceRange(target.sourceFile(), range),
                List.<TypeReference>of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                range.start(),
                MethodTargetResolution.resolved(target),
                true,
                false,
                true, List.of());
        SourceTypeMetadata metadata = com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(),
                target.packageName(),
                target.packageName() + "." + target.className(),
                target.sourceFile(),
                SourceTypeKind.CLASS,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(method),
                false,
                false,
                List.of(),
                range,
                new SourceRange(target.sourceFile(), range),
                false,
                List.of());
        return new RepositorySyntax(List.of(), List.of(metadata));
    }
}
