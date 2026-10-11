package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.support.DocumentFixture;
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
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DocumentLocalPublicationTest {
    @TempDir Path temporary;
    @Test void updateAndActivationPreserveAuthorizedPosixAccessAndCatalogExecutableMode() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(PosixFileAttributeView.class));
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("access"))) {
            DocumentLocalPublisherTest.identity(fixture);
            fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            Path catalog = fixture.root.resolve("navigation-catalog.json");
            Files.setPosixFilePermissions(catalog, PosixFilePermissions.fromString("rwxr-x---"));
            fixture.git.add().addFilepattern("navigation-catalog.json").call();
            String a = fixture.git.commit().setMessage("executable catalog").call().name();
            fixture.publish(a, a);
            Files.setPosixFilePermissions(fixture.publication, PosixFilePermissions.fromString("rw-r-----"));
            PosixFileAttributes selectionAccess = Files.readAttributes(fixture.publication, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            PosixFileAttributes catalogAccess = Files.readAttributes(catalog, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                LocalDocumentPublisher.Result result = publisher.update(DocumentLocalPublisherTest.request());
                assertTrue(result.commitCreated());
                assertNotEquals(a, result.activeRevision().orElseThrow());
                assertPosixAccess(selectionAccess, fixture.publication);
                assertPosixAccess(catalogAccess, catalog);
                publisher.activate(a, List.of());
                assertEquals(a, publisher.status().activeRevision().orElseThrow());
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
    @Test void updateAndActivationPreserveSupportedFilesystemAcl() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView(AclFileAttributeView.class));
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("acl-access"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(a, a);
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
                publisher.activate(a, List.of());
                assertEquals(a, publisher.status().activeRevision().orElseThrow());
                assertEquals(selectionAcl, selectionView.getAcl());
                assertEquals(catalogAcl, catalogView.getAcl());
            }
        }
    }
    private static void assertPosixAccess(PosixFileAttributes expected, Path path) throws IOException {
        PosixFileAttributes actual = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        assertEquals(expected.owner(), actual.owner());
        assertEquals(expected.group(), actual.group());
        assertEquals(expected.permissions(), actual.permissions());
    }
    @Test void aggregateRetainedSectionsRefuseEvenWhenEveryRevisionIndividuallyFits() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("aggregate"))) {
            DocumentLocalPublisherTest.identity(fixture);
            Map<String, String> documents = new java.util.LinkedHashMap<>();
            for (int i = 0; i < 128; i++) {
                StringBuilder markdown = new StringBuilder();
                for (int section = 0; section < 17; section++) { markdown.append("# Section ").append(section).append("\nbody\n"); }
                documents.put("repositories/sample/d" + i + ".md", markdown.toString());
            }
            String first = fixture.commit(documents);
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                publisher.activate(first, List.of());
                for (int i = 0; i < 2; i++) {
                    documents.put("repositories/sample/d0.md", documents.get("repositories/sample/d0.md") + "revision " + i + "\n");
                    publisher.activate(fixture.commit(documents), List.of());
                }
                byte[] before = Files.readAllBytes(fixture.publication);
                documents.put("repositories/sample/d0.md", documents.get("repositories/sample/d0.md") + "fourth\n");
                String fourth = fixture.commit(documents);
                assertEquals("DOCUMENT_CAPACITY_EXCEEDED", assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.activate(fourth, List.of())).code());
                assertArrayEquals(before, Files.readAllBytes(fixture.publication));
                publisher.activate(fourth, List.of(first));
                assertEquals(3, publisher.status().retainedRevisions().size());
            }
        }
    }
    @Test void committedCandidateCanRecoverWithoutMovingHeadAndWithdrawalDoesNotDeleteObjects() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(a, a);
            byte[] selection = Files.readAllBytes(fixture.publication);
            Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# B\n");
            String b;
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage(), phase -> {
                if (phase == LocalDocumentPublisher.Phase.ACTIVATE) { throw new IOException("synthetic atomic-replacement failure"); }
            })) {
                LocalDocumentPublisher.Failure failure = assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request()));
                b = failure.candidateRevision().orElseThrow();
                assertEquals(LocalDocumentPublisher.Phase.ACTIVATE, failure.phase());
                assertEquals(b, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
            }
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                assertEquals(a, publisher.status().activeRevision().orElseThrow());
                publisher.activate(b, List.of());
                publisher.activate(a, List.of(b));
                assertEquals(b, fixture.git.getRepository().resolve("HEAD").name());
                assertEquals(List.of(a), publisher.status().retainedRevisions());
                assertArrayEquals("# B\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), DocumentLocalPublisherTest.blob(fixture, b, DocumentFixture.GUIDE));
            }
        }
    }
    @Test void ninthRetainedRefusesBeforeHeadMutationUnlessExplicitlyWithdrawn() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            DocumentLocalPublisherTest.identity(fixture);
            java.util.ArrayList<String> revisions = new java.util.ArrayList<>();
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(fixture.properties(CollectionPurpose.TEST_ONLY).storage())) {
                Files.createDirectories(fixture.root.resolve("repositories/sample"));
                for (int i = 0; i < 8; i++) {
                    Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Revision " + i + "\n");
                    revisions.add(publisher.update(DocumentLocalPublisherTest.request()).candidateRevision().orElseThrow());
                }
                String head = fixture.git.getRepository().resolve("HEAD").name();
                byte[] selection = Files.readAllBytes(fixture.publication);
                Files.writeString(fixture.root.resolve(DocumentFixture.GUIDE), "# Ninth\n");
                assertEquals("DOCUMENT_CAPACITY_EXCEEDED", assertThrows(LocalDocumentPublisher.Failure.class,
                        () -> publisher.update(DocumentLocalPublisherTest.request())).code());
                assertEquals(head, fixture.git.getRepository().resolve("HEAD").name());
                assertArrayEquals(selection, Files.readAllBytes(fixture.publication));
                LocalDocumentPublisher.UpdateRequest ordinary = DocumentLocalPublisherTest.request();
                publisher.update(new LocalDocumentPublisher.UpdateRequest(ordinary.primaryContext(), ordinary.relatedContexts(), ordinary.fileNames(),
                        ordinary.reviewReference(), List.of(revisions.getFirst())));
                assertEquals(8, publisher.status().retainedRevisions().size());
            }
        }
    }
    @Test void writerBusyAndChangedHeadDoNotOverwriteOtherWriter() throws Exception {
        try (DocumentFixture fixture = new DocumentFixture(temporary.resolve("repo"))) {
            DocumentLocalPublisherTest.identity(fixture);
            String a = fixture.commit(Map.of(DocumentFixture.GUIDE, "# A\n"));
            fixture.publish(a, a);
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
                assertEquals(a, publisher.status().activeRevision().orElseThrow());
                assertNotEquals(failure.candidateRevision().orElseThrow(), fixture.git.getRepository().resolve("HEAD").name());
            }
        }
    }
}
