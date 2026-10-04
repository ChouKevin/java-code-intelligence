package com.java.semantic.query.config;

import com.java.semantic.model.repository.RepositoryId;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "semantic.query.source")
public record SourceAccessProperties(Path publishedRoot, Path rgExecutable,
        @DefaultValue List<String> allowedRepositories,
        @DefaultValue("65536") int readContentBytes,
        @DefaultValue("5s") Duration searchTimeout,
        @DefaultValue("2s") Duration readTimeout,
        @DefaultValue("2s") Duration listTimeout,
        @DefaultValue("2") int maxActiveSearches) {
    @ConstructorBinding
    public SourceAccessProperties {
        Objects.requireNonNull(publishedRoot, "published root");
        Objects.requireNonNull(rgExecutable, "rg executable");
        boolean absolutePaths = publishedRoot.isAbsolute() && rgExecutable.isAbsolute();
        publishedRoot = publishedRoot.normalize();
        rgExecutable = rgExecutable.normalize();
        allowedRepositories = List.copyOf(Objects.requireNonNull(allowedRepositories, "allowed repositories"));
        Set<String> ids = allowedRepositories.stream().map(value -> new RepositoryId(value).value()).collect(Collectors.toSet());
        if (!absolutePaths || ids.size() != allowedRepositories.size() || readContentBytes < 4 || readContentBytes > 65_536
                || searchTimeout.isNegative() || searchTimeout.isZero() || searchTimeout.compareTo(Duration.ofSeconds(5)) > 0
                || readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofSeconds(2)) > 0
                || listTimeout.isNegative() || listTimeout.isZero() || listTimeout.compareTo(Duration.ofSeconds(2)) > 0
                || maxActiveSearches < 1 || maxActiveSearches > 2
                || !Files.isDirectory(publishedRoot, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(rgExecutable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(rgExecutable)) {
            throw new IllegalArgumentException("Invalid source query configuration");
        }
    }
}
