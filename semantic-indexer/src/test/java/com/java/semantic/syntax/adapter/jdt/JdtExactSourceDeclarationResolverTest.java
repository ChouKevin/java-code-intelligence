package com.java.semantic.syntax.adapter.jdt;

import com.java.semantic.api.ExactSourceDeclarationTargetHttpMapper;
import com.java.semantic.api.InternalSourceReferenceResponseMapper;
import com.java.semantic.api.SourceLocationHttpMapper;
import com.java.semantic.api.StructuredDiscoveryResponseMapper;
import com.java.semantic.api.dto.DiscoveryFollowUpResponse;
import com.java.semantic.api.dto.InternalSourceReferenceResponse;
import com.java.semantic.model.codefact.JavaTypeIdentity;
import com.java.semantic.model.codefact.MethodTarget;
import com.java.semantic.model.codefact.SourceTypeIdentity;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.semantic.application.InternalReferenceCacheMetadata;
import com.java.semantic.semantic.application.InternalReferencePage;
import com.java.semantic.semantic.application.InternalReferenceStatus;
import com.java.semantic.semantic.application.InternalSourceReferenceQuery;
import com.java.semantic.semantic.application.InternalSourceReferenceResult;
import com.java.semantic.syntax.application.DiscoveryFollowUp;
import com.java.semantic.syntax.application.DiscoveryFollowUpFactory;
import com.java.semantic.syntax.domain.ExactSourceDeclaration;
import com.java.semantic.syntax.domain.ExactSourceDeclarationTarget;
import com.java.semantic.syntax.domain.SourceMemberIdentity;
import com.java.semantic.model.codefact.SyntaxPosition;
import com.java.semantic.model.codefact.SyntaxRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** exact source declaration 的 canonical identity 契約 */
class JdtExactSourceDeclarationResolverTest {

    private static final String SOURCE_FILE = "src/main/java/com/acme/OrderService.java";

    private final JdtExactSourceDeclarationResolver resolver = new JdtExactSourceDeclarationResolver();

    @Test
    void should_resolve_exact_overloaded_method_declaration_and_identifier(@TempDir Path repositoryRoot)
            throws IOException {
        writeSource(repositoryRoot);
        SourceTypeIdentity owner = sourceType("OrderService");
        ExactSourceDeclarationTarget target = new ExactSourceDeclarationTarget.Method(
                new MethodTarget(owner, "confirm", List.of("java.lang.String")));

        Optional<ExactSourceDeclaration> declaration = resolver.resolve(repositoryRoot, target);

        assertThat(declaration).contains(new ExactSourceDeclaration(
                target,
                range(5, 4, 5, 35),
                range(5, 9, 5, 16)));
    }

    @Test
    void should_map_method_scoped_parameter_with_its_identity_declaration_range(@TempDir Path repositoryRoot)
            throws IOException {
        writeSource(repositoryRoot);
        MethodTarget declaringMethod = new MethodTarget(
                sourceType("OrderService"), "inspect", List.of("java.lang.String"));
        SyntaxRange identityRange = range(6, 24, 6, 29);
        ExactSourceDeclarationTarget target = new ExactSourceDeclarationTarget.Member(
                new SourceMemberIdentity.MethodScoped(declaringMethod, identityRange, "local"));
        ExactSourceDeclaration declaration = resolver.resolve(repositoryRoot, target).orElseThrow();
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.ofSha("a".repeat(40));
        InternalSourceReferenceQuery query = new InternalSourceReferenceQuery(
                repositoryId, revision, target, 0, 20);
        InternalSourceReferenceResult result = new InternalSourceReferenceResult(
                repositoryId,
                revision,
                declaration,
                InternalReferenceStatus.COMPLETE,
                0,
                List.of(),
                new InternalReferencePage(0, 20, 0, 0, false),
                List.of(),
                new InternalReferenceCacheMetadata(false, true, 1, 0),
                0,
                0,
                0,
                0);
        SourceLocationHttpMapper sourceLocationMapper = new SourceLocationHttpMapper();
        StructuredDiscoveryResponseMapper followUpMapper = mock(StructuredDiscoveryResponseMapper.class);
        when(followUpMapper.followUp(any(DiscoveryFollowUp.class)))
                .thenReturn(mock(DiscoveryFollowUpResponse.class));
        InternalSourceReferenceResponseMapper responseMapper = new InternalSourceReferenceResponseMapper(
                new ExactSourceDeclarationTargetHttpMapper(sourceLocationMapper),
                sourceLocationMapper,
                new DiscoveryFollowUpFactory(),
                followUpMapper);

        InternalSourceReferenceResponse response = responseMapper.toResponse(query, result);

        assertThat(declaration.declarationRange()).isEqualTo(identityRange);
        assertThat(declaration.identifierRange()).isEqualTo(identityRange);
        assertThat(response.targetDeclaration().declarationRange())
                .isEqualTo(sourceLocationMapper.toTextRange(identityRange));
    }

    @ParameterizedTest
    @MethodSource("typeAndMemberCases")
    void should_resolve_representative_type_and_member_declarations(
            Function<SourceTypeIdentity, ExactSourceDeclarationTarget> targetFactory,
            SyntaxRange declarationRange,
            SyntaxRange identifierRange,
            @TempDir Path repositoryRoot) throws IOException {
        writeSource(repositoryRoot);
        ExactSourceDeclarationTarget target = targetFactory.apply(sourceType("OrderService"));

        Optional<ExactSourceDeclaration> declaration = resolver.resolve(repositoryRoot, target);

        assertThat(declaration).contains(new ExactSourceDeclaration(target, declarationRange, identifierRange));
    }

    private static Stream<Object[]> typeAndMemberCases() {
        return Stream.of(
                new Object[]{
                        (Function<SourceTypeIdentity, ExactSourceDeclarationTarget>)
                                identity -> new ExactSourceDeclarationTarget.Type(identity),
                        range(1, 0, 7, 1),
                        range(1, 6, 1, 18)
                },
                new Object[]{
                        (Function<SourceTypeIdentity, ExactSourceDeclarationTarget>)
                                identity -> new ExactSourceDeclarationTarget.Member(
                                        new SourceMemberIdentity.TypeMember(identity, "repository")),
                        range(2, 4, 2, 22),
                        range(2, 11, 2, 21)
                });
    }

    private static void writeSource(Path repositoryRoot) throws IOException {
        Path source = repositoryRoot.resolve(SOURCE_FILE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme;
                class OrderService {
                    String repository;
                    void confirm() {}
                    void confirm(Integer orderId) {}
                    void confirm(String orderId) {}
                    void inspect(String local) { System.out.println(local); }
                }
                """);
    }

    private static SourceTypeIdentity sourceType(String className) {
        return new SourceTypeIdentity(new JavaTypeIdentity("com.acme", className), SOURCE_FILE);
    }

    private static SyntaxRange range(int startLine, int startCharacter, int endLine, int endCharacter) {
        return new SyntaxRange(
                new SyntaxPosition(startLine, startCharacter),
                new SyntaxPosition(endLine, endCharacter));
    }
}
