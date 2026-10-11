package com.java.semantic.document.catalog;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentContract.DocumentContext;
import com.java.semantic.document.application.DocumentContract.PublicationStatus;
import com.java.semantic.document.application.DocumentContract.ReviewStatus;
import com.java.semantic.document.application.DocumentContract.Section;
import com.java.semantic.document.application.DocumentDeadline;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.application.DocumentFailure.Code;
import com.java.semantic.document.config.DocumentStorage;
import com.java.semantic.document.mcp.DocumentResultWriter;
import com.java.semantic.model.source.SourceContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Owns the repository, reads exact regular blobs, and publishes no live Git handles. */
public final class GitDocumentLoader implements AutoCloseable {
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> CATALOG_FIELDS = Set.of("formatVersion", "documentRepositoryId", "documents");
    private static final Set<String> ROW_FIELDS = Set.of("path", "contentDigest", "primaryContext", "relatedContexts", "reviewStatus", "reviewReference");
    private static final JsonMapper INPUT = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(DocumentContract.MAX_CATALOG_BYTES).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DocumentStorage properties;
    private final DocumentResultWriter writer;
    private final Repository repository;

    public GitDocumentLoader(DocumentStorage properties, DocumentResultWriter writer) {
        this.properties = properties;
        this.writer = writer;
        rejectSymlinkPath(properties.gitDirectory());
        rejectSymlinkPath(properties.publicationFile().getParent());
        try {
            this.repository = new FileRepositoryBuilder().setGitDir(properties.gitDirectory().toFile()).setMustExist(true).build();
        } catch (IOException | IllegalArgumentException exception) {
            throw new DocumentFailure(Code.DOCUMENT_UNAVAILABLE);
        }
    }

    public Map<String, DocumentCatalog.RepositorySnapshot> load(String revision, Set<String> repositoryIds,
            DocumentDeadline deadline, int maximumDocuments, long maximumBytes, int maximumSections) {
        deadline.check();
        if (repositoryIds.isEmpty()) { throw invalid(); }
        try {
            for (String owner : repositoryIds) { new DocumentContext(properties.repositoryId(), owner, revision); }
        } catch (IllegalArgumentException exception) { throw invalid(); }
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(revision));
            if (!commit.name().equals(revision)) { throw invalid(); }
            JsonNode catalog = parseObject(blob(commit, "navigation-catalog.json", DocumentContract.MAX_CATALOG_BYTES), CATALOG_FIELDS);
            requireVersion(catalog);
            if (!text(catalog, "documentRepositoryId").equals(properties.repositoryId())) { throw invalid(); }
            JsonNode rows = catalog.path("documents");
            if (!rows.isArray()) { throw invalid(); }
            Set<String> paths = new HashSet<>();
            TreeMap<String, TreeMap<String, DocumentCatalog.Document>> documents = new TreeMap<>();
            for (String owner : repositoryIds) { documents.put(owner, new TreeMap<>()); }
            int documentCount = 0;
            int selectedCount = 0;
            for (JsonNode row : rows) {
                deadline.check();
                if (repositoryIds.contains(sourceContext(row.path("primaryContext")).repositoryId())) { selectedCount++; }
            }
            if (selectedCount > maximumDocuments) { throw capacity(); }
            long bytes = 0;
            int sections = 0;
            for (JsonNode row : rows) {
                deadline.check();
                fields(row, ROW_FIELDS);
                SourceContext primary = sourceContext(row.path("primaryContext"));
                String path = text(row, "path");
                validatePath(path, primary.repositoryId());
                if (!paths.add(path)) { throw invalid(); }
                String digest = text(row, "contentDigest");
                if (!DIGEST.matcher(digest).matches()) { throw invalid(); }
                List<SourceContext> related = relatedContexts(row.path("relatedContexts"), primary.repositoryId());
                ReviewStatus status;
                try { status = ReviewStatus.valueOf(text(row, "reviewStatus")); }
                catch (IllegalArgumentException exception) { throw invalid(); }
                Optional<String> reference = row.has("reviewReference") ? Optional.of(text(row, "reviewReference")) : Optional.empty();
                if (status == ReviewStatus.APPROVED && (reference.isEmpty() || reference.orElseThrow().isBlank())) { throw invalid(); }
                if (!repositoryIds.contains(primary.repositoryId())) { continue; }
                if (++documentCount > maximumDocuments) { throw capacity(); }
                DocumentContext context = new DocumentContext(properties.repositoryId(), primary.repositoryId(), revision);
                if (properties.collectionPurpose() == CollectionPurpose.APPROVED && status != ReviewStatus.APPROVED) { throw invalid(); }
                DocumentCatalog.Document document;
                try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
                    requireRegular(tree);
                    ObjectLoader object = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                    long size = object.getSize();
                    if (size > DocumentContract.MAX_DOCUMENT_BYTES || size > maximumBytes - bytes) { throw capacity(); }
                    if (size <= 0) { throw invalid(); }
                    byte[] contentBytes = object.getBytes(DocumentContract.MAX_DOCUMENT_BYTES);
                    if (!sha256(contentBytes).equals(digest)) { throw invalid(); }
                    String content = decode(contentBytes);
                    List<MarkdownSections.Span> spans = MarkdownSections.parse(content, maximumSections - sections, deadline);
                    document = new DocumentCatalog.Document(path, tree.getObjectId(0).name(), digest, contentBytes.length,
                            content, spans, primary, related, status, reference);
                }
                deadline.check();
                Section largest = document.sections().getFirst().section();
                int largestSize = writer.sectionWireBytes(largest);
                for (int index = 1; index < document.sections().size(); index++) {
                    deadline.check();
                    Section section = document.sections().get(index).section();
                    int size = writer.sectionWireBytes(section);
                    if (size > largestSize) { largest = section; largestSize = size; }
                }
                deadline.check();
                writer.success(document.readResult(context, largest, properties.collectionPurpose(),
                        PublicationStatus.UPDATE_REJECTED, Optional.of(Code.DOCUMENT_CAPACITY_EXCEEDED)));
                deadline.check();
                documents.get(primary.repositoryId()).put(path, document);
                bytes += document.byteLength();
                sections += document.sections().size();
            }
            deadline.check();
            TreeMap<String, DocumentCatalog.RepositorySnapshot> snapshots = new TreeMap<>();
            for (Map.Entry<String, TreeMap<String, DocumentCatalog.Document>> entry : documents.entrySet()) {
                if (entry.getValue().isEmpty()) { throw invalid(); }
                long ownerBytes = 0;
                int ownerSections = 0;
                for (DocumentCatalog.Document document : entry.getValue().values()) {
                    ownerBytes += document.byteLength();
                    ownerSections += document.sections().size();
                }
                snapshots.put(entry.getKey(), new DocumentCatalog.RepositorySnapshot(
                        new DocumentContext(properties.repositoryId(), entry.getKey(), revision),
                        entry.getValue(), ownerBytes, ownerSections));
            }
            return snapshots;
        } catch (DocumentFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new DocumentFailure(Code.DOCUMENT_UNAVAILABLE);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private byte[] blob(RevCommit commit, String path, int maximumBytes) throws IOException {
        try (TreeWalk tree = TreeWalk.forPath(repository, path, commit.getTree())) {
            requireRegular(tree);
            ObjectLoader object = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
            if (object.getSize() > maximumBytes) { throw capacity(); }
            return object.getBytes(maximumBytes);
        }
    }

    private static void requireRegular(TreeWalk tree) {
        if (Objects.isNull(tree) || !(tree.getFileMode(0).equals(FileMode.REGULAR_FILE)
                || tree.getFileMode(0).equals(FileMode.EXECUTABLE_FILE))) { throw invalid(); }
    }

    static JsonNode parseObject(byte[] bytes, Set<String> allowedFields) {
        try {
            JsonNode node = INPUT.readTree(bytes);
            fields(node, allowedFields);
            return node;
        } catch (DocumentFailure failure) { throw failure; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    static void fields(JsonNode node, Set<String> allowedFields) {
        if (Objects.isNull(node) || !node.isObject()) { throw invalid(); }
        for (String name : node.propertyNames()) {
            if (!allowedFields.contains(name)) { throw invalid(); }
        }
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isString()) { throw invalid(); }
        return value.stringValue();
    }

    static void requireVersion(JsonNode node) {
        JsonNode value = node.path("formatVersion");
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != 1) { throw invalid(); }
    }

    private static SourceContext sourceContext(JsonNode node) {
        fields(node, Set.of("repositoryId", "revision"));
        try { return new SourceContext(text(node, "repositoryId"), text(node, "revision")); }
        catch (IllegalArgumentException exception) { throw invalid(); }
    }

    private static List<SourceContext> relatedContexts(JsonNode node, String primaryRepository) {
        if (!node.isArray()) { throw invalid(); }
        if (node.size() > DocumentContract.MAX_RELATED_CONTEXTS) { throw capacity(); }
        List<SourceContext> contexts = new ArrayList<>(node.size());
        Set<String> repositories = new HashSet<>();
        repositories.add(primaryRepository);
        for (JsonNode related : node) {
            SourceContext context = sourceContext(related);
            if (!repositories.add(context.repositoryId())) { throw invalid(); }
            contexts.add(context);
        }
        return List.copyOf(contexts);
    }

    private static void validatePath(String path, String primaryRepository) {
        if (path.getBytes(StandardCharsets.UTF_8).length > DocumentContract.MAX_PATH_BYTES) { throw capacity(); }
        String directory = "repositories/" + primaryRepository + "/";
        if (!path.startsWith(directory) || !path.endsWith(".md") || path.indexOf('\\') >= 0
                || path.codePoints().anyMatch(code -> Character.isISOControl(code)
                        || code >= Character.MIN_SURROGATE && code <= Character.MAX_SURROGATE)) { throw invalid(); }
        if (path.indexOf('/', directory.length()) >= 0) { throw invalid(); }
        for (String component : path.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")) { throw invalid(); }
        }
    }

    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\r' && code != '\n' && code != '\t')) { throw invalid(); }
            return text;
        } catch (CharacterCodingException exception) { throw invalid(); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("Required SHA-256 unavailable", exception); }
    }

    static void rejectSymlinkPath(Path path) {
        for (Path cursor = path; Objects.nonNull(cursor); cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) { throw invalid(); }
        }
    }
    private static DocumentFailure invalid() { return new DocumentFailure(Code.DOCUMENT_CATALOG_INVALID); }
    private static DocumentFailure capacity() { return new DocumentFailure(Code.DOCUMENT_CAPACITY_EXCEEDED); }
    @Override public void close() { repository.close(); }
}
