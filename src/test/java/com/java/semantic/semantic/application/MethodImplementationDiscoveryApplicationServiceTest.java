package com.java.semantic.semantic.application;

import com.java.semantic.callgraph.application.CanonicalTargetProjection;
import com.java.semantic.callgraph.application.ImplementationCandidateFactory;
import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.semantic.domain.JavaSemanticService;
import com.java.semantic.semantic.domain.SemanticDeclarationAnchor;
import com.java.semantic.semantic.domain.SemanticImplementationIssueReason;
import com.java.semantic.semantic.domain.SemanticImplementationResult;
import com.java.semantic.semantic.domain.SemanticLocation;
import com.java.semantic.semantic.domain.SemanticMethod;
import com.java.semantic.semantic.domain.SemanticPosition;
import com.java.semantic.semantic.domain.SemanticRange;
import com.java.semantic.syntax.adapter.jdt.JdtSyntaxExtractionService;
import com.java.semantic.syntax.domain.CanonicalMethodDeclarationResolver;
import com.java.semantic.syntax.domain.SourceTypeMetadata;
import com.java.semantic.syntax.domain.SourceMethodMetadata;
import com.java.semantic.syntax.domain.SourceTypeKind;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceSlice;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import com.java.semantic.syntax.domain.SyntaxPosition;
import com.java.semantic.syntax.domain.SyntaxRange;
import com.java.semantic.syntax.domain.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MethodImplementationDiscoveryApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.fixture();

    @TempDir
    private Path root;

    @Test
    void should_discover_enriched_candidates_in_canonical_order_at_the_requested_revision() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        MethodTarget zeta = target("ZetaHandler.java", "ZetaHandler", "handle");
        MethodTarget alpha = target("AlphaHandler.java", "AlphaHandler", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.INTERFACE, false, true, false, false, List.of(), List.of()),
                        type(zeta, SourceTypeKind.CLASS, false, false, true, false,
                                List.of("zeta"), List.of("batch")),
                        type(alpha, SourceTypeKind.CLASS, false, false, true, true,
                                List.of("alpha"), List.of("prod"))),
                requested,
                List.of(semantic(zeta), semantic(alpha)),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.revision()).isEqualTo(REVISION);
        assertThat(result.requestedTarget()).isEqualTo(requested);
        assertThat(result.candidates()).extracting(candidate -> candidate.target())
                .containsExactly(alpha, zeta);
        assertThat(result.candidates().getFirst()).satisfies(candidate -> {
            assertThat(candidate.primary()).isTrue();
            assertThat(candidate.qualifiers()).containsExactly("alpha");
            assertThat(candidate.profiles()).containsExactly("prod");
        });
        assertThat(result.limits()).isEqualTo(new MethodImplementationLimits(10, 2, 2, false));
        assertThat(result.issues()).isEmpty();
        InOrder calls = inOrder(fixture.repository, fixture.syntaxExtraction, fixture.semantic);
        calls.verify(fixture.repository).withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any());
        calls.verify(fixture.syntaxExtraction).extract(root);
        calls.verify(fixture.semantic).resolveExactMethod(eq(fixture.snapshot), any(SemanticDeclarationAnchor.class));
        calls.verify(fixture.semantic).implementations(fixture.snapshot, fixture.requestedSemantic);
    }

    @Test
    void should_remove_the_requested_target_before_deduplicating_and_counting_candidates() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        MethodTarget implementation = target("Handler.java", "Handler", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.INTERFACE, false, true, false, false, List.of(), List.of()),
                        type(implementation, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of())),
                requested,
                List.of(semantic(requested), semantic(implementation), semantic(implementation)),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.candidates()).extracting(candidate -> candidate.target()).containsExactly(implementation);
        assertThat(result.limits()).isEqualTo(new MethodImplementationLimits(10, 1, 1, false));
    }

    @ParameterizedTest(name = "should reject {0} as an implementation discovery declaration")
    @MethodSource("unsupportedDeclarations")
    void should_reject_unsupported_declaration_forms(
            String ignoredDescription,
            SourceTypeKind kind,
            boolean abstractClass,
            boolean abstractDeclaration,
            boolean executableDeclaration) {
        MethodTarget requested = target("Target.java", "Target", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(type(requested, kind, abstractClass, abstractDeclaration,
                        executableDeclaration, false, List.of(), List.of())),
                requested,
                List.of(),
                List.of());

        assertThatThrownBy(() -> fixture.discover(10, new ImplementationCandidateFactory()))
                .isInstanceOf(ImplementationTargetUnsupportedException.class)
                .extracting("target")
                .isEqualTo(requested);
        verify(fixture.semantic, never()).implementations(any(), any());
    }

    @Test
    void should_accept_an_abstract_method_declared_by_an_abstract_class() {
        MethodTarget requested = target("AbstractPort.java", "AbstractPort", "handle");
        MethodTarget implementation = target("Handler.java", "Handler", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.CLASS, true, true, false, false, List.of(), List.of()),
                        type(implementation, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of())),
                requested,
                List.of(semantic(implementation)),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.candidates()).extracting(candidate -> candidate.target()).containsExactly(implementation);
    }

    @Test
    void should_discover_an_implementation_of_an_implicitly_abstract_interface_method_extracted_from_syntax()
            throws IOException {
        Path sourceFile = root.resolve("src/main/java/com/acme/Port.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, """
                package com.acme;

                interface Port {
                    void handle();
                }

                class Handler implements Port {
                    @Override
                    public void handle() {
                    }
                }
                """);
        JdtSyntaxExtractionService syntaxExtraction = new JdtSyntaxExtractionService();
        RepositorySyntax syntax = syntaxExtraction.extract(root);
        MethodTarget requested = extractedTarget(syntax, "Port", "handle");
        MethodTarget implementation = extractedTarget(syntax, "Handler", "handle");
        RepositoryApplicationService repository = mock(RepositoryApplicationService.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        SemanticMethod requestedSemantic = semantic(requested);
        when(repository.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundMethodImplementations> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(semantic.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class)))
                .thenReturn(requestedSemantic);
        when(semantic.implementations(snapshot, requestedSemantic))
                .thenReturn(new SemanticImplementationResult(List.of(semantic(implementation)), List.of()));
        MethodImplementationDiscoveryApplicationService service = new MethodImplementationDiscoveryApplicationService(
                repository,
                syntaxExtraction,
                new CanonicalMethodDeclarationResolver(),
                semantic,
                new CanonicalTargetProjection(),
                new ImplementationCandidateFactory(),
                10);

        RevisionBoundMethodImplementations result = service.discover(
                new MethodImplementationDiscoveryQuery(REPOSITORY_ID, REVISION, requested));

        assertThat(result.candidates()).extracting(candidate -> candidate.target()).containsExactly(implementation);
    }

    @Test
    void should_preserve_adapter_issue_multiplicity() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(type(requested, SourceTypeKind.INTERFACE, false, true, false, false,
                        List.of(), List.of())),
                requested,
                List.of(),
                List.of(
                        SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED,
                        SemanticImplementationIssueReason.EXTERNAL_TARGET,
                        SemanticImplementationIssueReason.LOCAL_CONVERSION_FAILED));

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.issues()).containsExactly(
                MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED,
                MethodImplementationIssueReason.EXTERNAL_TARGET,
                MethodImplementationIssueReason.LOCAL_CONVERSION_FAILED);
    }

    @Test
    void should_record_projection_loss_without_returning_a_candidate() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(type(requested, SourceTypeKind.INTERFACE, false, true, false, false,
                        List.of(), List.of())),
                requested,
                List.of(unprojectableMethod()),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.issues()).containsExactly(MethodImplementationIssueReason.CANONICAL_TARGET_UNRESOLVED);
    }

    @Test
    void should_omit_non_executable_implementations_and_record_an_issue() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        MethodTarget abstractImplementation = target("AbstractHandler.java", "AbstractHandler", "handle");
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.INTERFACE, false, true, false, false,
                                List.of(), List.of()),
                        type(abstractImplementation, SourceTypeKind.CLASS, true, true, false, false,
                                List.of(), List.of())),
                requested,
                List.of(semantic(abstractImplementation)),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(10, new ImplementationCandidateFactory());

        assertThat(result.candidates()).isEmpty();
        assertThat(result.limits()).isEqualTo(new MethodImplementationLimits(10, 0, 0, false));
        assertThat(result.issues()).containsExactly(MethodImplementationIssueReason.NON_EXECUTABLE_TARGET);
    }

    @Test
    void should_fail_closed_when_candidate_metadata_cannot_be_proven() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        MethodTarget implementation = target("Handler.java", "Handler", "handle");
        ImplementationCandidateFactory factory = mock(ImplementationCandidateFactory.class);
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.INTERFACE, false, true, false, false,
                                List.of(), List.of()),
                        type(implementation, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of())),
                requested,
                List.of(semantic(implementation)),
                List.of());
        when(factory.create(any(), any(), eq(implementation))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.discover(10, factory))
                .isInstanceOf(ImplementationDiscoveryContractException.class)
                .extracting("target")
                .isEqualTo(implementation);
    }

    @Test
    void should_cap_after_canonical_sort_without_treating_truncation_as_an_issue() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        MethodTarget methodAlpha = target("A.java", "com.alpha", "Alpha", "alpha", List.of());
        MethodTarget parameterAlpha = target("A.java", "com.alpha", "Alpha", "beta", List.of("Alpha"));
        MethodTarget parameterZeta = target("A.java", "com.alpha", "Alpha", "beta", List.of("Zeta"));
        MethodTarget classBeta = target("A.java", "com.alpha", "Beta", "alpha", List.of());
        MethodTarget packageBeta = target("A.java", "com.beta", "Alpha", "alpha", List.of());
        MethodTarget sourceBeta = target("B.java", "com.alpha", "Alpha", "alpha", List.of());
        DiscoveryFixture fixture = fixture(
                List.of(
                        type(requested, SourceTypeKind.INTERFACE, false, true, false, false,
                                List.of(), List.of()),
                        type(sourceBeta, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of()),
                        type(packageBeta, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of()),
                        type(classBeta, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of()),
                        type(parameterZeta, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of()),
                        type(parameterAlpha, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of()),
                        type(methodAlpha, SourceTypeKind.CLASS, false, false, true, false,
                                List.of(), List.of())),
                requested,
                List.of(
                        semantic(sourceBeta),
                        semantic(packageBeta),
                        semantic(classBeta),
                        semantic(parameterZeta),
                        semantic(parameterAlpha),
                        semantic(methodAlpha)),
                List.of());

        RevisionBoundMethodImplementations result = fixture.discover(5, new ImplementationCandidateFactory());

        assertThat(result.candidates()).extracting(candidate -> candidate.target())
                .containsExactly(methodAlpha, parameterAlpha, parameterZeta, classBeta, packageBeta);
        assertThat(result.limits()).isEqualTo(new MethodImplementationLimits(5, 5, 6, true));
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void should_propagate_revision_mismatch_without_extracting_syntax() {
        MethodTarget requested = target("Port.java", "Port", "handle");
        RepositoryApplicationService repository = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntaxExtraction = mock(SyntaxExtractionService.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        RepositoryRevision currentRevision = RepositoryRevision.ofSha("a".repeat(40));
        RepositoryRevisionMismatchException mismatch = new RepositoryRevisionMismatchException(REVISION, currentRevision);
        when(repository.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any())).thenThrow(mismatch);
        MethodImplementationDiscoveryApplicationService service = new MethodImplementationDiscoveryApplicationService(
                repository,
                syntaxExtraction,
                new CanonicalMethodDeclarationResolver(),
                semantic,
                new CanonicalTargetProjection(),
                new ImplementationCandidateFactory(),
                10);

        assertThatThrownBy(() -> service.discover(new MethodImplementationDiscoveryQuery(REPOSITORY_ID, REVISION, requested)))
                .isSameAs(mismatch);
        verify(syntaxExtraction, never()).extract(any());
    }

    private static Stream<Arguments> unsupportedDeclarations() {
        return Stream.of(
                Arguments.of("interface default method", SourceTypeKind.INTERFACE, false, false, true),
                Arguments.of("interface concrete method", SourceTypeKind.INTERFACE, false, false, true),
                Arguments.of("concrete class method", SourceTypeKind.CLASS, false, false, true),
                Arguments.of("enum abstract method", SourceTypeKind.ENUM, false, true, false),
                Arguments.of("abstract-class native method", SourceTypeKind.CLASS, true, false, false));
    }

    private DiscoveryFixture fixture(
            List<SourceTypeMetadata> classes,
            MethodTarget requested,
            List<SemanticMethod> implementations,
            List<SemanticImplementationIssueReason> adapterIssues) {
        RepositoryApplicationService repository = mock(RepositoryApplicationService.class);
        SyntaxExtractionService syntaxExtraction = mock(SyntaxExtractionService.class);
        JavaSemanticService semantic = mock(JavaSemanticService.class);
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, root, REVISION);
        RepositorySyntax syntax = new RepositorySyntax(List.of(), classes);
        SemanticMethod requestedSemantic = semantic(requested);
        when(repository.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundMethodImplementations> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(syntaxExtraction.extract(root)).thenReturn(syntax);
        lenient().when(semantic.resolveExactMethod(eq(snapshot), any(SemanticDeclarationAnchor.class)))
                .thenReturn(requestedSemantic);
        lenient().when(semantic.implementations(snapshot, requestedSemantic))
                .thenReturn(new SemanticImplementationResult(implementations, adapterIssues));
        return new DiscoveryFixture(repository, syntaxExtraction, semantic, snapshot, requestedSemantic, requested);
    }

    private MethodImplementationDiscoveryApplicationService service(
            DiscoveryFixture fixture,
            int candidateLimit,
            ImplementationCandidateFactory factory) {
        return new MethodImplementationDiscoveryApplicationService(
                fixture.repository,
                fixture.syntaxExtraction,
                new CanonicalMethodDeclarationResolver(),
                fixture.semantic,
                new CanonicalTargetProjection(),
                factory,
                candidateLimit);
    }

    private static MethodTarget target(String sourceFile, String className, String methodName) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme", className),
                        sourceFile),
                methodName,
                List.of());
    }

    private static MethodTarget target(
            String sourceFile,
            String packageName,
            String className,
            String methodName,
            List<String> parameterTypes) {
        return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(packageName, className),
                        sourceFile),
                methodName,
                parameterTypes);
    }

    private static MethodTarget extractedTarget(RepositorySyntax syntax, String className, String methodName) {
        return syntax.sourceTypes().stream()
                .filter(metadata -> className.equals(metadata.declaration().identity().javaType().className()))
                .flatMap(metadata -> metadata.members().methods().stream())
                .filter(method -> methodName.equals(method.name()))
                .map(method -> method.analysisTarget().target().orElseThrow())
                .findFirst()
                .orElseThrow();
    }

    private SemanticMethod semantic(MethodTarget target) {
        SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0));
        return new SemanticMethod(
                target.packageName(),
                target.className(),
                target.methodName(),
                target.parameterTypes(),
                "void",
                new SemanticLocation(root.resolve(target.sourceFile()).toUri().toString(), range, range));
    }

    private SemanticMethod unprojectableMethod() {
        SemanticRange range = new SemanticRange(new SemanticPosition(0, 0), new SemanticPosition(1, 0));
        return new SemanticMethod(
                "external", "ExternalHandler", "handle", List.of(), "void",
                new SemanticLocation("file:///outside/ExternalHandler.java", range, range));
    }

    private static SourceTypeMetadata type(
            MethodTarget target,
            SourceTypeKind kind,
            boolean abstractClass,
            boolean abstractDeclaration,
            boolean executableDeclaration,
            boolean primary,
            List<String> qualifiers,
            List<String> profiles) {
        SyntaxRange range = new SyntaxRange(new SyntaxPosition(0, 0), new SyntaxPosition(3, 0));
        SourceMethodMetadata method = new SourceMethodMetadata(
                target.methodName(),
                target.parameterTypes(),
                null,
                null,
                1,
                2,
                range,
                new SourceSlice(range, "void " + target.methodName() + "() {}"),
                List.<TypeReference>of(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                range.start(),
                MethodTargetResolution.resolved(target),
                executableDeclaration,
                abstractDeclaration,
                true);
        return com.java.semantic.syntax.domain.SourceTypeMetadataFixture.sourceType(
                target.className(),
                target.packageName(),
                target.packageName() + "." + target.className(),
                target.sourceFile(),
                kind,
                abstractClass,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(method),
                false,
                false,
                profiles,
                range,
                new SourceSlice(range, "class " + target.className() + " {}"),
                primary,
                qualifiers);
    }

    private final class DiscoveryFixture {

        private final RepositoryApplicationService repository;
        private final SyntaxExtractionService syntaxExtraction;
        private final JavaSemanticService semantic;
        private final RepositorySnapshot snapshot;
        private final SemanticMethod requestedSemantic;
        private final MethodTarget requested;

        private DiscoveryFixture(
                RepositoryApplicationService repository,
                SyntaxExtractionService syntaxExtraction,
                JavaSemanticService semantic,
                RepositorySnapshot snapshot,
                SemanticMethod requestedSemantic,
                MethodTarget requested) {
            this.repository = repository;
            this.syntaxExtraction = syntaxExtraction;
            this.semantic = semantic;
            this.snapshot = snapshot;
            this.requestedSemantic = requestedSemantic;
            this.requested = requested;
        }

        private RevisionBoundMethodImplementations discover(int candidateLimit, ImplementationCandidateFactory factory) {
            MethodImplementationDiscoveryApplicationService service = service(this, candidateLimit, factory);
            return service.discover(new MethodImplementationDiscoveryQuery(REPOSITORY_ID, REVISION, requested));
        }
    }
}
