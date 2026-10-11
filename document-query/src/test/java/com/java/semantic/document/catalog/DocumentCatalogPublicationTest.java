package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.support.DocumentFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class DocumentCatalogPublicationTest {
    @TempDir Path root;

    private DocumentDeadline deadline() { return DocumentDeadline.after(Duration.ofSeconds(5)); }

    @Test
    void exactGitMembershipSurvivesDirtyTreeAndRejectsBrokenUpdate() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# VIP\r\n規則 A\r\n"));
            fixture.publish(d1, d1);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "dirty secret");
            Files.writeString(fixture.root.resolve("not-listed.md"), "not published");
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d1);
                    assertThat(lease.view().revisions().get(d1).documents()).containsOnlyKeys(DocumentFixture.GUIDE);
                    assertThat(lease.view().revisions().get(d1).documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# VIP\r\n規則 A\r\n");
                }
                String d2 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# VIP\n規則 B"));
                fixture.publish(d2, d1, d2);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d2);
                    assertThat(lease.view().revisions().get(d1).documents().get(DocumentFixture.GUIDE).content()).contains("規則 A");
                }
                Files.writeString(fixture.publication, "{broken");
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d2);
                    assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
                }
                fixture.publish(d2, d2);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().revisions()).containsOnlyKeys(d2);
                    assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.READY);
                }
            }
        }
    }

    @Test
    void invalidPublicationCannotBootstrapAndDraftCannotEnterFormalCollection() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# VIP\nfixture"));
            fixture.publish(d1, d1);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.APPROVED).storage(), new DocumentResultWriter(fixture.mapper))) {
                assertThatThrownBy(() -> new DocumentCatalog(fixture.properties(CollectionPurpose.APPROVED).storage(), loader))
                        .isInstanceOf(DocumentFailure.class);
            }
            Files.writeString(fixture.publication, "{}");
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                assertThatThrownBy(() -> new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader))
                        .isInstanceOf(DocumentFailure.class);
            }
        }
    }

    @Test
    void invalidBlobAndMetadataRejectCandidateWithoutReplacingReadableVersion() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Stable\nkept"));
            fixture.publish(d1, d1);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                for (byte[] invalid : List.of(new byte[]{(byte) 0xc3, 0x28}, new byte[]{0}, new byte[0], "x".repeat(65_537).getBytes(StandardCharsets.UTF_8))) {
                    String bad = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, invalid), List.of(fixture.row(DocumentFixture.GUIDE, invalid)));
                    fixture.publish(bad, bad);
                    try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                        assertThat(lease.view().activeRevision()).isEqualTo(d1);
                        assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
                        assertThat(lease.view().revisions().get(d1).documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# Stable\nkept");
                    }
                }
                byte[] content = "# Changed\nnot admitted".getBytes(StandardCharsets.UTF_8);
                Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, content);
                row.put("contentDigest", "0".repeat(64));
                String badDigest = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(row));
                fixture.publish(badDigest, badDigest);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d1);
                    assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
                }
            }
        }
    }

    @Test
    void activeReadLeasePreventsReplacementAndWaitingDeadlineDoesNotActivate() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\nold"));
            fixture.publish(d1, d1);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper));
                    ExecutorService executor = Executors.newSingleThreadExecutor()) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String d2 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# B\nnew"));
                CountDownLatch entered = new CountDownLatch(1);
                try (DocumentCatalog.ReadLease old = catalog.open(deadline())) {
                    fixture.publish(d2, d1, d2);
                    Future<String> waiting = executor.submit(() -> {
                        entered.countDown();
                        try (DocumentCatalog.ReadLease changed = catalog.open(DocumentDeadline.after(Duration.ofMillis(100)))) {
                            return changed.view().activeRevision();
                        }
                    });
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(DocumentFailure.class)
                            .satisfies(error -> assertThat(((DocumentFailure) error.getCause()).code()).isEqualTo(DocumentFailure.Code.DOCUMENT_TIMEOUT));
                    assertThat(old.view().activeRevision()).isEqualTo(d1);
                    assertThat(old.view().revisions().get(d1).documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# A\nold");
                }
                try (DocumentCatalog.ReadLease updated = catalog.open(deadline())) {
                    assertThat(updated.view().activeRevision()).isEqualTo(d2);
                    assertThat(updated.view().revisions().get(d2).documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# B\nnew");
                }
            }
        }
    }

    @Test
    void admittedEscapedDocumentFitsTheSameCompleteWireWriterUsedByReads() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String text = "# 中文\n" + "\"\\\t\r\n".repeat(8_000);
            String revision = fixture.commit(Map.of(DocumentFixture.GUIDE, text));
            fixture.publish(revision, revision);
            DocumentResultWriter writer = new DocumentResultWriter(fixture.mapper);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), writer)) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    DocumentCatalog.Document document = lease.view().revisions().get(revision).documents().get(DocumentFixture.GUIDE);
                    DocumentContract.ReadResult result = document.readResult(lease.view().context(revision), document.sections().getFirst().section(),
                            CollectionPurpose.TEST_ONLY, PublicationStatus.READY, Optional.empty());
                    assertThat(result.content()).isEqualTo(text);
                    assertThat(fixture.mapper.writeValueAsBytes(writer.success(result)).length).isLessThanOrEqualTo(512 * 1024);
                }
            }
        }
    }

    @Test
    void unchangedRejectedSelectionRecoversWhenItsMissingObjectArrivesAndRestartsExactly() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\nretained"));
            fixture.publish(d1, d1);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String d2 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# B\narriving"));
                Path object = fixture.root.resolve(".git/objects").resolve(d2.substring(0, 2)).resolve(d2.substring(2));
                Path withheld = root.resolve("withheld-object");
                Files.move(object, withheld);
                fixture.publish(d2, d1, d2);
                byte[] selection = Files.readAllBytes(fixture.publication);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d1);
                    assertThat(lease.view().publicationStatus()).isEqualTo(PublicationStatus.UPDATE_REJECTED);
                }
                Files.move(withheld, object);
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d2);
                    assertThat(lease.view().revisions().get(d2).documents().get(DocumentFixture.GUIDE).content()).isEqualTo("# B\narriving");
                }
                assertThat(Files.readAllBytes(fixture.publication)).isEqualTo(selection);
                DocumentCatalog restarted = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                try (DocumentCatalog.ReadLease lease = restarted.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d2);
                    assertThat(lease.view().revisions()).containsOnlyKeys(d1, d2);
                }
            }
        }
    }

    @Test
    void listedSymlinkNeverBecomesReadableMarkdown() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            byte[] target = "unlisted.md".getBytes(StandardCharsets.UTF_8);
            Path guide = fixture.root.resolve(DocumentFixture.GUIDE);
            Files.createDirectories(guide.getParent());
            Files.createSymbolicLink(guide, Path.of("unlisted.md"));
            String revision = fixture.commitBytes(Map.of("repositories/sample/unlisted.md", "secret".getBytes(StandardCharsets.UTF_8)),
                    List.of(fixture.row(DocumentFixture.GUIDE, target)));
            fixture.publish(revision, revision);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                assertThatThrownBy(() -> new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_CATALOG_INVALID));
            }
        }
    }

    @Test
    void oversizedEncodedReadCannotEnterAnOtherwiseValidCatalog() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            byte[] content = "\\".repeat(65_536).getBytes(StandardCharsets.UTF_8);
            Map<String, Object> row = fixture.row(DocumentFixture.GUIDE, content);
            row.put("reviewReference", "\\".repeat(40_000));
            String revision = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, content), List.of(row));
            fixture.publish(revision, revision);
            try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper))) {
                assertThatThrownBy(() -> new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader))
                        .isInstanceOfSatisfying(DocumentFailure.class,
                                failure -> assertThat(failure.code()).isEqualTo(DocumentFailure.Code.DOCUMENT_CAPACITY_EXCEEDED));
            }
        }
    }

    @Test
    void selectionChangingDuringRealCandidateValidationRejectsCandidateAndReadersCannotBypassWriter() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(root.resolve("repo"))) {
            String d1 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\nfirst"));
            fixture.publish(d1, d1);
            try (GitDocumentLoader loader = spy(new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), new DocumentResultWriter(fixture.mapper)));
                    ExecutorService executor = Executors.newFixedThreadPool(2)) {
                DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                String d2 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# B\nsecond"));
                String d3 = fixture.commit(Map.of(DocumentFixture.GUIDE, "# C\nthird"));
                CountDownLatch candidateLoaded = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                doAnswer(invocation -> {
                    Object snapshot = invocation.callRealMethod();
                    candidateLoaded.countDown();
                    assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                    return snapshot;
                }).when(loader).load(eq(d2), any(DocumentDeadline.class), anyLong(), anyInt());
                fixture.publish(d2, d1, d2);
                Future<String> writer = executor.submit(() -> {
                    try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                        return lease.view().activeRevision() + " " + lease.view().publicationStatus();
                    }
                });
                try {
                    assertThat(candidateLoaded.await(2, TimeUnit.SECONDS)).isTrue();
                    Future<String> blocked = executor.submit(() -> {
                        try (DocumentCatalog.ReadLease lease = catalog.open(DocumentDeadline.after(Duration.ofMillis(100)))) {
                            return lease.view().activeRevision();
                        }
                    });
                    assertThatThrownBy(() -> blocked.get(2, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(DocumentFailure.class)
                            .satisfies(error -> assertThat(((DocumentFailure) error.getCause()).code()).isEqualTo(DocumentFailure.Code.DOCUMENT_TIMEOUT));
                    fixture.publish(d3, d1, d3);
                } finally { release.countDown(); }
                assertThat(writer.get(2, TimeUnit.SECONDS)).isEqualTo(d1 + " UPDATE_REJECTED");
                try (DocumentCatalog.ReadLease lease = catalog.open(deadline())) {
                    assertThat(lease.view().activeRevision()).isEqualTo(d3);
                    assertThat(lease.view().revisions()).containsOnlyKeys(d1, d3);
                }
            }
        }
    }
}
