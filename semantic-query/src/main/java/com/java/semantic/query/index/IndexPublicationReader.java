package com.java.semantic.query.index;

import com.java.semantic.model.index.IndexContract;
import com.java.semantic.model.index.IndexFailure;
import com.java.semantic.model.index.IndexManifest;
import com.java.semantic.model.index.IndexRepositoryState;
import com.java.semantic.query.source.AdmittedSourceRevision;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;
import static com.java.semantic.model.index.IndexFailure.Code.*;

/** Membership and sealed-artifact validation, without any JDBC/native initialization. */
final class IndexPublicationReader {
    private final Path root;
    private final ObjectMapper mapper;

    IndexPublicationReader(Path root, ObjectMapper mapper) {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper);
    }

    Publication select(AdmittedSourceRevision source, Optional<String> explicit, long deadline) throws IOException {
        Path repository = root.resolve(source.context().repositoryId());
        Path statePath = repository.resolve("state.json");
        noLinks(statePath);
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) throw new IndexFailure(explicit.isPresent() ? INDEX_GENERATION_EXPIRED : INDEX_NOT_PREPARED);
        IndexRepositoryState state = decode(bytes(statePath, 4 * 1024 * 1024, deadline), IndexRepositoryState.class);
        require(state.repositoryId().equals(source.context().repositoryId()));
        if (state.formatVersion() != IndexContract.FORMAT_VERSION) throw new IndexFailure(INDEX_INCOMPATIBLE);
        IndexRepositoryState.RevisionIndexes revisions = state.revisions().get(source.context().revision());
        if (Objects.isNull(revisions)) throw new IndexFailure(explicit.isPresent() ? INDEX_GENERATION_EXPIRED : INDEX_NOT_PREPARED);
        require(revisions.sourceManifestDigest().equals(source.manifestDigest()));
        String generation = explicit.or(() -> revisions.selectedGeneration()).orElseThrow(() -> new IndexFailure(INDEX_NOT_PREPARED));
        IndexRepositoryState.Receipt receipt = revisions.generations().get(generation);
        if (Objects.isNull(receipt)) throw new IndexFailure(explicit.isPresent() ? INDEX_GENERATION_EXPIRED : INDEX_UNAVAILABLE);
        Path directory = repository.resolve("revisions").resolve(source.context().revision()).resolve(generation);
        byte[] manifestBytes = bytes(directory.resolve("manifest.json"), 65536, deadline);
        require(digest(manifestBytes).equals(receipt.manifestDigest()));
        IndexManifest manifest = decode(manifestBytes, IndexManifest.class);
        require(manifest.equals(receipt.manifest()) && manifest.context().equals(source.context())
                && manifest.originFingerprint().equals(state.originFingerprint())
                && manifest.sourceManifestDigest().equals(source.manifestDigest())
                && manifest.inventoryDigest().equals(source.manifest().inventoryDigest()));
        if (!manifest.compatible()) throw new IndexFailure(INDEX_INCOMPATIBLE);
        require(manifest.databaseBytes() <= IndexContract.MAX_DATABASE_BYTES);
        return new Publication(manifest, directory.resolve("index.sqlite"));
    }

    void database(Publication publication, long deadline) throws IOException {
        Path path = publication.database();
        noLinks(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) == publication.manifest().databaseBytes());
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] block = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(block)) >= 0) {
                check(deadline);
                total += count;
                require(total <= publication.manifest().databaseBytes());
                digest.update(block, 0, count);
            }
            require(total == publication.manifest().databaseBytes());
        }
        require(HexFormat.of().formatHex(digest.digest()).equals(publication.manifest().databaseDigest()));
    }

    static byte[] bytes(Path path, int maximum, long deadline) throws IOException {
        noLinks(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= maximum);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            check(deadline);
            byte[] bytes = input.readNBytes(maximum + 1);
            check(deadline);
            require(bytes.length <= maximum);
            return bytes;
        }
    }

    static void noLinks(Path path) {
        for (Path current = path.toAbsolutePath(); Objects.nonNull(current); current = current.getParent()) require(!Files.isSymbolicLink(current));
    }

    static void check(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw new IndexFailure(INDEX_TIMEOUT);
    }

    static void require(boolean valid) { if (!valid) throw new IndexFailure(INDEX_UNAVAILABLE); }
    static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private <T> T decode(byte[] bytes, Class<T> type) {
        try { return mapper.readValue(bytes, type); }
        catch (RuntimeException failure) { throw new IndexFailure(INDEX_UNAVAILABLE, failure); }
    }
    record Publication(IndexManifest manifest, Path database) { }
}
