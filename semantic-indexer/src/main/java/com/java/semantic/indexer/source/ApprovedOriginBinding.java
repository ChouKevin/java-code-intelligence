package com.java.semantic.indexer.source;

import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.repository.config.RepositoryProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.jgit.transport.URIish;

/** An immutable per-ID identity in both object and published namespaces. */
public final class ApprovedOriginBinding {
    private static final String FILE = "origin.sha256";
    private static final int MAX_BYTES = 128;
    private final Path admin;
    private final Path published;

    public ApprovedOriginBinding(RepositoryProperties properties) {
        admin = Path.of(properties.getSourceAdminRoot()).toAbsolutePath().normalize();
        published = Path.of(properties.getSourcePublishedRoot()).toAbsolutePath().normalize();
    }

    public static String fingerprint(String endpoint) {
        try {
            URIish uri = new URIish(endpoint);
            // A password or URL query can carry a token. Never persist even its digest as public identity.
            if (Objects.nonNull(uri.getPass()) || endpoint.indexOf('?') >= 0 || endpoint.indexOf('#') >= 0) {
                throw new IllegalArgumentException("unsupported credential-bearing Git endpoint");
            }
            return DurableSourceFiles.sha256(("source-origin-v1\u0000" + endpoint).getBytes(StandardCharsets.UTF_8));
        } catch (java.net.URISyntaxException exception) {
            throw new IllegalArgumentException("invalid approved Git endpoint", exception);
        }
    }

    public synchronized void preflight(RepositoryId id, String fingerprint) {
        try {
            verify(admin.resolve("repositories").resolve(id.value()), fingerprint,
                    admin.resolve("jobs").resolve(id.value()));
            verify(published.resolve(id.value()), fingerprint, null);
        } catch (IOException exception) {
            throw new IllegalStateException("approved repository origin binding unavailable", exception);
        }
    }

    public synchronized void bind(RepositoryId id, String fingerprint) {
        Path privateNamespace = admin.resolve("repositories").resolve(id.value());
        Path publicNamespace = published.resolve(id.value());
        try {
            verify(privateNamespace, fingerprint, admin.resolve("jobs").resolve(id.value()));
            verify(publicNamespace, fingerprint, null);
            DurableSourceFiles.ensureDirectories(admin, privateNamespace, DurableSourceFiles.Visibility.PRIVATE);
            DurableSourceFiles.ensureDirectories(published, publicNamespace, DurableSourceFiles.Visibility.PUBLISHED);
            establish(privateNamespace.resolve(FILE), fingerprint, DurableSourceFiles.Visibility.PRIVATE);
            establish(publicNamespace.resolve(FILE), fingerprint, DurableSourceFiles.Visibility.PUBLISHED);
        } catch (IOException exception) {
            throw new IllegalStateException("approved repository origin binding unavailable", exception);
        }
    }

    public synchronized void verify(RepositoryId id, String fingerprint) {
        try {
            verify(admin.resolve("repositories").resolve(id.value()), fingerprint,
                    admin.resolve("jobs").resolve(id.value()));
            verify(published.resolve(id.value()), fingerprint, null);
            if (!Files.exists(admin.resolve("repositories").resolve(id.value()).resolve(FILE), LinkOption.NOFOLLOW_LINKS)
                    || !Files.exists(published.resolve(id.value()).resolve(FILE), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("repository origin binding is missing");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("approved repository origin binding unavailable", exception);
        }
    }

    private static void establish(Path file, String fingerprint, DurableSourceFiles.Visibility visibility) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            DurableSourceFiles.atomicBytes(file, fingerprint.getBytes(StandardCharsets.US_ASCII), MAX_BYTES, visibility);
        }
    }

    private static void verify(Path namespace, String fingerprint, Path additional) throws IOException {
        Path file = namespace.resolve(FILE);
        if (Files.isSymbolicLink(namespace.getParent()) || Files.isSymbolicLink(namespace)
                || Files.isSymbolicLink(file)) {
            throw new IOException("repository origin binding path is not regular");
        }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !fingerprint.equals(new String(DurableSourceFiles.boundedRead(file, MAX_BYTES),
                            StandardCharsets.US_ASCII))) {
                throw new IOException("repository origin binding mismatch");
            }
        } else if (nonempty(namespace) || Objects.nonNull(additional) && nonempty(additional)) {
            throw new IOException("unbound repository namespace cannot be adopted");
        }
    }

    private static boolean nonempty(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return true;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
            return entries.iterator().hasNext();
        }
    }
}
