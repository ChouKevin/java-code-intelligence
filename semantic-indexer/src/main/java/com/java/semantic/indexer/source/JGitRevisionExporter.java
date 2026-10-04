package com.java.semantic.indexer.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.model.repository.RepositoryRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceInventoryEntry;
import com.java.semantic.model.source.SourcePathPolicy;
import com.java.semantic.model.source.SourceReadContract.EntryKind;
import com.java.semantic.model.source.SourceReadContract.EntryStatus;
import com.java.semantic.model.source.SourceReadContract.GuideFreshness;
import com.java.semantic.model.source.SourceReadContract.GuideInfo;
import com.java.semantic.model.source.SourceReadContract.GuideState;
import com.java.semantic.model.source.SourceRevisionManifest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Walks Git objects only; eligible tree files are exact blob bytes without checkout/filter execution. */
@Component
public final class JGitRevisionExporter {
    private static final int MAX_FRAME = 64 * 1024;
    private static final byte[] LFS_PREFIX = "version https://git-lfs.github.com/spec/v1\n".getBytes(StandardCharsets.US_ASCII);
    private final RepositoryRevisionResolver resolver;
    private final RepositoryRegistry registry;
    private final ObjectMapper mapper;

    public JGitRevisionExporter(RepositoryRevisionResolver resolver, RepositoryRegistry registry, ObjectMapper mapper) {
        this.resolver = resolver;
        this.registry = registry;
        this.mapper = mapper;
    }

    public SourceRevisionManifest export(RepositoryId repository, RepositoryRevision revision, Path stagingDirectory) {
        try (Git git = resolver.open(repository);
                RevWalk commits = new RevWalk(git.getRepository())) {
            RevCommit commit = commits.parseCommit(ObjectId.fromString(revision.value()));
            DurableSourceFiles.ensureDirectories(stagingDirectory, stagingDirectory.resolve("tree"),
                    DurableSourceFiles.Visibility.PRIVATE);
            CoverageCounter counts = new CoverageCounter();
            String guide = registry.require(repository).getProjectGuidePath();
            GuideTracker guideTracker = new GuideTracker(guide);
            Path inventory = stagingDirectory.resolve("inventory.jsonl");
            try (OutputStream out = Files.newOutputStream(inventory, StandardOpenOption.CREATE_NEW);
                    TreeWalk walk = new TreeWalk(git.getRepository())) {
                Files.setPosixFilePermissions(inventory,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                walk.addTree(commit.getTree());
                walk.setRecursive(false);
                int excludedDepth = -1;
                while (walk.next()) {
                    FileMode mode = walk.getFileMode(0);
                    if (excludedDepth >= 0) {
                        if (walk.getDepth() > excludedDepth) {
                            if (mode.equals(FileMode.TREE)) {
                                walk.enterSubtree();
                            } else {
                                counts.excluded++;
                            }
                            continue;
                        }
                        excludedDepth = -1;
                    }
                    String path = safePath(walk.getRawPath());
                    if (path.isEmpty()) {
                        counts.unsupported(EntryStatus.UNSUPPORTED_PATH);
                        continue;
                    }
                    if (SourcePathPolicy.isExcluded(path)) {
                        if (mode.equals(FileMode.TREE)) {
                            // Once excluded, count all tracked leaves without decoding names or opening blobs.
                            excludedDepth = walk.getDepth();
                            walk.enterSubtree();
                        } else {
                            counts.excluded++;
                        }
                        continue;
                    }
                    if (mode.equals(FileMode.TREE)) {
                        DurableSourceFiles.ensureDirectories(stagingDirectory,
                                stagingDirectory.resolve("tree").resolve(path), DurableSourceFiles.Visibility.PRIVATE);
                        frame(out, new SourceInventoryEntry(path, EntryKind.DIRECTORY, Optional.empty(), 0,
                                Optional.empty(), Optional.empty()));
                        walk.enterSubtree();
                        continue;
                    }
                    ObjectId id = walk.getObjectId(0);
                    if (!mode.equals(FileMode.REGULAR_FILE) && !mode.equals(FileMode.EXECUTABLE_FILE)) {
                        EntryStatus status = mode.equals(FileMode.GITLINK) ? EntryStatus.SUBMODULE
                                : mode.equals(FileMode.SYMLINK) ? EntryStatus.SYMLINK : EntryStatus.UNSUPPORTED_PATH;
                        counts.unsupported(status);
                        if (guideTracker.path.equals(path)) {
                            guideTracker.invalid = true;
                        }
                        frame(out, new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(status), 0,
                                Optional.empty(), Optional.empty()));
                        continue;
                    }
                    ObjectLoader blob = git.getRepository().open(id);
                    long size = blob.getSize();
                    EntryStatus status = classify(blob);
                    if (status != EntryStatus.TEXT) {
                        counts.unsupported(status);
                        if (guideTracker.path.equals(path)) {
                            guideTracker.invalid = true;
                        }
                        frame(out, new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(status), size,
                                Optional.empty(), Optional.empty()));
                        continue;
                    }
                    Path destination = stagingDirectory.resolve("tree").resolve(path).normalize();
                    if (!destination.startsWith(stagingDirectory.resolve("tree"))) {
                        throw new IOException("invalid source path");
                    }
                    DurableSourceFiles.ensureDirectories(stagingDirectory, destination.getParent(),
                            DurableSourceFiles.Visibility.PRIVATE);
                    String digest = streamBlob(blob, destination);
                    counts.readable++;
                    frame(out, new SourceInventoryEntry(path, EntryKind.FILE, Optional.of(EntryStatus.TEXT), size,
                            Optional.of(id.name()), Optional.of(digest)));
                    if (guideTracker.path.equals(path)) {
                        guideTracker.available = true;
                        guideTracker.digest = digest;
                    }
                }
            }
            GuideInfo guideInfo = guideTracker.info();
            return new SourceRevisionManifest(SourceRevisionManifest.FORMAT_VERSION,
                    SourceRevisionManifest.POLICY_VERSION, new SourceContext(repository.value(), revision.value()),
                    Instant.now(), guideInfo,
                    new SourceRevisionManifest.Coverage(counts.readable, counts.excluded, Map.copyOf(counts.unsupported)),
                    DurableSourceFiles.sha256(inventory));
        } catch (IOException exception) {
            throw new IllegalStateException("source export unavailable", exception);
        }
    }

    private static String safePath(byte[] raw) {
        try {
            String path = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            if (path.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME - 1024) {
                return "";
            }
            SourcePathPolicy.requireFile(path);
            return path;
        } catch (IllegalArgumentException | CharacterCodingException exception) {
            return "";
        }
    }

    private EntryStatus classify(ObjectLoader blob) throws IOException {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        byte[] chunk = new byte[65536];
        ByteBuffer pending = ByteBuffer.allocate(65540);
        CharBuffer characters = CharBuffer.allocate(65540);
        byte[] prefix = new byte[LFS_PREFIX.length];
        int prefixRead = 0;
        boolean binary = false;
        try (InputStream input = blob.openStream()) {
            int length;
            while ((length = input.read(chunk)) >= 0) {
                for (int index = 0; index < length; index++) {
                    if (chunk[index] == 0) {
                        binary = true;
                    }
                    if (prefixRead < prefix.length) {
                        prefix[prefixRead++] = chunk[index];
                    }
                }
                pending.put(chunk, 0, length);
                pending.flip();
                java.nio.charset.CoderResult result = decoder.decode(pending, characters, false);
                if (result.isError()) {
                    return EntryStatus.UNSUPPORTED_ENCODING;
                }
                pending.compact();
                characters.clear();
            }
            pending.flip();
            if (decoder.decode(pending, characters, true).isError() || pending.hasRemaining()
                    || decoder.flush(characters).isError()) {
                return EntryStatus.UNSUPPORTED_ENCODING;
            }
        }
        if (binary) {
            return EntryStatus.BINARY;
        }
        if (prefixRead == prefix.length && java.util.Arrays.equals(prefix, LFS_PREFIX)) {
            return EntryStatus.LFS_POINTER;
        }
        return EntryStatus.TEXT;
    }

    private static String streamBlob(ObjectLoader blob, Path destination) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = blob.openStream(); OutputStream file = Files.newOutputStream(destination,
                    StandardOpenOption.CREATE_NEW); DigestOutputStream output = new DigestOutputStream(file, digest)) {
                Files.setPosixFilePermissions(destination,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                input.transferTo(output);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void frame(OutputStream output, SourceInventoryEntry entry) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(entry);
        if (bytes.length + 1 > MAX_FRAME) {
            throw new IOException("source inventory frame exceeds its size limit");
        }
        output.write(bytes);
        output.write('\n');
    }

    private static final class CoverageCounter {
        private long readable;
        private long excluded;
        private final EnumMap<EntryStatus, Long> unsupported = new EnumMap<>(EntryStatus.class);
        private void unsupported(EntryStatus status) {
            unsupported.merge(status, 1L, Long::sum);
        }
    }

    private static final class GuideTracker {
        private final String path;
        private final boolean valid;
        private boolean available;
        private boolean invalid;
        private String digest;

        private GuideTracker(String path) {
            this.path = path;
            boolean safe;
            try {
                safe = !path.isBlank() && path.endsWith(".md") && !SourcePathPolicy.isExcluded(SourcePathPolicy.requireFile(path));
            } catch (IllegalArgumentException exception) {
                safe = false;
            }
            valid = safe;
        }

        private GuideInfo info() {
            if (path.isBlank()) {
                return new GuideInfo(GuideState.DISABLED, Optional.empty(), Optional.empty(), GuideFreshness.NOT_VERIFIED);
            }
            if (invalid) {
                return new GuideInfo(GuideState.INVALID, Optional.of(path), Optional.empty(), GuideFreshness.NOT_VERIFIED);
            }
            if (!valid) {
                return new GuideInfo(GuideState.INVALID, Optional.empty(), Optional.empty(), GuideFreshness.NOT_VERIFIED);
            }
            return new GuideInfo(available ? GuideState.AVAILABLE : GuideState.MISSING, Optional.of(path),
                    Optional.ofNullable(digest), GuideFreshness.NOT_VERIFIED);
        }
    }
}
