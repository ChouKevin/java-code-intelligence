package com.java.semantic.semantic.application;

import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.repository.domain.RepositoryStatus;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.semantic.domain.SemanticReferenceLocation;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationResolver;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.model.codefact.SourceRange;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceTypeMetadataFixture;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalSourceReferenceApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha("a".repeat(40));
    private static final String SOURCE_FILE = "src/main/java/com/example/OrderService.java";

    @TempDir
    Path root;

    @Test
    void should_aggregate_exact_revision_references_into_deterministic_bounded_context_groups() {
        SourceTypeIdentity sourceType = new SourceTypeIdentity(
                new JavaTypeIdentity("com.example", "OrderService"), SOURCE_FILE);
        MethodTarget methodTarget = new MethodTarget(sourceType, "submit", List.of("SubmitOrder"));
        ExactSourceDeclarationTarget target = new ExactSourceDeclarationTarget.Type(sourceType);
        ExactSourceDeclaration declaration = new ExactSourceDeclaration(
                target, syntaxRange(0, 0, 40, 1), syntaxRange(1, 13, 1, 25));
        ExactSourceDeclarationResolver declarationResolver = mock(ExactSourceDeclarationResolver.class);
        when(declarationResolver.resolve(root, target)).thenReturn(Optional.of(declaration));
        JavaSemanticService semanticService = mock(JavaSemanticService.class);
        when(semanticService.findReferences(any(), any()))
                .thenReturn(List.of(
                        local(SOURCE_FILE, 1, 13, 1, 25),
                        local(SOURCE_FILE, 12, 8, 12, 20),
                        local(SOURCE_FILE, 12, 8, 12, 20),
                        local(SOURCE_FILE, 13, 8, 13, 20),
                        local(SOURCE_FILE, 14, 8, 14, 20),
                        local(SOURCE_FILE, 15, 8, 15, 20),
                        local(SOURCE_FILE, 5, 4, 5, 16),
                        local("src/main/java/com/example/Loose.java", 0, 0, 0, 5),
                        local("src/main/resources/reference.txt", 0, 0, 0, 5),
                        SemanticReferenceLocation.OutsideRepository.INSTANCE,
                        SemanticReferenceLocation.UnprovableUri.INSTANCE));
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider = mock(RevisionBoundRepositorySyntaxProvider.class);
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(new RepositorySyntax(
                List.of(), List.of(sourceMetadata(sourceType, methodTarget))));
        List<InternalReferenceAnalysisCache.Key> cacheKeys = new ArrayList<>();
        InternalReferenceAnalysisCache cache = (key, loader) -> {
            cacheKeys.add(key);
            InternalReferenceAnalysis analysis = loader.get();
            return new InternalReferenceAnalysisCache.LookupResult(
                    analysis, false, false, analysis.entryWeight(), Duration.ofMillis(4));
        };
        InternalSourceReferenceApplicationService service = new InternalSourceReferenceApplicationService(
                new SnapshotRepositoryApplicationService(snapshot),
                declarationResolver,
                semanticService,
                repositorySyntaxProvider,
                cache);

        InternalSourceReferenceResult complete = service.find(new InternalSourceReferenceQuery(
                REPOSITORY_ID, REVISION, target, 0, 20));
        InternalSourceReferenceResult secondPage = service.find(new InternalSourceReferenceQuery(
                REPOSITORY_ID, REVISION, target, 1, 1));

        assertThat(complete.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(complete.analyzedRevision()).isEqualTo(REVISION);
        assertThat(complete.targetDeclaration()).isEqualTo(declaration);
        assertThat(complete.status()).isEqualTo(InternalReferenceStatus.PARTIAL);
        assertThat(complete.totalReferenceCount()).isEqualTo(6);
        assertThat(complete.rawLocationCount()).isEqualTo(11);
        assertThat(complete.repositoryLocalReferenceCount()).isEqualTo(7);
        assertThat(complete.hitFileCount()).isEqualTo(2);
        assertThat(complete.totalGroupCount()).isEqualTo(2);
        assertThat(complete.groups()).extracting(group -> group.context().kind())
                .containsExactly(InternalReferenceContext.Kind.TYPE, InternalReferenceContext.Kind.METHOD);
        assertThat(complete.groups().getFirst().context())
                .isEqualTo(new InternalReferenceContext.Type(sourceType));
        assertThat(complete.groups().getFirst().limits())
                .isEqualTo(new InternalReferenceGroupLimits(3, 1, 1, false));
        assertThat(complete.groups().get(1).context())
                .isEqualTo(new InternalReferenceContext.Method(methodTarget));
        assertThat(complete.groups().get(1).representativeReferences()).hasSize(3);
        assertThat(complete.groups().get(1).limits())
                .isEqualTo(new InternalReferenceGroupLimits(3, 3, 4, true));
        assertThat(complete.issueSummaries()).containsExactly(
                new InternalReferenceIssueSummary(InternalReferenceIssueCode.REFERENCE_SOURCE_NOT_JAVA, 1),
                new InternalReferenceIssueSummary(InternalReferenceIssueCode.REFERENCE_SOURCE_OUTSIDE_SNAPSHOT, 1),
                new InternalReferenceIssueSummary(InternalReferenceIssueCode.REFERENCE_CONTEXT_UNRESOLVED, 1));
        assertThat(complete.page()).isEqualTo(new InternalReferencePage(0, 20, 2, 2, false));
        assertThat(complete.cache()).isEqualTo(new InternalReferenceCacheMetadata(
                false, false, complete.cache().entryWeight(), 4));
        assertThat(secondPage.groups()).singleElement()
                .satisfies(group -> assertThat(group.context().kind())
                        .isEqualTo(InternalReferenceContext.Kind.METHOD));
        assertThat(secondPage.page()).isEqualTo(new InternalReferencePage(1, 1, 1, 2, false));
        assertThat(cacheKeys).hasSize(2).allMatch(cacheKeys.getFirst()::equals);
    }

    private SourceTypeMetadata sourceMetadata(SourceTypeIdentity sourceType, MethodTarget target) {
        SyntaxRange typeRange = syntaxRange(0, 0, 40, 1);
        SyntaxRange methodRange = syntaxRange(10, 4, 20, 5);
        SourceMethodMetadata method = new SourceMethodMetadata(
                target.methodName(), target.parameterTypes(), null, Optional.empty(),
                new SourceRange(target.sourceFile(), methodRange),
                List.<TypeReference>of(), Optional.empty(), List.of(), List.of(), List.of(),
                new SyntaxPosition(10, 9), MethodTargetResolution.resolved(target), true, false, true, List.of());
        return SourceTypeMetadataFixture.sourceType(
                sourceType.javaType().className(),
                sourceType.javaType().packageName(),
                sourceType.fullyQualifiedName(),
                sourceType.sourceFile(),
                SourceTypeKind.CLASS,
                false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(method),
                false, false, List.of(), typeRange,
                new SourceRange(target.sourceFile(), typeRange), false, List.of());
    }

    private SemanticReferenceLocation.LocalSource local(
            String sourceFile, int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SemanticReferenceLocation.LocalSource(
                sourceFile,
                new SemanticRange(
                        new SemanticPosition(startLine, startCharacter),
                        new SemanticPosition(endLine, endCharacter)));
    }

    private SyntaxRange syntaxRange(
            int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }

    private static final class SnapshotRepositoryApplicationService implements RepositoryApplicationService {

        private final RepositorySnapshot snapshot;

        private SnapshotRepositoryApplicationService(RepositorySnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public <T> T withSnapshot(
                RepositoryId repositoryId,
                Optional<RepositoryRevision> expectedRevision,
                Function<RepositorySnapshot, T> operation) {
            assertThat(repositoryId).isEqualTo(snapshot.repositoryId());
            assertThat(expectedRevision).contains(snapshot.revision());
            return operation.apply(snapshot);
        }

        @Override
        public RepositoryStatus status(RepositoryId repositoryId) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public List<RepositoryStatus> list() {
            return List.of();
        }
    }
}
