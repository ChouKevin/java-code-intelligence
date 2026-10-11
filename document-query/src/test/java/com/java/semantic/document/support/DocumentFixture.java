package com.java.semantic.document.support;

import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.config.DocumentQueryProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Only service-owned synthetic Git objects; never executes target source or hooks. */
public final class DocumentFixture implements AutoCloseable {
    public static final String DOCUMENT_REPOSITORY = "navigation-test";
    public static final String SOURCE_REVISION = "a".repeat(40);
    public static final String GUIDE = "repositories/sample/guide.md";
    public final Path root;
    public final Path publication;
    public final Git git;
    public final ObjectMapper mapper = JsonMapper.builder().build();

    public DocumentFixture(Path root) throws Exception {
        this.root = Files.createDirectories(root);
        this.publication = root.resolve("../publication.json").normalize();
        this.git = Git.init().setDirectory(root.toFile()).call();
    }

    public Map<String, Object> row(String path, byte[] bytes) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", path);
        row.put("contentDigest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        row.put("primaryContext", Map.of("repositoryId", "sample", "revision", SOURCE_REVISION));
        row.put("relatedContexts", List.of());
        row.put("reviewStatus", "DRAFT");
        return row;
    }

    public String commit(Map<String, String> documents) throws Exception {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : documents.entrySet()) {
            bytes.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : bytes.entrySet()) {
            rows.add(row(entry.getKey(), entry.getValue()));
        }
        return commitBytes(bytes, rows);
    }

    public String commitBytes(Map<String, byte[]> documents, List<Map<String, Object>> rows) throws Exception {
        for (Map.Entry<String, byte[]> entry : documents.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        Map<String, Object> catalog = Map.of("formatVersion", 1, "documentRepositoryId", DOCUMENT_REPOSITORY,
                "documents", rows);
        Files.write(root.resolve("navigation-catalog.json"), mapper.writeValueAsBytes(catalog));
        git.add().addFilepattern(".").call();
        return git.commit().setMessage("owned document fixture").setAuthor("Fixture", "fixture@example.test")
                .setCommitter("Fixture", "fixture@example.test").setAllowEmpty(true).call().name();
    }

    public void publish(String active, String... retained) throws Exception {
        Path temporary = publication.resolveSibling("publication.next");
        Files.write(temporary, mapper.writeValueAsBytes(Map.of("formatVersion", 1,
                "documentRepositoryId", DOCUMENT_REPOSITORY, "activeRevision", active,
                "retainedRevisions", List.of(retained))));
        Files.move(temporary, publication, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public DocumentQueryProperties properties(CollectionPurpose purpose) {
        return new DocumentQueryProperties(DOCUMENT_REPOSITORY, root.resolve(".git"), publication, purpose, "fixture-read-token");
    }

    @Override
    public void close() { git.close(); }
}
