package com.java.semantic.syntax.application;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EntryPointDiscoveryApplicationServiceTest {

    private static final RepositoryId REPOSITORY_ID = RepositoryId.of("orders");
    private static final Path REPOSITORY_ROOT = Path.of("/workspace/orders");
    private static final RepositoryRevision REVISION = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");

    @Mock
    private RepositoryApplicationService repositoryApplicationService;

    @Mock
    private SyntaxExtractionService syntaxExtractionService;

    @Mock
    private EntryPointDiscoveryFilter discoveryFilter;

    private RepositorySyntax extracted;
    private RepositorySyntax filtered;
    private EntryPointDiscoveryApplicationService service;

    @BeforeEach
    void setUp() {
        extracted = new RepositorySyntax(List.of(entryPointClass("read")), List.of());
        filtered = new RepositorySyntax(List.of(entryPointClass("read")), List.of());
        service = new EntryPointDiscoveryApplicationService(
                repositoryApplicationService, syntaxExtractionService, discoveryFilter);
    }

    @Test
    void should_extract_and_filter_inside_snapshot_and_return_exact_revision() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        Set<EntryPointType> requested = EnumSet.of(EntryPointType.API, EntryPointType.MQ);
        when(repositoryApplicationService.withSnapshot(eq(REPOSITORY_ID), eq(Optional.of(REVISION)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundEntryPoints> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(extracted);
        when(discoveryFilter.filter(REPOSITORY_ID, extracted, requested)).thenReturn(filtered);

        RevisionBoundEntryPoints result = service.list(REPOSITORY_ID, REVISION, requested);

        assertThat(result.repositoryId()).isEqualTo(REPOSITORY_ID);
        assertThat(result.analyzedRevision()).isEqualTo(REVISION);
        assertThat(result.entryPoints()).isEqualTo(filtered.entryPoints());
        InOrder order = inOrder(syntaxExtractionService, discoveryFilter);
        order.verify(syntaxExtractionService).extract(REPOSITORY_ROOT);
        order.verify(discoveryFilter).filter(REPOSITORY_ID, extracted, requested);
    }

    @Test
    void should_return_exact_fixture_revision_and_empty_list_for_repository_policy_denial() {
        RepositorySnapshot snapshot = new RepositorySnapshot(
                REPOSITORY_ID, REPOSITORY_ROOT, RepositoryRevision.fixture());
        delegateSnapshot(snapshot);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(extracted);
        when(discoveryFilter.filter(
                REPOSITORY_ID, extracted, EnumSet.allOf(EntryPointType.class)))
                .thenReturn(RepositorySyntax.empty());

        RevisionBoundEntryPoints result = service.list(
                REPOSITORY_ID, RepositoryRevision.fixture(), EnumSet.allOf(EntryPointType.class));

        assertThat(result.analyzedRevision()).isEqualTo(RepositoryRevision.fixture());
        assertThat(result.entryPoints()).isEmpty();
    }

    @Test
    void should_sort_same_java_type_from_two_modules_by_repository_relative_source_file() {
        RepositorySnapshot snapshot = new RepositorySnapshot(REPOSITORY_ID, REPOSITORY_ROOT, REVISION);
        EntryPointClass moduleB = entryPointClass(
                "module-b/src/main/java/com/acme/order/OrderController.java", "moduleB");
        EntryPointClass moduleA = entryPointClass(
                "module-a/src/main/java/com/acme/order/OrderController.java", "moduleA");
        RepositorySyntax unsorted = new RepositorySyntax(List.of(moduleB, moduleA), List.of());
        delegateSnapshot(snapshot);
        when(syntaxExtractionService.extract(REPOSITORY_ROOT)).thenReturn(extracted);
        when(discoveryFilter.filter(REPOSITORY_ID, extracted, EnumSet.of(EntryPointType.API)))
                .thenReturn(unsorted);

        RevisionBoundEntryPoints result = service.list(
                REPOSITORY_ID, REVISION, EnumSet.of(EntryPointType.API));

        assertThat(result.entryPoints()).extracting(entryPoint -> entryPoint.sourceType().sourceFile())
                .containsExactly(
                        "module-a/src/main/java/com/acme/order/OrderController.java",
                        "module-b/src/main/java/com/acme/order/OrderController.java");
    }

    private void delegateSnapshot(RepositorySnapshot snapshot) {
        when(repositoryApplicationService.withSnapshot(
                eq(REPOSITORY_ID), eq(Optional.of(snapshot.revision())), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundEntryPoints> operation = invocation.getArgument(2);
                    return operation.apply(snapshot);
                });
    }

    private static EntryPointClass entryPointClass(String methodName) {
        return entryPointClass("com/acme/order/OrderController.java", methodName);
    }

    private static EntryPointClass entryPointClass(String sourceFile, String methodName) {
        return new EntryPointClass(
                new SourceTypeIdentity(new JavaTypeIdentity("com.acme.order", "OrderController"), sourceFile),
                "",
                List.of(),
                List.of(new ApiEntryPoint(
                        methodName, "", "/orders", List.of("GET"), List.of(), unresolved())));
    }

    private static MethodTargetResolution unresolved() {
        return MethodTargetResolution.unresolved("TEST_ANALYSIS_TARGET_UNAVAILABLE");
    }
}
