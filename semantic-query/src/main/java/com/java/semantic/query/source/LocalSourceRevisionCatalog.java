package com.java.semantic.query.source;

import com.java.semantic.model.source.PreparedRevision;
import com.java.semantic.model.source.SourceContext;
import com.java.semantic.model.source.SourceReadContract;
import com.java.semantic.model.source.SourceReadContract.*;
import com.java.semantic.model.source.SourceRepositoryDescriptor;
import com.java.semantic.model.source.SourceRepositoryState;
import com.java.semantic.model.source.SourceRepositoryState.PreparationPhase;
import com.java.semantic.model.source.SourceRevisionManifest;
import com.java.semantic.query.application.QueryCursorCodec;
import com.java.semantic.query.config.SourceAccessProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

/** Reads only sealed public membership; directory presence never grants admission. */
public final class LocalSourceRevisionCatalog implements SourceRevisionCatalog {
    private final SourceAccessProperties properties;
    private final ObjectMapper mapper;
    private final SourcePathResolver resolver;

    public LocalSourceRevisionCatalog(SourceAccessProperties properties, ObjectMapper mapper) {
        this.properties = Objects.requireNonNull(properties);
        this.mapper = Objects.requireNonNull(mapper);
        this.resolver = new SourcePathResolver(mapper);
    }

    @Override
    public RepositoryCollection listRepositories(RepositoryRequest request) {
        long deadline = deadline(properties.listTimeout());
        List<SourceRepositoryDescriptor> descriptors = descriptors();
        String binding = QueryCursorCodec.binding("source_repositories", List.of(request.nameFilter().orElse(""),
                Integer.toString(request.limit()), registryDigest()));
        String after = request.cursor().map(cursor -> decode(cursor, binding)).orElse("");
        if (!after.isEmpty() && descriptors.stream().noneMatch(descriptor -> descriptor.repositoryId().equals(after)
                && request.nameFilter().map(value -> descriptor.displayName().contains(value)).orElse(true))) {
            throw new SourceQueryException(SourceQueryException.Code.INVALID_ARGUMENT);
        }
        List<RepositoryItem> items = new ArrayList<>();
        boolean hasMore = false;
        for (SourceRepositoryDescriptor descriptor : descriptors) {
            check(deadline);
            if (descriptor.repositoryId().compareTo(after) <= 0
                    || request.nameFilter().filter(value -> !descriptor.displayName().contains(value)).isPresent()) continue;
            if (items.size() == request.limit()) { hasMore = true; break; }
            SourceRepositoryState state = state(descriptor.repositoryId());
            Optional<String> revision = state.current().map(SourceRepositoryState.CurrentPublication::revision);
            SourceStatus status = status(state);
            if (revision.isPresent()) {
                admit(new SourceContext(descriptor.repositoryId(), revision.orElseThrow()));
            }
            items.add(new RepositoryItem(descriptor.repositoryId(), descriptor.displayName(), descriptor.defaultBranch(),
                    safeGuide(descriptor.projectGuidePath()), status, SemanticStatus.NOT_READY, revision));
        }
        Optional<String> next = hasMore ? Optional.of(QueryCursorCodec.encode(binding,
                List.of(items.getLast().repositoryId()))) : Optional.empty();
        return new RepositoryCollection(items, new Page(items.size(), hasMore, next));
    }

    @Override
    public ContextResult getContext(ContextRequest request) {
        SourceRepositoryDescriptor descriptor = descriptors().stream()
                .filter(item -> item.repositoryId().equals(request.repositoryId())).findFirst()
                .orElseThrow(() -> new SourceQueryException(SourceQueryException.Code.REPOSITORY_NOT_FOUND));
        SourceRepositoryState state = state(descriptor.repositoryId());
        if (request.revision().isPresent()) {
            SourceContext context = new SourceContext(descriptor.repositoryId(), request.revision().orElseThrow());
            if (!state.published().containsKey(context.revision())) {
                throw new SourceQueryException(SourceQueryException.Code.REVISION_NOT_PREPARED);
            }
            AdmittedSourceRevision admitted = admit(context);
            return ready(descriptor, admitted);
        }
        if (state.current().isEmpty()) return new ContextResult(descriptor.repositoryId(), descriptor.defaultBranch(),
                status(state), SemanticStatus.NOT_READY, Optional.empty(), Optional.empty(), Optional.empty());
        AdmittedSourceRevision admitted = admit(new SourceContext(descriptor.repositoryId(), state.current().orElseThrow().revision()));
        return ready(descriptor, admitted);
    }

    private ContextResult ready(SourceRepositoryDescriptor descriptor, AdmittedSourceRevision admitted) {
        return new ContextResult(descriptor.repositoryId(), descriptor.defaultBranch(), SourceStatus.READY,
                SemanticStatus.NOT_READY, Optional.of(admitted.context()), Optional.of(admitted.manifest().projectGuide()),
                Optional.of(admitted.manifest().coverage()));
    }

    @Override
    public AdmittedSourceRevision admit(SourceContext context) {
        long deadline = deadline(properties.listTimeout());
        if (descriptors().stream().noneMatch(item -> item.repositoryId().equals(context.repositoryId()))) {
            throw new SourceQueryException(SourceQueryException.Code.REPOSITORY_NOT_FOUND);
        }
        SourceRepositoryState state = state(context.repositoryId());
        PreparedRevision receipt = state.published().get(context.revision());
        if (Objects.isNull(receipt)) throw new SourceQueryException(state.published().isEmpty()
                ? SourceQueryException.Code.SOURCE_NOT_PREPARED : SourceQueryException.Code.REVISION_NOT_PREPARED);
        Path directory = properties.publishedRoot().resolve(context.repositoryId()).resolve("revisions").resolve(context.revision());
        Path manifestPath = directory.resolve("manifest.json");
        byte[] bytes = metadata(manifestPath, 64 * 1024);
        if (!digest(bytes).equals(receipt.manifestDigest())) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
        SourceRevisionManifest manifest = parse(bytes, SourceRevisionManifest.class);
        if (!manifest.context().equals(context) || manifest.formatVersion() != SourceRevisionManifest.FORMAT_VERSION
                || manifest.policyVersion() != SourceRevisionManifest.POLICY_VERSION) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
        }
        Path inventory = directory.resolve("inventory.jsonl");
        Path tree = directory.resolve("tree");
        noLinks(inventory);
        noLinks(tree);
        if (!Files.isDirectory(tree, LinkOption.NOFOLLOW_LINKS)
                || !resolver.inventoryDigest(inventory, deadline).equals(manifest.inventoryDigest())) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
        }
        return new AdmittedSourceRevision(context, manifest, receipt.manifestDigest(), tree, inventory);
    }

    private List<SourceRepositoryDescriptor> descriptors() {
        byte[] bytes = metadata(properties.publishedRoot().resolve("repositories.json"), 1024 * 1024);
        try {
            JsonNode array = mapper.readTree(bytes);
            if (!array.isArray()) throw new IllegalArgumentException("registry is not an array");
            List<SourceRepositoryDescriptor> descriptors = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode node : array) {
                SourceRepositoryDescriptor descriptor = mapper.treeToValue(node, SourceRepositoryDescriptor.class);
                if (!seen.add(descriptor.repositoryId())) throw new IllegalArgumentException("duplicate repository");
                if (properties.allowedRepositories().contains(descriptor.repositoryId())) descriptors.add(descriptor);
            }
            descriptors.sort((left, right) -> left.repositoryId().compareTo(right.repositoryId()));
            return descriptors;
        } catch (RuntimeException exception) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    private String registryDigest() { return digest(metadata(properties.publishedRoot().resolve("repositories.json"), 1024 * 1024)); }

    private SourceRepositoryState state(String repositoryId) {
        Path path = properties.publishedRoot().resolve(repositoryId).resolve("state.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return new SourceRepositoryState(SourceRevisionManifest.FORMAT_VERSION, repositoryId, Optional.empty(),
                    java.util.Map.of(), new SourceRepositoryState.PreparationStatus(PreparationPhase.IDLE, Optional.empty(), Optional.empty()));
        }
        SourceRepositoryState state = parse(metadata(path, 4 * 1024 * 1024), SourceRepositoryState.class);
        if (!state.repositoryId().equals(repositoryId)) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
        return state;
    }

    private static SourceStatus status(SourceRepositoryState state) {
        if (state.current().isPresent()) return SourceStatus.READY;
        return switch (state.preparation().phase()) {
            case ACCEPTED, RUNNING -> SourceStatus.PREPARING;
            case FAILED -> SourceStatus.FAILED;
            default -> SourceStatus.NOT_PREPARED;
        };
    }

    private static Optional<String> safeGuide(Optional<String> guide) {
        return guide.filter(path -> !com.java.semantic.model.source.SourcePathPolicy.isExcluded(path));
    }

    static byte[] metadata(Path path, int cap) {
        noLinks(path);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > cap) {
                throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
            }
            try (java.io.InputStream stream = Files.newInputStream(path)) {
                byte[] bytes = stream.readNBytes(cap + 1);
                if (bytes.length > cap) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
                return bytes;
            }
        } catch (IOException exception) {
            throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception);
        }
    }

    static void noLinks(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path component = absolute.getRoot();
        for (Path segment : absolute) {
            component = component.resolve(segment);
            if (Files.isSymbolicLink(component)) throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE);
        }
    }

    private <T> T parse(byte[] bytes, Class<T> type) {
        try { return mapper.readValue(bytes, type); }
        catch (RuntimeException exception) { throw new SourceQueryException(SourceQueryException.Code.SOURCE_UNAVAILABLE, exception); }
    }

    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static long deadline(Duration duration) { return SourceOperationDeadline.cap(System.nanoTime() + duration.toNanos()); }
    static void check(long deadline) {
        if (System.nanoTime() - deadline >= 0) throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT);
        if (Thread.currentThread().isInterrupted()) throw new SourceQueryException(SourceQueryException.Code.SOURCE_TIMEOUT);
    }
    static String decode(String cursor, String binding) {
        try { return QueryCursorCodec.decode(cursor, binding, 1).getFirst(); }
        catch (IllegalArgumentException exception) { throw new SourceQueryException(SourceQueryException.Code.INVALID_ARGUMENT, exception); }
    }
}
