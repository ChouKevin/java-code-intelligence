package com.java.semantic.syntax.application;

import com.java.semantic.repository.application.RepositoryApplicationService;
import com.java.semantic.repository.application.RepositoryRevisionMismatchException;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.repository.domain.RepositorySnapshot;
import com.java.semantic.syntax.domain.RepositorySyntax;
import com.java.semantic.syntax.domain.RevisionBoundRepositorySyntaxProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventListenerDiscoveryApplicationServiceTest {

    @TempDir
    private Path root;

    @Mock
    private RepositoryApplicationService repositories;
    @Mock
    private RevisionBoundRepositorySyntaxProvider repositorySyntaxProvider;
    @Mock
    private EventListenerDiscoveryPolicy policy;

    @Test
    void should_extract_once_inside_the_expected_revision_snapshot_and_return_exact_snapshot_revision() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision expectedRevision = RepositoryRevision.ofSha("0".repeat(40));
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, expectedRevision);
        EventListenerDiscoveryQuery query = new EventListenerDiscoveryQuery(
                repositoryId, expectedRevision, "com.acme.OrderPlaced", 0, 50);
        EventListenerDiscoveryPage page = EventListenerDiscoveryPage.empty(0, 50);
        when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(expectedRevision)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundEventListenerDiscovery> callback = invocation.getArgument(2);
                    return callback.apply(snapshot);
                });
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(RepositorySyntax.empty());
        when(policy.discover(RepositorySyntax.empty(), "com.acme.OrderPlaced", 0, 50)).thenReturn(page);
        EventListenerDiscoveryApplicationService service = new EventListenerDiscoveryApplicationService(
                repositories, repositorySyntaxProvider, policy);

        RevisionBoundEventListenerDiscovery result = service.discover(query);

        assertThat(result.repositoryId()).isEqualTo(repositoryId);
        assertThat(result.analyzedRevision()).isEqualTo(expectedRevision);
        assertThat(result.discovery()).isSameAs(page);
        InOrder order = inOrder(repositories, repositorySyntaxProvider, policy);
        order.verify(repositories).withSnapshot(eq(repositoryId), eq(Optional.of(expectedRevision)), any());
        order.verify(repositorySyntaxProvider).get(snapshot);
        order.verify(policy).discover(RepositorySyntax.empty(), "com.acme.OrderPlaced", 0, 50);
        verify(repositorySyntaxProvider).get(snapshot);
    }

    @Test
    void should_propagate_repository_revision_mismatch_without_parsing() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision expectedRevision = RepositoryRevision.ofSha("0".repeat(40));
        EventListenerDiscoveryQuery query = new EventListenerDiscoveryQuery(
                repositoryId, expectedRevision, "com.acme.OrderPlaced", 0, 50);
        RepositoryRevisionMismatchException mismatch = new RepositoryRevisionMismatchException(
                expectedRevision, RepositoryRevision.ofSha("0123456789012345678901234567890123456789"));
        when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(expectedRevision)), any())).thenThrow(mismatch);
        EventListenerDiscoveryApplicationService service = new EventListenerDiscoveryApplicationService(
                repositories, repositorySyntaxProvider, policy);

        assertThatThrownBy(() -> service.discover(query)).isSameAs(mismatch);
        verifyNoInteractions(repositorySyntaxProvider, policy);
    }

    @Test
    void should_request_revision_bound_syntax_for_each_query_and_keep_empty_results_bound_to_the_requested_event_type() {
        RepositoryId repositoryId = RepositoryId.of("orders");
        RepositoryRevision revision = RepositoryRevision.ofSha("0".repeat(40));
        RepositorySnapshot snapshot = new RepositorySnapshot(repositoryId, root, revision);
        EventListenerDiscoveryQuery orderQuery = new EventListenerDiscoveryQuery(
                repositoryId, revision, "com.acme.OrderPlaced", 0, 50);
        EventListenerDiscoveryQuery paymentQuery = new EventListenerDiscoveryQuery(
                repositoryId, revision, "com.acme.PaymentCaptured", 0, 50);
        when(repositories.withSnapshot(eq(repositoryId), eq(Optional.of(revision)), any()))
                .thenAnswer(invocation -> {
                    Function<RepositorySnapshot, RevisionBoundEventListenerDiscovery> callback = invocation.getArgument(2);
                    return callback.apply(snapshot);
                });
        when(repositorySyntaxProvider.get(snapshot)).thenReturn(RepositorySyntax.empty());
        when(policy.discover(RepositorySyntax.empty(), "com.acme.OrderPlaced", 0, 50))
                .thenReturn(EventListenerDiscoveryPage.empty(0, 50));
        when(policy.discover(RepositorySyntax.empty(), "com.acme.PaymentCaptured", 0, 50))
                .thenReturn(EventListenerDiscoveryPage.empty(0, 50));
        EventListenerDiscoveryApplicationService service = new EventListenerDiscoveryApplicationService(
                repositories, repositorySyntaxProvider, policy);

        RevisionBoundEventListenerDiscovery orderResult = service.discover(orderQuery);
        RevisionBoundEventListenerDiscovery paymentResult = service.discover(paymentQuery);

        assertThat(orderResult.requestedEventType()).isEqualTo("com.acme.OrderPlaced");
        assertThat(paymentResult.requestedEventType()).isEqualTo("com.acme.PaymentCaptured");
        verify(repositorySyntaxProvider, times(2)).get(snapshot);
    }
}
