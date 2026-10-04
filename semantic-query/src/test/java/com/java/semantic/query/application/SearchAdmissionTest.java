package com.java.semantic.query.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.query.source.AdmittedSourceRevision;
import com.java.semantic.query.source.RepositorySourcePort;
import com.java.semantic.query.source.SourceQueryException;
import com.java.semantic.query.source.SourceRevisionCatalog;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearchAdmissionTest {
    @Test
    void searches_reserve_capacity_before_admission_and_release_it_after_failure_and_exit() throws Exception {
        SourceContext context = new SourceContext("sample", "0123456789abcdef0123456789abcdef01234567");
        TextSearchRequest request = new TextSearchRequest(context, "needle", "", Optional.empty(), 1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch firstExit = new CountDownLatch(1);
        CountDownLatch secondExit = new CountDownLatch(1);
        CountDownLatch fourthEntered = new CountDownLatch(1);
        AtomicInteger admissions = new AtomicInteger();
        SourceRevisionCatalog catalog = new SourceRevisionCatalog() {
            @Override public RepositoryCollection listRepositories(RepositoryRequest value) { throw new UnsupportedOperationException(); }
            @Override public ContextResult getContext(ContextRequest value) { throw new UnsupportedOperationException(); }
            @Override public AdmittedSourceRevision admit(SourceContext value) {
                int number = admissions.incrementAndGet();
                if (number <= 2) entered.countDown();
                if (number == 1) firstEntered.countDown();
                if (number > 2) fourthEntered.countDown();
                try {
                    if (!(number == 1 ? firstExit : secondExit).await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("admission was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                if (number == 1) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
                throw new SourceQueryException(SourceQueryException.Code.REPOSITORY_NOT_FOUND);
            }
        };
        RepositorySourcePort source = new RepositorySourcePort() {
            @Override public FileCollection listFiles(AdmittedSourceRevision admitted, FileListRequest value) { throw new UnsupportedOperationException(); }
            @Override public TextSearchResult searchText(AdmittedSourceRevision admitted, TextSearchRequest value) {
                throw new AssertionError("admission must fail before search");
            }
            @Override public SourceResult readSource(AdmittedSourceRevision admitted, ReadSourceRequest value) { throw new UnsupportedOperationException(); }
        };
        SemanticQueryFacade facade = new SemanticQueryFacade(catalog, source, 2);
        AtomicReference<SourceQueryException> firstError = new AtomicReference<>();
        AtomicReference<SourceQueryException> secondError = new AtomicReference<>();
        AtomicReference<SourceQueryException> fourthError = new AtomicReference<>();
        Thread first = Thread.ofVirtual().start(() -> capture(facade, request, firstError));
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
        Thread second = Thread.ofVirtual().start(() -> capture(facade, request, secondError));
        Thread fourth = null;
        AtomicReference<Throwable> thirdError = new AtomicReference<>();
        Thread third = null;
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            third = Thread.ofVirtual().start(() -> {
                try { facade.searchText(request); }
                catch (Throwable exception) { thirdError.set(exception); }
            });
            third.join(500);
            assertThat(third.isAlive()).isFalse();
            assertThat(thirdError.get()).isInstanceOfSatisfying(SourceQueryException.class,
                    error -> assertThat(error.code()).isEqualTo(SourceQueryException.Code.SOURCE_BUSY));
            assertThat(admissions.get()).isEqualTo(2);
            firstExit.countDown();
            first.join(2000);
            assertThat(first.isAlive()).isFalse();
            assertThat(firstError.get().code()).isEqualTo(SourceQueryException.Code.SOURCE_UNAVAILABLE);
            fourth = Thread.ofVirtual().start(() -> capture(facade, request, fourthError));
            assertThat(fourthEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(admissions.get()).isEqualTo(3);
        } finally {
            firstExit.countDown();
            secondExit.countDown();
            if (third != null) third.join(3000);
            first.join(3000);
            second.join(3000);
            if (fourth != null) fourth.join(3000);
        }
        assertThat(second.isAlive()).isFalse();
        assertThat(secondError.get().code()).isEqualTo(SourceQueryException.Code.REPOSITORY_NOT_FOUND);
        assertThat(fourthError.get().code()).isEqualTo(SourceQueryException.Code.REPOSITORY_NOT_FOUND);
    }

    private static void capture(SemanticQueryFacade facade, TextSearchRequest request,
            AtomicReference<SourceQueryException> outcome) {
        try { facade.searchText(request); }
        catch (SourceQueryException exception) { outcome.set(exception); }
    }
}
