package com.java.semantic.query.config;

import java.nio.file.Path;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Missing index storage is a normal source-only deployment; no filesystem or JDBC bootstrap here. */
@ConfigurationProperties(prefix = "semantic.query.index")
public record IndexAccessProperties(@DefaultValue("") String publishedRoot) {
    public IndexAccessProperties {
        publishedRoot = Objects.requireNonNull(publishedRoot, "index root configuration");
    }

    public Path resolveRoot(SourceAccessProperties source) {
        Path root = publishedRoot.isBlank() ? source.publishedRoot().getParent().resolve("index-published")
                : Path.of(publishedRoot);
        if (!root.isAbsolute()) throw new IllegalArgumentException("published index root must be absolute");
        root = root.normalize();
        if (root.startsWith(source.publishedRoot()) || source.publishedRoot().startsWith(root)) {
            throw new IllegalArgumentException("source and index namespaces must be separate");
        }
        return root;
    }
}
