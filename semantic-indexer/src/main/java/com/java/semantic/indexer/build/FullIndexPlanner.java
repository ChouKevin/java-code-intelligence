package com.java.semantic.indexer.build;

import com.java.semantic.indexer.repository.FixtureInputSelector;
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
import java.util.Objects;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Selects Java and MyBatis mapper XML inputs without depending on host path ordering. */
public final class FullIndexPlanner {
    private final FixtureInputSelector inputSelector;

    public FullIndexPlanner() {
        this(new FixtureInputSelector());
    }

    public FullIndexPlanner(FixtureInputSelector inputSelector) {
        this.inputSelector = Objects.requireNonNull(inputSelector, "input selector is required");
    }

    public FullIndexPlan plan(Path repositoryRoot) {
        Path root = Objects.requireNonNull(repositoryRoot, "repository root is required").toAbsolutePath().normalize();
        try {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                List<FullIndexPlan.SourceInput> sources = paths.filter(path -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> toSource(root, path))
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::orElseThrow)
                    .sorted(Comparator.comparing(FullIndexPlan.SourceInput::sourcePath))
                    .toList();
                return new FullIndexPlan(root, sources);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("unable to plan repository sources", exception);
        }
    }

    private java.util.Optional<FullIndexPlan.SourceInput> toSource(Path root, Path path) {
        Path relative = root.relativize(path);
        if (!inputSelector.includes(relative) || !supportedCandidate(path)) {
            return java.util.Optional.empty();
        }
        try {
            if (Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("supported source must not be a symlink or escape repository root: " + relative);
            }
            if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(root.toRealPath())) {
                return java.util.Optional.empty();
            }
            String content = Files.readString(path);
            if (!supported(path, content)) {
                return java.util.Optional.empty();
            }
            String sourcePath = relative.toString().replace(path.getFileSystem().getSeparator(), "/");
            return java.util.Optional.of(new FullIndexPlan.SourceInput(sourcePath, path,
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
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(content));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    return "mapper".equals(reader.getLocalName());
                }
            }
            return false;
        } catch (XMLStreamException exception) {
            return false;
        }
    }
}
