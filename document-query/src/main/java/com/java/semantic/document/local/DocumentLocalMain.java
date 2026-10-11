package com.java.semantic.document.local;

import com.java.semantic.document.application.DocumentContract;
import com.java.semantic.document.application.DocumentContract.CollectionPurpose;
import com.java.semantic.document.application.DocumentFailure;
import com.java.semantic.document.catalog.LocalDocumentPublisher;
import com.java.semantic.document.catalog.LocalDocumentPublisher.Failure;
import com.java.semantic.document.catalog.LocalDocumentPublisher.Result;
import com.java.semantic.document.catalog.LocalDocumentPublisher.UpdateRequest;
import com.java.semantic.document.catalog.LocalDocumentPublisher.RemoveRequest;
import com.java.semantic.model.repository.RepositoryId;
import com.java.semantic.document.config.DocumentStorage;
import com.java.semantic.model.source.SourceContext;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/** Explicit offline operator entry; never starts Spring or accepts an MCP request. */
public final class DocumentLocalMain {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_ARGUMENTS = 1 + 2 * (DocumentContract.MAX_DOCUMENTS
            + DocumentContract.MAX_RELATED_CONTEXTS + 3);

    private DocumentLocalMain() { }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    public static int run(String[] args, Map<String, String> environment, PrintStream out, PrintStream err) {
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                out.println("""
                        Local document Git operations (no remote, token or web server):
                          --document-local update --source repo@fullSHA --file name.md [--file ...]
                            [--related repo@fullSHA ...] [--review-reference text]
                          --document-local remove --repository-id repo --file name.md [--file ...] [--review-reference text]
                          --document-local status
                        Required environment: DOCUMENT_REPOSITORY_ID, DOCUMENT_GIT_DIRECTORY,
                          DOCUMENT_PUBLICATION_FILE, DOCUMENT_COLLECTION_PURPOSE (TEST_ONLY or APPROVED).
                        Local publication is not running-service verification; confirm through Document MCP.
                        """);
                return 0;
            }
            Parsed parsed = parse(args);
            DocumentStorage storage = new DocumentStorage(required(environment, "DOCUMENT_REPOSITORY_ID"),
                    Path.of(required(environment, "DOCUMENT_GIT_DIRECTORY")),
                    Path.of(required(environment, "DOCUMENT_PUBLICATION_FILE")),
                    CollectionPurpose.valueOf(required(environment, "DOCUMENT_COLLECTION_PURPOSE")));
            try (LocalDocumentPublisher publisher = new LocalDocumentPublisher(storage)) {
                Result result = switch (parsed.operation()) {
                    case "update" -> publisher.update(parsed.update().orElseThrow());
                    case "remove" -> publisher.remove(parsed.remove().orElseThrow());
                    case "status" -> publisher.status();
                    default -> throw new IllegalArgumentException();
                };
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("operation", result.operation());
                value.put("documentRepositoryId", result.documentRepositoryId());
                result.candidateRevision().ifPresent(revision -> value.put("candidateRevision", revision));
                result.repositoryId().ifPresent(owner -> value.put("repositoryId", owner));
                value.put("activeRevisions", result.activeRevisions());
                value.put("commitCreated", result.commitCreated());
                value.put("selectionChanged", result.selectionChanged());
                value.put("serviceVerified", false);
                out.println(JSON.writeValueAsString(value));
                return 0;
            }
        } catch (Failure failure) {
            Map<String, Object> value = error(failure.code(), failure.phase().name());
            failure.candidateRevision().ifPresent(revision -> value.put("candidateRevision", revision));
            out.println(JSON.writeValueAsString(value));
            return switch (failure.kind()) { case INVALID -> 2; case BUSY -> 3; case IO -> 4; };
        } catch (DocumentFailure failure) {
            out.println(JSON.writeValueAsString(error(failure.code().name(), "PREPARE")));
            return 2;
        } catch (IllegalArgumentException failure) {
            out.println(JSON.writeValueAsString(error("LOCAL_DOCUMENT_INVALID_ARGUMENT", "PREPARE")));
            return 2;
        } catch (Exception failure) {
            out.println(JSON.writeValueAsString(error("LOCAL_DOCUMENT_IO_FAILURE", "PREPARE")));
            return 4;
        }
    }

    private static Map<String, Object> error(String code, String phase) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("phase", phase);
        value.put("serviceVerified", false);
        return value;
    }

    private static Parsed parse(String[] args) {
        if (args.length == 0 || args.length > MAX_ARGUMENTS) { throw new IllegalArgumentException(); }
        String operation = args[0];
        if (!Set.of("update", "remove", "status").contains(operation)) { throw new IllegalArgumentException(); }
        if (operation.equals("status")) {
            if (args.length != 1) { throw new IllegalArgumentException(); }
            return new Parsed(operation, Optional.empty(), Optional.empty());
        }
        Map<String, List<String>> options = new LinkedHashMap<>();
        Set<String> allowed = operation.equals("update")
                ? Set.of("--source", "--file", "--related", "--review-reference")
                : Set.of("--repository-id", "--file", "--review-reference");
        for (int index = 1; index < args.length; index += 2) {
            if (!allowed.contains(args[index]) || index + 1 >= args.length || args[index + 1].isBlank()) {
                throw new IllegalArgumentException();
            }
            options.computeIfAbsent(args[index], ignored -> new ArrayList<>()).add(args[index + 1]);
        }
        List<String> files = unique(options.getOrDefault("--file", List.of()), DocumentContract.MAX_DOCUMENTS);
        if (files.isEmpty()) { throw new IllegalArgumentException(); }
        for (String file : files) {
            if (!file.endsWith(".md") || file.contains("/") || file.contains("\\")
                    || file.codePoints().anyMatch(Character::isISOControl)) { throw new IllegalArgumentException(); }
        }
        if (operation.equals("remove")) {
            String owner = new RepositoryId(single(options, "--repository-id", true).orElseThrow()).value();
            return new Parsed(operation, Optional.empty(), Optional.of(new RemoveRequest(owner, files,
                    single(options, "--review-reference", false))));
        }
        SourceContext primary = source(single(options, "--source", true).orElseThrow());
        List<SourceContext> related = new ArrayList<>();
        Set<String> repositories = new HashSet<>();
        repositories.add(primary.repositoryId());
        for (String value : unique(options.getOrDefault("--related", List.of()), DocumentContract.MAX_RELATED_CONTEXTS)) {
            SourceContext context = source(value);
            if (!repositories.add(context.repositoryId())) { throw new IllegalArgumentException(); }
            related.add(context);
        }
        UpdateRequest request = new UpdateRequest(primary, related, files,
                single(options, "--review-reference", false));
        return new Parsed(operation, Optional.of(request), Optional.empty());
    }

    private static Optional<String> single(Map<String, List<String>> values, String key, boolean required) {
        List<String> found = values.getOrDefault(key, List.of());
        if (found.size() > 1 || required && found.isEmpty()) { throw new IllegalArgumentException(); }
        return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
    }

    private static List<String> unique(List<String> values, int maximum) {
        if (values.size() > maximum || new HashSet<>(values).size() != values.size()) { throw new IllegalArgumentException(); }
        return List.copyOf(values);
    }

    private static SourceContext source(String value) {
        int separator = value.indexOf('@');
        if (separator <= 0 || separator != value.lastIndexOf('@')) { throw new IllegalArgumentException(); }
        return new SourceContext(value.substring(0, separator), value.substring(separator + 1));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (Objects.isNull(value) || value.isBlank()) { throw new IllegalArgumentException(); }
        return value;
    }

    private record Parsed(String operation, Optional<UpdateRequest> update, Optional<RemoveRequest> remove) { }
}
