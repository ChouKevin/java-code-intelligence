package com.java.semantic.indexer.build;

import com.java.semantic.indexer.repository.RepositoryInputSelector;
import com.java.semantic.model.index.SourceArtifactDocument;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Selects Java and MyBatis mapper XML inputs without depending on host path ordering. */
public final class FullIndexPlanner {
    private final RepositoryInputSelector inputSelector;

    public FullIndexPlanner() {
        this(new RepositoryInputSelector());
    }

    public FullIndexPlanner(RepositoryInputSelector inputSelector) {
        this.inputSelector = Objects.requireNonNull(inputSelector, "input selector is required");
    }

    public FullIndexPlan plan(Path repositoryRoot) {
        Path root = Objects.requireNonNull(repositoryRoot, "repository root is required").toAbsolutePath().normalize();
        try {
            try (Stream<Path> paths = Files.walk(root)) {
                List<FullIndexPlan.SourceInput> sources = paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> toSource(root, path))
                    .filter(Optional::isPresent)
                    .map(Optional::orElseThrow)
                    .sorted(Comparator.comparing(FullIndexPlan.SourceInput::sourcePath))
                    .toList();
                return new FullIndexPlan(root, sources);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to plan repository sources", exception);
        }
    }

    public FullIndexPlan plan(Path repositoryRoot, List<Path> includedSourceRoots) {
        return plan(repositoryRoot, includedSourceRoots, Map.of());
    }

    public FullIndexPlan plan(Path repositoryRoot, List<Path> includedSourceRoots,
                              Map<String, String> effectiveCompilerOptions) {
        Path root = Objects.requireNonNull(repositoryRoot, "repository root is required").toAbsolutePath().normalize();
        List<Path> roots = List.copyOf(Objects.requireNonNull(includedSourceRoots, "included source roots are required"))
                .stream().map(path -> path.toAbsolutePath().normalize()).toList();
        for (Path sourceRoot : roots) {
            if (!sourceRoot.startsWith(root)) {
                throw new IllegalArgumentException("included source root escaped repository root");
            }
        }
        FullIndexPlan completePlan = plan(root);
        return new FullIndexPlan(root, completePlan.sources().stream()
                .filter(source -> roots.stream().anyMatch(rootPath -> source.path().startsWith(rootPath)))
                .toList(), roots, effectiveCompilerOptions);
    }

    private Optional<FullIndexPlan.SourceInput> toSource(Path root, Path path) {
        Path relative = root.relativize(path);
        if (!inputSelector.includes(relative) || !supportedCandidate(path)) {
            return Optional.empty();
        }
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("supported source must not be a symlink or escape repository root: " + relative);
            }
            if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(root.toRealPath())) {
                return Optional.empty();
            }
            String content = Files.readString(path);
            if (!supported(path, content)) {
                return Optional.empty();
            }
            String sourcePath = relative.toString().replace(path.getFileSystem().getSeparator(), "/");
            return Optional.of(new FullIndexPlan.SourceInput(sourcePath, path,
                    SourceArtifactDocument.create(content)));
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to read source input", exception);
        }
    }

    private static boolean supportedCandidate(Path path) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return filename.endsWith(".java") || filename.endsWith(".xml");
    }

    private static boolean supported(Path path, String content) {
        String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".java")) {
            return true;
        }
        if (!filename.endsWith(".xml")) {
            return false;
        }
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> new StringReader(""));
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(content));
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                        return "mapper".equals(reader.getLocalName());
                    }
                }
                return false;
            } finally {
                reader.close();
            }
        } catch (XMLStreamException exception) {
            return false;
        }
    }
}
