package com.java.semantic.document.application;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.ReadRequest;
import com.java.semantic.document.application.DocumentContract.ReadResult;
import com.java.semantic.document.application.DocumentContract.ListRequest;
import com.java.semantic.document.application.DocumentContract.ListResult;
import com.java.semantic.document.application.DocumentContract.SearchRequest;
import com.java.semantic.document.application.DocumentContract.SearchResult;
import com.java.semantic.document.catalog.DocumentCatalog;
import com.java.semantic.document.catalog.GitDocumentLoader;
import com.java.semantic.document.config.DocumentQueryProperties;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentOperationLifecycleTest {
    @TempDir Path root;

    @Test
    void blockedReadsHoldPermitsAndLeasesThroughCancellationAndPublicationReload() throws Exception {
        String original = "# Old guide\r\nold-only content\r\nEvidence and qualification remain exact";
        String replacement = "# New guide\nnew-only content with updated evidence";
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String firstRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, original));
            fixture.publish(Map.of("sample", firstRevision));
            DocumentQueryProperties properties = fixture.properties(CollectionPurpose.TEST_ONLY);
            DocumentResultWriter writer = new DocumentResultWriter(fixture.mapper);
            try (GitDocumentLoader loader = new GitDocumentLoader(properties.storage(), writer);
                    ExecutorService executor = Executors.newFixedThreadPool(3)) {
                DocumentCatalog catalog = new DocumentCatalog(properties.storage(), loader);
                DocumentQueryService service = new DocumentQueryService(catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate());
                ReadRequest oldRead = new ReadRequest(new DocumentContext(DocumentFixture.DOCUMENT_REPOSITORY,
                        "sample", firstRevision), DocumentFixture.GUIDE, 1);
                CountDownLatch readersEntered = new CountDownLatch(2);
                CountDownLatch firstInterrupted = new CountDownLatch(1);
                CountDownLatch releaseFirst = new CountDownLatch(1);
                CountDownLatch releaseSecond = new CountDownLatch(1);
                CountDownLatch firstUnwound = new CountDownLatch(1);
                CountDownLatch secondUnwound = new CountDownLatch(1);
                AtomicReference<DocumentFailure.Code> firstFailure = new AtomicReference<>();
                AtomicReference<DocumentFailure.Code> secondFailure = new AtomicReference<>();
                AtomicReference<Thread> reloadThread = new AtomicReference<>();
                CountDownLatch reloadStarted = new CountDownLatch(1);

                try {
                    Future<ReadResult> cancelledRead = blockedRead(executor, service, oldRead, readersEntered,
                            releaseFirst, firstInterrupted, firstUnwound, firstFailure);
                    Future<ReadResult> retainedRead = blockedRead(executor, service, oldRead, readersEntered,
                            releaseSecond, new CountDownLatch(1), secondUnwound, secondFailure);
                    assertThat(readersEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertBusy(service, oldRead);

                    assertThat(cancelledRead.cancel(true)).isTrue();
                    assertThat(firstInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertBusy(service, oldRead);
                    releaseFirst.countDown();
                    assertThat(firstUnwound.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(firstFailure.get()).isEqualTo(DocumentFailure.Code.DOCUMENT_TIMEOUT);

                    String secondRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, replacement));
                    fixture.publish(Map.of("sample", secondRevision));
                    Future<SearchResult> reloadedSearch = executor.submit(() -> {
                        reloadThread.set(Thread.currentThread());
                        reloadStarted.countDown();
                        return service.search(new SearchRequest("new-only", 1, Optional.empty(), Optional.empty()), value -> value);
                    });
                    assertThat(reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(awaitTimedWaiting(reloadThread.get())).isTrue();
                    assertBusy(service, oldRead);

                    releaseSecond.countDown();
                    ReadResult oldResult = retainedRead.get(5, TimeUnit.SECONDS);
                    assertThat(secondUnwound.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(secondFailure.get()).isNull();
                    SearchResult newResult = reloadedSearch.get(5, TimeUnit.SECONDS);
                    assertThat(oldResult.content()).isEqualTo(original);
                    assertThat(oldResult.documentContext().documentRevision()).isEqualTo(firstRevision);
                    assertThat(newResult.items().getFirst().documentContext().documentRevision()).isEqualTo(secondRevision);
                    assertThat(newResult.items()).extracting(hit -> hit.path()).containsExactly(DocumentFixture.GUIDE);
                    ReadResult recovered = service.read(new ReadRequest(newResult.items().getFirst().documentContext(), DocumentFixture.GUIDE, 1),
                            value -> value);
                    assertThat(recovered.content()).isEqualTo(replacement);
                } finally {
                    releaseFirst.countDown();
                    releaseSecond.countDown();
                }
            }
        }
    }

    @Test
    void blockedListsHoldPermitsAndCatalogLeasesThroughCancellationAndPublicationReload() throws Exception {
        String original = "# Old guide\nold listing metadata";
        String replacement = "# New guide\nupdated listing metadata";
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("list-lifecycle"))) {
            String firstRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, original));
            fixture.publish(Map.of("sample", firstRevision));
            DocumentQueryProperties properties = fixture.properties(CollectionPurpose.TEST_ONLY);
            DocumentResultWriter writer = new DocumentResultWriter(fixture.mapper);
            try (GitDocumentLoader loader = new GitDocumentLoader(properties.storage(), writer);
                    ExecutorService executor = Executors.newFixedThreadPool(3)) {
                DocumentCatalog catalog = new DocumentCatalog(properties.storage(), loader);
                DocumentQueryService service = new DocumentQueryService(catalog,
                        new DocumentCursorCodec(fixture.mapper), new DocumentOperationGate());
                ListRequest request = new ListRequest(Optional.empty(), 10, Optional.empty());
                CountDownLatch listsEntered = new CountDownLatch(2);
                CountDownLatch firstInterrupted = new CountDownLatch(1);
                CountDownLatch releaseFirst = new CountDownLatch(1);
                CountDownLatch releaseSecond = new CountDownLatch(1);
                CountDownLatch firstUnwound = new CountDownLatch(1);
                CountDownLatch secondUnwound = new CountDownLatch(1);
                AtomicReference<DocumentFailure.Code> firstFailure = new AtomicReference<>();
                AtomicReference<DocumentFailure.Code> secondFailure = new AtomicReference<>();
                AtomicReference<Thread> reloadThread = new AtomicReference<>();
                CountDownLatch reloadStarted = new CountDownLatch(1);

                try {
                    Future<ListResult> cancelledList = blockedList(executor, service, request, listsEntered,
                            releaseFirst, firstInterrupted, firstUnwound, firstFailure);
                    Future<ListResult> retainedList = blockedList(executor, service, request, listsEntered,
                            releaseSecond, new CountDownLatch(1), secondUnwound, secondFailure);
                    assertThat(listsEntered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertBusyList(service, request);

                    assertThat(cancelledList.cancel(true)).isTrue();
                    assertThat(firstInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertBusyList(service, request);
                    releaseFirst.countDown();
                    assertThat(firstUnwound.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(firstFailure.get()).isEqualTo(DocumentFailure.Code.DOCUMENT_TIMEOUT);

                    String secondRevision = fixture.commit(Map.of(DocumentFixture.GUIDE, replacement));
                    fixture.publish(Map.of("sample", secondRevision));
                    Future<ListResult> reloaded = executor.submit(() -> {
                        reloadThread.set(Thread.currentThread());
                        reloadStarted.countDown();
                        return service.list(request, value -> value);
                    });
                    assertThat(reloadStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(awaitTimedWaiting(reloadThread.get())).isTrue();
                    assertBusyList(service, request);

                    releaseSecond.countDown();
                    ListResult oldResult = retainedList.get(5, TimeUnit.SECONDS);
                    assertThat(secondUnwound.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThat(secondFailure.get()).isNull();
                    ListResult newResult = reloaded.get(5, TimeUnit.SECONDS);
                    assertThat(oldResult.items().getFirst().documentContext().documentRevision()).isEqualTo(firstRevision);
                    assertThat(newResult.items().getFirst().documentContext().documentRevision()).isEqualTo(secondRevision);
                    assertThat(service.list(request, value -> value).items().getFirst().path())
                            .isEqualTo(DocumentFixture.GUIDE);
                } finally {
                    releaseFirst.countDown();
                    releaseSecond.countDown();
                }
            }
        }
    }

    @Test
    void anExpiredDeadlineDoesNotReleaseItsPermitBeforeTheOwnerClosesIt() {
        DocumentClockFixture clock = new DocumentClockFixture();
        DocumentOperationGate operations = new DocumentOperationGate(clock::deadline);
        DocumentOperationGate.Permit first = operations.open();
        clock.expire();
        assertThatThrownBy(first.deadline()::check).isInstanceOfSatisfying(DocumentFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_TIMEOUT));

        try (DocumentOperationGate.Permit second = operations.open()) {
            assertThatThrownBy(operations::open).isInstanceOfSatisfying(DocumentFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_BUSY));
        }
        first.close();
        try (DocumentOperationGate.Permit recovered = operations.open()) {
            recovered.deadline().check();
        }
    }

    private static Future<ReadResult> blockedRead(ExecutorService executor, DocumentQueryService service,
            ReadRequest request, CountDownLatch entered, CountDownLatch release, CountDownLatch interrupted,
            CountDownLatch unwound, AtomicReference<DocumentFailure.Code> failureCode) {
        return executor.submit(() -> {
            try {
                return service.read(request, result -> {
                    entered.countDown();
                    awaitUninterruptibly(release, interrupted);
                    return result;
                });
            } catch (DocumentFailure failure) {
                failureCode.set(failure.code());
                throw failure;
            } finally {
                unwound.countDown();
            }
        });
    }

    private static void assertBusy(DocumentQueryService service, ReadRequest request) {
        assertThatThrownBy(() -> service.read(request, value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_BUSY));
    }

    private static void awaitUninterruptibly(CountDownLatch release, CountDownLatch interrupted) {
        boolean wasInterrupted = false;
        while (true) {
            try {
                release.await();
                break;
            } catch (InterruptedException exception) {
                wasInterrupted = true;
                interrupted.countDown();
            }
        }
        if (wasInterrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitTimedWaiting(Thread thread) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < end) {
            if (thread.getState() == Thread.State.TIMED_WAITING) {
                return true;
            }
            if (thread.getState() == Thread.State.TERMINATED) {
                return false;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        }
        return false;
    }
    private static Future<ListResult> blockedList(ExecutorService executor, DocumentQueryService service,
            ListRequest request, CountDownLatch entered, CountDownLatch release, CountDownLatch interrupted,
            CountDownLatch unwound, AtomicReference<DocumentFailure.Code> failureCode) {
        return executor.submit(() -> {
            try {
                return service.list(request, result -> {
                    entered.countDown();
                    awaitUninterruptibly(release, interrupted);
                    return result;
                });
            } catch (DocumentFailure failure) {
                failureCode.set(failure.code());
                throw failure;
            } finally {
                unwound.countDown();
            }
        });
    }

    private static void assertBusyList(DocumentQueryService service, ListRequest request) {
        assertThatThrownBy(() -> service.list(request, value -> value)).isInstanceOfSatisfying(DocumentFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_BUSY));
    }
}
