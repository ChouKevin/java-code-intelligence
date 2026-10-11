package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentClockFixture;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class DocumentCatalogLifecycleTest {
    @TempDir Path root;
    private DocumentDeadline deadline() { return DocumentDeadline.after(Duration.ofSeconds(5)); }

    @Test
    void successfulUpdatesWaitForOldLeasesAndThirdPublicationCannotConstructBehindSecondLease() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            byte[] a = "# A\nfirst".getBytes(StandardCharsets.UTF_8);
            byte[] b = "# B\nunchanged".getBytes(StandardCharsets.UTF_8);
            String bPath = "repositories/b/guide.md";
            String first = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, a, bPath, b),
                    List.of(fixture.row(DocumentFixture.GUIDE, a), fixture.row(bPath, b, new SourceContext("b", DocumentFixture.SOURCE_REVISION))));
            fixture.publish(Map.of("sample", first, "b", first));
            try (GitDocumentLoader loader = spy(new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper)));
                    ExecutorService executor = Executors.newFixedThreadPool(2)) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# B\nsecond"));
                String third = fixture.commit(Map.of(DocumentFixture.GUIDE, "# C\nthird"));
                CountDownLatch secondRequested = new CountDownLatch(1);
                CountDownLatch secondLeased = new CountDownLatch(1);
                CountDownLatch releaseSecond = new CountDownLatch(1);
                CountDownLatch thirdRequested = new CountDownLatch(1);
                CountDownLatch thirdConstructed = new CountDownLatch(1);
                doAnswer(invocation -> {
                    thirdConstructed.countDown();
                    return invocation.callRealMethod();
                }).when(loader).load(eq(third), any(), any(DocumentDeadline.class), anyInt(), anyLong(), anyInt());
                Future<String> secondRead;
                try (DocumentCatalog.ReadLease old = catalog.open(deadline())) {
                    fixture.publish(Map.of("sample", second, "b", first));
                    secondRead = executor.submit(() -> {
                        secondRequested.countDown();
                        try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                            secondLeased.countDown();
                            assertThat(releaseSecond.await(3, TimeUnit.SECONDS)).isTrue();
                            assertThat(lease.view().repositories().get("b").context().documentRevision()).isEqualTo(first);
                            assertThat(lease.view().repositories().get("b").documents().get(bPath).content()).isEqualTo("# B\nunchanged");
                            return lease.view().repositories().get("sample").context().documentRevision() + ":" + lease.view().repositories().get("sample").documents().get(DocumentFixture.GUIDE).content();
                        }
                    });
                    assertThat(secondRequested.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(old.view().repositories().get("sample").context().documentRevision()).isEqualTo(first);
                    assertThat(old.view().repositories().get("sample").documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# A\nfirst");
                    assertThat(old.view().repositories().get("b").documents().get(bPath).content()).isEqualTo("# B\nunchanged");
                    assertThat(secondLeased.getCount()).isEqualTo(1);
                }
                assertThat(secondLeased.await(2, TimeUnit.SECONDS)).isTrue();
                fixture.publish(Map.of("sample", third, "b", first));
                Future<String> thirdRead = executor.submit(() -> {
                    thirdRequested.countDown();
                    try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                        assertThat(lease.view().repositories().get("b").context().documentRevision()).isEqualTo(first);
                        return lease.view().repositories().get("sample").context().documentRevision() + ":" + lease.view().repositories().get("sample").documents().get(DocumentFixture.GUIDE).content();
                    }
                });
                try {
                    assertThat(thirdRequested.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThat(thirdConstructed.await(100, TimeUnit.MILLISECONDS)).isFalse();
                } finally { releaseSecond.countDown(); }
                assertThat(secondRead.get(2, TimeUnit.SECONDS)).isEqualTo(second + ":# B\nsecond");
                assertThat(thirdRead.get(2, TimeUnit.SECONDS)).isEqualTo(third + ":# C\nthird");
                assertThat(thirdConstructed.getCount()).isZero();
            }
        }
    }

    @Test
    void expiryDuringSectionValidationCannotActivateAndInvalidRestartCannotRecoverOnlyFromMemory() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            byte[] a = "# A\nfirst".getBytes(StandardCharsets.UTF_8);
            byte[] b = "# B\nunchanged".getBytes(StandardCharsets.UTF_8);
            String bPath = "repositories/b/guide.md";
            String first = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, a, bPath, b),
                    List.of(fixture.row(DocumentFixture.GUIDE, a), fixture.row(bPath, b, new SourceContext("b", DocumentFixture.SOURCE_REVISION))));
            fixture.publish(Map.of("sample", first, "b", first));
            DocumentResultWriter writer = spy(new DocumentResultWriter(fixture.mapper));
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), writer)) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String second = fixture.commit(Map.of(DocumentFixture.GUIDE, "# B\n# C\n# D\n"));
                DocumentClockFixture clock = new DocumentClockFixture();
                doAnswer(invocation -> {
                    Object result = invocation.callRealMethod();
                    clock.expire();
                    return result;
                }).when(writer).sectionWireBytes(argThat(section -> section.startLine() == 2));
                fixture.publish(Map.of("sample", second, "b", first));
                assertThatThrownBy(() -> catalog.open(clock.deadline())).isInstanceOfSatisfying(DocumentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo(Code.DOCUMENT_TIMEOUT));
                Files.writeString(fixture.publication, "{broken");
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().repositories().get("sample").context().documentRevision()).isEqualTo(first);
                    assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
                    assertThat(lease.view().repositories().get("sample").documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# A\nfirst");
                    assertThat(lease.view().repositories()).containsOnlyKeys("sample", "b");
                    assertThat(lease.view().repositories().get("b").context().documentRevision()).isEqualTo(first);
                    assertThat(lease.view().repositories().get("b").documents().get(bPath).content()).isEqualTo("# B\nunchanged");
                }
                assertThatThrownBy(() -> new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(Code.DOCUMENT_CATALOG_INVALID));
            }
        }
    }
}
