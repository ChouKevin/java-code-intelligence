package com.java.semantic.trie;

import com.java.semantic.identity.JavaTypeIdentity;
import com.java.semantic.identity.MethodTarget;
import com.java.semantic.identity.SourceTypeIdentity;
import com.java.semantic.repository.domain.RepositoryId;
import com.java.semantic.repository.domain.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.application.EntryPointDiscoveryFilter;
import com.java.semantic.syntax.domain.ApiEntryPoint;
import com.java.semantic.syntax.domain.EntryPointClass;
import com.java.semantic.syntax.domain.EntryPointMethod;
import com.java.semantic.syntax.domain.EntryPointType;
import com.java.semantic.syntax.domain.MethodTargetResolution;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.SyntaxExtractionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiTrieRepositorySnapshotPublicationListenerTest {

    private static final RepositoryRevision SHA_ONE = RepositoryRevision.ofSha(
            "1111111111111111111111111111111111111111");
    private static final RepositoryRevision SHA_TWO = RepositoryRevision.ofSha(
            "2222222222222222222222222222222222222222");

    @Mock
    private SyntaxExtractionService syntaxExtractionService;

    @Mock
    private EntryPointDiscoveryFilter filter;

    @Test
    void should_clear_on_mutation_and_rebuild_only_from_exact_published_snapshot() {
        ApiTrieService trie = new ApiTrieService();
        RepositorySnapshot oldSnapshot = snapshot("orders", SHA_ONE);
        RepositorySnapshot newSnapshot = snapshot("orders", SHA_TWO);
        RepositorySyntax newSyntax = syntax(route("UnfilteredController", "unfiltered", "GET", "/unfiltered"));
        RepositorySyntax filteredSyntax = syntax(route("NewController", "newRoute", "GET", "/new"));
        trie.reload(oldSnapshot, syntax(route("OldController", "old", "GET", "/old")));
        when(syntaxExtractionService.extract(newSnapshot.root())).thenReturn(newSyntax);
        when(filter.filter(
                newSnapshot.repositoryId(), newSyntax, EnumSet.of(EntryPointType.API)))
                .thenReturn(filteredSyntax);
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);

        listener.beforeMutation(newSnapshot.repositoryId());
        assertThat(refs(trie.lookupMatches("/old", "GET", ""))).isEmpty();

        listener.afterPublication(newSnapshot);

        assertThat(refs(trie.lookupMatches("/unfiltered", "GET", ""))).isEmpty();
        assertThat(refs(trie.lookupMatches("/new", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::analyzedRevision)
                .isEqualTo(SHA_TWO.value());
        verify(syntaxExtractionService).extract(newSnapshot.root());
        verify(filter).filter(
                newSnapshot.repositoryId(), newSyntax, EnumSet.of(EntryPointType.API));
    }

    @Test
    void should_clear_repository_again_before_publication_reconciliation() {
        ApiTrieService trie = new ApiTrieService();
        RepositorySnapshot orders = snapshot("orders", SHA_ONE);
        RepositorySnapshot catalog = snapshot("catalog", SHA_TWO);
        trie.reload(orders, syntax(route("OldController", "old", "GET", "/old")));
        trie.reload(catalog, syntax(route("CatalogController", "catalog", "GET", "/catalog")));
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);

        listener.beforePublication(orders.repositoryId());

        assertThat(refs(trie.lookupMatches("/old", "GET", ""))).isEmpty();
        assertThat(refs(trie.lookupMatches("/catalog", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::analyzedRevision)
                .containsExactly("catalog", SHA_TWO.value());
        verifyNoInteractions(syntaxExtractionService, filter);
    }

    @Test
    void should_leave_repository_absent_when_syntax_extraction_fails() {
        ApiTrieService trie = new ApiTrieService();
        RepositorySnapshot oldSnapshot = snapshot("orders", SHA_ONE);
        RepositorySnapshot newSnapshot = snapshot("orders", SHA_TWO);
        IllegalStateException failure = new IllegalStateException("sensitive extraction detail");
        trie.reload(oldSnapshot, syntax(route("OldController", "old", "GET", "/old")));
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);
        listener.beforePublication(newSnapshot.repositoryId());
        when(syntaxExtractionService.extract(newSnapshot.root())).thenThrow(failure);

        assertThatThrownBy(() -> listener.afterPublication(newSnapshot)).isSameAs(failure);

        assertThat(refs(trie.lookupMatches("/old", "GET", ""))).isEmpty();
        assertThat(refs(trie.lookupMatches("/new", "GET", ""))).isEmpty();
        verifyNoInteractions(filter);
    }

    @Test
    void should_leave_repository_absent_when_policy_filter_fails() {
        ApiTrieService trie = new ApiTrieService();
        RepositorySnapshot oldSnapshot = snapshot("orders", SHA_ONE);
        RepositorySnapshot newSnapshot = snapshot("orders", SHA_TWO);
        RepositorySyntax extracted = syntax(route("NewController", "newRoute", "GET", "/new"));
        IllegalStateException failure = new IllegalStateException("sensitive policy detail");
        trie.reload(oldSnapshot, syntax(route("OldController", "old", "GET", "/old")));
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);
        listener.beforePublication(newSnapshot.repositoryId());
        when(syntaxExtractionService.extract(newSnapshot.root())).thenReturn(extracted);
        when(filter.filter(
                newSnapshot.repositoryId(), extracted, EnumSet.of(EntryPointType.API)))
                .thenThrow(failure);

        assertThatThrownBy(() -> listener.afterPublication(newSnapshot)).isSameAs(failure);

        assertThat(refs(trie.lookupMatches("/old", "GET", ""))).isEmpty();
        assertThat(refs(trie.lookupMatches("/new", "GET", ""))).isEmpty();
    }

    @Test
    void should_propagate_trie_rebuild_failure_after_prepublication_clear() {
        ApiTrieService trie = mock(ApiTrieService.class);
        RepositorySnapshot snapshot = snapshot("orders", SHA_TWO);
        RepositorySyntax extracted = syntax(route("NewController", "newRoute", "GET", "/new"));
        RepositorySyntax filtered = syntax(route("AllowedController", "allowed", "GET", "/allowed"));
        IllegalStateException failure = new IllegalStateException("sensitive rebuild detail");
        when(syntaxExtractionService.extract(snapshot.root())).thenReturn(extracted);
        when(filter.filter(
                snapshot.repositoryId(), extracted, EnumSet.of(EntryPointType.API)))
                .thenReturn(filtered);
        doThrow(failure).when(trie).reload(snapshot, filtered);
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);

        listener.beforePublication(snapshot.repositoryId());

        verify(trie).clear(snapshot.repositoryId());
        assertThatThrownBy(() -> listener.afterPublication(snapshot)).isSameAs(failure);
    }

    @Test
    void should_preserve_unrelated_repository_when_one_publication_fails() {
        ApiTrieService trie = new ApiTrieService();
        RepositorySnapshot ordersOld = snapshot("orders", SHA_ONE);
        RepositorySnapshot ordersNew = snapshot("orders", SHA_TWO);
        RepositorySnapshot catalog = snapshot("catalog", SHA_ONE);
        IllegalStateException failure = new IllegalStateException("sensitive extraction detail");
        trie.reload(ordersOld, syntax(route("OldController", "old", "GET", "/shared")));
        trie.reload(catalog, syntax(route("CatalogController", "catalog", "GET", "/shared")));
        ApiTrieRepositorySnapshotPublicationListener listener = listener(trie);
        listener.beforePublication(ordersNew.repositoryId());
        when(syntaxExtractionService.extract(ordersNew.root())).thenThrow(failure);

        assertThatThrownBy(() -> listener.afterPublication(ordersNew)).isSameAs(failure);

        assertThat(refs(trie.lookupMatches("/shared", "GET", "")))
                .singleElement()
                .extracting(ApiEntryPointRef::repoId, ApiEntryPointRef::analyzedRevision)
                .containsExactly("catalog", SHA_ONE.value());
        assertThat(refs(trie.lookupMatches("/shared", "GET", "orders"))).isEmpty();
    }

    private ApiTrieRepositorySnapshotPublicationListener listener(ApiTrieService trie) {
        return new ApiTrieRepositorySnapshotPublicationListener(
                trie, syntaxExtractionService, filter);
    }

    private static List<ApiEntryPointRef> refs(ApiRouteMatchBatch batch) {
        return batch.matches().stream().map(ApiRouteMatch::ref).toList();
    }

    private static RepositorySnapshot snapshot(String repoId, RepositoryRevision revision) {
        return new RepositorySnapshot(
                RepositoryId.of(repoId),
                Path.of("/workspace", repoId).toAbsolutePath().normalize(),
                revision);
    }

    private static RepositorySyntax syntax(EntryPointClass... classes) {
        return new RepositorySyntax(List.of(classes), List.of());
    }

    private static EntryPointClass route(
            String className,
            String methodName,
            String httpMethod,
            String path) {
        List<EntryPointMethod> methods = List.of(new ApiEntryPoint(
                methodName,
                "",
                path,
                List.of(httpMethod),
                List.of(),
                MethodTargetResolution.resolved(new MethodTarget(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        "src/main/java/com/example/" + className + ".java"),
                methodName,
                List.of()))));
        return new EntryPointClass(
                new SourceTypeIdentity(
                        new JavaTypeIdentity("com.example", className),
                        "com/example/" + className + ".java"),
                "",
                List.of(),
                methods);
    }
}
