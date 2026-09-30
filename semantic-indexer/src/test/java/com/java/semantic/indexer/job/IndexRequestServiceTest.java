package com.java.semantic.indexer.job;

import com.java.semantic.indexer.repository.RepositoryRevisionResolver;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.review.ReviewSelection;
import com.java.semantic.repository.application.RepositoryNotFoundException;
import com.java.semantic.repository.application.RepositoryRuntimeRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IndexRequestServiceTest {
    @Test
    void rejects_unknown_preparation_before_resolving_git_or_accessing_jobs() {
        RepositoryRevisionResolver revisionResolver = mock(RepositoryRevisionResolver.class);
        RepositoryRuntimeRegistry repositories = mock(RepositoryRuntimeRegistry.class);
        IndexJobStore store = mock(IndexJobStore.class);
        RepositoryId unknown = RepositoryId.of("unknown");
        when(repositories.get(unknown)).thenThrow(new RepositoryNotFoundException(unknown));
        IndexRequestService service = new IndexRequestService(revisionResolver, repositories, store);
        PreparationRequestId id = new PreparationRequestId("8f899830-47bb-4dc7-a9a6-c4ad0c016bb3");

        assertThatThrownBy(() -> service.refreshRepositoryMetadata(unknown, id, Optional.empty()))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> service.prepareCodebase(unknown, id)).isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> service.prepareReview(unknown, id,
                ReviewSelection.commit(RepositoryRevision.ofSha("a".repeat(40)))))
                .isInstanceOf(RepositoryNotFoundException.class);
        assertThatThrownBy(() -> service.getJob(unknown, Optional.empty(), Optional.of(id)))
                .isInstanceOf(RepositoryNotFoundException.class);

        verifyNoInteractions(store, revisionResolver);
    }
}
