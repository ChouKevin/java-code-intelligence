package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.document.config.DocumentQueryProperties;
import com.java.semantic.document.support.DocumentFixture;
import com.java.semantic.model.source.SourceContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Optional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Constants;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentLocalPublicationTest {
    @TempDir Path temporary;
    @Test void successiveUpdatesKeepGitHistoryWithoutRetentionAdmission() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("successive-updates"))) {
            DocumentLocalPublisherTest.identity(fixture);
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            String original;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Revision 0\n");
                LocalDocumentPublisher.Result first = publisher.update(DocumentLocalPublisherTest.request());
                assertTrue(first.commitCreated());
                original = first.candidateRevision().orElseThrow();
                for (int i = 1; i < 10; i++) {
                    Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Revision " + i + "\n");
                    LocalDocumentPublisher.Result updated = publisher.update(DocumentLocalPublisherTest.request());
                    assertTrue(updated.commitCreated());
                    assertEquals(updated.candidateRevision().orElseThrow(), fixture.git.getRepository().resolve("HEAD").name());
                }
            }
            assertArrayEquals("# Revision 0\n".getBytes(StandardCharsets.UTF_8),
                    DocumentLocalPublisherTest.blob(fixture, original, DocumentFixture.GUIDE));
            assertEquals("# Revision 9\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
        }
    }

    @Test void updateAndRemovalPreserveAuthorizedPosixAccessAndCatalogExecutableMode() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView.class));
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("access"))) {
            DocumentLocalPublisherTest.identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            Path catalog = fixture.root.resolve("navigation-catalog.json");
            Files.setPosixFilePermissions(catalog, PosixFilePermissions.fromString("rwxr-x---"));
            fixture.git.add().addFilepattern("navigation-catalog.json").call();
            String a = fixture.git.commit().setMessage("executable catalog").call().name();
            fixture.publish(Map.of("sample", a));
            Files.setPosixFilePermissions(fixture.publication, PosixFilePermissions.fromString("rw-r-----"));
            PosixFileAttributes selectionAccess = Files.readAttributes(fixture.publication, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes catalogAccess = Files.readAttributes(catalog, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentPublisher.Result result = publisher.update(DocumentLocalPublisherTest.request());
                assertTrue(result.commitCreated());
                assertNotEquals(a, result.activeRevisions().get("sample"));
                assertPosixAccess(selectionAccess, fixture.publication);
                assertPosixAccess(catalogAccess, catalog);
                publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty()));
                assertTrue(publisher.status().activeRevisions().isEmpty());
                assertPosixAccess(selectionAccess, fixture.publication);
                assertPosixAccess(catalogAccess, catalog);
            }
        }
    }
    @Test void initialPublicationAndCatalogRemainOwnerOnlyOnPosix() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView.class));
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("initial-access"))) {
            DocumentLocalPublisherTest.identity(fixture);
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# First\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                publisher.update(DocumentLocalPublisherTest.request());
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(fixture.publication));
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(fixture.root.resolve("navigation-catalog.json")));
            }
        }
    }
    @Test void updateAndRemovalPreserveSupportedFilesystemAcl() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(AclFileAttributeView.class));
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("acl-access"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(Map.of("sample", a));
            Path catalog = fixture.root.resolve("navigation-catalog.json");
            AclFileAttributeView selectionView = Files.getFileAttributeView(fixture.publication, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            AclFileAttributeView catalogView = Files.getFileAttributeView(catalog, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            AclEntry explicitRead = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(selectionView.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.READ_ATTRIBUTES).build();
            List<AclEntry> provisioned = new java.util.ArrayList<>(selectionView.getAcl());
            provisioned.add(0, explicitRead);
            selectionView.setAcl(provisioned);
            catalogView.setAcl(provisioned);
            List<AclEntry> selectionAcl = List.copyOf(selectionView.getAcl());
            List<AclEntry> catalogAcl = List.copyOf(catalogView.getAcl());
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                publisher.update(DocumentLocalPublisherTest.request());
                assertEquals(selectionAcl, selectionView.getAcl());
                assertEquals(catalogAcl, catalogView.getAcl());
                String selected = publisher.status().activeRevisions().get("sample");
                byte[] publication = Files.readAllBytes(fixture.publication);
                if (supportsRemoval(fixture.root.resolve(DocumentFixture.GUIDE))) {
                    publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty()));
                    assertTrue(publisher.status().activeRevisions().isEmpty());
                    assertEquals(selectionAcl, selectionView.getAcl());
                    assertEquals(catalogAcl, catalogView.getAcl());
                } else {
                    LocalDocumentPublisher.Failure refusal = assertThrows(LocalDocumentPublisher.Failure.class,
                            () -> publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty())));
                    assertTrue(refusal.kind() == LocalDocumentPublisher.FailureKind.IO || refusal.kind() == LocalDocumentPublisher.FailureKind.BUSY);
                    assertArrayEquals(publication, Files.readAllBytes(fixture.publication));
                    assertEquals(Map.of("sample", selected), publisher.status().activeRevisions());
                    assertEquals("# B\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
                    assertArrayEquals("# B\n".getBytes(StandardCharsets.UTF_8),
                            DocumentLocalPublisherTest.blob(fixture, selected, DocumentFixture.GUIDE));
                    assertEquals(selectionAcl, selectionView.getAcl());
                    assertEquals(catalogAcl, catalogView.getAcl());
                }
            }
        }
    }
    private static boolean supportsRemoval(Path file) throws IOException {
        Path parent = file.getParent();
        if (!Files.getFileStore(parent).supportsFileAttributeView(PosixFileAttributeView.class)) { return false; }
        Object fileKey = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        Object parentKey = Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (Objects.isNull(fileKey) || Objects.isNull(parentKey)
                || !fileKey.equals(Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())
                || !parentKey.equals(Files.readAttributes(parent, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey())) { return false; }
        for (Path directory = parent; Objects.nonNull(directory); directory = directory.getParent()) {
            try (DirectoryStream<Path> opened = Files.newDirectoryStream(directory)) {
                if (!(opened instanceof SecureDirectoryStream<?>)) { return false; }
            }
        }
        return true;
    }

    @Test void freshSelectionCutoverPublishesCompleteUnchangedApprovedOwnersWithForwardIntentCommits() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("unchanged-cutover"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String aGuide = "repositories/a/guide.md";
            String aOther = "repositories/a/other.md";
            String bGuide = "repositories/b/guide.md";
            Map<String, byte[]> documents = Map.of(aGuide, "# A\r\nunchanged\r\n".getBytes(StandardCharsets.UTF_8),
                    aOther, "# A other\n".getBytes(StandardCharsets.UTF_8), bGuide, "# B\nunchanged\n".getBytes(StandardCharsets.UTF_8));
            String review = "legacy approved owner groups";
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : documents.entrySet()) {
                String owner = entry.getKey().split("/")[1];
                Map<String, Object> row = fixture.row(entry.getKey(), entry.getValue(), new SourceContext(owner, DocumentFixture.SOURCE_REVISION));
                row.put("reviewStatus", "APPROVED"); row.put("reviewReference", review);
                rows.add(row);
            }
            String original = fixture.commitBytes(documents, rows);
            byte[] legacySelection = fixture.mapper.writeValueAsBytes(Map.of("formatVersion", 1,
                    "documentRepositoryId", DocumentFixture.DOCUMENT_REPOSITORY, "activeRevision", original, "retainedRevisions", List.of()));
            Files.write(fixture.publication, legacySelection);
            Path fresh = fixture.publication.resolveSibling("fresh-publication.json");
            DocumentQueryProperties properties = new DocumentQueryProperties(DocumentFixture.DOCUMENT_REPOSITORY,
                    fixture.root.resolve(".git"), fresh, CollectionPurpose.APPROVED, "fixture-read-token");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(properties.storage())) {
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(new LocalDocumentPublisher.UpdateRequest(
                        new SourceContext("a", DocumentFixture.SOURCE_REVISION), List.of(), List.of("guide.md"), Optional.of(review))));
                assertEquals(original, fixture.git.getRepository().resolve("HEAD").name());
                assertFalse(Files.exists(fresh));
                assertArrayEquals(legacySelection, Files.readAllBytes(fixture.publication));
                LocalDocumentPublisher.Result a = publisher.update(new LocalDocumentPublisher.UpdateRequest(
                        new SourceContext("a", DocumentFixture.SOURCE_REVISION), List.of(), List.of("guide.md", "other.md"), Optional.of(review)));
                String aRevision = a.activeRevisions().get("a");
                assertTrue(a.commitCreated()); assertTrue(a.selectionChanged());
                assertEquals(Map.of("a", aRevision), a.activeRevisions());
                LocalDocumentPublisher.Result b = publisher.update(new LocalDocumentPublisher.UpdateRequest(
                        new SourceContext("b", DocumentFixture.SOURCE_REVISION), List.of(), List.of("guide.md"), Optional.of(review)));
                String bRevision = b.activeRevisions().get("b");
                assertTrue(b.commitCreated()); assertTrue(b.selectionChanged());
                assertEquals(Map.of("a", aRevision, "b", bRevision), b.activeRevisions());
                try (RevWalk walk = new RevWalk(fixture.git.getRepository())) {
                    assertEquals(original, walk.parseCommit(ObjectId.fromString(aRevision)).getParent(0).name());
                    assertEquals(aRevision, walk.parseCommit(ObjectId.fromString(bRevision)).getParent(0).name());
                    ObjectId originalTree = walk.parseCommit(ObjectId.fromString(original)).getTree().getId();
                    assertEquals(originalTree, walk.parseCommit(ObjectId.fromString(aRevision)).getTree().getId());
                    assertEquals(originalTree, walk.parseCommit(ObjectId.fromString(bRevision)).getTree().getId());
                }
                try (GitDocumentLoader loader = new GitDocumentLoader(properties.storage(), new DocumentResultWriter(fixture.mapper))) {
                    DocumentCatalog catalog = new DocumentCatalog(properties.storage(), loader);
                    try (DocumentCatalog.ReadLease lease = catalog.open(DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT))) {
                        assertEquals(aRevision, lease.view().repositories().get("a").context().documentRevision());
                        assertEquals(bRevision, lease.view().repositories().get("b").context().documentRevision());
                        assertEquals(3, lease.view().documents().size());
                    }
                }
                for (Map.Entry<String, byte[]> entry : documents.entrySet()) {
                    assertArrayEquals(entry.getValue(), DocumentLocalPublisherTest.blob(fixture, original, entry.getKey()));
                    assertArrayEquals(entry.getValue(), Files.readAllBytes(fixture.root.resolve(entry.getKey())));
                }
                assertArrayEquals(legacySelection, Files.readAllBytes(fixture.publication));
            }
        }
    }

    @Test void matchingPendingFirstPublicationReusesItsForwardIntentCommit() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("pending-first-intent"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Unchanged owner\n"));
            fixture.publish(Map.of("sample", original));
            byte[] oldSelection = Files.readAllBytes(fixture.publication);
            Path fresh = fixture.publication.resolveSibling("first-owner-publication.json");
            DocumentQueryProperties properties = new DocumentQueryProperties(DocumentFixture.DOCUMENT_REPOSITORY,
                    fixture.root.resolve(".git"), fresh, CollectionPurpose.TEST_ONLY, "fixture-read-token");
            String candidate;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(properties.storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("first selection failure"); }
            })) {
                candidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
                assertNotEquals(original, candidate);
                assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                assertFalse(Files.exists(fresh));
            }
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(properties.storage())) {
                assertTrue(publisher.status().activeRevisions().isEmpty());
                assertEquals(candidate, publisher.status().candidateRevision().orElseThrow());
                LocalDocumentPublisher.Result recovered = publisher.update(DocumentLocalPublisherTest.request());
                assertFalse(recovered.commitCreated()); assertTrue(recovered.selectionChanged());
                assertEquals(Map.of("sample", candidate), recovered.activeRevisions());
                assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals(oldSelection, Files.readAllBytes(fixture.publication));
                assertArrayEquals("# Unchanged owner\n".getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, original, DocumentFixture.GUIDE));
            }
        }
    }

    private static void assertPosixAccess(PosixFileAttributes expected, Path path) throws IOException {
        PosixFileAttributes actual = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(expected.owner(), actual.owner());
        assertEquals(expected.group(), actual.group());
        assertEquals(expected.permissions(), actual.permissions());
    }
    @Test void committedUpdateAndPartialAndFinalRemovalRecoverOnlyNamedOwnerWithoutMovingHead() throws Exception {
        for (String operation : List.of("update", "partial-remove", "final-remove")) {
            try (DocumentFixture fixture = new DocumentFixture(temporary.resolve(operation))) {
                DocumentLocalPublisherTest.identity(fixture);
                byte[] aBytes = "# A\n".getBytes(StandardCharsets.UTF_8);
                byte[] bBytes = "# B\n".getBytes(StandardCharsets.UTF_8);
                String bPath = "repositories/b/guide.md";
                Map<String, byte[]> documents = new java.util.LinkedHashMap<>(Map.of(DocumentFixture.GUIDE, aBytes, bPath, bBytes));
                List<Map<String, Object>> rows = new java.util.ArrayList<>(List.of(fixture.row(DocumentFixture.GUIDE, aBytes),
                        fixture.row(bPath, bBytes, new SourceContext("b", DocumentFixture.SOURCE_REVISION))));
                if (operation.equals("partial-remove")) {
                    documents.put("repositories/sample/other.md", aBytes);
                    rows.add(fixture.row("repositories/sample/other.md", aBytes));
                }
                String original = fixture.commitBytes(documents, rows);
                fixture.publish(Map.of("sample", original, "b", original));
                byte[] selection = Files.readAllBytes(fixture.publication);
                if (operation.equals("update")) { Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Updated\n"); }
                String candidate;
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                    if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("synthetic selection write failure"); }
                })) {
                    LocalDocumentPublisher.Failure failure = assertThrows(LocalDocumentPublisher.Failure.class, () -> {
                        if (operation.equals("update")) { publisher.update(DocumentLocalPublisherTest.request()); }
                        else { publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty())); }
                    });
                    candidate = failure.candidateRevision().orElseThrow();
                    assertEquals(LocalDocumentPublisher.Phase.ACTIVATE, failure.phase());
                    assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                    assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                }
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                    assertEquals(Map.of("sample", original, "b", original), publisher.status().activeRevisions());
                    assertEquals(candidate, publisher.status().candidateRevision().orElseThrow());
                    if (!operation.equals("update")) {
                        Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Intervening dirty removal input\n");
                        assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.remove(
                                new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty())));
                        assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                        assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                        assertEquals("# Intervening dirty removal input\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
                        Files.delete(fixture.root.resolve(DocumentFixture.GUIDE));
                    }
                    LocalDocumentPublisher.Result recovered = operation.equals("update") ? publisher.update(DocumentLocalPublisherTest.request())
                            : publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty()));
                    assertFalse(recovered.commitCreated());
                    assertTrue(recovered.selectionChanged());
                    assertEquals(candidate, recovered.candidateRevision().orElseThrow());
                    assertEquals(original, recovered.activeRevisions().get("b"));
                    if (operation.equals("final-remove")) {
                        assertFalse(recovered.activeRevisions().containsKey("sample"));
                        Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Re-added\n");
                        LocalDocumentPublisher.Result added = publisher.update(DocumentLocalPublisherTest.request());
                        assertTrue(added.commitCreated());
                        assertNotEquals(original, added.activeRevisions().get("sample"));
                        try (GitDocumentLoader loader = new GitDocumentLoader(fixture.properties(CollectionPurpose.TEST_ONLY).storage(),
                                new DocumentResultWriter(fixture.mapper))) {
                            DocumentCatalog catalog = new DocumentCatalog(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), loader);
                            try (DocumentCatalog.ReadLease lease = catalog.open(DocumentDeadline.after(DocumentContract.OPERATION_TIMEOUT))) {
                                DocumentFailure stale = assertThrows(DocumentFailure.class, () -> lease.view().requireCurrent(
                                        new DocumentContract.DocumentContext(DocumentFixture.DOCUMENT_REPOSITORY, "sample", original)));
                                assertEquals(DocumentFailure.Code.DOCUMENT_REVISION_NOT_AVAILABLE, stale.code());
                            }
                        }
                    } else { assertEquals(candidate, recovered.activeRevisions().get("sample")); }
                    assertArrayEquals(aBytes, DocumentLocalPublisherTest.blob(fixture, original, DocumentFixture.GUIDE));
                    assertArrayEquals(bBytes, DocumentLocalPublisherTest.blob(fixture, original, bPath));
                    assertEquals("# B\n", Files.readString(fixture.root.resolve(bPath)));
                }
            }
        }
    }

    @Test void pendingTwoNameRemovalCannotBePublishedByOneNameReplay() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("removal-input-binding"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String other = "repositories/sample/other.md";
            String keep = "repositories/sample/keep.md";
            String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Guide\n", other, "# Other\n", keep, "# Keep\n"));
            fixture.publish(Map.of("sample", original));
            byte[] selection = Files.readAllBytes(fixture.publication);
            String candidate;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
            })) {
                candidate = assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.remove(
                        new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md", "other.md"), Optional.empty())))
                        .candidateRevision().orElseThrow();
            }
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals(candidate, publisher.status().candidateRevision().orElseThrow());
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.remove(
                        new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty())));
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.remove(
                        new LocalDocumentPublisher.RemoveRequest("sample", List.of("keep.md"), Optional.empty())));
                assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                assertEquals(Map.of("sample", original), publisher.status().activeRevisions());
                assertFalse(Files.exists(fixture.root.resolve(DocumentFixture.GUIDE)));
                assertFalse(Files.exists(fixture.root.resolve(other)));
                assertEquals("# Keep\n", Files.readString(fixture.root.resolve(keep)));
                LocalDocumentPublisher.Result recovered = publisher.remove(
                        new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md", "other.md"), Optional.empty()));
                assertFalse(recovered.commitCreated());
                assertEquals(candidate, recovered.activeRevisions().get("sample"));
            }
        }
    }

    @Test void pendingUpdateCannotBePublishedByDifferentUnchangedNamedFile() throws Exception { assertUnrelatedPendingUpdateRefused(false); }
    @Test void pendingUpdateCannotBePublishedByDifferentChangedNamedFile() throws Exception { assertUnrelatedPendingUpdateRefused(true); }

    private void assertUnrelatedPendingUpdateRefused(boolean changedOther) throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve(changedOther ? "changed-update-input-binding" : "update-input-binding"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String other = "repositories/sample/other.md";
            String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Guide\n", other, "# Other\n"));
            fixture.publish(Map.of("sample", original));
            byte[] selection = Files.readAllBytes(fixture.publication);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Pending guide change\n");
            String candidate;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
            })) {
                candidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
            }
            String otherBytes = changedOther ? "# Changed other input\n" : "# Other\n";
            if (changedOther) { Files.writeString(fixture.root.resolve(other), otherBytes); }
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(new LocalDocumentPublisher.UpdateRequest(
                        new SourceContext("sample", DocumentFixture.SOURCE_REVISION), List.of(), List.of("other.md"), Optional.empty())));
                assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                assertEquals(Map.of("sample", original), publisher.status().activeRevisions());
                assertEquals(otherBytes, Files.readString(fixture.root.resolve(other)));
                assertEquals("# Pending guide change\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
                LocalDocumentPublisher.Result recovered = publisher.update(DocumentLocalPublisherTest.request());
                assertFalse(recovered.commitCreated());
                assertEquals(candidate, recovered.activeRevisions().get("sample"));
            }
        }
    }

    @Test void pendingRemovalReplaysDeletedInputBytesFromItsActualCommitParent() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("pending-removal-input-bytes"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String other = "repositories/sample/other.md";
            String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Original guide\n", other, "# Other\n"));
            fixture.publish(Map.of("sample", original));
            byte[] selection = Files.readAllBytes(fixture.publication);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Pending guide bytes\n");
            String updateCandidate;
            String removalCandidate;
            LocalDocumentPublisher.RemoveRequest remove = new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.empty());
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
            })) {
                updateCandidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
                removalCandidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.remove(remove)).candidateRevision().orElseThrow();
                assertNotEquals(updateCandidate, removalCandidate);
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                assertFalse(Files.exists(fixture.root.resolve(DocumentFixture.GUIDE)));
            }
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentPublisher.Result recovered = publisher.remove(remove);
                assertFalse(recovered.commitCreated());
                assertEquals(removalCandidate, recovered.activeRevisions().get("sample"));
                assertEquals(removalCandidate, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals("# Pending guide bytes\n".getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, updateCandidate, DocumentFixture.GUIDE));
                assertArrayEquals("# Original guide\n".getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, original, DocumentFixture.GUIDE));
                assertEquals("# Other\n", Files.readString(fixture.root.resolve(other)));
            }
        }
    }

    @Test void pendingOperationRequiresCompleteNameSetAndApprovalFingerprint() throws Exception {
        for (boolean removal : List.of(false, true)) {
            try (DocumentFixture fixture = new DocumentFixture(temporary.resolve(removal ? "approval-fingerprint" : "names-fingerprint"))) {
                DocumentLocalPublisherTest.identity(fixture);
                String other = "repositories/sample/other.md";
                String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Guide\n", other, "# Other\n"));
                fixture.publish(Map.of("sample", original));
                byte[] selection = Files.readAllBytes(fixture.publication);
                LocalDocumentPublisher.UpdateRequest fullUpdate = new LocalDocumentPublisher.UpdateRequest(
                        new SourceContext("sample", DocumentFixture.SOURCE_REVISION), List.of(), List.of("other.md", "guide.md"), Optional.empty());
                LocalDocumentPublisher.RemoveRequest fullRemove = new LocalDocumentPublisher.RemoveRequest(
                        "sample", List.of("guide.md"), Optional.of("original removal approval"));
                if (!removal) { Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Pending\n"); }
                String candidate;
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                    if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
                })) {
                    candidate = assertThrows(LocalDocumentPublisher.Failure.class, () -> {
                        if (removal) { publisher.remove(fullRemove); } else { publisher.update(fullUpdate); }
                    }).candidateRevision().orElseThrow();
                }
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                    assertThrows(LocalDocumentPublisher.Failure.class, () -> {
                        if (removal) { publisher.remove(new LocalDocumentPublisher.RemoveRequest("sample", List.of("guide.md"), Optional.of("different approval"))); }
                        else { publisher.update(DocumentLocalPublisherTest.request()); }
                    });
                    assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                    assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                    LocalDocumentPublisher.Result recovered = removal ? publisher.remove(fullRemove)
                            : publisher.update(new LocalDocumentPublisher.UpdateRequest(fullUpdate.primaryContext(), fullUpdate.relatedContexts(),
                                    List.of("guide.md", "other.md"), fullUpdate.reviewReference()));
                    assertFalse(recovered.commitCreated());
                    assertEquals(candidate, recovered.activeRevisions().get("sample"));
                    assertEquals("# Other\n", Files.readString(fixture.root.resolve(other)));
                }
            }
        }
    }

    @Test void pendingRecoveryRefusesUnknownOrMalformedCommitOperationMetadata() throws Exception {
        for (String message : List.of("legacy publication\n", "Update local document catalog\n\nLocal-Document-Operation: malformed\n")) {
            try (DocumentFixture fixture = new DocumentFixture(temporary.resolve(message.startsWith("legacy") ? "legacy-metadata" : "malformed-metadata"))) {
                DocumentLocalPublisherTest.identity(fixture);
                String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Original\n"));
                fixture.publish(Map.of("sample", original));
                byte[] selection = Files.readAllBytes(fixture.publication);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Pending\n");
                String candidate;
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                    if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
                })) {
                    candidate = assertThrows(LocalDocumentPublisher.Failure.class,
                            () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
                }
                ObjectId unknown;
                try (RevWalk walk = new RevWalk(fixture.git.getRepository());
                        ObjectInserter inserter = fixture.git.getRepository().newObjectInserter()) {
                    CommitBuilder commit = new CommitBuilder();
                    commit.setTreeId(walk.parseCommit(ObjectId.fromString(candidate)).getTree());
                    commit.setParentId(ObjectId.fromString(candidate));
                    PersonIdent identity = new PersonIdent("Fixture", "fixture@example.test");
                    commit.setAuthor(identity); commit.setCommitter(identity); commit.setMessage(message);
                    unknown = inserter.insert(commit);
                    inserter.flush();
                    RefUpdate update = fixture.git.getRepository().updateRef(fixture.git.getRepository().getFullBranch());
                    update.setExpectedOldObjectId(ObjectId.fromString(candidate)); update.setNewObjectId(unknown);
                    assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
                }
                try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                    assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(DocumentLocalPublisherTest.request()));
                    assertEquals(unknown, fixture.git.getRepository().resolve("HEAD"));
                    assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                    assertEquals(Map.of("sample", original), publisher.status().activeRevisions());
                }
            }
        }
    }

    @Test void reusedCandidateRecoveryRefusesHeadAdvanceAtActivation() throws Exception { assertReusedCandidateConcurrentChange(false); }
    @Test void reusedCandidateRecoveryRefusesBranchChangeAtActivation() throws Exception { assertReusedCandidateConcurrentChange(true); }

    private void assertReusedCandidateConcurrentChange(boolean changeBranch) throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve(changeBranch ? "recovery-branch-race" : "recovery-head-race"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String original = fixture.commit(Map.of(DocumentFixture.GUIDE, "# Original\n"));
            fixture.publish(Map.of("sample", original));
            byte[] selection = Files.readAllBytes(fixture.publication);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Pending\n");
            String candidate;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("selection failure"); }
            })) {
                candidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
            }
            String branch = fixture.git.getRepository().getFullBranch();
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase != LocalDocumentPublisher.Phase.ACTIVATE) { return; }
                if (changeBranch) {
                    RefUpdate intervening = fixture.git.getRepository().updateRef(Constants.R_HEADS + "intervening");
                    intervening.setNewObjectId(ObjectId.fromString(candidate));
                    assertEquals(RefUpdate.Result.NEW, intervening.update());
                    fixture.git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + "intervening");
                } else {
                    try (RevWalk walk = new RevWalk(fixture.git.getRepository());
                            ObjectInserter inserter = fixture.git.getRepository().newObjectInserter()) {
                        CommitBuilder competing = new CommitBuilder();
                        competing.setTreeId(walk.parseCommit(ObjectId.fromString(candidate)).getTree());
                        competing.setParentId(ObjectId.fromString(candidate));
                        PersonIdent identity = new PersonIdent("Fixture", "fixture@example.test");
                        competing.setAuthor(identity); competing.setCommitter(identity); competing.setMessage("intervening commit\n");
                        ObjectId revision = inserter.insert(competing);
                        inserter.flush();
                        RefUpdate update = fixture.git.getRepository().updateRef(branch);
                        update.setExpectedOldObjectId(ObjectId.fromString(candidate));
                        update.setNewObjectId(revision);
                        assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
                    }
                }
            })) {
                LocalDocumentPublisher.Failure failure = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request()));
                assertEquals("LOCAL_DOCUMENT_CONCURRENT_CHANGE", failure.code());
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                assertEquals(Map.of("sample", original), publisher.status().activeRevisions());
                if (changeBranch) {
                    assertEquals(Constants.R_HEADS + "intervening", fixture.git.getRepository().getFullBranch());
                    assertEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                } else {
                    assertEquals(branch, fixture.git.getRepository().getFullBranch());
                    assertNotEquals(candidate, fixture.git.getRepository().resolve("HEAD").name());
                }
                assertEquals("# Pending\n", Files.readString(fixture.root.resolve(DocumentFixture.GUIDE)));
            }
        }
    }

    @Test void removalPreparationRefusesReplacedTargetAndPreservesNewBytes() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("removal-target-replacement"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String text = "# Selected\n";
            fixture.commit(Map.of(DocumentFixture.GUIDE, text));
            Path target = fixture.root.resolve(DocumentFixture.GUIDE);
            try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentGit.Removal removal = git.removal(DocumentFixture.GUIDE, text.getBytes(StandardCharsets.UTF_8));
                Path saved = target.resolveSibling("saved-original.md");
                Files.move(target, saved);
                Files.writeString(target, "# Replaced working bytes\n");
                assertThrows(LocalDocumentPublisher.Failure.class, () -> git.delete(removal));
                assertEquals("# Replaced working bytes\n", Files.readString(target));
                assertEquals(text, Files.readString(saved));
            }
        }
    }

    @Test void removalPreparationRefusesSubstitutedAncestorAndPreservesOutsideBytes() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("removal-ancestor-replacement"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String text = "# Selected\n";
            fixture.commit(Map.of(DocumentFixture.GUIDE, text));
            Path parent = fixture.root.resolve("repositories/sample");
            Path saved = fixture.root.resolve("repositories/saved-sample");
            Path outside = temporary.resolve("outside-removal-target");
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("guide.md"), "# Outside working bytes\n");
            try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentGit.Removal removal = git.removal(DocumentFixture.GUIDE, text.getBytes(StandardCharsets.UTF_8));
                Files.move(parent, saved);
                Files.createSymbolicLink(parent, outside);
                assertThrows(DocumentFailure.class, () -> git.delete(removal));
                assertEquals("# Outside working bytes\n", Files.readString(outside.resolve("guide.md")));
                assertEquals(text, Files.readString(saved.resolve("guide.md")));
            }
        }
    }

    @Test void removalCapturePreservesReplacementMovedAtTheCaptureBoundary() throws Exception { assertCapturePreservesUnexpectedObject("before"); }
    @Test void removalCapturePreservesReplacementCreatedAfterCapture() throws Exception { assertCapturePreservesUnexpectedObject("after"); }
    @Test void removalCapturePreservesOutsideBytesWhenAncestorIsSubstituted() throws Exception { assertCapturePreservesUnexpectedObject("ancestor"); }
    @Test void removalCaptureNeverFollowsReplacementSymlink() throws Exception { assertCapturePreservesUnexpectedObject("link"); }

    private void assertCapturePreservesUnexpectedObject(String mode) throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("capture-" + mode))) {
            DocumentLocalPublisherTest.identity(fixture);
            String selected = "# Selected original\n";
            fixture.commit(Map.of(DocumentFixture.GUIDE, selected));
            Path target = fixture.root.resolve(DocumentFixture.GUIDE);
            Path saved = target.resolveSibling("saved-original.md");
            Path savedParent = target.getParent().resolveSibling("saved-parent");
            Path outside = temporary.resolve("outside-capture-" + mode);
            Files.createDirectories(outside);
            Files.writeString(outside.resolve("guide.md"), "# Outside bytes\n");
            AtomicReference<Path> captured = new AtomicReference<>();
            LocalDocumentGit.CapturePhase changedAt = mode.equals("after")
                    ? LocalDocumentGit.CapturePhase.AFTER_CAPTURE : LocalDocumentGit.CapturePhase.BEFORE_CAPTURE;
            try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentGit.Removal removal = git.removal(DocumentFixture.GUIDE, selected.getBytes(StandardCharsets.UTF_8));
                Class<? extends RuntimeException> failure = mode.equals("ancestor") ? DocumentFailure.class : LocalDocumentPublisher.Failure.class;
                assertThrows(failure, () -> git.delete(removal, (phase, original, capture) -> {
                    if (phase != changedAt) { return; }
                    captured.set(capture);
                    if (mode.equals("ancestor")) {
                        Files.move(original.getParent(), savedParent);
                        Files.createSymbolicLink(original.getParent(), outside);
                    } else if (mode.equals("after")) { Files.writeString(original, "# Replacement bytes\n"); }
                    else {
                        Files.move(original, saved);
                        if (mode.equals("link")) { Files.createSymbolicLink(original, outside.resolve("guide.md")); }
                        else { Files.writeString(original, "# Replacement bytes\n"); }
                    }
                }));
                Path preserved = captured.get();
                assertNotNull(preserved);
                if (mode.equals("ancestor")) {
                    preserved = savedParent.resolve(preserved.getParent().getFileName()).resolve(preserved.getFileName());
                    assertEquals(selected, Files.readString(preserved));
                    assertTrue(Files.isSymbolicLink(target.getParent()));
                } else if (mode.equals("after")) {
                    assertEquals("# Replacement bytes\n", Files.readString(target));
                    assertEquals(selected, Files.readString(preserved));
                } else if (mode.equals("link")) {
                    assertTrue(Files.isSymbolicLink(preserved));
                    assertEquals(outside.resolve("guide.md"), Files.readSymbolicLink(preserved));
                    assertEquals(selected, Files.readString(saved));
                } else {
                    assertEquals("# Replacement bytes\n", Files.readString(preserved));
                    assertEquals(selected, Files.readString(saved));
                }
                assertEquals("# Outside bytes\n", Files.readString(outside.resolve("guide.md")));
            }
        }
    }

    @Test void successfulRemovalDeletesValidatedCaptureAndItsPrivateDirectory() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("capture-success"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String selected = "# Selected\n";
            fixture.commit(Map.of(DocumentFixture.GUIDE, selected));
            AtomicReference<Path> captured = new AtomicReference<>();
            try (LocalDocumentGit git = new LocalDocumentGit(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                git.delete(git.removal(DocumentFixture.GUIDE, selected.getBytes(StandardCharsets.UTF_8)),
                        (phase, original, capture) -> captured.set(capture));
                assertFalse(Files.exists(fixture.root.resolve(DocumentFixture.GUIDE)));
                assertNotNull(captured.get());
                assertFalse(Files.exists(captured.get().getParent()));
            }
        }
    }

    @Test void changedPendingUpdateCreatesANewForwardCandidateInsteadOfClaimingUnchangedRecovery() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("changed-pending-update"))) {
            DocumentLocalPublisherTest.identity(fixture);
            byte[] aBytes = "# Original A\n".getBytes(StandardCharsets.UTF_8);
            byte[] bBytes = "# Original B\n".getBytes(StandardCharsets.UTF_8);
            String bPath = "repositories/b/guide.md";
            String original = fixture.commitBytes(Map.of(DocumentFixture.GUIDE, aBytes, bPath, bBytes),
                    List.of(fixture.row(DocumentFixture.GUIDE, aBytes), fixture.row(bPath, bBytes,
                            new SourceContext("b", DocumentFixture.SOURCE_REVISION))));
            fixture.publish(Map.of("sample", original, "b", original));
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Failed selection candidate\n");
            String failedCandidate;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("injected selection failure"); }
            })) {
                failedCandidate = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).candidateRevision().orElseThrow();
            }
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Intervening new owner content\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals(Map.of("sample", original, "b", original), publisher.status().activeRevisions());
                LocalDocumentPublisher.Result changed = publisher.update(DocumentLocalPublisherTest.request());
                assertTrue(changed.commitCreated());
                assertTrue(changed.selectionChanged());
                String current = changed.activeRevisions().get("sample");
                assertNotEquals(failedCandidate, current);
                assertEquals(original, changed.activeRevisions().get("b"));
                try (RevWalk walk = new RevWalk(fixture.git.getRepository())) {
                    assertEquals(failedCandidate, walk.parseCommit(ObjectId.fromString(current)).getParent(0).name());
                }
                assertArrayEquals("# Failed selection candidate\n".getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, failedCandidate, DocumentFixture.GUIDE));
                assertArrayEquals("# Intervening new owner content\n".getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, current, DocumentFixture.GUIDE));
                assertArrayEquals(bBytes, DocumentLocalPublisherTest.blob(fixture, original, bPath));
                assertEquals("# Original B\n", Files.readString(fixture.root.resolve(bPath)));
            }
        }
    }

    @Test void sourceTargetRollbackCreatesForwardDocumentCommitsAndNeverMovesOtherOwner() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("rollback"))) {
            DocumentLocalPublisherTest.identity(fixture);
            Files.createDirectories(fixture.root.resolve("repositories/sample"));
            Files.createDirectories(fixture.root.resolve("repositories/b"));
            Files.writeString(fixture.root.resolve("repositories/b/guide.md"), "# B owner\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                String b = publisher.update(new LocalDocumentPublisher.UpdateRequest(new SourceContext("b", "b".repeat(40)),
                        List.of(), List.of("guide.md"), Optional.empty())).activeRevisions().get("b");
                String parent = b;
                String original = null;
                for (String source : List.of("a".repeat(40), "c".repeat(40), "a".repeat(40))) {
                    Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Target\n" + source + "\n");
                    LocalDocumentPublisher.Result result = publisher.update(new LocalDocumentPublisher.UpdateRequest(new SourceContext("sample", source),
                            List.of(), List.of("guide.md"), Optional.empty()));
                    String revision = result.activeRevisions().get("sample");
                    assertTrue(result.commitCreated());
                    assertEquals(b, result.activeRevisions().get("b"));
                    try (RevWalk walk = new RevWalk(fixture.git.getRepository())) {
                        assertEquals(parent, walk.parseCommit(ObjectId.fromString(revision)).getParent(0).name());
                    }
                    if (Objects.isNull(original)) { original = revision; }
                    parent = revision;
                }
                assertNotEquals(original, parent);
                assertArrayEquals(("# Target\n" + "a".repeat(40) + "\n").getBytes(StandardCharsets.UTF_8),
                        DocumentLocalPublisherTest.blob(fixture, original, DocumentFixture.GUIDE));
            }
        }
    }
    @Test void writerBusyAndChangedHeadDoNotOverwriteOtherWriter() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(Map.of("sample", a));
            Path lock = fixture.root.resolve(".git/document-local-writer.lock");
            Files.createFile(lock);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals("LOCAL_DOCUMENT_BUSY", assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(DocumentLocalPublisherTest.request())).code());
            }
            Files.delete(lock);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.COMMIT) {
                    try (org.eclipse.jgit.revwalk.RevWalk walk = new org.eclipse.jgit.revwalk.RevWalk(fixture.git.getRepository());
                            org.eclipse.jgit.lib.ObjectInserter inserter = fixture.git.getRepository().newObjectInserter()) {
                        org.eclipse.jgit.lib.CommitBuilder competing = new org.eclipse.jgit.lib.CommitBuilder();
                        competing.setTreeId(walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(a)).getTree());
                        competing.setParentId(org.eclipse.jgit.lib.ObjectId.fromString(a));
                        org.eclipse.jgit.lib.PersonIdent identity = new org.eclipse.jgit.lib.PersonIdent("Fixture", "fixture@example.test");
                        competing.setAuthor(identity); competing.setCommitter(identity); competing.setMessage("competing commit\n");
                        org.eclipse.jgit.lib.ObjectId other = inserter.insert(competing);
                        inserter.flush();
                        org.eclipse.jgit.lib.RefUpdate update = fixture.git.getRepository().updateRef(fixture.git.getRepository().getFullBranch());
                        update.setExpectedOldObjectId(org.eclipse.jgit.lib.ObjectId.fromString(a)); update.setNewObjectId(other);
                        assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD, update.update());
                    }
                }
            })) {
                LocalDocumentPublisher.Failure failure = assertThrows(LocalDocumentPublisher.Failure.class, () -> publisher.update(DocumentLocalPublisherTest.request()));
                assertEquals("LOCAL_DOCUMENT_CONCURRENT_CHANGE", failure.code());
                assertEquals(LocalDocumentPublisher.FailureKind.BUSY, failure.kind());
                assertEquals(a, publisher.status().activeRevisions().get("sample"));
                assertNotEquals(failure.candidateRevision().orElseThrow(), fixture.git.getRepository().resolve("HEAD").name());
            }
        }
    }
}
