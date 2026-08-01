package com.java.semantic.syntax.application.concept;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.concept.EntryPointConceptIdentity.ApiRouteConceptIdentity;
import com.java.semantic.syntax.application.concept.DeclarationConceptIdentity.MethodConceptIdentity;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SourceExtractionOutcome;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 固定版本結構化概念探索的應用服務契約 */
@ExtendWith(MockitoExtension.class)
class ConceptDiscoveryApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final Path REPOSITORY_ROOT = Path.of("/workspace/orders");

    @Mock
    private RepositoryApplicationService repositoryApplicationService;

    @Mock
    private SyntaxExtractionService syntaxExtractionService;

    private RepositorySyntax syntax;
    private ConceptDiscoveryApplicationService service;

    @BeforeEach
    void setUp() {
        syntax = new RepositorySyntax(
                List.of(),
                List.of(),
                List.of(
                        SourceExtractionOutcome.extracted("src/main/java/com/acme/order/OrderService.java"),
                        SourceExtractionOutcome.syntaxFailed("src/main/java/com/acme/order/Broken.java", "SYNTAX_ERROR")));
        service = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                new StructuredConceptCatalogProjector(List.of(new TestConceptProvider())),
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
    }

    @Test
    void should_search_a_fixed_revision_before_one_page_and_return_an_exact_next_query() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(
                        new ConceptSearchTerm("ORDER*", ConceptMatchMode.TOKEN_EXACT),
                        new ConceptSearchTerm("ord", ConceptMatchMode.TOKEN_PREFIX)),
                Set.of(ConceptKind.METHOD, ConceptKind.API_ROUTE),
                Set.of(" com.acme.order. "),
                1,
                2);
        delegateSnapshot(snapshot, query);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(syntax);

        ConceptSearchResult result = service.search(query);

        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
        assertThat(result.normalizedTerms()).containsExactly(
                new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX),
                new ConceptSearchTerm("ord", ConceptMatchMode.TOKEN_PREFIX));
        assertThat(result.searchedKinds()).containsExactly(
                ConceptKind.METHOD,
                ConceptKind.API_ROUTE);
        assertThat(result.candidates()).extracting(ConceptCatalogEntry::displayValue)
                .containsExactly("OrderUpdate", "GET /orders");
        assertThat(result.page()).isEqualTo(new ConceptPage(1, 2, 2, 4, true));
        assertThat(result.coverage()).isEqualTo(syntax.extractionOutcomes());
        assertThat(result.issueSummaries()).containsExactly(
                new ConceptIssueSummary(ConceptIssueReason.MQ_DESTINATION_UNRESOLVED, 2),
                new ConceptIssueSummary(ConceptIssueReason.SCHEDULE_TRIGGER_VALUE_UNRESOLVED, 1));
        assertThat(result.nextPageQuery()).contains(new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(
                        new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_PREFIX),
                        new ConceptSearchTerm("ord", ConceptMatchMode.TOKEN_PREFIX)),
                Set.of(ConceptKind.METHOD, ConceptKind.API_ROUTE),
                Set.of("com.acme.order"),
                3,
                2));
        InOrder order = inOrder(syntaxExtractionService);
        order.verify(syntaxExtractionService).extract(REPOSITORY_ROOT);
    }

    @Test
    void should_resolve_one_typed_identity_from_the_expected_revision_catalog_without_search() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        MethodTarget target = new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.acme.order", "OrderService"),
                        "src/main/java/com/acme/order/OrderService.java"),
                "createOrder",
                List.of());
        ConceptResolveQuery query = new ConceptResolveQuery(
                REPOSITORY_ID, REVISION, new MethodConceptIdentity(target));
        delegateResolveSnapshot(snapshot, query);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(syntax);

        RevisionBoundConceptResolution result = service.resolve(query);

        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
        assertThat(result.candidate().identity()).isEqualTo(query.identity());
    }

    @ParameterizedTest
    @MethodSource("zeroResultCoverage")
    void should_preserve_complete_or_partial_coverage_on_the_required_empty_default_page(
            List<SourceExtractionOutcome> extractionOutcomes) {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        RepositorySyntax zeroResultSyntax = new RepositorySyntax(
                List.of(),
                List.of(),
                extractionOutcomes);
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(new ConceptSearchTerm("invoice", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(ConceptKind.METHOD),
                Set.of(),
                0,
                50);
        delegateSnapshot(snapshot, query);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(zeroResultSyntax);

        ConceptSearchResult result = service.search(query);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.page()).isEqualTo(new ConceptPage(0, 50, 0, 0, false));
        assertThat(result.coverage()).containsExactlyElementsOf(extractionOutcomes);
        assertThat(result.nextPageQuery()).isEmpty();
    }

    @Test
    void should_reject_mapper_statement_when_its_provider_is_absent_before_repository_access() {
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(ConceptKind.MAPPER_STATEMENT),
                Set.of(),
                0,
                50);

        assertThatThrownBy(() -> service.search(query))
                .isInstanceOfSatisfying(ConceptKindUnavailableException.class, exception -> {
                    assertThat(exception.unavailableKinds()).containsExactly(ConceptKind.MAPPER_STATEMENT);
                    assertThat(exception.supportedKinds()).containsExactly(ConceptKind.METHOD, ConceptKind.API_ROUTE);
                });
        verifyNoInteractions(repositoryApplicationService, syntaxExtractionService);
    }

    @Test
    void should_accept_every_kind_active_in_the_configured_provider_composition() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        StructuredConceptCatalogProjector projector = new StructuredConceptCatalogProjector();
        ConceptDiscoveryApplicationService defaultService = new ConceptDiscoveryApplicationService(
                repositoryApplicationService,
                syntaxExtractionService,
                projector,
                new ConceptSearchDocumentProjector(),
                new ConceptSearchMatcher());
        ConceptSearchQuery query = new ConceptSearchQuery(
                REPOSITORY_ID,
                REVISION,
                List.of(new ConceptSearchTerm("order", ConceptMatchMode.TOKEN_EXACT)),
                Set.of(
                        ConceptKind.TYPE,
                        ConceptKind.METHOD,
                        ConceptKind.FIELD,
                        ConceptKind.ANNOTATION_USAGE,
                        ConceptKind.TYPE_USAGE,
                        ConceptKind.API_ROUTE,
                        ConceptKind.MQ_DESTINATION,
                        ConceptKind.SCHEDULE,
                        ConceptKind.MAPPER_STATEMENT),
                Set.of(),
                0,
                50);
        delegateSnapshot(snapshot, query);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(syntax);

        ConceptSearchResult result = defaultService.search(query);

        assertThat(result.supportedKinds()).containsExactly(
                ConceptKind.TYPE,
                ConceptKind.METHOD,
                ConceptKind.FIELD,
                ConceptKind.ANNOTATION_USAGE,
                ConceptKind.TYPE_USAGE,
                ConceptKind.API_ROUTE,
                ConceptKind.MQ_DESTINATION,
                ConceptKind.SCHEDULE,
                ConceptKind.MAPPER_STATEMENT);
    }

    private static Stream<Arguments> zeroResultCoverage() {
        return Stream.of(
                Arguments.of(List.of(SourceExtractionOutcome.extracted(
                        "src/main/java/com/acme/order/OrderService.java"))),
                Arguments.of(List.of(
                        SourceExtractionOutcome.extracted(
                                "src/main/java/com/acme/order/OrderService.java"),
                        SourceExtractionOutcome.syntaxFailed(
                                "src/main/java/com/acme/order/Broken.java",
                                "SYNTAX_ERROR"))));
    }

    private void delegateSnapshot(RepositorySnapshot snapshot, ConceptSearchQuery query) {
        when(repositoryApplicationService.withSnapshot(
                eq(query.repositoryId()), eq(Optional.of(query.expectedRevision())), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, ConceptSearchResult> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
    }

    private void delegateResolveSnapshot(RepositorySnapshot snapshot, ConceptResolveQuery query) {
        when(repositoryApplicationService.withSnapshot(
                eq(query.repositoryId()), eq(Optional.of(query.expectedRevision())), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundConceptResolution> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
    }

    /** 提供刻意未排序的目錄以驗證應用層篩選與分頁 */
    private static final class TestConceptProvider implements ConceptProvider {

        @Override
        public String providerId() {
            return "test";
        }

        @Override
        public Set<ConceptKind> supportedKinds() {
            return Set.of(ConceptKind.METHOD, ConceptKind.API_ROUTE);
        }

        @Override
        public ConceptProviderProjection project(RepositorySyntax ignored) {
            return new ConceptProviderProjection(
                    List.of(
                            apiRoute("POST /orders", "POST", "com.acme.order"),
                            method("OrderCreate", "createOrder", "com.acme.order"),
                            apiRoute("GET /orders", "GET", "com.acme.order"),
                            method("OrderUpdate", "updateOrder", "com.acme.order"),
                            method("OrderForeign", "findOrder", "com.acme.foreign")),
                    List.of(
                            ConceptIssueReason.MQ_DESTINATION_UNRESOLVED,
                            ConceptIssueReason.MQ_DESTINATION_UNRESOLVED,
                            ConceptIssueReason.SCHEDULE_TRIGGER_VALUE_UNRESOLVED));
        }

        private static ConceptCatalogEntry method(String displayValue, String methodName, String packageName) {
            MethodTarget target = target(methodName, packageName);
            MethodConceptIdentity identity = new MethodConceptIdentity(target);
            return entry(identity, displayValue, packageName);
        }

        private static ConceptCatalogEntry apiRoute(String displayValue, String method, String packageName) {
            MethodTarget target = target(method.toLowerCase() + "Order", packageName);
            ApiRouteConceptIdentity identity = new ApiRouteConceptIdentity(target, method, "/orders");
            return entry(identity, displayValue, packageName);
        }

        private static ConceptCatalogEntry entry(ConceptIdentity identity, String displayValue, String packageName) {
            return new ConceptCatalogEntry(
                    "test",
                    identity,
                    displayValue,
                    packageName,
                    Optional.of(packageName + ".OrderService"),
                    ConceptAuthority.SYNTAX_DECLARED,
                    Set.of(identity));
        }

        private static MethodTarget target(String methodName, String packageName) {
            return new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity(packageName, "OrderService"),
                        "src/main/java/" + packageName.replace('.', '/') + "/OrderService.java"),
                methodName,
                List.of());
        }
    }
}
